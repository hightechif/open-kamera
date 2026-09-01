#include "pyramid_blending.h"
#include "../utils/neon_math.h"
#include <algorithm>
#include <cmath>

namespace openkamera {
namespace panorama {

void blend_pyramid_seam(
    const uint8_t* lhs_rgba,
    const uint8_t* rhs_rgba,
    uint8_t* out_rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    const int32_t* best_path_mid_x,
    int blend_width
) {
    if (!lhs_rgba || !rhs_rgba || !out_rgba || width == 0 || height == 0) return;

    float inv_blend_w = blend_width > 0 ? (1.0f / static_cast<float>(blend_width)) : 1.0f;
    int half_blend_w = blend_width / 2;

    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* l_row = lhs_rgba + y * stride;
        const uint8_t* r_row = rhs_rgba + y * stride;
        uint8_t* out_row = out_rgba + y * stride;

        int mid_x = best_path_mid_x ? best_path_mid_x[y] : static_cast<int>(width / 2);
        int start_x = mid_x - half_blend_w;

        for (uint32_t x = 0; x < width; ++x) {
            float alpha = static_cast<float>(static_cast<int>(x) - start_x) * inv_blend_w;
            alpha = std::clamp(alpha, 0.0f, 1.0f);
            float inv_alpha = 1.0f - alpha;

            float r0 = l_row[x * 4 + 0];
            float g0 = l_row[x * 4 + 1];
            float b0 = l_row[x * 4 + 2];

            float r1 = r_row[x * 4 + 0];
            float g1 = r_row[x * 4 + 1];
            float b1 = r_row[x * 4 + 2];

            out_row[x * 4 + 0] = math::clamp_f_u8(inv_alpha * r0 + alpha * r1);
            out_row[x * 4 + 1] = math::clamp_f_u8(inv_alpha * g0 + alpha * g1);
            out_row[x * 4 + 2] = math::clamp_f_u8(inv_alpha * b0 + alpha * b1);
            out_row[x * 4 + 3] = 255;
        }
    }
}

int64_t compute_frame_overlap_error(
    const uint8_t* frame0_rgba,
    const uint8_t* frame1_rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride
) {
    if (!frame0_rgba || !frame1_rgba) return 0;

    int64_t total_error = 0;
    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* row0 = frame0_rgba + y * stride;
        const uint8_t* row1 = frame1_rgba + y * stride;

        for (uint32_t x = 0; x < width; ++x) {
            int dr = static_cast<int>(row0[x * 4 + 0]) - static_cast<int>(row1[x * 4 + 0]);
            int dg = static_cast<int>(row0[x * 4 + 1]) - static_cast<int>(row1[x * 4 + 1]);
            int db = static_cast<int>(row0[x * 4 + 2]) - static_cast<int>(row1[x * 4 + 2]);
            total_error += (dr * dr + dg * dg + db * db);
        }
    }

    return total_error;
}

} // namespace panorama
} // namespace openkamera
