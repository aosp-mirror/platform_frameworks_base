/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.PointF
import android.graphics.Rect
import android.util.MathUtils
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

class ScreenshotAnimationController(private val view: ScreenshotShelfView) {
    private var animator: Animator? = null
    private val screenshotPreview = view.requireViewById<View>(R.id.screenshot_preview)
    private val flashView = view.requireViewById<View>(R.id.screenshot_flash)
    private val actionContainer = view.requireViewById<View>(R.id.actions_container_background)
    private val fastOutSlowIn =
        AnimationUtils.loadInterpolator(view.context, android.R.interpolator.fast_out_slow_in)
    private val staticUI =
        listOf<View>(
            view.requireViewById(R.id.screenshot_preview_border),
            view.requireViewById(R.id.actions_container_background),
            view.requireViewById(R.id.screenshot_badge),
            view.requireViewById(R.id.screenshot_dismiss_button)
        )

    fun getEntranceAnimation(bounds: Rect, showFlash: Boolean): Animator {
        val entranceAnimation = AnimatorSet()

        val previewAnimator = getPreviewAnimator(bounds)

        if (showFlash) {
            val flashInAnimator =
                ObjectAnimator.ofFloat(flashView, "alpha", 0f, 1f).apply {
                    duration = FLASH_IN_DURATION_MS
                    interpolator = fastOutSlowIn
                }
            val flashOutAnimator =
                ObjectAnimator.ofFloat(flashView, "alpha", 1f, 0f).apply {
                    duration = FLASH_OUT_DURATION_MS
                    interpolator = fastOutSlowIn
                }
            flashInAnimator.doOnStart { flashView.visibility = View.VISIBLE }
            flashOutAnimator.doOnEnd { flashView.visibility = View.GONE }
            entranceAnimation.play(flashOutAnimator).after(flashInAnimator)
            entranceAnimation.play(previewAnimator).with(flashOutAnimator)
            entranceAnimation.doOnStart { screenshotPreview.visibility = View.INVISIBLE }
        }

        val fadeInAnimator = ValueAnimator.ofFloat(0f, 1f)
        fadeInAnimator.addUpdateListener {
            for (child in staticUI) {
                child.alpha = it.animatedValue as Float
            }
        }
        entranceAnimation.play(fadeInAnimator).after(previewAnimator)
        entranceAnimation.doOnStart {
            for (child in staticUI) {
                child.alpha = 0f
            }
        }

        this.animator = entranceAnimation
        return entranceAnimation
    }

    fun getSwipeReturnAnimation(): Animator {
        animator?.cancel()
        val animator = ValueAnimator.ofFloat(view.translationX, 0f)
        animator.addUpdateListener { view.translationX = it.animatedValue as Float }
        this.animator = animator
        return animator
    }

    fun getSwipeDismissAnimation(requestedVelocity: Float?): Animator {
        val velocity = getAdjustedVelocity(requestedVelocity)
        val screenWidth = view.resources.displayMetrics.widthPixels
        // translation at which point the visible UI is fully off the screen (in the direction
        // according to velocity)
        val endX =
            if (velocity < 0) {
                -1f * actionContainer.right
            } else {
                (screenWidth - actionContainer.left).toFloat()
            }
        val distance = endX - view.translationX
        val animator = ValueAnimator.ofFloat(view.translationX, endX)
        animator.addUpdateListener {
            view.translationX = it.animatedValue as Float
            view.alpha = 1f - it.animatedFraction
        }
        animator.duration = ((abs(distance / velocity))).toLong()

        this.animator = animator
        return animator
    }

    fun cancel() {
        animator?.cancel()
    }

    private fun getPreviewAnimator(bounds: Rect): Animator {
        val targetPosition = Rect()
        screenshotPreview.getHitRect(targetPosition)
        val startXScale = bounds.width() / targetPosition.width().toFloat()
        val startYScale = bounds.height() / targetPosition.height().toFloat()
        val startPos = PointF(bounds.exactCenterX(), bounds.exactCenterY())
        val endPos = PointF(targetPosition.exactCenterX(), targetPosition.exactCenterY())

        val previewYAnimator =
            ValueAnimator.ofFloat(startPos.y, endPos.y).apply {
                duration = PREVIEW_Y_ANIMATION_DURATION_MS
                interpolator = fastOutSlowIn
            }
        previewYAnimator.addUpdateListener {
            val progress = it.animatedValue as Float
            screenshotPreview.y = progress - screenshotPreview.height / 2f
        }
        // scale animation starts/finishes at the same time as x placement
        val previewXAndScaleAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = PREVIEW_X_ANIMATION_DURATION_MS
                interpolator = fastOutSlowIn
            }
        previewXAndScaleAnimator.addUpdateListener {
            val t = it.animatedFraction
            screenshotPreview.scaleX = MathUtils.lerp(startXScale, 1f, t)
            screenshotPreview.scaleY = MathUtils.lerp(startYScale, 1f, t)
            screenshotPreview.x =
                MathUtils.lerp(startPos.x, endPos.x, t) - screenshotPreview.width / 2f
        }

        val previewAnimator = AnimatorSet()
        previewAnimator.play(previewXAndScaleAnimator).with(previewYAnimator)

        previewAnimator.doOnStart { screenshotPreview.visibility = View.VISIBLE }
        return previewAnimator
    }

    private fun getAdjustedVelocity(requestedVelocity: Float?): Float {
        return if (requestedVelocity == null) {
            val isLTR = view.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR
            // dismiss to the left in LTR locales, to the right in RTL
            if (isLTR) -MINIMUM_VELOCITY else MINIMUM_VELOCITY
        } else {
            sign(requestedVelocity) * max(MINIMUM_VELOCITY, abs(requestedVelocity))
        }
    }

    companion object {
        private const val MINIMUM_VELOCITY = 1.5f // pixels per second
        private const val FLASH_IN_DURATION_MS: Long = 133
        private const val FLASH_OUT_DURATION_MS: Long = 217
        private const val PREVIEW_X_ANIMATION_DURATION_MS: Long = 234
        private const val PREVIEW_Y_ANIMATION_DURATION_MS: Long = 500
    }
}
