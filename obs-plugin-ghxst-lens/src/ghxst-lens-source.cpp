#include "ghxst-lens-source.hpp"
#include "ghxst-client.hpp"

#include <graphics/graphics.h>
#include <util/platform.h>

#include <cstdint>
#include <sstream>
#include <string>
#include <cmath>

#define GHXST_DEFAULT_PHONE_IP "192.168.99.101"
#define GHXST_DEFAULT_PORT 9000

struct ghxst_lens_source {
	obs_source_t *source = nullptr;

	std::string phone_ip = GHXST_DEFAULT_PHONE_IP;
	int port = GHXST_DEFAULT_PORT;
	bool auto_reconnect = true;
	bool auto_detect = false;
	std::string decode_mode = "auto";
	double zoom_ratio = 1.0;
	double zoom_step = 0.25;
	std::string control_profile_id = "ultra_4k30_45m";
	std::string control_codec_id = "h265";
	std::string control_camera_id = "auto";

	uint32_t width = 1280;
	uint32_t height = 720;

	std::string status = "Ready - Direct GPU mode available";
	std::string control_status = "Camera controls ready - use Apply buttons only";

	gs_texture_t *texture = nullptr;
	uint32_t texture_width = 0;
	uint32_t texture_height = 0;
	uint64_t uploaded_frame_number = 0;
	bool texture_is_shared = false;
	uint32_t opened_shared_handle = 0;
	uint64_t opened_shared_generation = 0;

	ghxst::GhxstClient client;
	uint64_t last_logged_sequence = 0;
	std::string last_logged_message;
};

static const char *ghxst_lens_get_name(void *)
{
	return "GHXST Lens";
}


static double ghxst_clamp_double(double value, double low, double high)
{
	if (value < low) {
		return low;
	}
	if (value > high) {
		return high;
	}
	return value;
}

static std::string ghxst_format_double(double value)
{
	std::stringstream ss;
	ss.setf(std::ios::fixed);
	ss.precision(2);
	ss << value;
	return ss.str();
}

static bool ghxst_lens_send_zoom(ghxst_lens_source *ctx, double zoom)
{
	if (!ctx) {
		return false;
	}

	ctx->zoom_ratio = ghxst_clamp_double(zoom, 1.0, 100.0);

	std::string command = "GHXC/1 SET_ZOOM ratio=" + ghxst_format_double(ctx->zoom_ratio);
	const bool ok = ctx->client.send_control_command(command);

	ctx->control_status = ok
		? ("Zoom command sent: " + ghxst_format_double(ctx->zoom_ratio) + "x")
		: "Zoom command failed - not connected";

	blog(
		ok ? LOG_INFO : LOG_WARNING,
		"[GHXST Lens] %s",
		ctx->control_status.c_str()
	);

	return ok;
}


static bool ghxst_lens_send_stream_settings(ghxst_lens_source *ctx)
{
	if (!ctx) {
		return false;
	}

	std::string profile = ctx->control_profile_id.empty() ? "balanced_720p60_8m" : ctx->control_profile_id;
	std::string codec = ctx->control_codec_id.empty() ? "h265" : ctx->control_codec_id;
	std::string camera_id = ctx->control_camera_id.empty() ? "auto" : ctx->control_camera_id;

	std::string command = "GHXC/1 SET_STREAM profile=" + profile +
	                      " codec=" + codec +
	                      " camera_id=" + camera_id +
	                      " restart=true";

	const bool ok = ctx->client.send_control_command(command);
	ctx->control_status = ok
		? ("Stream settings sent: profile=" + profile + " codec=" + codec + " camera=" + camera_id)
		: "Stream settings failed - not connected";

	blog(
		ok ? LOG_INFO : LOG_WARNING,
		"[GHXST Lens] %s",
		ctx->control_status.c_str()
	);

	return ok;
}

static bool ghxst_lens_zoom_ratio_modified(void *data, obs_properties_t *, obs_property_t *, obs_data_t *settings)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	if (!ctx) {
		return true;
	}

	double value = obs_data_get_double(settings, "zoom_ratio");
	if (!std::isfinite(value) || value <= 0.0) {
		value = 1.0;
	}
	ctx->zoom_ratio = ghxst_clamp_double(value, 1.0, 100.0);
	return true;
}

static bool ghxst_lens_zoom_step_modified(void *data, obs_properties_t *, obs_property_t *, obs_data_t *settings)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	if (!ctx) {
		return true;
	}

	double value = obs_data_get_double(settings, "zoom_step");
	if (!std::isfinite(value) || value <= 0.0) {
		value = 0.25;
	}
	ctx->zoom_step = ghxst_clamp_double(value, 0.05, 5.0);
	return true;
}

static bool ghxst_lens_stream_control_modified(void *data, obs_properties_t *, obs_property_t *, obs_data_t *settings)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	if (!ctx) {
		return true;
	}

	const char *profile = obs_data_get_string(settings, "control_profile_id");
	const char *codec = obs_data_get_string(settings, "control_codec_id");
	const char *camera = obs_data_get_string(settings, "control_camera_id");
	const char *zoom = obs_data_get_string(settings, "control_zoom_ratio");

	ctx->control_profile_id = profile && *profile ? profile : "ultra_4k30_45m";
	ctx->control_codec_id = codec && *codec ? codec : "h265";
	ctx->control_camera_id = camera && *camera ? camera : "auto";
	if (zoom && *zoom) {
		try {
			ctx->zoom_ratio = ghxst_clamp_double(std::stod(zoom), 1.0, 100.0);
		} catch (...) {
			ctx->zoom_ratio = 1.0;
		}
	}
	return true;
}

static std::string build_status_text(const ghxst::ClientStatus &status)
{
	if (!status.running) {
		return "Stopped";
	}

	if (!status.connected) {
		return status.message.empty() ? "Disconnected" : status.message;
	}

	if (status.stream_ready) {
		std::stringstream ss;
		ss.setf(std::ios::fixed);
		ss.precision(1);

		ss << "Connected: "
		   << status.stream.width << "x" << status.stream.height
		   << "@" << status.stream.fps << " "
		   << status.stream.codec
		   << " | decode=" << status.decode_mode
		   << (status.stream.zoom_applied.empty() ? "" : " | zoom=" + status.stream.zoom_applied + "x")
		   << " | decoded=" << status.decoded_frames
		   << " | rx=" << status.packet_fps << "fps"
		   << " | dec=" << status.decoded_fps << "fps"
		   << " | " << status.mbps << "Mbps";

		return ss.str();
	}

	return status.message.empty() ? "Connected - waiting for stream metadata" : status.message;
}

static void ghxst_lens_destroy_texture(ghxst_lens_source *ctx)
{
	if (ctx->texture) {
		gs_texture_destroy(ctx->texture);
		ctx->texture = nullptr;
	}

	ctx->texture_width = 0;
	ctx->texture_height = 0;
	ctx->uploaded_frame_number = 0;
	ctx->texture_is_shared = false;
	ctx->opened_shared_handle = 0;
	ctx->opened_shared_generation = 0;
}

static void ghxst_lens_update_texture(ghxst_lens_source *ctx)
{
	ghxst::DecodedFrame frame;
	if (!ctx->client.latest_frame(frame) || !frame.valid) {
		return;
	}

	if (frame.width <= 0 || frame.height <= 0) {
		return;
	}

	const uint32_t w = static_cast<uint32_t>(frame.width);
	const uint32_t h = static_cast<uint32_t>(frame.height);

	if (frame.gpu_shared && frame.shared_texture_handle != 0) {
		const bool needs_open =
			!ctx->texture ||
			!ctx->texture_is_shared ||
			ctx->opened_shared_handle != frame.shared_texture_handle ||
			ctx->opened_shared_generation != frame.shared_texture_generation ||
			ctx->texture_width != w ||
			ctx->texture_height != h;

		if (needs_open) {
			if (!gs_shared_texture_available()) {
				blog(LOG_WARNING, "[GHXST Lens] OBS shared texture support is unavailable on this graphics backend");
				return;
			}

			ghxst_lens_destroy_texture(ctx);

			ctx->texture = gs_texture_open_shared(frame.shared_texture_handle);
			if (!ctx->texture) {
				blog(LOG_WARNING, "[GHXST Lens] Failed to open D3D11 shared texture handle %u", frame.shared_texture_handle);
				return;
			}

			ctx->texture_width = w;
			ctx->texture_height = h;
			ctx->width = w;
			ctx->height = h;
			ctx->texture_is_shared = true;
			ctx->opened_shared_handle = frame.shared_texture_handle;
			ctx->opened_shared_generation = frame.shared_texture_generation;

			blog(LOG_INFO, "[GHXST Lens] Opened shared D3D11 video texture %ux%u", w, h);
		}

		ctx->uploaded_frame_number = frame.frame_number;
		return;
	}

	if (!frame.bgra || frame.bgra->empty()) {
		return;
	}

	if (frame.frame_number == ctx->uploaded_frame_number && !ctx->texture_is_shared) {
		return;
	}

	if (!ctx->texture || ctx->texture_is_shared || ctx->texture_width != w || ctx->texture_height != h) {
		ghxst_lens_destroy_texture(ctx);

		ctx->texture = gs_texture_create(
			w,
			h,
			GS_BGRA,
			1,
			nullptr,
			GS_DYNAMIC
		);

		ctx->texture_width = w;
		ctx->texture_height = h;
		ctx->width = w;
		ctx->height = h;
		ctx->texture_is_shared = false;

		blog(LOG_INFO, "[GHXST Lens] Created CPU-upload video texture %ux%u", w, h);
	}

	if (!ctx->texture) {
		return;
	}

	gs_texture_set_image(
		ctx->texture,
		frame.bgra->data(),
		w * 4,
		false
	);

	ctx->uploaded_frame_number = frame.frame_number;
}

static ghxst::ClientSettings build_client_settings(const ghxst_lens_source *ctx)
{
	ghxst::ClientSettings client_settings;
	client_settings.phone_ip = ctx->phone_ip;
	client_settings.port = ctx->port;
	client_settings.auto_reconnect = ctx->auto_reconnect;
	client_settings.decode_mode = ctx->decode_mode;
	return client_settings;
}

static void ghxst_lens_update(void *data, obs_data_t *settings)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);

	const std::string previous_phone_ip = ctx->phone_ip;
	const int previous_port = ctx->port;
	const bool previous_auto_reconnect = ctx->auto_reconnect;
	const std::string previous_decode_mode = ctx->decode_mode;

	const char *phone_ip = obs_data_get_string(settings, "phone_ip");
	ctx->phone_ip = phone_ip && *phone_ip ? phone_ip : GHXST_DEFAULT_PHONE_IP;

	ctx->port = static_cast<int>(obs_data_get_int(settings, "port"));
	ctx->auto_reconnect = obs_data_get_bool(settings, "auto_reconnect");
	// Phone discovery is intentionally hidden until the Android discovery/control
	// service is implemented. Keep manual IP/port reliable for now.
	ctx->auto_detect = false;

	const char *decode_mode = obs_data_get_string(settings, "decode_mode");
	std::string new_decode_mode = decode_mode && *decode_mode ? decode_mode : "auto";

	const char *control_zoom = obs_data_get_string(settings, "control_zoom_ratio");
	if (control_zoom && *control_zoom) {
		try {
			ctx->zoom_ratio = ghxst_clamp_double(std::stod(control_zoom), 1.0, 100.0);
		} catch (...) {
			ctx->zoom_ratio = 1.0;
		}
	} else {
		double new_zoom_ratio = obs_data_get_double(settings, "zoom_ratio");
		if (!std::isfinite(new_zoom_ratio) || new_zoom_ratio <= 0.0) {
			new_zoom_ratio = 1.0;
		}
		ctx->zoom_ratio = ghxst_clamp_double(new_zoom_ratio, 1.0, 100.0);
	}

	ctx->zoom_step = 0.25;

	const char *control_profile = obs_data_get_string(settings, "control_profile_id");
	const char *control_codec = obs_data_get_string(settings, "control_codec_id");
	const char *control_camera = obs_data_get_string(settings, "control_camera_id");
	ctx->control_profile_id = control_profile && *control_profile ? control_profile : "ultra_4k30_45m";
	ctx->control_codec_id = control_codec && *control_codec ? control_codec : "h265";
	ctx->control_camera_id = control_camera && *control_camera ? control_camera : "auto";

	const bool connection_changed =
		previous_phone_ip != ctx->phone_ip ||
		previous_port != ctx->port ||
		previous_auto_reconnect != ctx->auto_reconnect ||
		previous_decode_mode != new_decode_mode;

	ctx->decode_mode = new_decode_mode;
	ctx->status = "Target: " + ctx->phone_ip + ":" + std::to_string(ctx->port);

	if (connection_changed || !ctx->client.status().running) {
		if (!ctx->auto_detect) {
			ctx->client.start(build_client_settings(ctx));
		} else {
			ctx->client.stop();
		}
	}

	if (connection_changed) {
		blog(
			LOG_INFO,
			"[GHXST Lens] Connection settings updated: ip=%s port=%d auto_reconnect=%s decode_mode=%s",
			ctx->phone_ip.c_str(),
			ctx->port,
			ctx->auto_reconnect ? "true" : "false",
			ctx->decode_mode.c_str()
		);
	}
}

static void *ghxst_lens_create(obs_data_t *settings, obs_source_t *source)
{
	auto *ctx = new ghxst_lens_source();
	ctx->source = source;

	ghxst_lens_update(ctx, settings);

	blog(LOG_INFO, "[GHXST Lens] Source created");

	return ctx;
}

static void ghxst_lens_destroy(void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);

	ctx->client.stop();

	obs_enter_graphics();
	ghxst_lens_destroy_texture(ctx);
	obs_leave_graphics();

	blog(LOG_INFO, "[GHXST Lens] Source destroyed");

	delete ctx;
}

static uint32_t ghxst_lens_get_width(void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	return ctx->width;
}

static uint32_t ghxst_lens_get_height(void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	return ctx->height;
}

static void ghxst_lens_video_tick(void *data, float)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	if (!ctx) {
		return;
	}

	ghxst::ClientStatus status = ctx->client.status();

	ctx->status = build_status_text(status);
	if (!status.stream.control_status.empty()) {
		ctx->control_status = "Phone: " + status.stream.control_status;
	}

	if (status.sequence == ctx->last_logged_sequence) {
		return;
	}

	ctx->last_logged_sequence = status.sequence;

	if (!status.last_error.empty()) {
		if (status.last_error != ctx->last_logged_message) {
			ctx->last_logged_message = status.last_error;
			blog(LOG_WARNING, "[GHXST Lens] %s", status.last_error.c_str());
		}
		return;
	}

	if (!status.message.empty() && status.message != ctx->last_logged_message) {
		ctx->last_logged_message = status.message;
		blog(LOG_INFO, "[GHXST Lens] %s", status.message.c_str());
	}
}

static void ghxst_lens_video_render(void *data, gs_effect_t *)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);

	if (!ctx) {
		return;
	}

	ghxst_lens_update_texture(ctx);

	if (!ctx->texture) {
		return;
	}

	obs_source_draw(ctx->texture, 0, 0, ctx->width, ctx->height, false);
}



static bool ghxst_lens_restart_clicked(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	if (!ctx) {
		return false;
	}

	ctx->client.start(build_client_settings(ctx));
	ctx->status = "Restarting connection to " + ctx->phone_ip + ":" + std::to_string(ctx->port);
	blog(LOG_INFO, "[GHXST Lens] Manual reconnect requested");
	return true;
}

static bool ghxst_lens_stop_clicked(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	if (!ctx) {
		return false;
	}

	ctx->client.stop();
	ctx->status = "Stopped by user";
	blog(LOG_INFO, "[GHXST Lens] Manual disconnect requested");
	return true;
}


static bool ghxst_lens_apply_zoom_clicked(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	return ghxst_lens_send_zoom(ctx, ctx ? ctx->zoom_ratio : 1.0);
}

static bool ghxst_lens_zoom_in_clicked(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	if (!ctx) {
		return false;
	}
	return ghxst_lens_send_zoom(ctx, ctx->zoom_ratio + ctx->zoom_step);
}

static bool ghxst_lens_zoom_out_clicked(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	if (!ctx) {
		return false;
	}
	return ghxst_lens_send_zoom(ctx, ctx->zoom_ratio - ctx->zoom_step);
}

static bool ghxst_lens_reset_zoom_clicked(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	return ghxst_lens_send_zoom(ctx, 1.0);
}

static bool ghxst_lens_apply_stream_clicked(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);
	return ghxst_lens_send_stream_settings(ctx);
}

static obs_properties_t *ghxst_lens_get_properties(void *data)
{
	auto *ctx = static_cast<ghxst_lens_source *>(data);

	obs_properties_t *props = obs_properties_create();

	obs_properties_add_text(
		props,
		"phone_ip",
		"Phone IP / Hostname",
		OBS_TEXT_DEFAULT
	);

	obs_properties_add_int(
		props,
		"port",
		"Port",
		1,
		65535,
		1
	);

	obs_properties_add_bool(
		props,
		"auto_reconnect",
		"Reconnect automatically"
	);

	obs_properties_add_button(
		props,
		"restart_connection",
		"Reconnect now",
		ghxst_lens_restart_clicked
	);

	obs_properties_add_button(
		props,
		"stop_connection",
		"Disconnect",
		ghxst_lens_stop_clicked
	);

	obs_property_t *decode_mode = obs_properties_add_list(
		props,
		"decode_mode",
		"Performance mode",
		OBS_COMBO_TYPE_LIST,
		OBS_COMBO_FORMAT_STRING
	);
	obs_property_list_add_string(decode_mode, "Auto - Best available (recommended)", "auto");
	obs_property_list_add_string(decode_mode, "Direct GPU - Lowest CPU", "d3d11va_direct");
	obs_property_list_add_string(decode_mode, "Hardware decode - Compatibility", "d3d11va");
	obs_property_list_add_string(decode_mode, "Software decode - Fallback", "software");


	obs_property_t *profile_prop = obs_properties_add_list(
		props,
		"control_profile_id",
		"Phone video mode",
		OBS_COMBO_TYPE_LIST,
		OBS_COMBO_FORMAT_STRING
	);
	obs_property_list_add_string(profile_prop, "4K30 - Sharp / Recommended", "ultra_4k30_45m");
	obs_property_list_add_string(profile_prop, "1080p60 - Smooth HQ", "quality_1080p60_16m");
	obs_property_list_add_string(profile_prop, "720p60 - Smooth", "balanced_720p60_8m");
	obs_property_list_add_string(profile_prop, "1080p30 - Stable", "quality_1080p30_8m");
	obs_property_list_add_string(profile_prop, "720p30 - Stable", "basic_720p30_5m");
	obs_property_set_modified_callback2(profile_prop, ghxst_lens_stream_control_modified, ctx);

	obs_property_t *codec_prop = obs_properties_add_list(
		props,
		"control_codec_id",
		"Phone codec",
		OBS_COMBO_TYPE_LIST,
		OBS_COMBO_FORMAT_STRING
	);
	obs_property_list_add_string(codec_prop, "H.265 / HEVC - recommended", "h265");
	obs_property_list_add_string(codec_prop, "H.264 / AVC - compatibility", "h264");
	obs_property_set_modified_callback2(codec_prop, ghxst_lens_stream_control_modified, ctx);

	obs_property_t *camera_prop = obs_properties_add_list(
		props,
		"control_camera_id",
		"Lens",
		OBS_COMBO_TYPE_LIST,
		OBS_COMBO_FORMAT_STRING
	);
	obs_property_list_add_string(camera_prop, "Auto - Recommended", "auto");
	obs_property_list_add_string(camera_prop, "Back main / wide", "back_main");
	obs_property_list_add_string(camera_prop, "Back ultra-wide", "back_ultra_wide");
	obs_property_list_add_string(camera_prop, "Back telephoto", "back_telephoto");
	obs_property_list_add_string(camera_prop, "Front camera", "front");
	obs_property_set_long_description(camera_prop, "Use Auto first. Lens availability depends on the selected video mode and the phone camera hardware.");
	obs_property_set_modified_callback2(camera_prop, ghxst_lens_stream_control_modified, ctx);

	obs_property_t *zoom_prop = obs_properties_add_list(
		props,
		"control_zoom_ratio",
		"Phone zoom",
		OBS_COMBO_TYPE_LIST,
		OBS_COMBO_FORMAT_STRING
	);
	obs_property_list_add_string(zoom_prop, "1.0x - Reset", "1.00");
	obs_property_list_add_string(zoom_prop, "1.25x", "1.25");
	obs_property_list_add_string(zoom_prop, "1.5x", "1.50");
	obs_property_list_add_string(zoom_prop, "2.0x", "2.00");
	obs_property_list_add_string(zoom_prop, "3.0x", "3.00");
	obs_property_list_add_string(zoom_prop, "5.0x", "5.00");
	obs_property_set_modified_callback2(zoom_prop, ghxst_lens_stream_control_modified, ctx);

	obs_properties_add_button(
		props,
		"apply_stream_settings",
		"Apply video / lens",
		ghxst_lens_apply_stream_clicked
	);

	obs_properties_add_button(
		props,
		"apply_zoom",
		"Apply zoom",
		ghxst_lens_apply_zoom_clicked
	);

	obs_property_t *control_status_prop = obs_properties_add_text(
		props,
		"control_status",
		"Camera controls",
		OBS_TEXT_INFO
	);

	if (ctx) {
		obs_property_set_long_description(control_status_prop, ctx->control_status.c_str());
	}

	obs_property_t *status_prop = obs_properties_add_text(
		props,
		"status",
		"Status",
		OBS_TEXT_INFO
	);

	if (ctx) {
		obs_property_set_long_description(status_prop, ctx->status.c_str());
	}

	return props;
}

static void ghxst_lens_get_defaults(obs_data_t *settings)
{
	obs_data_set_default_string(settings, "phone_ip", GHXST_DEFAULT_PHONE_IP);
	obs_data_set_default_int(settings, "port", GHXST_DEFAULT_PORT);
	obs_data_set_default_bool(settings, "auto_reconnect", true);
	obs_data_set_default_bool(settings, "auto_detect", false);
	obs_data_set_default_string(settings, "decode_mode", "auto");
	obs_data_set_default_double(settings, "zoom_ratio", 1.0);
	obs_data_set_default_double(settings, "zoom_step", 0.25);
	obs_data_set_default_string(settings, "control_zoom_ratio", "1.00");
	obs_data_set_default_string(settings, "control_profile_id", "ultra_4k30_45m");
	obs_data_set_default_string(settings, "control_codec_id", "h265");
	obs_data_set_default_string(settings, "control_camera_id", "auto");
	obs_data_set_default_string(settings, "status", "Ready - Direct GPU mode available");
	obs_data_set_default_string(settings, "control_status", "Camera controls ready - use Apply buttons only");
}

struct obs_source_info ghxst_lens_source_info = [] {
	obs_source_info info = {};

	info.id = "ghxst_lens_source";
	info.type = OBS_SOURCE_TYPE_INPUT;
	info.output_flags = OBS_SOURCE_VIDEO;

	info.get_name = ghxst_lens_get_name;
	info.create = ghxst_lens_create;
	info.destroy = ghxst_lens_destroy;
	info.update = ghxst_lens_update;
	info.get_width = ghxst_lens_get_width;
	info.get_height = ghxst_lens_get_height;
	info.video_render = ghxst_lens_video_render;
	info.video_tick = ghxst_lens_video_tick;
	info.get_properties = ghxst_lens_get_properties;
	info.get_defaults = ghxst_lens_get_defaults;

	return info;
}();
