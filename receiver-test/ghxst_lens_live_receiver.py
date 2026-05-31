import shutil
import socket
import struct
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

PHONE_IP = "192.168.99.101"
PHONE_PORT = 9000

MAGIC = b"GHXL"

PACKET_TYPE_HELLO = 1
PACKET_TYPE_HEARTBEAT = 2
PACKET_TYPE_VIDEO_CONFIG = 9
PACKET_TYPE_VIDEO_FRAME = 10

HEADER_FORMAT = ">4sBQI"
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)

OUTPUT_DIR = Path("output")
OUTPUT_H264_FILE = OUTPUT_DIR / "ghxst_lens_live_capture.h264"

ENABLE_SAVE_TO_FILE = False
ENABLE_FFPLAY_PREVIEW = True


@dataclass
class LensPacket:
    packet_type: int
    timestamp_ms: int
    payload: bytes


def now_ms() -> int:
    return int(time.time() * 1000)


def read_exact(sock_file, size: int) -> bytes:
    chunks: list[bytes] = []
    bytes_read = 0

    while bytes_read < size:
        chunk = sock_file.read(size - bytes_read)

        if chunk is None or len(chunk) == 0:
            raise ConnectionError(
                f"Connection closed by phone. Expected {size} bytes, received {bytes_read} bytes."
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


def packet_type_name(packet_type: int) -> str:
    if packet_type == PACKET_TYPE_HELLO:
        return "HELLO"
    if packet_type == PACKET_TYPE_HEARTBEAT:
        return "HEARTBEAT"
    if packet_type == PACKET_TYPE_VIDEO_CONFIG:
        return "VIDEO_CONFIG"
    if packet_type == PACKET_TYPE_VIDEO_FRAME:
        return "VIDEO_FRAME"
    return f"UNKNOWN({packet_type})"


def find_ffplay() -> str | None:
    return shutil.which("ffplay")


def start_ffplay() -> subprocess.Popen | None:
    ffplay_path = find_ffplay()

    if not ffplay_path:
        print("ffplay was not found in PATH.")
        return None

    command = [
        ffplay_path,
        "-hide_banner",
        "-loglevel",
        "warning",

        "-fflags",
        "nobuffer",
        "-flags",
        "low_delay",
        "-avioflags",
        "direct",
        "-framedrop",

        "-probesize",
        "32",
        "-analyzeduration",
        "0",
        "-fpsprobesize",
        "0",

        "-f",
        "h264",

        # Important: do not try to build a perfect playback clock from raw H.264.
        "-sync",
        "video",

        "-i",
        "pipe:0",
    ]

    print("Starting ffplay live preview in diagnostic low-latency mode...")
    print(" ".join(command))
    print()

    return subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.DEVNULL,
        stderr=None,
        bufsize=0,
    )


def write_to_ffplay(ffplay_process: subprocess.Popen | None, data: bytes) -> bool:
    if ffplay_process is None:
        return False

    if ffplay_process.stdin is None:
        return False

    if ffplay_process.poll() is not None:
        return False

    try:
        ffplay_process.stdin.write(data)
        return True
    except BrokenPipeError:
        print("ffplay pipe closed.")
        return False
    except Exception as exc:
        print(f"Failed writing to ffplay: {exc}")
        return False


def stop_ffplay(ffplay_process: subprocess.Popen | None) -> None:
    if ffplay_process is None:
        return

    try:
        if ffplay_process.stdin:
            ffplay_process.stdin.close()
    except Exception:
        pass

    try:
        ffplay_process.terminate()
    except Exception:
        pass


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    if ENABLE_SAVE_TO_FILE and OUTPUT_H264_FILE.exists():
        OUTPUT_H264_FILE.unlink()

    print("GHXST Lens Live Receiver - Latency Diagnostic v0.2G-LT2")
    print(f"Connecting to {PHONE_IP}:{PHONE_PORT}...")
    print(f"Header size: {HEADER_SIZE} bytes")
    print("File saving: disabled for latency test")
    print()

    ffplay_process = start_ffplay() if ENABLE_FFPLAY_PREVIEW else None

    h264_file = None

    try:
        if ENABLE_SAVE_TO_FILE:
            h264_file = OUTPUT_H264_FILE.open("wb")

        with socket.create_connection((PHONE_IP, PHONE_PORT), timeout=10) as sock:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

            print("Connected.")
            print()

            sock_file = sock.makefile("rb", buffering=0)

            packet_count = 0
            heartbeat_count = 0
            video_config_count = 0
            video_frame_count = 0
            video_bytes_written = 0

            start_time = time.time()
            last_fps_report_time = start_time
            frames_since_last_report = 0

            max_packet_latency_ms = 0
            latency_samples = 0
            latency_total_ms = 0

            while True:
                packet = read_packet(sock_file)
                packet_count += 1

                receive_ms = now_ms()
                packet_latency_ms = max(0, receive_ms - packet.timestamp_ms)

                latency_samples += 1
                latency_total_ms += packet_latency_ms
                max_packet_latency_ms = max(max_packet_latency_ms, packet_latency_ms)

                elapsed = time.time() - start_time

                if packet.packet_type == PACKET_TYPE_HELLO:
                    payload_text = packet.payload.decode("utf-8", errors="replace")
                    print(
                        f"[{elapsed:8.3f}s] "
                        f"type=HELLO        "
                        f"latency_ms={packet_latency_ms:<5} "
                        f"payload_len={len(packet.payload)} "
                        f"payload={payload_text}"
                    )

                elif packet.packet_type == PACKET_TYPE_HEARTBEAT:
                    heartbeat_count += 1
                    payload_text = packet.payload.decode("utf-8", errors="replace")
                    print(
                        f"[{elapsed:8.3f}s] "
                        f"type=HEARTBEAT    "
                        f"latency_ms={packet_latency_ms:<5} "
                        f"payload_len={len(packet.payload)} "
                        f"payload={payload_text}"
                    )

                elif packet.packet_type == PACKET_TYPE_VIDEO_CONFIG:
                    video_config_count += 1

                    if h264_file:
                        h264_file.write(packet.payload)
                        h264_file.flush()

                    write_to_ffplay(ffplay_process, packet.payload)
                    video_bytes_written += len(packet.payload)

                    print(
                        f"[{elapsed:8.3f}s] "
                        f"type=VIDEO_CONFIG "
                        f"latency_ms={packet_latency_ms:<5} "
                        f"payload_len={len(packet.payload)} "
                        f"total_h264_bytes={video_bytes_written}"
                    )

                elif packet.packet_type == PACKET_TYPE_VIDEO_FRAME:
                    video_frame_count += 1
                    frames_since_last_report += 1

                    if h264_file:
                        h264_file.write(packet.payload)

                    write_to_ffplay(ffplay_process, packet.payload)
                    video_bytes_written += len(packet.payload)

                    current_time = time.time()
                    report_elapsed = current_time - last_fps_report_time

                    if report_elapsed >= 1.0:
                        fps = frames_since_last_report / report_elapsed
                        kb_written = video_bytes_written / 1024.0
                        avg_latency_ms = latency_total_ms / max(1, latency_samples)

                        print(
                            f"[{elapsed:8.3f}s] "
                            f"type=VIDEO_FRAME  "
                            f"frames={video_frame_count} "
                            f"payload_len={len(packet.payload)} "
                            f"fps={fps:5.1f} "
                            f"pkt_latency_avg={avg_latency_ms:6.1f}ms "
                            f"pkt_latency_max={max_packet_latency_ms:5d}ms "
                            f"h264_kb={kb_written:8.1f}"
                        )

                        if h264_file:
                            h264_file.flush()

                        frames_since_last_report = 0
                        last_fps_report_time = current_time

                        max_packet_latency_ms = 0
                        latency_samples = 0
                        latency_total_ms = 0

                else:
                    print(
                        f"[{elapsed:8.3f}s] "
                        f"type={packet_type_name(packet.packet_type):<12} "
                        f"latency_ms={packet_latency_ms:<5} "
                        f"payload_len={len(packet.payload)}"
                    )

                if packet_count % 300 == 0:
                    print(
                        f"Totals: packets={packet_count}, "
                        f"heartbeats={heartbeat_count}, "
                        f"configs={video_config_count}, "
                        f"video_frames={video_frame_count}, "
                        f"h264_bytes={video_bytes_written}"
                    )

    finally:
        if h264_file:
            h264_file.close()

        stop_ffplay(ffplay_process)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped by user.")
    except Exception as exc:
        print(f"Receiver error: {exc}")
        sys.exit(1)