#ifndef OPENKAMERA_HISTOGRAM_ADJUST_H
#define OPENKAMERA_HISTOGRAM_ADJUST_H

#include <cstdint>

namespace openkamera {
namespace hdr {

void apply_histogram_equalization(
    uint8_t* rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride
);

} // namespace hdr
} // namespace openkamera

#endif // OPENKAMERA_HISTOGRAM_ADJUST_H
