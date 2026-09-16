#include "focus_peaking.h"
#include <cstring>
#include <vector>

namespace openkamera {
namespace preview {

void compute_focus_peaking(
    const uint8_t* src_pixels,
    uint8_t* temp_buffer,
    uint8_t* out_pixels,
    uint32_t width,
    uint32_t height,
    uint32_t stride
) {
    if (!src_pixels || !out_pixels || width < 3 || height < 3) return;

    // If no temp_buffer provided, allocate a temporary buffer for stage 1
    std::vector<uint8_t> local_temp;
    uint8_t* temp = temp_buffer;
    if (!temp) {
        local_temp.resize(height * stride);
        temp = local_temp.data();
    }

    std::memset(temp, 0, height * stride);
    std::memset(out_pixels, 0, height * stride);

    // Stage 1: Sobel / Laplacian high pass filter
    for (uint32_t y = 1; y < height - 1; ++y) {
        const uint8_t* row_prev = src_pixels + (y - 1) * stride;
        const uint8_t* row_curr = src_pixels + y * stride;
        const uint8_t* row_next = src_pixels + (y + 1) * stride;
        uint8_t* temp_row = temp + y * stride;

        for (uint32_t x = 1; x < width - 1; ++x) {
            float p0r = row_prev[(x - 1) * 4 + 0], p0g = row_prev[(x - 1) * 4 + 1], p0b = row_prev[(x - 1) * 4 + 2];
            float p1r = row_prev[x * 4 + 0],       p1g = row_prev[x * 4 + 1],       p1b = row_prev[x * 4 + 2];
            float p2r = row_prev[(x + 1) * 4 + 0], p2g = row_prev[(x + 1) * 4 + 1], p2b = row_prev[(x + 1) * 4 + 2];

            float p3r = row_curr[(x - 1) * 4 + 0], p3g = row_curr[(x - 1) * 4 + 1], p3b = row_curr[(x - 1) * 4 + 2];
            float p4r = row_curr[x * 4 + 0],       p4g = row_curr[x * 4 + 1],       p4b = row_curr[x * 4 + 2];
            float p5r = row_curr[(x + 1) * 4 + 0], p5g = row_curr[(x + 1) * 4 + 1], p5b = row_curr[(x + 1) * 4 + 2];

            float p6r = row_next[(x - 1) * 4 + 0], p6g = row_next[(x - 1) * 4 + 1], p6b = row_next[(x - 1) * 4 + 2];
            float p7r = row_next[x * 4 + 0],       p7g = row_next[x * 4 + 1],       p7b = row_next[x * 4 + 2];
            float p8r = row_next[(x + 1) * 4 + 0], p8g = row_next[(x + 1) * 4 + 1], p8b = row_next[(x + 1) * 4 + 2];

            float vr = 8.0f * p4r - p0r - p1r - p2r - p3r - p5r - p6r - p7r - p8r;
            float vg = 8.0f * p4g - p0g - p1g - p2g - p3g - p5g - p6g - p7g - p8g;
            float vb = 8.0f * p4b - p0b - p1b - p2b - p3b - p5b - p6b - p7b - p8b;

            float strength = vr * vr + vg * vg + vb * vb;

            if (strength > 256.0f * 256.0f) {
                temp_row[x * 4 + 0] = 255;
                temp_row[x * 4 + 1] = 255;
                temp_row[x * 4 + 2] = 255;
                temp_row[x * 4 + 3] = 255;
            }
        }
    }

    // Stage 2: Filtering (cross filter count >= 3)
    for (uint32_t y = 1; y < height - 1; ++y) {
        const uint8_t* temp_prev = temp + (y - 1) * stride;
        const uint8_t* temp_curr = temp + y * stride;
        const uint8_t* temp_next = temp + (y + 1) * stride;
        uint8_t* out_row = out_pixels + y * stride;

        for (uint32_t x = 1; x < width - 1; ++x) {
            int count = 0;
            if (temp_prev[x * 4 + 0] == 255) count++;
            if (temp_curr[(x - 1) * 4 + 0] == 255) count++;
            if (temp_curr[x * 4 + 0] == 255) count++;
            if (temp_curr[(x + 1) * 4 + 0] == 255) count++;
            if (temp_next[x * 4 + 0] == 255) count++;

            if (count >= 3) {
                out_row[x * 4 + 0] = 255;
                out_row[x * 4 + 1] = 255;
                out_row[x * 4 + 2] = 255;
                out_row[x * 4 + 3] = 255;
            }
        }
    }
}

} // namespace preview
} // namespace openkamera
