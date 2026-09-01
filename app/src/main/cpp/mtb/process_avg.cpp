#include "process_avg.h"
#include "../utils/neon_math.h"
#include <algorithm>
#include <cmath>

namespace openkamera {
namespace mtb {

void accumulate_frame_avg(
    uint8_t* base_rgba,
    const uint8_t* new_rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    int offset_x,
    int offset_y,
    float avg_factor,
    float wiener_c
) {
    if (!base_rgba || !new_rgba) return;

    for (uint32_t y = 0; y < height; ++y) {
        int ny = static_cast<int>(y) + offset_y;
        if (ny < 0 || ny >= static_cast<int>(height)) continue;

        uint8_t* base_row = base_rgba + y * stride;
        const uint8_t* new_row = new_rgba + ny * stride;

        for (uint32_t x = 0; x < width; ++x) {
            int nx = static_cast<int>(x) + offset_x;
            if (nx < 0 || nx >= static_cast<int>(width)) continue;

            float base_r = base_row[x * 4 + 0];
            float base_g = base_row[x * 4 + 1];
            float base_b = base_row[x * 4 + 2];

            float new_r = new_row[nx * 4 + 0];
            float new_g = new_row[nx * 4 + 1];
            float new_b = new_row[nx * 4 + 2];

            float dr = base_r - new_r;
            float dg = base_g - new_g;
            float db = base_b - new_b;
            float diff = dr * dr + dg * dg + db * db;

            // Wiener-inspired temporal weighting for ghosting/motion rejection
            float weight = avg_factor;
            if (wiener_c > 0.0f) {
                float local_weight = wiener_c / (wiener_c + diff);
                weight *= local_weight;
            }

            float blended_r = base_r * (1.0f - weight) + new_r * weight;
            float blended_g = base_g * (1.0f - weight) + new_g * weight;
            float blended_b = base_b * (1.0f - weight) + new_b * weight;

            base_row[x * 4 + 0] = math::clamp_f_u8(blended_r);
            base_row[x * 4 + 1] = math::clamp_f_u8(blended_g);
            base_row[x * 4 + 2] = math::clamp_f_u8(blended_b);
        }
    }
}

void apply_brighten(
    uint8_t* rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    float gain,
    float gamma,
    float low_x,
    float mid_x,
    float max_x
) {
    if (!rgba || width == 0 || height == 0) return;

    float gain_a = 1.0f;
    float gain_b = 0.0f;
    if (mid_x > low_x) {
        gain_a = (gain * mid_x - low_x) / (mid_x - low_x);
        gain_b = low_x * mid_x * (1.0f - gain) / (mid_x - low_x);
    }

    uint8_t lut[256];
    for (int i = 0; i < 256; ++i) {
        float val = static_cast<float>(i);
        float new_val = val;
        if (val >= low_x && val <= mid_x) {
            new_val = gain_a * val + gain_b;
        } else if (val > mid_x && val <= max_x && max_x > mid_x) {
            float alpha = (val - mid_x) / (max_x - mid_x);
            new_val = (1.0f - alpha) * gain * mid_x + alpha * max_x;
        }
        if (gamma != 1.0f && gamma > 0.0f) {
            new_val = std::pow(new_val / 255.0f, gamma) * 255.0f;
        }
        lut[i] = math::clamp_f_u8(new_val);
    }

    for (uint32_t y = 0; y < height; ++y) {
        uint8_t* row = rgba + y * stride;
        for (uint32_t x = 0; x < width; ++x) {
            row[x * 4 + 0] = lut[row[x * 4 + 0]];
            row[x * 4 + 1] = lut[row[x * 4 + 1]];
            row[x * 4 + 2] = lut[row[x * 4 + 2]];
        }
    }
}

} // namespace mtb
} // namespace openkamera
