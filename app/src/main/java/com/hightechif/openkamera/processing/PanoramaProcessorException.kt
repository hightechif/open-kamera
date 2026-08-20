package com.hightechif.openkamera.processing

/** Exception for PanoramaProcessor class.
 */
class PanoramaProcessorException internal constructor(val code: Int) : Exception() {
    companion object {
        const val INVALID_N_IMAGES: Int = 0 // the supplied number of images is not supported
        const val UNEQUAL_SIZES: Int = 1 // images not of the same resolution
        const val FAILED_TO_CROP: Int = 2 // failed to crop
    }
}
