#include "feature_detector.h"
#include "../utils/neon_math.h"
#include <vector>
#include <cmath>
#include <algorithm>

namespace openkamera {
namespace panorama {

std::vector<Point2D> detect_harris_corners(
    const uint8_t* rgba,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    float corner_threshold
) {
    std::vector<Point2D> keypoints;
    if (!rgba || width < 7 || height < 7) return keypoints;

    // 1. Grayscale
    std::vector<uint8_t> gray(width * height);
    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* row = rgba + y * stride;
        for (uint32_t x = 0; x < width; ++x) {
            uint8_t r = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t b = row[x * 4 + 2];
            gray[y * width + x] = math::rgb_to_luminance(r, g, b);
        }
    }

    // 2. Derivatives Ix, Iy (Sobel / central difference)
    std::vector<int16_t> ix(width * height, 0);
    std::vector<int16_t> iy(width * height, 0);

    for (uint32_t y = 1; y < height - 1; ++y) {
        for (uint32_t x = 1; x < width - 1; ++x) {
            int p_l = gray[y * width + (x - 1)];
            int p_r = gray[y * width + (x + 1)];
            int p_u = gray[(y - 1) * width + x];
            int p_d = gray[(y + 1) * width + x];

            ix[y * width + x] = static_cast<int16_t>((p_r - p_l) / 2);
            iy[y * width + x] = static_cast<int16_t>((p_d - p_u) / 2);
        }
    }

    // 3. Harris corner response map with 5x5 window (weights: 1, 4, 6, 4, 1)
    const int radius = 2;
    const float weights[5] = {1.0f, 4.0f, 6.0f, 4.0f, 1.0f};

    std::vector<float> corner_response(width * height, 0.0f);

    for (uint32_t y = radius + 1; y < height - radius - 1; ++y) {
        for (uint32_t x = radius + 1; x < width - radius - 1; ++x) {
            float h00 = 0.0f;
            float h01 = 0.0f;
            float h11 = 0.0f;

            for (int dy = -radius; dy <= radius; ++dy) {
                float wy = weights[dy + radius];
                uint32_t cy = y + dy;
                for (int dx = -radius; dx <= radius; ++dx) {
                    float wx = weights[dx + radius];
                    uint32_t cx = x + dx;
                    float weight = wx * wy;

                    float dx_val = ix[cy * width + cx];
                    float dy_val = iy[cy * width + cx];

                    h00 += weight * dx_val * dx_val;
                    h01 += weight * dx_val * dy_val;
                    h11 += weight * dy_val * dy_val;
                }
            }

            float det = h00 * h11 - h01 * h01;
            float trace = h00 + h11;
            float response = det - 0.06f * trace * trace;

            if (response > corner_threshold) {
                corner_response[y * width + x] = response;
            }
        }
    }

    // 4. Non-Maximum Suppression (5x5 local maximum)
    for (uint32_t y = 2; y < height - 2; ++y) {
        for (uint32_t x = 2; x < width - 2; ++x) {
            float val = corner_response[y * width + x];
            if (val <= 0.0f) continue;

            bool is_max = true;
            for (int dy = -2; dy <= 2 && is_max; ++dy) {
                for (int dx = -2; dx <= 2; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    if (corner_response[(y + dy) * width + (x + dx)] >= val) {
                        is_max = false;
                        break;
                    }
                }
            }

            if (is_max) {
                keypoints.push_back({static_cast<int>(x), static_cast<int>(y)});
            }
        }
    }

    return keypoints;
}

} // namespace panorama
} // namespace openkamera
