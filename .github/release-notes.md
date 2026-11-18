Choose the download for your Minecraft Java Edition world. Both editions include all 306 effect IDs.

| Edition | Installation | First effect |
| --- | --- | --- |
| **Java 26.3 · Vanilla datapack** | Put `MinecraftFx-26.3-datapack.zip` in the world's `datapacks/` folder and run `/reload`. | `/execute positioned ^ ^1 ^3 run function minecraftfx:play/impact/critical_spark` |
| **Java 1.21.1 · NeoForge mod** | Put `MinecraftFx-1.21.1-neoforge.jar` and Lodestone 1.8.2 in `mods/` on the client and server with NeoForge 21.1.265. | `/fx play minecraftfx:impact/critical_spark` |

The datapack draws animated vanilla particles and plays vanilla sound cues out of the box. To hear all 306 original sounds, enable `MinecraftFx-26.3-sounds.zip` under **Options → Resource Packs** on each client, then run `/data modify storage minecraftfx:config defaults.audio set value "original"` once in the world. The mod includes its audio.

[Installation, controls and rendering differences](https://github.com/spencertamashiro/MinecraftFx#choose-your-edition)
