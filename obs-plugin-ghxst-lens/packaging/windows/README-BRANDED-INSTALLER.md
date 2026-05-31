# GHXST Lens OBS Plugin - Branded Installer

This folder contains the Inno Setup installer script and branding assets.

## Files

```text
GHXST-Lens-OBS-Plugin.iss
assets/ghxst-lens.ico
assets/wizard-side.bmp
assets/wizard-small.bmp
```

## Build

From this folder:

```powershell
& "$env:LOCALAPPDATA\Programs\Inno Setup 7\ISCC.exe" .\GHXST-Lens-OBS-Plugin.iss
```

Output:

```text
dist/GHXST-Lens-OBS-Plugin-0.5.0-beta.1-Setup.exe
```
