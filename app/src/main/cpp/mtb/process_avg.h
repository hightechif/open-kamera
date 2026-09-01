#ifndef OPENKAMERA_PROCESS_AVG_H
#define OPENKAMERA_PROCESS_AVG_H

#include <cstdint>

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
);

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
);

} // namespace mtb
} // namespace openkamera

#endif // OPENKAMERA_PROCESS_AVG_H
