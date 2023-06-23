/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.shade

import android.view.MotionEvent
import android.view.ViewGroup
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.statusbar.RemoteInputController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController
import com.android.systemui.statusbar.phone.KeyguardStatusBarView
import com.android.systemui.statusbar.phone.KeyguardStatusBarViewController
import java.util.function.Consumer

/**
 * Controller for the top level shade view
 *
 * @see NotificationPanelViewController
 */
interface ShadeViewController {
    /** Expand the shade either animated or instantly. */
    fun expand(animate: Boolean)

    /** Animates to an expanded shade with QS expanded. If the shade starts expanded, expands QS. */
    fun expandToQs()

    /**
     * Expand shade so that notifications are visible. Non-split shade: just expanding shade or
     * collapsing QS when they're expanded. Split shade: only expanding shade, notifications are
     * always visible
     *
     * Called when `adb shell cmd statusbar expand-notifications` is executed.
     */
    fun expandToNotifications()

    /** Returns whether the shade is expanding or collapsing itself or quick settings. */
    val isExpandingOrCollapsing: Boolean

    /**
     * Returns whether the shade height is greater than zero (i.e. partially or fully expanded),
     * there is a HUN, the shade is animating, or the shade is instantly expanding.
     */
    val isExpanded: Boolean

    /**
     * Returns whether the shade height is greater than zero or the shade is expecting a synthesized
     * down event.
     */
    val isPanelExpanded: Boolean

    /** Returns whether the shade is fully expanded in either QS or QQS. */
    val isShadeFullyExpanded: Boolean

    /**
     * Animates the collapse of a shade with the given delay and the default duration divided by
     * speedUpFactor.
     */
    fun collapse(delayed: Boolean, speedUpFactor: Float)

    /** Collapses the shade. */
    fun collapse(animate: Boolean, delayed: Boolean, speedUpFactor: Float)

    /** Collapses the shade with an animation duration in milliseconds. */
    fun collapseWithDuration(animationDuration: Int)

    /**
     * Animate QS collapse by flinging it. If QS is expanded, it will collapse into QQS and stop. If
     * in split shade, it will collapse the whole shade.
     *
     * @param fullyCollapse Do not stop when QS becomes QQS. Fling until QS isn't visible anymore.
     */
    fun animateCollapseQs(fullyCollapse: Boolean)

    /** Returns whether the shade can be collapsed. */
    fun canBeCollapsed(): Boolean

    /** Returns whether the shade is in the process of collapsing. */
    val isCollapsing: Boolean

    /** Returns whether shade's height is zero. */
    val isFullyCollapsed: Boolean

    /** Returns whether the shade is tracking touches for expand/collapse of the shade or QS. */
    val isTracking: Boolean

    /** Returns whether the shade's top level view is enabled. */
    val isViewEnabled: Boolean

    /** Returns whether status bar icons should be hidden when the shade is expanded. */
    fun shouldHideStatusBarIconsWhenExpanded(): Boolean

    /**
     * Do not let the user drag the shade up and down for the current touch session. This is
     * necessary to avoid shade expansion while/after the bouncer is dismissed.
     */
    fun blockExpansionForCurrentTouch()

    /**
     * Disables the shade header.
     *
     * @see ShadeHeaderController.disable
     */
    fun disableHeader(state1: Int, state2: Int, animated: Boolean)

    /** If the latency tracker is enabled, begins tracking expand latency. */
    fun startExpandLatencyTracking()

    /** Called before animating Keyguard dismissal, i.e. the animation dismissing the bouncer. */
    fun startBouncerPreHideAnimation()

    /** Called once every minute while dozing. */
    fun dozeTimeTick()

    /** Close guts, notification menus, and QS. Set scroll and overscroll to 0. */
    fun resetViews(animate: Boolean)

    /** Returns the StatusBarState. */
    val barState: Int

    /** Returns the NSSL controller. */
    val notificationStackScrollLayoutController: NotificationStackScrollLayoutController

    /** Sets the amount of progress in the status bar launch animation. */
    fun applyLaunchAnimationProgress(linearProgress: Float)

    /**
     * Close the keyguard user switcher if it is open and capable of closing.
     *
     * Has no effect if user switcher isn't supported, if the user switcher is already closed, or if
     * the user switcher uses "simple" mode. The simple user switcher cannot be closed.
     *
     * @return true if the keyguard user switcher was open, and is now closed
     */
    fun closeUserSwitcherIfOpen(): Boolean

    /** Called when Back gesture has been committed (i.e. a back event has definitely occurred) */
    fun onBackPressed()

    /** Sets whether the status bar launch animation is currently running. */
    fun setIsLaunchAnimationRunning(running: Boolean)

    /** Sets the alpha value of the shade to a value between 0 and 255. */
    fun setAlpha(alpha: Int, animate: Boolean)

    /**
     * Sets the runnable to run after the alpha change animation completes.
     *
     * @see .setAlpha
     */
    fun setAlphaChangeAnimationEndAction(r: Runnable)

    /** Sets whether the screen has temporarily woken up to display notifications. */
    fun setPulsing(pulsing: Boolean)

    /** Sets Qs ScrimEnabled and updates QS state. */
    fun setQsScrimEnabled(qsScrimEnabled: Boolean)

    /** Sets the top spacing for the ambient indicator. */
    fun setAmbientIndicationTop(ambientIndicationTop: Int, ambientTextVisible: Boolean)

    /** Updates notification panel-specific flags on [SysUiState]. */
    fun updateSystemUiStateFlags()

    /** Ensures that the touchable region is updated. */
    fun updateTouchableRegion()

    // ******* Begin Keyguard Section *********
    /** Animate to expanded shade after a delay in ms. Used for lockscreen to shade transition. */
    fun transitionToExpandedShade(delay: Long)

    /**
     * Returns whether the unlock hint animation is running. The unlock hint animation is when the
     * user taps the lock screen, causing the contents of the lock screen visually bounce.
     */
    val isUnlockHintRunning: Boolean

    /** @see ViewGroupFadeHelper.reset */
    fun resetViewGroupFade()

    /**
     * Set the alpha and translationY of the keyguard elements which only show on the lockscreen,
     * but not in shade locked / shade. This is used when dragging down to the full shade.
     */
    fun setKeyguardTransitionProgress(keyguardAlpha: Float, keyguardTranslationY: Int)

    /** Sets the overstretch amount in raw pixels when dragging down. */
    fun setOverStretchAmount(amount: Float)

    /**
     * Sets the alpha value to be set on the keyguard status bar.
     *
     * @param alpha value between 0 and 1. -1 if the value is to be reset.
     */
    fun setKeyguardStatusBarAlpha(alpha: Float)

    /**
     * Reconfigures the shade to show the AOD UI (clock, smartspace, etc). This is called by the
     * screen off animation controller in order to animate in AOD without "actually" fully switching
     * to the KEYGUARD state, which is a heavy transition that causes jank as 10+ files react to the
     * change.
     */
    fun showAodUi()

    /**
     * This method should not be used anymore, you should probably use [.isShadeFullyOpen] instead.
     * It was overused as indicating if shade is open or we're on keyguard/AOD. Moving forward we
     * should be explicit about the what state we're checking.
     *
     * @return if panel is covering the screen, which means we're in expanded shade or keyguard/AOD
     */
    @Deprecated(
        "depends on the state you check, use {@link #isShadeFullyExpanded()},\n" +
            "{@link #isOnAod()}, {@link #isOnKeyguard()} instead."
    )
    fun isFullyExpanded(): Boolean

    /** Sends an external (e.g. Status Bar) touch event to the Shade touch handler. */
    fun handleExternalTouch(event: MotionEvent): Boolean

    /** Starts tracking a shade expansion gesture that originated from the status bar. */
    fun startTrackingExpansionFromStatusBar()

    // ******* End Keyguard Section *********

    /** Returns the ShadeHeadsUpTracker. */
    val shadeHeadsUpTracker: ShadeHeadsUpTracker

    /** Returns the ShadeFoldAnimator. */
    val shadeFoldAnimator: ShadeFoldAnimator

    /** Returns the ShadeNotificationPresenter. */
    val shadeNotificationPresenter: ShadeNotificationPresenter

    companion object {
        /**
         * Returns a multiplicative factor to use when determining the falsing threshold for touches
         * on the shade. The factor will be larger when the device is waking up due to a touch or
         * gesture.
         */
        @JvmStatic
        fun getFalsingThresholdFactor(wakefulness: WakefulnessModel): Float {
            return if (wakefulness.isDeviceInteractiveFromTapOrGesture()) 1.5f else 1.0f
        }

        const val WAKEUP_ANIMATION_DELAY_MS = 250
        const val FLING_MAX_LENGTH_SECONDS = 0.6f
        const val FLING_SPEED_UP_FACTOR = 0.6f
        const val FLING_CLOSING_MAX_LENGTH_SECONDS = 0.6f
        const val FLING_CLOSING_SPEED_UP_FACTOR = 0.6f

        /** Fling expanding QS. */
        const val FLING_EXPAND = 0

        /** Fling collapsing QS, potentially stopping when QS becomes QQS. */
        const val FLING_COLLAPSE = 1

        /** Fling until QS is completely hidden. */
        const val FLING_HIDE = 2
    }
}

/** Manages listeners for when users begin expanding the shade from a HUN. */
interface ShadeHeadsUpTracker {
    /** Add a listener for when the user starts expanding the shade from a HUN. */
    fun addTrackingHeadsUpListener(listener: Consumer<ExpandableNotificationRow>)

    /** Remove a listener for when the user starts expanding the shade from a HUN. */
    fun removeTrackingHeadsUpListener(listener: Consumer<ExpandableNotificationRow>)

    /** Set the controller for the appearance of HUNs in the icon area and the header itself. */
    fun setHeadsUpAppearanceController(headsUpAppearanceController: HeadsUpAppearanceController?)

    /** The notification row that was touched to initiate shade expansion. */
    val trackedHeadsUpNotification: ExpandableNotificationRow?
}

/** Handles the lifecycle of the shade's animation that happens when folding a foldable. */
interface ShadeFoldAnimator {
    /** Updates the views to the initial state for the fold to AOD animation. */
    fun prepareFoldToAodAnimation()

    /**
     * Starts fold to AOD animation.
     *
     * @param startAction invoked when the animation starts.
     * @param endAction invoked when the animation finishes, also if it was cancelled.
     * @param cancelAction invoked when the animation is cancelled, before endAction.
     */
    fun startFoldToAodAnimation(startAction: Runnable, endAction: Runnable, cancelAction: Runnable)

    /** Cancels fold to AOD transition and resets view state. */
    fun cancelFoldToAodAnimation()

    /** Returns the main view of the shade. */
    val view: ViewGroup
}

/** Handles the shade's interactions with StatusBarNotificationPresenter. */
interface ShadeNotificationPresenter {
    /** Returns a new delegate for some view controller pieces of the remote input process. */
    fun createRemoteInputDelegate(): RemoteInputController.Delegate

    /** Returns whether the screen has temporarily woken up to display notifications. */
    fun hasPulsingNotifications(): Boolean
}

/**
 * An interface that provides the current state of the notification panel and related views, which
 * is needed to calculate [KeyguardStatusBarView]'s state in [KeyguardStatusBarViewController].
 */
interface ShadeViewStateProvider {
    /** Returns the expanded height of the panel view. */
    val panelViewExpandedHeight: Float

    /**
     * Returns true if heads up should be visible.
     *
     * TODO(b/138786270): If HeadsUpAppearanceController was injectable, we could inject it into
     *   [KeyguardStatusBarViewController] and remove this method.
     */
    fun shouldHeadsUpBeVisible(): Boolean

    /** Return the fraction of the shade that's expanded, when in lockscreen. */
    val lockscreenShadeDragProgress: Float
}
