scoreboard players remove @e[type=minecraft:marker,tag=minecraftfx.active] mfx.wait 1
execute as @e[type=minecraft:marker,tag=minecraftfx.active,scores={mfx.wait=..0}] at @s run function minecraftfx:internal/step
