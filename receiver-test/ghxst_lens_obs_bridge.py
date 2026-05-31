#!/usr/bin/env python3
"""
GHXST Lens OBS Bridge - v0.3B-PC5-OBSBridge2

Temporary OBS integration bridge.

Pipeline:
    Android GHXST Lens TCP stream
        -> Python packet/protocol bridge, no video decode
        -> FFmpeg raw H.264/H.265 copy/remux
        -> local UDP MPEG-TS for OBS Media Source

This is intentionally not the final native OBS plugin. It exists to test OBS ingest,
latency, H.264/H.265 behavior, reconnect behavior, and high-res modes with minimal CPU.

OBSBridge2 changes:
    - quieter FFmpeg output by default
    - no repeated codec-config injection by default
    - lower-latency timestamp flags for MPEG-TS remuxing
    - clearer OBS low-latency setup hints
"""

from __future__ import annotations

import argparse
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, replace
from typing import Optional, Tuple, List

DEFAULT_PHONE_IP = "192.168.99.101"
DEFAULT_PHONE_PORT = 9000
DEFAULT_OBS_URL = "udp://127.0.0.1:5000?pkt_size=1316&fifo_size=100000&overrun_nonfatal=1"

MAGIC = b"GHXL"
PACKET_TYPE_HELLO = 1
PACKET_TYPE_HEARTBEAT = 2
PACKET_TYPE_VIDEO_CONFIG = 9
PACKET_TYPE_VIDEO_FRAME = 10
HEADER_FORMAT = ">4sBQI"
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)

ANNEX_B_3 = b"\x00\x00\x01"
ANNEX_B_4 = b"\x00\x00\x00\x01"

FRAME_MAGIC = b"GHXF"
FRAME_METADATA_FORMAT = ">4sQQBI"
FRAME_METADATA_SIZE = struct.calcsize(FRAME_METADATA_FORMAT)
FRAME_FLAG_KEYFRAME = 1


@dataclass
class LensPacket:
    packet_type: int
    timestamp_ms: int
    payload: bytes


@dataclass
class FrameMetadata:
    frame_index: int
    android_send_ms: int
    pc_receive_ms: int
    encoder_pts_us: int
    keyframe: bool


@dataclass
class BridgeConfig:
    phone_ip: str
    phone_port: int
    width: int
    height: int
    fps: int
    codec: str
    obs_url: str
    ffmpeg_loglevel: str
    report_interval_sec: float
    force_codec: str
    inject_config_on_keyframe: bool
    mux_format: str


def parse_args() -> BridgeConfig:
    parser = argparse.ArgumentParser(
        description="GHXST Lens OBS bridge: phone TCP stream -> local OBS UDP source"
    )
    parser.add_argument("--phone-ip", default=DEFAULT_PHONE_IP, help="Phone IP address")
    parser.add_argument("--port", type=int, default=DEFAULT_PHONE_PORT, help="Phone TCP port")
    parser.add_argument(
        "--obs-url",
        default=DEFAULT_OBS_URL,
        help="FFmpeg output URL for OBS. Default: udp://127.0.0.1:5000?pkt_size=1316",
    )
    parser.add_argument(
        "--ffmpeg-loglevel",
        default="error",
        choices=["quiet", "error", "warning", "info", "verbose"],
        help="FFmpeg log level",
    )
    parser.add_argument(
        "--report-interval",
        type=float,
        default=2.0,
        help="Stats report interval in seconds",
    )
    parser.add_argument(
        "--force-codec",
        choices=["auto", "h264", "h265"],
        default="auto",
        help="Force codec if auto-detect is wrong. Default: auto",
    )
    parser.add_argument(
        "--inject-config-on-keyframe",
        action="store_true",
        help="Re-inject cached VPS/SPS/PPS or SPS/PPS before keyframes. Off by default for lower OBS latency and cleaner timestamps.",
    )
    parser.add_argument(
        "--mux-format",
        choices=["mpegts"],
        default="mpegts",
        help="OBS bridge mux format. Currently only MPEG-TS is supported.",
    )
    args = parser.parse_args()

    forced_codec = args.force_codec if args.force_codec in ("h264", "h265") else "h264"
    return BridgeConfig(
        phone_ip=args.phone_ip,
        phone_port=args.port,
        width=0,
        height=0,
        fps=0,
        codec=forced_codec,
        obs_url=args.obs_url,
        ffmpeg_loglevel=args.ffmpeg_loglevel,
        report_interval_sec=max(0.25, args.report_interval),
        force_codec=args.force_codec,
        inject_config_on_keyframe=args.inject_config_on_keyframe,
        mux_format=args.mux_format,
    )


def read_exact(file_obj, size: int) -> bytes:
    chunks: list[bytes] = []
    bytes_read = 0
    while bytes_read < size:
        chunk = file_obj.read(size - bytes_read)
        if chunk is None or len(chunk) == 0:
            raise ConnectionError(
                f"Stream closed. Expected {size} bytes, received {bytes_read} bytes."
            )
        chunks.append(chunk)
        bytes_read += len(chunk)
    return b"".join(chunks)


def read_packet(sock_file) -> LensPacket:
    header = read_exact(sock_file, HEADER_SIZE)
    magic, packet_type, timestamp_ms, payload_length = struct.unpack(HEADER_FORMAT, header)
    if magic != MAGIC:
        raise ValueError(f"Invalid packet magic: {magic!r}")
    if payload_length > 50_000_000:
        raise ValueError(f"Payload too large: {payload_length}")
    payload = read_exact(sock_file, payload_length) if payload_length > 0 else b""
    return LensPacket(packet_type=packet_type, timestamp_ms=timestamp_ms, payload=payload)


def parse_heartbeat_fields(text: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for part in text.split(";"):
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        fields[key.strip()] = value.strip()
    return fields


def safe_int(value: Optional[str], default: int = 0) -> int:
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def infer_codec_from_hello(text: str, current: str) -> str:
    lower = text.lower()
    if "h.265" in lower or "h265" in lower or "hevc" in lower:
        return "h265"
    if "h.264" in lower or "h264" in lower or "avc" in lower:
        return "h264"
    return current


def build_detected_config(base: BridgeConfig, heartbeat_text: str) -> Optional[BridgeConfig]:
    fields = parse_heartbeat_fields(heartbeat_text)
    if fields.get("status") == "error":
        error = fields.get("error", "stream_error")
        message = fields.get("message", "Unknown phone-side stream error")
        raise RuntimeError(f"Phone reported {error}: {message}")

    width = safe_int(fields.get("width"))
    height = safe_int(fields.get("height"))
    fps = safe_int(fields.get("fps"))

    codec = base.codec
    if base.force_codec == "auto":
        codec = fields.get("codec", codec).lower().strip()
        if codec == "hevc":
            codec = "h265"
        if codec not in ("h264", "h265"):
            codec = base.codec
    else:
        codec = base.force_codec

    if width <= 0 or height <= 0 or fps <= 0:
        return None

    return replace(base, width=width, height=height, fps=fps, codec=codec)


def connect_and_detect(config: BridgeConfig) -> tuple[BridgeConfig, socket.socket, object, list[tuple[LensPacket, int]]]:
    print(f"Connecting to GHXST Lens at {config.phone_ip}:{config.phone_port}...")
    sock = socket.create_connection((config.phone_ip, config.phone_port), timeout=10)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock_file = sock.makefile("rb", buffering=0)

    print("Connected to phone.")
    print("Waiting for phone stream config...")
    print()

    inferred = config
    pre_packets: list[tuple[LensPacket, int]] = []
    deadline = time.time() + 10.0

    while time.time() < deadline:
        packet = read_packet(sock_file)
        pc_receive_ms = int(time.time() * 1000)
        pre_packets.append((packet, pc_receive_ms))

        if packet.packet_type == PACKET_TYPE_HELLO:
            text = packet.payload.decode("utf-8", errors="replace")
            print(f"HELLO: {text}")
            if config.force_codec == "auto":
                inferred = replace(inferred, codec=infer_codec_from_hello(text, inferred.codec))
            continue

        if packet.packet_type == PACKET_TYPE_HEARTBEAT:
            text = packet.payload.decode("utf-8", errors="replace")
            detected = build_detected_config(inferred, text)
            if detected is not None:
                print(f"Detected stream: {detected.width}x{detected.height}@{detected.fps} ({detected.codec})")
                print(f"OBS output:      {detected.obs_url}")
                print()
                sock.settimeout(None)
                return detected, sock, sock_file, pre_packets

    raise TimeoutError("Timed out waiting for a heartbeat with width/height/fps from the phone.")


def has_annex_b_start_code(data: bytes) -> bool:
    return (
        data.startswith(ANNEX_B_4)
        or data.startswith(ANNEX_B_3)
        or ANNEX_B_4 in data[:64]
        or ANNEX_B_3 in data[:64]
    )


def annex_b_nal_types(data: bytes, codec: str) -> List[int]:
    nal_types: List[int] = []
    i = 0
    n = len(data)
    while i < n - 4:
        start_len = 0
        if data[i:i + 4] == ANNEX_B_4:
            start_len = 4
        elif data[i:i + 3] == ANNEX_B_3:
            start_len = 3
        if start_len == 0:
            i += 1
            continue
        nal_start = i + start_len
        if nal_start < n:
            if codec == "h265" and nal_start + 1 < n:
                nal_types.append((data[nal_start] >> 1) & 0x3F)
            else:
                nal_types.append(data[nal_start] & 0x1F)
        i = nal_start + 1
    return nal_types


def normalize_payload(data: bytes, codec: str) -> tuple[bytes, List[int], bool]:
    if not data:
        return data, [], False

    if has_annex_b_start_code(data):
        return data, annex_b_nal_types(data, codec), False

    out = bytearray()
    nal_types: List[int] = []
    pos = 0
    converted_any = False
    while pos + 4 <= len(data):
        nal_len = int.from_bytes(data[pos:pos + 4], byteorder="big", signed=False)
        pos += 4
        if nal_len <= 0 or pos + nal_len > len(data):
            converted_any = False
            break
        nal = data[pos:pos + nal_len]
        pos += nal_len
        out += ANNEX_B_4
        out += nal
        if nal:
            nal_types.append(((nal[0] >> 1) & 0x3F) if codec == "h265" and len(nal) > 1 else (nal[0] & 0x1F))
        converted_any = True

    if converted_any and pos == len(data):
        return bytes(out), nal_types, True

    nal_type = ((data[0] >> 1) & 0x3F) if codec == "h265" and len(data) > 1 else (data[0] & 0x1F)
    return ANNEX_B_4 + data, [nal_type], True


def extract_frame_metadata(packet: LensPacket, pc_receive_ms: int) -> tuple[bytes, Optional[FrameMetadata]]:
    data = packet.payload
    if len(data) < FRAME_METADATA_SIZE or data[:4] != FRAME_MAGIC:
        return data, None

    magic, frame_index, encoder_pts_us, flags, payload_length = struct.unpack(
        FRAME_METADATA_FORMAT,
        data[:FRAME_METADATA_SIZE],
    )
    if magic != FRAME_MAGIC:
        return data, None
    if payload_length <= 0 or FRAME_METADATA_SIZE + payload_length > len(data):
        return data, None

    encoded_payload = data[FRAME_METADATA_SIZE:FRAME_METADATA_SIZE + payload_length]
    return encoded_payload, FrameMetadata(
        frame_index=frame_index,
        android_send_ms=packet.timestamp_ms,
        pc_receive_ms=pc_receive_ms,
        encoder_pts_us=encoder_pts_us,
        keyframe=(flags & FRAME_FLAG_KEYFRAME) != 0,
    )


def nal_summary(nal_types: List[int], codec: str) -> str:
    if not nal_types:
        return ""
    if codec == "h265":
        names = {
            1: "TRAIL_R",
            19: "IDR_W_RADL",
            20: "IDR_N_LP",
            32: "VPS",
            33: "SPS",
            34: "PPS",
            35: "AUD",
            39: "SEI",
        }
        return ",".join(names.get(t, f"HEVC_NAL{t}") for t in nal_types[:8])
    names = {1: "non-IDR", 5: "IDR", 6: "SEI", 7: "SPS", 8: "PPS", 9: "AUD"}
    return ",".join(names.get(t, f"NAL{t}") for t in nal_types[:8])


def is_keyframe_nal(nal_types: List[int], codec: str) -> bool:
    if codec == "h265":
        return 19 in nal_types or 20 in nal_types
    return 5 in nal_types


def find_ffmpeg() -> str:
    ffmpeg_path = shutil.which("ffmpeg")
    if not ffmpeg_path:
        raise RuntimeError("ffmpeg was not found in PATH. Install FFmpeg first.")
    return ffmpeg_path


def start_ffmpeg_bridge(config: BridgeConfig) -> subprocess.Popen:
    ffmpeg_path = find_ffmpeg()
    input_format = "hevc" if config.codec == "h265" else "h264"

    command = [
        ffmpeg_path,
        "-hide_banner",
        "-loglevel", config.ffmpeg_loglevel,
        "-probesize", "32768",
        "-analyzeduration", "0",
        "-fflags", "+genpts+nobuffer+igndts",
        "-flags", "low_delay",
        "-use_wallclock_as_timestamps", "1",
        "-f", input_format,
        "-framerate", str(config.fps),
        "-i", "pipe:0",
        "-an",
        "-c:v", "copy",
        "-f", config.mux_format,
        "-muxdelay", "0",
        "-muxpreload", "0",
        "-max_delay", "0",
        "-flush_packets", "1",
        config.obs_url,
    ]

    print("Starting FFmpeg OBS bridge:")
    print(" ".join(command))
    print()

    return subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        bufsize=0,
    )


def ffmpeg_stderr_thread(process: subprocess.Popen, stop_event: threading.Event, show_timestamp_spam: bool = False) -> None:
    if process.stderr is None:
        return
    skipped = 0
    noisy_patterns = (
        "Non-monotonic DTS",
        "Timestamps are unset",
        "not enough frames to estimate rate",
    )
    while not stop_event.is_set():
        line = process.stderr.readline()
        if not line:
            if process.poll() is not None:
                break
            time.sleep(0.05)
            continue
        text = line.decode("utf-8", errors="replace").strip()
        if not text:
            continue
        if not show_timestamp_spam and any(pattern in text for pattern in noisy_patterns):
            skipped += 1
            if skipped in (1, 10, 100):
                print(f"FFMPEG: suppressed {skipped} timestamp/probe warning(s)")
            continue
        print(f"FFMPEG: {text}")


def write_payload(process: subprocess.Popen, payload: bytes) -> bool:
    if process.stdin is None or process.stdin.closed:
        return False
    try:
        process.stdin.write(payload)
        return True
    except (BrokenPipeError, ValueError):
        return False


def bridge_loop(config: BridgeConfig, sock_file, pre_packets: list[tuple[LensPacket, int]], process: subprocess.Popen) -> None:
    start_time = time.time()
    last_report = start_time
    frames = 0
    frames_window = 0
    bytes_total = 0
    bytes_window = 0
    configs = 0
    heartbeats = 0
    cached_config: Optional[bytes] = None
    last_heartbeat = ""
    last_nals = ""
    injected_configs = 0

    def handle_packet(packet: LensPacket, pc_receive_ms: int) -> None:
        nonlocal frames, frames_window, bytes_total, bytes_window, configs, heartbeats
        nonlocal cached_config, last_heartbeat, last_nals, injected_configs, last_report

        if packet.packet_type == PACKET_TYPE_HELLO:
            text = packet.payload.decode("utf-8", errors="replace")
            print(f"HELLO: {text}")
            return

        if packet.packet_type == PACKET_TYPE_HEARTBEAT:
            heartbeats += 1
            last_heartbeat = packet.payload.decode("utf-8", errors="replace")
            return

        if packet.packet_type not in (PACKET_TYPE_VIDEO_CONFIG, PACKET_TYPE_VIDEO_FRAME):
            return

        metadata = None
        encoded_payload = packet.payload
        if packet.packet_type == PACKET_TYPE_VIDEO_FRAME:
            encoded_payload, metadata = extract_frame_metadata(packet, pc_receive_ms)

        normalized, nal_types, _converted = normalize_payload(encoded_payload, config.codec)
        last_nals = nal_summary(nal_types, config.codec)

        if packet.packet_type == PACKET_TYPE_VIDEO_CONFIG:
            configs += 1
            cached_config = normalized
        elif packet.packet_type == PACKET_TYPE_VIDEO_FRAME:
            frames += 1
            frames_window += 1

        should_inject = (
            config.inject_config_on_keyframe
            and packet.packet_type == PACKET_TYPE_VIDEO_FRAME
            and cached_config is not None
            and (
                (metadata is not None and metadata.keyframe)
                or is_keyframe_nal(nal_types, config.codec)
            )
        )
        if should_inject:
            if write_payload(process, cached_config):
                injected_configs += 1
                bytes_total += len(cached_config)
                bytes_window += len(cached_config)

        if not write_payload(process, normalized):
            raise BrokenPipeError("FFmpeg stdin closed")

        bytes_total += len(normalized)
        bytes_window += len(normalized)

        now = time.time()
        if now - last_report >= config.report_interval_sec:
            elapsed = now - start_time
            interval = max(0.001, now - last_report)
            fps = frames_window / interval
            mbps = (bytes_window * 8.0 / 1_000_000.0) / interval
            avg_mbps = (bytes_total * 8.0 / 1_000_000.0) / max(0.001, elapsed)
            print(
                f"[bridge] {config.codec} {config.width}x{config.height}@{config.fps} "
                f"rx={fps:5.1f}fps bitrate={mbps:5.2f}Mbps avg={avg_mbps:5.2f}Mbps "
                f"frames={frames} configs={configs} inject={injected_configs} nals={last_nals}"
            )
            frames_window = 0
            bytes_window = 0
            last_report = now

    try:
        for packet, pc_receive_ms in pre_packets:
            handle_packet(packet, pc_receive_ms)

        while True:
            if process.poll() is not None:
                raise RuntimeError(f"FFmpeg exited with code {process.returncode}")
            packet = read_packet(sock_file)
            handle_packet(packet, int(time.time() * 1000))

    finally:
        duration = max(0.001, time.time() - start_time)
        avg_mbps = (bytes_total * 8.0 / 1_000_000.0) / duration
        print()
        print("GHXST Lens OBS Bridge Summary")
        print("-----------------------------")
        print(f"Input:          {config.width}x{config.height}@{config.fps}")
        print(f"Codec:          {config.codec}")
        print(f"OBS output:     {config.obs_url}")
        print(f"Duration:       {duration:.1f}s")
        print(f"Frames bridged: {frames} ({frames / duration:.1f} fps avg)")
        print(f"Bitrate avg:    {avg_mbps:.2f} Mbps")
        print(f"Config packets: {configs}")
        print(f"Config injects: {injected_configs}")
        if last_heartbeat:
            print(f"Last heartbeat: {last_heartbeat}")
        print()


def main() -> None:
    base_config = parse_args()
    print("GHXST Lens OBS Bridge - v0.3B-PC5-OBSBridge2")
    print(f"Target: {base_config.phone_ip}:{base_config.phone_port}")
    print(f"OBS URL: {base_config.obs_url}")
    print(f"FFmpeg log level: {base_config.ffmpeg_loglevel}")
    print()

    sock = None
    sock_file = None
    process = None
    stop_event = threading.Event()

    try:
        config, sock, sock_file, pre_packets = connect_and_detect(base_config)
        process = start_ffmpeg_bridge(config)

        stderr_thread = threading.Thread(
            target=ffmpeg_stderr_thread,
            args=(process, stop_event, base_config.ffmpeg_loglevel in ("info", "verbose")),
            name="GHXST-OBSBridge-FFmpeg-Stderr",
            daemon=True,
        )
        stderr_thread.start()

        print("OBS bridge is live.")
        print("Add OBS Media Source with input:")
        print(f"  {config.obs_url}")
        print("Recommended OBS Media Source settings:")
        print("  Network Buffering: 0 MB or as low as OBS allows")
        print("  Input Format: mpegts")
        print("  FFmpeg Options: fflags=nobuffer flags=low_delay analyzeduration=0 probesize=32")
        print("Press Ctrl+C here to stop the bridge.")
        print()

        bridge_loop(config, sock_file, pre_packets, process)

    except KeyboardInterrupt:
        print("\nStopped by user.")
    except Exception as exc:
        print(f"Bridge error: {exc}")
        sys.exit_code = 1
    finally:
        stop_event.set()
        try:
            if process and process.stdin and not process.stdin.closed:
                process.stdin.close()
        except Exception:
            pass
        try:
            if process and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1.0)
                except subprocess.TimeoutExpired:
                    process.kill()
        except Exception:
            pass
        try:
            if sock_file:
                sock_file.close()
        except Exception:
            pass
        try:
            if sock:
                sock.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()
