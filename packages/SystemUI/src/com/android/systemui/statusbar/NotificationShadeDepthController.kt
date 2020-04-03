/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.util.IndentingPrintWriter
import com.android.systemui.Dumpable
import com.android.systemui.Interpolators
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK
import com.android.systemui.statusbar.phone.NotificationShadeWindowController
import com.android.systemui.statusbar.phone.PanelExpansionListener
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Controller responsible for statusbar window blur.
 */
@Singleton
class NotificationShadeDepthController @Inject constructor(
    private val statusBarStateController: StatusBarStateController,
    private val blurUtils: BlurUtils,
    private val biometricUnlockController: BiometricUnlockController,
    private val keyguardStateController: KeyguardStateController,
    private val choreographer: Choreographer,
    private val wallpaperManager: WallpaperManager,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    dumpManager: DumpManager
) : PanelExpansionListener, Dumpable {
    companion object {
        private const val WAKE_UP_ANIMATION_ENABLED = true
        private const val TAG = "DepthController"
    }

    lateinit var root: View
    private var blurRoot: View? = null
    private var keyguardAnimator: Animator? = null
    private var notificationAnimator: Animator? = null
    private var updateScheduled: Boolean = false
    private var shadeExpansion = 0f
    @VisibleForTesting
    var shadeSpring = DepthAnimation()
    @VisibleForTesting
    var globalActionsSpring = DepthAnimation()

    @VisibleForTesting
    var brightnessMirrorSpring = DepthAnimation()
    var brightnessMirrorVisible: Boolean = false
        set(value) {
            field = value
            brightnessMirrorSpring.animateTo(if (value) blurUtils.blurRadiusOfRatio(1f)
                else 0)
        }

    /**
     * Blur radius of the wake-up animation on this frame.
     */
    private var wakeAndUnlockBlurRadius = 0
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    /**
     * Callback that updates the window blur value and is called only once per frame.
     */
    @VisibleForTesting
    val updateBlurCallback = Choreographer.FrameCallback {
        updateScheduled = false

        var shadeRadius = max(shadeSpring.radius, wakeAndUnlockBlurRadius)
        shadeRadius = (shadeRadius * (1f - brightnessMirrorSpring.ratio)).toInt()
        val blur = max(shadeRadius, globalActionsSpring.radius)
        blurUtils.applyBlur(blurRoot?.viewRootImpl ?: root.viewRootImpl, blur)
        try {
            wallpaperManager.setWallpaperZoomOut(root.windowToken,
                    blurUtils.ratioOfBlurRadius(blur))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Can't set zoom. Window is gone: ${root.windowToken}", e)
        }
        notificationShadeWindowController.setBackgroundBlurRadius(blur)
    }

    /**
     * Animate blurs when unlocking.
     */
    private val keyguardStateCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardFadingAwayChanged() {
            if (!keyguardStateController.isKeyguardFadingAway ||
                    biometricUnlockController.mode != MODE_WAKE_AND_UNLOCK) {
                return
            }

            keyguardAnimator?.cancel()
            keyguardAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = keyguardStateController.keyguardFadingAwayDuration
                startDelay = keyguardStateController.keyguardFadingAwayDelay
                interpolator = Interpolators.DECELERATE_QUINT
                addUpdateListener { animation: ValueAnimator ->
                    wakeAndUnlockBlurRadius =
                            blurUtils.blurRadiusOfRatio(animation.animatedValue as Float)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        keyguardAnimator = null
                        scheduleUpdate()
                    }
                })
                start()
            }
        }

        override fun onKeyguardShowingChanged() {
            if (keyguardStateController.isShowing) {
                keyguardAnimator?.cancel()
                notificationAnimator?.cancel()
            }
        }
    }

    private val statusBarStateCallback = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {
            updateShadeBlur()
        }

        override fun onDozingChanged(isDozing: Boolean) {
            if (isDozing) {
                shadeSpring.finishIfRunning()
                globalActionsSpring.finishIfRunning()
                brightnessMirrorSpring.finishIfRunning()
            }
        }
    }

    init {
        dumpManager.registerDumpable(javaClass.name, this)
        if (WAKE_UP_ANIMATION_ENABLED) {
            keyguardStateController.addCallback(keyguardStateCallback)
        }
        statusBarStateController.addCallback(statusBarStateCallback)
    }

    /**
     * Update blurs when pulling down the shade
     */
    override fun onPanelExpansionChanged(expansion: Float, tracking: Boolean) {
        if (expansion == shadeExpansion) {
            return
        }
        shadeExpansion = expansion
        updateShadeBlur()
    }

    private fun updateShadeBlur() {
        var newBlur = 0
        if (statusBarStateController.state == StatusBarState.SHADE) {
            newBlur = blurUtils.blurRadiusOfRatio(shadeExpansion)
        }

        shadeSpring.animateTo(newBlur)
    }

    private fun scheduleUpdate(viewToBlur: View? = null) {
        if (updateScheduled) {
            return
        }
        updateScheduled = true
        blurRoot = viewToBlur
        choreographer.postFrameCallback(updateBlurCallback)
    }

    fun updateGlobalDialogVisibility(visibility: Float, dialogView: View?) {
        globalActionsSpring.animateTo(blurUtils.blurRadiusOfRatio(visibility), dialogView)
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").let {
            it.println("StatusBarWindowBlurController:")
            it.increaseIndent()
            it.println("shadeRadius: ${shadeSpring.radius}")
            it.println("globalActionsRadius: ${globalActionsSpring.radius}")
            it.println("brightnessMirrorRadius: ${brightnessMirrorSpring.radius}")
            it.println("wakeAndUnlockBlur: $wakeAndUnlockBlurRadius")
        }
    }

    /**
     * Animation helper that smoothly animates the depth using a spring and deals with frame
     * invalidation.
     */
    inner class DepthAnimation() {
        /**
         * Blur radius visible on the UI, in pixels.
         */
        var radius = 0

        /**
         * Depth ratio of the current blur radius.
         */
        val ratio
            get() = blurUtils.ratioOfBlurRadius(radius)

        /**
         * Radius that we're animating to.
         */
        private var pendingRadius = -1

        /**
         * View on {@link Surface} that wants depth.
         */
        private var view: View? = null

        private var springAnimation = SpringAnimation(this, object :
            FloatPropertyCompat<DepthAnimation>("blurRadius") {
            override fun setValue(rect: DepthAnimation?, value: Float) {
                radius = value.toInt()
                scheduleUpdate(view)
            }

            override fun getValue(rect: DepthAnimation?): Float {
                return radius.toFloat()
            }
        })

        init {
            springAnimation.spring = SpringForce(0.0f)
            springAnimation.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            springAnimation.spring.stiffness = SpringForce.STIFFNESS_HIGH
            springAnimation.addEndListener { _, _, _, _ -> pendingRadius = -1 }
        }

        fun animateTo(newRadius: Int, viewToBlur: View? = null) {
            if (pendingRadius == newRadius && view == viewToBlur) {
                return
            }
            view = viewToBlur
            pendingRadius = newRadius
            springAnimation.animateToFinalPosition(newRadius.toFloat())
        }

        fun finishIfRunning() {
            if (springAnimation.isRunning) {
                springAnimation.skipToEnd()
            }
        }
    }
}
