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
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.biometrics.BiometricSourceType
import android.view.View
import androidx.core.graphics.ColorUtils
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.settingslib.Utils
import com.android.systemui.animation.Interpolators
import com.android.systemui.plugins.statusbar.StatusBarStateController
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
    val mainExecutor: Executor
) : ScreenDecorations.DisplayCutoutView(context, pos) {
    private var showScanningAnim = false
    private val rimPaint = Paint()
    private var rimProgress: Float = HIDDEN_CAMERA_PROTECTION_SCALE
    private var rimAnimator: AnimatorSet? = null
    private val rimRect = RectF()
    private var cameraProtectionColor = Color.BLACK
    var faceScanningAnimColor = Utils.getColorAttrDefaultColor(context,
            com.android.systemui.R.attr.wallpaperTextColorAccent)
    private var cameraProtectionAnimator: ValueAnimator? = null
    var hideOverlayRunnable: Runnable? = null
    var faceAuthSucceeded = false

    init {
        visibility = View.INVISIBLE // only show this view when face scanning is happening
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mainExecutor.execute {
            keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainExecutor.execute {
            keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        }
    }

    override fun setColor(color: Int) {
        cameraProtectionColor = color
        invalidate()
    }

    override fun drawCutoutProtection(canvas: Canvas) {
        if (rimProgress > HIDDEN_RIM_SCALE && !protectionRect.isEmpty) {
            val rimPath = Path(protectionPath)
            val scaleMatrix = Matrix().apply {
                val rimBounds = RectF()
                rimPath.computeBounds(rimBounds, true)
                setScale(rimProgress, rimProgress, rimBounds.centerX(), rimBounds.centerY())
            }
            rimPath.transform(scaleMatrix)
            rimPaint.style = Paint.Style.FILL
            val rimPaintAlpha = rimPaint.alpha
            rimPaint.color = ColorUtils.blendARGB(
                    faceScanningAnimColor,
                    Color.WHITE,
                    statusBarStateController.dozeAmount)
            rimPaint.alpha = rimPaintAlpha
            canvas.drawPath(rimPath, rimPaint)
        }

        if (cameraProtectionProgress > HIDDEN_CAMERA_PROTECTION_SCALE &&
                !protectionRect.isEmpty) {
            val scaledProtectionPath = Path(protectionPath)
            val scaleMatrix = Matrix().apply {
                val protectionPathRect = RectF()
                scaledProtectionPath.computeBounds(protectionPathRect, true)
                setScale(cameraProtectionProgress, cameraProtectionProgress,
                        protectionPathRect.centerX(), protectionPathRect.centerY())
            }
            scaledProtectionPath.transform(scaleMatrix)
            paint.style = Paint.Style.FILL
            paint.color = cameraProtectionColor
            canvas.drawPath(scaledProtectionPath, paint)
        }
    }

    override fun updateVisOnUpdateCutout(): Boolean {
        return false // instead, we always update the visibility whenever face scanning starts/ends
    }

    override fun enableShowProtection(show: Boolean) {
        val showScanningAnimNow = keyguardUpdateMonitor.isFaceScanning && show
        if (showScanningAnimNow == showScanningAnim) {
            return
        }
        showScanningAnim = showScanningAnimNow
        updateProtectionBoundingPath()
        // Delay the relayout until the end of the animation when hiding,
        // otherwise we'd clip it.
        if (showScanningAnim) {
            visibility = View.VISIBLE
            requestLayout()
        }

        cameraProtectionAnimator?.cancel()
        cameraProtectionAnimator = ValueAnimator.ofFloat(cameraProtectionProgress,
                if (showScanningAnimNow) SHOW_CAMERA_PROTECTION_SCALE
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
            addUpdateListener(ValueAnimator.AnimatorUpdateListener {
                animation: ValueAnimator ->
                cameraProtectionProgress = animation.animatedValue as Float
                invalidate()
            })
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cameraProtectionAnimator = null
                    if (!showScanningAnim) {
                        visibility = View.INVISIBLE
                        hideOverlayRunnable?.run()
                        hideOverlayRunnable = null
                        requestLayout()
                    }
                }
            })
        }

        rimAnimator?.cancel()
        rimAnimator = AnimatorSet().apply {
            if (showScanningAnim) {
                val rimAppearAnimator = ValueAnimator.ofFloat(SHOW_CAMERA_PROTECTION_SCALE,
                        PULSE_RADIUS_OUT).apply {
                    duration = PULSE_APPEAR_DURATION
                    interpolator = Interpolators.STANDARD_DECELERATE
                    addUpdateListener(ValueAnimator.AnimatorUpdateListener {
                        animation: ValueAnimator ->
                        rimProgress = animation.animatedValue as Float
                        invalidate()
                    })
                }

                // animate in camera protection, rim, and then pulse in/out
                playSequentially(cameraProtectionAnimator, rimAppearAnimator,
                        createPulseAnimator(), createPulseAnimator(),
                        createPulseAnimator(), createPulseAnimator(),
                        createPulseAnimator(), createPulseAnimator())
            } else {
                val rimDisappearAnimator = ValueAnimator.ofFloat(
                        rimProgress,
                        if (faceAuthSucceeded) PULSE_RADIUS_SUCCESS
                        else SHOW_CAMERA_PROTECTION_SCALE
                ).apply {
                    duration =
                            if (faceAuthSucceeded) PULSE_SUCCESS_DISAPPEAR_DURATION
                            else PULSE_ERROR_DISAPPEAR_DURATION
                    interpolator =
                            if (faceAuthSucceeded) Interpolators.STANDARD_DECELERATE
                            else Interpolators.STANDARD
                    addUpdateListener(ValueAnimator.AnimatorUpdateListener {
                        animation: ValueAnimator ->
                        rimProgress = animation.animatedValue as Float
                        invalidate()
                    })
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            rimProgress = HIDDEN_RIM_SCALE
                            invalidate()
                        }
                    })
                }
                if (faceAuthSucceeded) {
                    val successOpacityAnimator = ValueAnimator.ofInt(255, 0).apply {
                        duration = PULSE_SUCCESS_DISAPPEAR_DURATION
                        interpolator = Interpolators.LINEAR
                        addUpdateListener(ValueAnimator.AnimatorUpdateListener {
                            animation: ValueAnimator ->
                            rimPaint.alpha = animation.animatedValue as Int
                            invalidate()
                        })
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                rimPaint.alpha = 255
                                invalidate()
                            }
                        })
                    }
                    val rimSuccessAnimator = AnimatorSet()
                    rimSuccessAnimator.playTogether(rimDisappearAnimator, successOpacityAnimator)
                    playTogether(rimSuccessAnimator, cameraProtectionAnimator)
                } else {
                    playTogether(rimDisappearAnimator, cameraProtectionAnimator)
                }
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rimAnimator = null
                    if (!showScanningAnim) {
                        requestLayout()
                    }
                }
            })
            start()
        }
    }

    fun createPulseAnimator(): AnimatorSet {
        return AnimatorSet().apply {
            val pulseInwards = ValueAnimator.ofFloat(
                    PULSE_RADIUS_OUT, PULSE_RADIUS_IN).apply {
                duration = PULSE_DURATION_INWARDS
                interpolator = Interpolators.STANDARD
                addUpdateListener(ValueAnimator.AnimatorUpdateListener {
                    animation: ValueAnimator ->
                    rimProgress = animation.animatedValue as Float
                    invalidate()
                })
            }
            val pulseOutwards = ValueAnimator.ofFloat(
                    PULSE_RADIUS_IN, PULSE_RADIUS_OUT).apply {
                duration = PULSE_DURATION_OUTWARDS
                interpolator = Interpolators.STANDARD
                addUpdateListener(ValueAnimator.AnimatorUpdateListener {
                    animation: ValueAnimator ->
                    rimProgress = animation.animatedValue as Float
                    invalidate()
                })
            }
            playSequentially(pulseInwards, pulseOutwards)
        }
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
            mTotalBounds.union(mBoundingRect)
            mTotalBounds.union(
                    rimRect.left.toInt(),
                    rimRect.top.toInt(),
                    rimRect.right.toInt(),
                    rimRect.bottom.toInt())
            setMeasuredDimension(
                    resolveSizeAndState(mTotalBounds.width(), widthMeasureSpec, 0),
                    resolveSizeAndState(mTotalBounds.height(), heightMeasureSpec, 0))
        } else {
            setMeasuredDimension(
                    resolveSizeAndState(mBoundingRect.width(), widthMeasureSpec, 0),
                    resolveSizeAndState(mBoundingRect.height(), heightMeasureSpec, 0))
        }
    }

    private val keyguardUpdateMonitorCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onBiometricAuthenticated(
            userId: Int,
            biometricSourceType: BiometricSourceType?,
            isStrongBiometric: Boolean
        ) {
            if (biometricSourceType == BiometricSourceType.FACE) {
                post {
                    faceAuthSucceeded = true
                    enableShowProtection(true)
                }
            }
        }

        override fun onBiometricAcquired(
            biometricSourceType: BiometricSourceType?,
            acquireInfo: Int
        ) {
            if (biometricSourceType == BiometricSourceType.FACE) {
                post {
                    faceAuthSucceeded = false // reset
                }
            }
        }

        override fun onBiometricAuthFailed(biometricSourceType: BiometricSourceType?) {
            if (biometricSourceType == BiometricSourceType.FACE) {
                post {
                    faceAuthSucceeded = false
                    enableShowProtection(false)
                }
            }
        }

        override fun onBiometricError(
            msgId: Int,
            errString: String?,
            biometricSourceType: BiometricSourceType?
        ) {
            if (biometricSourceType == BiometricSourceType.FACE) {
                post {
                    faceAuthSucceeded = false
                    enableShowProtection(false)
                }
            }
        }
    }

    companion object {
        private const val HIDDEN_RIM_SCALE = HIDDEN_CAMERA_PROTECTION_SCALE
        private const val SHOW_CAMERA_PROTECTION_SCALE = 1f

        private const val PULSE_RADIUS_IN = 1.1f
        private const val PULSE_RADIUS_OUT = 1.125f
        private const val PULSE_RADIUS_SUCCESS = 1.25f

        private const val CAMERA_PROTECTION_APPEAR_DURATION = 250L
        private const val PULSE_APPEAR_DURATION = 250L // without start delay

        private const val PULSE_DURATION_INWARDS = 500L
        private const val PULSE_DURATION_OUTWARDS = 500L

        private const val PULSE_SUCCESS_DISAPPEAR_DURATION = 400L
        private const val CAMERA_PROTECTION_SUCCESS_DISAPPEAR_DURATION = 500L // without start delay

        private const val PULSE_ERROR_DISAPPEAR_DURATION = 200L
        private const val CAMERA_PROTECTION_ERROR_DISAPPEAR_DURATION = 300L // without start delay
    }
}
