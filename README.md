# GHXST Lens

**GHXST Lens** is a low-latency Android camera source system for **OBS Studio**.

It lets an Android phone act as a clean native OBS camera source over your local network, without browser sources, cloud relays, subscription middleware, or unnecessary desktop bloat.

```text
Android Camera2 + MediaCodec
→ TCP LAN stream
→ Native OBS plugin
→ D3D11 direct shared texture decode/render path
→ OBS scene
```

> Current release: **0.5.0-beta.1**  
> Status: **Main beta build**

---

## Why GHXST Lens exists

Most phone-to-OBS camera workflows rely on one or more of the following:

- browser sources
- virtual webcam layers
- cloud accounts
- paid middleware
- heavy desktop companion apps
- high-latency network video paths
- unnecessary CPU usage

GHXST Lens is built around a simpler goal:

> Use the phone camera hardware properly, stream over LAN, receive directly inside OBS, and keep CPU usage extremely low.

---

## Current beta highlights

The current beta includes:

- Native OBS source: **GHXST Lens Beta**
- Android Camera2 capture
- Android MediaCodec hardware encoding
- H.264 / AVC support
- H.265 / HEVC support
- Direct TCP LAN streaming
- Native OBS plugin receiver
- D3D11VA direct shared texture decode path on Windows
- Very low OBS CPU usage in testing, usually below 1%
- 4K30 H.265 mode
- 1080p60 and 720p60 modes
- OBS-side video mode control
- OBS-side codec selection
- OBS-side lens selection
- OBS-side zoom control
- Auto reconnect
- Windows installer for the OBS plugin
- Android beta versioning and launcher branding

---

## Current limitations

This is still a beta.

Known limitations:

- Windows + OBS Studio is the main supported desktop path right now.
- Android app must be installed separately.
- Phone and OBS PC must be on the same LAN.
- Audio is not implemented yet.
- 1080p60 works, but on the tested phone it behaves closer to roughly 50 FPS.
- 360p60 was removed from the OBS controls because it caused reconnect loops on the tested device.
- Direct GPU mode depends on Windows/D3D11 support.
- Auto-discovery is not implemented yet; phone IP is entered manually.
- Public release signing and Play Store publishing are still in progress.

---

## Tested environment

The current beta has been developed and tested with:

```text
Windows 11
OBS Studio 32.1.2
Visual Studio 2022 Build Tools / MSVC
CMake
Android Studio
Android Camera2
MediaCodec H.264 / H.265
```

OBS plugin build dependencies currently use:

```text
obs-studio source: 31.1.1
obs-deps: 2025-07-11
Qt6 deps: 2025-07-11
```

The plugin is currently compatible with the tested OBS 32.1.2 install because it uses core libobs APIs and OBS dependency FFmpeg libraries.

---

## Project structure

```text
ghxst-lens/
├─ android/
│  └─ app/
│     └─ src/main/java/st/ghx/lens/
│        ├─ MainActivity.kt
│        └─ LensStreamService.kt
│
├─ obs-plugin-ghxst-lens/
│  ├─ src/
│  │  ├─ plugin-main.cpp
│  │  ├─ ghxst-lens-source.cpp
│  │  ├─ ghxst-lens-source.hpp
│  │  ├─ ghxst-client.cpp
│  │  ├─ ghxst-client.hpp
│  │  ├─ ghxst-protocol.cpp
│  │  ├─ ghxst-protocol.hpp
│  │  ├─ ghxst-decoder.cpp
│  │  └─ ghxst-decoder.hpp
│  │
│  ├─ data/
│  │  └─ locale/
│  │     └─ en-US.ini
│  │
│  └─ packaging/
│     └─ windows/
│        ├─ GHXST-Lens-OBS-Plugin.iss
│        ├─ package-ghxst-lens-release.ps1
│        ├─ install-ghxst-lens-obs.ps1
│        ├─ uninstall-ghxst-lens-obs.ps1
│        └─ assets/
│
├─ receiver-test/
│  ├─ ghxst_lens_auto_receiver.py
│  └─ ghxst_lens_obs_bridge.py
│
├─ docs/
├─ README.md
├─ architecture.md
└─ roadmap.md
```

---

## How it works

### Android side

The Android app uses:

```text
Camera2
→ MediaCodec hardware encoder
→ TCP socket server on port 9000
```

The stream includes:

- HELLO packets
- heartbeat metadata
- video configuration packets
- encoded video frames
- frame metadata

The Android app currently supports simplified video profiles such as:

```text
720p30
720p60
1080p30
1080p60
4K30
```

Codec support:

```text
H.264 / AVC
H.265 / HEVC
```

### OBS plugin side

The OBS plugin connects directly to the phone:

```text
Phone IP + port 9000
→ GHXST protocol parser
→ FFmpeg/libavcodec
→ D3D11VA direct shared texture path
→ OBS texture/render
```

The current preferred path is:

```text
H.264/H.265 encoded stream
→ FFmpeg D3D11VA hardware decode
→ D3D11 video processor
→ shared D3D11 texture
→ OBS render
```

This avoids the expensive CPU path:

```text
GPU decode
→ CPU readback
→ CPU swscale conversion
→ CPU-to-GPU BGRA upload
```

---

## OBS plugin performance modes

The OBS source includes a **Performance mode** option.

Recommended:

```text
Auto - Best available
```

Current modes:

```text
Auto - Best available
Direct GPU - Lowest CPU
Hardware decode - Compatibility
Software decode - Fallback
```

Best current mode:

```text
Direct GPU - Lowest CPU
```

Successful direct GPU logs look like:

```text
Decoder opened (d3d11va_direct): 3840x2160@30 h265 ...
Opened shared D3D11 video texture 3840x2160
Stats: rx=30.0fps decoded=30.0fps ... decode=d3d11va_direct
```

---

## OBS camera controls

The OBS plugin can control several Android camera/stream settings.

Current controls:

```text
Phone video mode
Phone codec
Phone lens
Phone zoom
Apply video / lens
Apply zoom
Reconnect now
Disconnect
```

Recommended beta settings:

```text
Performance mode: Auto - Best available
Phone video mode: 4K30 - Sharp / Recommended
Phone codec: H.265 / HEVC
Phone lens: Auto or Back main / wide
Phone zoom: 1.0x
```

---

## Installing the OBS plugin

### Option 1: Windows installer

Download or build:

```text
GHXST-Lens-OBS-Plugin-0.5.0-beta.1-Setup.exe
```

Close OBS, then run the installer.

Default OBS install path:

```text
C:\Program Files\obs-studio
```

The installer copies:

```text
ghxst-lens.dll
```

to:

```text
C:\Program Files\obs-studio\obs-plugins\64bit\
```

and locale/data files to:

```text
C:\Program Files\obs-studio\data\obs-plugins\ghxst-lens\
```

### Option 2: Portable ZIP/manual install

Portable ZIP layout:

```text
obs-plugins\64bit\ghxst-lens.dll
data\obs-plugins\ghxst-lens\locale\en-US.ini
```

Copy those folders into your OBS installation folder.

### Option 3: Local installer script

Run PowerShell as Administrator:

```powershell
cd C:\ghxst\ghxst-lens\obs-plugin-ghxst-lens\packaging\windows

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\install-ghxst-lens-obs.ps1
```

---

## Building the OBS plugin

From the plugin folder:

```powershell
cd C:\ghxst\ghxst-lens\obs-plugin-ghxst-lens

cmake -S . -B build -G "Visual Studio 17 2022" -A x64
cmake --build build --config RelWithDebInfo
```

Output DLL:

```text
obs-plugin-ghxst-lens\build\RelWithDebInfo\ghxst-lens.dll
```

---

## Building the Windows release package

Portable ZIP:

```powershell
cd C:\ghxst\ghxst-lens\obs-plugin-ghxst-lens\packaging\windows

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\package-ghxst-lens-release.ps1
```

Expected output:

```text
obs-plugin-ghxst-lens\release\GHXST-Lens-OBS-Plugin-0.5.0-beta.1-Windows.zip
```

Installer EXE:

```powershell
cd C:\ghxst\ghxst-lens\obs-plugin-ghxst-lens\packaging\windows

& "$env:LOCALAPPDATA\Programs\Inno Setup 7\ISCC.exe" .\GHXST-Lens-OBS-Plugin.iss
```

Expected output:

```text
obs-plugin-ghxst-lens\packaging\windows\dist\GHXST-Lens-OBS-Plugin-0.5.0-beta.1-Setup.exe
```

---

## Android app

The Android app is currently versioned as:

```text
0.5.0-beta.1
```

Debug builds may install as a separate app package if the debug application ID suffix is enabled.

The Android app should eventually be distributed through Google Play internal testing / closed beta to avoid sideloading friction for testers.

---

## Android release / Play beta plan

Planned beta rollout:

```text
Internal testing
→ Closed testing
→ Open beta
→ Production later
```

Release artifact:

```text
Android App Bundle (.aab)
```

Suggested first release name:

```text
GHXST Lens 0.5.0 Beta 1
```

Suggested first Android artifact name:

```text
GHXST-Lens-Android-0.5.0-beta.1.aab
```

---

## GitHub release artifacts

Suggested GitHub Release:

```text
GHXST Lens 0.5.0 Beta 1
```

Suggested tag:

```text
beta-0.5.0-beta.1
```

Suggested artifacts:

```text
GHXST-Lens-OBS-Plugin-0.5.0-beta.1-Setup.exe
GHXST-Lens-OBS-Plugin-0.5.0-beta.1-Windows.zip
GHXST-Lens-Android-0.5.0-beta.1.aab
```

---

## Troubleshooting

### OBS cannot connect to the phone

Check:

```text
Phone and PC are on the same LAN
Android app stream is started
Correct phone IP is entered in OBS
Port is 9000
Windows firewall is not blocking OBS
```

### OBS source appears but video is black

Check OBS logs for:

```text
Decoder opened
Opened shared D3D11 video texture
```

If direct GPU fails, try:

```text
Performance mode: Hardware decode - Compatibility
```

or:

```text
Performance mode: Software decode - Fallback
```

### OBS CPU usage is high

Use:

```text
Performance mode: Auto - Best available
```

Good direct GPU logs:

```text
decode=d3d11va_direct
Opened shared D3D11 video texture
```

If OBS logs mention CPU-upload video texture, the direct GPU path may have fallen back.

### 1080p60 does not feel like true 60 FPS

Known beta behavior on the tested phone. The OBS plugin decodes what it receives, but the phone may deliver closer to 50 FPS depending on Camera2 mode, encoder load, lighting, and thermal conditions.

### 360p60 mode reconnect loop

The unstable 360p60 mode has been removed from OBS controls. Update both the Android app and OBS plugin to the latest beta build.

---

## Roadmap

Near-term:

- README/docs cleanup
- GitHub Releases
- OBS Windows installer polish
- Google Play internal testing
- Android signed AAB release workflow
- Better 1080p60 tuning
- Cleaner app UI / branding polish

Future:

- Audio support
- Auto-discovery
- Better phone-to-OBS pairing flow
- Multi-phone support
- More camera controls
- NVR/security mode
- RTSP or relay mode
- Cross-platform plugin investigation

---

## Project status

GHXST Lens is currently in active beta development.

The core beta milestone is complete:

```text
Android phone camera
→ low-latency LAN stream
→ native OBS source
→ direct GPU decode/render
→ sub-1% OBS CPU in testing
```

---

## License

No license has been selected yet.

Until a license is added, all rights are reserved by the project owner.
