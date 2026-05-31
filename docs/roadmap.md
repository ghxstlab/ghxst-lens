\# GHXST Lens Roadmap



\## v0.1 - Android Camera Preview



Goal: Get the Android app opening the phone camera and showing a live preview.



\- Android app shell

\- Camera permission

\- Back camera preview

\- Basic dark GHXST-style UI

\- Start/Stop button

\- Display local phone IP



\## v0.2 - Android Stream Sender



Goal: Send a simple LAN video stream from the phone.



\- H.264 hardware encoding

\- 720p30

\- TCP LAN stream

\- No audio

\- Show stream IP and port



\## v0.3 - PC Receiver Test



Goal: Receive the phone stream on the PC before touching OBS.



\- Basic receiver app

\- Confirm packets/frames arrive

\- Print FPS/debug stats

\- Validate stream stability



\## v0.4 - OBS Plugin Prototype



Goal: Add GHXST Lens as a native OBS source.



\- OBS plugin skeleton

\- Source named GHXST Lens

\- Manual IP/port settings

\- Test pattern render

\- Stream receive/render



\## v1.0 - OBS MVP



Goal: Reliable Android phone camera source inside OBS.



\- Low-latency mode

\- 720p/1080p

\- 30/60 FPS options

\- Reconnect handling

\- Multiple phones later

\- No cloud

\- No browser source



\## Future - Security/NVR Mode



\- RTSP mode

\- Snapshot endpoint

\- Motion detection

\- Recording

\- Docker relay

\- Multi-camera dashboard

