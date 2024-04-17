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
package com.android.wm.shell.back

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.RemoteException
import android.view.Choreographer
import android.view.Display
import android.view.IRemoteAnimationFinishedCallback
import android.view.IRemoteAnimationRunner
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.window.BackEvent
import android.window.BackMotionEvent
import android.window.BackNavigationInfo
import android.window.BackProgressAnimator
import android.window.IOnBackInvokedCallback
import com.android.internal.jank.Cuj
import com.android.internal.policy.ScreenDecorationsUtils
import com.android.internal.protolog.common.ProtoLog
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.animation.Interpolators
import com.android.wm.shell.protolog.ShellProtoLogGroup
import com.android.wm.shell.shared.annotations.ShellMainThread
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Class that defines cross-activity animation.  */
@ShellMainThread
class CrossActivityBackAnimation @Inject constructor(
    private val context: Context,
    private val background: BackAnimationBackground,
    private val rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
) : ShellBackAnimation() {

    private val startClosingRect = RectF()
    private val targetClosingRect = RectF()
    private val currentClosingRect = RectF()

    private val startEnteringRect = RectF()
    private val targetEnteringRect = RectF()
    private val currentEnteringRect = RectF()

    private val backAnimRect = Rect()
    private val cropRect = Rect()

    private var cornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context)

    private val backAnimationRunner = BackAnimationRunner(
        Callback(), Runner(), context, Cuj.CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY
    )
    private val initialTouchPos = PointF()
    private val transformMatrix = Matrix()
    private val tmpFloat9 = FloatArray(9)
    private var enteringTarget: RemoteAnimationTarget? = null
    private var closingTarget: RemoteAnimationTarget? = null
    private val transaction = SurfaceControl.Transaction()
    private var triggerBack = false
    private var finishCallback: IRemoteAnimationFinishedCallback? = null
    private val progressAnimator = BackProgressAnimator()
    private val displayBoundsMargin =
        context.resources.getDimension(R.dimen.cross_task_back_vertical_margin)
    private val enteringStartOffset =
        context.resources.getDimension(R.dimen.cross_activity_back_entering_start_offset)

    private val gestureInterpolator = Interpolators.BACK_GESTURE
    private val postCommitInterpolator = Interpolators.FAST_OUT_SLOW_IN
    private val verticalMoveInterpolator: Interpolator = DecelerateInterpolator()

    private var scrimLayer: SurfaceControl? = null
    private var maxScrimAlpha: Float = 0f

    private var isLetterboxed = false
    private var enteringHasSameLetterbox = false
    private var leftLetterboxLayer: SurfaceControl? = null
    private var rightLetterboxLayer: SurfaceControl? = null
    private var letterboxColor: Int = 0

    override fun onConfigurationChanged(newConfiguration: Configuration) {
        cornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context)
    }

    override fun getRunner() = backAnimationRunner

    private fun startBackAnimation(backMotionEvent: BackMotionEvent) {
        if (enteringTarget == null || closingTarget == null) {
            ProtoLog.d(
                ShellProtoLogGroup.WM_SHELL_BACK_PREVIEW,
                "Entering target or closing target is null."
            )
            return
        }
        triggerBack = backMotionEvent.triggerBack
        initialTouchPos.set(backMotionEvent.touchX, backMotionEvent.touchY)

        transaction.setAnimationTransaction()
        isLetterboxed = closingTarget!!.taskInfo.appCompatTaskInfo.topActivityBoundsLetterboxed
        enteringHasSameLetterbox = isLetterboxed &&
                closingTarget!!.localBounds.equals(enteringTarget!!.localBounds)

        if (isLetterboxed && !enteringHasSameLetterbox) {
            // Play animation with letterboxes, if closing and entering target have mismatching
            // letterboxes
            backAnimRect.set(closingTarget!!.windowConfiguration.bounds)
        } else {
            // otherwise play animation on localBounds only
            backAnimRect.set(closingTarget!!.localBounds)
        }
        // Offset start rectangle to align task bounds.
        backAnimRect.offsetTo(0, 0)

        startClosingRect.set(backAnimRect)

        // scale closing target into the middle for rhs and to the right for lhs
        targetClosingRect.set(startClosingRect)
        targetClosingRect.scaleCentered(MAX_SCALE)
        if (backMotionEvent.swipeEdge != BackEvent.EDGE_RIGHT) {
            targetClosingRect.offset(
                startClosingRect.right - targetClosingRect.right - displayBoundsMargin, 0f
            )
        }

        // the entering target starts 96dp to the left of the screen edge...
        startEnteringRect.set(startClosingRect)
        startEnteringRect.offset(-enteringStartOffset, 0f)

        // ...and gets scaled in sync with the closing target
        targetEnteringRect.set(startEnteringRect)
        targetEnteringRect.scaleCentered(MAX_SCALE)

        // Draw background with task background color (or letterbox color).
        val backgroundColor = if (isLetterboxed) {
            letterboxColor
        } else {
            enteringTarget!!.taskInfo.taskDescription!!.backgroundColor
        }
        background.ensureBackground(
            closingTarget!!.windowConfiguration.bounds, backgroundColor, transaction
        )
        ensureScrimLayer()
        if (isLetterboxed && enteringHasSameLetterbox) {
            // crop left and right letterboxes
            cropRect.set(closingTarget!!.localBounds.left, 0, closingTarget!!.localBounds.right,
                    closingTarget!!.windowConfiguration.bounds.height())
            // and add fake letterbox square surfaces instead
            ensureLetterboxes()
        } else {
            cropRect.set(backAnimRect)
        }
        applyTransaction()
    }

    private fun onGestureProgress(backEvent: BackEvent) {
        val progress = gestureInterpolator.getInterpolation(backEvent.progress)
        background.onBackProgressed(progress)
        currentClosingRect.setInterpolatedRectF(startClosingRect, targetClosingRect, progress)
        val yOffset = getYOffset(currentClosingRect, backEvent.touchY)
        currentClosingRect.offset(0f, yOffset)
        applyTransform(closingTarget?.leash, currentClosingRect, 1f)
        currentEnteringRect.setInterpolatedRectF(startEnteringRect, targetEnteringRect, progress)
        currentEnteringRect.offset(0f, yOffset)
        applyTransform(enteringTarget?.leash, currentEnteringRect, 1f)
        applyTransaction()
    }

    private fun getYOffset(centeredRect: RectF, touchY: Float): Float {
        val screenHeight = backAnimRect.height()
        // Base the window movement in the Y axis on the touch movement in the Y axis.
        val rawYDelta = touchY - initialTouchPos.y
        val yDirection = (if (rawYDelta < 0) -1 else 1)
        // limit yDelta interpretation to 1/2 of screen height in either direction
        val deltaYRatio = min(screenHeight / 2f, abs(rawYDelta)) / (screenHeight / 2f)
        val interpolatedYRatio: Float = verticalMoveInterpolator.getInterpolation(deltaYRatio)
        // limit y-shift so surface never passes 8dp screen margin
        val deltaY = yDirection * interpolatedYRatio * max(
            0f, (screenHeight - centeredRect.height()) / 2f - displayBoundsMargin
        )
        return deltaY
    }

    private fun onGestureCommitted() {
        if (closingTarget?.leash == null || enteringTarget?.leash == null ||
                !enteringTarget!!.leash.isValid || !closingTarget!!.leash.isValid
        ) {
            finishAnimation()
            return
        }

        // We enter phase 2 of the animation, the starting coordinates for phase 2 are the current
        // coordinate of the gesture driven phase. Let's update the start and target rects and kick
        // off the animator
        startClosingRect.set(currentClosingRect)
        startEnteringRect.set(currentEnteringRect)
        targetEnteringRect.set(backAnimRect)
        targetClosingRect.set(backAnimRect)
        targetClosingRect.offset(currentClosingRect.left + enteringStartOffset, 0f)

        val valueAnimator = ValueAnimator.ofFloat(1f, 0f).setDuration(POST_ANIMATION_DURATION)
        valueAnimator.addUpdateListener { animation: ValueAnimator ->
            val progress = animation.animatedFraction
            onPostCommitProgress(progress)
            if (progress > 1 - BackAnimationConstants.UPDATE_SYSUI_FLAGS_THRESHOLD) {
                background.resetStatusBarCustomization()
            }
        }
        valueAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                background.resetStatusBarCustomization()
                finishAnimation()
            }
        })
        valueAnimator.start()
    }

    private fun onPostCommitProgress(linearProgress: Float) {
        val closingAlpha = max(1f - linearProgress * 2, 0f)
        val progress = postCommitInterpolator.getInterpolation(linearProgress)
        scrimLayer?.let { transaction.setAlpha(it, maxScrimAlpha * (1f - linearProgress)) }
        currentClosingRect.setInterpolatedRectF(startClosingRect, targetClosingRect, progress)
        applyTransform(closingTarget?.leash, currentClosingRect, closingAlpha)
        currentEnteringRect.setInterpolatedRectF(startEnteringRect, targetEnteringRect, progress)
        applyTransform(enteringTarget?.leash, currentEnteringRect, 1f)
        applyTransaction()
    }

    private fun finishAnimation() {
        enteringTarget?.let {
            if (it.leash != null && it.leash.isValid) {
                transaction.setCornerRadius(it.leash, 0f)
                it.leash.release()
            }
            enteringTarget = null
        }

        closingTarget?.leash?.release()
        closingTarget = null

        background.removeBackground(transaction)
        applyTransaction()
        transformMatrix.reset()
        initialTouchPos.set(0f, 0f)
        try {
            finishCallback?.onAnimationFinished()
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        finishCallback = null
        removeScrimLayer()
        removeLetterbox()
        isLetterboxed = false
        enteringHasSameLetterbox = false
    }

    private fun applyTransform(leash: SurfaceControl?, rect: RectF, alpha: Float) {
        if (leash == null || !leash.isValid) return
        val scale = rect.width() / backAnimRect.width()
        transformMatrix.reset()
        val scalePivotX = if (isLetterboxed && enteringHasSameLetterbox) {
            closingTarget!!.localBounds.left.toFloat()
        } else {
            0f
        }
        transformMatrix.setScale(scale, scale, scalePivotX, 0f)
        transformMatrix.postTranslate(rect.left, rect.top)
        transaction.setAlpha(leash, alpha)
            .setMatrix(leash, transformMatrix, tmpFloat9)
            .setCrop(leash, cropRect)
            .setCornerRadius(leash, cornerRadius)
    }

    private fun applyTransaction() {
        transaction.setFrameTimelineVsync(Choreographer.getInstance().vsyncId)
        transaction.apply()
    }

    private fun ensureScrimLayer() {
        if (scrimLayer != null) return
        val isDarkTheme: Boolean = isDarkMode(context)
        val scrimBuilder = SurfaceControl.Builder()
            .setName("Cross-Activity back animation scrim")
            .setCallsite("CrossActivityBackAnimation")
            .setColorLayer()
            .setOpaque(false)
            .setHidden(false)

        rootTaskDisplayAreaOrganizer.attachToDisplayArea(Display.DEFAULT_DISPLAY, scrimBuilder)
        scrimLayer = scrimBuilder.build()
        val colorComponents = floatArrayOf(0f, 0f, 0f)
        maxScrimAlpha = if (isDarkTheme) MAX_SCRIM_ALPHA_DARK else MAX_SCRIM_ALPHA_LIGHT
        val scrimCrop = if (isLetterboxed) {
            closingTarget!!.windowConfiguration.bounds
        } else {
            closingTarget!!.localBounds
        }
        transaction
            .setColor(scrimLayer, colorComponents)
            .setAlpha(scrimLayer!!, maxScrimAlpha)
            .setCrop(scrimLayer!!, scrimCrop)
            .setRelativeLayer(scrimLayer!!, closingTarget!!.leash, -1)
            .show(scrimLayer)
    }

    private fun removeScrimLayer() {
        if (removeLayer(scrimLayer)) applyTransaction()
        scrimLayer = null
    }

    /**
     * Adds two "fake" letterbox square surfaces to the left and right of the localBounds of the
     * closing target
     */
    private fun ensureLetterboxes() {
        closingTarget?.let { t ->
            if (t.localBounds.left != 0 && leftLetterboxLayer == null) {
                val bounds = Rect(0, t.windowConfiguration.bounds.top, t.localBounds.left,
                        t.windowConfiguration.bounds.bottom)
                leftLetterboxLayer = ensureLetterbox(bounds)
            }
            if (t.localBounds.right != t.windowConfiguration.bounds.right &&
                    rightLetterboxLayer == null) {
                val bounds = Rect(t.localBounds.right, t.windowConfiguration.bounds.top,
                        t.windowConfiguration.bounds.right, t.windowConfiguration.bounds.bottom)
                rightLetterboxLayer = ensureLetterbox(bounds)
            }
        }
    }

    private fun ensureLetterbox(bounds: Rect): SurfaceControl {
        val letterboxBuilder = SurfaceControl.Builder()
                .setName("Cross-Activity back animation letterbox")
                .setCallsite("CrossActivityBackAnimation")
                .setColorLayer()
                .setOpaque(true)
                .setHidden(false)

        rootTaskDisplayAreaOrganizer.attachToDisplayArea(Display.DEFAULT_DISPLAY, letterboxBuilder)
        val layer = letterboxBuilder.build()
        val colorComponents = floatArrayOf(Color.red(letterboxColor) / 255f,
                Color.green(letterboxColor) / 255f, Color.blue(letterboxColor) / 255f)
        transaction
                .setColor(layer, colorComponents)
                .setCrop(layer, bounds)
                .setRelativeLayer(layer, closingTarget!!.leash, 1)
                .show(layer)
        return layer
    }

    private fun removeLetterbox() {
        if (removeLayer(leftLetterboxLayer) || removeLayer(rightLetterboxLayer)) applyTransaction()
        leftLetterboxLayer = null
        rightLetterboxLayer = null
    }

    private fun removeLayer(layer: SurfaceControl?): Boolean {
        layer?.let {
            if (it.isValid) {
                transaction.remove(it)
                return true
            }
        }
        return false
    }

    override fun prepareNextAnimation(
            animationInfo: BackNavigationInfo.CustomAnimationInfo?,
            letterboxColor: Int
    ): Boolean {
        this.letterboxColor = letterboxColor
        return false
    }

    private inner class Callback : IOnBackInvokedCallback.Default() {
        override fun onBackStarted(backMotionEvent: BackMotionEvent) {
            // in case we're still animating an onBackCancelled event, let's remove the finish-
            // callback from the progress animator to prevent calling finishAnimation() before
            // restarting a new animation
            progressAnimator.removeOnBackCancelledFinishCallback()

            startBackAnimation(backMotionEvent)
            progressAnimator.onBackStarted(backMotionEvent) { backEvent: BackEvent ->
                onGestureProgress(backEvent)
            }
        }

        override fun onBackProgressed(backEvent: BackMotionEvent) {
            triggerBack = backEvent.triggerBack
            progressAnimator.onBackProgressed(backEvent)
        }

        override fun onBackCancelled() {
            progressAnimator.onBackCancelled {
                finishAnimation()
            }
        }

        override fun onBackInvoked() {
            progressAnimator.reset()
            onGestureCommitted()
        }
    }

    private inner class Runner : IRemoteAnimationRunner.Default() {
        override fun onAnimationStart(
            transit: Int,
            apps: Array<RemoteAnimationTarget>,
            wallpapers: Array<RemoteAnimationTarget>?,
            nonApps: Array<RemoteAnimationTarget>?,
            finishedCallback: IRemoteAnimationFinishedCallback
        ) {
            ProtoLog.d(
                ShellProtoLogGroup.WM_SHELL_BACK_PREVIEW, "Start back to activity animation."
            )
            for (a in apps) {
                when (a.mode) {
                    RemoteAnimationTarget.MODE_CLOSING -> closingTarget = a
                    RemoteAnimationTarget.MODE_OPENING -> enteringTarget = a
                }
            }
            finishCallback = finishedCallback
        }

        override fun onAnimationCancelled() {
            finishAnimation()
        }
    }

    companion object {
        /** Max scale of the entering/closing window.*/
        private const val MAX_SCALE = 0.9f

        /** Duration of post animation after gesture committed.  */
        private const val POST_ANIMATION_DURATION = 300L

        private const val MAX_SCRIM_ALPHA_DARK = 0.8f
        private const val MAX_SCRIM_ALPHA_LIGHT = 0.2f
    }
}

private fun isDarkMode(context: Context): Boolean {
    return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
}

private fun RectF.setInterpolatedRectF(start: RectF, target: RectF, progress: Float) {
    require(!(progress < 0 || progress > 1)) { "Progress value must be between 0 and 1" }
    left = start.left + (target.left - start.left) * progress
    top = start.top + (target.top - start.top) * progress
    right = start.right + (target.right - start.right) * progress
    bottom = start.bottom + (target.bottom - start.bottom) * progress
}

private fun RectF.scaleCentered(
    scale: Float,
    pivotX: Float = left + width() / 2,
    pivotY: Float = top + height() / 2
) {
    offset(-pivotX, -pivotY) // move pivot to origin
    scale(scale)
    offset(pivotX, pivotY) // Move back to the original position
}
