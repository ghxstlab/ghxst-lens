#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#endif

#include "ghxst-client.hpp"

#include <chrono>
#include <sstream>
#include <vector>

#ifdef _WIN32
#pragma comment(lib, "ws2_32.lib")
#endif

namespace ghxst {

static uint64_t now_ms()
{
	using namespace std::chrono;
	return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

static bool ensure_winsock_started()
{
#ifdef _WIN32
	static bool started = false;
	static std::mutex mutex;

	std::lock_guard<std::mutex> lock(mutex);

	if (started) {
		return true;
	}

	WSADATA wsa_data;
	int result = WSAStartup(MAKEWORD(2, 2), &wsa_data);
	if (result != 0) {
		return false;
	}

	started = true;
#endif

	return true;
}

GhxstClient::GhxstClient()
{
}

GhxstClient::~GhxstClient()
{
	stop();
}

void GhxstClient::start(const ClientSettings &settings)
{
	stop();

	settings_ = settings;

	stats_window_start_ms_ = now_ms();
	stats_window_packets_ = 0;
	stats_window_decoded_ = 0;
	stats_window_bytes_ = 0;

	{
		std::lock_guard<std::mutex> lock(status_mutex_);
		status_ = ClientStatus{};
		status_.running = true;
		status_.message = "Starting GHXST Lens client";
		status_.sequence++;
	}

	{
		std::lock_guard<std::mutex> lock(frame_mutex_);
		latest_frame_ = DecodedFrame{};
	}

	running_ = true;
	worker_ = std::thread(&GhxstClient::thread_main, this);
}

void GhxstClient::stop()
{
	running_ = false;
	close_socket();

	if (worker_.joinable()) {
		worker_.join();
	}

	decoder_.close();

	{
		std::lock_guard<std::mutex> lock(status_mutex_);
		status_.running = false;
		status_.connected = false;
		status_.stream_ready = false;
		status_.decoder_open = false;
		status_.message = "Stopped";
		status_.sequence++;
	}
}


bool GhxstClient::send_control_command(const std::string &command)
{
#ifdef _WIN32
	if (command.empty()) {
		return false;
	}

	std::lock_guard<std::mutex> lock(socket_mutex_);
	if (socket_ == 0) {
		set_error("Control command failed: not connected");
		return false;
	}

	std::string line = command;
	if (line.find('\n') == std::string::npos) {
		line += "\n";
	}

	SOCKET s = static_cast<SOCKET>(socket_);
	const char *data = line.c_str();
	int remaining = static_cast<int>(line.size());

	while (remaining > 0) {
		int sent = send(s, data, remaining, 0);
		if (sent <= 0) {
			set_error("Control command failed: socket send failed");
			return false;
		}

		data += sent;
		remaining -= sent;
	}

	{
		std::lock_guard<std::mutex> status_lock(status_mutex_);
		status_.message = "Control command sent";
		status_.last_error.clear();
		status_.sequence++;
	}

	return true;
#else
	(void)command;
	return false;
#endif
}

ClientStatus GhxstClient::status() const
{
	std::lock_guard<std::mutex> lock(status_mutex_);
	return status_;
}

bool GhxstClient::latest_frame(DecodedFrame &out) const
{
	std::lock_guard<std::mutex> lock(frame_mutex_);
	if (!latest_frame_.valid) {
		return false;
	}

	out = latest_frame_;
	return true;
}

void GhxstClient::set_status_message(const std::string &message)
{
	std::lock_guard<std::mutex> lock(status_mutex_);
	status_.message = message;
	status_.last_error.clear();
	status_.sequence++;
}

void GhxstClient::set_error(const std::string &error)
{
	std::lock_guard<std::mutex> lock(status_mutex_);
	status_.last_error = error;
	status_.message = error;
	status_.sequence++;
}

void GhxstClient::mark_disconnected(const std::string &reason)
{
	std::lock_guard<std::mutex> lock(status_mutex_);
	status_.connected = false;
	status_.stream_ready = false;
	status_.decoder_open = false;
	status_.message = reason;
	status_.sequence++;
}

bool GhxstClient::connect_socket()
{
#ifdef _WIN32
	if (!ensure_winsock_started()) {
		set_error("WSAStartup failed");
		return false;
	}

	close_socket();

	std::stringstream port_text;
	port_text << settings_.port;

	addrinfo hints = {};
	hints.ai_family = AF_INET;
	hints.ai_socktype = SOCK_STREAM;
	hints.ai_protocol = IPPROTO_TCP;

	addrinfo *result = nullptr;
	int gai = getaddrinfo(settings_.phone_ip.c_str(), port_text.str().c_str(), &hints, &result);
	if (gai != 0 || !result) {
		set_error("Failed to resolve " + settings_.phone_ip + ":" + port_text.str());
		return false;
	}

	SOCKET s = INVALID_SOCKET;

	for (addrinfo *ptr = result; ptr != nullptr; ptr = ptr->ai_next) {
		s = socket(ptr->ai_family, ptr->ai_socktype, ptr->ai_protocol);
		if (s == INVALID_SOCKET) {
			continue;
		}

		if (connect(s, ptr->ai_addr, static_cast<int>(ptr->ai_addrlen)) == 0) {
			break;
		}

		closesocket(s);
		s = INVALID_SOCKET;
	}

	freeaddrinfo(result);

	if (s == INVALID_SOCKET) {
		set_error("Could not connect to " + settings_.phone_ip + ":" + port_text.str());
		return false;
	}

	int nodelay = 1;
	setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char *>(&nodelay), sizeof(nodelay));

	{
		std::lock_guard<std::mutex> socket_lock(socket_mutex_);
		socket_ = static_cast<std::uintptr_t>(s);
	}

	{
		std::lock_guard<std::mutex> lock(status_mutex_);
		status_.connected = true;
		status_.message = "Connected to " + settings_.phone_ip + ":" + port_text.str();
		status_.sequence++;
	}

	return true;
#else
	set_error("GHXST Lens client currently supports Windows only");
	return false;
#endif
}

void GhxstClient::close_socket()
{
#ifdef _WIN32
	std::lock_guard<std::mutex> lock(socket_mutex_);
	if (socket_ != 0) {
		SOCKET s = static_cast<SOCKET>(socket_);
		shutdown(s, SD_BOTH);
		closesocket(s);
		socket_ = 0;
	}
#endif
}

bool GhxstClient::recv_exact(uint8_t *buffer, int length)
{
#ifdef _WIN32
	int received_total = 0;

	while (running_ && received_total < length) {
		SOCKET s = static_cast<SOCKET>(socket_);
		int received = recv(s, reinterpret_cast<char *>(buffer + received_total), length - received_total, 0);

		if (received <= 0) {
			return false;
		}

		received_total += received;
	}

	return received_total == length;
#else
	(void)buffer;
	(void)length;
	return false;
#endif
}

bool GhxstClient::read_packet(LensPacket &packet)
{
	uint8_t header[17] = {};

	if (!recv_exact(header, sizeof(header))) {
		return false;
	}

	if (header[0] != 'G' || header[1] != 'H' || header[2] != 'X' || header[3] != 'L') {
		set_error("Invalid GHXST packet magic");
		return false;
	}

	packet.packet_type = header[4];
	packet.timestamp_ms = read_be64(header + 5);

	uint32_t payload_length = read_be32(header + 13);
	if (payload_length > 50'000'000) {
		set_error("Payload too large");
		return false;
	}

	packet.payload.clear();
	packet.payload.resize(payload_length);

	if (payload_length > 0 && !recv_exact(packet.payload.data(), static_cast<int>(payload_length))) {
		return false;
	}

	return true;
}

void GhxstClient::handle_stream_info(const StreamInfo &info, const std::string &summary)
{
	std::lock_guard<std::mutex> lock(status_mutex_);

	status_.stream = info;
	status_.stream_ready = info.valid;

	if (info.valid) {
		const std::string new_key = stream_identity_key(info);
		const bool stream_changed = new_key != status_.stream_key;
		status_.stream_key = new_key;

		const bool decoder_needs_open =
			!decoder_.is_open() ||
			decoder_.codec() != info.codec ||
			stream_changed;

		if (decoder_needs_open) {
			if (decoder_.open(info.codec, info.width, info.height, settings_.decode_mode)) {
				status_.decoder_open = true;
				status_.decode_mode = decoder_.decode_mode();
				if (decoder_.last_error().empty()) {
					status_.last_error.clear();
				}
				status_.message = "Decoder opened (" + decoder_.decode_mode() + "): " + summary;
				status_.sequence++;
			} else {
				status_.decoder_open = false;
				status_.decode_failures++;
				status_.last_error = decoder_.last_error();
				status_.message = "Decoder open failed: " + decoder_.last_error();
				status_.sequence++;
			}
		} else if (stream_changed) {
			status_.message = "Stream changed: " + summary;
			status_.sequence++;
		}
	} else {
		status_.message = "Heartbeat received: metadata incomplete";
		status_.sequence++;
	}
}

void GhxstClient::update_stats_if_needed(uint64_t now, bool force)
{
	if (stats_window_start_ms_ == 0) {
		stats_window_start_ms_ = now;
		return;
	}

	const uint64_t elapsed_ms = now - stats_window_start_ms_;
	if (!force && elapsed_ms < 5000) {
		return;
	}

	if (elapsed_ms == 0) {
		return;
	}

	const double seconds = static_cast<double>(elapsed_ms) / 1000.0;

	std::lock_guard<std::mutex> lock(status_mutex_);

	status_.packet_fps = static_cast<double>(stats_window_packets_) / seconds;
	status_.decoded_fps = static_cast<double>(stats_window_decoded_) / seconds;
	status_.mbps = (static_cast<double>(stats_window_bytes_) * 8.0 / 1000000.0) / seconds;
	status_.last_stats_ms = now;

	std::stringstream ss;
	ss.setf(std::ios::fixed);
	ss.precision(1);
	ss << "Stats: rx=" << status_.packet_fps
	   << "fps decoded=" << status_.decoded_fps
	   << "fps bitrate=" << status_.mbps
	   << "Mbps frames=" << status_.video_frames
	   << " decoded=" << status_.decoded_frames
	   << " codec=" << status_.stream.codec
	   << " decode=" << status_.decode_mode;

	status_.message = ss.str();
	status_.sequence++;

	stats_window_start_ms_ = now;
	stats_window_packets_ = 0;
	stats_window_decoded_ = 0;
	stats_window_bytes_ = 0;
}

void GhxstClient::handle_encoded_video_packet(const LensPacket &packet)
{
	EncodedPayload extracted = extract_encoded_payload(packet);
	std::vector<uint8_t> normalized = normalize_annex_b(extracted.data);

	DecodedFrame decoded;
	bool got_frame = false;

	if (decoder_.is_open() && !normalized.empty()) {
		got_frame = decoder_.decode_packet(normalized, decoded);
	}

	const uint64_t now = now_ms();

	{
		std::lock_guard<std::mutex> lock(status_mutex_);

		status_.packets++;
		stats_window_packets_++;
		stats_window_bytes_ += normalized.size();

		if (packet.packet_type == PACKET_TYPE_VIDEO_CONFIG) {
			status_.video_configs++;
		} else {
			status_.video_frames++;
		}

		if (got_frame && decoded.valid) {
			{
				std::lock_guard<std::mutex> frame_lock(frame_mutex_);
				latest_frame_ = decoded;
			}

			status_.decoded_frames++;
			stats_window_decoded_++;
			status_.latest_frame_width = decoded.width;
			status_.latest_frame_height = decoded.height;
			status_.latest_frame_number = decoded.frame_number;
		} else if (decoder_.is_open() && !decoder_.last_error().empty()) {
			status_.decode_failures++;
			if ((status_.decode_failures % 300) == 1) {
				status_.last_error = decoder_.last_error();
				status_.message = "Decode issue: " + decoder_.last_error();
				status_.sequence++;
			}
		}
	}

	update_stats_if_needed(now);
}

void GhxstClient::thread_main()
{
	while (running_) {
		std::stringstream target;
		target << settings_.phone_ip << ":" << settings_.port;

		set_status_message("Connecting to " + target.str());

		if (!connect_socket()) {
			if (!settings_.auto_reconnect || !running_) {
				break;
			}

			std::this_thread::sleep_for(std::chrono::seconds(2));
			continue;
		}

		stats_window_start_ms_ = now_ms();
		stats_window_packets_ = 0;
		stats_window_decoded_ = 0;
		stats_window_bytes_ = 0;

		while (running_) {
			LensPacket packet;

			if (!read_packet(packet)) {
				break;
			}

			std::string payload_text;
			if (!packet.payload.empty()) {
				payload_text.assign(reinterpret_cast<const char *>(packet.payload.data()), packet.payload.size());
			}

			if (packet.packet_type == PACKET_TYPE_HELLO) {
				std::lock_guard<std::mutex> lock(status_mutex_);
				status_.packets++;
				status_.hello = payload_text;
				status_.message = "Phone HELLO received";
				status_.sequence++;
			} else if (packet.packet_type == PACKET_TYPE_HEARTBEAT) {
				StreamInfo info = parse_heartbeat(payload_text);
				std::string summary = stream_info_summary(info);

				{
					std::lock_guard<std::mutex> lock(status_mutex_);
					status_.packets++;
					status_.heartbeats++;
					status_.last_heartbeat = payload_text;
				}

				handle_stream_info(info, summary);
			} else if (packet.packet_type == PACKET_TYPE_VIDEO_CONFIG ||
			           packet.packet_type == PACKET_TYPE_VIDEO_FRAME) {
				handle_encoded_video_packet(packet);
			} else {
				std::lock_guard<std::mutex> lock(status_mutex_);
				status_.packets++;
				status_.sequence++;
			}
		}

		update_stats_if_needed(now_ms(), true);

		close_socket();
		decoder_.close();

		if (!running_) {
			break;
		}

		mark_disconnected("Disconnected from " + target.str());

		if (!settings_.auto_reconnect) {
			break;
		}

		std::this_thread::sleep_for(std::chrono::seconds(2));
	}
}

} // namespace ghxst
