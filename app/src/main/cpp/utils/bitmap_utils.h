#ifndef OPENKAMERA_BITMAP_UTILS_H
#define OPENKAMERA_BITMAP_UTILS_H

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstdint>

#define LOG_TAG "OpenKameraNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace openkamera {

class LockedBitmap {
public:
    LockedBitmap(JNIEnv* env, jobject bitmap)
        : env_(env), bitmap_(bitmap), pixels_(nullptr), locked_(false) {
        if (!bitmap) {
            LOGE("LockedBitmap: null bitmap passed");
            return;
        }

        if (AndroidBitmap_getInfo(env_, bitmap_, &info_) < 0) {
            LOGE("LockedBitmap: AndroidBitmap_getInfo failed");
            return;
        }

        if (AndroidBitmap_lockPixels(env_, bitmap_, &pixels_) < 0) {
            LOGE("LockedBitmap: AndroidBitmap_lockPixels failed");
            pixels_ = nullptr;
            return;
        }

        locked_ = true;
    }

    ~LockedBitmap() {
        unlock();
    }

    // Disable copy
    LockedBitmap(const LockedBitmap&) = delete;
    LockedBitmap& operator=(const LockedBitmap&) = delete;

    // Move semantics
    LockedBitmap(LockedBitmap&& other) noexcept
        : env_(other.env_),
          bitmap_(other.bitmap_),
          info_(other.info_),
          pixels_(other.pixels_),
          locked_(other.locked_) {
        other.locked_ = false;
        other.pixels_ = nullptr;
    }

    void unlock() {
        if (locked_ && bitmap_ && env_) {
            AndroidBitmap_unlockPixels(env_, bitmap_);
            locked_ = false;
            pixels_ = nullptr;
        }
    }

    bool isValid() const {
        return locked_ && pixels_ != nullptr;
    }

    uint32_t width() const { return info_.width; }
    uint32_t height() const { return info_.height; }
    uint32_t stride() const { return info_.stride; }
    int32_t format() const { return info_.format; }

    uint8_t* data() { return static_cast<uint8_t*>(pixels_); }
    const uint8_t* data() const { return static_cast<const uint8_t*>(pixels_); }

    uint32_t* data32() { return static_cast<uint32_t*>(pixels_); }
    const uint32_t* data32() const { return static_cast<const uint32_t*>(pixels_); }

private:
    JNIEnv* env_;
    jobject bitmap_;
    AndroidBitmapInfo info_{};
    void* pixels_;
    bool locked_;
};

} // namespace openkamera

#endif // OPENKAMERA_BITMAP_UTILS_H
