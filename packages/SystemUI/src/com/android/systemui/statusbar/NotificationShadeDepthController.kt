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
import android.os.SystemClock
import android.util.IndentingPrintWriter
import android.util.Log
import android.util.MathUtils
import android.view.Choreographer
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.Dumpable
import com.android.systemui.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.PanelExpansionListener
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.sign

/**
 * Controller responsible for statusbar window blur.
 */
@SysUISingleton
class NotificationShadeDepthController @Inject constructor(
    private val statusBarStateController: StatusBarStateController,
    private val blurUtils: BlurUtils,
    private val biometricUnlockController: BiometricUnlockController,
    private val keyguardStateController: KeyguardStateController,
    private val choreographer: Choreographer,
    private val wallpaperManager: WallpaperManager,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val dozeParameters: DozeParameters,
    dumpManager: DumpManager
) : PanelExpansionListener, Dumpable {
    companion object {
        private const val WAKE_UP_ANIMATION_ENABLED = true
        private const val VELOCITY_SCALE = 100f
        private const val MAX_VELOCITY = 3000f
        private const val MIN_VELOCITY = -MAX_VELOCITY
        private const val INTERACTION_BLUR_FRACTION = 0.4f
        private const val ANIMATION_BLUR_FRACTION = 1f - INTERACTION_BLUR_FRACTION
        private const val TAG = "DepthController"
    }

    lateinit var root: View
    private var blurRoot: View? = null
    private var keyguardAnimator: Animator? = null
    private var notificationAnimator: Animator? = null
    private var updateScheduled: Boolean = false
    private var shadeExpansion = 0f
    private var ignoreShadeBlurUntilHidden: Boolean = false
    private var isClosed: Boolean = true
    private var isOpen: Boolean = false
    private var isBlurred: Boolean = false
    private var listeners = mutableListOf<DepthListener>()

    private var prevTracking: Boolean = false
    private var prevTimestamp: Long = -1
    private var prevShadeDirection = 0
    private var prevShadeVelocity = 0f

    @VisibleForTesting
    var shadeSpring = DepthAnimation()
    var shadeAnimation = DepthAnimation()

    @VisibleForTesting
    var globalActionsSpring = DepthAnimation()
    var showingHomeControls: Boolean = false

    @VisibleForTesting
    var brightnessMirrorSpring = DepthAnimation()
    var brightnessMirrorVisible: Boolean = false
        set(value) {
            field = value
            brightnessMirrorSpring.animateTo(if (value) blurUtils.blurRadiusOfRatio(1f)
                else 0)
        }

    /**
     * When launching an app from the shade, the animations progress should affect how blurry the
     * shade is, overriding the expansion amount.
     */
    var notificationLaunchAnimationParams: ActivityLaunchAnimator.ExpandAnimationParameters? = null
        set(value) {
            field = value
            if (value != null) {
                scheduleUpdate()
                return
            }

            if (shadeSpring.radius == 0 && shadeAnimation.radius == 0) {
                return
            }
            ignoreShadeBlurUntilHidden = true
            shadeSpring.animateTo(0)
            shadeSpring.finishIfRunning()

            shadeAnimation.animateTo(0)
            shadeAnimation.finishIfRunning()
        }

    /**
     * Force stop blur effect when necessary.
     */
    private var scrimsVisible: Boolean = false
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

    /**
     * Callback that updates the window blur value and is called only once per frame.
     */
    @VisibleForTesting
    val updateBlurCallback = Choreographer.FrameCallback {
        updateScheduled = false
        val normalizedBlurRadius = MathUtils.constrain(shadeAnimation.radius,
                blurUtils.minBlurRadius, blurUtils.maxBlurRadius)
        val combinedBlur = (shadeSpring.radius * INTERACTION_BLUR_FRACTION +
                normalizedBlurRadius * ANIMATION_BLUR_FRACTION).toInt()
        var shadeRadius = max(combinedBlur, wakeAndUnlockBlurRadius).toFloat()
        shadeRadius *= 1f - brightnessMirrorSpring.ratio
        val launchProgress = notificationLaunchAnimationParams?.linearProgress ?: 0f
        shadeRadius *= (1f - launchProgress) * (1f - launchProgress)

        if (ignoreShadeBlurUntilHidden) {
            if (shadeRadius == 0f) {
                ignoreShadeBlurUntilHidden = false
            } else {
                shadeRadius = 0f
            }
        }

        // Home controls have black background, this means that we should not have blur when they
        // are fully visible, otherwise we'll enter Client Composition unnecessarily.
        var globalActionsRadius = globalActionsSpring.radius
        if (showingHomeControls) {
            globalActionsRadius = 0
        }
        var blur = max(shadeRadius.toInt(), globalActionsRadius)

        // Make blur be 0 if it is necessary to stop blur effect.
        if (scrimsVisible) {
            blur = 0
        }

        blurUtils.applyBlur(blurRoot?.viewRootImpl ?: root.viewRootImpl, blur)
        val zoomOut = blurUtils.ratioOfBlurRadius(blur)
        try {
            if (root.isAttachedToWindow && root.windowToken != null) {
                wallpaperManager.setWallpaperZoomOut(root.windowToken, zoomOut)
            } else {
                Log.i(TAG, "Won't set zoom. Window not attached $root")
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Can't set zoom. Window is gone: ${root.windowToken}", e)
        }
        listeners.forEach {
            it.onWallpaperZoomOutChanged(zoomOut)
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
                // keyguardStateController.keyguardFadingAwayDuration might be zero when unlock by
                // fingerprint due to there is no window container, see AppTransition#goodToGo.
                // We use DozeParameters.wallpaperFadeOutDuration as an alternative.
                duration = dozeParameters.wallpaperFadeOutDuration
                startDelay = keyguardStateController.keyguardFadingAwayDelay
                interpolator = Interpolators.FAST_OUT_SLOW_IN
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
            updateShadeAnimationBlur(
                    shadeExpansion, prevTracking, prevShadeVelocity, prevShadeDirection)
            updateShadeBlur()
        }

        override fun onDozingChanged(isDozing: Boolean) {
            if (isDozing) {
                shadeSpring.finishIfRunning()
                shadeAnimation.finishIfRunning()
                globalActionsSpring.finishIfRunning()
                brightnessMirrorSpring.finishIfRunning()
            }
        }

        override fun onDozeAmountChanged(linear: Float, eased: Float) {
            wakeAndUnlockBlurRadius = blurUtils.blurRadiusOfRatio(eased)
        }
    }

    init {
        dumpManager.registerDumpable(javaClass.name, this)
        if (WAKE_UP_ANIMATION_ENABLED) {
            keyguardStateController.addCallback(keyguardStateCallback)
        }
        statusBarStateController.addCallback(statusBarStateCallback)
        notificationShadeWindowController.setScrimsVisibilityListener {
            // Stop blur effect when scrims is opaque to avoid unnecessary GPU composition.
            visibility -> scrimsVisible = visibility == ScrimController.OPAQUE
        }
        shadeAnimation.setStiffness(SpringForce.STIFFNESS_LOW)
        shadeAnimation.setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
    }

    fun addListener(listener: DepthListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: DepthListener) {
        listeners.remove(listener)
    }

    /**
     * Update blurs when pulling down the shade
     */
    override fun onPanelExpansionChanged(expansion: Float, tracking: Boolean) {
        val timestamp = SystemClock.elapsedRealtimeNanos()

        if (shadeExpansion == expansion && prevTracking == tracking) {
            prevTimestamp = timestamp
            return
        }

        var deltaTime = 1f
        if (prevTimestamp < 0) {
            prevTimestamp = timestamp
        } else {
            deltaTime = MathUtils.constrain(
                    ((timestamp - prevTimestamp) / 1E9).toFloat(), 0.00001f, 1f)
        }

        val diff = expansion - shadeExpansion
        val shadeDirection = sign(diff).toInt()
        val shadeVelocity = MathUtils.constrain(
            VELOCITY_SCALE * diff / deltaTime, MIN_VELOCITY, MAX_VELOCITY)
        updateShadeAnimationBlur(expansion, tracking, shadeVelocity, shadeDirection)

        prevShadeDirection = shadeDirection
        prevShadeVelocity = shadeVelocity
        shadeExpansion = expansion
        prevTracking = tracking
        prevTimestamp = timestamp

        updateShadeBlur()
    }

    private fun updateShadeAnimationBlur(
        expansion: Float,
        tracking: Boolean,
        velocity: Float,
        direction: Int
    ) {
        if (isOnKeyguardNotDismissing()) {
            if (expansion > 0f) {
                // Blur view if user starts animating in the shade.
                if (isClosed) {
                    animateBlur(true, velocity)
                    isClosed = false
                }

                // If we were blurring out and the user stopped the animation, blur view.
                if (tracking && !isBlurred) {
                    animateBlur(true, 0f)
                }

                // If shade is being closed and the user isn't interacting with it, un-blur.
                if (!tracking && direction < 0 && isBlurred) {
                    animateBlur(false, velocity)
                }

                if (expansion == 1f) {
                    if (!isOpen) {
                        isOpen = true
                        // If shade is open and view is not blurred, blur.
                        if (!isBlurred) {
                            animateBlur(true, velocity)
                        }
                    }
                } else {
                    isOpen = false
                }
                // Automatic animation when the user closes the shade.
            } else if (!isClosed) {
                isClosed = true
                // If shade is closed and view is not blurred, blur.
                if (isBlurred) {
                    animateBlur(false, velocity)
                }
            }
        } else {
            animateBlur(false, 0f)
            isClosed = true
            isOpen = false
        }
    }

    private fun animateBlur(blur: Boolean, velocity: Float) {
        isBlurred = blur

        val targetBlurNormalized = if (blur && isOnKeyguardNotDismissing()) {
            1f
        } else {
            0f
        }

        shadeAnimation.setStartVelocity(velocity)
        shadeAnimation.animateTo(blurUtils.blurRadiusOfRatio(targetBlurNormalized))
    }

    private fun updateShadeBlur() {
        var newBlur = 0
        if (isOnKeyguardNotDismissing()) {
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

    private fun isOnKeyguardNotDismissing(): Boolean {
        val state = statusBarStateController.state
        return (state == StatusBarState.SHADE || state == StatusBarState.SHADE_LOCKED) &&
                !keyguardStateController.isKeyguardFadingAway
    }

    fun updateGlobalDialogVisibility(visibility: Float, dialogView: View?) {
        globalActionsSpring.animateTo(blurUtils.blurRadiusOfRatio(visibility), dialogView)
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").let {
            it.println("StatusBarWindowBlurController:")
            it.increaseIndent()
            it.println("shadeRadius: ${shadeSpring.radius}")
            it.println("shadeAnimation: ${shadeAnimation.radius}")
            it.println("globalActionsRadius: ${globalActionsSpring.radius}")
            it.println("brightnessMirrorRadius: ${brightnessMirrorSpring.radius}")
            it.println("wakeAndUnlockBlur: $wakeAndUnlockBlurRadius")
            it.println("notificationLaunchAnimationProgress: " +
                    "${notificationLaunchAnimationParams?.linearProgress}")
            it.println("ignoreShadeBlurUntilHidden: $ignoreShadeBlurUntilHidden")
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

        fun setStiffness(stiffness: Float) {
            springAnimation.spring.stiffness = stiffness
        }

        fun setDampingRatio(dampingRation: Float) {
            springAnimation.spring.dampingRatio = dampingRation
        }

        fun setStartVelocity(velocity: Float) {
            springAnimation.setStartVelocity(velocity)
        }
    }

    /**
     * Invoked when changes are needed in z-space
     */
    interface DepthListener {
        /**
         * Current wallpaper zoom out, where 0 is the closest, and 1 the farthest
         */
        fun onWallpaperZoomOutChanged(zoomOut: Float)
    }
}
