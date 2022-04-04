package com.android.systemui.statusbar.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import android.view.View
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.jank.InteractionJankMonitor.CUJ_SCREEN_OFF
import com.android.internal.jank.InteractionJankMonitor.CUJ_SCREEN_OFF_SHOW_AOD
import com.android.systemui.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.StatusBarStateControllerImpl
import com.android.systemui.statusbar.notification.AnimatableProperty
import com.android.systemui.statusbar.notification.PropertyAnimator
import com.android.systemui.statusbar.notification.stack.AnimationProperties
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject

/**
 * When to show the keyguard (AOD) view. This should be once the light reveal scrim is barely
 * visible, because the transition to KEYGUARD causes brief jank.
 */
private const val ANIMATE_IN_KEYGUARD_DELAY = 600L

/**
 * Duration for the light reveal portion of the animation.
 */
private const val LIGHT_REVEAL_ANIMATION_DURATION = 750L

/**
 * Controller for the unlocked screen off animation, which runs when the device is going to sleep
 * and we're unlocked.
 *
 * This animation uses a [LightRevealScrim] that lives in the status bar to hide the screen contents
 * and then animates in the AOD UI.
 */
@SysUISingleton
class UnlockedScreenOffAnimationController @Inject constructor(
    private val context: Context,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val statusBarStateControllerImpl: StatusBarStateControllerImpl,
    private val keyguardViewMediatorLazy: dagger.Lazy<KeyguardViewMediator>,
    private val keyguardStateController: KeyguardStateController,
    private val dozeParameters: dagger.Lazy<DozeParameters>,
    private val globalSettings: GlobalSettings,
    private val interactionJankMonitor: InteractionJankMonitor,
    private val powerManager: PowerManager,
    private val handler: Handler = Handler()
) : WakefulnessLifecycle.Observer, ScreenOffAnimation {
    private lateinit var mCentralSurfaces: CentralSurfaces
    /**
     * Whether or not [initialize] has been called to provide us with the StatusBar,
     * NotificationPanelViewController, and LightRevealSrim so that we can run the unlocked screen
     * off animation.
     */
    private var initialized = false

    private lateinit var lightRevealScrim: LightRevealScrim

    private var animatorDurationScale = 1f
    private var shouldAnimateInKeyguard = false
    private var lightRevealAnimationPlaying = false
    private var aodUiAnimationPlaying = false

    /**
     * The result of our decision whether to play the screen off animation in
     * [onStartedGoingToSleep], or null if we haven't made that decision yet or aren't going to
     * sleep.
     */
    private var decidedToAnimateGoingToSleep: Boolean? = null

    private val lightRevealAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = LIGHT_REVEAL_ANIMATION_DURATION
        interpolator = Interpolators.LINEAR
        addUpdateListener {
            lightRevealScrim.revealAmount = it.animatedValue as Float
            if (lightRevealScrim.isScrimAlmostOccludes &&
                    interactionJankMonitor.isInstrumenting(CUJ_SCREEN_OFF)) {
                // ends the instrument when the scrim almost occludes the screen.
                // because the following janky frames might not be perceptible.
                interactionJankMonitor.end(CUJ_SCREEN_OFF)
            }
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator?) {
                lightRevealScrim.revealAmount = 1f
                lightRevealAnimationPlaying = false
                interactionJankMonitor.cancel(CUJ_SCREEN_OFF)
            }

            override fun onAnimationEnd(animation: Animator?) {
                lightRevealAnimationPlaying = false
                interactionJankMonitor.end(CUJ_SCREEN_OFF)
            }

            override fun onAnimationStart(animation: Animator?) {
                interactionJankMonitor.begin(
                    mCentralSurfaces.notificationShadeWindowView, CUJ_SCREEN_OFF)
            }
        })
    }

    val animatorDurationScaleObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            updateAnimatorDurationScale()
        }
    }

    override fun initialize(
        centralSurfaces: CentralSurfaces,
        lightRevealScrim: LightRevealScrim
    ) {
        this.initialized = true
        this.lightRevealScrim = lightRevealScrim
        this.mCentralSurfaces = centralSurfaces

        updateAnimatorDurationScale()
        globalSettings.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                /* notify for descendants */ false,
                animatorDurationScaleObserver)
        wakefulnessLifecycle.addObserver(this)
    }

    fun updateAnimatorDurationScale() {
        animatorDurationScale =
                globalSettings.getFloat(Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    override fun shouldDelayKeyguardShow(): Boolean =
        shouldPlayAnimation()

    override fun isKeyguardShowDelayed(): Boolean =
        isAnimationPlaying()

    /**
     * Animates in the provided keyguard view, ending in the same position that it will be in on
     * AOD.
     */
    override fun animateInKeyguard(keyguardView: View, after: Runnable) {
        shouldAnimateInKeyguard = false
        keyguardView.alpha = 0f
        keyguardView.visibility = View.VISIBLE

        val currentY = keyguardView.y

        // Move the keyguard up by 10% so we can animate it back down.
        keyguardView.y = currentY - keyguardView.height * 0.1f

        val duration = StackStateAnimator.ANIMATION_DURATION_WAKEUP

        // We animate the Y properly separately using the PropertyAnimator, as the panel
        // view also needs to update the end position.
        PropertyAnimator.cancelAnimation(keyguardView, AnimatableProperty.Y)
        PropertyAnimator.setProperty<View>(keyguardView, AnimatableProperty.Y, currentY,
                AnimationProperties().setDuration(duration.toLong()),
                true /* animate */)

        keyguardView.animate()
                .setDuration(duration.toLong())
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(1f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        aodUiAnimationPlaying = false

                        // Lock the keyguard if it was waiting for the screen off animation to end.
                        keyguardViewMediatorLazy.get().maybeHandlePendingLock()

                        // Tell the CentralSurfaces to become keyguard for real - we waited on that
                        // since it is slow and would have caused the animation to jank.
                        mCentralSurfaces.updateIsKeyguard()

                        // Run the callback given to us by the KeyguardVisibilityHelper.
                        after.run()

                        // Done going to sleep, reset this flag.
                        decidedToAnimateGoingToSleep = null
                        // We need to unset the listener. These are persistent for future animators
                        keyguardView.animate().setListener(null)
                        interactionJankMonitor.end(CUJ_SCREEN_OFF_SHOW_AOD)
                    }

                    override fun onAnimationCancel(animation: Animator?) {
                        interactionJankMonitor.cancel(CUJ_SCREEN_OFF_SHOW_AOD)
                    }

                    override fun onAnimationStart(animation: Animator?) {
                        interactionJankMonitor.begin(
                                mCentralSurfaces.notificationShadeWindowView,
                                CUJ_SCREEN_OFF_SHOW_AOD)
                    }
                })
                .start()
    }

    override fun onStartedWakingUp() {
        // Waking up, so reset this flag.
        decidedToAnimateGoingToSleep = null

        shouldAnimateInKeyguard = false
        lightRevealAnimator.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onFinishedWakingUp() {
        // Set this to false in onFinishedWakingUp rather than onStartedWakingUp so that other
        // observers (such as CentralSurfaces) can ask us whether we were playing the screen off
        // animation and reset accordingly.
        aodUiAnimationPlaying = false

        // If we can't control the screen off animation, we shouldn't mess with the
        // CentralSurfaces's keyguard state unnecessarily.
        if (dozeParameters.get().canControlUnlockedScreenOff()) {
            // Make sure the status bar is in the correct keyguard state, forcing it if necessary.
            // This is required if the screen off animation is cancelled, since it might be
            // incorrectly left in the KEYGUARD or SHADE states depending on when it was cancelled
            // and whether 'lock instantly' is enabled. We need to force it so that the state is set
            // even if we're going from SHADE to SHADE or KEYGUARD to KEYGUARD, since we might have
            // changed parts of the UI (such as showing AOD in the shade) without actually changing
            // the StatusBarState. This ensures that the UI definitely reflects the desired state.
            mCentralSurfaces.updateIsKeyguard(true /* forceStateChange */)
        }
    }

    override fun startAnimation(): Boolean {
        if (shouldPlayUnlockedScreenOffAnimation()) {
            decidedToAnimateGoingToSleep = true

            shouldAnimateInKeyguard = true
            lightRevealAnimationPlaying = true
            lightRevealAnimator.start()
            handler.postDelayed({
                // Only run this callback if the device is sleeping (not interactive). This callback
                // is removed in onStartedWakingUp, but since that event is asynchronously
                // dispatched, a race condition could make it possible for this callback to be run
                // as the device is waking up. That results in the AOD UI being shown while we wake
                // up, with unpredictable consequences.
                if (!powerManager.isInteractive) {
                    aodUiAnimationPlaying = true

                    // Show AOD. That'll cause the KeyguardVisibilityHelper to call
                    // #animateInKeyguard.
                    mCentralSurfaces.notificationPanelViewController.showAodUi()
                }
            }, (ANIMATE_IN_KEYGUARD_DELAY * animatorDurationScale).toLong())

            return true
        } else {
            decidedToAnimateGoingToSleep = false
            return false
        }
    }

    /**
     * Whether we want to play the screen off animation when the phone starts going to sleep, based
     * on the current state of the device.
     */
    fun shouldPlayUnlockedScreenOffAnimation(): Boolean {
        // If we haven't been initialized yet, we don't have a StatusBar/LightRevealScrim yet, so we
        // can't perform the animation.
        if (!initialized) {
            return false
        }

        // If the device isn't in a state where we can control unlocked screen off (no AOD enabled,
        // power save, etc.) then we shouldn't try to do so.
        if (!dozeParameters.get().canControlUnlockedScreenOff()) {
            return false
        }

        // If we explicitly already decided not to play the screen off animation, then never change
        // our mind.
        if (decidedToAnimateGoingToSleep == false) {
            return false
        }

        // If animations are disabled system-wide, don't play this one either.
        if (Settings.Global.getString(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == "0") {
            return false
        }

        // We only play the unlocked screen off animation if we are... unlocked.
        if (statusBarStateControllerImpl.state != StatusBarState.SHADE) {
            return false
        }

        // We currently draw both the light reveal scrim, and the AOD UI, in the shade. If it's
        // already expanded and showing notifications/QS, the animation looks really messy. For now,
        // disable it if the notification panel is not fully collapsed.
        if ((!this::mCentralSurfaces.isInitialized ||
                !mCentralSurfaces.notificationPanelViewController.isFullyCollapsed) &&
                // Status bar might be expanded because we have started
                // playing the animation already
                !isAnimationPlaying()
        ) {
            return false
        }

        // If we're not allowed to rotate the keyguard, it can only be displayed in zero-degree
        // portrait. If we're in another orientation, disable the screen off animation so we don't
        // animate in the keyguard AOD UI sideways or upside down.
        if (!keyguardStateController.isKeyguardScreenRotationAllowed &&
            context.display.rotation != Surface.ROTATION_0) {
            return false
        }

        // Otherwise, good to go.
        return true
    }

    override fun shouldDelayDisplayDozeTransition(): Boolean =
        shouldPlayUnlockedScreenOffAnimation()

    /**
     * Whether we're doing the light reveal animation or we're done with that and animating in the
     * AOD UI.
     */
    override fun isAnimationPlaying(): Boolean {
        return lightRevealAnimationPlaying || aodUiAnimationPlaying
    }

    override fun shouldAnimateInKeyguard(): Boolean =
        shouldAnimateInKeyguard

    override fun shouldHideScrimOnWakeUp(): Boolean =
        isScreenOffLightRevealAnimationPlaying()

    override fun overrideNotificationsDozeAmount(): Boolean =
        shouldPlayUnlockedScreenOffAnimation() && isAnimationPlaying()

    override fun shouldShowAodIconsWhenShade(): Boolean =
        isAnimationPlaying()

    override fun shouldAnimateAodIcons(): Boolean =
        shouldPlayUnlockedScreenOffAnimation()

    override fun shouldPlayAnimation(): Boolean =
        shouldPlayUnlockedScreenOffAnimation()

    /**
     * Whether the light reveal animation is playing. The second part of the screen off animation,
     * where AOD animates in, might still be playing if this returns false.
     */
    fun isScreenOffLightRevealAnimationPlaying(): Boolean {
        return lightRevealAnimationPlaying
    }
}
