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
import androidx.annotation.FloatRange
import androidx.core.animation.doOnEnd
import com.android.systemui.animation.Interpolators
import com.android.systemui.dreams.complication.ComplicationHostViewController
import com.android.systemui.dreams.complication.ComplicationLayoutParams
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
    @Named(DreamOverlayModule.DREAM_IN_BLUR_ANIMATION_DURATION)
    private val mDreamInBlurAnimDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_BLUR_ANIMATION_DELAY)
    private val mDreamInBlurAnimDelayMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_COMPLICATIONS_ANIMATION_DURATION)
    private val mDreamInComplicationsAnimDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_TOP_COMPLICATIONS_ANIMATION_DELAY)
    private val mDreamInTopComplicationsAnimDelayMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_BOTTOM_COMPLICATIONS_ANIMATION_DELAY)
    private val mDreamInBottomComplicationsAnimDelayMs: Long,
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

    @FloatRange(from = 0.0, to = 1.0) private var mBlurProgress: Float = 0f

    /** Starts the dream content and dream overlay entry animations. */
    @JvmOverloads
    fun startEntryAnimations(view: View, animatorBuilder: () -> AnimatorSet = { AnimatorSet() }) {
        cancelAnimations()

        mAnimator =
            animatorBuilder().apply {
                playTogether(
                    blurAnimator(
                        view = view,
                        from = 1f,
                        to = 0f,
                        durationMs = mDreamInBlurAnimDurationMs,
                        delayMs = mDreamInBlurAnimDelayMs
                    ),
                    alphaAnimator(
                        from = 0f,
                        to = 1f,
                        durationMs = mDreamInComplicationsAnimDurationMs,
                        delayMs = mDreamInTopComplicationsAnimDelayMs,
                        position = ComplicationLayoutParams.POSITION_TOP
                    ),
                    alphaAnimator(
                        from = 0f,
                        to = 1f,
                        durationMs = mDreamInComplicationsAnimDurationMs,
                        delayMs = mDreamInBottomComplicationsAnimDelayMs,
                        position = ComplicationLayoutParams.POSITION_BOTTOM
                    )
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
                        from = mBlurProgress,
                        to = 1f,
                        durationMs = mDreamOutBlurDurationMs
                    ),
                    translationYAnimator(
                        from = 0f,
                        to = mDreamOutTranslationYDistance.toFloat(),
                        durationMs = mDreamOutTranslationYDurationMs,
                        delayMs = mDreamOutTranslationYDelayBottomMs,
                        position = ComplicationLayoutParams.POSITION_BOTTOM,
                        animInterpolator = Interpolators.EMPHASIZED_ACCELERATE
                    ),
                    translationYAnimator(
                        from = 0f,
                        to = mDreamOutTranslationYDistance.toFloat(),
                        durationMs = mDreamOutTranslationYDurationMs,
                        delayMs = mDreamOutTranslationYDelayTopMs,
                        position = ComplicationLayoutParams.POSITION_TOP,
                        animInterpolator = Interpolators.EMPHASIZED_ACCELERATE
                    ),
                    alphaAnimator(
                        from =
                            mCurrentAlphaAtPosition.getOrDefault(
                                key = ComplicationLayoutParams.POSITION_BOTTOM,
                                defaultValue = 1f
                            ),
                        to = 0f,
                        durationMs = mDreamOutAlphaDurationMs,
                        delayMs = mDreamOutAlphaDelayBottomMs,
                        position = ComplicationLayoutParams.POSITION_BOTTOM
                    ),
                    alphaAnimator(
                        from =
                            mCurrentAlphaAtPosition.getOrDefault(
                                key = ComplicationLayoutParams.POSITION_TOP,
                                defaultValue = 1f
                            ),
                        to = 0f,
                        durationMs = mDreamOutAlphaDurationMs,
                        delayMs = mDreamOutAlphaDelayTopMs,
                        position = ComplicationLayoutParams.POSITION_TOP
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
        from: Float,
        to: Float,
        durationMs: Long,
        delayMs: Long = 0
    ): Animator {
        return ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = Interpolators.LINEAR
            addUpdateListener { animator: ValueAnimator ->
                mBlurProgress = animator.animatedValue as Float
                mBlurUtils.applyBlur(
                    viewRootImpl = view.viewRootImpl,
                    radius = mBlurUtils.blurRadiusOfRatio(mBlurProgress).toInt(),
                    opaque = false
                )
            }
        }
    }

    private fun alphaAnimator(
        from: Float,
        to: Float,
        durationMs: Long,
        delayMs: Long,
        @Position position: Int
    ): Animator {
        return ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = Interpolators.LINEAR
            addUpdateListener { va: ValueAnimator ->
                setElementsAlphaAtPosition(
                    alpha = va.animatedValue as Float,
                    position = position,
                    fadingOut = to < from
                )
            }
        }
    }

    private fun translationYAnimator(
        from: Float,
        to: Float,
        durationMs: Long,
        delayMs: Long,
        @Position position: Int,
        animInterpolator: Interpolator
    ): Animator {
        return ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = animInterpolator
            addUpdateListener { va: ValueAnimator ->
                setElementsTranslationYAtPosition(va.animatedValue as Float, position)
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
        if (position == ComplicationLayoutParams.POSITION_TOP) {
            mStatusBarViewController.setFadeAmount(alpha, fadingOut)
        }
    }

    /** Sets y translation of complications at the specified position. */
    private fun setElementsTranslationYAtPosition(translationY: Float, position: Int) {
        mComplicationHostViewController.getViewsAtPosition(position).forEach { v ->
            v.translationY = translationY
        }
        if (position == ComplicationLayoutParams.POSITION_TOP) {
            mStatusBarViewController.setTranslationY(translationY)
        }
    }
}
