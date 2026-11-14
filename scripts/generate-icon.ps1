Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$masterSize = 1024
$bmp = New-Object System.Drawing.Bitmap($masterSize, $masterSize)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)

function RoundedPath([System.Drawing.RectangleF]$r, [float]$radius) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $p.AddArc($r.X, $r.Y, $d, $d, 180, 90)
    $p.AddArc($r.Right - $d, $r.Y, $d, $d, 270, 90)
    $p.AddArc($r.Right - $d, $r.Bottom - $d, $d, $d, 0, 90)
    $p.AddArc($r.X, $r.Bottom - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

# Background tile
$bgRect = New-Object System.Drawing.RectangleF(24, 24, 976, 976)
$bgPath = RoundedPath $bgRect 210
$bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.PointF(80, 60)),
    (New-Object System.Drawing.PointF(940, 980)),
    [System.Drawing.Color]::FromArgb(255, 6, 20, 35),
    [System.Drawing.Color]::FromArgb(255, 12, 52, 78)
)
$g.FillPath($bgBrush, $bgPath)

# Ambient glow
$glow = New-Object System.Drawing.Drawing2D.GraphicsPath
$glow.AddEllipse(120, 70, 820, 700)
$glowBrush = New-Object System.Drawing.Drawing2D.PathGradientBrush($glow)
$glowBrush.CenterColor = [System.Drawing.Color]::FromArgb(90, 34, 211, 238)
$glowBrush.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 34, 211, 238))
$g.FillPath($glowBrush, $glow)

# Inner border
$borderPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(70, 125, 211, 252), 5)
$g.DrawPath($borderPen, $bgPath)

# Shield geometry
$shield = New-Object System.Drawing.Drawing2D.GraphicsPath
$shield.AddPolygon(@(
    (New-Object System.Drawing.PointF(512, 160)),
    (New-Object System.Drawing.PointF(786, 268)),
    (New-Object System.Drawing.PointF(768, 548)),
    (New-Object System.Drawing.PointF(668, 716)),
    (New-Object System.Drawing.PointF(512, 848)),
    (New-Object System.Drawing.PointF(356, 716)),
    (New-Object System.Drawing.PointF(256, 548)),
    (New-Object System.Drawing.PointF(238, 268))
))

# Extruded body (2.5D depth)
$depth = New-Object System.Drawing.Drawing2D.GraphicsPath
$depth.AddPolygon(@(
    (New-Object System.Drawing.PointF(512, 206)),
    (New-Object System.Drawing.PointF(786, 314)),
    (New-Object System.Drawing.PointF(768, 594)),
    (New-Object System.Drawing.PointF(668, 762)),
    (New-Object System.Drawing.PointF(512, 894)),
    (New-Object System.Drawing.PointF(356, 762)),
    (New-Object System.Drawing.PointF(256, 594)),
    (New-Object System.Drawing.PointF(238, 314))
))
$depthBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 4, 40, 58))
$g.FillPath($depthBrush, $depth)

# Face gradient
$faceBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.PointF(250, 180)),
    (New-Object System.Drawing.PointF(790, 830)),
    [System.Drawing.Color]::FromArgb(255, 103, 232, 249),
    [System.Drawing.Color]::FromArgb(255, 8, 145, 178)
)
$g.FillPath($faceBrush, $shield)

# Bevel highlight
$highlightPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(190, 224, 255, 255), 14)
$g.DrawPath($highlightPen, $shield)
$innerPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(80, 255, 255, 255), 4)
$inner = New-Object System.Drawing.Drawing2D.GraphicsPath
$inner.AddPolygon(@(
    (New-Object System.Drawing.PointF(512, 196)),
    (New-Object System.Drawing.PointF(752, 292)),
    (New-Object System.Drawing.PointF(736, 540)),
    (New-Object System.Drawing.PointF(648, 688)),
    (New-Object System.Drawing.PointF(512, 806)),
    (New-Object System.Drawing.PointF(376, 688)),
    (New-Object System.Drawing.PointF(288, 540)),
    (New-Object System.Drawing.PointF(272, 292))
))
$g.DrawPath($innerPen, $inner)

# Glass highlight across upper left
$glass = New-Object System.Drawing.Drawing2D.GraphicsPath
$glass.AddEllipse(270, 210, 430, 180)
$glassBrush = New-Object System.Drawing.Drawing2D.PathGradientBrush($glass)
$glassBrush.CenterColor = [System.Drawing.Color]::FromArgb(150, 255, 255, 255)
$glassBrush.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 255, 255, 255))
$g.FillPath($glassBrush, $glass)

# Keyhole
$holeBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 5, 34, 50))
$g.FillEllipse($holeBrush, 438, 356, 148, 148)
$stem = New-Object System.Drawing.Drawing2D.GraphicsPath
$stem.AddPolygon(@(
    (New-Object System.Drawing.PointF(478, 470)),
    (New-Object System.Drawing.PointF(546, 470)),
    (New-Object System.Drawing.PointF(568, 650)),
    (New-Object System.Drawing.PointF(512, 690)),
    (New-Object System.Drawing.PointF(456, 650))
))
$g.FillPath($holeBrush, $stem)

# Keyhole rim
$rimPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(130, 207, 250, 254), 7)
$g.DrawEllipse($rimPen, 438, 356, 148, 148)
$g.DrawPath($rimPen, $stem)

# Small circuit accents
$accentPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(110, 207, 250, 254), 5)
$g.DrawLine($accentPen, 326, 360, 392, 360)
$g.DrawLine($accentPen, 326, 360, 326, 426)
$g.DrawLine($accentPen, 632, 360, 698, 360)
$g.DrawLine($accentPen, 698, 360, 698, 426)
$g.FillEllipse($holeBrush, 316, 350, 22, 22)
$g.FillEllipse($holeBrush, 688, 350, 22, 22)

# Outer shadow/vignette
$vignette = New-Object System.Drawing.Drawing2D.GraphicsPath
$vignette.AddRectangle($bgRect)
$vignetteBrush = New-Object System.Drawing.Drawing2D.PathGradientBrush($vignette)
$vignetteBrush.CenterColor = [System.Drawing.Color]::FromArgb(0, 0, 0, 0)
$vignetteBrush.SurroundColors = @([System.Drawing.Color]::FromArgb(90, 0, 8, 16))
$g.FillPath($vignetteBrush, $bgPath)

$g.Dispose()

$master = Join-Path $root "assets\icon-1024.png"
New-Item -ItemType Directory -Force (Split-Path $master) | Out-Null
$bmp.Save($master, [System.Drawing.Imaging.ImageFormat]::Png)

function Save-Resized([int]$size, [string]$path) {
    $img = New-Object System.Drawing.Bitmap($size, $size)
    $ctx = [System.Drawing.Graphics]::FromImage($img)
    $ctx.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $ctx.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $ctx.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $ctx.DrawImage($bmp, 0, 0, $size, $size)
    $ctx.Dispose()
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    $img.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $img.Dispose()
}

Save-Resized 512 (Join-Path $root "android\app\src\main\res\drawable-nodpi\ic_launcher.png")
Save-Resized 512 (Join-Path $root "android\app\src\main\res\drawable-nodpi\ic_launcher_round.png")
foreach ($size in 48, 96, 128) {
    Save-Resized $size (Join-Path $root "firefox\src\icons\icon-$size.png")
}
Save-Resized 512 (Join-Path $root "docs\icon.png")

$bmp.Dispose()
Write-Host "icon assets generated"
