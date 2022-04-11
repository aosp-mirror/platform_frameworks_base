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
import android.provider.Settings
import android.util.Log
import android.view.RemoteAnimationTarget
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
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.shared.system.smartspace.ISysuiUnlockAnimationController
import com.android.systemui.shared.system.smartspace.SmartspaceState
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.min

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
const val DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD = 0.25f

/**
 * Dismiss amount at which to complete the keyguard exit animation and hide the keyguard.
 *
 * The dismiss amount is the inverse of the notification panel expansion, which decreases as the
 * lock screen is swiped away.
 */
const val DISMISS_AMOUNT_EXIT_KEYGUARD_THRESHOLD = 0.4f

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
const val SURFACE_BEHIND_SWIPE_FADE_DURATION_MS = 150L

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
    private val biometricUnlockControllerLazy: Lazy<BiometricUnlockController>
) : KeyguardStateController.Callback, ISysuiUnlockAnimationController.Stub() {

    interface KeyguardUnlockAnimationListener {
        /**
         * Called when the remote unlock animation, controlled by
         * [KeyguardUnlockAnimationController], first starts.
         *
         * [playingCannedAnimation] indicates whether we are playing a canned animation to show the
         * app/launcher behind the keyguard, vs. this being a swipe to unlock where the dismiss
         * amount drives the animation.
         * [fromWakeAndUnlock] tells us whether we are unlocking directly from AOD - in this case,
         * the lockscreen is dismissed instantly, so we shouldn't run any animations that rely on it
         * being visible.
         */
        @JvmDefault
        fun onUnlockAnimationStarted(playingCannedAnimation: Boolean, fromWakeAndUnlock: Boolean) {}

        /**
         * Called when the remote unlock animation ends, in all cases, canned or swipe-to-unlock.
         * The keyguard is no longer visible in this state and the app/launcher behind the keyguard
         * is now completely visible.
         */
        @JvmDefault
        fun onUnlockAnimationFinished() {}

        /**
         * Called when we begin the smartspace shared element transition, either due to an unlock
         * action (biometric, etc.) or a swipe to unlock.
         *
         * This transition can begin BEFORE [onUnlockAnimationStarted] is called, if we are swiping
         * to unlock and the surface behind the keyguard has not yet been made visible. This is
         * because the lockscreen smartspace immediately begins moving towards the launcher
         * smartspace location when a swipe begins, even before we start the keyguard exit remote
         * animation and show the launcher itself.
         */
        @JvmDefault
        fun onSmartspaceSharedElementTransitionStarted() {}
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

        // If the provided callback dies, set it to null. We'll always check whether it's null
        // to avoid DeadObjectExceptions.
        callback?.asBinder()?.linkToDeath({
            launcherUnlockController = null
            launcherSmartspaceState = null
        }, 0 /* flags */)
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
    private var surfaceBehindAlphaAnimator = ValueAnimator.ofFloat(0f, 1f)
    private var smartspaceAnimator = ValueAnimator.ofFloat(0f, 1f)

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
     * Whether we tried to start the SmartSpace shared element transition for this unlock swipe.
     * It's possible we were unable to do so (if the Launcher SmartSpace is not available), and we
     * need to keep track of that so that we don't start doing it halfway through the swipe if
     * Launcher becomes available suddenly.
     */
    private var attemptedSmartSpaceTransitionForThisSwipe = false

    /**
     * The original location of the lockscreen smartspace on the screen.
     */
    private val smartspaceOriginBounds = Rect()

    /**
     * The bounds to which the lockscreen smartspace is moving. This is set to the bounds of the
     * launcher's smartspace prior to the transition starting.
     */
    private val smartspaceDestBounds = Rect()

    /**
     * From 0f to 1f, the progress of the smartspace shared element animation. 0f means the
     * smartspace is at its normal position within the lock screen hierarchy, and 1f means it has
     * fully animated to the location of the Launcher's smartspace.
     */
    private var smartspaceUnlockProgress = 0f

    /**
     * Whether we're currently unlocking, and we're talking to Launcher to perform in-window
     * animations rather than simply animating the Launcher window like any other app. This can be
     * true while [unlockingWithSmartspaceTransition] is false, if the smartspace is not available
     * or was not ready in time.
     */
    private var unlockingToLauncherWithInWindowAnimations: Boolean = false

    /**
     * Whether we are currently unlocking, and the smartspace shared element transition is in
     * progress. If true, we're also [unlockingToLauncherWithInWindowAnimations].
     */
    private var unlockingWithSmartspaceTransition: Boolean = false

    private val handler = Handler()

    init {
        with(surfaceBehindAlphaAnimator) {
            duration = SURFACE_BEHIND_SWIPE_FADE_DURATION_MS
            interpolator = Interpolators.TOUCH_RESPONSE
            addUpdateListener { valueAnimator: ValueAnimator ->
                surfaceBehindAlpha = valueAnimator.animatedValue as Float
                updateSurfaceBehindAppearAmount()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // If the surface alpha is 0f, it's no longer visible so we can safely be done
                    // with the animation even if other properties are still animating.
                    if (surfaceBehindAlpha == 0f) {
                        keyguardViewMediator.get().finishSurfaceBehindRemoteAnimation(
                            false /* cancelled */)
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
                    playingCannedUnlockAnimation = false
                    keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished(
                        false /* cancelled */
                    )
                }
            })
        }

        with(smartspaceAnimator) {
            duration = UNLOCK_ANIMATION_DURATION_MS
            interpolator = Interpolators.TOUCH_RESPONSE
            addUpdateListener {
                smartspaceUnlockProgress = it.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    launcherUnlockController?.setSmartspaceVisibility(View.VISIBLE)
                    keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished(
                        false /* cancelled */)
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
     * as opposed to being called because the device was unlocked and the keyguard is going away.
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

        // If we specifically requested that the surface behind be made visible, it means we are
        // swiping to unlock. In that case, the surface visibility is tied to the dismiss amount,
        // and we'll handle that in onKeyguardDismissAmountChanged(). If we didn't request that, the
        // keyguard is being dismissed for a different reason (biometric auth, etc.) and we should
        // play a canned animation to make the surface fully visible.
        if (!requestedShowSurfaceBehindKeyguard) {
            playCannedUnlockAnimation()
        }

        listeners.forEach {
            it.onUnlockAnimationStarted(
                playingCannedUnlockAnimation /* playingCannedAnimation */,
                biometricUnlockControllerLazy.get().isWakeAndUnlock /* isWakeAndUnlock */) }

        // Finish the keyguard remote animation if the dismiss amount has crossed the threshold.
        // Check it here in case there is no more change to the dismiss amount after the last change
        // that starts the keyguard animation. @see #updateKeyguardViewMediatorIfThresholdsReached()
        finishKeyguardExitRemoteAnimationIfReachThreshold()
    }

    /**
     * Called by [KeyguardViewMediator] to let us know that the remote animation has finished, and
     * we should clean up all of our state.
     */
    fun notifyFinishedKeyguardExitAnimation(cancelled: Boolean) {
        // Cancel any pending actions.
        handler.removeCallbacksAndMessages(null)

        // Make sure we made the surface behind fully visible, just in case. It should already be
        // fully visible.
        setSurfaceBehindAppearAmount(1f)
        launcherUnlockController?.setUnlockAmount(1f)
        smartspaceDestBounds.setEmpty()

        // That target is no longer valid since the animation finished, null it out.
        surfaceBehindRemoteAnimationTarget = null
        surfaceBehindParams = null

        playingCannedUnlockAnimation = false
        unlockingToLauncherWithInWindowAnimations = false
        unlockingWithSmartspaceTransition = false
        resetSmartspaceTransition()

        listeners.forEach { it.onUnlockAnimationFinished() }
    }

    /**
     * Play a canned unlock animation to unlock the device. This is used when we were *not* swiping
     * to unlock using a touch gesture. If we were swiping to unlock, the animation will be driven
     * by the dismiss amount via [onKeyguardDismissAmountChanged].
     */
    fun playCannedUnlockAnimation() {
        playingCannedUnlockAnimation = true

        if (canPerformInWindowLauncherAnimations()) {
            // If possible, use the neat in-window animations to unlock to the launcher.
            unlockToLauncherWithInWindowAnimations()
        } else if (!biometricUnlockControllerLazy.get().isWakeAndUnlock) {
            // If the launcher isn't behind the keyguard, or the launcher unlock controller is not
            // available, animate in the entire window.
            surfaceBehindEntryAnimator.start()
        } else {
            setSurfaceBehindAppearAmount(1f)
            keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished(false)
        }

        // If this is a wake and unlock, hide the lockscreen immediately. In the future, we should
        // animate it out nicely instead, but to the current state of wake and unlock, not hiding it
        // causes a lot of issues.
        // TODO(b/210016643): Not this, it looks not-ideal!
        if (biometricUnlockControllerLazy.get().isWakeAndUnlock) {
            keyguardViewController.hide(surfaceBehindRemoteAnimationStartTime, 350)
        }
    }

    /**
     * Unlock to the launcher, using in-window animations, and the smartspace shared element
     * transition if possible.
     */
    private fun unlockToLauncherWithInWindowAnimations() {
        // See if we can do the smartspace transition, and if so, do it!
        if (prepareForSmartspaceTransition()) {
            animateSmartspaceToDestination()
            listeners.forEach { it.onSmartspaceSharedElementTransitionStarted() }
        }

        val startDelay = Settings.Secure.getLong(
            context.contentResolver, "unlock_start_delay", CANNED_UNLOCK_START_DELAY)
        val duration = Settings.Secure.getLong(
            context.contentResolver, "unlock_duration", LAUNCHER_ICONS_ANIMATION_DURATION_MS)

        unlockingToLauncherWithInWindowAnimations = true
        prepareLauncherWorkspaceForUnlockAnimation()

        // Begin the animation, waiting for the shade to animate out.
        launcherUnlockController?.playUnlockAnimation(
            true /* unlocked */,
            duration /* duration */,
            startDelay /* startDelay */)

        handler.postDelayed({
            applyParamsToSurface(
                SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(
                    surfaceBehindRemoteAnimationTarget!!.leash)
                    .withAlpha(1f)
                    .build())
        }, startDelay)

        if (!unlockingWithSmartspaceTransition) {
            // If we are not unlocking with the smartspace transition, wait for the unlock animation
            // to end and then finish the remote animation. If we are using the smartspace
            // transition, it will finish the remote animation once it ends.
            handler.postDelayed({
                keyguardViewMediator.get().onKeyguardExitRemoteAnimationFinished(
                    false /* cancelled */)
            }, UNLOCK_ANIMATION_DURATION_MS)
        }
    }

    /**
     * Asks Launcher to prepare the workspace to be unlocked. This sets up the animation and makes
     * the page invisible.
     */
    private fun prepareLauncherWorkspaceForUnlockAnimation() {
        // Tell the launcher to prepare for the animation by setting its views invisible and
        // syncing the selected smartspace pages.
        launcherUnlockController?.prepareForUnlock(
            unlockingWithSmartspaceTransition /* willAnimateSmartspace */,
            (lockscreenSmartspace as BcSmartspaceDataPlugin.SmartspaceView?)?.selectedPage ?: -1)
    }

    /**
     * Animates the lockscreen smartspace all the way to the launcher's smartspace location, then
     * makes the launcher smartspace visible and ends the remote animation.
     */
    private fun animateSmartspaceToDestination() {
        smartspaceAnimator.start()
    }

    /**
     * Reset the lockscreen smartspace's position, and reset all state involving the smartspace
     * transition.
     */
    public fun resetSmartspaceTransition() {
        unlockingWithSmartspaceTransition = false
        smartspaceUnlockProgress = 0f

        lockscreenSmartspace?.post {
            lockscreenSmartspace!!.translationX = 0f
            lockscreenSmartspace!!.translationY = 0f
        }
    }

    /**
     * Moves the lockscreen smartspace towards the launcher smartspace's position.
     */
    private fun setSmartspaceProgressToDestinationBounds(progress: Float) {
        if (smartspaceDestBounds.isEmpty) {
            return
        }

        val progressClamped = min(1f, progress)

        // Calculate the distance (relative to the origin) that we need to be for the current
        // progress value.
        val progressX =
                (smartspaceDestBounds.left - smartspaceOriginBounds.left) * progressClamped
        val progressY =
                (smartspaceDestBounds.top - smartspaceOriginBounds.top) * progressClamped

        val lockscreenSmartspaceCurrentBounds = Rect().also {
            lockscreenSmartspace!!.getBoundsOnScreen(it)
        }

        // Figure out how far that is from our present location on the screen. This approach
        // compensates for the fact that our parent container is also translating to animate out.
        val dx = smartspaceOriginBounds.left + progressX -
                lockscreenSmartspaceCurrentBounds.left
        val dy = smartspaceOriginBounds.top + progressY -
                lockscreenSmartspaceCurrentBounds.top

        with(lockscreenSmartspace!!) {
            translationX += dx
            translationY += dy
        }
    }

    /**
     * Update the lockscreen SmartSpace to be positioned according to the current dismiss amount. As
     * the dismiss amount increases, we will increase our SmartSpace's progress to the destination
     * bounds (the location of the Launcher SmartSpace).
     *
     * This is used by [KeyguardClockSwitchController] to keep the smartspace position updated as
     * the clock is swiped away.
     */
    fun updateLockscreenSmartSpacePosition() {
        setSmartspaceProgressToDestinationBounds(smartspaceUnlockProgress)
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

    /**
     * Scales in and translates up the surface behind the keyguard. This is used during unlock
     * animations and swipe gestures to animate the surface's entry (and exit, if the swipe is
     * cancelled).
     */
    fun setSurfaceBehindAppearAmount(amount: Float) {
        if (surfaceBehindRemoteAnimationTarget == null) {
            return
        }

        if (unlockingToLauncherWithInWindowAnimations) {
            // If we aren't using the canned unlock animation (which would be setting the unlock
            // amount in its update listener), do it here.
            if (!isPlayingCannedUnlockAnimation()) {
                launcherUnlockController?.setUnlockAmount(amount)

                if (surfaceBehindParams?.alpha?.let { it < 1f } != false) {
                    applyParamsToSurface(
                        SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(
                            surfaceBehindRemoteAnimationTarget!!.leash)
                            .withAlpha(1f)
                            .build())
                }
            }
        } else {
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

            applyParamsToSurface(
                SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(
                    surfaceBehindRemoteAnimationTarget!!.leash)
                .withMatrix(surfaceBehindMatrix)
                .withCornerRadius(roundedCornerRadius)
                .withAlpha(animationAlpha)
                .build())
        }
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

        if (keyguardViewController.isShowing) {
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

        // The end of the SmartSpace transition can occur after the keyguard is hidden (when we tell
        // Launcher's SmartSpace to become visible again), so update it even if the keyguard view is
        // no longer showing.
        applyDismissAmountToSmartspaceTransition()
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

        val dismissAmount = keyguardStateController.dismissAmount
        if (dismissAmount >= DISMISS_AMOUNT_SHOW_SURFACE_THRESHOLD &&
                !keyguardViewMediator.get().requestedShowSurfaceBehindKeyguard()) {
            // We passed the threshold, and we're not yet showing the surface behind the
            // keyguard. Animate it in.
            if (!unlockingToLauncherWithInWindowAnimations &&
                canPerformInWindowLauncherAnimations()) {
                unlockingToLauncherWithInWindowAnimations = true
                prepareLauncherWorkspaceForUnlockAnimation()
            }
            keyguardViewMediator.get().showSurfaceBehindKeyguard()
            fadeInSurfaceBehind()
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
     * Updates flags related to the SmartSpace transition in response to a change in keyguard
     * dismiss amount, and also updates the SmartSpaceTransitionController, which will let Launcher
     * know if it needs to do something as a result.
     */
    private fun applyDismissAmountToSmartspaceTransition() {
        if (!featureFlags.isEnabled(Flags.SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED)) {
            return
        }

        // If we are playing the canned animation, the smartspace is being animated directly between
        // its original location and the location of the launcher smartspace by smartspaceAnimator.
        // We can ignore the dismiss amount, which is caused by panel height changes as the panel is
        // flung away.
        if (playingCannedUnlockAnimation) {
            return
        }

        val dismissAmount = keyguardStateController.dismissAmount

        // If we've begun a swipe, and haven't yet tried doing the SmartSpace transition, do that
        // now.
        if (!attemptedSmartSpaceTransitionForThisSwipe &&
            keyguardViewController.isShowing &&
            dismissAmount > 0f &&
            dismissAmount < 1f) {
            attemptedSmartSpaceTransitionForThisSwipe = true

            if (prepareForSmartspaceTransition()) {
                unlockingWithSmartspaceTransition = true

                // Ensure that the smartspace is invisible if we're doing the transition, and
                // visible if we aren't.
                launcherUnlockController?.setSmartspaceVisibility(
                    if (unlockingWithSmartspaceTransition) View.INVISIBLE else View.VISIBLE)

                if (unlockingWithSmartspaceTransition) {
                    listeners.forEach { it.onSmartspaceSharedElementTransitionStarted() }
                }
            }
        } else if (attemptedSmartSpaceTransitionForThisSwipe &&
            (dismissAmount == 0f || dismissAmount == 1f)) {
            attemptedSmartSpaceTransitionForThisSwipe = false
            unlockingWithSmartspaceTransition = false
            launcherUnlockController?.setSmartspaceVisibility(View.VISIBLE)
        }

        if (unlockingWithSmartspaceTransition) {
            val swipedFraction: Float = keyguardStateController.dismissAmount
            val progress = swipedFraction / DISMISS_AMOUNT_EXIT_KEYGUARD_THRESHOLD
            smartspaceUnlockProgress = progress
            setSmartspaceProgressToDestinationBounds(smartspaceUnlockProgress)
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

    /**
     * Prepare for the smartspace shared element transition, if possible, by figuring out where we
     * are animating from/to.
     *
     * Return true if we'll be able to do the smartspace transition, or false if conditions are not
     * right to do it right now.
     */
    private fun prepareForSmartspaceTransition(): Boolean {
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

        unlockingWithSmartspaceTransition = true
        smartspaceDestBounds.setEmpty()

        // Assuming we were able to retrieve the launcher's state, start the lockscreen
        // smartspace at 0, 0, and save its starting bounds.
        with(lockscreenSmartspace!!) {
            translationX = 0f
            translationY = 0f
            getBoundsOnScreen(smartspaceOriginBounds)
        }

        // Set the destination bounds to the launcher smartspace's bounds, offset by any
        // padding on our smartspace.
        with(smartspaceDestBounds) {
            set(launcherSmartspaceState!!.boundsOnScreen)
            offset(-lockscreenSmartspace!!.paddingLeft, -lockscreenSmartspace!!.paddingTop)
        }

        return true
    }

    /**
     * Whether we should be able to do the in-window launcher animations given the current state of
     * the device.
     */
    fun canPerformInWindowLauncherAnimations(): Boolean {
        return isNexusLauncherUnderneath() &&
                launcherUnlockController != null &&
                // Temporarily disable for foldables since foldable launcher has two first pages,
                // which breaks the in-window animation.
                !isFoldable(context)
    }

    /**
     * Whether we are currently in the process of unlocking the keyguard, and we are performing the
     * shared element SmartSpace transition.
     */
    fun isUnlockingWithSmartSpaceTransition(): Boolean {
        return unlockingWithSmartspaceTransition
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