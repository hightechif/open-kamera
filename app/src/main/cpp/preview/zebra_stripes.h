#ifndef OPENKAMERA_ZEBRA_STRIPES_H
#define OPENKAMERA_ZEBRA_STRIPES_H

#include <cstdint>

namespace openkamera {
namespace preview {

void compute_zebra_stripes(
    const uint8_t* src_pixels,
    uint8_t* out_pixels,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    int threshold,
    uint8_t fg_r, uint8_t fg_g, uint8_t fg_b, uint8_t fg_a,
    uint8_t bg_r, uint8_t bg_g, uint8_t bg_b, uint8_t bg_a,
    int stripe_width
);

} // namespace preview
} // namespace openkamera

#endif // OPENKAMERA_ZEBRA_STRIPES_H
