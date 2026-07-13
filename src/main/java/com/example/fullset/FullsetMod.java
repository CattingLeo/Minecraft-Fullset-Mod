package com.example.fullset;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.serialization.Codec;
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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
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

    private static final Codec<Map<String, List<ItemStack>>> STORE_CODEC =
            Codec.unboundedMap(Codec.STRING, ItemStack.OPTIONAL_CODEC.listOf());

    // name -> 41 stacks. Stored globally in .minecraft/config (shared across worlds/servers).
    private static final Map<String, List<ItemStack>> STORE = new HashMap<>();
    private static boolean loaded = false;

    // A restore waiting for the server to put us into creative mode.
    private static PendingRestore pending = null;
    // How many enchantments the last restore had to drop (server didn't have them).
    private static int lastDropped = 0;

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
    }

    // ---- persistence (global, crash-proof) ----

    private static synchronized void ensureLoaded(ClientPacketListener conn) {
        if (loaded) return;
        loaded = true;
        Path file = storeFile();
        if (!Files.exists(file) || conn == null) return;
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, conn.registryAccess());
            JsonElement json = JsonParser.parseString(Files.readString(file));
            Map<String, List<ItemStack>> raw = STORE_CODEC.parse(ops, json).getOrThrow();
            STORE.clear();
            STORE.putAll(raw);
        } catch (Exception ex) {
            System.err.println("[fullset] Failed to load loadouts: " + ex.getMessage());
        }
    }

    private static synchronized void saveStore(ClientPacketListener conn) {
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, conn.registryAccess());
            JsonElement json = STORE_CODEC.encodeStart(ops, STORE).getOrThrow();
            Path file = storeFile();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("loadouts.json.tmp");
            Files.writeString(tmp, json.toString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            System.err.println("[fullset] Failed to save loadouts: " + ex.getMessage());
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
        ensureLoaded(source.getClient().getConnection());

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
        saveStore(source.getClient().getConnection());

        source.sendFeedback(Component.literal("Saved loadout '" + name + "'.").withStyle(GREEN));
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
        conn.sendCommand("gamemode creative");
        client.gui.hud.setOverlayMessage(
                Component.literal("Fullset v2: restoring '" + name + "'...").withStyle(GREEN), false);
        return 1;
    }

    // Runs every client tick; finishes a multiplayer restore once creative mode is active.
    private static void tickPending(Minecraft client) {
        if (pending == null) return;
        if (client.player == null || client.gameMode == null || client.getConnection() == null) {
            pending = null;
            return;
        }
        pending.ticks++;

        // We can push creative packets once the server has actually made us creative.
        boolean creative = client.gameMode.getPlayerMode() == GameType.CREATIVE
                || client.player.getAbilities().instabuild;

        if (!creative) {
            if (pending.ticks > 100) { // ~5 seconds and still not creative
                client.gui.hud.setOverlayMessage(
                        Component.literal("Fullset v2: couldn't restore '" + pending.name + "' - not detected as creative.")
                                .withStyle(RED), false);
                pending = null;
            }
            return; // keep waiting
        }

        try {
            // We're in creative: push every slot straight into the inventory. Each item is
            // first made safe for THIS server so a bad reference can't break the connection.
            RegistryAccess ra = client.getConnection().registryAccess();
            lastDropped = 0;
            for (int i = 0; i < SIZE; i++) {
                ItemStack stack = (i < pending.stacks.size() && pending.stacks.get(i) != null)
                        ? pending.stacks.get(i).copy() : ItemStack.EMPTY;
                client.gameMode.handleCreativeModeItemAdd(safeForCreative(stack, ra), menuSlot(i));
            }
            if (pending.previous != null && pending.previous != GameType.CREATIVE) {
                client.getConnection().sendCommand("gamemode " + pending.previous.getName());
            }
            String note = (lastDropped > 0)
                    ? " (" + lastDropped + " enchantment(s) this server blocks were skipped)"
                    : "";
            client.gui.hud.setOverlayMessage(
                    Component.literal("Fullset v2: restored '" + pending.name + "'." + note).withStyle(GREEN), false);
        } catch (Exception e) {
            client.gui.hud.setOverlayMessage(
                    Component.literal("Fullset v2: restore of '" + pending.name + "' FAILED: "
                            + e.getClass().getSimpleName()).withStyle(RED), false);
            System.err.println("[fullset] restore error: " + e);
        } finally {
            pending = null; // always finish, so it never gets stuck on "Restoring..."
        }
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
        ensureLoaded(source.getClient().getConnection());
        if (!STORE.containsKey(name)) {
            source.sendError(Component.literal("No loadout named '" + name + "'." + savedList()));
            return 0;
        }
        STORE.remove(name);
        saveStore(source.getClient().getConnection());
        source.sendFeedback(Component.literal("Removed loadout '" + name + "'.").withStyle(YELLOW));
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

        PendingRestore(String name, List<ItemStack> stacks, GameType previous) {
            this.name = name;
            this.stacks = stacks;
            this.previous = previous;
        }
    }
}
