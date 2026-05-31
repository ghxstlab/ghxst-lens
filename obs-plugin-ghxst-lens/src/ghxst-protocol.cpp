#include "ghxst-protocol.hpp"

#include <sstream>

namespace ghxst {

static constexpr uint8_t ANNEX_B_4[4] = {0x00, 0x00, 0x00, 0x01};

uint32_t read_be32(const uint8_t *data)
{
	return (uint32_t(data[0]) << 24) |
	       (uint32_t(data[1]) << 16) |
	       (uint32_t(data[2]) << 8) |
	       uint32_t(data[3]);
}

uint64_t read_be64(const uint8_t *data)
{
	return (uint64_t(data[0]) << 56) |
	       (uint64_t(data[1]) << 48) |
	       (uint64_t(data[2]) << 40) |
	       (uint64_t(data[3]) << 32) |
	       (uint64_t(data[4]) << 24) |
	       (uint64_t(data[5]) << 16) |
	       (uint64_t(data[6]) << 8) |
	       uint64_t(data[7]);
}

static std::string trim(const std::string &value)
{
	size_t start = value.find_first_not_of(" \t\r\n");
	if (start == std::string::npos) {
		return "";
	}

	size_t end = value.find_last_not_of(" \t\r\n");
	return value.substr(start, end - start + 1);
}

std::map<std::string, std::string> parse_key_value_text(const std::string &text)
{
	std::map<std::string, std::string> fields;

	std::stringstream ss(text);
	std::string part;

	while (std::getline(ss, part, ';')) {
		size_t pos = part.find('=');
		if (pos == std::string::npos) {
			continue;
		}

		std::string key = trim(part.substr(0, pos));
		std::string value = trim(part.substr(pos + 1));

		if (!key.empty()) {
			fields[key] = value;
		}
	}

	return fields;
}

int safe_int(const std::map<std::string, std::string> &fields, const std::string &key, int fallback)
{
	auto it = fields.find(key);
	if (it == fields.end()) {
		return fallback;
	}

	try {
		return std::stoi(it->second);
	} catch (...) {
		return fallback;
	}
}

std::string safe_string(const std::map<std::string, std::string> &fields, const std::string &key, const std::string &fallback)
{
	auto it = fields.find(key);
	if (it == fields.end()) {
		return fallback;
	}

	return it->second;
}

StreamInfo parse_heartbeat(const std::string &text)
{
	auto fields = parse_key_value_text(text);

	StreamInfo info;

	info.status = safe_string(fields, "status");
	info.version = safe_string(fields, "version");
	info.profile = safe_string(fields, "profile");
	info.width = safe_int(fields, "width");
	info.height = safe_int(fields, "height");
	info.fps = safe_int(fields, "fps");
	info.bitrate = safe_int(fields, "bitrate");
	info.iframe_interval = safe_int(fields, "iframe_interval");
	info.codec = safe_string(fields, "codec");
	info.input = safe_string(fields, "input");
	info.camera2_id = safe_string(fields, "camera2_id");
	info.camera_requested = safe_string(fields, "camera_requested");
	info.camera2_mode = safe_string(fields, "camera2_mode");
	info.zoom_requested = safe_string(fields, "zoom_requested");
	info.zoom_applied = safe_string(fields, "zoom_applied");
	info.encoded_fps_estimate = safe_string(fields, "encoded_fps_estimate");
	info.encoder_latency = safe_string(fields, "encoder_latency");
	info.control_status = safe_string(fields, "control_status");

	if (info.codec == "hevc") {
		info.codec = "h265";
	}

	info.valid = info.width > 0 && info.height > 0 && info.fps > 0 && !info.codec.empty();

	return info;
}

std::string stream_info_summary(const StreamInfo &info)
{
	if (!info.valid) {
		return "stream metadata incomplete";
	}

	std::stringstream ss;
	ss << info.width << "x" << info.height << "@" << info.fps << " " << info.codec;

	if (info.bitrate > 0) {
		ss << " bitrate=" << info.bitrate;
	}

	if (!info.profile.empty()) {
		ss << " profile=" << info.profile;
	}

	if (!info.camera2_id.empty()) {
		ss << " camera=" << info.camera2_id;
	}

	if (!info.zoom_applied.empty()) {
		ss << " zoom=" << info.zoom_applied;
	}

	return ss.str();
}

std::string stream_identity_key(const StreamInfo &info)
{
	if (!info.valid) {
		return "";
	}

	std::stringstream ss;
	ss << info.width << "x" << info.height << "@" << info.fps << ":" << info.codec << ":" << info.profile;
	return ss.str();
}

EncodedPayload extract_encoded_payload(const LensPacket &packet)
{
	EncodedPayload out;

	if (packet.payload.size() < 25 ||
	    packet.payload[0] != 'G' ||
	    packet.payload[1] != 'H' ||
	    packet.payload[2] != 'X' ||
	    packet.payload[3] != 'F') {
		out.data = packet.payload;
		return out;
	}

	const uint8_t *p = packet.payload.data();

	out.metadata.frame_index = read_be64(p + 4);
	out.metadata.encoder_pts_us = read_be64(p + 12);
	out.metadata.keyframe = (p[20] & FRAME_FLAG_KEYFRAME) != 0;

	uint32_t payload_length = read_be32(p + 21);
	if (payload_length == 0 || 25ULL + payload_length > packet.payload.size()) {
		out.data = packet.payload;
		out.has_metadata = false;
		return out;
	}

	out.data.assign(packet.payload.begin() + 25, packet.payload.begin() + 25 + payload_length);
	out.has_metadata = true;
	return out;
}

static bool has_annex_b_start_code(const std::vector<uint8_t> &payload)
{
	if (payload.size() >= 4 &&
	    payload[0] == 0x00 &&
	    payload[1] == 0x00 &&
	    payload[2] == 0x00 &&
	    payload[3] == 0x01) {
		return true;
	}

	if (payload.size() >= 3 &&
	    payload[0] == 0x00 &&
	    payload[1] == 0x00 &&
	    payload[2] == 0x01) {
		return true;
	}

	const size_t max_scan = payload.size() < 64 ? payload.size() : 64;
	for (size_t i = 0; i + 4 <= max_scan; i++) {
		if (payload[i] == 0x00 && payload[i + 1] == 0x00 && payload[i + 2] == 0x00 && payload[i + 3] == 0x01) {
			return true;
		}
		if (payload[i] == 0x00 && payload[i + 1] == 0x00 && payload[i + 2] == 0x01) {
			return true;
		}
	}

	return false;
}

std::vector<uint8_t> normalize_annex_b(const std::vector<uint8_t> &payload)
{
	if (payload.empty()) {
		return payload;
	}

	if (has_annex_b_start_code(payload)) {
		return payload;
	}

	std::vector<uint8_t> out;
	size_t pos = 0;
	bool converted_any = false;

	while (pos + 4 <= payload.size()) {
		uint32_t nal_len = read_be32(payload.data() + pos);
		pos += 4;

		if (nal_len == 0 || pos + nal_len > payload.size()) {
			converted_any = false;
			break;
		}

		out.insert(out.end(), ANNEX_B_4, ANNEX_B_4 + 4);
		out.insert(out.end(), payload.begin() + pos, payload.begin() + pos + nal_len);

		pos += nal_len;
		converted_any = true;
	}

	if (converted_any && pos == payload.size()) {
		return out;
	}

	out.clear();
	out.insert(out.end(), ANNEX_B_4, ANNEX_B_4 + 4);
	out.insert(out.end(), payload.begin(), payload.end());
	return out;
}

} // namespace ghxst
