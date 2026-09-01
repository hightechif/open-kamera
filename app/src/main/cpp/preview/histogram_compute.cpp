#include "histogram_compute.h"
#include "../utils/neon_math.h"
#include <cstring>
#include <algorithm>

namespace openkamera {
namespace preview {

void compute_histogram_rgb(
    const uint8_t* src_pixels,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    int32_t* out_histogram_r,
    int32_t* out_histogram_g,
    int32_t* out_histogram_b
) {
    if (!src_pixels || !out_histogram_r || !out_histogram_g || !out_histogram_b) return;

    std::memset(out_histogram_r, 0, 256 * sizeof(int32_t));
    std::memset(out_histogram_g, 0, 256 * sizeof(int32_t));
    std::memset(out_histogram_b, 0, 256 * sizeof(int32_t));

    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* row = src_pixels + y * stride;
        for (uint32_t x = 0; x < width; ++x) {
            uint8_t r = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t b = row[x * 4 + 2];

            out_histogram_r[r]++;
            out_histogram_g[g]++;
            out_histogram_b[b]++;
        }
    }
}

void compute_histogram_single(
    const uint8_t* src_pixels,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    HistogramMode mode,
    int32_t* out_histogram
) {
    if (!src_pixels || !out_histogram) return;

    std::memset(out_histogram, 0, 256 * sizeof(int32_t));

    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* row = src_pixels + y * stride;
        for (uint32_t x = 0; x < width; ++x) {
            uint8_t r = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t b = row[x * 4 + 2];

            uint8_t bin_val = 0;
            switch (mode) {
                case HistogramMode::LUMINANCE:
                    bin_val = math::rgb_to_luminance(r, g, b);
                    break;
                case HistogramMode::VALUE:
                    bin_val = math::rgb_to_value(r, g, b);
                    break;
                case HistogramMode::INTENSITY:
                    bin_val = math::rgb_to_intensity(r, g, b);
                    break;
                case HistogramMode::LIGHTNESS:
                    bin_val = math::rgb_to_lightness(r, g, b);
                    break;
            }
            out_histogram[bin_val]++;
        }
    }
}

} // namespace preview
} // namespace openkamera
