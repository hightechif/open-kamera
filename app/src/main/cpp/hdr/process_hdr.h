#ifndef OPENKAMERA_PROCESS_HDR_H
#define OPENKAMERA_PROCESS_HDR_H

#include <cstdint>
#include <vector>

namespace openkamera {
namespace hdr {

enum class TonemapAlgorithm {
    CLAMP = 0,
    EXPONENTIAL = 1,
    REINHARD = 2,
    FU2 = 3,
    ACES = 4
};

struct FrameInfo {
    const uint8_t* pixels;
    int offset_x;
    int offset_y;
    float param_a;
    float param_b;
};

void process_hdr_fusion(
    const std::vector<FrameInfo>& frames,
    uint8_t* out_rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    TonemapAlgorithm tonemap_algo,
    float tonemap_scale,
    float linear_scale
);

} // namespace hdr
} // namespace openkamera

#endif // OPENKAMERA_PROCESS_HDR_H
