#include "create_mtb.h"
#include <algorithm>
#include <vector>
#include <cstdlib>
#include <cstring>

namespace openkamera {
namespace mtb {

int compute_median_value(
    const uint8_t* src_rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    int start_x,
    int start_y,
    int crop_w,
    int crop_h
) {
    if (!src_rgba || crop_w <= 0 || crop_h <= 0) return 128;

    int histogram[256] = {0};
    int total_pixels = 0;

    int end_x = std::min(static_cast<int>(width), start_x + crop_w);
    int end_y = std::min(static_cast<int>(height), start_y + crop_h);

    for (int y = start_y; y < end_y; ++y) {
        const uint8_t* row = src_rgba + y * stride;
        for (int x = start_x; x < end_x; ++x) {
            uint8_t r = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t b = row[x * 4 + 2];
            uint8_t val = std::max({r, g, b});
            histogram[val]++;
            total_pixels++;
        }
    }

    if (total_pixels == 0) return 128;

    int target = total_pixels / 2;
    int count = 0;
    for (int i = 0; i < 256; ++i) {
        count += histogram[i];
        if (count >= target) {
            return i;
        }
    }
    return 128;
}

void create_mtb(
    const uint8_t* src_rgba,
    uint8_t* out_mtb_8u,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    int median_val,
    int start_x,
    int start_y,
    int crop_w,
    int crop_h
) {
    if (!src_rgba || !out_mtb_8u || crop_w <= 0 || crop_h <= 0) return;

    int end_x = std::min(static_cast<int>(width), start_x + crop_w);
    int end_y = std::min(static_cast<int>(height), start_y + crop_h);

    for (int y = start_y; y < end_y; ++y) {
        const uint8_t* row = src_rgba + y * stride;
        uint8_t* out_row = out_mtb_8u + (y - start_y) * crop_w;

        for (int x = start_x; x < end_x; ++x) {
            uint8_t r = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t b = row[x * 4 + 2];
            uint8_t val = std::max({r, g, b});

            int diff = std::abs(static_cast<int>(val) - median_val);
            if (diff <= 4) {
                out_row[x - start_x] = 127; // noise mask (ignore)
            } else if (val <= median_val) {
                out_row[x - start_x] = 0;
            } else {
                out_row[x - start_x] = 255;
            }
        }
    }
}

void create_greyscale(
    const uint8_t* src_rgba,
    uint8_t* out_grey_8u,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    int start_x,
    int start_y,
    int crop_w,
    int crop_h
) {
    if (!src_rgba || !out_grey_8u || crop_w <= 0 || crop_h <= 0) return;

    int end_x = std::min(static_cast<int>(width), start_x + crop_w);
    int end_y = std::min(static_cast<int>(height), start_y + crop_h);

    for (int y = start_y; y < end_y; ++y) {
        const uint8_t* row = src_rgba + y * stride;
        uint8_t* out_row = out_grey_8u + (y - start_y) * crop_w;

        for (int x = start_x; x < end_x; ++x) {
            uint8_t r = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t b = row[x * 4 + 2];
            out_row[x - start_x] = std::max({r, g, b});
        }
    }
}

} // namespace mtb
} // namespace openkamera
