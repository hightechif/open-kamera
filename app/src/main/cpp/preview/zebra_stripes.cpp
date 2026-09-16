#include "zebra_stripes.h"
#include <algorithm>
#include <cstring>

namespace openkamera {
namespace preview {

void compute_zebra_stripes(
    const uint8_t* src_pixels,
    uint8_t* out_pixels,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    int threshold,
    uint8_t fg_r, uint8_t fg_g, uint8_t fg_b, uint8_t fg_a,
    uint8_t bg_r, uint8_t bg_g, uint8_t bg_b, uint8_t bg_a,
    int stripe_width
) {
    if (!src_pixels || !out_pixels || stripe_width <= 0) return;

    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* in_row = src_pixels + y * stride;
        uint8_t* out_row = out_pixels + y * stride;

        for (uint32_t x = 0; x < width; ++x) {
            uint8_t r = in_row[x * 4 + 0];
            uint8_t g = in_row[x * 4 + 1];
            uint8_t b = in_row[x * 4 + 2];

            uint8_t max_val = std::max({r, g, b});

            if (max_val >= threshold) {
                int stripe = static_cast<int>((x + y) / stripe_width);
                if (stripe % 2 == 0) {
                    out_row[x * 4 + 0] = bg_r;
                    out_row[x * 4 + 1] = bg_g;
                    out_row[x * 4 + 2] = bg_b;
                    out_row[x * 4 + 3] = bg_a;
                } else {
                    out_row[x * 4 + 0] = fg_r;
                    out_row[x * 4 + 1] = fg_g;
                    out_row[x * 4 + 2] = fg_b;
                    out_row[x * 4 + 3] = fg_a;
                }
            } else {
                out_row[x * 4 + 0] = 0;
                out_row[x * 4 + 1] = 0;
                out_row[x * 4 + 2] = 0;
                out_row[x * 4 + 3] = 0;
            }
        }
    }
}

} // namespace preview
} // namespace openkamera
