package com.android.systemui.animation

object GSFAxes {
    const val WEIGHT = "wght"
    const val WIDTH = "wdth"
    const val SLANT = "slnt"
    const val ROUND = "ROND"
    const val OPTICAL_SIZE = "opsz"
}

class FontVariationUtils {
    private var mWeight = -1
    private var mWidth = -1
    private var mOpticalSize = -1
    private var mRoundness = -1
    private var isUpdated = false

    /*
     * generate fontVariationSettings string, used for key in typefaceCache in TextAnimator
     * the order of axes should align to the order of parameters
     * if every axis remains unchanged, return ""
     */
    fun updateFontVariation(
        weight: Int = -1,
        width: Int = -1,
        opticalSize: Int = -1,
        roundness: Int = -1,
    ): String {
        isUpdated = false
        if (weight >= 0 && mWeight != weight) {
            isUpdated = true
            mWeight = weight
        }
        if (width >= 0 && mWidth != width) {
            isUpdated = true
            mWidth = width
        }
        if (opticalSize >= 0 && mOpticalSize != opticalSize) {
            isUpdated = true
            mOpticalSize = opticalSize
        }

        if (roundness >= 0 && mRoundness != roundness) {
            isUpdated = true
            mRoundness = roundness
        }
        var resultString = ""
        if (mWeight >= 0) {
            resultString += "'${GSFAxes.WEIGHT}' $mWeight"
        }
        if (mWidth >= 0) {
            resultString +=
                (if (resultString.isBlank()) "" else ", ") + "'${GSFAxes.WIDTH}' $mWidth"
        }
        if (mOpticalSize >= 0) {
            resultString +=
                (if (resultString.isBlank()) "" else ", ") +
                    "'${GSFAxes.OPTICAL_SIZE}' $mOpticalSize"
        }
        if (mRoundness >= 0) {
            resultString +=
                (if (resultString.isBlank()) "" else ", ") + "'${GSFAxes.ROUND}' $mRoundness"
        }
        return if (isUpdated) resultString else ""
    }
}
