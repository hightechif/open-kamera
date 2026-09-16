#ifndef OPENKAMERA_HISTOGRAM_COMPUTE_H
#define OPENKAMERA_HISTOGRAM_COMPUTE_H

#include <cstdint>
#include <vector>

namespace openkamera {
namespace preview {

enum class HistogramMode {
    LUMINANCE = 0,
    VALUE = 1,
    INTENSITY = 2,
    LIGHTNESS = 3
};

void compute_histogram_rgb(
    const uint8_t* src_pixels,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    int32_t* out_histogram_r,
    int32_t* out_histogram_g,
    int32_t* out_histogram_b
);

void compute_histogram_single(
    const uint8_t* src_pixels,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    HistogramMode mode,
    int32_t* out_histogram
);

} // namespace preview
} // namespace openkamera

#endif // OPENKAMERA_HISTOGRAM_COMPUTE_H
