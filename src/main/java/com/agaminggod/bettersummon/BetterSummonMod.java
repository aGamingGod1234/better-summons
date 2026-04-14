package com.agaminggod.bettersummon;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.NbtCompoundArgumentType;
import net.minecraft.command.argument.RegistryEntryReferenceArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.SummonCommand;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterSummonMod implements ModInitializer {
	public static final String MOD_ID = "better_summon";

	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int MAX_REPEAT_COUNT = 10_000;
	private static final int ENTITIES_PER_TICK = 100;
	private static final int PROGRESS_FEEDBACK_INTERVAL_TICKS = 20; // ~1s @ 20 TPS
	private static final String COMMAND_NAME = "summon";
	private static final String CANCEL_COMMAND_NAME = "summoncancel";
	private static final String STATUS_COMMAND_NAME = "summonstatus";
	private static final String ARG_ENTITY = "entity";
	private static final String ARG_POS = "pos";
	private static final String ARG_NBT = "nbt";
	private static final String ARG_REPEAT_COUNT = "repeat_count";
	private static final String ARG_MIN = "min";
	private static final String ARG_MAX = "max";
	private static final String LITERAL_RANDOM = "random";

	private static final SimpleCommandExceptionType BASE_COMMAND_RESOLUTION_ERROR =
		new SimpleCommandExceptionType(Text.literal("Failed to resolve summon base command."));

	private static final Dynamic2CommandExceptionType INVALID_RANDOM_RANGE_ERROR =
		new Dynamic2CommandExceptionType((min, max) ->
			Text.literal("Invalid range: min (" + min + ") must be less than or equal to max (" + max + ")."));

	private static final List<SpawnTask> ACTIVE_TASKS = new ArrayList<>();
	private static final AtomicInteger TASK_ID_SEQ = new AtomicInteger(1);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				CommandManager.literal(COMMAND_NAME)
					.requires(CommandManager.requirePermissionLevel((PermissionCheck) CommandManager.GAMEMASTERS_CHECK))
					.then(createEntityBranch(registryAccess))
			);

			dispatcher.register(
				CommandManager.literal(CANCEL_COMMAND_NAME)
					.requires(CommandManager.requirePermissionLevel((PermissionCheck) CommandManager.GAMEMASTERS_CHECK))
					.executes(BetterSummonMod::executeCancelOwn)
					.then(CommandManager.literal("all").executes(BetterSummonMod::executeCancelAll))
			);

			dispatcher.register(
				CommandManager.literal(STATUS_COMMAND_NAME)
					.requires(CommandManager.requirePermissionLevel((PermissionCheck) CommandManager.GAMEMASTERS_CHECK))
					.executes(BetterSummonMod::executeStatus)
			);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> processSpawnTasks());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (!ACTIVE_TASKS.isEmpty()) {
				LOGGER.info("Server stopping, cancelling {} pending spawn task(s)", ACTIVE_TASKS.size());
				ACTIVE_TASKS.clear();
			}
		});

		LOGGER.info("Better /summon extensions loaded.");
	}

	private static RequiredArgumentBuilder<ServerCommandSource, RegistryEntry.Reference<EntityType<?>>> createEntityBranch(
		CommandRegistryAccess registryAccess
	) {
		RequiredArgumentBuilder<ServerCommandSource, RegistryEntry.Reference<EntityType<?>>> entityArgument =
			CommandManager.argument(
				ARG_ENTITY,
				RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.ENTITY_TYPE)
			);

		attachRepeatNodes(entityArgument, ARG_ENTITY);

		RequiredArgumentBuilder<ServerCommandSource, ?> positionArgument =
			CommandManager.argument(ARG_POS, Vec3ArgumentType.vec3());
		attachRepeatNodes(positionArgument, ARG_POS);

		RequiredArgumentBuilder<ServerCommandSource, ?> nbtArgument =
			CommandManager.argument(ARG_NBT, NbtCompoundArgumentType.nbtCompound());
		attachRepeatNodes(nbtArgument, ARG_NBT);

		positionArgument.then(nbtArgument);
		entityArgument.then(positionArgument);

		return entityArgument;
	}

	private static void attachRepeatNodes(ArgumentBuilder<ServerCommandSource, ?> parentNode, String baseNodeName) {
		parentNode.then(
			CommandManager.argument(ARG_REPEAT_COUNT, IntegerArgumentType.integer(1, MAX_REPEAT_COUNT))
				.executes(context -> executeFixedRepeat(context, baseNodeName))
		);

		parentNode.then(
			CommandManager.literal(LITERAL_RANDOM)
				.then(CommandManager.argument(ARG_MIN, IntegerArgumentType.integer(1, MAX_REPEAT_COUNT))
					.then(CommandManager.argument(ARG_MAX, IntegerArgumentType.integer(1, MAX_REPEAT_COUNT))
						.executes(context -> executeRandomRepeat(context, baseNodeName))))
		);
	}

	private static int executeFixedRepeat(CommandContext<ServerCommandSource> context, String baseNodeName) throws CommandSyntaxException {
		int repeatCount = IntegerArgumentType.getInteger(context, ARG_REPEAT_COUNT);
		return executeRepeats(context, baseNodeName, repeatCount, null);
	}

	private static int executeRandomRepeat(CommandContext<ServerCommandSource> context, String baseNodeName) throws CommandSyntaxException {
		int min = IntegerArgumentType.getInteger(context, ARG_MIN);
		int max = IntegerArgumentType.getInteger(context, ARG_MAX);
		if (min > max) {
			throw INVALID_RANDOM_RANGE_ERROR.create(min, max);
		}

		int selectedCount = randomInRangeInclusive(min, max);
		String randomDetails = " (randomly selected in [" + min + ", " + max + "])";
		return executeRepeats(context, baseNodeName, selectedCount, randomDetails);
	}

	private static int executeRepeats(
		CommandContext<ServerCommandSource> context,
		String baseNodeName,
		int repeatCount,
		String details
	) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		SummonRequest request = buildSummonRequest(context, baseNodeName);
		String baseCommand = extractBaseCommand(context, baseNodeName);

		if (repeatCount <= ENTITIES_PER_TICK) {
			return spawnImmediate(source, request, baseCommand, repeatCount, details);
		}

		int taskId = TASK_ID_SEQ.getAndIncrement();
		ACTIVE_TASKS.add(new SpawnTask(taskId, source, request, baseCommand, repeatCount, details));
		final int totalForMessage = repeatCount;
		String suffix = details == null ? "" : details;
		source.sendFeedback(
			() -> Text.literal(
				"[task #" + taskId + "] Spawning " + totalForMessage + " entities in batches of "
					+ ENTITIES_PER_TICK + suffix + ". Use /" + STATUS_COMMAND_NAME + " or /"
					+ CANCEL_COMMAND_NAME + " to manage."
			),
			false
		);
		return repeatCount;
	}

	private static int spawnImmediate(
		ServerCommandSource source,
		SummonRequest request,
		String baseCommand,
		int repeatCount,
		String details
	) throws CommandSyntaxException {
		int successfulExecutions = 0;
		String failureReason = null;
		for (int i = 0; i < repeatCount; i++) {
			try {
				NbtCompound nbtCopy = request.nbt().copy();
				Entity entity = SummonCommand.summon(source, request.entityType(), request.position(), nbtCopy, request.initialize());
				if (entity == null) {
					failureReason = "summon returned no entity (type may be disabled)";
					LOGGER.warn("SummonCommand.summon() returned null at iteration {}", i + 1);
					break;
				}
				successfulExecutions++;
			} catch (CommandSyntaxException exception) {
				if (successfulExecutions == 0) {
					throw exception;
				}
				failureReason = exception.getMessage();
				LOGGER.warn("Summon failed at iteration {}/{}: {}", i + 1, repeatCount, exception.getMessage());
				break;
			} catch (Exception exception) {
				failureReason = exception.getClass().getSimpleName()
					+ (exception.getMessage() == null ? "" : ": " + exception.getMessage());
				LOGGER.error("Unexpected error at iteration {}/{}", i + 1, repeatCount, exception);
				break;
			}
		}

		if (successfulExecutions == 0) {
			return 0;
		}

		sendCompletionFeedback(source, baseCommand, successfulExecutions, repeatCount, details, failureReason);
		return successfulExecutions;
	}

	private static void processSpawnTasks() {
		if (ACTIVE_TASKS.isEmpty()) {
			return;
		}

		Iterator<SpawnTask> iterator = ACTIVE_TASKS.iterator();
		while (iterator.hasNext()) {
			SpawnTask task = iterator.next();
			if (!task.isSourceStillValid()) {
				LOGGER.info("Aborting task #{}: source is no longer valid ({} spawned of {})",
					task.id, task.spawned, task.totalCount);
				iterator.remove();
				continue;
			}
			boolean completed = task.processNextBatch();
			if (completed) {
				sendCompletionFeedback(task.source, task.baseCommand, task.spawned, task.totalCount, task.details, task.failureReason);
				iterator.remove();
			} else {
				task.maybeSendProgress();
			}
		}
	}

	private static int executeCancelOwn(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		UUID ownerId = resolveOwnerId(source);
		int cancelled = 0;
		Iterator<SpawnTask> iterator = ACTIVE_TASKS.iterator();
		while (iterator.hasNext()) {
			SpawnTask task = iterator.next();
			if (Objects.equals(task.ownerId, ownerId)) {
				iterator.remove();
				cancelled++;
				final int spawned = task.spawned;
				final int total = task.totalCount;
				final int id = task.id;
				task.source.sendFeedback(
					() -> Text.literal("[task #" + id + "] Cancelled after " + spawned + "/" + total + " entities."),
					false
				);
			}
		}
		final int finalCancelled = cancelled;
		source.sendFeedback(
			() -> Text.literal("Cancelled " + finalCancelled + " pending summon task(s)."),
			false
		);
		return cancelled;
	}

	private static int executeCancelAll(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		int cancelled = ACTIVE_TASKS.size();
		if (cancelled > 0) {
			for (SpawnTask task : ACTIVE_TASKS) {
				final int spawned = task.spawned;
				final int total = task.totalCount;
				final int id = task.id;
				task.source.sendFeedback(
					() -> Text.literal("[task #" + id + "] Cancelled after " + spawned + "/" + total + " entities."),
					false
				);
			}
			ACTIVE_TASKS.clear();
		}
		final int finalCancelled = cancelled;
		source.sendFeedback(
			() -> Text.literal("Cancelled " + finalCancelled + " pending summon task(s)."),
			false
		);
		return cancelled;
	}

	private static int executeStatus(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		if (ACTIVE_TASKS.isEmpty()) {
			source.sendFeedback(() -> Text.literal("No pending summon tasks."), false);
			return 0;
		}
		List<SpawnTask> snapshot = new ArrayList<>(ACTIVE_TASKS);
		source.sendFeedback(() -> Text.literal("Pending summon tasks: " + snapshot.size()), false);
		for (SpawnTask task : snapshot) {
			int pct = task.totalCount == 0 ? 100 : (int) ((task.spawned * 100L) / task.totalCount);
			String line = "[task #" + task.id + "] " + task.ownerName
				+ " — " + task.spawned + "/" + task.totalCount + " (" + pct + "%)";
			source.sendFeedback(() -> Text.literal(line), false);
		}
		return snapshot.size();
	}

	private static UUID resolveOwnerId(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		return player == null ? null : player.getUuid();
	}

	private static String resolveOwnerName(ServerCommandSource source) {
		return source.getName();
	}

	private static void sendCompletionFeedback(
		ServerCommandSource source,
		String baseCommand,
		int successCount,
		int totalCount,
		String details,
		String failureReason
	) {
		String suffix = details == null ? "" : details;
		String reasonSuffix = failureReason == null ? "" : " — reason: " + failureReason;
		try {
			if (successCount < totalCount) {
				source.sendFeedback(
					() -> Text.literal(
						"Executed /" + baseCommand + " " + successCount + "/" + totalCount
							+ " time(s) before a failure stopped execution" + suffix + reasonSuffix + "."
					),
					false
				);
			} else {
				source.sendFeedback(
					() -> Text.literal("Executed /" + baseCommand + " " + successCount + " time(s)" + suffix + "."),
					false
				);
			}
		} catch (Exception exception) {
			LOGGER.warn("Failed to send completion feedback", exception);
		}
	}

	private static SummonRequest buildSummonRequest(CommandContext<ServerCommandSource> context, String baseNodeName)
		throws CommandSyntaxException {
		RegistryEntry.Reference<EntityType<?>> entityType =
			RegistryEntryReferenceArgumentType.getSummonableEntityType(context, ARG_ENTITY);

		if (ARG_ENTITY.equals(baseNodeName)) {
			return new SummonRequest(entityType, context.getSource().getPosition(), new NbtCompound(), true);
		}

		if (ARG_POS.equals(baseNodeName)) {
			Vec3d position = Vec3ArgumentType.getVec3(context, ARG_POS);
			return new SummonRequest(entityType, position, new NbtCompound(), true);
		}

		if (ARG_NBT.equals(baseNodeName)) {
			Vec3d position = Vec3ArgumentType.getVec3(context, ARG_POS);
			NbtCompound nbt = NbtCompoundArgumentType.getNbtCompound(context, ARG_NBT);
			return new SummonRequest(entityType, position, nbt, false);
		}

		throw BASE_COMMAND_RESOLUTION_ERROR.create();
	}

	private static String extractBaseCommand(CommandContext<ServerCommandSource> context, String baseNodeName)
		throws CommandSyntaxException {
		int endIndex = -1;
		for (ParsedCommandNode<ServerCommandSource> parsedNode : context.getNodes()) {
			if (parsedNode.getNode() != null
				&& baseNodeName.equals(parsedNode.getNode().getName())
				&& parsedNode.getRange() != null) {
				endIndex = parsedNode.getRange().getEnd();
			}
		}

		if (endIndex <= 0 || endIndex > context.getInput().length()) {
			throw BASE_COMMAND_RESOLUTION_ERROR.create();
		}

		String rawBaseCommand = context.getInput().substring(0, endIndex).trim();
		if (rawBaseCommand.isEmpty()) {
			throw BASE_COMMAND_RESOLUTION_ERROR.create();
		}

		return CommandManager.stripLeadingSlash(rawBaseCommand);
	}

	private static int randomInRangeInclusive(int min, int max) {
		if (min == max) {
			return min;
		}
		// Argument bounds (1..MAX_REPEAT_COUNT) guarantee max + 1 cannot overflow.
		return ThreadLocalRandom.current().nextInt(min, max + 1);
	}

	private static final class SpawnTask {
		final int id;
		final ServerCommandSource source;
		final SummonRequest request;
		final String baseCommand;
		final int totalCount;
		final String details;
		final UUID ownerId;
		final String ownerName;
		final ServerWorld world;
		int spawned;
		int ticksSinceLastProgress;
		String failureReason;

		SpawnTask(int id, ServerCommandSource source, SummonRequest request, String baseCommand, int totalCount, String details) {
			this.id = id;
			this.source = source;
			this.request = request;
			this.baseCommand = baseCommand;
			this.totalCount = totalCount;
			this.details = details;
			this.ownerId = resolveOwnerId(source);
			this.ownerName = resolveOwnerName(source);
			this.world = source.getWorld();
			this.spawned = 0;
			this.ticksSinceLastProgress = 0;
			this.failureReason = null;
		}

		boolean isSourceStillValid() {
			// If a real player originated the command, ensure they are still online.
			ServerPlayerEntity player = source.getPlayer();
			if (ownerId != null && player == null) {
				failureReason = "command source (player) disconnected";
				return false;
			}
			// If the captured world is no longer reachable, abort.
			if (world != null && source.getServer().getWorld(world.getRegistryKey()) == null) {
				failureReason = "world unloaded";
				return false;
			}
			return true;
		}

		boolean processNextBatch() {
			int batchEnd = Math.min(spawned + ENTITIES_PER_TICK, totalCount);
			for (int i = spawned; i < batchEnd; i++) {
				try {
					NbtCompound nbtCopy = request.nbt().copy();
					Entity entity = SummonCommand.summon(source, request.entityType(), request.position(), nbtCopy, request.initialize());
					if (entity == null) {
						failureReason = "summon returned no entity (type may be disabled)";
						LOGGER.warn("SummonCommand.summon() returned null at iteration {}/{}", i + 1, totalCount);
						return true;
					}
					spawned++;
				} catch (CommandSyntaxException exception) {
					failureReason = exception.getMessage();
					LOGGER.warn("Batched summon CommandSyntaxException at iteration {}/{}: {}", i + 1, totalCount, exception.getMessage());
					return true;
				} catch (Exception exception) {
					failureReason = exception.getClass().getSimpleName()
						+ (exception.getMessage() == null ? "" : ": " + exception.getMessage());
					LOGGER.warn("Batched summon failed at iteration {}/{}: {}", i + 1, totalCount, exception.getMessage());
					return true;
				}
			}
			return spawned >= totalCount;
		}

		void maybeSendProgress() {
			ticksSinceLastProgress++;
			if (ticksSinceLastProgress < PROGRESS_FEEDBACK_INTERVAL_TICKS) {
				return;
			}
			ticksSinceLastProgress = 0;
			final int snapshotSpawned = spawned;
			final int snapshotTotal = totalCount;
			final int taskId = id;
			int pct = snapshotTotal == 0 ? 100 : (int) ((snapshotSpawned * 100L) / snapshotTotal);
			final int snapshotPct = pct;
			try {
				source.sendFeedback(
					() -> Text.literal(
						"[task #" + taskId + "] " + snapshotSpawned + "/" + snapshotTotal + " (" + snapshotPct + "%)"
					),
					false
				);
			} catch (Exception exception) {
				LOGGER.warn("Failed to send progress feedback for task #{}", taskId, exception);
			}
		}
	}

	private record SummonRequest(
		RegistryEntry.Reference<EntityType<?>> entityType,
		Vec3d position,
		NbtCompound nbt,
		boolean initialize
	) {
		SummonRequest {
			Objects.requireNonNull(entityType, "entityType must not be null");
			Objects.requireNonNull(position, "position must not be null");
			Objects.requireNonNull(nbt, "nbt must not be null");
		}
	}
}
