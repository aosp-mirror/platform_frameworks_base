package com.android.systemui.statusbar.notification

import android.util.MathUtils
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Interpolators
import com.android.systemui.plugins.animation.ActivityLaunchAnimator
import kotlin.math.min

/** Parameters for the notifications expand animations. */
class ExpandAnimationParameters(
    top: Int,
    bottom: Int,
    left: Int,
    right: Int,

    topCornerRadius: Float = 0f,
    bottomCornerRadius: Float = 0f
) : ActivityLaunchAnimator.State(top, bottom, left, right, topCornerRadius, bottomCornerRadius) {
    @VisibleForTesting
    constructor() : this(
        top = 0, bottom = 0, left = 0, right = 0, topCornerRadius = 0f, bottomCornerRadius = 0f
    )

    var startTranslationZ = 0f
    var startClipTopAmount = 0
    var parentStartClipTopAmount = 0
    var progress = 0f
    var linearProgress = 0f

    override val topChange: Int
        get() {
            // We need this compensation to ensure that the QS moves in sync.
            var clipTopAmountCompensation = 0
            if (startClipTopAmount.toFloat() != 0.0f) {
                clipTopAmountCompensation = MathUtils.lerp(0f, startClipTopAmount.toFloat(),
                        Interpolators.FAST_OUT_SLOW_IN.getInterpolation(linearProgress)).toInt()
            }
            return min(super.topChange - clipTopAmountCompensation, 0)
        }

    fun getProgress(delay: Long, duration: Long): Float {
        return ActivityLaunchAnimator.getProgress(linearProgress, delay, duration)
    }
}