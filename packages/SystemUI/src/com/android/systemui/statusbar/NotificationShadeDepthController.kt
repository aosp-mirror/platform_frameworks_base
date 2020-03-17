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
    }

    lateinit var root: View
    private var blurRoot: View? = null
    private var keyguardAnimator: Animator? = null
    private var notificationAnimator: Animator? = null
    private var updateScheduled: Boolean = false
    private var shadeExpansion = 0f
    @VisibleForTesting
    var shadeSpring = SpringAnimation(this, object :
            FloatPropertyCompat<NotificationShadeDepthController>("shadeBlurRadius") {
        override fun setValue(rect: NotificationShadeDepthController?, value: Float) {
            shadeBlurRadius = value.toInt()
        }

        override fun getValue(rect: NotificationShadeDepthController?): Float {
            return shadeBlurRadius.toFloat()
        }
    })
    private val zoomInterpolator = Interpolators.ACCELERATE_DECELERATE

    /**
     * Radius that we're animating to.
     */
    private var pendingShadeBlurRadius = -1

    /**
     * Shade blur radius on the current frame.
     */
    private var shadeBlurRadius = 0
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
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
    private var globalDialogVisibility = 0f

    /**
     * Callback that updates the window blur value and is called only once per frame.
     */
    private val updateBlurCallback = Choreographer.FrameCallback {
        updateScheduled = false

        val blur = max(shadeBlurRadius,
                max(wakeAndUnlockBlurRadius, blurUtils.blurRadiusOfRatio(globalDialogVisibility)))
        blurUtils.applyBlur(blurRoot?.viewRootImpl ?: root.viewRootImpl, blur)
        val rawZoom = max(blurUtils.ratioOfBlurRadius(blur), globalDialogVisibility)
        wallpaperManager.setWallpaperZoomOut(root.windowToken,
                zoomInterpolator.getInterpolation(rawZoom))
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
            if (isDozing && shadeSpring.isRunning) {
                shadeSpring.skipToEnd()
            }
        }
    }

    init {
        dumpManager.registerDumpable(javaClass.name, this)
        if (WAKE_UP_ANIMATION_ENABLED) {
            keyguardStateController.addCallback(keyguardStateCallback)
        }
        shadeSpring.spring = SpringForce(0.0f)
        shadeSpring.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        shadeSpring.spring.stiffness = SpringForce.STIFFNESS_LOW
        shadeSpring.addEndListener { _, _, _, _ -> pendingShadeBlurRadius = -1 }
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

        if (pendingShadeBlurRadius == newBlur) {
            return
        }
        pendingShadeBlurRadius = newBlur
        shadeSpring.animateToFinalPosition(newBlur.toFloat())
    }

    private fun scheduleUpdate(viewToBlur: View? = null) {
        if (updateScheduled) {
            return
        }
        updateScheduled = true
        blurRoot = viewToBlur
        choreographer.postFrameCallback(updateBlurCallback)
    }

    fun updateGlobalDialogVisibility(visibility: Float, dialogView: View) {
        if (visibility == globalDialogVisibility) {
            return
        }
        globalDialogVisibility = visibility
        scheduleUpdate(dialogView)
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").let {
            it.println("StatusBarWindowBlurController:")
            it.increaseIndent()
            it.println("shadeBlurRadius: $shadeBlurRadius")
            it.println("wakeAndUnlockBlur: $wakeAndUnlockBlurRadius")
        }
    }
}
