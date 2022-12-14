/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.dreams

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.Interpolator
import androidx.core.animation.doOnEnd
import com.android.systemui.animation.Interpolators
import com.android.systemui.dreams.complication.ComplicationHostViewController
import com.android.systemui.dreams.complication.ComplicationLayoutParams
import com.android.systemui.dreams.complication.ComplicationLayoutParams.POSITION_BOTTOM
import com.android.systemui.dreams.complication.ComplicationLayoutParams.POSITION_TOP
import com.android.systemui.dreams.complication.ComplicationLayoutParams.Position
import com.android.systemui.dreams.dagger.DreamOverlayModule
import com.android.systemui.statusbar.BlurUtils
import com.android.systemui.statusbar.CrossFadeHelper
import javax.inject.Inject
import javax.inject.Named

/** Controller for dream overlay animations. */
class DreamOverlayAnimationsController
@Inject
constructor(
    private val mBlurUtils: BlurUtils,
    private val mComplicationHostViewController: ComplicationHostViewController,
    private val mStatusBarViewController: DreamOverlayStatusBarViewController,
    private val mOverlayStateController: DreamOverlayStateController,
    @Named(DreamOverlayModule.DREAM_BLUR_RADIUS) private val mDreamBlurRadius: Int,
    @Named(DreamOverlayModule.DREAM_IN_BLUR_ANIMATION_DURATION)
    private val mDreamInBlurAnimDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_COMPLICATIONS_ANIMATION_DURATION)
    private val mDreamInComplicationsAnimDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_TRANSLATION_Y_DISTANCE)
    private val mDreamInTranslationYDistance: Int,
    @Named(DreamOverlayModule.DREAM_IN_TRANSLATION_Y_DURATION)
    private val mDreamInTranslationYDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_OUT_TRANSLATION_Y_DISTANCE)
    private val mDreamOutTranslationYDistance: Int,
    @Named(DreamOverlayModule.DREAM_OUT_TRANSLATION_Y_DURATION)
    private val mDreamOutTranslationYDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_OUT_TRANSLATION_Y_DELAY_BOTTOM)
    private val mDreamOutTranslationYDelayBottomMs: Long,
    @Named(DreamOverlayModule.DREAM_OUT_TRANSLATION_Y_DELAY_TOP)
    private val mDreamOutTranslationYDelayTopMs: Long,
    @Named(DreamOverlayModule.DREAM_OUT_ALPHA_DURATION) private val mDreamOutAlphaDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_OUT_ALPHA_DELAY_BOTTOM)
    private val mDreamOutAlphaDelayBottomMs: Long,
    @Named(DreamOverlayModule.DREAM_OUT_ALPHA_DELAY_TOP) private val mDreamOutAlphaDelayTopMs: Long,
    @Named(DreamOverlayModule.DREAM_OUT_BLUR_DURATION) private val mDreamOutBlurDurationMs: Long
) {

    private var mAnimator: Animator? = null

    /**
     * Store the current alphas at the various positions. This is so that we may resume an animation
     * at the current alpha.
     */
    private var mCurrentAlphaAtPosition = mutableMapOf<Int, Float>()

    private var mCurrentBlurRadius: Float = 0f

    /** Starts the dream content and dream overlay entry animations. */
    @JvmOverloads
    fun startEntryAnimations(view: View, animatorBuilder: () -> AnimatorSet = { AnimatorSet() }) {
        cancelAnimations()

        mAnimator =
            animatorBuilder().apply {
                playTogether(
                    blurAnimator(
                        view = view,
                        fromBlurRadius = mDreamBlurRadius.toFloat(),
                        toBlurRadius = 0f,
                        durationMs = mDreamInBlurAnimDurationMs,
                        interpolator = Interpolators.EMPHASIZED_DECELERATE
                    ),
                    alphaAnimator(
                        from = 0f,
                        to = 1f,
                        durationMs = mDreamInComplicationsAnimDurationMs,
                        interpolator = Interpolators.LINEAR
                    ),
                    translationYAnimator(
                        from = mDreamInTranslationYDistance.toFloat(),
                        to = 0f,
                        durationMs = mDreamInTranslationYDurationMs,
                        interpolator = Interpolators.EMPHASIZED_DECELERATE
                    ),
                )
                doOnEnd {
                    mAnimator = null
                    mOverlayStateController.setEntryAnimationsFinished(true)
                }
                start()
            }
    }

    /** Starts the dream content and dream overlay exit animations. */
    @JvmOverloads
    fun startExitAnimations(
        view: View,
        doneCallback: () -> Unit,
        animatorBuilder: () -> AnimatorSet = { AnimatorSet() }
    ) {
        cancelAnimations()

        mAnimator =
            animatorBuilder().apply {
                playTogether(
                    blurAnimator(
                        view = view,
                        // Start the blurring wherever the entry animation ended, in
                        // case it was cancelled early.
                        fromBlurRadius = mCurrentBlurRadius,
                        toBlurRadius = mDreamBlurRadius.toFloat(),
                        durationMs = mDreamOutBlurDurationMs,
                        interpolator = Interpolators.EMPHASIZED_ACCELERATE
                    ),
                    translationYAnimator(
                        from = 0f,
                        to = mDreamOutTranslationYDistance.toFloat(),
                        durationMs = mDreamOutTranslationYDurationMs,
                        delayMs = mDreamOutTranslationYDelayBottomMs,
                        positions = POSITION_BOTTOM,
                        interpolator = Interpolators.EMPHASIZED_ACCELERATE
                    ),
                    translationYAnimator(
                        from = 0f,
                        to = mDreamOutTranslationYDistance.toFloat(),
                        durationMs = mDreamOutTranslationYDurationMs,
                        delayMs = mDreamOutTranslationYDelayTopMs,
                        positions = POSITION_TOP,
                        interpolator = Interpolators.EMPHASIZED_ACCELERATE
                    ),
                    alphaAnimator(
                        from =
                            mCurrentAlphaAtPosition.getOrDefault(
                                key = POSITION_BOTTOM,
                                defaultValue = 1f
                            ),
                        to = 0f,
                        durationMs = mDreamOutAlphaDurationMs,
                        delayMs = mDreamOutAlphaDelayBottomMs,
                        positions = POSITION_BOTTOM
                    ),
                    alphaAnimator(
                        from =
                            mCurrentAlphaAtPosition.getOrDefault(
                                key = POSITION_TOP,
                                defaultValue = 1f
                            ),
                        to = 0f,
                        durationMs = mDreamOutAlphaDurationMs,
                        delayMs = mDreamOutAlphaDelayTopMs,
                        positions = POSITION_TOP
                    )
                )
                doOnEnd {
                    mAnimator = null
                    mOverlayStateController.setExitAnimationsRunning(false)
                    doneCallback()
                }
                start()
            }
        mOverlayStateController.setExitAnimationsRunning(true)
    }

    /** Cancels the dream content and dream overlay animations, if they're currently running. */
    fun cancelAnimations() {
        mAnimator =
            mAnimator?.let {
                it.cancel()
                null
            }
    }

    private fun blurAnimator(
        view: View,
        fromBlurRadius: Float,
        toBlurRadius: Float,
        durationMs: Long,
        delayMs: Long = 0,
        interpolator: Interpolator = Interpolators.LINEAR
    ): Animator {
        return ValueAnimator.ofFloat(fromBlurRadius, toBlurRadius).apply {
            duration = durationMs
            startDelay = delayMs
            this.interpolator = interpolator
            addUpdateListener { animator: ValueAnimator ->
                mCurrentBlurRadius = animator.animatedValue as Float
                mBlurUtils.applyBlur(
                    viewRootImpl = view.viewRootImpl,
                    radius = mCurrentBlurRadius.toInt(),
                    opaque = false
                )
            }
        }
    }

    private fun alphaAnimator(
        from: Float,
        to: Float,
        durationMs: Long,
        delayMs: Long = 0,
        @Position positions: Int = POSITION_TOP or POSITION_BOTTOM,
        interpolator: Interpolator = Interpolators.LINEAR
    ): Animator {
        return ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            startDelay = delayMs
            this.interpolator = interpolator
            addUpdateListener { va: ValueAnimator ->
                ComplicationLayoutParams.iteratePositions(
                    { position: Int ->
                        setElementsAlphaAtPosition(
                            alpha = va.animatedValue as Float,
                            position = position,
                            fadingOut = to < from
                        )
                    },
                    positions
                )
            }
        }
    }

    private fun translationYAnimator(
        from: Float,
        to: Float,
        durationMs: Long,
        delayMs: Long = 0,
        @Position positions: Int = POSITION_TOP or POSITION_BOTTOM,
        interpolator: Interpolator = Interpolators.LINEAR
    ): Animator {
        return ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            startDelay = delayMs
            this.interpolator = interpolator
            addUpdateListener { va: ValueAnimator ->
                ComplicationLayoutParams.iteratePositions(
                    { position: Int ->
                        setElementsTranslationYAtPosition(va.animatedValue as Float, position)
                    },
                    positions
                )
            }
        }
    }

    /** Sets alpha of complications at the specified position. */
    private fun setElementsAlphaAtPosition(alpha: Float, position: Int, fadingOut: Boolean) {
        mCurrentAlphaAtPosition[position] = alpha
        mComplicationHostViewController.getViewsAtPosition(position).forEach { view ->
            if (fadingOut) {
                CrossFadeHelper.fadeOut(view, 1 - alpha, /* remap= */ false)
            } else {
                CrossFadeHelper.fadeIn(view, alpha, /* remap= */ false)
            }
        }
        if (position == POSITION_TOP) {
            mStatusBarViewController.setFadeAmount(alpha, fadingOut)
        }
    }

    /** Sets y translation of complications at the specified position. */
    private fun setElementsTranslationYAtPosition(translationY: Float, position: Int) {
        mComplicationHostViewController.getViewsAtPosition(position).forEach { v ->
            v.translationY = translationY
        }
        if (position == POSITION_TOP) {
            mStatusBarViewController.setTranslationY(translationY)
        }
    }
}
