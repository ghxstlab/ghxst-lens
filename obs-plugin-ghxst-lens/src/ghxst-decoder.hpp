#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#ifdef _WIN32
#include <d3d11.h>
#include <wrl/client.h>
#endif

extern "C" {
struct AVBufferRef;
struct AVCodecContext;
struct AVFrame;
struct SwsContext;
}

namespace ghxst {

struct DecodedFrame {
	bool valid = false;
	int width = 0;
	int height = 0;
	uint64_t frame_number = 0;

	// Software / CPU-upload path.
	std::shared_ptr<std::vector<uint8_t>> bgra;

	// Experimental direct GPU path.
	// This is a Windows/OBS D3D11 shared BGRA texture handle opened by OBS with
	// gs_texture_open_shared(). The decoder keeps the underlying texture alive.
	bool gpu_shared = false;
	uint32_t shared_texture_handle = 0;
	uint64_t shared_texture_generation = 0;
};

class GhxstDecoder {
public:
	GhxstDecoder();
	~GhxstDecoder();

	GhxstDecoder(const GhxstDecoder &) = delete;
	GhxstDecoder &operator=(const GhxstDecoder &) = delete;

	bool open(const std::string &codec, int width, int height, const std::string &decode_mode = "software");
	void close();

	bool is_open() const;
	std::string codec() const;
	std::string decode_mode() const;

	bool decode_packet(const std::vector<uint8_t> &packet, DecodedFrame &frame_out);
	std::string last_error() const;

private:
	bool open_internal(const std::string &codec, int width, int height, const std::string &decode_mode, bool use_d3d11va, bool direct_gpu_output);
	bool receive_frame(DecodedFrame &frame_out);
	std::shared_ptr<std::vector<uint8_t>> next_bgra_buffer(int required_size);

#ifdef _WIN32
	bool attach_d3d11_device_from_ffmpeg();
	void release_d3d11_resources();
	bool ensure_d3d11_video_processor(int width, int height);
	bool ensure_shared_bgra_texture(int width, int height);
	bool process_d3d11_frame_to_shared_texture(::AVFrame *frame, DecodedFrame &frame_out);
#endif

	std::string codec_;
	std::string decode_mode_ = "software";
	std::string last_error_;
	int width_ = 0;
	int height_ = 0;
	uint64_t frame_number_ = 0;
	bool hardware_decode_enabled_ = false;
	bool direct_gpu_output_enabled_ = false;

	::AVCodecContext *codec_ctx_ = nullptr;
	::AVFrame *frame_ = nullptr;
	::AVFrame *transfer_frame_ = nullptr;
	::AVFrame *bgra_frame_ = nullptr;
	::SwsContext *sws_ctx_ = nullptr;
	::AVBufferRef *hw_device_ctx_ = nullptr;

	std::vector<std::shared_ptr<std::vector<uint8_t>>> bgra_buffers_;
	size_t next_bgra_buffer_ = 0;

#ifdef _WIN32
	Microsoft::WRL::ComPtr<ID3D11Device> d3d11_device_;
	Microsoft::WRL::ComPtr<ID3D11DeviceContext> d3d11_context_;
	Microsoft::WRL::ComPtr<ID3D11VideoDevice> d3d11_video_device_;
	Microsoft::WRL::ComPtr<ID3D11VideoContext> d3d11_video_context_;
	Microsoft::WRL::ComPtr<ID3D11VideoProcessorEnumerator> video_processor_enumerator_;
	Microsoft::WRL::ComPtr<ID3D11VideoProcessor> video_processor_;
	Microsoft::WRL::ComPtr<ID3D11Texture2D> shared_bgra_texture_;
	Microsoft::WRL::ComPtr<ID3D11VideoProcessorOutputView> shared_bgra_output_view_;
	uint32_t shared_texture_handle_ = 0;
	uint64_t shared_texture_generation_ = 0;
	int video_processor_width_ = 0;
	int video_processor_height_ = 0;
	int shared_texture_width_ = 0;
	int shared_texture_height_ = 0;
#endif
};

} // namespace ghxst
