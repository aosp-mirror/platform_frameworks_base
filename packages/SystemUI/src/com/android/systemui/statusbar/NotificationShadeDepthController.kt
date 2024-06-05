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
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.os.Trace
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
import com.android.app.animation.Interpolators
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionListener
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.LargeScreenUtils
import com.android.systemui.util.WallpaperController
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
    private val wallpaperController: WallpaperController,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val dozeParameters: DozeParameters,
    private val context: Context,
    dumpManager: DumpManager,
    configurationController: ConfigurationController
) : ShadeExpansionListener, Dumpable {
    companion object {
        private const val WAKE_UP_ANIMATION_ENABLED = true
        private const val VELOCITY_SCALE = 100f
        private const val MAX_VELOCITY = 3000f
        private const val MIN_VELOCITY = -MAX_VELOCITY
        private const val INTERACTION_BLUR_FRACTION = 0.8f
        private const val ANIMATION_BLUR_FRACTION = 1f - INTERACTION_BLUR_FRACTION
        private const val TAG = "DepthController"
    }

    lateinit var root: View
    private var keyguardAnimator: Animator? = null
    private var notificationAnimator: Animator? = null
    private var updateScheduled: Boolean = false
    @VisibleForTesting
    var shadeExpansion = 0f
    private var isClosed: Boolean = true
    private var isOpen: Boolean = false
    private var isBlurred: Boolean = false
    private var listeners = mutableListOf<DepthListener>()
    private var inSplitShade: Boolean = false

    private var prevTracking: Boolean = false
    private var prevTimestamp: Long = -1
    private var prevShadeDirection = 0
    private var prevShadeVelocity = 0f

    // Only for dumpsys
    private var lastAppliedBlur = 0

    // Shade expansion offset that happens when pulling down on a HUN.
    var panelPullDownMinFraction = 0f

    var shadeAnimation = DepthAnimation()

    @VisibleForTesting
    var brightnessMirrorSpring = DepthAnimation()
    var brightnessMirrorVisible: Boolean = false
        set(value) {
            field = value
            brightnessMirrorSpring.animateTo(if (value) blurUtils.blurRadiusOfRatio(1f).toInt()
                else 0)
        }

    var qsPanelExpansion = 0f
        set(value) {
            if (value.isNaN()) {
                Log.w(TAG, "Invalid qs expansion")
                return
            }
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    /**
     * How much we're transitioning to the full shade
     */
    var transitionToFullShadeProgress = 0f
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    /**
     * When launching an app from the shade, the animations progress should affect how blurry the
     * shade is, overriding the expansion amount.
     */
    var blursDisabledForAppLaunch: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            scheduleUpdate()

            if (shadeExpansion == 0f && shadeAnimation.radius == 0f) {
                return
            }
            // Do not remove blurs when we're re-enabling them
            if (!value) {
                return
            }

            shadeAnimation.animateTo(0)
            shadeAnimation.finishIfRunning()
        }

    /**
     * We're unlocking, and should not blur as the panel expansion changes.
     */
    var blursDisabledForUnlock: Boolean = false
    set(value) {
        if (field == value) return
        field = value
        scheduleUpdate()
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
    private var wakeAndUnlockBlurRadius = 0f
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    private fun computeBlurAndZoomOut(): Pair<Int, Float> {
        val animationRadius = MathUtils.constrain(shadeAnimation.radius,
                blurUtils.minBlurRadius.toFloat(), blurUtils.maxBlurRadius.toFloat())
        val expansionRadius = blurUtils.blurRadiusOfRatio(
                ShadeInterpolation.getNotificationScrimAlpha(
                        if (shouldApplyShadeBlur()) shadeExpansion else 0f))
        var combinedBlur = (expansionRadius * INTERACTION_BLUR_FRACTION +
                animationRadius * ANIMATION_BLUR_FRACTION)
        val qsExpandedRatio = ShadeInterpolation.getNotificationScrimAlpha(qsPanelExpansion) *
                shadeExpansion
        combinedBlur = max(combinedBlur, blurUtils.blurRadiusOfRatio(qsExpandedRatio))
        combinedBlur = max(combinedBlur, blurUtils.blurRadiusOfRatio(transitionToFullShadeProgress))
        var shadeRadius = max(combinedBlur, wakeAndUnlockBlurRadius)

        if (blursDisabledForAppLaunch || blursDisabledForUnlock) {
            shadeRadius = 0f
        }

        var zoomOut = MathUtils.saturate(blurUtils.ratioOfBlurRadius(shadeRadius))
        var blur = shadeRadius.toInt()

        if (inSplitShade) {
            zoomOut = 0f
        }

        // Make blur be 0 if it is necessary to stop blur effect.
        if (scrimsVisible) {
            blur = 0
            zoomOut = 0f
        }

        if (!blurUtils.supportsBlursOnWindows()) {
            blur = 0
        }

        // Brightness slider removes blur, but doesn't affect zooms
        blur = (blur * (1f - brightnessMirrorSpring.ratio)).toInt()

        return Pair(blur, zoomOut)
    }

    /**
     * Callback that updates the window blur value and is called only once per frame.
     */
    @VisibleForTesting
    val updateBlurCallback = Choreographer.FrameCallback {
        updateScheduled = false
        val (blur, zoomOut) = computeBlurAndZoomOut()
        val opaque = scrimsVisible && !blursDisabledForAppLaunch
        Trace.traceCounter(Trace.TRACE_TAG_APP, "shade_blur_radius", blur)
        blurUtils.applyBlur(root.viewRootImpl, blur, opaque)
        lastAppliedBlur = blur
        wallpaperController.setNotificationShadeZoom(zoomOut)
        listeners.forEach {
            it.onWallpaperZoomOutChanged(zoomOut)
            it.onBlurRadiusChanged(blur)
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
                    override fun onAnimationEnd(animation: Animator) {
                        keyguardAnimator = null
                        wakeAndUnlockBlurRadius = 0f
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
            scheduleUpdate()
        }

        override fun onDozingChanged(isDozing: Boolean) {
            if (isDozing) {
                shadeAnimation.finishIfRunning()
                brightnessMirrorSpring.finishIfRunning()
            }
        }

        override fun onDozeAmountChanged(linear: Float, eased: Float) {
            wakeAndUnlockBlurRadius = blurUtils.blurRadiusOfRatio(eased)
        }
    }

    init {
        dumpManager.registerCriticalDumpable(javaClass.name, this)
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
        updateResources()
        configurationController.addCallback(object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                updateResources()
            }
        })
    }

    private fun updateResources() {
        inSplitShade = LargeScreenUtils.shouldUseSplitNotificationShade(context.resources)
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
    override fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent) {
        val rawFraction = event.fraction
        val tracking = event.tracking
        val timestamp = SystemClock.elapsedRealtimeNanos()
        val expansion = MathUtils.saturate(
                (rawFraction - panelPullDownMinFraction) / (1f - panelPullDownMinFraction))

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

        scheduleUpdate()
    }

    private fun updateShadeAnimationBlur(
        expansion: Float,
        tracking: Boolean,
        velocity: Float,
        direction: Int
    ) {
        if (shouldApplyShadeBlur()) {
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

        val targetBlurNormalized = if (blur && shouldApplyShadeBlur()) {
            1f
        } else {
            0f
        }

        shadeAnimation.setStartVelocity(velocity)
        shadeAnimation.animateTo(blurUtils.blurRadiusOfRatio(targetBlurNormalized).toInt())
    }

    private fun scheduleUpdate() {
        if (updateScheduled) {
            return
        }
        updateScheduled = true
        val (blur, _) = computeBlurAndZoomOut()
        blurUtils.prepareBlur(root.viewRootImpl, blur)
        choreographer.postFrameCallback(updateBlurCallback)
    }

    /**
     * Should blur be applied to the shade currently. This is mainly used to make sure that
     * on the lockscreen, the wallpaper isn't blurred.
     */
    private fun shouldApplyShadeBlur(): Boolean {
        val state = statusBarStateController.state
        return (state == StatusBarState.SHADE || state == StatusBarState.SHADE_LOCKED) &&
                !keyguardStateController.isKeyguardFadingAway
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").let {
            it.println("StatusBarWindowBlurController:")
            it.increaseIndent()
            it.println("shadeExpansion: $shadeExpansion")
            it.println("shouldApplyShadeBlur: ${shouldApplyShadeBlur()}")
            it.println("shadeAnimation: ${shadeAnimation.radius}")
            it.println("brightnessMirrorRadius: ${brightnessMirrorSpring.radius}")
            it.println("wakeAndUnlockBlur: $wakeAndUnlockBlurRadius")
            it.println("blursDisabledForAppLaunch: $blursDisabledForAppLaunch")
            it.println("qsPanelExpansion: $qsPanelExpansion")
            it.println("transitionToFullShadeProgress: $transitionToFullShadeProgress")
            it.println("lastAppliedBlur: $lastAppliedBlur")
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
        var radius = 0f

        /**
         * Depth ratio of the current blur radius.
         */
        val ratio
            get() = blurUtils.ratioOfBlurRadius(radius)

        /**
         * Radius that we're animating to.
         */
        private var pendingRadius = -1

        private var springAnimation = SpringAnimation(this, object :
                FloatPropertyCompat<DepthAnimation>("blurRadius") {
            override fun setValue(rect: DepthAnimation?, value: Float) {
                radius = value
                scheduleUpdate()
            }

            override fun getValue(rect: DepthAnimation?): Float {
                return radius
            }
        })

        init {
            springAnimation.spring = SpringForce(0.0f)
            springAnimation.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            springAnimation.spring.stiffness = SpringForce.STIFFNESS_HIGH
            springAnimation.addEndListener { _, _, _, _ -> pendingRadius = -1 }
        }

        fun animateTo(newRadius: Int) {
            if (pendingRadius == newRadius) {
                return
            }
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

        fun onBlurRadiusChanged(blurRadius: Int) {}
    }
}
