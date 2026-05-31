# GHXST Lens OBS Plugin - Windows Installer Scripts

These are local beta installer scripts for the GHXST Lens OBS plugin.

## What this installs

The installer copies:

- `obs-plugin-ghxst-lens/build/RelWithDebInfo/ghxst-lens.dll`
- `obs-plugin-ghxst-lens/data/locale/*`

Into your OBS Studio installation:

- `C:\Program Files\obs-studio\obs-plugins\64bit\ghxst-lens.dll`
- `C:\Program Files\obs-studio\data\obs-plugins\ghxst-lens\`

## Before installing

Build the plugin first:

```powershell
cd C:\ghxst\ghxst-lens\obs-plugin-ghxst-lens
cmake -S . -B build -G "Visual Studio 17 2022" -A x64
cmake --build build --config RelWithDebInfo
```

Close OBS completely.

## Install

Run PowerShell as Administrator:

```powershell
cd C:\ghxst\ghxst-lens\obs-plugin-ghxst-lens\packaging\windows
.\install-ghxst-lens-obs.ps1
```

Or double-click:

```text
install-ghxst-lens-obs.bat
```

## Custom OBS path

```powershell
.\install-ghxst-lens-obs.ps1 -ObsPath "D:\Apps\obs-studio"
```

## Uninstall

```powershell
.\uninstall-ghxst-lens-obs.ps1
```

Or double-click:

```text
uninstall-ghxst-lens-obs.bat
```

## Notes

- OBS must be closed before installing.
- The installer backs up an existing `ghxst-lens.dll` automatically.
- This is a beta local installer, not a signed public Windows installer yet.
