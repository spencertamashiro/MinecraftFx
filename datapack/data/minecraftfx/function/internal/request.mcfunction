data modify storage minecraftfx:work options set value {scale:1.0d,force:0b,volume:1.0f,pitch:1.0f,up_yaw:0f,up_pitch:-90f,audio:"vanilla"}
execute if data storage minecraftfx:config defaults run data modify storage minecraftfx:work options merge from storage minecraftfx:config defaults
$data modify storage minecraftfx:work options merge value $(options)
$data modify storage minecraftfx:work mode set value "$(mode)"
$function minecraftfx:start/$(effect)
