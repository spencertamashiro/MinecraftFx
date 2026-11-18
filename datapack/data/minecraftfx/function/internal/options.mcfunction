data modify storage minecraftfx:work v.visibility set value "normal"
execute if data storage minecraftfx:work options{force:1b} run data modify storage minecraftfx:work v.visibility set value "force"
data modify storage minecraftfx:work v.up_yaw set from storage minecraftfx:work options.up_yaw
data modify storage minecraftfx:work v.up_pitch set from storage minecraftfx:work options.up_pitch
execute store result score #scale mfx.math run data get storage minecraftfx:work options.scale 1000
execute if score #scale mfx.math matches ..99 run scoreboard players set #scale mfx.math 100
execute if score #scale mfx.math matches 10001.. run scoreboard players set #scale mfx.math 10000
execute if data storage minecraftfx:work options.tint run function minecraftfx:internal/tint
