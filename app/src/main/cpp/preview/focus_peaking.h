#ifndef OPENKAMERA_FOCUS_PEAKING_H
#define OPENKAMERA_FOCUS_PEAKING_H

#include <cstdint>

namespace openkamera {
namespace preview {

void compute_focus_peaking(
    const uint8_t* src_pixels,
    uint8_t* temp_buffer,
    uint8_t* out_pixels,
    uint32_t width,
    uint32_t height,
    uint32_t stride
);

} // namespace preview
} // namespace openkamera

#endif // OPENKAMERA_FOCUS_PEAKING_H
