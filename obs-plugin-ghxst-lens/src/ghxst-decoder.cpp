#include "ghxst-decoder.hpp"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/imgutils.h>
#ifdef _WIN32
#include <libavutil/hwcontext_d3d11va.h>
#endif
#include <libswscale/swscale.h>
}

#include <algorithm>
#include <cinttypes>

#ifdef _WIN32
#include <dxgi.h>
#endif

namespace ghxst {

static AVPixelFormat ghxst_get_d3d11va_format(AVCodecContext *, const AVPixelFormat *pixel_formats)
{
	for (const AVPixelFormat *format = pixel_formats; *format != AV_PIX_FMT_NONE; ++format) {
		if (*format == AV_PIX_FMT_D3D11) {
			return *format;
		}
	}

	return pixel_formats[0];
}

GhxstDecoder::GhxstDecoder()
{
}

GhxstDecoder::~GhxstDecoder()
{
	close();
}

bool GhxstDecoder::open(const std::string &codec, int width, int height, const std::string &decode_mode)
{
	close();

	const std::string requested_mode = decode_mode.empty() ? "software" : decode_mode;

	if (requested_mode == "auto") {
		if (open_internal(codec, width, height, "d3d11va_direct", true, true)) {
			return true;
		}

		const std::string direct_error = last_error_;
		close();

		if (open_internal(codec, width, height, "d3d11va", true, false)) {
			if (!direct_error.empty()) {
				last_error_ = "D3D11VA direct unavailable, using D3D11VA CPU-upload path. Previous error: " + direct_error;
			}
			return true;
		}

		const std::string hw_error = last_error_;
		close();

		if (open_internal(codec, width, height, "software", false, false)) {
			if (!hw_error.empty()) {
				last_error_ = "Hardware decode unavailable, using software decode. Previous error: " + hw_error;
			}
			return true;
		}

		return false;
	}

	const bool use_d3d11va = requested_mode == "d3d11va" || requested_mode == "d3d11va_direct";
	const bool direct_gpu_output = requested_mode == "d3d11va_direct";
	return open_internal(codec, width, height, requested_mode, use_d3d11va, direct_gpu_output);
}

bool GhxstDecoder::open_internal(const std::string &codec, int width, int height, const std::string &decode_mode, bool use_d3d11va, bool direct_gpu_output)
{
	codec_ = codec;
	decode_mode_ = decode_mode;
	width_ = width;
	height_ = height;
	frame_number_ = 0;
	hardware_decode_enabled_ = false;
	direct_gpu_output_enabled_ = false;
	last_error_.clear();

	AVCodecID codec_id = AV_CODEC_ID_H264;
	if (codec == "h265" || codec == "hevc") {
		codec_id = AV_CODEC_ID_HEVC;
	} else if (codec == "h264") {
		codec_id = AV_CODEC_ID_H264;
	} else {
		last_error_ = "Unsupported codec: " + codec;
		return false;
	}

	const AVCodec *decoder = avcodec_find_decoder(codec_id);
	if (!decoder) {
		last_error_ = "FFmpeg decoder not found for codec: " + codec;
		return false;
	}

	codec_ctx_ = avcodec_alloc_context3(decoder);
	if (!codec_ctx_) {
		last_error_ = "Failed to allocate AVCodecContext";
		return false;
	}

	codec_ctx_->flags |= AV_CODEC_FLAG_LOW_DELAY;
	codec_ctx_->flags2 |= AV_CODEC_FLAG2_FAST;
	codec_ctx_->thread_count = 1;
	codec_ctx_->thread_type = FF_THREAD_SLICE;

	if (use_d3d11va) {
#ifdef _WIN32
		const int hw_ret = av_hwdevice_ctx_create(
			&hw_device_ctx_,
			AV_HWDEVICE_TYPE_D3D11VA,
			nullptr,
			nullptr,
			0
		);

		if (hw_ret < 0 || !hw_device_ctx_) {
			last_error_ = "Failed to create FFmpeg D3D11VA device";
			close();
			return false;
		}

		codec_ctx_->hw_device_ctx = av_buffer_ref(hw_device_ctx_);
		if (!codec_ctx_->hw_device_ctx) {
			last_error_ = "Failed to attach D3D11VA device to decoder";
			close();
			return false;
		}

		codec_ctx_->get_format = ghxst_get_d3d11va_format;
		hardware_decode_enabled_ = true;
		direct_gpu_output_enabled_ = direct_gpu_output;
#else
		last_error_ = "D3D11VA is only available on Windows";
		close();
		return false;
#endif
	}

	int ret = avcodec_open2(codec_ctx_, decoder, nullptr);
	if (ret < 0) {
		last_error_ = use_d3d11va ? "Failed to open FFmpeg D3D11VA decoder" : "Failed to open FFmpeg software decoder";
		close();
		return false;
	}

	frame_ = av_frame_alloc();
	transfer_frame_ = av_frame_alloc();
	bgra_frame_ = av_frame_alloc();

	if (!frame_ || !transfer_frame_ || !bgra_frame_) {
		last_error_ = "Failed to allocate AVFrame";
		close();
		return false;
	}

#ifdef _WIN32
	if (direct_gpu_output_enabled_ && !attach_d3d11_device_from_ffmpeg()) {
		close();
		return false;
	}
#endif

	if (direct_gpu_output_enabled_) {
		decode_mode_ = "d3d11va_direct";
	} else {
		decode_mode_ = hardware_decode_enabled_ ? "d3d11va" : "software";
	}

	return true;
}

void GhxstDecoder::close()
{
	if (sws_ctx_) {
		sws_freeContext(sws_ctx_);
		sws_ctx_ = nullptr;
	}

	if (bgra_frame_) {
		av_frame_free(&bgra_frame_);
	}

	if (transfer_frame_) {
		av_frame_free(&transfer_frame_);
	}

	if (frame_) {
		av_frame_free(&frame_);
	}

	if (codec_ctx_) {
		avcodec_free_context(&codec_ctx_);
	}

	if (hw_device_ctx_) {
		av_buffer_unref(&hw_device_ctx_);
	}

#ifdef _WIN32
	release_d3d11_resources();
#endif

	bgra_buffers_.clear();
	next_bgra_buffer_ = 0;
	codec_.clear();
	decode_mode_ = "software";
	width_ = 0;
	height_ = 0;
	frame_number_ = 0;
	hardware_decode_enabled_ = false;
	direct_gpu_output_enabled_ = false;
}

bool GhxstDecoder::is_open() const
{
	return codec_ctx_ != nullptr;
}

std::string GhxstDecoder::codec() const
{
	return codec_;
}

std::string GhxstDecoder::decode_mode() const
{
	return decode_mode_;
}

std::string GhxstDecoder::last_error() const
{
	return last_error_;
}

#ifdef _WIN32
void GhxstDecoder::release_d3d11_resources()
{
	shared_bgra_output_view_.Reset();
	shared_bgra_texture_.Reset();
	video_processor_.Reset();
	video_processor_enumerator_.Reset();
	d3d11_video_context_.Reset();
	d3d11_video_device_.Reset();
	d3d11_context_.Reset();
	d3d11_device_.Reset();
	shared_texture_handle_ = 0;
	shared_texture_generation_ = 0;
	video_processor_width_ = 0;
	video_processor_height_ = 0;
	shared_texture_width_ = 0;
	shared_texture_height_ = 0;
}

bool GhxstDecoder::attach_d3d11_device_from_ffmpeg()
{
	if (!hw_device_ctx_ || !hw_device_ctx_->data) {
		last_error_ = "D3D11VA device context is missing";
		return false;
	}

	auto *hw_device = reinterpret_cast<AVHWDeviceContext *>(hw_device_ctx_->data);
	if (!hw_device || !hw_device->hwctx) {
		last_error_ = "FFmpeg D3D11VA hwctx is missing";
		return false;
	}

	auto *d3d11va = reinterpret_cast<AVD3D11VADeviceContext *>(hw_device->hwctx);
	if (!d3d11va || !d3d11va->device || !d3d11va->device_context) {
		last_error_ = "FFmpeg D3D11VA device/context is missing";
		return false;
	}

	d3d11_device_ = d3d11va->device;
	d3d11_context_ = d3d11va->device_context;

	HRESULT hr = d3d11_device_.As(&d3d11_video_device_);
	if (FAILED(hr) || !d3d11_video_device_) {
		last_error_ = "D3D11 video device interface unavailable";
		return false;
	}

	hr = d3d11_context_.As(&d3d11_video_context_);
	if (FAILED(hr) || !d3d11_video_context_) {
		last_error_ = "D3D11 video context interface unavailable";
		return false;
	}

	return true;
}

bool GhxstDecoder::ensure_d3d11_video_processor(int width, int height)
{
	if (!d3d11_video_device_ || !d3d11_video_context_) {
		last_error_ = "D3D11 video processor interfaces are not ready";
		return false;
	}

	if (video_processor_ && video_processor_enumerator_ && video_processor_width_ == width && video_processor_height_ == height) {
		return true;
	}

	video_processor_.Reset();
	video_processor_enumerator_.Reset();

	D3D11_VIDEO_PROCESSOR_CONTENT_DESC content_desc = {};
	content_desc.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
	content_desc.InputWidth = static_cast<UINT>(width);
	content_desc.InputHeight = static_cast<UINT>(height);
	content_desc.OutputWidth = static_cast<UINT>(width);
	content_desc.OutputHeight = static_cast<UINT>(height);
	content_desc.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;

	HRESULT hr = d3d11_video_device_->CreateVideoProcessorEnumerator(&content_desc, &video_processor_enumerator_);
	if (FAILED(hr) || !video_processor_enumerator_) {
		last_error_ = "Failed to create D3D11 video processor enumerator";
		return false;
	}

	hr = d3d11_video_device_->CreateVideoProcessor(video_processor_enumerator_.Get(), 0, &video_processor_);
	if (FAILED(hr) || !video_processor_) {
		last_error_ = "Failed to create D3D11 video processor";
		return false;
	}

	video_processor_width_ = width;
	video_processor_height_ = height;
	return true;
}

bool GhxstDecoder::ensure_shared_bgra_texture(int width, int height)
{
	if (shared_bgra_texture_ && shared_bgra_output_view_ && shared_texture_width_ == width && shared_texture_height_ == height && shared_texture_handle_ != 0) {
		return true;
	}

	shared_bgra_output_view_.Reset();
	shared_bgra_texture_.Reset();
	shared_texture_handle_ = 0;

	D3D11_TEXTURE2D_DESC texture_desc = {};
	texture_desc.Width = static_cast<UINT>(width);
	texture_desc.Height = static_cast<UINT>(height);
	texture_desc.MipLevels = 1;
	texture_desc.ArraySize = 1;
	texture_desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
	texture_desc.SampleDesc.Count = 1;
	texture_desc.SampleDesc.Quality = 0;
	texture_desc.Usage = D3D11_USAGE_DEFAULT;
	texture_desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
	texture_desc.CPUAccessFlags = 0;
	texture_desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED;

	HRESULT hr = d3d11_device_->CreateTexture2D(&texture_desc, nullptr, &shared_bgra_texture_);
	if (FAILED(hr) || !shared_bgra_texture_) {
		last_error_ = "Failed to create shared BGRA D3D11 texture";
		return false;
	}

	D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC output_desc = {};
	output_desc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
	output_desc.Texture2D.MipSlice = 0;

	hr = d3d11_video_device_->CreateVideoProcessorOutputView(
		shared_bgra_texture_.Get(),
		video_processor_enumerator_.Get(),
		&output_desc,
		&shared_bgra_output_view_
	);
	if (FAILED(hr) || !shared_bgra_output_view_) {
		last_error_ = "Failed to create shared BGRA video processor output view";
		shared_bgra_texture_.Reset();
		return false;
	}

	Microsoft::WRL::ComPtr<IDXGIResource> dxgi_resource;
	hr = shared_bgra_texture_.As(&dxgi_resource);
	if (FAILED(hr) || !dxgi_resource) {
		last_error_ = "Failed to query shared texture DXGI resource";
		shared_bgra_output_view_.Reset();
		shared_bgra_texture_.Reset();
		return false;
	}

	HANDLE shared_handle = nullptr;
	hr = dxgi_resource->GetSharedHandle(&shared_handle);
	if (FAILED(hr) || !shared_handle) {
		last_error_ = "Failed to get D3D11 shared texture handle";
		shared_bgra_output_view_.Reset();
		shared_bgra_texture_.Reset();
		return false;
	}

	shared_texture_handle_ = static_cast<uint32_t>(reinterpret_cast<uintptr_t>(shared_handle));
	shared_texture_generation_++;
	shared_texture_width_ = width;
	shared_texture_height_ = height;
	return true;
}

bool GhxstDecoder::process_d3d11_frame_to_shared_texture(::AVFrame *frame, DecodedFrame &frame_out)
{
	if (!frame || frame->format != AV_PIX_FMT_D3D11 || !frame->data[0]) {
		last_error_ = "Direct GPU output received a non-D3D11 frame";
		return false;
	}

	auto *decoded_texture = reinterpret_cast<ID3D11Texture2D *>(frame->data[0]);
	const int texture_index = static_cast<int>(reinterpret_cast<intptr_t>(frame->data[1]));
	const int src_w = frame->width;
	const int src_h = frame->height;

	if (src_w <= 0 || src_h <= 0) {
		last_error_ = "D3D11 decoded frame has invalid dimensions";
		return false;
	}

	if (!ensure_d3d11_video_processor(src_w, src_h)) {
		return false;
	}

	if (!ensure_shared_bgra_texture(src_w, src_h)) {
		return false;
	}

	D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC input_desc = {};
	input_desc.FourCC = 0;
	input_desc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
	input_desc.Texture2D.MipSlice = 0;
	input_desc.Texture2D.ArraySlice = texture_index > 0 ? static_cast<UINT>(texture_index) : 0;

	Microsoft::WRL::ComPtr<ID3D11VideoProcessorInputView> input_view;
	HRESULT hr = d3d11_video_device_->CreateVideoProcessorInputView(
		decoded_texture,
		video_processor_enumerator_.Get(),
		&input_desc,
		&input_view
	);

	if (FAILED(hr) || !input_view) {
		last_error_ = "Failed to create D3D11 video processor input view";
		return false;
	}

	D3D11_VIDEO_PROCESSOR_STREAM stream = {};
	stream.Enable = TRUE;
	stream.OutputIndex = 0;
	stream.InputFrameOrField = 0;
	stream.PastFrames = 0;
	stream.FutureFrames = 0;
	stream.ppPastSurfaces = nullptr;
	stream.pInputSurface = input_view.Get();
	stream.ppFutureSurfaces = nullptr;

	hr = d3d11_video_context_->VideoProcessorBlt(
		video_processor_.Get(),
		shared_bgra_output_view_.Get(),
		0,
		1,
		&stream
	);

	if (FAILED(hr)) {
		last_error_ = "D3D11 VideoProcessorBlt failed";
		return false;
	}

	if (d3d11_context_) {
		d3d11_context_->Flush();
	}

	frame_number_++;
	width_ = src_w;
	height_ = src_h;

	frame_out.valid = true;
	frame_out.width = src_w;
	frame_out.height = src_h;
	frame_out.frame_number = frame_number_;
	frame_out.gpu_shared = true;
	frame_out.shared_texture_handle = shared_texture_handle_;
	frame_out.shared_texture_generation = shared_texture_generation_;
	frame_out.bgra.reset();

	return true;
}
#endif

std::shared_ptr<std::vector<uint8_t>> GhxstDecoder::next_bgra_buffer(int required_size)
{
	if (required_size <= 0) {
		return {};
	}

	if (bgra_buffers_.empty()) {
		bgra_buffers_.reserve(3);
		for (int i = 0; i < 3; ++i) {
			auto buffer = std::make_shared<std::vector<uint8_t>>();
			buffer->resize(static_cast<size_t>(required_size));
			bgra_buffers_.push_back(buffer);
		}
		next_bgra_buffer_ = 0;
	}

	auto buffer = bgra_buffers_[next_bgra_buffer_];
	next_bgra_buffer_ = (next_bgra_buffer_ + 1) % bgra_buffers_.size();

	if (static_cast<int>(buffer->size()) != required_size) {
		buffer->resize(static_cast<size_t>(required_size));
	}

	return buffer;
}

bool GhxstDecoder::receive_frame(DecodedFrame &frame_out)
{
	if (!codec_ctx_) {
		return false;
	}

	int ret = avcodec_receive_frame(codec_ctx_, frame_);
	if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
		return false;
	}

	if (ret < 0) {
		last_error_ = "FFmpeg receive_frame failed";
		return false;
	}

#ifdef _WIN32
	if (direct_gpu_output_enabled_ && hardware_decode_enabled_ && frame_->format == AV_PIX_FMT_D3D11) {
		const bool ok = process_d3d11_frame_to_shared_texture(frame_, frame_out);
		av_frame_unref(frame_);
		return ok;
	}
#endif

	AVFrame *source_frame = frame_;

	if (hardware_decode_enabled_ && frame_->format == AV_PIX_FMT_D3D11) {
		av_frame_unref(transfer_frame_);

		const int transfer_ret = av_hwframe_transfer_data(transfer_frame_, frame_, 0);
		if (transfer_ret < 0) {
			av_frame_unref(frame_);
			last_error_ = "Failed to transfer D3D11VA decoded frame to system memory";
			return false;
		}

		source_frame = transfer_frame_;
	}

	const int src_w = source_frame->width;
	const int src_h = source_frame->height;

	if (src_w <= 0 || src_h <= 0) {
		av_frame_unref(frame_);
		av_frame_unref(transfer_frame_);
		last_error_ = "Decoded frame has invalid dimensions";
		return false;
	}

	if (!sws_ctx_ || width_ != src_w || height_ != src_h) {
		if (sws_ctx_) {
			sws_freeContext(sws_ctx_);
			sws_ctx_ = nullptr;
		}

		width_ = src_w;
		height_ = src_h;

		sws_ctx_ = sws_getContext(
			src_w,
			src_h,
			static_cast<AVPixelFormat>(source_frame->format),
			src_w,
			src_h,
			AV_PIX_FMT_BGRA,
			SWS_FAST_BILINEAR,
			nullptr,
			nullptr,
			nullptr
		);

		if (!sws_ctx_) {
			av_frame_unref(frame_);
			av_frame_unref(transfer_frame_);
			last_error_ = "Failed to create swscale context";
			return false;
		}
	}

	const int buffer_size = av_image_get_buffer_size(AV_PIX_FMT_BGRA, src_w, src_h, 1);
	if (buffer_size <= 0) {
		av_frame_unref(frame_);
		av_frame_unref(transfer_frame_);
		last_error_ = "Invalid BGRA buffer size";
		return false;
	}

	auto bgra_buffer = next_bgra_buffer(buffer_size);
	if (!bgra_buffer || bgra_buffer->empty()) {
		av_frame_unref(frame_);
		av_frame_unref(transfer_frame_);
		last_error_ = "Failed to allocate BGRA output buffer";
		return false;
	}

	const int fill_ret = av_image_fill_arrays(
		bgra_frame_->data,
		bgra_frame_->linesize,
		bgra_buffer->data(),
		AV_PIX_FMT_BGRA,
		src_w,
		src_h,
		1
	);

	if (fill_ret < 0) {
		av_frame_unref(frame_);
		av_frame_unref(transfer_frame_);
		last_error_ = "Failed to fill BGRA frame arrays";
		return false;
	}

	sws_scale(
		sws_ctx_,
		source_frame->data,
		source_frame->linesize,
		0,
		src_h,
		bgra_frame_->data,
		bgra_frame_->linesize
	);

	frame_number_++;

	frame_out.valid = true;
	frame_out.width = src_w;
	frame_out.height = src_h;
	frame_out.frame_number = frame_number_;
	frame_out.bgra = bgra_buffer;
	frame_out.gpu_shared = false;
	frame_out.shared_texture_handle = 0;
	frame_out.shared_texture_generation = 0;

	av_frame_unref(frame_);
	av_frame_unref(transfer_frame_);

	return true;
}

bool GhxstDecoder::decode_packet(const std::vector<uint8_t> &packet, DecodedFrame &frame_out)
{
	frame_out = DecodedFrame{};

	if (!codec_ctx_) {
		last_error_ = "Decoder is not open";
		return false;
	}

	if (packet.empty()) {
		return false;
	}

	AVPacket av_packet = {};
	av_packet.data = const_cast<uint8_t *>(packet.data());
	av_packet.size = static_cast<int>(packet.size());
	av_packet.pts = AV_NOPTS_VALUE;
	av_packet.dts = AV_NOPTS_VALUE;

	int ret = avcodec_send_packet(codec_ctx_, &av_packet);

	if (ret == AVERROR(EAGAIN)) {
		return receive_frame(frame_out);
	}

	if (ret < 0) {
		last_error_ = "FFmpeg send_packet failed";
		return false;
	}

	return receive_frame(frame_out);
}

} // namespace ghxst
