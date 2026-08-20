package com.hightechif.openkamera.processing

/** Exception for HDRProcessor class.
 */
class HDRProcessorException internal constructor(val code: Int) : Exception() {
    companion object {
        const val INVALID_N_IMAGES: Int = 0 // the supplied number of images is not supported
        const val UNEQUAL_SIZES: Int = 1 // images not of the same resolution
    }
}
