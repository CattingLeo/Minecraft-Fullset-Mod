package com.example.fullset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FullsetMod implements ClientModInitializer {

    public static final String MOD_ID = "fullset";

    // Text colours (26.2 styles components via Style#withColor rather than ChatFormatting).
    private static final Style GREEN = Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55));
    private static final Style RED = Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555));
    private static final Style YELLOW = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));

    // Our stored slot layout: 0-8 hotbar, 9-35 inventory, 36 feet, 37 legs,
    // 38 chest, 39 head, 40 offhand.
    private static final int SIZE = 41;
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    // name -> 41 stacks. Stored globally in .minecraft/config (shared across worlds/servers).
    private static final Map<String, List<ItemStack>> STORE = new HashMap<>();
    private static boolean loaded = false;

    // A restore in progress: waiting for creative mode, then applying slots a few per tick.
    private static PendingRestore pending = null;
    private static final int APPLY_PER_TICK = 6;
    // How many enchantments the last restore had to drop (server didn't have them).
    private static int lastDropped = 0;

    // Result banner the HUD shows for a few seconds after a restore finishes/fails/times
    // out. The vanilla action bar is too easy to clobber with other chat/pickup messages,
    // so this is a more reliable way to see the outcome.
    private static Component resultMessage = null;
    private static int resultTicksLeft = 0;
    private static final int RESULT_DURATION_TICKS = 60; // ~3 seconds

    private static Path storeFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("loadouts.json");
    }

    private static final SuggestionProvider<FabricClientCommandSource> SAVED_NAMES = (ctx, builder) -> {
        ensureLoaded(ctx.getSource().getClient().getConnection());
        for (String n : STORE.keySet()) {
            builder.suggest(n);
        }
        return builder.buildFuture();
    };

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(buildSave("setfullset"));
            dispatcher.register(buildRestore("fullset"));
        });
        // Drives the multiplayer restore once the server confirms creative mode.
        ClientTickEvents.END_CLIENT_TICK.register(FullsetMod::tickPending);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "restore_hud"), FullsetMod::renderHud);
    }

    // ---- HUD ----

    private static void renderHud(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        int centerX = guiGraphics.guiWidth() / 2;

        PendingRestore p = pending;
        if (p != null) {
            int barWidth = 160, barHeight = 6;
            int barX = centerX - barWidth / 2;
            int barY = 16;

            Component label = p.creativeConfirmed
                    ? Component.literal("Fullset: restoring '" + p.name + "'  " + p.applied + "/" + SIZE)
                    : Component.literal("Fullset: waiting for creative mode...");

            guiGraphics.fill(barX - 4, barY - 13, barX + barWidth + 4, barY + barHeight + 4, 0x90000000);
            guiGraphics.centeredText(client.font, label, centerX, barY - 10, 0xFFFFFF);

            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0x55FFFFFF);
            if (p.creativeConfirmed) {
                int filled = Math.round(barWidth * ((float) p.applied / SIZE));
                if (filled > 0) {
                    guiGraphics.fill(barX, barY, barX + filled, barY + barHeight, 0xFF55FF55);
                }
            }
            guiGraphics.outline(barX - 1, barY - 1, barWidth + 2, barHeight + 2, 0xFFFFFFFF);
            return;
        }

        if (resultTicksLeft > 0 && resultMessage != null) {
            int alpha = resultTicksLeft < 15 ? Math.round(255 * (resultTicksLeft / 15f)) : 255;
            guiGraphics.fill(centerX - 100, 3, centerX + 100, 17, (alpha / 2) << 24);
            guiGraphics.centeredText(client.font, resultMessage, centerX, 8, (alpha << 24) | 0xFFFFFF);
        }
    }

    private static void showResult(Component message) {
        resultMessage = message;
        resultTicksLeft = RESULT_DURATION_TICKS;
    }

    // ---- persistence (global, crash-proof) ----

    private static synchronized void ensureLoaded(ClientPacketListener conn) {
        if (loaded || conn == null) return; // retry later if we didn't have a connection yet
        loaded = true;
        Path file = storeFile();
        if (!Files.exists(file)) return;
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, conn.registryAccess());
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Map<String, List<ItemStack>> raw = new HashMap<>();
            int dropped = 0;
            for (String name : root.keySet()) {
                JsonArray arr = root.getAsJsonArray(name);
                List<ItemStack> stacks = new ArrayList<>(arr.size());
                // Decode one item at a time: an item this world/server can't resolve
                // (different datapack, disabled enchantment, etc.) is dropped instead
                // of corrupting the whole loadout.
                for (JsonElement itemJson : arr) {
                    ItemStack stack = ItemStack.EMPTY;
                    try {
                        stack = ItemStack.OPTIONAL_CODEC.parse(ops, itemJson).getOrThrow();
                    } catch (Exception ex) {
                        dropped++;
                    }
                    stacks.add(stack);
                }
                raw.put(name, stacks);
            }
            STORE.clear();
            STORE.putAll(raw);
            if (dropped > 0) {
                System.err.println("[fullset] " + dropped + " item(s) couldn't load (incompatible with this world/server) and were dropped.");
            }
        } catch (Exception ex) {
            System.err.println("[fullset] Failed to load loadouts: " + ex.getMessage());
        }
    }

    private static synchronized boolean saveStore(ClientPacketListener conn) {
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, conn.registryAccess());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<ItemStack>> entry : STORE.entrySet()) {
                JsonArray arr = new JsonArray();
                // Encode one item at a time: a loadout carrying an item from a different
                // server's registries can't sink every other loadout's save.
                for (ItemStack stack : entry.getValue()) {
                    arr.add(encodeItemSafe(stack, ops));
                }
                root.add(entry.getKey(), arr);
            }
            Path file = storeFile();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("loadouts.json.tmp");
            Files.writeString(tmp, root.toString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception ex) {
            System.err.println("[fullset] Failed to save loadouts: " + ex.getMessage());
            return false;
        }
    }

    private static JsonElement encodeItemSafe(ItemStack stack, RegistryOps<JsonElement> ops) {
        try {
            return ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow();
        } catch (Exception ex) {
            try {
                return ItemStack.OPTIONAL_CODEC.encodeStart(ops, ItemStack.EMPTY).getOrThrow();
            } catch (Exception inner) {
                return new JsonObject();
            }
        }
    }

    // ---- command trees ----

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildSave(String literal) {
        return ClientCommands.literal(literal)
                .executes(ctx -> save(ctx, "default"))
                .then(ClientCommands.argument("name", StringArgumentType.word())
                        .executes(ctx -> save(ctx, StringArgumentType.getString(ctx, "name"))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildRestore(String literal) {
        return ClientCommands.literal(literal)
                .executes(FullsetMod::restoreSingle)
                .then(ClientCommands.literal("clear")
                        .executes(FullsetMod::clearSingle)
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .suggests(SAVED_NAMES)
                                .executes(ctx -> clear(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(ClientCommands.argument("name", StringArgumentType.word())
                        .suggests(SAVED_NAMES)
                        .executes(ctx -> restore(ctx, StringArgumentType.getString(ctx, "name"))));
    }

    // ---- handlers ----

    private static int save(CommandContext<FabricClientCommandSource> ctx, String name) {
        FabricClientCommandSource source = ctx.getSource();
        LocalPlayer player = source.getPlayer();
        ClientPacketListener conn = source.getClient().getConnection();
        ensureLoaded(conn);

        Inventory inv = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>(SIZE);
        for (int i = 0; i < 36; i++) {
            stacks.add(inv.getItem(i).copy());      // 0-8 hotbar, 9-35 inventory
        }
        for (EquipmentSlot slot : ARMOR) {
            stacks.add(player.getItemBySlot(slot).copy());  // 36-39 armor
        }
        stacks.add(player.getItemBySlot(EquipmentSlot.OFFHAND).copy()); // 40 offhand

        STORE.put(name, stacks);
        if (saveStore(conn)) {
            source.sendFeedback(Component.literal("Saved loadout '" + name + "'.").withStyle(GREEN));
        } else {
            source.sendError(Component.literal(
                    "Saved loadout '" + name + "' in memory, but writing loadouts.json failed - it won't survive a restart. Check the log."));
        }
        return 1;
    }

    private static int restoreSingle(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        ensureLoaded(source.getClient().getConnection());
        if (STORE.isEmpty()) {
            source.sendError(Component.literal("You haven't saved any loadouts yet. Use /setfullset <name>."));
            return 0;
        }
        if (STORE.size() > 1) {
            source.sendError(Component.literal("You have multiple loadouts: " + String.join(", ", STORE.keySet()) + ". Use /fullset <name>."));
            return 0;
        }
        String name = STORE.keySet().iterator().next();
        return apply(source, name, STORE.get(name));
    }

    private static int restore(CommandContext<FabricClientCommandSource> ctx, String name) {
        FabricClientCommandSource source = ctx.getSource();
        ensureLoaded(source.getClient().getConnection());
        if (!STORE.containsKey(name)) {
            source.sendError(Component.literal("No loadout named '" + name + "'." + savedList()));
            return 0;
        }
        return apply(source, name, STORE.get(name));
    }

    private static int apply(FabricClientCommandSource source, String name, List<ItemStack> saved) {
        Minecraft client = source.getClient();
        MinecraftServer integrated = client.getSingleplayerServer();

        if (integrated != null) {
            // Single-player: set the inventory directly on our own server (full fidelity, no cheats).
            LocalPlayer local = source.getPlayer();
            integrated.execute(() -> {
                ServerPlayer sp = integrated.getPlayerList().getPlayer(local.getUUID());
                if (sp == null) return;
                Inventory inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, get(saved, i));
                }
                for (int a = 0; a < ARMOR.length; a++) {
                    sp.setItemSlot(ARMOR[a], get(saved, 36 + a));
                }
                sp.setItemSlot(EquipmentSlot.OFFHAND, get(saved, 40));
                sp.inventoryMenu.broadcastChanges();
                sp.containerMenu.broadcastChanges();
            });
            source.sendFeedback(Component.literal("Restored loadout '" + name + "'.").withStyle(GREEN));
            return 1;
        }

        // Multiplayer: use creative-mode set-slot packets (full fidelity incl. shulkers).
        // This needs permission to run /gamemode (operators have it).
        ClientPacketListener conn = client.getConnection();
        if (conn == null || client.gameMode == null) {
            source.sendError(Component.literal("Not connected to a server."));
            return 0;
        }
        List<ItemStack> copies = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            copies.add(get(saved, i));
        }
        GameType previous = client.gameMode.getPlayerMode();
        pending = new PendingRestore(name, copies, previous);
        resultTicksLeft = 0; // let the progress bar take over from any leftover result banner
        conn.sendCommand("gamemode creative");
        return 1;
    }

    // Runs every client tick; finishes a multiplayer restore once creative mode is active.
    private static void tickPending(Minecraft client) {
        if (resultTicksLeft > 0) resultTicksLeft--;
        if (pending == null) return;
        if (client.player == null || client.gameMode == null || client.getConnection() == null) {
            pending = null;
            return;
        }
        pending.ticks++;

        if (!pending.creativeConfirmed) {
            // We can push creative packets once the server has actually made us creative.
            boolean creative = client.gameMode.getPlayerMode() == GameType.CREATIVE
                    || client.player.getAbilities().instabuild;
            if (!creative) {
                if (pending.ticks > 100) { // ~5 seconds and still not creative
                    showResult(Component.literal("Fullset: couldn't restore '" + pending.name
                            + "' - not detected as creative. Do you have permission to use /gamemode here?")
                            .withStyle(RED));
                    pending = null;
                }
                return; // keep waiting
            }
            pending.creativeConfirmed = true;
            lastDropped = 0;
        }

        // Apply a handful of slots per tick instead of all 41 at once - a single-tick burst
        // of creative set-slot packets is exactly what server anti-cheat (NoCheatPlus, Grim,
        // Vulcan, ...) flags as an impossible inventory edit and can cancel or punish.
        try {
            RegistryAccess ra = client.getConnection().registryAccess();
            int end = Math.min(pending.applied + APPLY_PER_TICK, SIZE);
            for (int i = pending.applied; i < end; i++) {
                ItemStack stack = (i < pending.stacks.size() && pending.stacks.get(i) != null)
                        ? pending.stacks.get(i).copy() : ItemStack.EMPTY;
                client.gameMode.handleCreativeModeItemAdd(safeForCreative(stack, ra), menuSlot(i));
            }
            pending.applied = end;
        } catch (Exception e) {
            showResult(Component.literal("Fullset: restore of '" + pending.name + "' FAILED: "
                    + e.getClass().getSimpleName()).withStyle(RED));
            System.err.println("[fullset] restore error: " + e);
            pending = null;
            return;
        }

        if (pending.applied < SIZE) return; // more slots next tick

        if (pending.previous != null && pending.previous != GameType.CREATIVE) {
            client.getConnection().sendCommand("gamemode " + pending.previous.getName());
        }
        String note = (lastDropped > 0)
                ? " (" + lastDropped + " enchantment(s) this server blocks were skipped)"
                : "";
        showResult(Component.literal("Fullset: restored '" + pending.name + "'." + note).withStyle(GREEN));
        pending = null;
    }

    private static int clearSingle(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        ensureLoaded(source.getClient().getConnection());
        if (STORE.isEmpty()) {
            source.sendError(Component.literal("You have no saved loadouts to clear."));
            return 0;
        }
        if (STORE.size() > 1) {
            source.sendError(Component.literal("You have multiple loadouts: " + String.join(", ", STORE.keySet()) + ". Use /fullset clear <name>."));
            return 0;
        }
        return clear(ctx, STORE.keySet().iterator().next());
    }

    private static int clear(CommandContext<FabricClientCommandSource> ctx, String name) {
        FabricClientCommandSource source = ctx.getSource();
        ClientPacketListener conn = source.getClient().getConnection();
        ensureLoaded(conn);
        if (!STORE.containsKey(name)) {
            source.sendError(Component.literal("No loadout named '" + name + "'." + savedList()));
            return 0;
        }
        STORE.remove(name);
        if (saveStore(conn)) {
            source.sendFeedback(Component.literal("Removed loadout '" + name + "'.").withStyle(YELLOW));
        } else {
            source.sendError(Component.literal(
                    "Removed loadout '" + name + "' from memory, but writing loadouts.json failed. Check the log."));
        }
        return 1;
    }

    // ---- helpers ----

    private static ItemStack get(List<ItemStack> saved, int i) {
        return (i < saved.size() && saved.get(i) != null) ? saved.get(i).copy() : ItemStack.EMPTY;
    }

    // Map our stored slot index to the player's inventory-menu slot id used by
    // the creative set-slot packet: hotbar 36-44, main 9-35, armor 5-8, offhand 45.
    private static int menuSlot(int i) {
        if (i < 9) return 36 + i;     // hotbar
        if (i < 36) return i;         // main inventory
        return switch (i) {
            case 36 -> 8;  // feet
            case 37 -> 7;  // legs
            case 38 -> 6;  // chest
            case 39 -> 5;  // head
            default -> 45; // 40 offhand
        };
    }

    // True if this item can be packed into a network packet for the current server.
    // An item pointing at something the server lacks (like a disabled enchantment)
    // fails here - exactly the case we must avoid sending.
    private static boolean encodesOk(ItemStack stack, RegistryAccess ra) {
        if (stack.isEmpty()) return true;
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            buf.release();
        }
    }

    // Make an item safe to send to this server, keeping as much as possible:
    //   1. if it already packs, send it untouched (full fidelity);
    //   2. otherwise re-resolve it through THIS server's data (rebuilds the links for
    //      enchantments/contents that came from the saved file) and use that;
    //   3. if it still won't pack, drop enchantments but keep the rest of the item;
    //   4. then fall back to a plain item; worst case skip it (so we never crash).
    private static ItemStack safeForCreative(ItemStack stack, RegistryAccess ra) {
        if (stack.isEmpty()) return stack;
        if (encodesOk(stack, ra)) return stack;

        // Re-resolve the whole item (including shulker contents) against this server.
        ItemStack rr = roundTrip(stack, ra);
        if (rr != null && encodesOk(rr, ra)) return rr;

        // Something here the server doesn't have (e.g. a disabled enchantment). Keep the
        // item but without enchantments, re-resolving the rest so it still packs cleanly.
        ItemStack base = (rr != null) ? rr : stack.copy();
        ItemStack noEnch = base.copy();
        noEnch.remove(DataComponents.ENCHANTMENTS);
        noEnch.remove(DataComponents.STORED_ENCHANTMENTS);
        ItemStack noEnchRr = roundTrip(noEnch, ra);
        if (noEnchRr != null && encodesOk(noEnchRr, ra)) { lastDropped++; return noEnchRr; }
        if (encodesOk(noEnch, ra)) { lastDropped++; return noEnch; }

        ItemStack bare = new ItemStack(stack.getItem(), stack.getCount());
        if (encodesOk(bare, ra)) { lastDropped++; return bare; }

        return ItemStack.EMPTY;
    }

    // Re-resolve an item through this server's registries: write it out and read it back
    // using the current data, which rebuilds any links (enchantments, shulker contents)
    // that were saved against an older copy of the server's lists. Returns null if the
    // item references something this server genuinely doesn't have.
    private static ItemStack roundTrip(ItemStack stack, RegistryAccess ra) {
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, ra);
            JsonElement json = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow();
            return ItemStack.OPTIONAL_CODEC.parse(ops, json).getOrThrow();
        } catch (Exception e) {
            return null;
        }
    }

    private static String savedList() {
        if (STORE.isEmpty()) return " You have no saved loadouts.";
        return " Saved: " + String.join(", ", STORE.keySet()) + ".";
    }

    private static final class PendingRestore {
        final String name;
        final List<ItemStack> stacks;
        final GameType previous;
        int ticks = 0;
        int applied = 0;
        boolean creativeConfirmed = false;

        PendingRestore(String name, List<ItemStack> stacks, GameType previous) {
            this.name = name;
            this.stacks = stacks;
            this.previous = previous;
        }
    }
}
