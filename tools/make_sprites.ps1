# Generates every sprite sheet for minecraftFx: greyscale pixel-art frames plus each sheet's particles/<sheet>.json.
# Sprites are white/grey with hard alpha; an effect's palette tints them at runtime.
#   powershell -ExecutionPolicy Bypass -File tools\make_sprites.ps1 [-PreviewPath preview.png]
# A new sheet also needs its name in SPRITE_SHEETS in MinecraftFx.java.
param([string]$PreviewPath)
Add-Type -AssemblyName System.Drawing

$assets = Join-Path $PSScriptRoot '..\src\main\resources\assets\minecraftfx'
$textureDir = Join-Path $assets 'textures\particle'
$particleDir = Join-Path $assets 'particles'
New-Item -ItemType Directory -Force $textureDir, $particleDir | Out-Null

$frames = [System.Collections.ArrayList]::new()
$sheets = [ordered]@{}

function New-Frame([string]$sheet, [int]$w, [int]$h, [scriptblock]$tone) {
    # $tone returns 0 (transparent) or a brightness 1..255 for pixel (x, y)
    if (-not $sheets.Contains($sheet)) { $sheets[$sheet] = 0 }
    $name = "${sheet}_$($sheets[$sheet])"
    $sheets[$sheet]++
    $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $h; $y++) {
        for ($x = 0; $x -lt $w; $x++) {
            $t = [int](& $tone $x $y)
            $c = if ($t -le 0) { [System.Drawing.Color]::FromArgb(0, 0, 0, 0) } else { [System.Drawing.Color]::FromArgb(255, $t, $t, $t) }
            $bmp.SetPixel($x, $y, $c)
        }
    }
    $bmp.Save((Join-Path $textureDir "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    [void]$frames.Add(@{ Name = $name; Bitmap = $bmp })
}

# Hand-drawn frame: '#' bright, '+' mid, '-' dim, anything else transparent.
function New-ArtFrame([string]$sheet, [string[]]$rows) {
    if ($rows | Where-Object { $_.Length -ne $rows[0].Length }) { throw "${sheet}: rows differ in length" }
    New-Frame $sheet $rows[0].Length $rows.Count {
        param($x, $y)
        switch ([string]$rows[$y][$x]) { '#' { 255 } '+' { 200 } '-' { 140 } default { 0 } }
    }.GetNewClosure()
}

# Global, because the tone closures below run in their own module scope and cannot see script functions.
function global:Get-Distance($x, $y, $cx, $cy) { [math]::Sqrt([math]::Pow($x - $cx, 2) + [math]::Pow($y - $cy, 2)) }

# Distance from point p to segment a-b.
function global:Get-SegmentDistance($px, $py, $ax, $ay, $bx, $by) {
    $vx = $bx - $ax; $vy = $by - $ay
    # 0.0 and 1.0, not 0 and 1: integer literals pick Math.Max(int, int) and round the projection.
    $t = [math]::Max(0.0, [math]::Min(1.0, (($px - $ax) * $vx + ($py - $ay) * $vy) / ($vx * $vx + $vy * $vy)))
    Get-Distance $px $py ($ax + $t * $vx) ($ay + $t * $vy)
}

$BRIGHT = 255; $MID = 190; $DIM = 130

# flash: four-point star (astroid) that swells, then thins and breaks up. 16x16.
$flash = @(
    @{ len = 3.5; arm = 1.6; core = 2.0; dither = 0 },
    @{ len = 6.0; arm = 2.4; core = 3.6; dither = 0 },
    @{ len = 8.0; arm = 2.8; core = 4.6; dither = 0 },
    @{ len = 8.0; arm = 1.7; core = 3.0; dither = 0 },
    @{ len = 8.0; arm = 1.1; core = 1.6; dither = 2 }
)
foreach ($f in $flash) {
    New-Frame 'flash' 16 16 {
        param($x, $y)
        $dx = [math]::Abs($x - 7.5); $dy = [math]::Abs($y - 7.5)
        $far = [math]::Max($dx, $dy); $near = [math]::Min($dx, $dy)
        $armHalf = $f.arm * (1 - $far / $f.len)          # arms taper to a point
        $inCore = ($dx + $dy) -le $f.core
        if (-not $inCore -and $near -gt $armHalf) { return 0 }
        if ($f.dither -gt 0 -and -not $inCore -and (($x + $y) % $f.dither) -eq 0) { return 0 }
        if (($dx + $dy) -le ($f.core * 0.7) -or $near -le ($armHalf * 0.4)) { return $BRIGHT }
        if ($far -gt ($f.len * 0.75)) { return $DIM }
        return $MID
    }.GetNewClosure()
}

# ring: shock front with a bright leading edge; the band thins and dithers away. 32x32.
$ring = @(
    @{ t = 5.0; dither = 0 }, @{ t = 4.0; dither = 0 }, @{ t = 3.0; dither = 0 },
    @{ t = 2.2; dither = 0 }, @{ t = 1.6; dither = 2 }, @{ t = 1.2; dither = 3 }
)
foreach ($f in $ring) {
    New-Frame 'ring' 32 32 {
        param($x, $y)
        $d = Get-Distance $x $y 15.5 15.5
        if ($d -gt 15.2 -or $d -lt (15.2 - $f.t)) { return 0 }
        if ($f.dither -gt 0 -and (($x + 2 * $y) % $f.dither) -ne 0) { return 0 }
        if ($d -gt 14.0) { return $BRIGHT }
        if ($d -gt 12.6) { return $MID }
        return $DIM
    }.GetNewClosure()
}

# shard: vertical lozenge; Lodestone's spark behaviour stretches V along the travel direction. 8x16.
for ($i = 0; $i -lt 3; $i++) {
    $dither = $i
    New-Frame 'shard' 8 16 {
        param($x, $y)
        $half = 0.4 + 3.6 * (1 - [math]::Abs($y - 7.5) / 8.0)
        $dx = [math]::Abs($x - 3.5)
        if ($dx -gt $half) { return 0 }
        if ($dither -eq 1 -and ($y % 4) -eq 3) { return 0 }
        if ($dither -eq 2 -and ($y % 2) -eq 1) { return 0 }
        if ($dx -lt 1.2) { return $BRIGHT }
        return $MID
    }.GetNewClosure()
}

# pixel: a square ember that steps down in size. 8x8.
$pixel = @(4, 4, 2, 2, 1)
for ($i = 0; $i -lt $pixel.Count; $i++) {
    $size = $pixel[$i]; $soft = ($i -eq 1 -or $i -eq 3)
    New-Frame 'pixel' 8 8 {
        param($x, $y)
        $lo = 4 - [math]::Ceiling($size / 2.0); $hi = $lo + $size - 1
        if ($x -lt $lo -or $x -gt $hi -or $y -lt $lo -or $y -gt $hi) { return 0 }
        if ($soft -and ($x -eq $lo -or $x -eq $hi) -and ($y -eq $lo -or $y -eq $hi)) { return 0 }
        if ($size -ge 4 -and ($x -eq $lo -or $x -eq $hi -or $y -eq $lo -or $y -eq $hi)) { return $MID }
        return $BRIGHT
    }.GetNewClosure()
}

# smoke: a lumpy puff, lit from the top left, that swells and breaks up. 16x16.
$smoke = @(@{ r = 3.2; dither = 0 }, @{ r = 4.6; dither = 0 }, @{ r = 5.8; dither = 0 }, @{ r = 6.6; dither = 1 }, @{ r = 7.2; dither = 2 })
foreach ($f in $smoke) {
    New-Frame 'smoke' 16 16 {
        param($x, $y)
        $r = $f.r
        $inside = ((Get-Distance $x $y (7.5 - $r * 0.3) 8.6) -le $r * 0.72) -or
                  ((Get-Distance $x $y (8.2 + $r * 0.32) 8.2) -le $r * 0.68) -or
                  ((Get-Distance $x $y 7.8 (7.4 - $r * 0.3)) -le $r * 0.66)
        if (-not $inside) { return 0 }
        $edge = (Get-Distance $x $y 7.8 8.0) / $r
        if ($f.dither -eq 1 -and $edge -gt 0.55 -and (($x + $y) % 2) -eq 0) { return 0 }
        if ($f.dither -eq 2 -and (($x + $y) % 2) -eq 0) { return 0 }
        if ($f.dither -eq 2 -and $edge -gt 0.7 -and ($x % 2) -eq 0) { return 0 }
        $light = -($x - 7.5) - ($y - 7.5)
        if ($light -gt 2) { return $BRIGHT }
        if ($light -gt -4) { return $MID }
        return $DIM
    }.GetNewClosure()
}

# orb: a round glow with a hot centre that shrinks away. 8x8.
foreach ($r in 3.7, 3.1, 2.3, 1.3) {
    New-Frame 'orb' 8 8 {
        param($x, $y)
        $d = Get-Distance $x $y 3.5 3.5
        if ($d -gt $r) { return 0 }
        if ($d -le $r * 0.5) { return $BRIGHT }
        if ($d -le $r * 0.8) { return $MID }
        return $DIM
    }.GetNewClosure()
}

# bolt: a jagged vertical lightning segment for streaks; each frame is a different fork. 8x32.
foreach ($seed in 7, 19, 31) {
    $rng = [System.Random]::new($seed)
    $path = [double[]]::new(32)
    $px = 3.5; $next = 0
    for ($y = 0; $y -lt 32; $y++) {
        if ($y -ge $next) { $px = [math]::Max(1.5, [math]::Min(5.5, $px + $rng.Next(-2, 3))); $next = $y + $rng.Next(3, 6) }
        $path[$y] = $px
    }
    $thin = ($seed -eq 31)
    New-Frame 'bolt' 8 32 {
        param($x, $y)
        $d = [math]::Abs($x - $path[$y])
        if ($d -lt 0.6) { return $BRIGHT }
        if (-not $thin -and $d -lt 1.6) { return $DIM }
        return 0
    }.GetNewClosure()
}

# bubble: a thin shell with a highlight that pops into a ring of droplets. 12x12.
foreach ($f in @(@{ rx = 5.2; ry = 5.2 }, @{ rx = 5.4; ry = 4.8 }, @{ rx = 5.2; ry = 5.2 }, @{ pop = $true })) {
    New-Frame 'bubble' 12 12 {
        param($x, $y)
        if ($f.pop) {
            for ($k = 0; $k -lt 8; $k++) {
                $a = $k * [math]::PI / 4
                if ($x -eq [math]::Round(5.5 + 5 * [math]::Cos($a)) -and $y -eq [math]::Round(5.5 + 5 * [math]::Sin($a))) { return $BRIGHT }
            }
            return 0
        }
        $n = [math]::Sqrt([math]::Pow(($x - 5.5) / $f.rx, 2) + [math]::Pow(($y - 5.5) / $f.ry, 2))
        if (($x -eq 3 -and $y -eq 3) -or ($x -eq 4 -and $y -eq 3) -or ($x -eq 3 -and $y -eq 4)) { return $BRIGHT }
        if ($n -le 1.0 -and $n -gt 0.8) { return $MID }
        return 0
    }.GetNewClosure()
}

# leaf: three variants, for frames "random". 8x8.
New-ArtFrame 'leaf' @('......##', '....####', '...##+##', '..##+##.', '.##+##..', '.#+##...', '.+##....', '+.......')
New-ArtFrame 'leaf' @('........', '...###..', '..#####.', '.##+###.', '.#+####.', '.+####..', '+.##....', '........')
New-ArtFrame 'leaf' @('........', '........', '....##..', '...####.', '..#+##..', '.+.##...', '+.......', '........')

# heart: beats once, then shrinks. 7x7.
New-ArtFrame 'heart' @('.++.++.', '+##++++', '+#+++++', '+++++++', '.+++++.', '..+++..', '...+...')
New-ArtFrame 'heart' @('.++.++.', '+##++++', '+#+++++', '+++++++', '.+++++.', '..+++..', '...+...')
New-ArtFrame 'heart' @('.......', '.++.++.', '.+#+++.', '.+++++.', '..+++..', '...+...', '.......')

# rune: a magic circle that draws itself in, holds, then breaks up. Use with shape "plane". 32x32.
$tri = { param($phase) 0..2 | ForEach-Object { $a = $phase + $_ * 2 * [math]::PI / 3; , @((15.5 + 10.4 * [math]::Cos($a)), (15.5 + 10.4 * [math]::Sin($a))) } }
$up = & $tri (-[math]::PI / 2); $down = & $tri ([math]::PI / 2)
foreach ($step in 0, 1, 2, 3, 4) {
    New-Frame 'rune' 32 32 {
        param($x, $y)
        if ($step -eq 4 -and (($x + $y) % 2) -eq 0) { return 0 }
        $d = Get-Distance $x $y 15.5 15.5
        $angle = [math]::Atan2($y - 15.5, $x - 15.5) + [math]::PI      # 0 .. 2pi
        if ($d -gt 14.4 -and $d -le 15.4 -and ($step -gt 0 -or $angle -lt [math]::PI)) { return $BRIGHT }
        if ($step -ge 1 -and $d -gt 10.4 -and $d -le 11.2) { return $MID }
        if ($step -ge 2 -and $d -gt 11.8 -and $d -lt 13.6) {
            $tick = ($angle * 8 / (2 * [math]::PI)) % 1
            if ($tick -lt 0.12 -or $tick -gt 0.88) { return $MID }
        }
        foreach ($t in @(@{ on = 2; p = $up }, @{ on = 3; p = $down })) {
            if ($step -lt $t.on) { continue }
            for ($k = 0; $k -lt 3; $k++) {
                $a = $t.p[$k]; $b = $t.p[($k + 1) % 3]
                if ((Get-SegmentDistance $x $y $a[0] $a[1] $b[0] $b[1]) -lt 0.55) { return $BRIGHT }
            }
        }
        return 0
    }.GetNewClosure()
}

# sigil: the finished magic circle held still, one frame, with an inner ring and a ring round each point of its star.
# For circles that must look the same every tick, such as an effect replayed as a loop. Use with shape "plane". 32x32.
New-Frame 'sigil' 32 32 {
    param($x, $y)
    $d = Get-Distance $x $y 15.5 15.5
    $angle = [math]::Atan2($y - 15.5, $x - 15.5) + [math]::PI
    if ($d -gt 14.4 -and $d -le 15.4) { return $BRIGHT }
    if ($d -gt 10.4 -and $d -le 11.2) { return $MID }
    if ($d -gt 11.8 -and $d -lt 13.6) {
        $tick = ($angle * 8 / (2 * [math]::PI)) % 1
        if ($tick -lt 0.12 -or $tick -gt 0.88) { return $MID }
    }
    if ($d -gt 4.3 -and $d -le 5.1) { return $MID }
    foreach ($p in @($up + $down)) {
        $r = Get-Distance $x $y $p[0] $p[1]
        if ($r -gt 1.3 -and $r -le 2.1) { return $BRIGHT }
    }
    foreach ($t in @($up, $down)) {
        for ($k = 0; $k -lt 3; $k++) {
            $a = $t[$k]; $b = $t[($k + 1) % 3]
            if ((Get-SegmentDistance $x $y $a[0] $a[1] $b[0] $b[1]) -lt 0.55) { return $BRIGHT }
        }
    }
    return 0
}.GetNewClosure()

# line: a straight bar for streaks, solid along its whole length with a bright core and dim edges, so segments laid end
# to end draw hairline rings and the straight edges of star figures. One frame. 8x8.
New-ArtFrame 'line' @('-+####+-', '-+####+-', '-+####+-', '-+####+-', '-+####+-', '-+####+-', '-+####+-', '-+####+-')

# slash: a crescent sword arc, thick in the middle and tapered at the tips, that sweeps in and thins out. 32x32.
foreach ($f in @(@{ sweep = 0.55; t = 5.0; dither = 0 }, @{ sweep = 1.0; t = 5.5; dither = 0 }, @{ sweep = 1.0; t = 3.5; dither = 0 }, @{ sweep = 1.0; t = 1.8; dither = 2 })) {
    New-Frame 'slash' 32 32 {
        param($x, $y)
        $from = -1.9; $to = 1.9                                  # radians, an arc open to the left
        $angle = [math]::Atan2($y - 15.5, $x - 13.0)
        $end = $from + ($to - $from) * $f.sweep
        if ($angle -lt $from -or $angle -gt $end) { return 0 }
        $along = ($angle - $from) / ($to - $from)
        $thick = $f.t * [math]::Sin([math]::PI * $along)
        $d = Get-Distance $x $y 13.0 15.5
        if ($d -gt 14.5 -or $d -lt 14.5 - $thick) { return 0 }
        if ($f.dither -gt 0 -and (($x + $y) % $f.dither) -eq 0) { return 0 }
        if ($d -gt 13.3) { return $BRIGHT }
        if ($d -gt 14.5 - $thick * 0.6) { return $MID }
        return $DIM
    }.GetNewClosure()
}

# snowflake: eight arms that melt to a small star. 9x9.
New-ArtFrame 'snowflake' @('....#....', '.+..#..+.', '..+.#.+..', '...+#+...', '#########', '...+#+...', '..+.#.+..', '.+..#..+.', '....#....')
New-ArtFrame 'snowflake' @('.........', '.........', '..+.#.+..', '...+#+...', '..#####..', '...+#+...', '..+.#.+..', '.........', '.........')

# coin: one full turn about its vertical axis. 8x8.
foreach ($hw in 3.6, 2.7, 1.3, 0.55, 1.3, 2.7) {
    New-Frame 'coin' 8 8 {
        param($x, $y)
        $n = [math]::Pow(($x - 3.5) / $hw, 2) + [math]::Pow(($y - 3.5) / 3.7, 2)
        if ($n -gt 1) { return 0 }
        if ($hw -gt 2 -and [math]::Abs($x - 3.5) -lt 1 -and [math]::Abs($y - 3.5) -lt 1.6) { return $DIM }
        if ($n -gt 0.55) { return $MID }
        return $BRIGHT
    }.GetNewClosure()
}

# plus: a healing cross that pulses down. 7x7.
New-ArtFrame 'plus' @('..+++..', '..+#+..', '+++#+++', '+#####+', '+++#+++', '..+#+..', '..+++..')
New-ArtFrame 'plus' @('.......', '...+...', '...#...', '.+###+.', '...#...', '...+...', '.......')

# drop: a falling droplet with a highlight. 5x7.
New-ArtFrame 'drop' @('..+..', '..+..', '.+++.', '.#+++', '+#+++', '+++++', '.+++.')

# feather: two mirror-image variants, for frames "random". 8x8.
$feather = @('.....+##', '....+#++', '...+#++.', '..+#++..', '.+#++...', '..+.....', '.#......', '#.......')
New-ArtFrame 'feather' $feather
New-ArtFrame 'feather' ($feather | ForEach-Object { -join $_[($_.Length - 1)..0] })

# skull: 7x7.
New-ArtFrame 'skull' @('.+++++.', '+##++++', '+..+..+', '+..+..+', '+++.+++', '.+++++.', '.+.+.+.')

# glyph: four invented rune symbols, for frames "random". 8x8.
New-ArtFrame 'glyph' @('.######.', '.#....#.', '...##...', '..#..#..', '..#..#..', '...##...', '.#....#.', '.######.')
New-ArtFrame 'glyph' @('#......#', '.#....#.', '..#..#..', '...##...', '...##...', '...##...', '...##...', '..####..')
New-ArtFrame 'glyph' @('...##...', '..#..#..', '.#....#.', '########', '.#....#.', '..#..#..', '...##...', '........')
New-ArtFrame 'glyph' @('.#####..', '.#...#..', '.#...#..', '.#####..', '.#......', '.#.###..', '.#...#..', '.#####..')

# ---- Sheets for the 300-effect catalog: blocky, three tones, hard edges. ----
$e8 = '........'; $e16 = '................'

# puff: a chunky blob, lit from the top left, that steps down in size. 8x8.
New-ArtFrame 'puff' @('.####+..', '######+.', '#######+', '#######+', '######++', '+####++-', '.+++++-.', '..+---..')
New-ArtFrame 'puff' @($e8, '..###+..', '.#####+.', '.####++.', '.###++-.', '.+++--..', '..+--...', $e8)
New-ArtFrame 'puff' @($e8, $e8, '..##+...', '..#++-..', '..++--..', '...--...', $e8, $e8)
New-ArtFrame 'puff' @($e8, $e8, $e8, '...#+...', '...+-...', $e8, $e8, $e8)

# flame: a tongue of fire with a hot core that flickers, then dies down. 8x8.
New-ArtFrame 'flame' @('...+....', '...++...', '..-++...', '..+++-..', '.-+##+-.', '.+####+.', '.-+##+-.', '..-++-..')
New-ArtFrame 'flame' @('....+...', '...++...', '...++-..', '..-+++..', '.-+##+-.', '.+####+.', '.-+##+-.', '..-++-..')
New-ArtFrame 'flame' @($e8, '...+....', '...++...', '..-++-..', '..+##+..', '..+##+..', '..-++-..', $e8)
New-ArtFrame 'flame' @($e8, $e8, '...+....', '...++...', '..-##-..', '...++...', $e8, $e8)

# rock: three chunks of debris, for frames "random". 8x8.
New-ArtFrame 'rock' @($e8, '..###+..', '.####++.', '.###++-.', '.##++--.', '.+++---.', '..+---..', $e8)
New-ArtFrame 'rock' @($e8, $e8, '..##+...', '.###++..', '.##++-..', '..+--...', $e8, $e8)
New-ArtFrame 'rock' @($e8, '...##...', '..###+..', '..##++-.', '...+--..', $e8, $e8, $e8)

# star: five points, then a small one. 9x9.
New-ArtFrame 'star' @('....#....', '....#....', '...###...', '#########', '.#######.', '..#####..', '..##.##..', '.##...##.', '.#.....#.')
New-ArtFrame 'star' @('.........', '.........', '....#....', '..#####..', '...###...', '...#.#...', '.........', '.........', '.........')

# glint: a thin sparkle that opens and closes. 7x7.
$glintSmall = @('.......', '.......', '...+...', '..+#+..', '...+...', '.......', '.......')
New-ArtFrame 'glint' $glintSmall
New-ArtFrame 'glint' @('...+...', '...+...', '...#...', '++###++', '...#...', '...+...', '...+...')
New-ArtFrame 'glint' $glintSmall

# hex: one honeycomb cell of a shield: lit, outline, then broken outline. 16x16.
foreach ($step in 0, 1, 2) {
    New-Frame 'hex' 16 16 {
        param($x, $y)
        $dx = [math]::Abs($x - 7.5); $dy = [math]::Abs($y - 7.5)
        $d = [math]::Max($dx, $dx * 0.5 + $dy * 0.866)      # hexagon "radius": flat sides, points top and bottom
        if ($d -gt 6.6) { return 0 }
        if ($d -gt 5.2) { if ($step -eq 2 -and ($x % 2) -eq 0) { return 0 }; return $BRIGHT }
        if ($step -eq 0) { return $DIM }
        return 0
    }.GetNewClosure()
}

# Status icons. 8x8 unless noted.
New-ArtFrame 'question' @('.#####..', '##...##.', '.....##.', '...###..', '...##...', $e8, '...##...', '...##...')
New-ArtFrame 'exclaim' @('...##...', '...##...', '...##...', '...##...', '...##...', $e8, '...##...', '...##...')
New-ArtFrame 'zzz' @($e8, '.######.', '....##..', '...##...', '..##....', '.######.', $e8, $e8)
New-ArtFrame 'anger' @('.##..##.', '..#..#..', '#......#', '##....##', '##....##', '#......#', '..#..#..', '.##..##.')
New-ArtFrame 'spiral' @('#########', '........#', '.######.#', '.#....#.#', '.#.##.#.#', '.#.#..#.#', '.#.####.#', '.#......#', '.########')
$e12 = '............'
New-ArtFrame 'eyes' @($e12, $e12, $e12, '#..........#', '##........##', '+###....###+', '.+##....##+.', $e12, $e12, $e12, $e12, $e12)
New-ArtFrame 'silence' @('.##########.', '#..........#', '#..#....#..#', '#...#..#...#', '#....##....#', '#...#..#...#', '#..#....#..#', '#..........#', '.###..#####.', '...#.#......', '...##.......', $e12)

# shield: whole, cracked, shattered; frames "age" breaks it, "first" keeps it whole. 8x8.
New-ArtFrame 'shield' @('########', '#++##++#', '#++##++#', '#++##++#', '#++##++#', '.#+##+#.', '..####..', '...##...')
New-ArtFrame 'shield' @('###..###', '#++#.++#', '#+..#++#', '#++.#++#', '#++#..+#', '.#+##.#.', '..##.#..', '...##...')
New-ArtFrame 'shield' @('##....##', '#+#..#+#', '#+.....#', $e8, '#+....+#', '.#+..+#.', '..#..#..', $e8)

# reticle: a target ring with crosshair ticks. 16x16.
New-Frame 'reticle' 16 16 {
    param($x, $y)
    $dx = [math]::Abs($x - 7.5); $dy = [math]::Abs($y - 7.5)
    $d = Get-Distance $x $y 7.5 7.5
    if ($d -gt 4.6 -and $d -le 6.0) { return $BRIGHT }
    if (($dx -lt 1 -and $dy -gt 2.5) -or ($dy -lt 1 -and $dx -gt 2.5)) { return $MID }
    if ($dx -lt 1 -and $dy -lt 1) { return $BRIGHT }
    return 0
}.GetNewClosure()

# Variant sheets, for frames "random". 8x8.
New-ArtFrame 'chain' @('..####..', '.#....#.', '.#....#.', '.#....#.', '.#....#.', '.#....#.', '.#....#.', '..####..')
New-ArtFrame 'chain' @('...##...', '...##...', '...##...', '...##...', '...##...', '...##...', '...##...', '...##...')
New-ArtFrame 'petal' @($e8, '...##...', '..###+..', '..##++..', '...++...', $e8, $e8, $e8)
New-ArtFrame 'petal' @($e8, $e8, '..##....', '.###+...', '..#++...', '...+....', $e8, $e8)
New-ArtFrame 'petal' @($e8, '....##..', '...##+..', '..##+...', '..++....', $e8, $e8, $e8)
New-ArtFrame 'digit' @('..####..', '.##..##.', '.##..##.', '.##..##.', '.##..##.', '.##..##.', '..####..', $e8)
New-ArtFrame 'digit' @('...##...', '..###...', '...##...', '...##...', '...##...', '...##...', '..####..', $e8)

# bug: wings up, wings down, four beats over a lifetime. 8x8.
foreach ($beat in 1..4) {
    New-ArtFrame 'bug' @($e8, '.+....+.', '..+..+..', '...##...', '...##...', $e8, $e8, $e8)
    New-ArtFrame 'bug' @($e8, $e8, $e8, '.++##++.', '...##...', $e8, $e8, $e8)
}

# Single icons. 8x8.
New-ArtFrame 'ghost' @('..####..', '.######.', '##.##.##', '##.##.##', '########', '########', '########', '#.##.##.')
New-ArtFrame 'mushroom' @('..####..', '.#+##+#.', '########', '#+####+#', '...++...', '...++...', '...++...', '..++++..')
New-ArtFrame 'gem' @($e8, '..####..', '.#+###+.', '########', '.+####-.', '..+##-..', '...+-...', $e8)
New-ArtFrame 'flask' @('...##...', '...++...', '...##...', '..#..#..', '.#....#.', '.#++++#.', '.#++++#.', '..####..')
New-ArtFrame 'box' @('########', '#+----+#', '#-+--+-#', '#--++--#', '#--++--#', '#-+--+-#', '#+----+#', '########')
New-ArtFrame 'gear' @('...##...', '.#.##.#.', '..####..', '###..###', '###..###', '..####..', '.#.##.#.', '...##...')
New-ArtFrame 'dagger' @('......##', '.....##+', '....##+.', '...##+..', '.-##+...', '.--+....', '---.....', '.-......')
New-ArtFrame 'coral' @('.#...#..', '.#...#.#', '.##.##.#', '..#.#.##', '..###.#.', '...###..', '...##...', '...##...')
New-ArtFrame 'key' @('..###...', '.#...#..', '.#...#..', '..###...', '...#....', '...##...', '...#....', '...##...')
New-ArtFrame 'block' @('########', '#++++++-', '#++++++-', '#++++++-', '#++++++-', '#++++++-', '#++++++-', '#-------')
New-ArtFrame 'screen' @('########', '#------#', '#.++++.#', '#......#', '#.+++..#', '#......#', '#.++...#', '########')

# Single icons. 16x16.
New-ArtFrame 'crescent' @($e16, $e16, $e16, $e16, '......####......', '....########....', '..####++++####..', '.###++....++###.',
    '###+........+###', '##+..........+##', '#+............+#', '+..............+', $e16, $e16, $e16, $e16)
New-ArtFrame 'wings' @($e16, $e16, '.##..........##.', '####........####', '#####......#####', '+#####....#####+', '.+#####..#####+.',
    '..+####..####+..', '...+###..###+...', '....+##..##+....', '.....+#..#+.....', $e16, $e16, $e16, $e16, $e16)
New-ArtFrame 'cloud' @($e16, $e16, $e16, '....######......', '..##########....', '..############..', '################', '################',
    '+##############+', '.++++++++++++++.', '..------------..', $e16, $e16, $e16, $e16, $e16)
New-ArtFrame 'crest' @($e16, $e16, '.....#####......', '...####++###....', '..###+....+##...', '.###+......##...', '.##+.......##...',
    '###+......##....', '###++....###....', '####++++####+...', '######++#####++.', '###############+', '################', '################',
    '++++++++++++++++', '----------------')

# grid: a floor tile of grid lines. Use with shape "plane". 16x16.
New-Frame 'grid' 16 16 { param($x, $y) if (($x % 5) -eq 0 -or ($y % 5) -eq 0) { $BRIGHT } else { 0 } }.GetNewClosure()

# wave: a squiggle for streaks (tethers, vines, tendrils); the four frames make it wriggle. 8x16.
foreach ($phase in 0, 1, 2, 3) {
    New-Frame 'wave' 8 16 {
        param($x, $y)
        $cx = 3.5 + 2.2 * [math]::Sin(($y / 16.0 + $phase / 4.0) * 2 * [math]::PI)
        if ([math]::Abs($x - $cx) -lt 1.1) { $BRIGHT } else { 0 }
    }.GetNewClosure()
}

# crack: fractures that run out from the centre. Use with shape "plane". 16x16.
$crackRays = @(@(0.3, 0.5), @(1.5, -0.4), @(2.6, 0.45), @(3.9, -0.5), @(5.2, 0.4))      # angle, kink (radians)
foreach ($reach in 3.5, 6.0, 8.0) {
    New-Frame 'crack' 16 16 {
        param($x, $y)
        $half = $reach / 2
        foreach ($ray in $crackRays) {
            $mx = 7.5 + $half * [math]::Cos($ray[0]); $my = 7.5 + $half * [math]::Sin($ray[0])
            $ex = $mx + $half * [math]::Cos($ray[0] + $ray[1]); $ey = $my + $half * [math]::Sin($ray[0] + $ray[1])
            if ((Get-SegmentDistance $x $y 7.5 7.5 $mx $my) -lt 0.75) { return $BRIGHT }
            if ((Get-SegmentDistance $x $y $mx $my $ex $ey) -lt 0.6) { return $MID }
        }
        return 0
    }.GetNewClosure()
}

# xslash: two crossing cuts that thin out. 16x16.
foreach ($f in @(@{ t = 2.4; dither = 0 }, @{ t = 1.5; dither = 0 }, @{ t = 0.9; dither = 2 })) {
    New-Frame 'xslash' 16 16 {
        param($x, $y)
        $dx = [math]::Abs($x - 7.5); $dy = [math]::Abs($y - 7.5)
        $far = [math]::Max($dx, $dy)
        if ([math]::Abs($dx - $dy) -gt $f.t * (1 - $far / 9.0)) { return 0 }
        if ($f.dither -gt 0 -and ($x % $f.dither) -eq 0) { return 0 }
        if ($far -lt 4) { return $BRIGHT }
        return $MID
    }.GetNewClosure()
}

# splat: a ragged puddle that spreads. Use with shape "plane". 16x16.
foreach ($r in 3.2, 4.8, 6.0) {
    New-Frame 'splat' 16 16 {
        param($x, $y)
        $a = [math]::Atan2($y - 7.5, $x - 7.5)
        $edge = $r * (1 + 0.16 * [math]::Sin(3 * $a + 1) + 0.1 * [math]::Sin(5 * $a + 2))
        $d = Get-Distance $x $y 7.5 7.5
        if ($d -gt $edge) { return 0 }
        if ($d -gt $edge - 1.2) { return $DIM }
        if ((($x * 3 + $y * 5) % 11) -eq 0) { return $BRIGHT }
        return $MID
    }.GetNewClosure()
}

# Frame lists, one file per sheet.
foreach ($sheet in $sheets.Keys) {
    $names = (0..($sheets[$sheet] - 1) | ForEach-Object { "`"minecraftfx:${sheet}_$_`"" }) -join ', '
    [System.IO.File]::WriteAllText((Join-Path $particleDir "$sheet.json"), "{`"textures`": [$names]}`n")
}

# Optional contact sheet for eyeballing: every frame at 6x nearest-neighbour on a dark background, wrapped into rows.
if ($PreviewPath) {
    $scale = 6; $pad = 6; $maxWidth = 1400
    $placed = @(); $x = $pad; $y = $pad; $rowHeight = 0
    foreach ($f in $frames) {
        $w = $f.Bitmap.Width * $scale; $h = $f.Bitmap.Height * $scale
        if ($x + $w + $pad -gt $maxWidth) { $x = $pad; $y += $rowHeight + $pad; $rowHeight = 0 }
        $placed += @{ Bitmap = $f.Bitmap; X = $x; Y = $y; W = $w; H = $h }
        $x += $w + $pad; $rowHeight = [math]::Max($rowHeight, $h)
    }
    $sheet = New-Object System.Drawing.Bitmap($maxWidth, [int]($y + $rowHeight + $pad))
    $g = [System.Drawing.Graphics]::FromImage($sheet)
    $g.Clear([System.Drawing.Color]::FromArgb(255, 24, 20, 37))
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    foreach ($p in $placed) { $g.DrawImage($p.Bitmap, $p.X, $p.Y, $p.W, $p.H) }
    $g.Dispose()
    $sheet.Save($PreviewPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $sheet.Dispose()
}
$frames | ForEach-Object { $_.Bitmap.Dispose() }
"wrote $($frames.Count) frames in $($sheets.Count) sheets"
