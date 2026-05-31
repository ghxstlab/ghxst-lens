param(
    [string]$Configuration = "RelWithDebInfo",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PluginRoot = Resolve-Path (Join-Path $ScriptDir "..\..")

$Version = "0.5.0-beta.1"
$PackageName = "GHXST-Lens-OBS-Plugin-$Version-Windows"
$ReleaseRoot = Join-Path $PluginRoot "release"
$DistDir = Join-Path $ReleaseRoot $PackageName
$ZipPath = Join-Path $ReleaseRoot "$PackageName.zip"

if (-not $SkipBuild) {
    Push-Location $PluginRoot
    cmake -S . -B build -G "Visual Studio 17 2022" -A x64
    cmake --build build --config $Configuration
    Pop-Location
}

$DllPath = Join-Path $PluginRoot "build\$Configuration\ghxst-lens.dll"
$LocalePath = Join-Path $PluginRoot "data\locale"

if (-not (Test-Path $DllPath)) {
    throw "Missing DLL: $DllPath"
}
if (-not (Test-Path $LocalePath)) {
    throw "Missing locale folder: $LocalePath"
}

New-Item -ItemType Directory -Force -Path $ReleaseRoot | Out-Null

if (Test-Path $DistDir) {
    Remove-Item $DistDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path (Join-Path $DistDir "obs-plugins\64bit") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $DistDir "data\obs-plugins\ghxst-lens\locale") | Out-Null

Copy-Item $DllPath (Join-Path $DistDir "obs-plugins\64bit\ghxst-lens.dll") -Force
Copy-Item (Join-Path $LocalePath "*") (Join-Path $DistDir "data\obs-plugins\ghxst-lens\locale") -Recurse -Force

@"
GHXST Lens OBS Plugin $Version

Manual install:
Copy these folders into your OBS Studio install folder:

obs-plugins\64bit\ghxst-lens.dll
data\obs-plugins\ghxst-lens\locale\*

Default OBS install:
C:\Program Files\obs-studio

Close OBS before replacing files.
"@ | Set-Content -Encoding UTF8 (Join-Path $DistDir "README-INSTALL.txt")

if (Test-Path $ZipPath) {
    Remove-Item $ZipPath -Force
}

Compress-Archive -Path (Join-Path $DistDir "*") -DestinationPath $ZipPath -Force

Write-Host "Created portable package:" -ForegroundColor Green
Write-Host $ZipPath
