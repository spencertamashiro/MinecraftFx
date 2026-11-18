# Capture the caller's rotation before entering the marker's own coordinate frame.
rotate @s ~ ~
tag @s add minecraftfx.active
data modify entity @s data set from storage minecraftfx:work instance
execute at @s run function minecraftfx:internal/step
