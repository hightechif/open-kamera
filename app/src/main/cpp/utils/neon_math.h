#ifndef OPENKAMERA_NEON_MATH_H
#define OPENKAMERA_NEON_MATH_H

#include <cstdint>
#include <algorithm>
#include <cmath>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define HAS_NEON 1
#else
#define HAS_NEON 0
#endif

namespace openkamera {
namespace math {

inline uint8_t clamp_u8(int value) {
    return static_cast<uint8_t>(std::clamp(value, 0, 255));
}

inline uint8_t clamp_f_u8(float value) {
    return static_cast<uint8_t>(std::clamp(static_cast<int>(std::round(value)), 0, 255));
}

inline float clamp_f(float value, float low, float high) {
    return std::clamp(value, low, high);
}

// Fast luminance from RGBA (0.299 R + 0.587 G + 0.114 B)
inline uint8_t rgb_to_luminance(uint8_t r, uint8_t g, uint8_t b) {
    // Fixed point approx: (19595*R + 38469*G + 7472*B + 32768) >> 16
    uint32_t luma = 19595U * r + 38469U * g + 7472U * b + 32768U;
    return static_cast<uint8_t>(luma >> 16);
}

// Fast value from RGBA: max(R, G, B)
inline uint8_t rgb_to_value(uint8_t r, uint8_t g, uint8_t b) {
    return std::max({r, g, b});
}

// Fast intensity: (R + G + B) / 3
inline uint8_t rgb_to_intensity(uint8_t r, uint8_t g, uint8_t b) {
    return static_cast<uint8_t>((static_cast<uint32_t>(r) + g + b) / 3);
}

// Fast lightness: (max(R,G,B) + min(R,G,B)) / 2
inline uint8_t rgb_to_lightness(uint8_t r, uint8_t g, uint8_t b) {
    uint8_t max_v = std::max({r, g, b});
    uint8_t min_v = std::min({r, g, b});
    return static_cast<uint8_t>((static_cast<uint32_t>(max_v) + min_v) >> 1);
}

// Vectorized popcount for MTB alignment errors
inline int popcount_u64(uint64_t v) {
#if defined(__GNUC__) || defined(__clang__)
    return __builtin_popcountll(v);
#else
    v = v - ((v >> 1) & 0x5555555555555555ULL);
    v = (v & 0x3333333333333333ULL) + ((v >> 2) & 0x3333333333333333ULL);
    return (int)((((v + (v >> 4)) & 0xF0F0F0F0F0F0F0FULL) * 0x101010101010101ULL) >> 56);
#endif
}

} // namespace math
} // namespace openkamera

#endif // OPENKAMERA_NEON_MATH_H
