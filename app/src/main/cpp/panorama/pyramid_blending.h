#ifndef OPENKAMERA_PYRAMID_BLENDING_H
#define OPENKAMERA_PYRAMID_BLENDING_H

#include <cstdint>
#include <vector>

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
);

int64_t compute_frame_overlap_error(
    const uint8_t* frame0_rgba,
    const uint8_t* frame1_rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride
);

} // namespace panorama
} // namespace openkamera

#endif // OPENKAMERA_PYRAMID_BLENDING_H
