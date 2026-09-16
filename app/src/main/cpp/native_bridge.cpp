#include <jni.h>
#include "utils/bitmap_utils.h"
#include "utils/neon_math.h"
#include "preview/histogram_compute.h"
#include "preview/focus_peaking.h"
#include "preview/zebra_stripes.h"
#include "mtb/create_mtb.h"
#include "mtb/align_mtb.h"
#include "mtb/process_avg.h"
#include "hdr/process_hdr.h"
#include "hdr/histogram_adjust.h"
#include "panorama/feature_detector.h"
#include "panorama/pyramid_blending.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_isNativeSupported(
    JNIEnv* env,
    jobject /* thiz */
) {
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_hasNeon(
    JNIEnv* env,
    jobject /* thiz */
) {
#if HAS_NEON
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jintArray JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeComputeHistogram(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jint mode
) {
    openkamera::LockedBitmap locked(env, bitmap);
    if (!locked.isValid()) {
        return nullptr;
    }

    jintArray result = env->NewIntArray(256);
    if (!result) return nullptr;

    jint* array_elements = env->GetIntArrayElements(result, nullptr);
    if (!array_elements) return nullptr;

    openkamera::preview::HistogramMode hist_mode = static_cast<openkamera::preview::HistogramMode>(mode);
    openkamera::preview::compute_histogram_single(
        locked.data(),
        locked.width(),
        locked.height(),
        locked.stride(),
        hist_mode,
        reinterpret_cast<int32_t*>(array_elements)
    );

    env->ReleaseIntArrayElements(result, array_elements, 0);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeComputeHistogramRgb(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap
) {
    openkamera::LockedBitmap locked(env, bitmap);
    if (!locked.isValid()) {
        return nullptr;
    }

    jintArray result = env->NewIntArray(256 * 3);
    if (!result) return nullptr;

    jint* array_elements = env->GetIntArrayElements(result, nullptr);
    if (!array_elements) return nullptr;

    int32_t* r_ptr = reinterpret_cast<int32_t*>(array_elements);
    int32_t* g_ptr = r_ptr + 256;
    int32_t* b_ptr = g_ptr + 256;

    openkamera::preview::compute_histogram_rgb(
        locked.data(),
        locked.width(),
        locked.height(),
        locked.stride(),
        r_ptr,
        g_ptr,
        b_ptr
    );

    env->ReleaseIntArrayElements(result, array_elements, 0);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeComputeFocusPeaking(
    JNIEnv* env,
    jobject /* thiz */,
    jobject src_bitmap,
    jobject temp_bitmap,
    jobject out_bitmap
) {
    openkamera::LockedBitmap locked_src(env, src_bitmap);
    openkamera::LockedBitmap locked_out(env, out_bitmap);

    if (!locked_src.isValid() || !locked_out.isValid()) {
        return JNI_FALSE;
    }

    uint8_t* temp_ptr = nullptr;
    openkamera::LockedBitmap locked_temp(env, temp_bitmap);
    if (temp_bitmap && locked_temp.isValid()) {
        temp_ptr = locked_temp.data();
    }

    openkamera::preview::compute_focus_peaking(
        locked_src.data(),
        temp_ptr,
        locked_out.data(),
        locked_src.width(),
        locked_src.height(),
        locked_src.stride()
    );

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeComputeZebraStripes(
    JNIEnv* env,
    jobject /* thiz */,
    jobject src_bitmap,
    jobject out_bitmap,
    jint threshold,
    jint color_fg,
    jint color_bg,
    jint stripe_width
) {
    openkamera::LockedBitmap locked_src(env, src_bitmap);
    openkamera::LockedBitmap locked_out(env, out_bitmap);

    if (!locked_src.isValid() || !locked_out.isValid()) {
        return JNI_FALSE;
    }

    uint8_t fg_r = (color_fg >> 16) & 0xFF;
    uint8_t fg_g = (color_fg >> 8) & 0xFF;
    uint8_t fg_b = color_fg & 0xFF;
    uint8_t fg_a = (color_fg >> 24) & 0xFF;

    uint8_t bg_r = (color_bg >> 16) & 0xFF;
    uint8_t bg_g = (color_bg >> 8) & 0xFF;
    uint8_t bg_b = color_bg & 0xFF;
    uint8_t bg_a = (color_bg >> 24) & 0xFF;

    openkamera::preview::compute_zebra_stripes(
        locked_src.data(),
        locked_out.data(),
        locked_src.width(),
        locked_src.height(),
        locked_src.stride(),
        threshold,
        fg_r, fg_g, fg_b, fg_a,
        bg_r, bg_g, bg_b, bg_a,
        stripe_width
    );

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeComputeMedianValue(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jint start_x,
    jint start_y,
    jint crop_w,
    jint crop_h
) {
    openkamera::LockedBitmap locked(env, bitmap);
    if (!locked.isValid()) return 128;

    return openkamera::mtb::compute_median_value(
        locked.data(),
        locked.width(),
        locked.height(),
        locked.stride(),
        start_x,
        start_y,
        crop_w,
        crop_h
    );
}

JNIEXPORT jbyteArray JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeCreateMtb(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jint median_val,
    jint start_x,
    jint start_y,
    jint crop_w,
    jint crop_h
) {
    openkamera::LockedBitmap locked(env, bitmap);
    if (!locked.isValid() || crop_w <= 0 || crop_h <= 0) return nullptr;

    jbyteArray result = env->NewByteArray(crop_w * crop_h);
    if (!result) return nullptr;

    jbyte* bytes = env->GetByteArrayElements(result, nullptr);
    if (!bytes) return nullptr;

    openkamera::mtb::create_mtb(
        locked.data(),
        reinterpret_cast<uint8_t*>(bytes),
        locked.width(),
        locked.height(),
        locked.stride(),
        median_val,
        start_x,
        start_y,
        crop_w,
        crop_h
    );

    env->ReleaseByteArrayElements(result, bytes, 0);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeComputeMtbErrors(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray mtb0_bytes,
    jbyteArray mtb1_bytes,
    jint width,
    jint height,
    jint off_x,
    jint off_y,
    jint step_size
) {
    if (!mtb0_bytes || !mtb1_bytes || width <= 0 || height <= 0) return nullptr;

    jbyte* mtb0 = env->GetByteArrayElements(mtb0_bytes, nullptr);
    jbyte* mtb1 = env->GetByteArrayElements(mtb1_bytes, nullptr);

    if (!mtb0 || !mtb1) {
        if (mtb0) env->ReleaseByteArrayElements(mtb0_bytes, mtb0, JNI_ABORT);
        if (mtb1) env->ReleaseByteArrayElements(mtb1_bytes, mtb1, JNI_ABORT);
        return nullptr;
    }

    jintArray result = env->NewIntArray(9);
    if (!result) {
        env->ReleaseByteArrayElements(mtb0_bytes, mtb0, JNI_ABORT);
        env->ReleaseByteArrayElements(mtb1_bytes, mtb1, JNI_ABORT);
        return nullptr;
    }

    jint* out_errors = env->GetIntArrayElements(result, nullptr);
    if (!out_errors) {
        env->ReleaseByteArrayElements(mtb0_bytes, mtb0, JNI_ABORT);
        env->ReleaseByteArrayElements(mtb1_bytes, mtb1, JNI_ABORT);
        return nullptr;
    }

    openkamera::mtb::compute_mtb_errors(
        reinterpret_cast<const uint8_t*>(mtb0),
        reinterpret_cast<const uint8_t*>(mtb1),
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
        off_x,
        off_y,
        step_size,
        reinterpret_cast<int32_t*>(out_errors)
    );

    env->ReleaseIntArrayElements(result, out_errors, 0);
    env->ReleaseByteArrayElements(mtb0_bytes, mtb0, JNI_ABORT);
    env->ReleaseByteArrayElements(mtb1_bytes, mtb1, JNI_ABORT);

    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeAccumulateFrameAvg(
    JNIEnv* env,
    jobject /* thiz */,
    jobject base_bitmap,
    jobject new_bitmap,
    jint offset_x,
    jint offset_y,
    jfloat avg_factor,
    jfloat wiener_c
) {
    openkamera::LockedBitmap locked_base(env, base_bitmap);
    openkamera::LockedBitmap locked_new(env, new_bitmap);

    if (!locked_base.isValid() || !locked_new.isValid()) return JNI_FALSE;

    openkamera::mtb::accumulate_frame_avg(
        locked_base.data(),
        locked_new.data(),
        locked_base.width(),
        locked_base.height(),
        locked_base.stride(),
        offset_x,
        offset_y,
        avg_factor,
        wiener_c
    );

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeApplyBrighten(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jfloat gain,
    jfloat gamma,
    jfloat low_x,
    jfloat mid_x,
    jfloat max_x
) {
    openkamera::LockedBitmap locked(env, bitmap);
    if (!locked.isValid()) return JNI_FALSE;

    openkamera::mtb::apply_brighten(
        locked.data(),
        locked.width(),
        locked.height(),
        locked.stride(),
        gain,
        gamma,
        low_x,
        mid_x,
        max_x
    );

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeProcessHdrFusion(
    JNIEnv* env,
    jobject /* thiz */,
    jobjectArray frame_bitmaps,
    jintArray offsets_x,
    jintArray offsets_y,
    jfloatArray params_a,
    jfloatArray params_b,
    jobject out_bitmap,
    jint tonemap_algorithm,
    jfloat tonemap_scale,
    jfloat linear_scale
) {
    if (!frame_bitmaps || !out_bitmap) return JNI_FALSE;

    jsize num_frames = env->GetArrayLength(frame_bitmaps);
    if (num_frames <= 0) return JNI_FALSE;

    openkamera::LockedBitmap locked_out(env, out_bitmap);
    if (!locked_out.isValid()) return JNI_FALSE;

    jint* off_x = offsets_x ? env->GetIntArrayElements(offsets_x, nullptr) : nullptr;
    jint* off_y = offsets_y ? env->GetIntArrayElements(offsets_y, nullptr) : nullptr;
    jfloat* p_a = params_a ? env->GetFloatArrayElements(params_a, nullptr) : nullptr;
    jfloat* p_b = params_b ? env->GetFloatArrayElements(params_b, nullptr) : nullptr;

    std::vector<openkamera::LockedBitmap> locked_frames;
    locked_frames.reserve(num_frames);

    std::vector<openkamera::hdr::FrameInfo> frame_infos;
    frame_infos.reserve(num_frames);

    for (jsize i = 0; i < num_frames; ++i) {
        jobject bmp = env->GetObjectArrayElement(frame_bitmaps, i);
        locked_frames.emplace_back(env, bmp);
        if (locked_frames.back().isValid()) {
            openkamera::hdr::FrameInfo info;
            info.pixels = locked_frames.back().data();
            info.offset_x = off_x ? off_x[i] : 0;
            info.offset_y = off_y ? off_y[i] : 0;
            info.param_a = p_a ? p_a[i] : 1.0f;
            info.param_b = p_b ? p_b[i] : 0.0f;
            frame_infos.push_back(info);
        }
        env->DeleteLocalRef(bmp);
    }

    openkamera::hdr::TonemapAlgorithm algo = static_cast<openkamera::hdr::TonemapAlgorithm>(tonemap_algorithm);
    openkamera::hdr::process_hdr_fusion(
        frame_infos,
        locked_out.data(),
        locked_out.width(),
        locked_out.height(),
        locked_out.stride(),
        algo,
        tonemap_scale,
        linear_scale
    );

    if (off_x) env->ReleaseIntArrayElements(offsets_x, off_x, JNI_ABORT);
    if (off_y) env->ReleaseIntArrayElements(offsets_y, off_y, JNI_ABORT);
    if (p_a) env->ReleaseFloatArrayElements(params_a, p_a, JNI_ABORT);
    if (p_b) env->ReleaseFloatArrayElements(params_b, p_b, JNI_ABORT);

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeApplyHistogramEqualization(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap
) {
    openkamera::LockedBitmap locked(env, bitmap);
    if (!locked.isValid()) return JNI_FALSE;

    openkamera::hdr::apply_histogram_equalization(
        locked.data(),
        locked.width(),
        locked.height(),
        locked.stride()
    );

    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeDetectHarrisFeatures(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jfloat corner_threshold
) {
    openkamera::LockedBitmap locked(env, bitmap);
    if (!locked.isValid()) return nullptr;

    auto keypoints = openkamera::panorama::detect_harris_corners(
        locked.data(),
        locked.width(),
        locked.height(),
        locked.stride(),
        corner_threshold
    );

    jsize num_pts = static_cast<jsize>(keypoints.size());
    jintArray result = env->NewIntArray(num_pts * 2);
    if (!result) return nullptr;

    jint* elements = env->GetIntArrayElements(result, nullptr);
    if (!elements) return nullptr;

    for (jsize i = 0; i < num_pts; ++i) {
        elements[i * 2 + 0] = keypoints[i].x;
        elements[i * 2 + 1] = keypoints[i].y;
    }

    env->ReleaseIntArrayElements(result, elements, 0);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeBlendPyramidSeam(
    JNIEnv* env,
    jobject /* thiz */,
    jobject lhs_bitmap,
    jobject rhs_bitmap,
    jobject out_bitmap,
    jintArray best_path_mid_x,
    jint blend_width
) {
    openkamera::LockedBitmap locked_lhs(env, lhs_bitmap);
    openkamera::LockedBitmap locked_rhs(env, rhs_bitmap);
    openkamera::LockedBitmap locked_out(env, out_bitmap);

    if (!locked_lhs.isValid() || !locked_rhs.isValid() || !locked_out.isValid()) {
        return JNI_FALSE;
    }

    jint* mid_x = best_path_mid_x ? env->GetIntArrayElements(best_path_mid_x, nullptr) : nullptr;

    openkamera::panorama::blend_pyramid_seam(
        locked_lhs.data(),
        locked_rhs.data(),
        locked_out.data(),
        locked_lhs.width(),
        locked_lhs.height(),
        locked_lhs.stride(),
        mid_x,
        blend_width
    );

    if (mid_x) env->ReleaseIntArrayElements(best_path_mid_x, mid_x, JNI_ABORT);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_hightechif_openkamera_processing_NativeImageProcessorBridge_nativeComputeFrameOverlapError(
    JNIEnv* env,
    jobject /* thiz */,
    jobject frame0,
    jobject frame1
) {
    openkamera::LockedBitmap locked0(env, frame0);
    openkamera::LockedBitmap locked1(env, frame1);

    if (!locked0.isValid() || !locked1.isValid()) return 0;

    return static_cast<jlong>(openkamera::panorama::compute_frame_overlap_error(
        locked0.data(),
        locked1.data(),
        locked0.width(),
        locked0.height(),
        locked0.stride()
    ));
}

} // extern "C"
