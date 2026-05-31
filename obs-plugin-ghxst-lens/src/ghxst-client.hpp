#pragma once

#include "ghxst-decoder.hpp"
#include "ghxst-protocol.hpp"

#include <atomic>
#include <cstdint>
#include <cstddef>
#include <mutex>
#include <string>
#include <thread>


namespace ghxst {

struct ClientSettings {
	std::string phone_ip = "192.168.99.101";
	int port = 9000;
	bool auto_reconnect = true;
	std::string decode_mode = "software";
};

struct ClientStatus {
	bool running = false;
	bool connected = false;
	bool stream_ready = false;
	bool decoder_open = false;

	uint64_t sequence = 0;

	std::string message;
	std::string last_error;
	std::string hello;
	std::string last_heartbeat;

	StreamInfo stream;

	uint64_t packets = 0;
	uint64_t heartbeats = 0;
	uint64_t video_configs = 0;
	uint64_t video_frames = 0;
	uint64_t decoded_frames = 0;
	uint64_t decode_failures = 0;

	int latest_frame_width = 0;
	int latest_frame_height = 0;
	uint64_t latest_frame_number = 0;

	double packet_fps = 0.0;
	double decoded_fps = 0.0;
	double mbps = 0.0;
	uint64_t last_stats_ms = 0;

	std::string stream_key;
	std::string decode_mode = "software";
};

class GhxstClient {
public:
	GhxstClient();
	~GhxstClient();

	GhxstClient(const GhxstClient &) = delete;
	GhxstClient &operator=(const GhxstClient &) = delete;

	void start(const ClientSettings &settings);
	void stop();
	bool send_control_command(const std::string &command);

	ClientStatus status() const;
	bool latest_frame(DecodedFrame &out) const;

private:
	void thread_main();
	bool connect_socket();
	void close_socket();

	bool recv_exact(uint8_t *buffer, int length);
	bool read_packet(LensPacket &packet);

	void set_status_message(const std::string &message);
	void set_error(const std::string &error);
	void mark_disconnected(const std::string &reason);

	void handle_stream_info(const StreamInfo &info, const std::string &summary);
	void handle_encoded_video_packet(const LensPacket &packet);
	void update_stats_if_needed(uint64_t now_ms, bool force = false);

	ClientSettings settings_;

	std::atomic<bool> running_{false};
	std::thread worker_;
	mutable std::mutex socket_mutex_;

	mutable std::mutex status_mutex_;
	ClientStatus status_;

	mutable std::mutex frame_mutex_;
	DecodedFrame latest_frame_;

	GhxstDecoder decoder_;

	uint64_t stats_window_start_ms_ = 0;
	uint64_t stats_window_packets_ = 0;
	uint64_t stats_window_decoded_ = 0;
	uint64_t stats_window_bytes_ = 0;

#ifdef _WIN32
	std::uintptr_t socket_ = 0;
#endif
};

} // namespace ghxst
