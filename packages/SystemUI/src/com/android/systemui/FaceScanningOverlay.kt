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

package com.android.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.ColorUtils
import com.android.app.animation.Interpolators
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.settingslib.Utils
import com.android.systemui.biometrics.AuthController
import com.android.systemui.log.ScreenDecorationsLogger
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.util.asIndenting
import java.io.PrintWriter
import java.util.concurrent.Executor

/**
 * When the face is enrolled, we use this view to show the face scanning animation and the camera
 * protection on the keyguard.
 */
class FaceScanningOverlay(
    context: Context,
    pos: Int,
    val statusBarStateController: StatusBarStateController,
    val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    val mainExecutor: Executor,
    val logger: ScreenDecorationsLogger,
    val authController: AuthController,
) : ScreenDecorations.DisplayCutoutView(context, pos) {
    private var showScanningAnim = false
    private val rimPaint = Paint()
    private var rimProgress: Float = HIDDEN_CAMERA_PROTECTION_SCALE
    private var rimAnimator: AnimatorSet? = null
    private val rimRect = RectF()
    private var cameraProtectionColor = Color.BLACK

    var faceScanningAnimColor = Utils.getColorAttrDefaultColor(context,
        com.android.internal.R.attr.materialColorPrimaryFixed)
    private var cameraProtectionAnimator: ValueAnimator? = null
    var hideOverlayRunnable: Runnable? = null

    init {
        visibility = View.INVISIBLE // only show this view when face scanning is happening
    }

    override fun setColor(color: Int) {
        cameraProtectionColor = color
        invalidate()
    }

    override fun drawCutoutProtection(canvas: Canvas) {
        if (protectionRect.isEmpty) {
            return
        }
        if (rimProgress > HIDDEN_RIM_SCALE) {
            drawFaceScanningRim(canvas)
        }
        if (cameraProtectionProgress > HIDDEN_CAMERA_PROTECTION_SCALE) {
            drawCameraProtection(canvas)
        }
    }

    override fun enableShowProtection(isCameraActive: Boolean) {
        val scanningAnimationRequiredWhenCameraActive =
                keyguardUpdateMonitor.isFaceDetectionRunning || authController.isShowing
        val faceAuthSucceeded = keyguardUpdateMonitor.isFaceAuthenticated
        val showScanningAnimationNow = scanningAnimationRequiredWhenCameraActive && isCameraActive
        if (showScanningAnimationNow == showScanningAnim) {
            return
        }
        logger.cameraProtectionShownOrHidden(
                showScanningAnimationNow,
                keyguardUpdateMonitor.isFaceDetectionRunning,
                authController.isShowing,
                faceAuthSucceeded,
                isCameraActive,
                showScanningAnim)
        showScanningAnim = showScanningAnimationNow
        updateProtectionBoundingPath()
        // Delay the relayout until the end of the animation when hiding,
        // otherwise we'd clip it.
        if (showScanningAnim) {
            visibility = View.VISIBLE
            requestLayout()
        }

        cameraProtectionAnimator?.cancel()
        cameraProtectionAnimator = ValueAnimator.ofFloat(cameraProtectionProgress,
                if (showScanningAnimationNow) SHOW_CAMERA_PROTECTION_SCALE
                else HIDDEN_CAMERA_PROTECTION_SCALE).apply {
            startDelay =
                    if (showScanningAnim) 0
                    else if (faceAuthSucceeded) PULSE_SUCCESS_DISAPPEAR_DURATION
                    else PULSE_ERROR_DISAPPEAR_DURATION
            duration =
                    if (showScanningAnim) CAMERA_PROTECTION_APPEAR_DURATION
                    else if (faceAuthSucceeded) CAMERA_PROTECTION_SUCCESS_DISAPPEAR_DURATION
                    else CAMERA_PROTECTION_ERROR_DISAPPEAR_DURATION
            interpolator =
                    if (showScanningAnim) Interpolators.STANDARD_ACCELERATE
                    else if (faceAuthSucceeded) Interpolators.STANDARD
                    else Interpolators.STANDARD_DECELERATE
            addUpdateListener(this@FaceScanningOverlay::updateCameraProtectionProgress)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cameraProtectionAnimator = null
                    if (!showScanningAnim) {
                        hide()
                    }
                }
            })
        }

        rimAnimator?.cancel()
        rimAnimator = if (showScanningAnim) {
            createFaceScanningRimAnimator()
        } else if (faceAuthSucceeded) {
            createFaceSuccessRimAnimator()
        } else {
            createFaceNotSuccessRimAnimator()
        }
        rimAnimator?.apply {
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rimAnimator = null
                    if (!showScanningAnim) {
                        requestLayout()
                    }
                }
            })
        }
        rimAnimator?.start()
    }

    override fun updateVisOnUpdateCutout(): Boolean {
        return false // instead, we always update the visibility whenever face scanning starts/ends
    }

    override fun updateProtectionBoundingPath() {
        super.updateProtectionBoundingPath()
        rimRect.set(protectionRect)
        rimRect.scale(rimProgress)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (mBounds.isEmpty()) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if (showScanningAnim) {
            // Make sure that our measured height encompasses the extra space for the animation
            mTotalBounds.set(mBoundingRect)
            mTotalBounds.union(
                rimRect.left.toInt(),
                rimRect.top.toInt(),
                rimRect.right.toInt(),
                rimRect.bottom.toInt())
            val measuredWidth = resolveSizeAndState(mTotalBounds.width(), widthMeasureSpec, 0)
            val measuredHeight = resolveSizeAndState(mTotalBounds.height(), heightMeasureSpec, 0)
            logger.boundingRect(rimRect, "onMeasure: Face scanning animation")
            logger.boundingRect(mBoundingRect, "onMeasure: Display cutout view bounding rect")
            logger.boundingRect(mTotalBounds, "onMeasure: TotalBounds")
            logger.onMeasureDimensions(widthMeasureSpec,
                    heightMeasureSpec,
                    measuredWidth,
                    measuredHeight)
            setMeasuredDimension(measuredWidth, measuredHeight)
        } else {
            setMeasuredDimension(
                resolveSizeAndState(mBoundingRect.width(), widthMeasureSpec, 0),
                resolveSizeAndState(mBoundingRect.height(), heightMeasureSpec, 0))
        }
    }

    private fun drawFaceScanningRim(canvas: Canvas) {
        val rimPath = Path(protectionPath)
        scalePath(rimPath, rimProgress)
        rimPaint.style = Paint.Style.FILL
        val rimPaintAlpha = rimPaint.alpha
        rimPaint.color = ColorUtils.blendARGB(
            faceScanningAnimColor,
            Color.WHITE,
            statusBarStateController.dozeAmount
        )
        rimPaint.alpha = rimPaintAlpha
        canvas.drawPath(rimPath, rimPaint)
    }

    private fun drawCameraProtection(canvas: Canvas) {
        val scaledProtectionPath = Path(protectionPath)
        scalePath(scaledProtectionPath, cameraProtectionProgress)
        paint.style = Paint.Style.FILL
        paint.color = cameraProtectionColor
        canvas.drawPath(scaledProtectionPath, paint)
    }

    private fun createFaceSuccessRimAnimator(): AnimatorSet {
        val rimSuccessAnimator = AnimatorSet()
        rimSuccessAnimator.playTogether(
            createRimDisappearAnimator(
                PULSE_RADIUS_SUCCESS,
                PULSE_SUCCESS_DISAPPEAR_DURATION,
                Interpolators.STANDARD_DECELERATE
            ),
            createSuccessOpacityAnimator(),
        )
        return AnimatorSet().apply {
            playTogether(rimSuccessAnimator, cameraProtectionAnimator)
        }
    }

    private fun createFaceNotSuccessRimAnimator(): AnimatorSet {
        return AnimatorSet().apply {
            playTogether(
                createRimDisappearAnimator(
                    SHOW_CAMERA_PROTECTION_SCALE,
                    PULSE_ERROR_DISAPPEAR_DURATION,
                    Interpolators.STANDARD
                ),
                cameraProtectionAnimator,
            )
        }
    }

    private fun createRimDisappearAnimator(
        endValue: Float,
        animDuration: Long,
        timeInterpolator: TimeInterpolator
    ): ValueAnimator {
        return ValueAnimator.ofFloat(rimProgress, endValue).apply {
            duration = animDuration
            interpolator = timeInterpolator
            addUpdateListener(this@FaceScanningOverlay::updateRimProgress)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rimProgress = HIDDEN_RIM_SCALE
                    invalidate()
                }
            })
        }
    }

    private fun createSuccessOpacityAnimator(): ValueAnimator {
        return ValueAnimator.ofInt(255, 0).apply {
            duration = PULSE_SUCCESS_DISAPPEAR_DURATION
            interpolator = Interpolators.LINEAR
            addUpdateListener(this@FaceScanningOverlay::updateRimAlpha)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rimPaint.alpha = 255
                    invalidate()
                }
            })
        }
    }

    private fun createFaceScanningRimAnimator(): AnimatorSet {
        return AnimatorSet().apply {
            playSequentially(
                    cameraProtectionAnimator,
                    createRimAppearAnimator(),
            )
        }
    }

    private fun createRimAppearAnimator(): ValueAnimator {
        return ValueAnimator.ofFloat(
            SHOW_CAMERA_PROTECTION_SCALE,
            PULSE_RADIUS_OUT
        ).apply {
            duration = PULSE_APPEAR_DURATION
            interpolator = Interpolators.STANDARD_DECELERATE
            addUpdateListener(this@FaceScanningOverlay::updateRimProgress)
        }
    }

    private fun hide() {
        visibility = INVISIBLE
        hideOverlayRunnable?.run()
        hideOverlayRunnable = null
        requestLayout()
    }

    private fun updateRimProgress(animator: ValueAnimator) {
        rimProgress = animator.animatedValue as Float
        invalidate()
    }

    private fun updateCameraProtectionProgress(animator: ValueAnimator) {
        cameraProtectionProgress = animator.animatedValue as Float
        invalidate()
    }

    private fun updateRimAlpha(animator: ValueAnimator) {
        rimPaint.alpha = animator.animatedValue as Int
        invalidate()
    }

    companion object {
        private const val HIDDEN_RIM_SCALE = HIDDEN_CAMERA_PROTECTION_SCALE
        private const val SHOW_CAMERA_PROTECTION_SCALE = 1f

        private const val PULSE_RADIUS_OUT = 1.125f
        private const val PULSE_RADIUS_SUCCESS = 1.25f

        private const val CAMERA_PROTECTION_APPEAR_DURATION = 250L
        private const val PULSE_APPEAR_DURATION = 250L // without start delay

        private const val PULSE_SUCCESS_DISAPPEAR_DURATION = 400L
        private const val CAMERA_PROTECTION_SUCCESS_DISAPPEAR_DURATION = 500L // without start delay

        private const val PULSE_ERROR_DISAPPEAR_DURATION = 200L
        private const val CAMERA_PROTECTION_ERROR_DISAPPEAR_DURATION = 300L // without start delay

        private fun scalePath(path: Path, scalingFactor: Float) {
            val scaleMatrix = Matrix().apply {
                val boundingRectangle = RectF()
                path.computeBounds(boundingRectangle, true)
                setScale(
                    scalingFactor, scalingFactor,
                    boundingRectangle.centerX(), boundingRectangle.centerY()
                )
            }
            path.transform(scaleMatrix)
        }
    }

    override fun dump(pw: PrintWriter) {
        val ipw = pw.asIndenting()
        ipw.increaseIndent()
        ipw.println("FaceScanningOverlay:")
        super.dump(ipw)
        ipw.println("rimProgress=$rimProgress")
        ipw.println("rimRect=$rimRect")
        ipw.println("this=$this")
        ipw.decreaseIndent()
    }
}
