#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace ghxst {

static constexpr char MAGIC[4] = {'G', 'H', 'X', 'L'};

static constexpr uint8_t PACKET_TYPE_HELLO = 1;
static constexpr uint8_t PACKET_TYPE_HEARTBEAT = 2;
static constexpr uint8_t PACKET_TYPE_VIDEO_CONFIG = 9;
static constexpr uint8_t PACKET_TYPE_VIDEO_FRAME = 10;

static constexpr char FRAME_MAGIC[4] = {'G', 'H', 'X', 'F'};
static constexpr uint8_t FRAME_FLAG_KEYFRAME = 1;

struct LensPacket {
	uint8_t packet_type = 0;
	uint64_t timestamp_ms = 0;
	std::vector<uint8_t> payload;
};

struct FrameMetadata {
	uint64_t frame_index = 0;
	uint64_t encoder_pts_us = 0;
	bool keyframe = false;
};

struct EncodedPayload {
	std::vector<uint8_t> data;
	FrameMetadata metadata;
	bool has_metadata = false;
};

struct StreamInfo {
	bool valid = false;

	int width = 0;
	int height = 0;
	int fps = 0;
	int bitrate = 0;
	int iframe_interval = 0;

	std::string codec;
	std::string profile;
	std::string status;
	std::string version;
	std::string input;
	std::string camera2_id;
	std::string camera_requested;
	std::string camera2_mode;
	std::string zoom_requested;
	std::string zoom_applied;
	std::string encoded_fps_estimate;
	std::string encoder_latency;
	std::string control_status;
};

uint32_t read_be32(const uint8_t *data);
uint64_t read_be64(const uint8_t *data);

std::map<std::string, std::string> parse_key_value_text(const std::string &text);
int safe_int(const std::map<std::string, std::string> &fields, const std::string &key, int fallback = 0);
std::string safe_string(const std::map<std::string, std::string> &fields, const std::string &key, const std::string &fallback = "");

StreamInfo parse_heartbeat(const std::string &text);
std::string stream_info_summary(const StreamInfo &info);
std::string stream_identity_key(const StreamInfo &info);

EncodedPayload extract_encoded_payload(const LensPacket &packet);
std::vector<uint8_t> normalize_annex_b(const std::vector<uint8_t> &payload);

} // namespace ghxst
