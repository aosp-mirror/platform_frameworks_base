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
import android.graphics.Rect
import android.os.Handler
import android.os.RemoteException
import android.util.Log
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.SyncRtSurfaceTransactionApplier
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.math.MathUtils
import com.android.internal.R
import com.android.keyguard.KeyguardClockSwitchController
import com.android.keyguard.KeyguardViewController
import com.android.systemui.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.shared.recents.utilities.Utilities
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.shared.system.smartspace.ISysuiUnlockAnimationController
import com.android.systemui.shared.system.smartspace.SmartspaceState
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.Lazy
import javax.inject.Inject

const val TAG = "KeyguardUnlock"

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
const val DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD = 0.15f

/**
 * Dismiss amount at which to complete the keyguard exit animation and hide the keyguard.
 *
 * The dismiss amount is the inverse of the notification panel expansion, which decreases as the
 * lock screen is swiped away.
 */
const val DISMISS_AMOUNT_EXIT_KEYGUARD_THRESHOLD = 0.3f

/**
 * How long the canned unlock animation takes. This is used if we are unlocking from biometric auth,
 * from a tap on the unlock icon, or from the bouncer. This is not relevant if the lockscreen is
 * swiped away via a touch gesture, or when it's flinging expanded/collapsed after a swipe.
 */
const val UNLOCK_ANIMATION_DURATION_MS = 200L

/**
 * How long the in-window launcher icon animation takes. This is used if the launcher is underneath
 * the lock screen and supports in-window animations.
 *
 * This animation will take place entirely within the Launcher window. We can safely unlock the
 * device, end remote animations, etc. even if this is still running.
 */
const val LAUNCHER_ICONS_ANIMATION_DURATION_MS = 633L

/**
 * How long to wait for the shade to get out of the way before starting the canned unlock animation.
 */
const val CANNED_UNLOCK_START_DELAY = 100L

/**
 * Duration for the alpha animation on the surface behind. This plays to fade in the surface during
 * a swipe to unlock (and to fade it back out if the swipe is cancelled).
 */
const val SURFACE_BEHIND_SWIPE_FADE_DURATION_MS = 175L

/**
 * Start delay for the surface behind animation, used so that the lockscreen can get out of the way
 * before the surface begins appearing.
 */
const val UNLOCK_ANIMATION_SURFACE_BEHIND_START_DELAY_MS = 75L

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
 * [notifyStartSurfaceBehindRemoteAnimation] by [KeyguardViewMediator].
 */
@SysUISingleton
class KeyguardUnlockAnimationController @Inject constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
    private val
    keyguardViewMediator: Lazy<KeyguardViewMediator>,
    private val keyguardViewController: KeyguardViewController,
    private val featureFlags: FeatureFlags,
    private val biometricUnlockControllerLazy: Lazy<BiometricUnlockController>,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val notificationShadeWindowController: NotificationShadeWindowController
) : KeyguardStateController.Callback, ISysuiUnlockAnimationController.Stub() {

    interface KeyguardUnlockAnimationListener {
        /**
         * Called when the remote unlock animation, controlled by
         * [KeyguardUnlockAnimationController], first starts.
         *
         * [playingCannedAnimation] indicates whether we are playing a canned animation to show the
         * app/launcher behind the keyguard, vs. this being a swipe to unlock where the dismiss
         * amount drives the animation.
         *
         * [fromWakeAndUnlock] tells us whether we are unlocking directly from AOD - in this case,
         * the lockscreen is dismissed instantly, so we shouldn't run any animations that rely on it
         * being visible.
         *
         * [unlockAnimationStartDelay] and [unlockAnimationDuration] provide the timing parameters
         * for the canned animation (if applicable) so interested parties can sync with it. If no
         * canned animation is playing, these are both 0.
         */
        @JvmDefault
        fun onUnlockAnimationStarted(
            playingCannedAnimation: Boolean,
            fromWakeAndUnlock: Boolean,
            unlockAnimationStartDelay: Long,
            unlockAnimationDuration: Long
        ) {}

        /**
         * Called when the remote unlock animation ends, in all cases, canned or swipe-to-unlock.
         * The keyguard is no longer visible in this state and the app/launcher behind the keyguard
         * is now completely visible.
         */
        @JvmDefault
        fun onUnlockAnimationFinished() {}
    }

    /** The SmartSpace view on the lockscreen, provided by [KeyguardClockSwitchController]. */
    var lockscreenSmartspace: View? = null

    /**
     * The state of the Launcher's smartspace, delivered via [onLauncherSmartspaceStateUpdated].
     * This is pushed to us from Launcher whenever their smartspace moves or its visibility changes.
     * We'll animate the lockscreen smartspace to this location during an unlock.
     */
    var launcherSmartspaceState: SmartspaceState? = null

    /**
     * Whether a canned unlock animation is playing, vs. currently unlocking in response to a swipe
     * gesture or panel fling. If we're swiping/flinging, the unlock animation is driven by the
     * dismiss amount, via [onKeyguardDismissAmountChanged]. If we're using a canned animation, it's
     * being driven by ValueAnimators started in [playCannedUnlockAnimation].
     */
    var playingCannedUnlockAnimation = false

    /**
     * Remote callback provided by Launcher that allows us to control the Launcher's unlock
     * animation and smartspace.
     *
     * If this is null, we will not be animating any Launchers today and should fall back to window
     * animations.
     */
    private var launcherUnlockController: ILauncherUnlockAnimationController? = null

    private val listeners = ArrayList<KeyguardUnlockAnimationListener>()

    /**
     * Called from SystemUiProxy to pass us the launcher's unlock animation controller. If this
     * doesn't happen, we won't use in-window animations or the smartspace shared element
     * transition, but that's okay!
     */
    override fun setLauncherUnlockController(callback: ILauncherUnlockAnimationController?) {
        launcherUnlockController = callback
    }

    /**
     * Called from SystemUiProxy to pass us the latest state of the Launcher's smartspace. This is
     * only done when the state has changed in some way.
     */
    override fun onLauncherSmartspaceStateUpdated(state: SmartspaceState?) {
        launcherSmartspaceState = state
    }

    /**
     * Information used to start, run, and finish a RemoteAnimation on the app or launcher surface
     * behind the keyguard.
     *
     * If we're swiping to unlock, the "animation" is controlled via the gesture, tied to the
     * dismiss amounts received in [onKeyguardDismissAmountChanged]. It does not have a fixed
     * duration, and it ends when the gesture reaches a certain threshold or is cancell
     *
     * If we're unlocking via biometrics, PIN entry, or from clicking a notification, a canned
     * animation is started in [playCannedUnlockAnimation].
     */
    @VisibleForTesting
    var surfaceTransactionApplier: SyncRtSurfaceTransactionApplier? = null
    private var surfaceBehindRemoteAnimationTarget: RemoteAnimationTarget? = null
    private var surfaceBehindRemoteAnimationStartTime: Long = 0
    private var surfaceBehindParams: SyncRtSurfaceTransactionApplier.SurfaceParams? = null

    /**
     * Alpha value applied to [surfaceBehindRemoteAnimationTarget], which is the surface of the
     * app/launcher behind the keyguard.
     *
     * If we're doing a swipe gesture, we fade in the surface when the swipe passes a certain
     * threshold. If we're doing a canned animation, it'll be faded in while a translate/scale
     * animation plays.
     */
    private var surfaceBehindAlpha = 1f

    @VisibleForTesting
    var surfaceBehindAlphaAnimator = ValueAnimator.ofFloat(0f, 1f)

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
    @VisibleForTesting
    val surfaceBehindEntryAnimator = ValueAnimator.ofFloat(0f, 1f)

    /** Rounded corner radius to apply to the surface behind the keyguard. */
    private var roundedCornerRadius = 0f

    /**
     * Whether we decided in [prepareForInWindowLauncherAnimations] that we are able to and want to
     * play the in-window launcher unlock animations rather than simply animating the Launcher
     * window like any other app. This can be true while [willUnlockWithSmartspaceTransition] is
     * false, if the smartspace is not available or was not ready in time.
     */
    private var willUnlockWithInWindowLauncherAnimations: Boolean = false

    /**
     * Whether we decided in [prepareForInWindowLauncherAnimations] that we are able to and want to
     * play the smartspace shared element animation. If true,
     * [willUnlockWithInWindowLauncherAnimations] will also always be true since in-window
     * animations are a prerequisite for the smartspace transition.
     */
    private var willUnlockWithSmartspaceTransition: Boolean = false

    private val handler = Handler()

    private val tmpFloat = FloatArray(9)

    init {
        with(surfaceBehindAlphaAnimator) {
            duration = SURFACE_BEHIND_SWIPE_FADE_DURATION_MS
            interpolator = Interpolators.LINEAR
            addUpdateListener { valueAnimator: ValueAnimator ->
                surfaceBehindAlpha = valueAnimator.animatedValue as Float
                updateSurfaceBehindAppearAmount()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // If we animated the surface alpha to 0f, it means we cancelled a swipe to
                    // dismiss. In this case, we should ask the KeyguardViewMediator to end the
                    // remote animation to hide the surface behind the keyguard, but should *not*
                    // call onKeyguardExitRemoteAnimationFinished since that will hide the keyguard
                    // and unlock the device as well as hiding the surface.
                    if (surfaceBehindAlpha == 0f) {
                        Log.d(TAG, "surfaceBehindAlphaAnimator#onAnimationEnd")
                        keyguardViewMediator.get().finishSurfaceBehindRemoteAnimation(
                            false /* cancelled */)
                    } else {
                        Log.d(TAG, "skip finishSurfaceBehindRemoteAnimation" +
                                " surfaceBehindAlpha=$surfaceBehindAlpha")
                    }
                }
            })
        }

        with(surfaceBehindEntryAnimator) {
            duration = UNLOCK_ANIMATION_DURATION_MS
            startDelay = UNLOCK_ANIMATION_SURFACE_BEHIND_START_DELAY_MS
            interpolator = Interpolators.TOUCH_RESPONSE
            addUpdateListener { valueAnimator: ValueAnimator ->
                surfaceBehindAlpha = valueAnimator.animatedValue as Float
                setSurfaceBehindAppearAmount(valueAnimator.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    Log.d(TAG, "surfaceBehindEntryAnimator#onAnimationEnd")
                    playingCannedUnlockAnimation = false
                    keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished(
                        false /* cancelled */
                    )
                }
            })
        }

        // Listen for changes in the dismiss amount.
        keyguardStateController.addCallback(this)

        roundedCornerRadius =
            context.resources.getDimensionPixelSize(R.dimen.rounded_corner_radius).toFloat()
    }

    /**
     * Add a listener to be notified of various stages of the unlock animation.
     */
    fun addKeyguardUnlockAnimationListener(listener: KeyguardUnlockAnimationListener) {
        listeners.add(listener)
    }

    fun removeKeyguardUnlockAnimationListener(listener: KeyguardUnlockAnimationListener) {
        listeners.remove(listener)
    }

    /**
     * Whether we should be able to do the in-window launcher animations given the current state of
     * the device.
     */
    fun canPerformInWindowLauncherAnimations(): Boolean {
        return isNexusLauncherUnderneath() &&
                // If the launcher is underneath, but we're about to launch an activity, don't do
                // the animations since they won't be visible.
                !notificationShadeWindowController.isLaunchingActivity &&
                launcherUnlockController != null &&
                !keyguardStateController.isDismissingFromSwipe &&
                // Temporarily disable for foldables since foldable launcher has two first pages,
                // which breaks the in-window animation.
                !isFoldable(context)
    }

    /**
     * Called from [KeyguardStateController] to let us know that the keyguard going away state has
     * changed.
     */
    override fun onKeyguardGoingAwayChanged() {
        if (keyguardStateController.isKeyguardGoingAway &&
                !statusBarStateController.leaveOpenOnKeyguardHide()) {
            prepareForInWindowLauncherAnimations()
        }
    }

    /**
     * Prepare for in-window Launcher unlock animations, if we're able to do so.
     *
     * The in-window animations consist of the staggered ring icon unlock animation, and optionally
     * the shared element smartspace transition.
     */
    fun prepareForInWindowLauncherAnimations() {
        willUnlockWithInWindowLauncherAnimations = canPerformInWindowLauncherAnimations()

        if (!willUnlockWithInWindowLauncherAnimations) {
            return
        }

        // There are additional conditions under which we should not perform the smartspace
        // transition specifically, so check those.
        willUnlockWithSmartspaceTransition = shouldPerformSmartspaceTransition()

        var lockscreenSmartspaceBounds = Rect()

        // Grab the bounds of our lockscreen smartspace and send them to launcher so they can
        // position their smartspace there initially, then animate it to its resting position.
        if (willUnlockWithSmartspaceTransition) {
            lockscreenSmartspaceBounds = Rect().apply {
                lockscreenSmartspace!!.getBoundsOnScreen(this)

                // The smartspace container on the lockscreen has left and top padding to align it
                // with other lockscreen content. This padding is inside the bounds on screen, so
                // add it to those bounds so that the padding-less launcher smartspace is properly
                // aligned.
                offset(lockscreenSmartspace!!.paddingLeft, lockscreenSmartspace!!.paddingTop)

                // Also offset by the current card's top padding, if it has any. This allows us to
                // align the tops of the lockscreen/launcher smartspace cards. Some cards, such as
                // the three-line date/weather/alarm card, only have three lines on lockscreen but
                // two on launcher.
                offset(0, (lockscreenSmartspace
                        as? BcSmartspaceDataPlugin.SmartspaceView)?.currentCardTopPadding ?: 0)
            }
        }

        // Currently selected lockscreen smartspace page, or -1 if it's not available.
        val selectedPage =
            (lockscreenSmartspace as BcSmartspaceDataPlugin.SmartspaceView?)?.selectedPage ?: -1

        try {
            // Let the launcher know to prepare for this animation.
            launcherUnlockController?.prepareForUnlock(
                willUnlockWithSmartspaceTransition, /* willAnimateSmartspace */
                lockscreenSmartspaceBounds, /* lockscreenSmartspaceBounds */
                selectedPage /* selectedPage */
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in prepareForInWindowUnlockAnimations.", e)
        }
    }

    /**
     * Called from [KeyguardViewMediator] to tell us that the RemoteAnimation on the surface behind
     * the keyguard has started successfully. We can use these parameters to directly manipulate the
     * surface for the unlock gesture/animation.
     *
     * When we're done with it, we'll call [KeyguardViewMediator.finishSurfaceBehindRemoteAnimation]
     * to end the RemoteAnimation. The KeyguardViewMediator will then end the animation and let us
     * know that it's over by calling [notifyFinishedKeyguardExitAnimation].
     *
     * [requestedShowSurfaceBehindKeyguard] indicates whether the animation started because of a
     * call to [KeyguardViewMediator.showSurfaceBehindKeyguard], as happens during a swipe gesture,
     * as opposed to being called because the device was unlocked instantly by some other means
     * (fingerprint, tap, etc.) and the keyguard is going away.
     */
    fun notifyStartSurfaceBehindRemoteAnimation(
        target: RemoteAnimationTarget,
        startTime: Long,
        requestedShowSurfaceBehindKeyguard: Boolean
    ) {
        if (surfaceTransactionApplier == null) {
            surfaceTransactionApplier = SyncRtSurfaceTransactionApplier(
                    keyguardViewController.viewRootImpl.view)
        }

        // New animation, new params.
        surfaceBehindParams = null

        surfaceBehindRemoteAnimationTarget = target
        surfaceBehindRemoteAnimationStartTime = startTime

        // If we specifically requested that the surface behind be made visible (vs. it being made
        // visible because we're unlocking), then we're in the middle of a swipe-to-unlock touch
        // gesture and the surface behind the keyguard should be made visible.
        if (requestedShowSurfaceBehindKeyguard) {
            // Fade in the surface, as long as we're not now flinging. The touch gesture ending in
            // a fling during the time it takes the keyguard exit animation to start is an edge
            // case race condition, and we'll handle it by playing a canned animation on the
            // now-visible surface to finish unlocking.
            if (!keyguardStateController.isFlingingToDismissKeyguard) {
                fadeInSurfaceBehind()
            } else {
                playCannedUnlockAnimation()
            }
        } else {
            // The surface was made visible since we're unlocking not from a swipe (fingerprint,
            // lock icon long-press, etc). Play the full unlock animation.
            playCannedUnlockAnimation()
        }

        listeners.forEach {
            it.onUnlockAnimationStarted(
                playingCannedUnlockAnimation /* playingCannedAnimation */,
                biometricUnlockControllerLazy.get().isWakeAndUnlock /* isWakeAndUnlock */,
                CANNED_UNLOCK_START_DELAY /* unlockStartDelay */,
                LAUNCHER_ICONS_ANIMATION_DURATION_MS /* unlockAnimationDuration */) }

        // Finish the keyguard remote animation if the dismiss amount has crossed the threshold.
        // Check it here in case there is no more change to the dismiss amount after the last change
        // that starts the keyguard animation. @see #updateKeyguardViewMediatorIfThresholdsReached()
        finishKeyguardExitRemoteAnimationIfReachThreshold()
    }

    /**
     * Play a canned unlock animation to unlock the device. This is used when we were *not* swiping
     * to unlock using a touch gesture. If we were swiping to unlock, the animation will be driven
     * by the dismiss amount via [onKeyguardDismissAmountChanged].
     */
    private fun playCannedUnlockAnimation() {
        Log.d(TAG, "playCannedUnlockAnimation")
        playingCannedUnlockAnimation = true

        when {
            // If we're set up for in-window launcher animations, ask Launcher to play its in-window
            // canned animation.
            willUnlockWithInWindowLauncherAnimations -> {
                Log.d(TAG, "playCannedUnlockAnimation, unlockToLauncherWithInWindowAnimations")
                unlockToLauncherWithInWindowAnimations()
            }

            // If we're waking and unlocking to a non-Launcher app surface (or Launcher in-window
            // animations are not available), show it immediately and end the remote animation. The
            // circular light reveal will show the app surface, and it looks weird if it's moving
            // around behind that.
            biometricUnlockControllerLazy.get().isWakeAndUnlock -> {
                Log.d(TAG, "playCannedUnlockAnimation, isWakeAndUnlock")
                setSurfaceBehindAppearAmount(1f)
                keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished(
                    false /* cancelled */)
            }

            // Otherwise, we're doing a normal full-window unlock. Start this animator, which will
            // scale/translate the window underneath the lockscreen.
            else -> {
                Log.d(TAG, "playCannedUnlockAnimation, surfaceBehindEntryAnimator#start")
                surfaceBehindEntryAnimator.start()
            }
        }
    }

    /**
     * Unlock to the launcher, using in-window animations, and the smartspace shared element
     * transition if possible.
     */
    private fun unlockToLauncherWithInWindowAnimations() {
        setSurfaceBehindAppearAmount(1f)

        // Begin the animation, waiting for the shade to animate out.
        launcherUnlockController?.playUnlockAnimation(
            true /* unlocked */,
            LAUNCHER_ICONS_ANIMATION_DURATION_MS /* duration */,
            CANNED_UNLOCK_START_DELAY /* startDelay */)

        // Now that the Launcher surface (with its smartspace positioned identically to ours) is
        // visible, hide our smartspace.
        lockscreenSmartspace!!.visibility = View.INVISIBLE

        // As soon as the shade has animated out of the way, finish the keyguard exit animation. The
        // in-window animations in the Launcher window will end on their own.
        handler.postDelayed({
            if (keyguardViewMediator.get().isShowingAndNotOccluded &&
                !keyguardStateController.isKeyguardGoingAway) {
                    Log.e(TAG, "Finish keyguard exit animation delayed Runnable ran, but we are " +
                            "showing and not going away.")
                return@postDelayed
            }

            keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished(
                false /* cancelled */)
        }, CANNED_UNLOCK_START_DELAY)
    }

    /**
     * Sets the appearance amount of the surface behind the keyguard, according to the current
     * keyguard dismiss amount and the method of dismissal.
     */
    private fun updateSurfaceBehindAppearAmount() {
        if (surfaceBehindRemoteAnimationTarget == null) {
            return
        }

        if (playingCannedUnlockAnimation) {
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
        if (!willHandleUnlockAnimation()) {
            return
        }

        if (keyguardViewController.isShowing && !playingCannedUnlockAnimation) {
            showOrHideSurfaceIfDismissAmountThresholdsReached()

            // If the surface is visible or it's about to be, start updating its appearance to
            // reflect the new dismiss amount.
            if ((keyguardViewMediator.get().requestedShowSurfaceBehindKeyguard() ||
                    keyguardViewMediator.get()
                        .isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe) &&
                    !playingCannedUnlockAnimation) {
                updateSurfaceBehindAppearAmount()
            }
        }
    }

    /**
     * Lets the KeyguardViewMediator know if the dismiss amount has crossed a threshold of interest,
     * such as reaching the point in the dismiss swipe where we need to make the surface behind the
     * keyguard visible.
     */
    private fun showOrHideSurfaceIfDismissAmountThresholdsReached() {
        if (!featureFlags.isEnabled(Flags.NEW_UNLOCK_SWIPE_ANIMATION)) {
            return
        }

        // If we are playing the canned unlock animation, we flung away the keyguard to hide it and
        // started a canned animation to show the surface behind the keyguard. The fling will cause
        // panel height/dismiss amount updates, but we should ignore those updates here since the
        // surface behind is already visible and animating.
        if (playingCannedUnlockAnimation) {
            return
        }

        if (!keyguardStateController.isShowing) {
            return
        }

        val dismissAmount = keyguardStateController.dismissAmount

        if (dismissAmount >= DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD &&
            !keyguardViewMediator.get().requestedShowSurfaceBehindKeyguard()) {

            keyguardViewMediator.get().showSurfaceBehindKeyguard()
        } else if (dismissAmount < DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD &&
                keyguardViewMediator.get().requestedShowSurfaceBehindKeyguard()) {
            // We're no longer past the threshold but we are showing the surface. Animate it
            // out.
            keyguardViewMediator.get().hideSurfaceBehindKeyguard()
            fadeOutSurfaceBehind()
        }

        finishKeyguardExitRemoteAnimationIfReachThreshold()
    }

    /**
     * Hides the keyguard if we're fully dismissed, or if we're swiping to dismiss and have crossed
     * the threshold to finish the dismissal.
     */
    private fun finishKeyguardExitRemoteAnimationIfReachThreshold() {
        // no-op if keyguard is not showing or animation is not enabled.
        if (!KeyguardService.sEnableRemoteKeyguardGoingAwayAnimation ||
                !keyguardViewController.isShowing) {
            return
        }

        // no-op if animation is not requested yet.
        if (!keyguardViewMediator.get().requestedShowSurfaceBehindKeyguard() ||
                !keyguardViewMediator.get().isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe) {
            return
        }

        val dismissAmount = keyguardStateController.dismissAmount
        if (dismissAmount >= 1f ||
                (keyguardStateController.isDismissingFromSwipe &&
                        // Don't hide if we're flinging during a swipe, since we need to finish
                        // animating it out. This will be called again after the fling ends.
                        !keyguardStateController.isFlingingToDismissKeyguardDuringSwipeGesture &&
                        dismissAmount >= DISMISS_AMOUNT_EXIT_KEYGUARD_THRESHOLD)) {
            setSurfaceBehindAppearAmount(1f)
            keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished(false /* cancelled */)
        }
    }

    /**
     * Scales in and translates up the surface behind the keyguard. This is used during unlock
     * animations and swipe gestures to animate the surface's entry (and exit, if the swipe is
     * cancelled).
     */
    fun setSurfaceBehindAppearAmount(amount: Float) {
        if (surfaceBehindRemoteAnimationTarget == null) {
            return
        }

        // Otherwise, animate in the surface's scale/transltion.
        val surfaceHeight: Int = surfaceBehindRemoteAnimationTarget!!.screenSpaceBounds.height()
        val scaleFactor = (SURFACE_BEHIND_START_SCALE_FACTOR +
                (1f - SURFACE_BEHIND_START_SCALE_FACTOR) *
                MathUtils.clamp(amount, 0f, 1f))

        // Scale up from a point at the center-bottom of the surface.
        surfaceBehindMatrix.setScale(
            scaleFactor,
            scaleFactor,
            surfaceBehindRemoteAnimationTarget!!.screenSpaceBounds.width() / 2f,
            surfaceHeight * SURFACE_BEHIND_SCALE_PIVOT_Y
        )

        // Translate up from the bottom.
        surfaceBehindMatrix.postTranslate(
            0f,
            surfaceHeight * SURFACE_BEHIND_START_TRANSLATION_Y * (1f - amount)
        )

        // If we're snapping the keyguard back, immediately begin fading it out.
        val animationAlpha =
            if (keyguardStateController.isSnappingKeyguardBackAfterSwipe) amount
            else surfaceBehindAlpha

        // SyncRtSurfaceTransactionApplier cannot apply transaction when the target view is unable
        // to draw
        val sc: SurfaceControl? = surfaceBehindRemoteAnimationTarget?.leash
        if (keyguardViewController.viewRootImpl.view?.visibility != View.VISIBLE &&
            sc?.isValid == true) {
            with(SurfaceControl.Transaction()) {
                setMatrix(sc, surfaceBehindMatrix, tmpFloat)
                setCornerRadius(sc, roundedCornerRadius)
                setAlpha(sc, animationAlpha)
                apply()
            }
        } else {
            applyParamsToSurface(
                SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(
                    surfaceBehindRemoteAnimationTarget!!.leash)
                    .withMatrix(surfaceBehindMatrix)
                    .withCornerRadius(roundedCornerRadius)
                    .withAlpha(animationAlpha)
                    .build()
            )
        }
    }

    /**
     * Called by [KeyguardViewMediator] to let us know that the remote animation has finished, and
     * we should clean up all of our state.
     *
     * This is generally triggered by us, calling
     * [KeyguardViewMediator.finishSurfaceBehindRemoteAnimation].
     */
    fun notifyFinishedKeyguardExitAnimation(cancelled: Boolean) {
        // Cancel any pending actions.
        handler.removeCallbacksAndMessages(null)

        // Make sure we made the surface behind fully visible, just in case. It should already be
        // fully visible. The exit animation is finished, and we should not hold the leash anymore,
        // so forcing it to 1f.
        surfaceBehindAlphaAnimator.cancel()
        surfaceBehindEntryAnimator.cancel()
        surfaceBehindAlpha = 1f
        setSurfaceBehindAppearAmount(1f)
        launcherUnlockController?.setUnlockAmount(1f, false /* forceIfAnimating */)

        // That target is no longer valid since the animation finished, null it out.
        surfaceBehindRemoteAnimationTarget = null
        surfaceBehindParams = null

        playingCannedUnlockAnimation = false
        willUnlockWithInWindowLauncherAnimations = false
        willUnlockWithSmartspaceTransition = false

        // The lockscreen surface is gone, so it is now safe to re-show the smartspace.
        lockscreenSmartspace?.visibility = View.VISIBLE

        listeners.forEach { it.onUnlockAnimationFinished() }
    }

    /**
     * Asks the keyguard view to hide, using the start time from the beginning of the remote
     * animation.
     */
    fun hideKeyguardViewAfterRemoteAnimation() {
        if (keyguardViewController.isShowing) {
            // Hide the keyguard, with no fade out since we animated it away during the unlock.

            keyguardViewController.hide(
                surfaceBehindRemoteAnimationStartTime,
                0 /* fadeOutDuration */
            )
        } else {
            Log.e(TAG, "#hideKeyguardViewAfterRemoteAnimation called when keyguard view is not " +
                    "showing. Ignoring...")
        }
    }

    private fun applyParamsToSurface(params: SyncRtSurfaceTransactionApplier.SurfaceParams) {
        surfaceTransactionApplier!!.scheduleApply(params)
        surfaceBehindParams = params
    }

    private fun fadeInSurfaceBehind() {
        Log.d(TAG, "fadeInSurfaceBehind")
        surfaceBehindAlphaAnimator.cancel()
        surfaceBehindAlphaAnimator.start()
    }

    private fun fadeOutSurfaceBehind() {
        Log.d(TAG, "fadeOutSurfaceBehind")
        surfaceBehindAlphaAnimator.cancel()
        surfaceBehindAlphaAnimator.reverse()
    }

    private fun shouldPerformSmartspaceTransition(): Boolean {
        // Feature is disabled, so we don't want to.
        if (!featureFlags.isEnabled(Flags.SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED)) {
            return false
        }

        // If our controllers are null, or we haven't received a smartspace state from Launcher yet,
        // we will not be doing any smartspace transitions today.
        if (launcherUnlockController == null ||
            lockscreenSmartspace == null ||
            launcherSmartspaceState == null) {
            return false
        }

        // If the launcher does not have a visible smartspace (either because it's paged off-screen,
        // or the smartspace just doesn't exist), we can't do the transition.
        if ((launcherSmartspaceState?.visibleOnScreen) != true) {
            return false
        }

        // If our launcher isn't underneath, then we're unlocking to an app or custom launcher,
        // neither of which have a smartspace.
        if (!isNexusLauncherUnderneath()) {
            return false
        }

        // TODO(b/213910911): Unfortunately the keyguard is hidden instantly on wake and unlock, so
        // we won't have a lockscreen smartspace to animate. This is sad, and we should fix that!
        if (biometricUnlockControllerLazy.get().isWakeAndUnlock) {
            return false
        }

        // If we can't dismiss the lock screen via a swipe, then the only way we can do the shared
        // element transition is if we're doing a biometric unlock. Otherwise, it means the bouncer
        // is showing, and you can't see the lockscreen smartspace, so a shared element transition
        // would not make sense.
        if (!keyguardStateController.canDismissLockScreen() &&
            !biometricUnlockControllerLazy.get().isBiometricUnlock) {
            return false
        }

        // The smartspace is not visible if the bouncer is showing, so don't shared element it.
        if (keyguardStateController.isBouncerShowing) {
            return false
        }

        // We started to swipe to dismiss, but now we're doing a fling animation to complete the
        // dismiss. In this case, the smartspace swiped away with the rest of the keyguard, so don't
        // do the shared element transition.
        if (keyguardStateController.isFlingingToDismissKeyguardDuringSwipeGesture) {
            return false
        }

        // We don't do the shared element on tablets because they're large and the smartspace has to
        // fly across large distances, which is distracting.
        if (Utilities.isTablet(context)) {
            return false
        }

        return true
    }

    /**
     * Whether we are currently in the process of unlocking the keyguard, and we are performing the
     * shared element SmartSpace transition.
     */
    fun isUnlockingWithSmartSpaceTransition(): Boolean {
        return willUnlockWithSmartspaceTransition
    }

    /**
     * Whether this animation controller will be handling the unlock. We require remote animations
     * to be enabled to do this.
     *
     * If this is not true, nothing in this class is relevant, and the unlock will be handled in
     * [KeyguardViewMediator].
     */
    fun willHandleUnlockAnimation(): Boolean {
        return KeyguardService.sEnableRemoteKeyguardGoingAwayAnimation
    }

    /**
     * Whether the RemoteAnimation on the app/launcher surface behind the keyguard is 'running'.
     */
    fun isAnimatingBetweenKeyguardAndSurfaceBehind(): Boolean {
        return keyguardViewMediator.get().isAnimatingBetweenKeyguardAndSurfaceBehind
    }

    /**
     * Whether we are playing a canned unlock animation, vs. unlocking from a touch gesture such as
     * a swipe.
     */
    fun isPlayingCannedUnlockAnimation(): Boolean {
        return playingCannedUnlockAnimation
    }

    companion object {
        /**
         * Return whether the Google Nexus launcher is underneath the keyguard, vs. some other
         * launcher or an app. If so, we can communicate with it to perform in-window/shared element
         * transitions!
         */
        fun isNexusLauncherUnderneath(): Boolean {
            return ActivityManagerWrapper.getInstance()
                    .runningTask?.topActivity?.className?.equals(
                            QuickStepContract.LAUNCHER_ACTIVITY_CLASS_NAME) ?: false
        }

        fun isFoldable(context: Context): Boolean {
            return context.resources.getIntArray(R.array.config_foldedDeviceStates).isNotEmpty()
        }
    }
}
