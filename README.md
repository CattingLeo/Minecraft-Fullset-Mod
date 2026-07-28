# Fullset

A client-side [Fabric](https://fabricmc.net) mod for Minecraft Java that saves your whole
inventory as a named **loadout** and brings it back with one command — armor, offhand,
enchantments, custom names, even shulker boxes with everything inside them.

Made by **CattingYT**.

## Commands

| Command | What it does |
|---|---|
| `/setfullset` | Saves your current inventory as the loadout `default` |
| `/setfullset <name>` | Saves your current inventory as `<name>` |
| `/fullset` | Restores your loadout (when you only have one) |
| `/fullset <name>` | Restores the loadout called `<name>` |
| `/fullset clear` | Deletes your only loadout |
| `/fullset clear <name>` | Deletes the loadout called `<name>` |

Loadout names tab-complete. Messages show above your hotbar.

## What gets saved

All 41 slots: hotbar, main inventory, all 4 armor pieces, and offhand — with full item
data (enchantments, names, durability, shulker/bundle contents, and so on).

Loadouts are stored in `.minecraft/config/fullset/loadouts.json`, so they work in
**every world and every server** and survive quitting or crashing.

## Where it works

- **Single-player:** full, instant restore. Everything comes back exactly as saved.
- **Multiplayer:** you need to be an **operator** (the mod briefly switches you to
  creative to place the items, then switches you back). If the server has disabled an
  enchantment, that one enchantment is skipped and the mod tells you how many were
  skipped — everything else still comes back.

This mod is **client-side only** — the server does not need it installed.

## How to build

**Windows:** double-click `BUILD.bat`.

**Any system:**
```
./gradlew clean build
```

The mod appears at `build/libs/fullset-1.2.0.jar`.

This branch targets **Minecraft 26.2** and needs **JDK 25** (Gradle 9.5.1, Loom 1.17,
Fabric API 0.153.0+26.2). The mod has also been built for 26.1.2 and 1.21.11.

## How to install

1. Install the [Fabric loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) (the 26.2 version) in your `mods` folder.
3. Put `fullset-1.2.0.jar` in your `mods` folder.
4. Launch the game and try `/setfullset mykit`!
