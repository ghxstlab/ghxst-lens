import argparse
import queue
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from statistics import mean
from dataclasses import dataclass, replace
from typing import List, Optional, Tuple

import cv2
import numpy as np

DEFAULT_PHONE_IP = "192.168.99.101"
DEFAULT_PHONE_PORT = 9000

DEFAULT_VIDEO_WIDTH = 0
DEFAULT_VIDEO_HEIGHT = 0
DEFAULT_VIDEO_FPS = 0

MAGIC = b"GHXL"

PACKET_TYPE_HELLO = 1
PACKET_TYPE_HEARTBEAT = 2
PACKET_TYPE_VIDEO_CONFIG = 9
PACKET_TYPE_VIDEO_FRAME = 10

HEADER_FORMAT = ">4sBQI"
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)

# Important:
# Do not drop compressed encoded video packets before the decoder.
# encoded video P/B frames depend on previous packets, so dropping compressed packets can freeze decoding.
MAX_H264_QUEUE_PACKETS = 300

# Only keep the latest decoded frames for display.
# Dropping decoded frames is safe; dropping compressed frames is not.
MAX_FRAME_QUEUE = 2

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
class RuntimeConfig:
    phone_ip: str
    phone_port: int
    width: int
    height: int
    fps: int
    codec: str
    show_overlay: bool
    verbose: bool
    display_wait_ms: int
    preview_width: int
    preview_height: int
    decoder_threads: int
    latency_mode: str
    report_interval_sec: float

    @property
    def frame_size(self) -> int:
        return self.preview_width * self.preview_height * 3


class SharedStats:
    """Thread-safe runtime stats for benchmark-style test summaries."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.start_time = time.time()
        self.stop_time: Optional[float] = None

        self.hello_text = ""
        self.last_heartbeat = ""
        self.heartbeat_count = 0

        self.rx_frames = 0
        self.rx_configs = 0
        self.rx_packets = 0
        self.decoder_frames = 0
        self.display_frames = 0

        self.h264_packets = 0
        self.h264_bytes = 0
        self.converted_total = 0
        self.idr_total = 0
        self.sps_total = 0
        self.pps_total = 0
        self.last_nal_summary = ""

        self.rx_fps_latest = 0.0
        self.decoder_fps_latest = 0.0
        self.display_fps_latest = 0.0
        self.h264_kbps_latest = 0.0

        self.last_video_queue = 0
        self.last_frame_queue = 0
        self.latest_frame_id = 0
        self.latest_ui_age_ms = -1
        self.latest_rx_to_display_ms = -1
        self.latest_android_clock_delta_ms = -1

        self.latency_samples_ms: list[int] = []
        self.ui_age_samples_ms: list[int] = []

    def mark_stopped(self) -> None:
        with self.lock:
            self.stop_time = time.time()

    def duration(self) -> float:
        with self.lock:
            end = self.stop_time if self.stop_time is not None else time.time()
            return max(0.001, end - self.start_time)

    def record_hello(self, text: str) -> None:
        with self.lock:
            self.hello_text = text

    def record_heartbeat(self, text: str) -> None:
        with self.lock:
            self.heartbeat_count += 1
            self.last_heartbeat = text

    def record_rx_packet(self, packet_type: int, video_queue_size: int) -> None:
        with self.lock:
            self.rx_packets += 1
            self.last_video_queue = video_queue_size
            if packet_type == PACKET_TYPE_VIDEO_CONFIG:
                self.rx_configs += 1
            elif packet_type == PACKET_TYPE_VIDEO_FRAME:
                self.rx_frames += 1

    def set_rx_window(self, fps: float, video_queue_size: int) -> None:
        with self.lock:
            self.rx_fps_latest = fps
            self.last_video_queue = video_queue_size

    def record_h264_write(
        self,
        byte_count: int,
        nal_types: List[int],
        converted: bool,
        last_nal_summary: str,
        codec: str,
    ) -> None:
        with self.lock:
            self.h264_packets += 1
            self.h264_bytes += byte_count
            if converted:
                self.converted_total += 1
            if codec == "h265":
                # HEVC NAL unit types:
                # 19/20 = IDR, 32 = VPS, 33 = SPS, 34 = PPS.
                if 19 in nal_types or 20 in nal_types:
                    self.idr_total += 1
                if 33 in nal_types:
                    self.sps_total += 1
                if 34 in nal_types:
                    self.pps_total += 1
            else:
                # H.264 NAL unit types:
                # 5 = IDR, 7 = SPS, 8 = PPS.
                if 5 in nal_types:
                    self.idr_total += 1
                if 7 in nal_types:
                    self.sps_total += 1
                if 8 in nal_types:
                    self.pps_total += 1
            if last_nal_summary:
                self.last_nal_summary = last_nal_summary

    def set_h264_window(self, kbps: float) -> None:
        with self.lock:
            self.h264_kbps_latest = kbps

    def record_decoded_frame(self, frame_queue_size: int) -> None:
        with self.lock:
            self.decoder_frames += 1
            self.last_frame_queue = frame_queue_size

    def set_decoder_window(self, fps: float, frame_queue_size: int) -> None:
        with self.lock:
            self.decoder_fps_latest = fps
            self.last_frame_queue = frame_queue_size

    def record_display_window(
        self,
        display_fps: float,
        latest_frame_id: int,
        ui_age_ms: int,
        rx_to_display_ms: int,
        android_clock_delta_ms: int,
        new_frames: int,
    ) -> None:
        with self.lock:
            self.display_fps_latest = display_fps
            self.latest_frame_id = latest_frame_id
            self.latest_ui_age_ms = ui_age_ms
            self.latest_rx_to_display_ms = rx_to_display_ms
            self.latest_android_clock_delta_ms = android_clock_delta_ms
            self.display_frames += new_frames
            if rx_to_display_ms >= 0:
                self.latency_samples_ms.append(rx_to_display_ms)
            if ui_age_ms >= 0:
                self.ui_age_samples_ms.append(ui_age_ms)

    def one_line_report(self) -> str:
        with self.lock:
            return (
                f"[live] "
                f"rx={self.rx_fps_latest:5.1f}fps "
                f"dec={self.decoder_fps_latest:5.1f}fps "
                f"ui={self.display_fps_latest:5.1f}fps "
                f"lat={self.latest_rx_to_display_ms:4d}ms "
                f"ui_age={self.latest_ui_age_ms:3d}ms "
                f"bitrate={self.h264_kbps_latest / 1000.0:5.2f}Mbps "
                f"q={self.last_video_queue}/{self.last_frame_queue} "
                f"frame={self.latest_frame_id}"
            )

    @staticmethod
    def _percentile(values: list[int], percentile: float) -> int:
        if not values:
            return -1
        ordered = sorted(values)
        index = int(round((len(ordered) - 1) * percentile))
        return ordered[max(0, min(index, len(ordered) - 1))]

    def final_summary(self, config: RuntimeConfig) -> str:
        with self.lock:
            end = self.stop_time if self.stop_time is not None else time.time()
            duration = max(0.001, end - self.start_time)
            avg_rx_fps = self.rx_frames / duration
            avg_decoder_fps = self.decoder_frames / duration
            avg_display_fps = self.display_frames / duration
            avg_mbps = (self.h264_bytes * 8.0 / 1_000_000.0) / duration

            latencies = list(self.latency_samples_ms)
            ui_ages = list(self.ui_age_samples_ms)
            latency_min = min(latencies) if latencies else -1
            latency_avg = mean(latencies) if latencies else -1
            latency_p95 = self._percentile(latencies, 0.95)
            latency_max = max(latencies) if latencies else -1
            ui_age_avg = mean(ui_ages) if ui_ages else -1

            lines = [
                "",
                "GHXST Lens Test Summary",
                "------------------------",
                f"Input:          {config.width}x{config.height}@{config.fps}",
                f"Codec:          {config.codec}",
                f"Preview:        {config.preview_width}x{config.preview_height}",
                f"Latency mode:   {config.latency_mode}",
                f"Decoder threads:{config.decoder_threads}",
                f"Duration:       {duration:.1f}s",
                f"RX frames:      {self.rx_frames} ({avg_rx_fps:.1f} fps avg)",
                f"Decoded frames: {self.decoder_frames} ({avg_decoder_fps:.1f} fps avg)",
                f"Displayed new:  {self.display_frames} ({avg_display_fps:.1f} fps avg)",
                f"Bitrate avg:    {avg_mbps:.2f} Mbps",
                f"encoded video packets:  {self.h264_packets}",
                (
                    f"HEVC IDR/SPS/PPS: {self.idr_total}/{self.sps_total}/{self.pps_total}"
                    if config.codec == "h265"
                    else f"H.264 IDR/SPS/PPS: {self.idr_total}/{self.sps_total}/{self.pps_total}"
                ),
                f"Latency samples:{len(latencies)}",
                f"RX→Display:     min={latency_min}ms avg={latency_avg:.1f}ms p95={latency_p95}ms max={latency_max}ms",
                f"UI age avg:     {ui_age_avg:.1f}ms",
            ]

            if self.hello_text:
                lines.append(f"HELLO:          {self.hello_text}")
            if self.last_heartbeat:
                lines.append(f"Last heartbeat: {self.last_heartbeat}")

            lines.append("")
            return "\n".join(lines)


def parse_args() -> RuntimeConfig:
    parser = argparse.ArgumentParser(
        description="GHXST Lens OpenCV/FFmpeg low-latency receiver"
    )

    parser.add_argument("--phone-ip", default=DEFAULT_PHONE_IP, help="Phone IP address")
    parser.add_argument("--port", type=int, default=DEFAULT_PHONE_PORT, help="Phone TCP port")
    parser.add_argument("--width", type=int, default=DEFAULT_VIDEO_WIDTH, help="Manual video width. Leave unset for auto-detect.")
    parser.add_argument("--height", type=int, default=DEFAULT_VIDEO_HEIGHT, help="Manual video height. Leave unset for auto-detect.")
    parser.add_argument("--fps", type=int, default=DEFAULT_VIDEO_FPS, help="Manual input FPS. Leave unset for auto-detect.")
    parser.add_argument(
        "--codec",
        choices=["h264", "h265", "hevc"],
        default="h264",
        help="Manual codec for manual mode. Auto mode reads codec from the phone heartbeat.",
    )
    parser.add_argument(
        "--no-overlay",
        action="store_true",
        help="Disable green debug overlay text on the preview",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Print extra encoded video NAL diagnostics",
    )
    parser.add_argument(
        "--display-wait-ms",
        type=int,
        default=1,
        help="OpenCV waitKey delay in milliseconds. Use 1 for 60 FPS testing; 15 is gentler for 30 FPS.",
    )
    parser.add_argument(
        "--preview-width",
        type=int,
        default=0,
        help="Optional preview/output width. In auto mode, default caps large streams to 1280x720 preview.",
    )
    parser.add_argument(
        "--preview-height",
        type=int,
        default=0,
        help="Optional preview/output height. In auto mode, default caps large streams to 1280x720 preview.",
    )
    parser.add_argument(
        "--decoder-threads",
        type=int,
        default=0,
        help="FFmpeg decoder thread count. 0 = auto. Use 1 for strict baseline, auto for heavier profiles.",
    )
    parser.add_argument(
        "--latency-mode",
        choices=["fast", "stable", "low", "aggressive", "realtime"],
        default="fast",
        help="FFmpeg input latency mode. fast = current GHXST low-latency baseline, stable = safer but higher latency, realtime = unsafe experimental.",
    )
    parser.add_argument(
        "--report-interval",
        type=float,
        default=1.0,
        help="Stats report interval in seconds. Default: 1.0",
    )

    args = parser.parse_args()

    preview_width = args.preview_width if args.preview_width > 0 else args.width
    preview_height = args.preview_height if args.preview_height > 0 else args.height

    return RuntimeConfig(
        phone_ip=args.phone_ip,
        phone_port=args.port,
        width=args.width,
        height=args.height,
        fps=args.fps,
        codec="h265" if args.codec == "hevc" else args.codec,
        show_overlay=not args.no_overlay,
        verbose=args.verbose,
        display_wait_ms=max(1, args.display_wait_ms),
        preview_width=preview_width,
        preview_height=preview_height,
        decoder_threads=max(0, args.decoder_threads),
        latency_mode=args.latency_mode,
        report_interval_sec=max(0.25, args.report_interval),
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

    magic, packet_type, timestamp_ms, payload_length = struct.unpack(
        HEADER_FORMAT,
        header,
    )

    if magic != MAGIC:
        raise ValueError(f"Invalid packet magic: {magic!r}")

    if payload_length > 50_000_000:
        raise ValueError(f"Payload too large: {payload_length}")

    payload = read_exact(sock_file, payload_length) if payload_length > 0 else b""

    return LensPacket(
        packet_type=packet_type,
        timestamp_ms=timestamp_ms,
        payload=payload,
    )


def put_latest_frame(q: queue.Queue, item) -> None:
    while True:
        try:
            q.put_nowait(item)
            return
        except queue.Full:
            try:
                q.get_nowait()
            except queue.Empty:
                return


def has_annex_b_start_code(data: bytes) -> bool:
    return (
        data.startswith(ANNEX_B_4)
        or data.startswith(ANNEX_B_3)
        or ANNEX_B_4 in data[:64]
        or ANNEX_B_3 in data[:64]
    )


def nal_type_name(nal_type: int, codec: str = "h264") -> str:
    if codec == "h265":
        names = {
            19: "IDR_W_RADL",
            20: "IDR_N_LP",
            32: "VPS",
            33: "SPS",
            34: "PPS",
            35: "AUD",
            39: "SEI",
        }
        return names.get(nal_type, f"HEVC_NAL{nal_type}")

    names = {
        1: "non-IDR",
        5: "IDR",
        6: "SEI",
        7: "SPS",
        8: "PPS",
        9: "AUD",
    }
    return names.get(nal_type, f"NAL{nal_type}")


def annex_b_nal_types(data: bytes, codec: str = "h264") -> List[int]:
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

def extract_frame_metadata(packet: LensPacket, pc_receive_ms: int) -> Tuple[bytes, Optional[FrameMetadata]]:
    """
    LT10+ VIDEO_FRAME payload:
    4 bytes  frame magic: GHXF
    8 bytes  frame index
    8 bytes  encoder presentation timestamp us
    1 byte   frame flags
    4 bytes  encoded video payload length
    N bytes  encoded video payload

    Older payloads are still accepted as raw encoded video.
    """
    data = packet.payload

    if len(data) < FRAME_METADATA_SIZE or data[:4] != FRAME_MAGIC:
        return data, None

    magic, frame_index, encoder_pts_us, flags, h264_length = struct.unpack(
        FRAME_METADATA_FORMAT,
        data[:FRAME_METADATA_SIZE],
    )

    if magic != FRAME_MAGIC:
        return data, None

    if h264_length <= 0 or FRAME_METADATA_SIZE + h264_length > len(data):
        return data, None

    h264_payload = data[FRAME_METADATA_SIZE:FRAME_METADATA_SIZE + h264_length]

    return h264_payload, FrameMetadata(
        frame_index=frame_index,
        android_send_ms=packet.timestamp_ms,
        pc_receive_ms=pc_receive_ms,
        encoder_pts_us=encoder_pts_us,
        keyframe=(flags & FRAME_FLAG_KEYFRAME) != 0,
    )


def normalize_h264_payload(data: bytes, codec: str = "h264") -> Tuple[bytes, List[int], bool]:
    """
    FFmpeg raw encoded video input expects Annex-B byte stream.

    Most Android MediaCodec encoded video output we have tested is already Annex-B.
    This function keeps Annex-B payloads unchanged, but also supports
    length-prefixed AVCC-style NAL payloads as a safety fallback.
    """
    if not data:
        return data, [], False

    if has_annex_b_start_code(data):
        return data, annex_b_nal_types(data, codec), False

    # Try 4-byte length-prefixed AVCC.
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

    # Fallback: treat payload as one raw NAL without a start code.
    nal_type = ((data[0] >> 1) & 0x3F) if codec == "h265" and len(data) > 1 else (data[0] & 0x1F)
    return ANNEX_B_4 + data, [nal_type], True


def find_ffmpeg() -> str:
    ffmpeg_path = shutil.which("ffmpeg")

    if not ffmpeg_path:
        raise RuntimeError("ffmpeg was not found in PATH. Install FFmpeg first.")

    return ffmpeg_path


def start_ffmpeg_decoder(config: RuntimeConfig) -> subprocess.Popen:
    ffmpeg_path = find_ffmpeg()

    command = [
        ffmpeg_path,
        "-hide_banner",
        "-loglevel",
        "warning",
    ]

    if config.latency_mode == "stable":
        # Proven baseline. More forgiving, but can add decoder/input buffering.
        command.extend([
            "-probesize",
            "500000",
            "-analyzeduration",
            "500000",
            "-fflags",
            "+genpts",
        ])

    elif config.latency_mode == "fast":
        # Safer low-latency mode for Windows FFmpeg raw encoded video pipe decoding.
        # This keeps enough parser/probe behavior for FFmpeg to stay alive, but removes
        # most of the heavy buffering used by stable mode.
        command.extend([
            "-probesize",
            "32768",
            "-analyzeduration",
            "0",
            "-fflags",
            "+genpts",
            "-flags",
            "low_delay",
            "-flags2",
            "fast",
        ])

    elif config.latency_mode == "low":
        # Lower buffering without the ultra-aggressive flags that previously froze decoding.
        command.extend([
            "-probesize",
            "65536",
            "-analyzeduration",
            "100000",
            "-fflags",
            "+genpts+nobuffer",
            "-flags",
            "low_delay",
        ])

    elif config.latency_mode == "aggressive":
        # Experimental. May reduce latency, but may be less stable on some FFmpeg builds.
        command.extend([
            "-probesize",
            "32768",
            "-analyzeduration",
            "0",
            "-fflags",
            "+genpts+nobuffer",
            "-flags",
            "low_delay",
            "-avioflags",
            "direct",
            "-flush_packets",
            "1",
        ])

    elif config.latency_mode == "realtime":
        # Unsafe experimental mode kept only for comparison.
        # On the current Windows FFmpeg build this can stall or exit, so fast is the recommended mode.
        command.extend([
            "-probesize",
            "8192",
            "-analyzeduration",
            "0",
            "-fflags",
            "+genpts+nobuffer",
            "-flags",
            "low_delay",
            "-flags2",
            "fast",
            "-flush_packets",
            "1",
        ])

    command.extend([
        "-threads",
        str(config.decoder_threads),

        "-f",
        "hevc" if config.codec == "h265" else "h264",
        "-framerate",
        str(config.fps),
        "-i",
        "pipe:0",

        "-an",
    ])

    if config.preview_width != config.width or config.preview_height != config.height:
        command.extend([
            "-vf",
            f"scale={config.preview_width}:{config.preview_height}",
        ])

    command.extend([
        "-f",
        "rawvideo",
        "-pix_fmt",
        "bgr24",
        "pipe:1",
    ])

    print("Starting FFmpeg decoder:")
    print(" ".join(command))
    print()

    return subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )


def ffmpeg_stderr_thread(
    ffmpeg_process: subprocess.Popen,
    stop_event: threading.Event,
) -> None:
    if ffmpeg_process.stderr is None:
        return

    while not stop_event.is_set():
        line = ffmpeg_process.stderr.readline()

        if not line:
            if ffmpeg_process.poll() is not None:
                break
            time.sleep(0.05)
            continue

        text = line.decode("utf-8", errors="replace").strip()
        if text:
            print(f"FFMPEG: {text}")



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


def auto_preview_dimensions(width: int, height: int, requested_width: int, requested_height: int) -> tuple[int, int]:
    """
    Pick a sane preview size for the test receiver.

    Important: this is only the OpenCV preview size, not the phone stream size.
    For OBS testing we want to avoid accidentally benchmarking Python/OpenCV scaling.
    """
    if requested_width > 0 and requested_height > 0:
        return requested_width, requested_height

    if requested_width > 0 and requested_height <= 0:
        return requested_width, max(1, round(requested_width * height / width))

    if requested_height > 0 and requested_width <= 0:
        return max(1, round(requested_height * width / height)), requested_height

    # Keep small/medium streams native. Cap large/HQ streams to a practical 720p preview.
    if width <= 1280 and height <= 720:
        return width, height

    scale = min(1280 / width, 720 / height)
    return max(1, round(width * scale)), max(1, round(height * scale))


def build_detected_config(base_config: RuntimeConfig, heartbeat_text: str) -> Optional[RuntimeConfig]:
    fields = parse_heartbeat_fields(heartbeat_text)

    if fields.get("status") == "error":
        message = fields.get("message", "Unknown phone-side stream error")
        error = fields.get("error", "stream_error")
        raise RuntimeError(f"Phone reported {error}: {message}")

    width = safe_int(fields.get("width"))
    height = safe_int(fields.get("height"))
    fps = safe_int(fields.get("fps"))
    codec = fields.get("codec", base_config.codec).lower().strip()
    if codec == "hevc":
        codec = "h265"
    if codec not in ("h264", "h265"):
        codec = "h264"

    if width <= 0 or height <= 0 or fps <= 0:
        return None

    preview_width, preview_height = auto_preview_dimensions(
        width=width,
        height=height,
        requested_width=base_config.preview_width,
        requested_height=base_config.preview_height,
    )

    return replace(
        base_config,
        width=width,
        height=height,
        fps=fps,
        codec=codec,
        preview_width=preview_width,
        preview_height=preview_height,
    )


def process_receiver_packet(
    packet: LensPacket,
    pc_receive_ms: int,
    config: RuntimeConfig,
    video_queue: queue.Queue[Tuple[int, bytes, Optional[FrameMetadata]]],
    stop_event: threading.Event,
    stats: SharedStats,
    counters: dict[str, int],
    timing: dict[str, float],
) -> None:
    counters["packets"] += 1

    if packet.packet_type == PACKET_TYPE_HELLO:
        text = packet.payload.decode("utf-8", errors="replace")
        stats.record_hello(text)
        print(f"HELLO: {text}")
        return

    if packet.packet_type == PACKET_TYPE_HEARTBEAT:
        counters["heartbeats"] += 1
        text = packet.payload.decode("utf-8", errors="replace")
        stats.record_heartbeat(text)

        if config.verbose:
            print(f"HEARTBEAT: {text}")
        return

    if packet.packet_type not in (PACKET_TYPE_VIDEO_CONFIG, PACKET_TYPE_VIDEO_FRAME):
        return

    if packet.packet_type == PACKET_TYPE_VIDEO_CONFIG:
        counters["configs"] += 1
    else:
        counters["frames"] += 1
        counters["frames_since_report"] += 1

    h264_payload = packet.payload
    frame_metadata = None

    if packet.packet_type == PACKET_TYPE_VIDEO_FRAME:
        h264_payload, frame_metadata = extract_frame_metadata(packet, pc_receive_ms)

    try:
        video_queue.put((packet.packet_type, h264_payload, frame_metadata), timeout=0.5)
        stats.record_rx_packet(packet.packet_type, video_queue.qsize())
    except queue.Full:
        print("WARNING: encoded video queue full. Decoder is falling behind.")
        stop_event.set()
        return

    current_time = time.time()
    if current_time - timing["last_report_time"] >= config.report_interval_sec:
        elapsed = current_time - timing["start_time"]
        rx_fps = counters["frames_since_report"] / (current_time - timing["last_report_time"])
        stats.set_rx_window(rx_fps, video_queue.qsize())

        if config.verbose:
            print(
                f"[rx {elapsed:7.2f}s] "
                f"frames={counters['frames']} "
                f"configs={counters['configs']} "
                f"heartbeats={counters['heartbeats']} "
                f"rx_fps={rx_fps:5.1f} "
                f"video_queue={video_queue.qsize()}"
            )

        counters["frames_since_report"] = 0
        timing["last_report_time"] = current_time


def detect_stream_config(
    config: RuntimeConfig,
    stats: SharedStats,
) -> tuple[RuntimeConfig, socket.socket, object, list[tuple[LensPacket, int]]]:
    print(f"Connecting to GHXST Lens at {config.phone_ip}:{config.phone_port}...")

    sock = socket.create_connection((config.phone_ip, config.phone_port), timeout=10)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock_file = sock.makefile("rb", buffering=0)

    print("Connected to phone.")
    print("Waiting for phone stream config...")
    print()

    inferred_config = config

    pre_packets: list[tuple[LensPacket, int]] = []
    deadline = time.time() + 10.0

    while time.time() < deadline:
        packet = read_packet(sock_file)
        pc_receive_ms = int(time.time() * 1000)
        pre_packets.append((packet, pc_receive_ms))

        if packet.packet_type == PACKET_TYPE_HELLO:
            text = packet.payload.decode("utf-8", errors="replace")
            stats.record_hello(text)
            print(f"HELLO: {text}")

            # Some early packets/older Android builds may expose codec clearly in HELLO
            # before the first heartbeat is parsed. Use it as a provisional codec so
            # FFmpeg never starts H.264 for a HEVC stream. Heartbeat codec= still wins.
            hello_lower = text.lower()
            if "h.265" in hello_lower or "h265" in hello_lower or "hevc" in hello_lower:
                inferred_config = replace(inferred_config, codec="h265")
            elif "h.264" in hello_lower or "h264" in hello_lower:
                inferred_config = replace(inferred_config, codec="h264")
            continue

        if packet.packet_type == PACKET_TYPE_HEARTBEAT:
            text = packet.payload.decode("utf-8", errors="replace")
            stats.record_heartbeat(text)
            detected = build_detected_config(inferred_config, text)

            if detected is not None:
                print(f"Detected stream: {detected.width}x{detected.height}@{detected.fps} ({detected.codec})")
                print(f"Preview output:  {detected.preview_width}x{detected.preview_height}")
                print()
                sock.settimeout(None)
                return detected, sock, sock_file, pre_packets

    raise TimeoutError("Timed out waiting for a heartbeat with width/height/fps from the phone.")


def h264_receiver_thread_from_socket(
    config: RuntimeConfig,
    sock: socket.socket,
    sock_file,
    pre_packets: list[tuple[LensPacket, int]],
    video_queue: queue.Queue[Tuple[int, bytes, Optional[FrameMetadata]]],
    stop_event: threading.Event,
    stats: SharedStats,
) -> None:
    counters = {
        "packets": 0,
        "frames": 0,
        "configs": 0,
        "heartbeats": 0,
        "frames_since_report": 0,
    }
    start_time = time.time()
    timing = {
        "start_time": start_time,
        "last_report_time": start_time,
    }

    try:
        for packet, pc_receive_ms in pre_packets:
            if stop_event.is_set():
                break
            process_receiver_packet(
                packet=packet,
                pc_receive_ms=pc_receive_ms,
                config=config,
                video_queue=video_queue,
                stop_event=stop_event,
                stats=stats,
                counters=counters,
                timing=timing,
            )

        while not stop_event.is_set():
            packet = read_packet(sock_file)
            pc_receive_ms = int(time.time() * 1000)
            process_receiver_packet(
                packet=packet,
                pc_receive_ms=pc_receive_ms,
                config=config,
                video_queue=video_queue,
                stop_event=stop_event,
                stats=stats,
                counters=counters,
                timing=timing,
            )

    except Exception as exc:
        print(f"Receiver thread stopped: {exc}")
        stop_event.set()
    finally:
        try:
            sock_file.close()
        except Exception:
            pass
        try:
            sock.close()
        except Exception:
            pass

def h264_receiver_thread(
    config: RuntimeConfig,
    video_queue: queue.Queue[Tuple[int, bytes, Optional[FrameMetadata]]],
    stop_event: threading.Event,
    stats: SharedStats,
) -> None:
    print(f"Connecting to GHXST Lens at {config.phone_ip}:{config.phone_port}...")

    try:
        with socket.create_connection((config.phone_ip, config.phone_port), timeout=10) as sock:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

            print("Connected to phone.")
            print()

            sock_file = sock.makefile("rb", buffering=0)

            packet_count = 0
            video_frame_count = 0
            video_config_count = 0
            heartbeat_count = 0

            start_time = time.time()
            last_report_time = start_time
            frames_since_report = 0

            while not stop_event.is_set():
                packet = read_packet(sock_file)
                pc_receive_ms = int(time.time() * 1000)
                packet_count += 1

                if packet.packet_type == PACKET_TYPE_HELLO:
                    text = packet.payload.decode("utf-8", errors="replace")
                    stats.record_hello(text)
                    print(f"HELLO: {text}")

                elif packet.packet_type == PACKET_TYPE_HEARTBEAT:
                    heartbeat_count += 1
                    text = packet.payload.decode("utf-8", errors="replace")
                    stats.record_heartbeat(text)

                    if config.verbose:
                        print(f"HEARTBEAT: {text}")

                elif packet.packet_type in (PACKET_TYPE_VIDEO_CONFIG, PACKET_TYPE_VIDEO_FRAME):
                    if packet.packet_type == PACKET_TYPE_VIDEO_CONFIG:
                        video_config_count += 1
                    else:
                        video_frame_count += 1
                        frames_since_report += 1

                    h264_payload = packet.payload
                    frame_metadata = None

                    if packet.packet_type == PACKET_TYPE_VIDEO_FRAME:
                        h264_payload, frame_metadata = extract_frame_metadata(packet, pc_receive_ms)

                    try:
                        video_queue.put((packet.packet_type, h264_payload, frame_metadata), timeout=0.5)
                        stats.record_rx_packet(packet.packet_type, video_queue.qsize())
                    except queue.Full:
                        print("WARNING: encoded video queue full. Decoder is falling behind.")
                        stop_event.set()
                        break

                    current_time = time.time()

                    if current_time - last_report_time >= config.report_interval_sec:
                        elapsed = current_time - start_time
                        rx_fps = frames_since_report / (current_time - last_report_time)
                        stats.set_rx_window(rx_fps, video_queue.qsize())

                        if config.verbose:
                            print(
                                f"[rx {elapsed:7.2f}s] "
                                f"frames={video_frame_count} "
                                f"configs={video_config_count} "
                                f"heartbeats={heartbeat_count} "
                                f"rx_fps={rx_fps:5.1f} "
                                f"video_queue={video_queue.qsize()}"
                            )

                        frames_since_report = 0
                        last_report_time = current_time

    except Exception as exc:
        print(f"Receiver thread stopped: {exc}")
        stop_event.set()


def ffmpeg_writer_thread(
    config: RuntimeConfig,
    ffmpeg_process: subprocess.Popen,
    video_queue: queue.Queue[Tuple[int, bytes, Optional[FrameMetadata]]],
    frame_metadata_queue: queue.Queue[FrameMetadata],
    stop_event: threading.Event,
    stats: SharedStats,
) -> None:
    if ffmpeg_process.stdin is None:
        print("FFmpeg stdin is not available.")
        stop_event.set()
        return

    converted_total = 0
    idr_total = 0
    sps_total = 0
    pps_total = 0

    last_report_time = time.time()
    packets_since_report = 0
    bytes_since_report = 0
    last_nal_summary = ""

    while not stop_event.is_set():
        if ffmpeg_process.poll() is not None:
            print(f"FFmpeg exited with code {ffmpeg_process.returncode}.")
            stop_event.set()
            break

        try:
            packet_type, payload, frame_metadata = video_queue.get(timeout=0.1)
        except queue.Empty:
            continue

        normalized_payload, nal_types, converted = normalize_h264_payload(payload, config.codec)

        if packet_type == PACKET_TYPE_VIDEO_FRAME and frame_metadata is not None:
            try:
                frame_metadata_queue.put_nowait(frame_metadata)
            except queue.Full:
                try:
                    frame_metadata_queue.get_nowait()
                except queue.Empty:
                    pass
                try:
                    frame_metadata_queue.put_nowait(frame_metadata)
                except queue.Full:
                    pass

        packets_since_report += 1
        bytes_since_report += len(normalized_payload)

        if converted:
            converted_total += 1

        if 5 in nal_types:
            idr_total += 1
        if 7 in nal_types:
            sps_total += 1
        if 8 in nal_types:
            pps_total += 1

        if nal_types:
            last_nal_summary = ",".join(nal_type_name(t, config.codec) for t in nal_types[:8])

        stats.record_h264_write(
            len(normalized_payload),
            nal_types,
            converted,
            last_nal_summary,
            config.codec,
        )

        if stop_event.is_set() or ffmpeg_process.stdin.closed:
            break

        try:
            ffmpeg_process.stdin.write(normalized_payload)
        except (BrokenPipeError, ValueError):
            if not stop_event.is_set():
                print("FFmpeg stdin pipe closed.")
            stop_event.set()
            break
        except Exception as exc:
            if not stop_event.is_set():
                print(f"Failed writing encoded video to FFmpeg: {exc}")
            stop_event.set()
            break

        now = time.time()
        if now - last_report_time >= config.report_interval_sec:
            kbps = (bytes_since_report * 8 / 1000.0) / max(0.001, now - last_report_time)
            stats.set_h264_window(kbps)

            if config.verbose:
                print(
                    f"[{config.codec}] packets={packets_since_report} "
                    f"kbps={kbps:7.1f} "
                    f"converted_total={converted_total} "
                    f"idr_total={idr_total} "
                    f"sps_total={sps_total} "
                    f"pps_total={pps_total} "
                    f"last_nals={last_nal_summary}"
                )
            else:
                print(
                    f"[{config.codec}] packets={packets_since_report} "
                    f"kbps={kbps:7.1f} "
                    f"idr_total={idr_total} "
                    f"last_nals={last_nal_summary}"
                )

            packets_since_report = 0
            bytes_since_report = 0
            last_report_time = now


def ffmpeg_frame_reader_thread(
    config: RuntimeConfig,
    ffmpeg_process: subprocess.Popen,
    frame_metadata_queue: queue.Queue[FrameMetadata],
    frame_queue: queue.Queue,
    stop_event: threading.Event,
    stats: SharedStats,
) -> None:
    if ffmpeg_process.stdout is None:
        print("FFmpeg stdout is not available.")
        stop_event.set()
        return

    decoder_frame_count = 0
    start_time = time.time()
    last_report_time = start_time
    frames_since_report = 0

    while not stop_event.is_set():
        if ffmpeg_process.poll() is not None:
            print(f"FFmpeg exited with code {ffmpeg_process.returncode}.")
            stop_event.set()
            break

        try:
            raw_frame = read_exact(ffmpeg_process.stdout, config.frame_size)
        except ConnectionError as exc:
            print(f"FFmpeg decoder stopped producing frames: {exc}")
            stop_event.set()
            break
        except Exception as exc:
            print(f"FFmpeg frame reader error: {exc}")
            stop_event.set()
            break

        frame = np.frombuffer(raw_frame, dtype=np.uint8).copy()
        frame = frame.reshape((config.preview_height, config.preview_width, 3))

        decoder_frame_count += 1
        frames_since_report += 1

        metadata = None
        try:
            metadata = frame_metadata_queue.get_nowait()
        except queue.Empty:
            pass

        put_latest_frame(frame_queue, (decoder_frame_count, time.time(), frame, metadata))
        stats.record_decoded_frame(frame_queue.qsize())

        current_time = time.time()
        if current_time - last_report_time >= config.report_interval_sec:
            elapsed = current_time - start_time
            decoder_fps = frames_since_report / (current_time - last_report_time)

            stats.set_decoder_window(decoder_fps, frame_queue.qsize())

            if config.verbose:
                print(
                    f"[dec {elapsed:7.2f}s] "
                    f"frames={decoder_frame_count} "
                    f"decoder_fps={decoder_fps:5.1f} "
                    f"frame_queue={frame_queue.qsize()}"
                )

            frames_since_report = 0
            last_report_time = current_time


def display_loop(
    config: RuntimeConfig,
    frame_queue: queue.Queue,
    stop_event: threading.Event,
    stats: SharedStats,
) -> None:
    window_name = "GHXST Lens Live Preview"

    cv2.namedWindow(window_name, cv2.WINDOW_NORMAL)
    cv2.resizeWindow(window_name, config.preview_width, config.preview_height)

    print("OpenCV preview started.")
    print("Press Q in the preview window to quit.")
    print()

    last_frame = np.zeros((config.preview_height, config.preview_width, 3), dtype=np.uint8)
    last_frame_id = 0
    last_frame_time = 0.0
    last_metadata: Optional[FrameMetadata] = None

    start_time = time.time()
    last_report_time = start_time
    new_frames_since_report = 0

    while not stop_event.is_set():
        got_new_frame = False

        # Drain to latest frame only. This keeps display latency low.
        while True:
            try:
                frame_id, frame_time, frame, metadata = frame_queue.get_nowait()
                last_frame_id = frame_id
                last_frame_time = frame_time
                last_frame = frame
                last_metadata = metadata
                got_new_frame = True
            except queue.Empty:
                break

        display_frame = last_frame.copy()

        if got_new_frame:
            new_frames_since_report += 1

        now_time = time.time()
        now_ms = int(now_time * 1000)

        # age_ms = decoded-frame age inside the UI loop.
        age_ms = int((now_time - last_frame_time) * 1000) if last_frame_time > 0 else -1

        # rx_to_display_ms = time from PC receiving the encoded frame packet to this display tick.
        # This is reliable because both timestamps are from the PC clock.
        rx_to_display_ms = (
            now_ms - last_metadata.pc_receive_ms
            if last_metadata is not None
            else -1
        )

        # android_clock_delta_ms is only for diagnostics. PC and phone clocks are not synchronized,
        # so this value is not an end-to-end latency number.
        android_clock_delta_ms = (
            last_metadata.pc_receive_ms - last_metadata.android_send_ms
            if last_metadata is not None
            else -1
        )

        if config.show_overlay:
            cv2.putText(
                display_frame,
                f"GHXST | Frame {last_frame_id} | UI {age_ms}ms | RX→Display {rx_to_display_ms}ms",
                (12, 28),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.58,
                (0, 255, 0),
                2,
                cv2.LINE_AA,
            )

        cv2.imshow(window_name, display_frame)

        current_time = time.time()
        if current_time - last_report_time >= config.report_interval_sec:
            elapsed = current_time - start_time
            display_fps = new_frames_since_report / (current_time - last_report_time)
            stats.record_display_window(
                display_fps,
                last_frame_id,
                age_ms,
                rx_to_display_ms,
                android_clock_delta_ms,
                new_frames_since_report,
            )

            if config.verbose:
                print(
                    f"[ui  {elapsed:7.2f}s] "
                    f"display_fps={display_fps:5.1f} "
                    f"latest_frame={last_frame_id} "
                    f"ui_age_ms={age_ms} "
                    f"rx_to_display_ms={rx_to_display_ms} "
                    f"android_clock_delta_ms={android_clock_delta_ms}"
                )
            else:
                print(stats.one_line_report())

            new_frames_since_report = 0
            last_report_time = current_time

        key = cv2.waitKey(config.display_wait_ms) & 0xFF
        if key == ord("q"):
            stop_event.set()
            break

    cv2.destroyAllWindows()



def run_manual_receiver(config: RuntimeConfig) -> None:
    print("GHXST Lens OpenCV Receiver - Manual Baseline v0.2G-CV19")
    print(f"Target: {config.phone_ip}:{config.phone_port}")
    print(f"Input video: {config.width}x{config.height}@{config.fps}")
    print(f"Codec: {config.codec}")
    print(f"Preview output: {config.preview_width}x{config.preview_height}")
    print(f"Decoder threads: {config.decoder_threads} (0 = auto)")
    print(f"Latency mode: {config.latency_mode}")
    print(f"Display wait: {config.display_wait_ms}ms")
    print(f"Report interval: {config.report_interval_sec:.2f}s")
    print()

    ffmpeg_process = start_ffmpeg_decoder(config)

    stop_event = threading.Event()
    stats = SharedStats()
    video_queue: queue.Queue[Tuple[int, bytes, Optional[FrameMetadata]]] = queue.Queue(maxsize=MAX_H264_QUEUE_PACKETS)
    frame_metadata_queue: queue.Queue[FrameMetadata] = queue.Queue(maxsize=MAX_H264_QUEUE_PACKETS)
    frame_queue: queue.Queue = queue.Queue(maxsize=MAX_FRAME_QUEUE)

    threads = [
        threading.Thread(
            target=ffmpeg_stderr_thread,
            args=(ffmpeg_process, stop_event),
            name="GHXST-FFmpeg-Stderr",
            daemon=True,
        ),
        threading.Thread(
            target=h264_receiver_thread,
            args=(config, video_queue, stop_event, stats),
            name="GHXST-H264-Receiver",
            daemon=False,
        ),
        threading.Thread(
            target=ffmpeg_writer_thread,
            args=(config, ffmpeg_process, video_queue, frame_metadata_queue, stop_event, stats),
            name="GHXST-FFmpeg-Writer",
            daemon=False,
        ),
        threading.Thread(
            target=ffmpeg_frame_reader_thread,
            args=(config, ffmpeg_process, frame_metadata_queue, frame_queue, stop_event, stats),
            name="GHXST-FFmpeg-Frame-Reader",
            daemon=False,
        ),
    ]

    for thread in threads:
        thread.start()

    try:
        display_loop(config, frame_queue, stop_event, stats)
    finally:
        stop_event.set()
        shutdown_receiver(ffmpeg_process, threads, stats, config)


def run_auto_receiver(base_config: RuntimeConfig) -> None:
    print("GHXST Lens Auto Receiver - v0.3B-PC3-HEVC4K-FIX3")
    print(f"Target: {base_config.phone_ip}:{base_config.phone_port}")
    print(f"Decoder threads: {base_config.decoder_threads} (0 = auto)")
    print(f"Latency mode: {base_config.latency_mode}")
    print(f"Display wait: {base_config.display_wait_ms}ms")
    print(f"Report interval: {base_config.report_interval_sec:.2f}s")
    print()

    stop_event = threading.Event()
    stats = SharedStats()
    detected_config, sock, sock_file, pre_packets = detect_stream_config(base_config, stats)

    ffmpeg_process = start_ffmpeg_decoder(detected_config)

    video_queue: queue.Queue[Tuple[int, bytes, Optional[FrameMetadata]]] = queue.Queue(maxsize=MAX_H264_QUEUE_PACKETS)
    frame_metadata_queue: queue.Queue[FrameMetadata] = queue.Queue(maxsize=MAX_H264_QUEUE_PACKETS)
    frame_queue: queue.Queue = queue.Queue(maxsize=MAX_FRAME_QUEUE)

    threads = [
        threading.Thread(
            target=ffmpeg_stderr_thread,
            args=(ffmpeg_process, stop_event),
            name="GHXST-FFmpeg-Stderr",
            daemon=True,
        ),
        threading.Thread(
            target=h264_receiver_thread_from_socket,
            args=(detected_config, sock, sock_file, pre_packets, video_queue, stop_event, stats),
            name="GHXST-H264-Receiver-Auto",
            daemon=False,
        ),
        threading.Thread(
            target=ffmpeg_writer_thread,
            args=(detected_config, ffmpeg_process, video_queue, frame_metadata_queue, stop_event, stats),
            name="GHXST-FFmpeg-Writer",
            daemon=False,
        ),
        threading.Thread(
            target=ffmpeg_frame_reader_thread,
            args=(detected_config, ffmpeg_process, frame_metadata_queue, frame_queue, stop_event, stats),
            name="GHXST-FFmpeg-Frame-Reader",
            daemon=False,
        ),
    ]

    for thread in threads:
        thread.start()

    try:
        display_loop(detected_config, frame_queue, stop_event, stats)
    finally:
        stop_event.set()
        shutdown_receiver(ffmpeg_process, threads, stats, detected_config)


def shutdown_receiver(
    ffmpeg_process: subprocess.Popen,
    threads: list[threading.Thread],
    stats: SharedStats,
    config: RuntimeConfig,
) -> None:
    try:
        ffmpeg_process.terminate()
    except Exception:
        pass

    try:
        if ffmpeg_process.stdin and not ffmpeg_process.stdin.closed:
            ffmpeg_process.stdin.close()
    except Exception:
        pass

    for thread in threads:
        thread.join(timeout=1.0)

    try:
        if ffmpeg_process.poll() is None:
            ffmpeg_process.kill()
    except Exception:
        pass

    stats.mark_stopped()
    print(stats.final_summary(config))
    print("Stopped cleanly.")


def main() -> None:
    config = parse_args()

    auto_mode = config.width <= 0 or config.height <= 0 or config.fps <= 0

    if auto_mode:
        run_auto_receiver(config)
    else:
        run_manual_receiver(config)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped by user.")
    except Exception as exc:
        print(f"Receiver error: {exc}")
        sys.exit(1)
