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

package com.android.systemui.keyguard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.view.RemoteAnimationTarget
import android.view.SyncRtSurfaceTransactionApplier
import android.view.View
import androidx.core.math.MathUtils
import com.android.internal.R
import com.android.keyguard.KeyguardClockSwitchController
import com.android.keyguard.KeyguardViewController
import com.android.systemui.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.system.smartspace.SmartspaceTransitionController
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.Lazy
import javax.inject.Inject

/**
 * Starting scale factor for the app/launcher surface behind the keyguard, when it's animating
 * in during keyguard exit.
 */
const val SURFACE_BEHIND_START_SCALE_FACTOR = 0.95f

/**
 * How much to translate the surface behind the keyguard at the beginning of the exit animation,
 * in terms of percentage of the surface's height.
 */
const val SURFACE_BEHIND_START_TRANSLATION_Y = 0.05f

/**
 * Y coordinate of the pivot point for the scale effect on the surface behind the keyguard. This
 * is expressed as percentage of the surface's height, so 0.66f means the surface will scale up
 * from the point at (width / 2, height * 0.66).
 */
const val SURFACE_BEHIND_SCALE_PIVOT_Y = 0.66f

/**
 * Dismiss amount at which to fade in the surface behind the keyguard. The surface will then animate
 * along with the dismiss amount until [DISMISS_AMOUNT_EXIT_KEYGUARD_THRESHOLD] is reached.
 *
 * The dismiss amount is the inverse of the notification panel expansion, which decreases as the
 * lock screen is swiped away.
 */
const val DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD = 0.1f

/**
 * Dismiss amount at which to complete the keyguard exit animation and hide the keyguard.
 *
 * The dismiss amount is the inverse of the notification panel expansion, which decreases as the
 * lock screen is swiped away.
 */
const val DISMISS_AMOUNT_EXIT_KEYGUARD_THRESHOLD = 0.3f

/**
 * Initiates, controls, and ends the keyguard unlock animation.
 *
 * The unlock animation transitions between the keyguard (lock screen) and the app/launcher surface
 * behind the keyguard. If the user is swiping away the keyguard, this controller will decide when
 * to animate in the surface, and synchronize its appearance with the swipe gesture. If the keyguard
 * is animating away via a canned animation (due to biometric unlock, tapping a notification, etc.)
 * this controller will play a canned animation on the surface as well.
 *
 * The surface behind the keyguard is manipulated via a RemoteAnimation passed to
 * [notifyStartKeyguardExitAnimation] by [KeyguardViewMediator].
 */
@SysUISingleton
class KeyguardUnlockAnimationController @Inject constructor(
    context: Context,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardViewMediator: Lazy<KeyguardViewMediator>,
    private val keyguardViewController: KeyguardViewController,
    private val smartspaceTransitionController: SmartspaceTransitionController
) : KeyguardStateController.Callback {

    /**
     * Information used to start, run, and finish a RemoteAnimation on the app or launcher surface
     * behind the keyguard.
     *
     * If we're swiping to unlock, the "animation" is controlled via the gesture, tied to the
     * dismiss amounts received in [onKeyguardDismissAmountChanged]. It does not have a fixed
     * duration, and it ends when the gesture reaches a certain threshold or is cancelled.
     *
     * If we're unlocking via biometrics, PIN entry, or from clicking a notification, a canned
     * animation is started in [notifyStartKeyguardExitAnimation].
     */
    private var surfaceTransactionApplier: SyncRtSurfaceTransactionApplier? = null
    private var surfaceBehindRemoteAnimationTarget: RemoteAnimationTarget? = null
    private var surfaceBehindRemoteAnimationStartTime: Long = 0

    /**
     * Alpha value applied to [surfaceBehindRemoteAnimationTarget], which is the surface of the
     * app/launcher behind the keyguard.
     *
     * If we're doing a swipe gesture, we fade in the surface when the swipe passes a certain
     * threshold. If we're doing a canned animation, it'll be faded in while a translate/scale
     * animation plays.
     */
    private var surfaceBehindAlpha = 1f
    private var surfaceBehindAlphaAnimator = ValueAnimator.ofFloat(0f, 1f)

    /**
     * Matrix applied to [surfaceBehindRemoteAnimationTarget], which is the surface of the
     * app/launcher behind the keyguard.
     *
     * This is used during the unlock animation/swipe gesture to scale and translate the surface.
     */
    private val surfaceBehindMatrix = Matrix()

    /**
     * Animator that animates in the surface behind the keyguard. This is used to play a canned
     * animation on the surface, if we're not doing a swipe gesture.
     */
    private val surfaceBehindEntryAnimator = ValueAnimator.ofFloat(0f, 1f)

    /** Rounded corner radius to apply to the surface behind the keyguard. */
    private var roundedCornerRadius = 0f

    /** The SmartSpace view on the lockscreen, provided by [KeyguardClockSwitchController]. */
    public var lockscreenSmartSpace: View? = null

    /**
     * Whether we are currently in the process of unlocking the keyguard, and we are performing the
     * shared element SmartSpace transition.
     */
    private var unlockingWithSmartSpaceTransition: Boolean = false

    /**
     * Whether we tried to start the SmartSpace shared element transition for this unlock swipe.
     * It's possible we're unable to do so (if the Launcher SmartSpace is not available).
     */
    private var attemptedSmartSpaceTransitionForThisSwipe = false

    init {
        surfaceBehindAlphaAnimator.duration = 150
        surfaceBehindAlphaAnimator.interpolator = Interpolators.ALPHA_IN
        surfaceBehindAlphaAnimator.addUpdateListener { valueAnimator: ValueAnimator ->
            surfaceBehindAlpha = valueAnimator.animatedValue as Float
            updateSurfaceBehindAppearAmount()
        }
        surfaceBehindAlphaAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // If the surface alpha is 0f, it's no longer visible so we can safely be done with
                // the animation.
                if (surfaceBehindAlpha == 0f) {
                    keyguardViewMediator.get().finishSurfaceBehindRemoteAnimation()
                }
            }
        })

        surfaceBehindEntryAnimator.duration = 450
        surfaceBehindEntryAnimator.interpolator = Interpolators.DECELERATE_QUINT
        surfaceBehindEntryAnimator.addUpdateListener { valueAnimator: ValueAnimator ->
            surfaceBehindAlpha = valueAnimator.animatedValue as Float
            setSurfaceBehindAppearAmount(valueAnimator.animatedValue as Float)
        }
        surfaceBehindEntryAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished()
            }
        })

        // Listen for changes in the dismiss amount.
        keyguardStateController.addCallback(this)

        roundedCornerRadius =
                context.resources.getDimensionPixelSize(R.dimen.rounded_corner_radius).toFloat()
    }

    /**
     * Called from [KeyguardViewMediator] to tell us that the RemoteAnimation on the surface behind
     * the keyguard has started successfully. We can use these parameters to directly manipulate the
     * surface for the unlock gesture/animation.
     *
     * When we're done with it, we'll call [KeyguardViewMediator.finishSurfaceBehindRemoteAnimation]
     * to end the RemoteAnimation.
     *
     * [requestedShowSurfaceBehindKeyguard] denotes whether the exit animation started because of a
     * call to [KeyguardViewMediator.showSurfaceBehindKeyguard], as happens during a swipe gesture,
     * as opposed to the keyguard hiding.
     */
    fun notifyStartKeyguardExitAnimation(
        target: RemoteAnimationTarget,
        startTime: Long,
        requestedShowSurfaceBehindKeyguard: Boolean
    ) {

        if (surfaceTransactionApplier == null) {
            surfaceTransactionApplier = SyncRtSurfaceTransactionApplier(
                    keyguardViewController.viewRootImpl.view)
        }

        surfaceBehindRemoteAnimationTarget = target
        surfaceBehindRemoteAnimationStartTime = startTime

        // If the surface behind wasn't made visible during a swipe, we'll do a canned animation
        // to animate it in. Otherwise, the swipe touch events will continue animating it.
        if (!requestedShowSurfaceBehindKeyguard) {
            keyguardViewController.hide(startTime, 350)
            surfaceBehindEntryAnimator.start()
        }
    }

    fun notifyCancelKeyguardExitAnimation() {
        surfaceBehindRemoteAnimationTarget = null
    }

    fun notifyFinishedKeyguardExitAnimation() {
        surfaceBehindRemoteAnimationTarget = null
    }

    fun hideKeyguardViewAfterRemoteAnimation() {
        keyguardViewController.hide(surfaceBehindRemoteAnimationStartTime, 350)
    }

    /**
     * Whether we are currently in the process of unlocking the keyguard, and we are performing the
     * shared element SmartSpace transition.
     */
    fun isUnlockingWithSmartSpaceTransition(): Boolean {
        return unlockingWithSmartSpaceTransition
    }

    /**
     * Update the lockscreen SmartSpace to be positioned according to the current dismiss amount. As
     * the dismiss amount increases, we will increase our SmartSpace's progress to the destination
     * bounds (the location of the Launcher SmartSpace).
     */
    fun updateLockscreenSmartSpacePosition() {
        smartspaceTransitionController.setProgressToDestinationBounds(
                keyguardStateController.dismissAmount / DISMISS_AMOUNT_EXIT_KEYGUARD_THRESHOLD)
    }

    /**
     * Scales in and translates up the surface behind the keyguard. This is used during unlock
     * animations and swipe gestures to animate the surface's entry (and exit, if the swipe is
     * cancelled).
     */
    private fun setSurfaceBehindAppearAmount(amount: Float) {
        if (surfaceBehindRemoteAnimationTarget == null) {
            return
        }

        val surfaceHeight: Int = surfaceBehindRemoteAnimationTarget!!.screenSpaceBounds.height()
        val scaleFactor = (SURFACE_BEHIND_START_SCALE_FACTOR +
                (1f - SURFACE_BEHIND_START_SCALE_FACTOR) *
                MathUtils.clamp(amount, 0f, 1f))

        // Scale up from a point at the center-bottom of the surface.
        surfaceBehindMatrix.setScale(
                scaleFactor,
                scaleFactor,
                surfaceBehindRemoteAnimationTarget!!.screenSpaceBounds.width() / 2f,
                surfaceHeight * SURFACE_BEHIND_SCALE_PIVOT_Y)

        // Translate up from the bottom.
        surfaceBehindMatrix.postTranslate(0f,
                surfaceHeight * SURFACE_BEHIND_START_TRANSLATION_Y * (1f - amount))

        // If we're snapping the keyguard back, immediately begin fading it out.
        val animationAlpha =
                if (keyguardStateController.isSnappingKeyguardBackAfterSwipe) amount
                else surfaceBehindAlpha

        val params = SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(
                surfaceBehindRemoteAnimationTarget!!.leash)
                .withMatrix(surfaceBehindMatrix)
                .withCornerRadius(roundedCornerRadius)
                .withAlpha(animationAlpha)
                .build()
        surfaceTransactionApplier!!.scheduleApply(params)
    }

    /**
     * Sets the appearance amount of the surface behind the keyguard, according to the current
     * keyguard dismiss amount and the method of dismissal.
     */
    private fun updateSurfaceBehindAppearAmount() {
        if (surfaceBehindRemoteAnimationTarget == null) {
            return
        }

        // For fling animations, we want to animate the surface in over the full distance. If we're
        // dismissing the keyguard via a swipe gesture (or cancelling the swipe gesture), we want to
        // bring in the surface behind over a relatively short swipe distance (~15%), to keep the
        // interaction tight.
        if (keyguardStateController.isFlingingToDismissKeyguard) {
            setSurfaceBehindAppearAmount(keyguardStateController.dismissAmount)
        } else if (keyguardStateController.isDismissingFromSwipe ||
                keyguardStateController.isSnappingKeyguardBackAfterSwipe) {
            val totalSwipeDistanceToDismiss =
                    (DISMISS_AMOUNT_EXIT_KEYGUARD_THRESHOLD - DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD)
            val swipedDistanceSoFar: Float =
                    keyguardStateController.dismissAmount - DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD
            val progress = swipedDistanceSoFar / totalSwipeDistanceToDismiss
            setSurfaceBehindAppearAmount(progress)
        }
    }

    override fun onKeyguardDismissAmountChanged() {
        if (!KeyguardService.sEnableRemoteKeyguardGoingAwayAnimation) {
            return
        }

        if (keyguardViewController.isShowing) {
            updateKeyguardViewMediatorIfThresholdsReached()

            // If the surface is visible or it's about to be, start updating its appearance to
            // reflect the new dismiss amount.
            if (keyguardViewMediator.get().requestedShowSurfaceBehindKeyguard() ||
                    keyguardViewMediator.get().isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe) {
                updateSurfaceBehindAppearAmount()
            }
        }

        // The end of the SmartSpace transition can occur after the keyguard is hidden (when we tell
        // Launcher's SmartSpace to become visible again), so update it even if the keyguard view is
        // no longer showing.
        updateSmartSpaceTransition()
    }

    /**
     * Lets the KeyguardViewMediator know if the dismiss amount has crossed a threshold of interest,
     * such as reaching the point in the dismiss swipe where we need to make the surface behind the
     * keyguard visible.
     */
    private fun updateKeyguardViewMediatorIfThresholdsReached() {
        val dismissAmount = keyguardStateController.dismissAmount

        // Hide the keyguard if we're fully dismissed, or if we're swiping to dismiss and have
        // crossed the threshold to finish the dismissal.
        val reachedHideKeyguardThreshold = (dismissAmount >= 1f ||
                (keyguardStateController.isDismissingFromSwipe &&
                        // Don't hide if we're flinging during a swipe, since we need to finish
                        // animating it out. This will be called again after the fling ends.
                        !keyguardStateController.isFlingingToDismissKeyguardDuringSwipeGesture &&
                        dismissAmount >= DISMISS_AMOUNT_EXIT_KEYGUARD_THRESHOLD))

        if (dismissAmount >= DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD &&
                !keyguardViewMediator.get().requestedShowSurfaceBehindKeyguard()) {
            // We passed the threshold, and we're not yet showing the surface behind the
            // keyguard. Animate it in.
            keyguardViewMediator.get().showSurfaceBehindKeyguard()
            fadeInSurfaceBehind()
        } else if (dismissAmount < DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD &&
                keyguardViewMediator.get().requestedShowSurfaceBehindKeyguard()) {
            // We're no longer past the threshold but we are showing the surface. Animate it
            // out.
            keyguardViewMediator.get().hideSurfaceBehindKeyguard()
            fadeOutSurfaceBehind()
        } else if (keyguardViewMediator.get()
                        .isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe &&
                reachedHideKeyguardThreshold) {
            keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished()
        }
    }

    /**
     * Updates flags related to the SmartSpace transition in response to a change in keyguard
     * dismiss amount, and also updates the SmartSpaceTransitionController, which will let Launcher
     * know if it needs to do something as a result.
     */
    private fun updateSmartSpaceTransition() {
        val dismissAmount = keyguardStateController.dismissAmount

        // If we've begun a swipe, and are capable of doing the SmartSpace transition, start it!
        if (!attemptedSmartSpaceTransitionForThisSwipe &&
                dismissAmount > 0f &&
                dismissAmount < 1f &&
                keyguardViewController.isShowing) {
            attemptedSmartSpaceTransitionForThisSwipe = true

            smartspaceTransitionController.prepareForUnlockTransition()
            if (keyguardStateController.canPerformSmartSpaceTransition()) {
                unlockingWithSmartSpaceTransition = true
                smartspaceTransitionController.launcherSmartspace?.setVisibility(
                        View.INVISIBLE)
            }
        } else if (attemptedSmartSpaceTransitionForThisSwipe &&
                (dismissAmount == 0f || dismissAmount == 1f)) {
            attemptedSmartSpaceTransitionForThisSwipe = false
            unlockingWithSmartSpaceTransition = false
            smartspaceTransitionController.launcherSmartspace?.setVisibility(View.VISIBLE)
        }
    }

    private fun fadeInSurfaceBehind() {
        surfaceBehindAlphaAnimator.cancel()
        surfaceBehindAlphaAnimator.start()
    }

    private fun fadeOutSurfaceBehind() {
        surfaceBehindAlphaAnimator.cancel()
        surfaceBehindAlphaAnimator.reverse()
    }
}