#ifndef OPENKAMERA_CREATE_MTB_H
#define OPENKAMERA_CREATE_MTB_H

#include <cstdint>

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
);

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
);

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
);

} // namespace mtb
} // namespace openkamera

#endif // OPENKAMERA_CREATE_MTB_H
