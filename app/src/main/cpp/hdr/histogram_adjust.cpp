#include "histogram_adjust.h"
#include "../utils/neon_math.h"
#include <cstring>
#include <algorithm>

namespace openkamera {
namespace hdr {

void apply_histogram_equalization(
    uint8_t* rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride
) {
    if (!rgba || width == 0 || height == 0) return;

    int histogram[256] = {0};
    uint32_t total_pixels = width * height;

    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* row = rgba + y * stride;
        for (uint32_t x = 0; x < width; ++x) {
            uint8_t r = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t b = row[x * 4 + 2];
            uint8_t luma = math::rgb_to_luminance(r, g, b);
            histogram[luma]++;
        }
    }

    uint8_t lut[256];
    int cumulative = 0;
    for (int i = 0; i < 256; ++i) {
        cumulative += histogram[i];
        float cdf = static_cast<float>(cumulative) / static_cast<float>(total_pixels);
        lut[i] = math::clamp_f_u8(cdf * 255.0f);
    }

    for (uint32_t y = 0; y < height; ++y) {
        uint8_t* row = rgba + y * stride;
        for (uint32_t x = 0; x < width; ++x) {
            uint8_t r = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t b = row[x * 4 + 2];

            uint8_t luma = math::rgb_to_luminance(r, g, b);
            if (luma > 0) {
                float scale = static_cast<float>(lut[luma]) / static_cast<float>(luma);
                row[x * 4 + 0] = math::clamp_f_u8(r * scale);
                row[x * 4 + 1] = math::clamp_f_u8(g * scale);
                row[x * 4 + 2] = math::clamp_f_u8(b * scale);
            }
        }
    }
}

} // namespace hdr
} // namespace openkamera
