#include "align_mtb.h"
#include <cstring>
#include <cmath>

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
) {
    if (!mtb0 || !mtb1 || !out_errors || step_size <= 0) return;

    for (int i = 0; i < 9; ++i) {
        out_errors[i] = 0;
    }

    int min_limit_x = step_size;
    int max_limit_x = static_cast<int>(width) - step_size;
    int min_limit_y = step_size;
    int max_limit_y = static_cast<int>(height) - step_size;

    for (uint32_t y = 0; y < height; y += step_size) {
        const uint8_t* row0 = mtb0 + y * width;

        for (uint32_t x = 0; x < width; x += step_size) {
            int tx = static_cast<int>(x) + off_x;
            int ty = static_cast<int>(y) + off_y;

            if (tx >= min_limit_x && tx < max_limit_x && ty >= min_limit_y && ty < max_limit_y) {
                uint8_t pixel0 = row0[x];

                int c = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    const uint8_t* row1 = mtb1 + (ty + dy * step_size) * width;
                    for (int dx = -1; dx <= 1; ++dx) {
                        uint8_t pixel1 = row1[tx + dx * step_size];
                        if (pixel0 != pixel1) {
                            if (pixel0 != 127 && pixel1 != 127) { // ignore noise mask
                                out_errors[c]++;
                            }
                        }
                        c++;
                    }
                }
            }
        }
    }
}

void compute_greyscale_errors(
    const uint8_t* grey0,
    const uint8_t* grey1,
    uint32_t width,
    uint32_t height,
    int off_x,
    int off_y,
    int step_size,
    int32_t out_errors[9]
) {
    if (!grey0 || !grey1 || !out_errors || step_size <= 0) return;

    for (int i = 0; i < 9; ++i) {
        out_errors[i] = 0;
    }

    int min_limit_x = step_size;
    int max_limit_x = static_cast<int>(width) - step_size;
    int min_limit_y = step_size;
    int max_limit_y = static_cast<int>(height) - step_size;

    for (uint32_t y = 0; y < height; y += step_size) {
        const uint8_t* row0 = grey0 + y * width;

        for (uint32_t x = 0; x < width; x += step_size) {
            int tx = static_cast<int>(x) + off_x;
            int ty = static_cast<int>(y) + off_y;

            if (tx >= min_limit_x && tx < max_limit_x && ty >= min_limit_y && ty < max_limit_y) {
                float pixel0 = static_cast<float>(row0[x]);

                int c = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    const uint8_t* row1 = grey1 + (ty + dy * step_size) * width;
                    for (int dx = -1; dx <= 1; ++dx) {
                        float pixel1 = static_cast<float>(row1[tx + dx * step_size]);
                        float diff = pixel1 - pixel0;
                        int32_t diff2 = static_cast<int32_t>(diff * diff);
                        if (out_errors[c] < 2000000000) {
                            out_errors[c] += diff2;
                        }
                        c++;
                    }
                }
            }
        }
    }
}

} // namespace mtb
} // namespace openkamera
