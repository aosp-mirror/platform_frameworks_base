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
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.dream.lowlight.util.TruncatedInterpolator
import com.android.systemui.ambient.statusbar.ui.AmbientStatusBarViewController
import com.android.systemui.complication.ComplicationHostViewController
import com.android.systemui.complication.ComplicationLayoutParams
import com.android.systemui.complication.ComplicationLayoutParams.POSITION_BOTTOM
import com.android.systemui.complication.ComplicationLayoutParams.POSITION_TOP
import com.android.systemui.complication.ComplicationLayoutParams.Position
import com.android.systemui.dreams.dagger.DreamOverlayComponent.DreamOverlayScope
import com.android.systemui.dreams.dagger.DreamOverlayModule
import com.android.systemui.dreams.ui.viewmodel.DreamViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.DreamLog
import com.android.systemui.statusbar.BlurUtils
import com.android.systemui.statusbar.CrossFadeHelper
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/** Controller for dream overlay animations. */
@DreamOverlayScope
class DreamOverlayAnimationsController
@Inject
constructor(
    private val mBlurUtils: BlurUtils,
    private val mComplicationHostViewController: ComplicationHostViewController,
    private val mStatusBarViewController: AmbientStatusBarViewController,
    private val mOverlayStateController: DreamOverlayStateController,
    @Named(DreamOverlayModule.DREAM_BLUR_RADIUS) private val mDreamBlurRadius: Int,
    private val dreamViewModel: DreamViewModel,
    @Named(DreamOverlayModule.DREAM_IN_BLUR_ANIMATION_DURATION)
    private val mDreamInBlurAnimDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_COMPLICATIONS_ANIMATION_DURATION)
    private val mDreamInComplicationsAnimDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_TRANSLATION_Y_DISTANCE)
    private val mDreamInTranslationYDistance: Int,
    @Named(DreamOverlayModule.DREAM_IN_TRANSLATION_Y_DURATION)
    private val mDreamInTranslationYDurationMs: Long,
    @DreamLog logBuffer: LogBuffer,
) {
    companion object {
        private const val TAG = "DreamOverlayAnimationsController"
    }

    private val logger = Logger(logBuffer, TAG)

    private var mAnimator: Animator? = null
    private lateinit var view: View

    /**
     * Store the current alphas at the various positions. This is so that we may resume an animation
     * at the current alpha.
     */
    private var mCurrentAlphaAtPosition = mutableMapOf<Int, Float>()

    private var mCurrentBlurRadius: Float = 0f

    private var mLifecycleFlowHandle: DisposableHandle? = null

    fun init(view: View) {
        this.view = view

        mLifecycleFlowHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        dreamViewModel.dreamOverlayTranslationY.collect { px ->
                            ComplicationLayoutParams.iteratePositions(
                                { position: Int ->
                                    setElementsTranslationYAtPosition(px, position)
                                },
                                POSITION_TOP or POSITION_BOTTOM
                            )
                        }
                    }

                    launch {
                        dreamViewModel.dreamOverlayTranslationX.collect { px ->
                            ComplicationLayoutParams.iteratePositions(
                                { position: Int ->
                                    setElementsTranslationXAtPosition(px, position)
                                },
                                POSITION_TOP or POSITION_BOTTOM
                            )
                        }
                    }

                    launch {
                        dreamViewModel.dreamOverlayAlpha.collect { alpha ->
                            ComplicationLayoutParams.iteratePositions(
                                { position: Int ->
                                    setElementsAlphaAtPosition(
                                        alpha = alpha,
                                        position = position,
                                        fadingOut = true,
                                    )
                                },
                                POSITION_TOP or POSITION_BOTTOM
                            )
                        }
                    }

                    launch {
                        dreamViewModel.transitionEnded.collect { _ ->
                            mOverlayStateController.setExitAnimationsRunning(false)
                        }
                    }
                }
            }
    }

    fun destroy() {
        mLifecycleFlowHandle?.dispose()
    }

    /**
     * Starts the dream content and dream overlay entry animations.
     *
     * @param downwards if true, the entry animation translations downwards into position rather
     *   than upwards.
     */
    @JvmOverloads
    fun startEntryAnimations(
        downwards: Boolean,
        animatorBuilder: () -> AnimatorSet = { AnimatorSet() }
    ) {
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
                        from = mDreamInTranslationYDistance.toFloat() * (if (downwards) -1 else 1),
                        to = 0f,
                        durationMs = mDreamInTranslationYDurationMs,
                        interpolator = Interpolators.EMPHASIZED_DECELERATE
                    ),
                )
                doOnEnd {
                    mAnimator = null
                    mOverlayStateController.setEntryAnimationsFinished(true)
                    logger.d("Dream overlay entry animations finished.")
                }
                doOnCancel { logger.d("Dream overlay entry animations canceled.") }
                start()
                logger.d("Dream overlay entry animations started.")
            }
    }

    /**
     * Starts the dream content and dream overlay exit animations.
     *
     * This should only be used when the low light dream is entering, animations to/from other SysUI
     * views is controlled by `transitionViewModel`.
     */
    // TODO(b/256916668): integrate with the keyguard transition model once dream surfaces work is
    // done.
    @JvmOverloads
    fun startExitAnimations(animatorBuilder: () -> AnimatorSet = { AnimatorSet() }): Animator {
        cancelAnimations()

        mAnimator =
            animatorBuilder().apply {
                playTogether(
                    translationYAnimator(
                        from = 0f,
                        to = -mDreamInTranslationYDistance.toFloat(),
                        durationMs = mDreamInComplicationsAnimDurationMs,
                        delayMs = 0,
                        // Truncate the animation from the full duration to match the alpha
                        // animation so that the whole animation ends at the same time.
                        interpolator =
                            TruncatedInterpolator(
                                Interpolators.EMPHASIZED,
                                /*originalDuration=*/ mDreamInTranslationYDurationMs.toFloat(),
                                /*newDuration=*/ mDreamInComplicationsAnimDurationMs.toFloat()
                            )
                    ),
                    alphaAnimator(
                        from =
                            mCurrentAlphaAtPosition.getOrDefault(
                                key = POSITION_BOTTOM,
                                defaultValue = 1f
                            ),
                        to = 0f,
                        durationMs = mDreamInComplicationsAnimDurationMs,
                        delayMs = 0,
                        positions = POSITION_BOTTOM
                    ),
                    alphaAnimator(
                        from =
                            mCurrentAlphaAtPosition.getOrDefault(
                                key = POSITION_TOP,
                                defaultValue = 1f
                            ),
                        to = 0f,
                        durationMs = mDreamInComplicationsAnimDurationMs,
                        delayMs = 0,
                        positions = POSITION_TOP
                    )
                )
                doOnEnd {
                    mAnimator = null
                    mOverlayStateController.setExitAnimationsRunning(false)
                    logger.d("Dream overlay exit animations finished.")
                }
                doOnCancel { logger.d("Dream overlay exit animations canceled.") }
                start()
                logger.d("Dream overlay exit animations started.")
            }
        mOverlayStateController.setExitAnimationsRunning(true)
        return mAnimator as AnimatorSet
    }

    fun onWakeUp() {
        cancelAnimations()
    }

    /** Cancels the dream content and dream overlay animations, if they're currently running. */
    fun cancelAnimations() {
        mAnimator =
            mAnimator?.let {
                it.cancel()
                null
            }
        mOverlayStateController.setExitAnimationsRunning(false)
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

    /** Sets x translation of complications at the specified position. */
    private fun setElementsTranslationXAtPosition(translationX: Float, position: Int) {
        mComplicationHostViewController.getViewsAtPosition(position).forEach { v ->
            v.translationX = translationX
        }
    }
}
