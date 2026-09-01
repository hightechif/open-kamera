#ifndef OPENKAMERA_FEATURE_DETECTOR_H
#define OPENKAMERA_FEATURE_DETECTOR_H

#include <cstdint>
#include <vector>

namespace openkamera {
namespace panorama {

struct Point2D {
    int x;
    int y;
};

std::vector<Point2D> detect_harris_corners(
    const uint8_t* rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    float corner_threshold
);

} // namespace panorama
} // namespace openkamera

#endif // OPENKAMERA_FEATURE_DETECTOR_H
