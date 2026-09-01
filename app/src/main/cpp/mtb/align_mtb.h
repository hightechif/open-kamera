#ifndef OPENKAMERA_ALIGN_MTB_H
#define OPENKAMERA_ALIGN_MTB_H

#include <cstdint>

namespace openkamera {
namespace mtb {

void compute_mtb_errors(
    const uint8_t* mtb0,
    const uint8_t* mtb1,
    uint32_t width,
    uint32_t height,
    int off_x,
    int off_y,
    int step_size,
    int32_t out_errors[9]
);

void compute_greyscale_errors(
    const uint8_t* grey0,
    const uint8_t* grey1,
    uint32_t width,
    uint32_t height,
    int off_x,
    int off_y,
    int step_size,
    int32_t out_errors[9]
);

} // namespace mtb
} // namespace openkamera

#endif // OPENKAMERA_ALIGN_MTB_H
