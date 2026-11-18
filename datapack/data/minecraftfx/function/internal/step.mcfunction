# Retire the marker if an invalid macro argument or a removed effect prevents the next frame.
scoreboard players set @s mfx.wait -1
function minecraftfx:internal/next with entity @s data
execute if entity @s[scores={mfx.wait=-1}] run kill @s
