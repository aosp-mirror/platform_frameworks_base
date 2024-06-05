package com.android.systemui.unfold

import android.os.SystemProperties
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.updates.FoldProvider
import java.util.concurrent.Executor
import javax.inject.Inject

/** Class that plays a haptics effect during unfolding a foldable device */
@SysUIUnfoldScope
class UnfoldHapticsPlayer
@Inject
constructor(
    unfoldTransitionProgressProvider: UnfoldTransitionProgressProvider,
    foldProvider: FoldProvider,
    transitionConfig: UnfoldTransitionConfig,
    @Main private val mainExecutor: Executor,
    private val vibrator: Vibrator?
) : TransitionProgressListener {

    private var isFirstAnimationAfterUnfold = false
    private val touchVibrationAttributes =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

    init {
        if (vibrator != null && transitionConfig.isHapticsEnabled) {
            // We don't need to remove the callback because we should listen to it
            // the whole time when SystemUI process is alive
            unfoldTransitionProgressProvider.addCallback(this)

            foldProvider.registerCallback({ isFolded ->
                if (isFolded) {
                    isFirstAnimationAfterUnfold = true
                }
            }, mainExecutor)
        }
    }

    private var lastTransitionProgress = TRANSITION_PROGRESS_FULL_OPEN

    override fun onTransitionStarted() {
        lastTransitionProgress = TRANSITION_PROGRESS_CLOSED
    }

    override fun onTransitionProgress(progress: Float) {
        lastTransitionProgress = progress
    }

    override fun onTransitionFinishing() {
        // Run haptics only when unfolding the device (first animation after unfolding)
        if (!isFirstAnimationAfterUnfold) {
            return
        }

        isFirstAnimationAfterUnfold = false

        // Run haptics only if the animation is long enough to notice
        if (lastTransitionProgress < TRANSITION_NOTICEABLE_THRESHOLD) {
            playHaptics()
        }
    }

    override fun onTransitionFinished() {
        lastTransitionProgress = TRANSITION_PROGRESS_FULL_OPEN
    }

    private fun playHaptics() {
        vibrator?.vibrate(effect, touchVibrationAttributes)
    }

    private val hapticsScale: Float
        get() {
            val intensityString = SystemProperties.get("persist.unfold.haptics_scale", "0.5")
            return intensityString.toFloatOrNull() ?: 0.5f
        }

    private val hapticsScaleTick: Float
        get() {
            val intensityString =
                SystemProperties.get("persist.unfold.haptics_scale_end_tick", "1.0")
            return intensityString.toFloatOrNull() ?: 1.0f
        }

    private val primitivesCount: Int
        get() {
            val count = SystemProperties.get("persist.unfold.primitives_count", "18")
            return count.toIntOrNull() ?: 18
        }

    private val effect: VibrationEffect by lazy {
        val composition =
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0F, 0)

        repeat(primitivesCount) {
            composition.addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                hapticsScale,
                0
            )
        }

        composition
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, hapticsScaleTick)
            .compose()
    }
}

private const val TRANSITION_PROGRESS_CLOSED = 0f
private const val TRANSITION_PROGRESS_FULL_OPEN = 1f
private const val TRANSITION_NOTICEABLE_THRESHOLD = 0.9f
