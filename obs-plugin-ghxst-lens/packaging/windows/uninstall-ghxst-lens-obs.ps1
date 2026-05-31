param(
    [string]$ObsPath = ""
)

$ErrorActionPreference = "Stop"

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

    throw "Could not find OBS Studio. Re-run with: .\uninstall-ghxst-lens-obs.ps1 -ObsPath `"C:\Program Files\obs-studio`""
}

function Assert-Admin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)) {
        throw "Please run PowerShell as Administrator."
    }
}

Assert-Admin

$ResolvedObsPath = Find-ObsPath -ProvidedPath $ObsPath
$DllTarget = Join-Path $ResolvedObsPath "obs-plugins\64bit\ghxst-lens.dll"
$ObsDataDir = Join-Path $ResolvedObsPath "data\obs-plugins\ghxst-lens"

$obsRunning = Get-Process -Name "obs64" -ErrorAction SilentlyContinue
if ($obsRunning) {
    throw "OBS Studio is currently running. Close OBS and run the uninstaller again."
}

Write-Host "GHXST Lens OBS Plugin Uninstaller" -ForegroundColor Yellow
Write-Host "OBS path: $ResolvedObsPath"

if (Test-Path $DllTarget) {
    Remove-Item $DllTarget -Force
    Write-Host "Removed: $DllTarget"
} else {
    Write-Host "DLL not found, skipping: $DllTarget"
}

if (Test-Path $ObsDataDir) {
    Remove-Item $ObsDataDir -Recurse -Force
    Write-Host "Removed: $ObsDataDir"
} else {
    Write-Host "Data folder not found, skipping: $ObsDataDir"
}

Write-Host "Uninstall complete." -ForegroundColor Green
