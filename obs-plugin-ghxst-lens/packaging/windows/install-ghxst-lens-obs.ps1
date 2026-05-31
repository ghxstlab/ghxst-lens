param(
    [string]$ObsPath = "",
    [switch]$SkipBackup,
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"

function Write-Step($Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Find-ObsPath {
    param([string]$ProvidedPath)

    if ($ProvidedPath -and (Test-Path $ProvidedPath)) {
        return (Resolve-Path $ProvidedPath).Path
    }

    $candidates = @(
        "$env:ProgramFiles\obs-studio",
        "${env:ProgramFiles(x86)}\obs-studio",
        "C:\Program Files\obs-studio",
        "C:\Program Files (x86)\obs-studio"
    ) | Where-Object { $_ -and (Test-Path $_) }

    if ($candidates.Count -gt 0) {
        return $candidates[0]
    }

    throw "Could not find OBS Studio. Re-run with: .\install-ghxst-lens-obs.ps1 -ObsPath `"C:\Program Files\obs-studio`""
}

function Assert-Admin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)) {
        throw "Please run PowerShell as Administrator."
    }
}

Assert-Admin

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..\..") -ErrorAction SilentlyContinue

if (-not $RepoRoot) {
    throw "Could not resolve repository root. Expected this script in obs-plugin-ghxst-lens\packaging\windows."
}

$RepoRoot = $RepoRoot.Path
$PluginRoot = Resolve-Path (Join-Path $RepoRoot "obs-plugin-ghxst-lens") -ErrorAction SilentlyContinue

if (-not $PluginRoot) {
    # If script is already inside obs-plugin-ghxst-lens\packaging\windows, repo root calculation differs.
    $PluginRoot = Resolve-Path (Join-Path $ScriptDir "..\..") -ErrorAction SilentlyContinue
}

if (-not $PluginRoot) {
    throw "Could not resolve obs-plugin-ghxst-lens folder."
}

$PluginRoot = $PluginRoot.Path

$DllSourceCandidates = @(
    (Join-Path $PluginRoot "build\RelWithDebInfo\ghxst-lens.dll"),
    (Join-Path $PluginRoot "build\Release\ghxst-lens.dll"),
    (Join-Path $PluginRoot "build\Debug\ghxst-lens.dll")
)

$DllSource = $DllSourceCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $DllSource) {
    throw "Could not find built ghxst-lens.dll. Build first: cmake --build build --config RelWithDebInfo"
}

$LocaleSource = Join-Path $PluginRoot "data\locale"
if (-not (Test-Path $LocaleSource)) {
    throw "Could not find locale folder: $LocaleSource"
}

$ResolvedObsPath = Find-ObsPath -ProvidedPath $ObsPath
$ObsPluginDir = Join-Path $ResolvedObsPath "obs-plugins\64bit"
$ObsDataDir = Join-Path $ResolvedObsPath "data\obs-plugins\ghxst-lens"
$DllTarget = Join-Path $ObsPluginDir "ghxst-lens.dll"

Write-Host "GHXST Lens OBS Plugin Installer" -ForegroundColor Green
Write-Host "OBS path:       $ResolvedObsPath"
Write-Host "Plugin source:  $DllSource"
Write-Host "Plugin target:  $DllTarget"
Write-Host "Data target:    $ObsDataDir"

$obsRunning = Get-Process -Name "obs64" -ErrorAction SilentlyContinue
if ($obsRunning) {
    throw "OBS Studio is currently running. Close OBS and run the installer again."
}

Write-Step "Creating OBS plugin/data folders"
New-Item -ItemType Directory -Force -Path $ObsPluginDir | Out-Null
New-Item -ItemType Directory -Force -Path $ObsDataDir | Out-Null

if ((Test-Path $DllTarget) -and (-not $SkipBackup)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $backupPath = "$DllTarget.backup-$timestamp"
    Write-Step "Backing up existing plugin DLL"
    Copy-Item $DllTarget $backupPath -Force
    Write-Host "Backup created: $backupPath"
}

Write-Step "Installing GHXST Lens plugin DLL"
Copy-Item $DllSource $DllTarget -Force

Write-Step "Installing GHXST Lens locale/data files"
Copy-Item (Join-Path $LocaleSource "*") $ObsDataDir -Recurse -Force

Write-Step "Install complete"
Write-Host "Installed GHXST Lens OBS plugin successfully." -ForegroundColor Green

if (-not $NoLaunch) {
    $ObsExe = Join-Path $ResolvedObsPath "bin\64bit\obs64.exe"
    if (Test-Path $ObsExe) {
        Write-Host "You can now launch OBS and add source: GHXST Lens Beta"
    }
}
