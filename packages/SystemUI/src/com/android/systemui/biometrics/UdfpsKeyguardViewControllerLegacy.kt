/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.content.res.Configuration
import android.util.MathUtils
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.keyguard.BouncerPanelExpansionCalculator.aboutToShowBouncerProgress
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.biometrics.UdfpsKeyguardViewLegacy.ANIMATE_APPEAR_ON_SCREEN_OFF
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager.KeyguardViewManagerCallback
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager.OccludingAppBiometricUI
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import java.io.PrintWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Class that coordinates non-HBM animations during keyguard authentication. */
@ExperimentalCoroutinesApi
open class UdfpsKeyguardViewControllerLegacy(
    private val view: UdfpsKeyguardViewLegacy,
    statusBarStateController: StatusBarStateController,
    private val keyguardViewManager: StatusBarKeyguardViewManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    dumpManager: DumpManager,
    private val lockScreenShadeTransitionController: LockscreenShadeTransitionController,
    private val configurationController: ConfigurationController,
    private val keyguardStateController: KeyguardStateController,
    private val unlockedScreenOffAnimationController: UnlockedScreenOffAnimationController,
    systemUIDialogManager: SystemUIDialogManager,
    private val udfpsController: UdfpsController,
    private val activityTransitionAnimator: ActivityTransitionAnimator,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    private val udfpsKeyguardAccessibilityDelegate: UdfpsKeyguardAccessibilityDelegate,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val transitionInteractor: KeyguardTransitionInteractor,
    shadeInteractor: ShadeInteractor,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
) :
    UdfpsAnimationViewController<UdfpsKeyguardViewLegacy>(
        view,
        statusBarStateController,
        shadeInteractor,
        systemUIDialogManager,
        dumpManager,
        udfpsOverlayInteractor,
    ) {
    private val uniqueIdentifier = this.toString()
    private var showingUdfpsBouncer = false
    private var udfpsRequested = false
    private var qsExpansion = 0f
    private var faceDetectRunning = false
    private var statusBarState = 0
    private var transitionToFullShadeProgress = 0f
    private var lastDozeAmount = 0f
    private var panelExpansionFraction = 0f
    private var launchTransitionFadingAway = false
    private var isLaunchingActivity = false
    private var activityLaunchProgress = 0f
    private var inputBouncerExpansion = 0f

    private val stateListener: StatusBarStateController.StateListener =
        object : StatusBarStateController.StateListener {
            override fun onStateChanged(statusBarState: Int) {
                this@UdfpsKeyguardViewControllerLegacy.statusBarState = statusBarState
                updateAlpha()
                updatePauseAuth()
            }
        }

    private val configurationListener: ConfigurationController.ConfigurationListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                view.updateColor()
            }

            override fun onThemeChanged() {
                view.updateColor()
            }

            override fun onConfigChanged(newConfig: Configuration) {
                updateScaleFactor()
                view.updatePadding()
                view.updateColor()
            }
        }

    private val keyguardStateControllerCallback: KeyguardStateController.Callback =
        object : KeyguardStateController.Callback {
            override fun onUnlockedChanged() {
                updatePauseAuth()
            }

            override fun onLaunchTransitionFadingAwayChanged() {
                launchTransitionFadingAway = keyguardStateController.isLaunchTransitionFadingAway
                updatePauseAuth()
            }
        }

    private val mActivityTransitionAnimatorListener: ActivityTransitionAnimator.Listener =
        object : ActivityTransitionAnimator.Listener {
            override fun onTransitionAnimationStart() {
                isLaunchingActivity = true
                activityLaunchProgress = 0f
                updateAlpha()
            }

            override fun onTransitionAnimationEnd() {
                isLaunchingActivity = false
                updateAlpha()
            }

            override fun onTransitionAnimationProgress(linearProgress: Float) {
                activityLaunchProgress = linearProgress
                updateAlpha()
            }
        }

    private val statusBarKeyguardViewManagerCallback: KeyguardViewManagerCallback =
        object : KeyguardViewManagerCallback {
            override fun onQSExpansionChanged(qsExpansion: Float) {
                this@UdfpsKeyguardViewControllerLegacy.qsExpansion = qsExpansion
                updateAlpha()
                updatePauseAuth()
            }
        }

    private val occludingAppBiometricUI: OccludingAppBiometricUI =
        object : OccludingAppBiometricUI {
            override fun requestUdfps(request: Boolean, color: Int) {
                udfpsRequested = request
                view.requestUdfps(request, color)
                updateAlpha()
                updatePauseAuth()
            }

            override fun dump(pw: PrintWriter) {
                pw.println(tag)
            }
        }

    override val tag: String
        get() = TAG

    override fun onInit() {
        super.onInit()
        keyguardViewManager.setOccludingAppBiometricUI(occludingAppBiometricUI)
    }

    init {
        com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor.assertInLegacyMode()
        view.repeatWhenAttached {
            // repeatOnLifecycle CREATED (as opposed to STARTED) because the Bouncer expansion
            // can make the view not visible; and we still want to listen for events
            // that may make the view visible again.
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                listenForBouncerExpansion(this)
                listenForAlternateBouncerVisibility(this)
                listenForOccludedToAodTransition(this)
                listenForGoneToAodTransition(this)
                listenForLockscreenAodTransitions(this)
                listenForAodToOccludedTransitions(this)
                listenForAlternateBouncerToAodTransitions(this)
                listenForDreamingToAodTransitions(this)
                listenForPrimaryBouncerToAodTransitions(this)
            }
        }
    }

    @VisibleForTesting
    suspend fun listenForPrimaryBouncerToAodTransitions(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor
                .transition(
                    edge = Edge.create(Scenes.Bouncer, AOD),
                    edgeWithoutSceneContainer = Edge.create(PRIMARY_BOUNCER, AOD)
                )
                .collect { transitionStep ->
                    view.onDozeAmountChanged(
                        transitionStep.value,
                        transitionStep.value,
                        ANIMATE_APPEAR_ON_SCREEN_OFF,
                    )
                }
        }
    }

    @VisibleForTesting
    suspend fun listenForDreamingToAodTransitions(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.transition(Edge.create(DREAMING, AOD)).collect { transitionStep ->
                view.onDozeAmountChanged(
                    transitionStep.value,
                    transitionStep.value,
                    ANIMATE_APPEAR_ON_SCREEN_OFF,
                )
            }
        }
    }

    @VisibleForTesting
    suspend fun listenForAlternateBouncerToAodTransitions(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.transition(Edge.create(ALTERNATE_BOUNCER, AOD)).collect {
                transitionStep ->
                view.onDozeAmountChanged(
                    transitionStep.value,
                    transitionStep.value,
                    UdfpsKeyguardViewLegacy.ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN,
                )
            }
        }
    }

    @VisibleForTesting
    suspend fun listenForAodToOccludedTransitions(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.transition(Edge.create(AOD, OCCLUDED)).collect { transitionStep ->
                view.onDozeAmountChanged(
                    1f - transitionStep.value,
                    1f - transitionStep.value,
                    UdfpsKeyguardViewLegacy.ANIMATION_NONE,
                )
            }
        }
    }

    @VisibleForTesting
    suspend fun listenForOccludedToAodTransition(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.transition(Edge.create(OCCLUDED, AOD)).collect { transitionStep ->
                view.onDozeAmountChanged(
                    transitionStep.value,
                    transitionStep.value,
                    ANIMATE_APPEAR_ON_SCREEN_OFF,
                )
            }
        }
    }

    @VisibleForTesting
    suspend fun listenForGoneToAodTransition(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor
                .transition(
                    edge = Edge.create(Scenes.Gone, AOD),
                    edgeWithoutSceneContainer = Edge.create(GONE, AOD)
                )
                .collect { transitionStep ->
                    view.onDozeAmountChanged(
                        transitionStep.value,
                        transitionStep.value,
                        ANIMATE_APPEAR_ON_SCREEN_OFF,
                    )
                }
        }
    }

    @VisibleForTesting
    suspend fun listenForLockscreenAodTransitions(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.dozeAmountTransition.collect { transitionStep ->
                if (
                    transitionStep.from == AOD &&
                        transitionStep.transitionState == TransitionState.CANCELED
                ) {
                    if (transitionInteractor.startedKeyguardTransitionStep.first().to != AOD) {
                        // If the next started transition isn't transitioning back to AOD, force
                        // doze amount to be 0f (as if the transition to the lockscreen completed).
                        view.onDozeAmountChanged(
                            0f,
                            0f,
                            UdfpsKeyguardViewLegacy.ANIMATION_NONE,
                        )
                    }
                } else {
                    view.onDozeAmountChanged(
                        transitionStep.value,
                        transitionStep.value,
                        UdfpsKeyguardViewLegacy.ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN,
                    )
                }
            }
        }
    }

    @VisibleForTesting
    suspend fun listenForBouncerExpansion(scope: CoroutineScope): Job {
        return scope.launch {
            primaryBouncerInteractor.bouncerExpansion.collect { bouncerExpansion: Float ->
                inputBouncerExpansion = bouncerExpansion

                panelExpansionFraction =
                    if (keyguardViewManager.isPrimaryBouncerInTransit) {
                        aboutToShowBouncerProgress(1f - bouncerExpansion)
                    } else {
                        1f - bouncerExpansion
                    }
                updateAlpha()
                updatePauseAuth()
            }
        }
    }

    @VisibleForTesting
    suspend fun listenForAlternateBouncerVisibility(scope: CoroutineScope): Job {
        return scope.launch {
            alternateBouncerInteractor.isVisible.collect { isVisible: Boolean ->
                showUdfpsBouncer(isVisible)
            }
        }
    }

    public override fun onViewAttached() {
        super.onViewAttached()
        alternateBouncerInteractor.setAlternateBouncerUIAvailable(true, uniqueIdentifier)
        val dozeAmount = statusBarStateController.dozeAmount
        lastDozeAmount = dozeAmount
        stateListener.onDozeAmountChanged(dozeAmount, dozeAmount)
        statusBarStateController.addCallback(stateListener)
        udfpsRequested = false
        launchTransitionFadingAway = keyguardStateController.isLaunchTransitionFadingAway
        keyguardStateController.addCallback(keyguardStateControllerCallback)
        statusBarState = statusBarStateController.state
        qsExpansion = keyguardViewManager.qsExpansion
        keyguardViewManager.addCallback(statusBarKeyguardViewManagerCallback)
        configurationController.addCallback(configurationListener)
        updateScaleFactor()
        view.updatePadding()
        updateAlpha()
        updatePauseAuth()
        keyguardViewManager.setOccludingAppBiometricUI(occludingAppBiometricUI)
        lockScreenShadeTransitionController.mUdfpsKeyguardViewControllerLegacy = this
        activityTransitionAnimator.addListener(mActivityTransitionAnimatorListener)
        view.startIconAsyncInflate {
            val animationViewInternal: View =
                view.requireViewById(R.id.udfps_animation_view_internal)
            animationViewInternal.accessibilityDelegate = udfpsKeyguardAccessibilityDelegate
        }
    }

    public override fun onViewDetached() {
        super.onViewDetached()
        alternateBouncerInteractor.setAlternateBouncerUIAvailable(false, uniqueIdentifier)
        faceDetectRunning = false
        keyguardStateController.removeCallback(keyguardStateControllerCallback)
        statusBarStateController.removeCallback(stateListener)
        keyguardViewManager.removeOccludingAppBiometricUI(occludingAppBiometricUI)
        configurationController.removeCallback(configurationListener)
        if (lockScreenShadeTransitionController.mUdfpsKeyguardViewControllerLegacy === this) {
            lockScreenShadeTransitionController.mUdfpsKeyguardViewControllerLegacy = null
        }
        activityTransitionAnimator.removeListener(mActivityTransitionAnimatorListener)
        keyguardViewManager.removeCallback(statusBarKeyguardViewManagerCallback)
    }

    override fun dump(pw: PrintWriter, args: Array<String>) {
        super.dump(pw, args)
        pw.println("showingUdfpsAltBouncer=$showingUdfpsBouncer")
        pw.println(
            "altBouncerInteractor#isAlternateBouncerVisible=" +
                "${alternateBouncerInteractor.isVisibleState()}"
        )
        pw.println(
            "altBouncerInteractor#canShowAlternateBouncerForFingerprint=" +
                "${alternateBouncerInteractor.canShowAlternateBouncerForFingerprint()}"
        )
        pw.println("faceDetectRunning=$faceDetectRunning")
        pw.println("statusBarState=" + StatusBarState.toString(statusBarState))
        pw.println("transitionToFullShadeProgress=$transitionToFullShadeProgress")
        pw.println("qsExpansion=$qsExpansion")
        pw.println("panelExpansionFraction=$panelExpansionFraction")
        pw.println("unpausedAlpha=" + view.unpausedAlpha)
        pw.println("udfpsRequestedByApp=$udfpsRequested")
        pw.println("launchTransitionFadingAway=$launchTransitionFadingAway")
        pw.println("lastDozeAmount=$lastDozeAmount")
        pw.println("inputBouncerExpansion=$inputBouncerExpansion")
        view.dump(pw)
    }

    /**
     * Overrides non-bouncer show logic in shouldPauseAuth to still show icon.
     *
     * @return whether the udfpsBouncer has been newly shown or hidden
     */
    private fun showUdfpsBouncer(show: Boolean): Boolean {
        if (showingUdfpsBouncer == show) {
            return false
        }
        val udfpsAffordanceWasNotShowing = shouldPauseAuth()
        showingUdfpsBouncer = show
        if (showingUdfpsBouncer) {
            if (udfpsAffordanceWasNotShowing) {
                view.animateInUdfpsBouncer(null)
            }
            view.announceForAccessibility(
                view.context.getString(R.string.accessibility_fingerprint_bouncer)
            )
        }
        updateAlpha()
        updatePauseAuth()
        return true
    }

    /**
     * Returns true if the fingerprint manager is running but we want to temporarily pause
     * authentication. On the keyguard, we may want to show udfps when the shade is expanded, so
     * this can be overridden with the showBouncer method.
     */
    override fun shouldPauseAuth(): Boolean {
        if (showingUdfpsBouncer) {
            return false
        }
        if (
            udfpsRequested &&
                !notificationShadeVisible &&
                !isInputBouncerFullyVisible() &&
                keyguardStateController.isShowing
        ) {
            return false
        }
        if (launchTransitionFadingAway) {
            return true
        }

        // Only pause auth if we're not on the keyguard AND we're not transitioning to doze.
        // For the UnlockedScreenOffAnimation, the statusBarState is
        // delayed. However, we still animate in the UDFPS affordance with the
        // unlockedScreenOffDozeAnimator.
        if (
            statusBarState != StatusBarState.KEYGUARD &&
                !unlockedScreenOffAnimationController.isAnimationPlaying()
        ) {
            return true
        }
        if (isBouncerExpansionGreaterThan(.5f)) {
            return true
        }
        if (
            keyguardUpdateMonitor.getUserUnlockedWithBiometric(
                selectedUserInteractor.getSelectedUserId()
            )
        ) {
            // If the device was unlocked by a biometric, immediately hide the UDFPS icon to avoid
            // overlap with the LockIconView. Shortly afterwards, UDFPS will stop running.
            return true
        }
        return view.unpausedAlpha < 255 * .1
    }

    fun isBouncerExpansionGreaterThan(bouncerExpansionThreshold: Float): Boolean {
        return inputBouncerExpansion >= bouncerExpansionThreshold
    }

    fun isInputBouncerFullyVisible(): Boolean {
        return inputBouncerExpansion == 1f
    }

    override fun listenForTouchesOutsideView(): Boolean {
        return true
    }

    /**
     * Set the progress we're currently transitioning to the full shade. 0.0f means we're not
     * transitioning yet, while 1.0f means we've fully dragged down. For example, start swiping down
     * to expand the notification shade from the empty space in the middle of the lock screen.
     */
    fun setTransitionToFullShadeProgress(progress: Float) {
        transitionToFullShadeProgress = progress
        updateAlpha()
    }

    /**
     * Update alpha for the UDFPS lock screen affordance. The AoD UDFPS visual affordance's alpha is
     * based on the doze amount.
     */
    override fun updateAlpha() {
        // Fade icon on transitions to showing the status bar or bouncer, but if mUdfpsRequested,
        // then the keyguard is occluded by some application - so instead use the input bouncer
        // hidden amount to determine the fade.
        val expansion = if (udfpsRequested) getInputBouncerHiddenAmt() else panelExpansionFraction
        var alpha: Int =
            if (showingUdfpsBouncer) 255
            else MathUtils.constrain(MathUtils.map(.5f, .9f, 0f, 255f, expansion), 0f, 255f).toInt()
        if (!showingUdfpsBouncer) {
            // swipe from top of the lockscreen to expand full QS:
            alpha =
                (alpha * (1.0f - Interpolators.EMPHASIZED_DECELERATE.getInterpolation(qsExpansion)))
                    .toInt()

            // swipe from the middle (empty space) of lockscreen to expand the notification shade:
            alpha = (alpha * (1.0f - transitionToFullShadeProgress)).toInt()

            // Fade out the icon if we are animating an activity launch over the lockscreen and the
            // activity didn't request the UDFPS.
            if (isLaunchingActivity && !udfpsRequested) {
                val udfpsActivityLaunchAlphaMultiplier =
                    1f -
                        (activityLaunchProgress *
                                (ActivityTransitionAnimator.TIMINGS.totalDuration / 83))
                            .coerceIn(0f, 1f)
                alpha = (alpha * udfpsActivityLaunchAlphaMultiplier).toInt()
            }

            // Fade out alpha when a dialog is shown
            // Fade in alpha when a dialog is hidden
            alpha = (alpha * view.dialogSuggestedAlpha).toInt()
        }
        view.unpausedAlpha = alpha
    }

    private fun getInputBouncerHiddenAmt(): Float {
        return 1f - inputBouncerExpansion
    }

    /** Update the scale factor based on the device's resolution. */
    private fun updateScaleFactor() {
        udfpsController.mOverlayParams?.scaleFactor?.let { view.setScaleFactor(it) }
    }

    companion object {
        const val TAG = "UdfpsKeyguardViewController"
    }
}
