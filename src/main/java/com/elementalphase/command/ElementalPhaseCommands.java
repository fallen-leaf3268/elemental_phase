package com.elementalphase.command;

import com.elementalphase.api.ElementalPhaseApi;
import com.elementalphase.capability.ElementalCapabilities;
import com.elementalphase.data.ElementDataManager;
import com.elementalphase.state.ElementRuntimeState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public final class ElementalPhaseCommands {
    private ElementalPhaseCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("elementalphase")
                .requires(source -> source.hasPermission(2));
        root.then(Commands.literal("inspect").then(Commands.argument("entity", EntityArgument.entity())
                .executes(context -> inspect(context.getSource(), EntityArgument.getEntity(context, "entity")))));
        root.then(Commands.literal("reload").executes(context -> {
            context.getSource().getServer().getCommands().performPrefixedCommand(context.getSource(), "reload");
            return 1;
        }));
        root.then(Commands.literal("apply").then(Commands.argument("entity", EntityArgument.entity())
                .then(Commands.argument("element", ResourceLocationArgument.id())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.000001D, 1_000_000.0D))
                                .executes(context -> apply(context.getSource(), EntityArgument.getEntity(context, "entity"),
                                        ResourceLocationArgument.getId(context, "element"),
                                        DoubleArgumentType.getDouble(context, "amount")))))));
        root.then(Commands.literal("remove").then(Commands.argument("entity", EntityArgument.entity())
                .then(Commands.argument("element", ResourceLocationArgument.id())
                        .executes(context -> remove(context.getSource(), EntityArgument.getEntity(context, "entity"),
                                ResourceLocationArgument.getId(context, "element"))))));
        root.then(Commands.literal("book").then(Commands.argument("element", ResourceLocationArgument.id())
                .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggestResource(
                        ElementDataManager.snapshot().elements().keySet(), builder))
                .executes(context -> giveBook(context.getSource(), context.getSource().getPlayerOrException(),
                        ResourceLocationArgument.getId(context, "element")))
                .then(Commands.argument("player", EntityArgument.player()).executes(context ->
                        giveBook(context.getSource(), EntityArgument.getPlayer(context, "player"),
                                ResourceLocationArgument.getId(context, "element"))))));
        dispatcher.register(root);
    }

    private static int giveBook(CommandSourceStack source, net.minecraft.server.level.ServerPlayer player,
                                ResourceLocation element) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!ElementDataManager.snapshot().elements().containsKey(element)) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.translatable("command.elemental_phase.unknown_element", element)).create();
        }
        if (com.elementalphase.registry.ModEnchantments.forElement(element).isEmpty()) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.translatable("command.elemental_phase.unregistered_enchantment", element)).create();
        }
        var book = com.elementalphase.enchantment.ElementEnchantmentData.createBook(element);
        if (!player.getInventory().add(book)) player.drop(book, false);
        player.containerMenu.broadcastChanges();
        source.sendSuccess(() -> Component.translatable("command.elemental_phase.book_given",
                element, player.getDisplayName()), true);
        return 1;
    }

    private static int inspect(CommandSourceStack source, Entity entity) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        LivingEntity living = living(source, entity);
        long now = living.level().getGameTime();
        var resolver = new com.elementalphase.profile.EntityProfileResolver();
        var snapshot = ElementDataManager.snapshot();
        var profile = resolver.resolve(living, snapshot);
        source.sendSuccess(() -> Component.translatable("command.elemental_phase.inspect_profile",
                profile.profileId().map(id -> Component.literal(id.toString()))
                        .orElseGet(() -> Component.translatable("command.elemental_phase.none")), profile.priority()), false);
        ElementalCapabilities.get(living).ifPresent(state -> {
            resolver.initializeIfNeeded(living, state, snapshot);
            for (ResourceLocation id : state.knownElements(now)) {
                ElementRuntimeState runtime = state.state(id);
                double preset = runtime == null ? 0.0D : runtime.permanentPreset();
                double current = runtime == null ? 0.0D : runtime.permanentCurrent();
                double temporary = runtime == null ? 0.0D : runtime.temporaryAmount();
                double effective = runtime == null ? 0.0D : runtime.effectiveAmount(now);
                Component recovery = remaining(runtime == null ? Long.MIN_VALUE : runtime.recoveryAt(), now);
                Component expiry = remaining(runtime == null ? Long.MIN_VALUE : runtime.temporaryExpiresAt(), now);
                Component cooldown = remaining(state.elementApplicationCooldownUntil(id, now), now);
                source.sendSuccess(() -> Component.translatable("command.elemental_phase.inspect_line", id,
                        preset, current, temporary, effective, recovery, expiry, cooldown, state.resistance(id)), false);
            }
            state.reactionResistances().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> source.sendSuccess(() -> Component.translatable(
                            "command.elemental_phase.inspect_reaction_resistance", entry.getKey(), entry.getValue()), false));
        });
        return 1;
    }

    private static int apply(CommandSourceStack source, Entity entity, ResourceLocation element, double amount) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        LivingEntity living = living(source, entity);
        ElementRuntimeState.ApplyResult result = ElementalPhaseApi.applyTemporary(living, element, amount);
        source.sendSuccess(() -> Component.translatable("command.elemental_phase.apply_result", result), false);
        return result.changed() ? 1 : 0;
    }

    private static int remove(CommandSourceStack source, Entity entity, ResourceLocation element) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        boolean removed = ElementalPhaseApi.removeElement(living(source, entity), element);
        source.sendSuccess(() -> Component.translatable("command.elemental_phase.remove_result", removed), false);
        return removed ? 1 : 0;
    }

    private static LivingEntity living(CommandSourceStack source, Entity entity) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (entity instanceof LivingEntity living && entity.level() == source.getLevel()) {
            return living;
        }
        throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                Component.translatable("command.elemental_phase.invalid_target")).create();
    }

    private static Component remaining(long deadline, long now) {
        return deadline == Long.MIN_VALUE ? Component.translatable("command.elemental_phase.none")
                : Component.literal(Long.toString(Math.max(0L, deadline - now)));
    }
}
