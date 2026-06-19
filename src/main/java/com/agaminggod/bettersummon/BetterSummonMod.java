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
import java.util.concurrent.ThreadLocalRandom;
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
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterSummonMod implements ModInitializer {
	public static final String MOD_ID = "better_summon";

	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int MAX_REPEAT_COUNT = 10_000;
	private static final int ENTITIES_PER_TICK = 100;
	private static final int MAX_ACTIVE_TASKS = 20;
	private static final String COMMAND_NAME = "summon";
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

	private static final SimpleCommandExceptionType TASK_QUEUE_FULL_ERROR =
		new SimpleCommandExceptionType(Text.literal(
			"Too many pending batched /summon tasks; please wait for current tasks to finish."));

	private static final NbtCompound EMPTY_NBT = new NbtCompound();

	/**
	 * ACTIVE_TASKS is mutated only from the server thread: the command executor runs on
	 * the server thread, and {@link #processSpawnTasks} runs during END_SERVER_TICK.
	 * {@link ServerLifecycleEvents#SERVER_STOPPING} also fires on the server thread in
	 * Fabric. No external synchronization is needed; we keep it as a plain ArrayList.
	 */
	private static final List<SpawnTask> ACTIVE_TASKS = new ArrayList<>();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(
				CommandManager.literal(COMMAND_NAME)
					.requires(CommandManager.requirePermissionLevel((PermissionCheck) CommandManager.GAMEMASTERS_CHECK))
					.then(createEntityBranch(registryAccess))
			)
		);

		ServerTickEvents.END_SERVER_TICK.register(server -> processSpawnTasks());
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			// Clear any stragglers from a prior integrated-server session to avoid
			// stale ServerCommandSource references pointing at a defunct world.
			if (!ACTIVE_TASKS.isEmpty()) {
				LOGGER.info("Server starting; discarding {} stale spawn task(s) from prior session", ACTIVE_TASKS.size());
				ACTIVE_TASKS.clear();
			}
		});
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

		if (ACTIVE_TASKS.size() >= MAX_ACTIVE_TASKS) {
			throw TASK_QUEUE_FULL_ERROR.create();
		}

		ACTIVE_TASKS.add(new SpawnTask(source, request, baseCommand, repeatCount, details));
		int count = repeatCount;
		int batch = ENTITIES_PER_TICK;
		String suffix = details == null ? "" : details;
		source.sendFeedback(
			() -> Text.literal("Spawning " + count + " entities in batches of " + batch + suffix + "..."),
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
		for (int i = 0; i < repeatCount; i++) {
			try {
				NbtCompound nbtForCall = nbtForSummon(request);
				Entity entity = SummonCommand.summon(source, request.entityType(), request.position(), nbtForCall, request.initialize());
				if (entity == null) {
					LOGGER.warn("SummonCommand.summon() returned null at iteration {}", i + 1);
					break;
				}
				successfulExecutions++;
			} catch (CommandSyntaxException exception) {
				if (successfulExecutions == 0) {
					throw exception;
				}
				LOGGER.warn("Summon failed at iteration {}/{}: {}", i + 1, repeatCount, exception.getMessage());
				break;
			} catch (Exception exception) {
				LOGGER.error("Unexpected error at iteration {}/{}", i + 1, repeatCount, exception);
				break;
			}
		}

		if (successfulExecutions == 0) {
			return 0;
		}

		sendCompletionFeedback(source, baseCommand, successfulExecutions, repeatCount, details);
		return successfulExecutions;
	}

	private static void processSpawnTasks() {
		if (ACTIVE_TASKS.isEmpty()) {
			return;
		}

		Iterator<SpawnTask> iterator = ACTIVE_TASKS.iterator();
		while (iterator.hasNext()) {
			SpawnTask task = iterator.next();
			boolean completed = task.processNextBatch();
			if (completed) {
				sendCompletionFeedback(task.source, task.baseCommand, task.spawned, task.totalCount, task.details);
				iterator.remove();
			}
		}
	}

	private static void sendCompletionFeedback(
		ServerCommandSource source,
		String baseCommand,
		int successCount,
		int totalCount,
		String details
	) {
		String suffix = details == null ? "" : details;
		try {
			if (successCount < totalCount) {
				source.sendFeedback(
					() -> Text.literal(
						"Executed /" + baseCommand + " " + successCount + "/" + totalCount
							+ " time(s) before a failure stopped execution" + suffix + "."
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

	/**
	 * Returns an NbtCompound to pass to SummonCommand.summon. If the request NBT is
	 * empty we can safely share a single immutable empty compound instead of deep-copying
	 * on every iteration, since SummonCommand only writes to it when non-empty input
	 * is present anyway. This avoids 100 redundant empty-map clones per tick on the
	 * common case where no [nbt] argument was supplied.
	 */
	private static NbtCompound nbtForSummon(SummonRequest request) {
		NbtCompound source = request.nbt();
		if (source.isEmpty()) {
			return EMPTY_NBT;
		}
		return source.copy();
	}

	private static int randomInRangeInclusive(int min, int max) {
		if (min == max) {
			return min;
		}
		if (max == Integer.MAX_VALUE) {
			throw new IllegalArgumentException("max cannot be Integer.MAX_VALUE for inclusive range (would overflow)");
		}
		return ThreadLocalRandom.current().nextInt(min, max + 1);
	}

	private static final class SpawnTask {
		final ServerCommandSource source;
		final SummonRequest request;
		final String baseCommand;
		final int totalCount;
		final String details;
		int spawned;

		SpawnTask(ServerCommandSource source, SummonRequest request, String baseCommand, int totalCount, String details) {
			this.source = source;
			this.request = request;
			this.baseCommand = baseCommand;
			this.totalCount = totalCount;
			this.details = details;
			this.spawned = 0;
		}

		boolean processNextBatch() {
			// Bail out if the originating server is no longer running to avoid
			// calling into a stopped/defunct world with a stale ServerCommandSource.
			if (source.getServer() == null || !source.getServer().isRunning()) {
				LOGGER.warn("Cancelling batched summon at {}/{}: source server is no longer running", spawned, totalCount);
				return true;
			}
			int batchEnd = Math.min(spawned + ENTITIES_PER_TICK, totalCount);
			for (int i = spawned; i < batchEnd; i++) {
				try {
					NbtCompound nbtForCall = nbtForSummon(request);
					Entity entity = SummonCommand.summon(source, request.entityType(), request.position(), nbtForCall, request.initialize());
					if (entity == null) {
						LOGGER.warn("SummonCommand.summon() returned null at iteration {}/{}", i + 1, totalCount);
						return true;
					}
					spawned++;
				} catch (Exception exception) {
					LOGGER.warn("Batched summon failed at iteration {}/{}: {}", i + 1, totalCount, exception.getMessage());
					return true;
				}
			}
			return spawned >= totalCount;
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
