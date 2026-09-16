#include "process_hdr.h"
#include "../utils/neon_math.h"
#include <cmath>
#include <algorithm>

namespace openkamera {
namespace hdr {

namespace {

inline float fu2_tonemap(float x) {
    const float A = 0.15f;
    const float B = 0.50f;
    const float C = 0.10f;
    const float D = 0.20f;
    const float E = 0.02f;
    const float F = 0.30f;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

inline void tonemap_pixel(
    float hdr_r, float hdr_g, float hdr_b,
    TonemapAlgorithm algo,
    float tonemap_scale,
    float linear_scale,
    uint8_t& out_r, uint8_t& out_g, uint8_t& out_b
) {
    switch (algo) {
        case TonemapAlgorithm::CLAMP: {
            out_r = math::clamp_f_u8(hdr_r);
            out_g = math::clamp_f_u8(hdr_g);
            out_b = math::clamp_f_u8(hdr_b);
            break;
        }
        case TonemapAlgorithm::EXPONENTIAL: {
            const float exposure = 1.2f;
            float out_r_f = linear_scale * 255.0f * (1.0f - std::exp(-exposure * hdr_r / 255.0f));
            float out_g_f = linear_scale * 255.0f * (1.0f - std::exp(-exposure * hdr_g / 255.0f));
            float out_b_f = linear_scale * 255.0f * (1.0f - std::exp(-exposure * hdr_b / 255.0f));
            out_r = math::clamp_f_u8(out_r_f);
            out_g = math::clamp_f_u8(out_g_f);
            out_b = math::clamp_f_u8(out_b_f);
            break;
        }
        case TonemapAlgorithm::REINHARD: {
            float val = std::max({hdr_r, hdr_g, hdr_b});
            float scale = (255.0f / (tonemap_scale + val)) * linear_scale;
            out_r = math::clamp_f_u8(scale * hdr_r);
            out_g = math::clamp_f_u8(scale * hdr_g);
            out_b = math::clamp_f_u8(scale * hdr_b);
            break;
        }
        case TonemapAlgorithm::FU2: {
            const float fu2_exposure_bias = 2.0f / 255.0f;
            const float W = 11.2f;
            float white_scale = 255.0f / fu2_tonemap(W);
            float curr_r = fu2_tonemap(fu2_exposure_bias * hdr_r) * white_scale;
            float curr_g = fu2_tonemap(fu2_exposure_bias * hdr_g) * white_scale;
            float curr_b = fu2_tonemap(fu2_exposure_bias * hdr_b) * white_scale;
            out_r = math::clamp_f_u8(curr_r);
            out_g = math::clamp_f_u8(curr_g);
            out_b = math::clamp_f_u8(curr_b);
            break;
        }
        case TonemapAlgorithm::ACES: {
            const float a = 2.51f;
            const float b = 0.03f;
            const float c = 2.43f;
            const float d = 0.59f;
            const float e = 0.14f;

            auto aces_curve = [&](float x_in) -> float {
                float x = x_in / 255.0f;
                return 255.0f * (x * (a * x + b)) / (x * (c * x + d) + e);
            };

            out_r = math::clamp_f_u8(aces_curve(hdr_r));
            out_g = math::clamp_f_u8(aces_curve(hdr_g));
            out_b = math::clamp_f_u8(aces_curve(hdr_b));
            break;
        }
    }
}

} // namespace

void process_hdr_fusion(
    const std::vector<FrameInfo>& frames,
    uint8_t* out_rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    TonemapAlgorithm tonemap_algo,
    float tonemap_scale,
    float linear_scale
) {
    if (frames.empty() || !out_rgba) return;

    const float weight_scale = (1.0f - 1.0f / 127.5f) / 127.5f;
    size_t num_frames = frames.size();

    for (uint32_t y = 0; y < height; ++y) {
        uint8_t* out_row = out_rgba + y * stride;

        for (uint32_t x = 0; x < width; ++x) {
            float hdr_r = 0.0f;
            float hdr_g = 0.0f;
            float hdr_b = 0.0f;
            float sum_weights = 0.0f;

            for (size_t f = 0; f < num_frames; ++f) {
                const auto& frame = frames[f];
                int tx = static_cast<int>(x) + frame.offset_x;
                int ty = static_cast<int>(y) + frame.offset_y;

                if (tx < 0 || tx >= static_cast<int>(width) || ty < 0 || ty >= static_cast<int>(height)) {
                    continue;
                }

                const uint8_t* in_pixel = frame.pixels + ty * stride + tx * 4;
                float r = in_pixel[0];
                float g = in_pixel[1];
                float b = in_pixel[2];

                float val = std::max({r, g, b});
                float weight = 1.0f - weight_scale * std::abs(val - 127.5f);
                if (weight < 0.01f) weight = 0.01f;

                float calibrated_r = frame.param_a * r + frame.param_b;
                float calibrated_g = frame.param_a * g + frame.param_b;
                float calibrated_b = frame.param_a * b + frame.param_b;

                hdr_r += weight * calibrated_r;
                hdr_g += weight * calibrated_g;
                hdr_b += weight * calibrated_b;
                sum_weights += weight;
            }

            if (sum_weights > 0.0f) {
                hdr_r /= sum_weights;
                hdr_g /= sum_weights;
                hdr_b /= sum_weights;
            }

            uint8_t final_r = 0, final_g = 0, final_b = 0;
            tonemap_pixel(hdr_r, hdr_g, hdr_b, tonemap_algo, tonemap_scale, linear_scale, final_r, final_g, final_b);

            out_row[x * 4 + 0] = final_r;
            out_row[x * 4 + 1] = final_g;
            out_row[x * 4 + 2] = final_b;
            out_row[x * 4 + 3] = 255;
        }
    }
}

} // namespace hdr
} // namespace openkamera
