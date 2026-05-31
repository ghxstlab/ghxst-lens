import socket
import struct
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
OUTPUT_H264_FILE = OUTPUT_DIR / "ghxst_lens_test.h264"


@dataclass
class LensPacket:
    packet_type: int
    timestamp_ms: int
    payload: bytes


def read_exact(sock_file, size: int) -> bytes:
    data = sock_file.read(size)

    if data is None or len(data) == 0:
        raise ConnectionError("Connection closed by phone.")

    if len(data) != size:
        raise ConnectionError(
            f"Short read. Expected {size} bytes, received {len(data)} bytes."
        )

    return data


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


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    if OUTPUT_H264_FILE.exists():
        OUTPUT_H264_FILE.unlink()

    print("GHXST Lens Receiver Test - H.264 Test Pattern v0.2D")
    print(f"Connecting to {PHONE_IP}:{PHONE_PORT}...")
    print(f"Header size: {HEADER_SIZE} bytes")
    print(f"Saving H.264 to: {OUTPUT_H264_FILE.resolve()}")
    print()

    with socket.create_connection((PHONE_IP, PHONE_PORT), timeout=10) as sock:
        print("Connected.")
        print()

        sock_file = sock.makefile("rb")

        packet_count = 0
        heartbeat_count = 0
        video_config_count = 0
        video_frame_count = 0
        video_bytes_written = 0

        start_time = time.time()
        last_fps_report_time = start_time
        frames_since_last_report = 0

        with OUTPUT_H264_FILE.open("wb") as h264_file:
            while True:
                packet = read_packet(sock_file)

                packet_count += 1
                elapsed = time.time() - start_time

                if packet.packet_type == PACKET_TYPE_HELLO:
                    payload_text = packet.payload.decode("utf-8", errors="replace")

                    print(
                        f"[{elapsed:8.3f}s] "
                        f"type=HELLO        "
                        f"timestamp={packet.timestamp_ms} "
                        f"payload_len={len(packet.payload)} "
                        f"payload={payload_text}"
                    )

                elif packet.packet_type == PACKET_TYPE_HEARTBEAT:
                    heartbeat_count += 1
                    payload_text = packet.payload.decode("utf-8", errors="replace")

                    print(
                        f"[{elapsed:8.3f}s] "
                        f"type=HEARTBEAT    "
                        f"timestamp={packet.timestamp_ms} "
                        f"payload_len={len(packet.payload)} "
                        f"payload={payload_text}"
                    )

                elif packet.packet_type == PACKET_TYPE_VIDEO_CONFIG:
                    video_config_count += 1

                    h264_file.write(packet.payload)
                    h264_file.flush()
                    video_bytes_written += len(packet.payload)

                    print(
                        f"[{elapsed:8.3f}s] "
                        f"type=VIDEO_CONFIG "
                        f"payload_len={len(packet.payload)} "
                        f"total_h264_bytes={video_bytes_written}"
                    )

                elif packet.packet_type == PACKET_TYPE_VIDEO_FRAME:
                    video_frame_count += 1
                    frames_since_last_report += 1

                    h264_file.write(packet.payload)
                    video_bytes_written += len(packet.payload)

                    now = time.time()
                    report_elapsed = now - last_fps_report_time

                    if report_elapsed >= 1.0:
                        fps = frames_since_last_report / report_elapsed
                        kb_written = video_bytes_written / 1024.0

                        print(
                            f"[{elapsed:8.3f}s] "
                            f"type=VIDEO_FRAME  "
                            f"frames={video_frame_count} "
                            f"payload_len={len(packet.payload)} "
                            f"fps={fps:5.1f} "
                            f"h264_kb={kb_written:8.1f}"
                        )

                        h264_file.flush()
                        frames_since_last_report = 0
                        last_fps_report_time = now

                else:
                    print(
                        f"[{elapsed:8.3f}s] "
                        f"type={packet_type_name(packet.packet_type):<12} "
                        f"timestamp={packet.timestamp_ms} "
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


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped by user.")
        print(f"H.264 file saved to: {OUTPUT_H264_FILE.resolve()}")
    except Exception as exc:
        print(f"Receiver error: {exc}")