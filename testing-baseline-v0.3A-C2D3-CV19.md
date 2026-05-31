# GHXST Lens Testing Baseline

## Current Known-Good Baseline

- Android sender: `v0.3A-C2D3`
- Receiver: `v0.2G-CV19`
- Receiver latency mode: `fast`
- Decoder threads: `1`
- Protocol: GHXL over TCP port `9000`
- Frame metadata: GHXF enabled
- Preview while streaming: disabled / locked

## Confirmed Working Profiles

| Profile | Status | Notes |
|---|---:|---|
| 720p60 | Good | Low latency using CV18/CV19 fast mode |
| 1080p30 | Good | Low latency using CV18/CV19 fast mode |
| 1080p60 | Good | Solid baseline, tested around 16 Mbps |

## Current Android Path

```text
Camera2
→ constrained high-speed session where available
→ MediaCodec input Surface
→ H.264 encoder
→ GHXL TCP packets
→ PC receiver
→ FFmpeg subprocess decode
→ raw BGR pipe
→ Python/OpenCV preview
```

## Current Receiver Findings

Recommended:

```text
--latency-mode fast --decoder-threads 1
```

Avoid for now:

```text
--latency-mode realtime
```

`realtime` is unstable on the current Windows FFmpeg build and can freeze, exit early, or produce worse lag than `fast`.

## Test Commands

### 720p60

```powershell
python .\ghxst_lens_cv_receiver.py --width 1280 --height 720 --fps 60 --no-overlay --decoder-threads 1
```

### 1080p30

```powershell
python .\ghxst_lens_cv_receiver.py --width 1920 --height 1080 --fps 30 --preview-width 1280 --preview-height 720 --no-overlay --decoder-threads 1
```

### 1080p60

```powershell
python .\ghxst_lens_cv_receiver.py --width 1920 --height 1080 --fps 60 --preview-width 1280 --preview-height 720 --no-overlay --decoder-threads 1
```

Add `--verbose` when deeper logs are needed.

## CV19 Receiver Improvements

- Keeps `fast` as the default baseline.
- Adds cleaner one-line live reporting in normal mode.
- Keeps full heartbeat/NAL/packet output behind `--verbose`.
- Adds final test summary on quit:
  - duration
  - RX FPS average
  - decoder FPS average
  - display FPS average
  - average bitrate
  - H.264 packet counts
  - IDR/SPS/PPS counts
  - RX-to-display min/avg/p95/max latency
  - last heartbeat metadata

## Current Notes

- Camera2 high-speed mode is active, but Samsung may report/use `[30, 120]` rather than fixed `[60, 60]`.
- FPS can float depending on camera/exposure/lighting, even when the selected profile is 60fps.
- Python/OpenCV receiver is still a test receiver, not the final OBS/native rendering path.
- The future OBS/native receiver path should reduce copies and avoid the Python + raw BGR pipe overhead.
