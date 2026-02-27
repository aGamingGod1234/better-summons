package com.agaminggod.bettersummon;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.NbtCompoundArgumentType;
import net.minecraft.command.argument.RegistryEntryReferenceArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.command.permission.PermissionCheck;
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

	private static final SecureRandom SECURE_RANDOM = createSecureRandom();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(
				CommandManager.literal(COMMAND_NAME)
					.requires(CommandManager.requirePermissionLevel((PermissionCheck) CommandManager.GAMEMASTERS_CHECK))
					.then(createEntityBranch(registryAccess))
			)
		);

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

		int selectedCount = secureRandomInRangeInclusive(min, max);
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

		int successfulExecutions = 0;
		for (int i = 0; i < repeatCount; i++) {
			try {
				SummonCommand.summon(source, request.entityType(), request.position(), request.nbt(), request.initialize());
				successfulExecutions++;
			} catch (CommandSyntaxException exception) {
				if (successfulExecutions == 0) {
					throw exception;
				}
				break;
			}
		}

		if (successfulExecutions == 0) {
			return 0;
		}

		String suffix = details == null ? "" : details;
		if (successfulExecutions < repeatCount) {
			int requestedCount = repeatCount;
			int executedCount = successfulExecutions;
			source.sendFeedback(
				() -> Text.literal(
					"Executed /" + baseCommand + " " + executedCount + "/" + requestedCount
						+ " time(s) before a failure stopped execution" + suffix + "."
				),
				false
			);
			return executedCount;
		}

		int executedCount = successfulExecutions;
		source.sendFeedback(
			() -> Text.literal("Executed /" + baseCommand + " " + executedCount + " time(s)" + suffix + "."),
			false
		);

		return successfulExecutions;
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
			if (baseNodeName.equals(parsedNode.getNode().getName())) {
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

	private static int secureRandomInRangeInclusive(int min, int max) {
		if (min == max) {
			return min;
		}
		return SECURE_RANDOM.nextInt(min, max + 1);
	}

	private static SecureRandom createSecureRandom() {
		try {
			return SecureRandom.getInstanceStrong();
		} catch (NoSuchAlgorithmException exception) {
			LOGGER.warn("Strong SecureRandom unavailable, falling back to default SecureRandom", exception);
			return new SecureRandom();
		}
	}

	private record SummonRequest(
		RegistryEntry.Reference<EntityType<?>> entityType,
		Vec3d position,
		NbtCompound nbt,
		boolean initialize
	) {
	}
}
