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
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.View
import androidx.core.animation.doOnEnd
import com.android.systemui.animation.Interpolators
import com.android.systemui.dreams.complication.ComplicationHostViewController
import com.android.systemui.dreams.complication.ComplicationLayoutParams
import com.android.systemui.dreams.dagger.DreamOverlayModule
import com.android.systemui.statusbar.BlurUtils
import java.util.function.Consumer
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
    private val mDreamInBlurAnimDuration: Int,
    @Named(DreamOverlayModule.DREAM_IN_BLUR_ANIMATION_DELAY) private val mDreamInBlurAnimDelay: Int,
    @Named(DreamOverlayModule.DREAM_IN_COMPLICATIONS_ANIMATION_DURATION)
    private val mDreamInComplicationsAnimDuration: Int,
    @Named(DreamOverlayModule.DREAM_IN_TOP_COMPLICATIONS_ANIMATION_DELAY)
    private val mDreamInTopComplicationsAnimDelay: Int,
    @Named(DreamOverlayModule.DREAM_IN_BOTTOM_COMPLICATIONS_ANIMATION_DELAY)
    private val mDreamInBottomComplicationsAnimDelay: Int
) {

    var mEntryAnimations: AnimatorSet? = null

    /** Starts the dream content and dream overlay entry animations. */
    fun startEntryAnimations(view: View) {
        cancelRunningEntryAnimations()

        mEntryAnimations = AnimatorSet()
        mEntryAnimations?.apply {
            playTogether(
                buildDreamInBlurAnimator(view),
                buildDreamInTopComplicationsAnimator(),
                buildDreamInBottomComplicationsAnimator()
            )
            doOnEnd { mOverlayStateController.setEntryAnimationsFinished(true) }
            start()
        }
    }

    /** Cancels the dream content and dream overlay animations, if they're currently running. */
    fun cancelRunningEntryAnimations() {
        if (mEntryAnimations?.isRunning == true) {
            mEntryAnimations?.cancel()
        }
        mEntryAnimations = null
    }

    private fun buildDreamInBlurAnimator(view: View): Animator {
        return ValueAnimator.ofFloat(1f, 0f).apply {
            duration = mDreamInBlurAnimDuration.toLong()
            startDelay = mDreamInBlurAnimDelay.toLong()
            interpolator = Interpolators.LINEAR
            addUpdateListener { animator: ValueAnimator ->
                mBlurUtils.applyBlur(
                    view.viewRootImpl,
                    mBlurUtils.blurRadiusOfRatio(animator.animatedValue as Float).toInt(),
                    false /*opaque*/
                )
            }
        }
    }

    private fun buildDreamInTopComplicationsAnimator(): Animator {
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = mDreamInComplicationsAnimDuration.toLong()
            startDelay = mDreamInTopComplicationsAnimDelay.toLong()
            interpolator = Interpolators.LINEAR
            addUpdateListener { va: ValueAnimator ->
                setTopElementsAlpha(va.animatedValue as Float)
            }
        }
    }

    private fun buildDreamInBottomComplicationsAnimator(): Animator {
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = mDreamInComplicationsAnimDuration.toLong()
            startDelay = mDreamInBottomComplicationsAnimDelay.toLong()
            interpolator = Interpolators.LINEAR
            addUpdateListener { va: ValueAnimator ->
                setBottomElementsAlpha(va.animatedValue as Float)
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        mComplicationHostViewController
                            .getViewsAtPosition(ComplicationLayoutParams.POSITION_BOTTOM)
                            .forEach(Consumer { v: View -> v.visibility = View.VISIBLE })
                    }
                }
            )
        }
    }

    /** Sets alpha of top complications and the status bar. */
    private fun setTopElementsAlpha(alpha: Float) {
        mComplicationHostViewController
            .getViewsAtPosition(ComplicationLayoutParams.POSITION_TOP)
            .forEach(Consumer { v: View -> setAlphaAndEnsureVisible(v, alpha) })
        mStatusBarViewController.setAlpha(alpha)
    }

    /** Sets alpha of bottom complications. */
    private fun setBottomElementsAlpha(alpha: Float) {
        mComplicationHostViewController
            .getViewsAtPosition(ComplicationLayoutParams.POSITION_BOTTOM)
            .forEach(Consumer { v: View -> setAlphaAndEnsureVisible(v, alpha) })
    }

    private fun setAlphaAndEnsureVisible(view: View, alpha: Float) {
        if (alpha > 0 && view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }

        view.alpha = alpha
    }
}
