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
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.util.MathUtils
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import com.android.systemui.res.R
import com.android.systemui.screenshot.scroll.ScrollCaptureController
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

class ScreenshotAnimationController(
    private val view: ScreenshotShelfView,
    private val viewModel: ScreenshotViewModel
) {
    private var animator: Animator? = null
    private val screenshotPreview = view.requireViewById<ImageView>(R.id.screenshot_preview)
    private val scrollingScrim = view.requireViewById<ImageView>((R.id.screenshot_scrolling_scrim))
    private val scrollTransitionPreview =
        view.requireViewById<ImageView>(R.id.screenshot_scrollable_preview)
    private val flashView = view.requireViewById<View>(R.id.screenshot_flash)
    private val actionContainer = view.requireViewById<View>(R.id.actions_container_background)
    private val fastOutSlowIn =
        AnimationUtils.loadInterpolator(view.context, android.R.interpolator.fast_out_slow_in)
    private val staticUI =
        listOf<View>(
            view.requireViewById(R.id.screenshot_preview_border),
            view.requireViewById(R.id.screenshot_badge),
            view.requireViewById(R.id.screenshot_dismiss_button)
        )
    private val fadeUI =
        listOf<View>(
            view.requireViewById(R.id.screenshot_preview_border),
            view.requireViewById(R.id.actions_container_background),
            view.requireViewById(R.id.screenshot_badge),
            view.requireViewById(R.id.screenshot_dismiss_button),
            view.requireViewById(R.id.screenshot_message_container),
        )

    fun getEntranceAnimation(
        bounds: Rect,
        showFlash: Boolean,
        onRevealMilestone: () -> Unit
    ): Animator {
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

        val actionsAnimator = getActionsAnimator()
        entranceAnimation.play(actionsAnimator).with(previewAnimator)

        // This isn't actually animating anything but is basically a timer for the first 200ms of
        // the entrance animation. Using an animator here ensures that this is scaled if we change
        // animator duration scales.
        val revealMilestoneAnimator =
            ValueAnimator.ofFloat(0f).apply {
                duration = 0
                startDelay = ACTION_REVEAL_DELAY_MS
                doOnEnd { onRevealMilestone() }
            }
        entranceAnimation.play(revealMilestoneAnimator).with(actionsAnimator)

        val fadeInAnimator = ValueAnimator.ofFloat(0f, 1f)
        fadeInAnimator.addUpdateListener {
            for (child in staticUI) {
                child.alpha = it.animatedValue as Float
            }
        }
        entranceAnimation.play(fadeInAnimator).after(previewAnimator)
        entranceAnimation.doOnStart {
            viewModel.setIsAnimating(true)
            for (child in staticUI) {
                child.alpha = 0f
            }
        }
        entranceAnimation.doOnEnd { viewModel.setIsAnimating(false) }

        this.animator = entranceAnimation
        return entranceAnimation
    }

    fun fadeForSharedTransition() {
        animator?.cancel()
        val fadeAnimator = ValueAnimator.ofFloat(1f, 0f)
        fadeAnimator.addUpdateListener {
            for (view in fadeUI) {
                view.alpha = it.animatedValue as Float
            }
        }
        animator = fadeAnimator
        fadeAnimator.start()
    }

    fun runLongScreenshotTransition(
        destRect: Rect,
        longScreenshot: ScrollCaptureController.LongScreenshot,
        onTransitionEnd: Runnable
    ): Animator {
        val animSet = AnimatorSet()

        val scrimAnim = ValueAnimator.ofFloat(0f, 1f)
        scrimAnim.addUpdateListener { animation: ValueAnimator ->
            scrollingScrim.setAlpha(1 - animation.animatedFraction)
        }
        scrollTransitionPreview.visibility = View.VISIBLE
        if (true) {
            scrollTransitionPreview.setImageBitmap(longScreenshot.toBitmap())
            val startX: Float = scrollTransitionPreview.x
            val startY: Float = scrollTransitionPreview.y
            val locInScreen: IntArray = scrollTransitionPreview.getLocationOnScreen()
            destRect.offset(startX.toInt() - locInScreen[0], startY.toInt() - locInScreen[1])
            scrollTransitionPreview.pivotX = 0f
            scrollTransitionPreview.pivotY = 0f
            scrollTransitionPreview.setAlpha(1f)
            val currentScale: Float = scrollTransitionPreview.width / longScreenshot.width.toFloat()
            val matrix = Matrix()
            matrix.setScale(currentScale, currentScale)
            matrix.postTranslate(
                longScreenshot.left * currentScale,
                longScreenshot.top * currentScale
            )
            scrollTransitionPreview.setImageMatrix(matrix)
            val destinationScale: Float = destRect.width() / scrollTransitionPreview.width.toFloat()
            val previewAnim = ValueAnimator.ofFloat(0f, 1f)
            previewAnim.addUpdateListener { animation: ValueAnimator ->
                val t = animation.animatedFraction
                val currScale = MathUtils.lerp(1f, destinationScale, t)
                scrollTransitionPreview.scaleX = currScale
                scrollTransitionPreview.scaleY = currScale
                scrollTransitionPreview.x = MathUtils.lerp(startX, destRect.left.toFloat(), t)
                scrollTransitionPreview.y = MathUtils.lerp(startY, destRect.top.toFloat(), t)
            }
            val previewFadeAnim = ValueAnimator.ofFloat(1f, 0f)
            previewFadeAnim.addUpdateListener { animation: ValueAnimator ->
                scrollTransitionPreview.setAlpha(1 - animation.animatedFraction)
            }
            previewAnim.doOnEnd { onTransitionEnd.run() }
            animSet.play(previewAnim).with(scrimAnim).before(previewFadeAnim)
        } else {
            // if we switched orientations between the original screenshot and the long screenshot
            // capture, just fade out the scrim instead of running the preview animation
            scrimAnim.doOnEnd { onTransitionEnd.run() }
            animSet.play(scrimAnim)
        }
        animator = animSet
        return animSet
    }

    fun fadeForLongScreenshotTransition() {
        scrollingScrim.imageTintBlendMode = BlendMode.SRC_ATOP
        val anim = ValueAnimator.ofFloat(0f, .3f)
        anim.addUpdateListener {
            scrollingScrim.setImageTintList(
                ColorStateList.valueOf(Color.argb(it.animatedValue as Float, 0f, 0f, 0f))
            )
        }
        for (view in fadeUI) {
            view.alpha = 0f
        }
        screenshotPreview.alpha = 0f
        anim.setDuration(200)
        anim.start()
    }

    fun restoreUI() {
        animator?.cancel()
        for (view in fadeUI) {
            view.alpha = 1f
        }
        screenshotPreview.alpha = 1f
    }

    fun getSwipeReturnAnimation(): Animator {
        animator?.cancel()
        val animator = ValueAnimator.ofFloat(view.translationX, 0f)
        animator.addUpdateListener { view.translationX = it.animatedValue as Float }
        this.animator = animator
        return animator
    }

    fun getSwipeDismissAnimation(requestedVelocity: Float?): Animator {
        animator?.cancel()
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
        animator.doOnStart { viewModel.setIsAnimating(true) }
        animator.doOnEnd { viewModel.setIsAnimating(false) }

        this.animator = animator
        return animator
    }

    fun cancel() {
        animator?.cancel()
    }

    private fun getActionsAnimator(): Animator {
        val startingOffset = view.height - actionContainer.top
        val actionsYAnimator =
            ValueAnimator.ofFloat(startingOffset.toFloat(), 0f).apply {
                duration = PREVIEW_Y_ANIMATION_DURATION_MS
                interpolator = fastOutSlowIn
            }
        actionsYAnimator.addUpdateListener {
            actionContainer.translationY = it.animatedValue as Float
        }
        actionContainer.translationY = startingOffset.toFloat()
        return actionsYAnimator
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
        previewAnimator.doOnEnd {
            screenshotPreview.scaleX = 1f
            screenshotPreview.scaleY = 1f
            screenshotPreview.x = endPos.x - screenshotPreview.width / 2f
            screenshotPreview.y = endPos.y - screenshotPreview.height / 2f
        }

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
        private const val ACTION_REVEAL_DELAY_MS: Long = 200
    }
}
