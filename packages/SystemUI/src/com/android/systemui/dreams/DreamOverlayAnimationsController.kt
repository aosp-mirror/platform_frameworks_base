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
import com.android.systemui.R
import com.android.systemui.complication.ComplicationHostViewController
import com.android.systemui.complication.ComplicationLayoutParams
import com.android.systemui.complication.ComplicationLayoutParams.POSITION_BOTTOM
import com.android.systemui.complication.ComplicationLayoutParams.POSITION_TOP
import com.android.systemui.complication.ComplicationLayoutParams.Position
import com.android.systemui.dreams.dagger.DreamOverlayModule
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.BlurUtils
import com.android.systemui.statusbar.CrossFadeHelper
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Controller for dream overlay animations. */
class DreamOverlayAnimationsController
@Inject
constructor(
    private val mBlurUtils: BlurUtils,
    private val mComplicationHostViewController: ComplicationHostViewController,
    private val mStatusBarViewController: DreamOverlayStatusBarViewController,
    private val mOverlayStateController: DreamOverlayStateController,
    @Named(DreamOverlayModule.DREAM_BLUR_RADIUS) private val mDreamBlurRadius: Int,
    private val transitionViewModel: DreamingToLockscreenTransitionViewModel,
    private val configController: ConfigurationController,
    @Named(DreamOverlayModule.DREAM_IN_BLUR_ANIMATION_DURATION)
    private val mDreamInBlurAnimDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_COMPLICATIONS_ANIMATION_DURATION)
    private val mDreamInComplicationsAnimDurationMs: Long,
    @Named(DreamOverlayModule.DREAM_IN_TRANSLATION_Y_DISTANCE)
    private val mDreamInTranslationYDistance: Int,
    @Named(DreamOverlayModule.DREAM_IN_TRANSLATION_Y_DURATION)
    private val mDreamInTranslationYDurationMs: Long,
    private val mLogger: DreamLogger,
) {
    companion object {
        private const val TAG = "DreamOverlayAnimationsController"
    }

    private var mAnimator: Animator? = null
    private lateinit var view: View

    /**
     * Store the current alphas at the various positions. This is so that we may resume an animation
     * at the current alpha.
     */
    private var mCurrentAlphaAtPosition = mutableMapOf<Int, Float>()

    private var mCurrentBlurRadius: Float = 0f

    fun init(view: View) {
        this.view = view

        view.repeatWhenAttached {
            val configurationBasedDimensions = MutableStateFlow(loadFromResources(view))
            val configCallback =
                object : ConfigurationListener {
                    override fun onDensityOrFontScaleChanged() {
                        configurationBasedDimensions.value = loadFromResources(view)
                    }
                }

            configController.addCallback(configCallback)

            repeatOnLifecycle(Lifecycle.State.CREATED) {
                /* Translation animations, when moving from DREAMING->LOCKSCREEN state */
                launch {
                    configurationBasedDimensions
                        .flatMapLatest {
                            transitionViewModel.dreamOverlayTranslationY(it.translationYPx)
                        }
                        .collect { px ->
                            ComplicationLayoutParams.iteratePositions(
                                { position: Int ->
                                    setElementsTranslationYAtPosition(px, position)
                                },
                                POSITION_TOP or POSITION_BOTTOM
                            )
                        }
                }

                /* Alpha animations, when moving from DREAMING->LOCKSCREEN state */
                launch {
                    transitionViewModel.dreamOverlayAlpha.collect { alpha ->
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
                    transitionViewModel.transitionEnded.collect { _ ->
                        mOverlayStateController.setExitAnimationsRunning(false)
                    }
                }
            }

            configController.removeCallback(configCallback)
        }
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
                    mLogger.d(TAG, "Dream overlay entry animations finished.")
                }
                doOnCancel { mLogger.d(TAG, "Dream overlay entry animations canceled.") }
                start()
                mLogger.d(TAG, "Dream overlay entry animations started.")
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
                        durationMs = mDreamInTranslationYDurationMs,
                        delayMs = 0,
                        interpolator = Interpolators.EMPHASIZED
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
                        )
                        .apply {
                            doOnEnd {
                                // The logical end of the animation is once the alpha and blur
                                // animations finish, end the animation so that any listeners are
                                // notified. The Y translation animation is much longer than all of
                                // the other animations due to how the spec is defined, but is not
                                // expected to run to completion.
                                mAnimator?.end()
                            }
                        },
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
                    mLogger.d(TAG, "Dream overlay exit animations finished.")
                }
                doOnCancel { mLogger.d(TAG, "Dream overlay exit animations canceled.") }
                start()
                mLogger.d(TAG, "Dream overlay exit animations started.")
            }
        mOverlayStateController.setExitAnimationsRunning(true)
        return mAnimator as AnimatorSet
    }

    /** Starts the dream content and dream overlay exit animations. */
    fun wakeUp() {
        cancelAnimations()
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

    private fun loadFromResources(view: View): ConfigurationBasedDimensions {
        return ConfigurationBasedDimensions(
            translationYPx =
                view.resources.getDimensionPixelSize(R.dimen.dream_overlay_exit_y_offset),
        )
    }

    private data class ConfigurationBasedDimensions(
        val translationYPx: Int,
    )
}
