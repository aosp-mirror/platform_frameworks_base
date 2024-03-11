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
import android.view.ViewTreeObserver
import com.android.systemui.power.shared.model.WakefulnessModel
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
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
    /** Returns whether the shade is expanding or collapsing itself or quick settings. */
    val isExpandingOrCollapsing: Boolean

    /**
     * Returns whether the shade height is greater than zero or the shade is expecting a synthesized
     * down event.
     */
    val isPanelExpanded: Boolean

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
     * Disables the shade header.
     *
     * @see ShadeHeaderController.disable
     */
    fun disableHeader(state1: Int, state2: Int, animated: Boolean)

    /** If the latency tracker is enabled, begins tracking expand latency. */
    fun startExpandLatencyTracking()

    /** Returns the StatusBarState. */
    val barState: Int

    /** Sets the alpha value of the shade to a value between 0 and 255. */
    fun setAlpha(alpha: Int, animate: Boolean)

    /**
     * Sets the runnable to run after the alpha change animation completes.
     *
     * @see .setAlpha
     */
    fun setAlphaChangeAnimationEndAction(r: Runnable)

    /** Sets Qs ScrimEnabled and updates QS state. */
    fun setQsScrimEnabled(qsScrimEnabled: Boolean)

    /** Sets the top spacing for the ambient indicator. */
    fun setAmbientIndicationTop(ambientIndicationTop: Int, ambientTextVisible: Boolean)

    /** Updates notification panel-specific flags on [SysUiState]. */
    fun updateSystemUiStateFlags()

    /** Ensures that the touchable region is updated. */
    fun updateTouchableRegion()

    /** Adds a global layout listener. */
    fun addOnGlobalLayoutListener(listener: ViewTreeObserver.OnGlobalLayoutListener)

    /** Removes a global layout listener. */
    fun removeOnGlobalLayoutListener(listener: ViewTreeObserver.OnGlobalLayoutListener)

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

    /**
     * Sends an external (e.g. Status Bar) touch event to the Shade touch handler.
     *
     * This is different from [startInputFocusTransfer] as it doesn't rely on setting the launcher
     * window slippery to allow the frameworks to route those events after passing the initial
     * threshold.
     */
    fun handleExternalTouch(event: MotionEvent): Boolean

    /**
     * Triggered when an input focus transfer gesture has started.
     *
     * Used to dispatch initial touch events before crossing the threshold to pull down the
     * notification shade. After that, since the launcher window is set to slippery, input
     * frameworks take care of routing the events to the notification shade.
     */
    fun startInputFocusTransfer()

    /** Triggered when the input focus transfer was cancelled. */
    fun cancelInputFocusTransfer()

    /**
     * Triggered when the input focus transfer has finished successfully.
     *
     * @param velocity unit is in px / millis
     */
    fun finishInputFocusTransfer(velocity: Float)

    /**
     * Performs haptic feedback from a view with a haptic feedback constant.
     *
     * The implementation of this method should use the [android.view.View.performHapticFeedback]
     * method with the provided constant.
     *
     * @param[constant] One of [android.view.HapticFeedbackConstants]
     */
    fun performHapticFeedback(constant: Int)

    /** Returns the ShadeHeadsUpTracker. */
    val shadeHeadsUpTracker: ShadeHeadsUpTracker

    /** Returns the ShadeFoldAnimator. */
    val shadeFoldAnimator: ShadeFoldAnimator

    companion object {
        /**
         * Returns a multiplicative factor to use when determining the falsing threshold for touches
         * on the shade. The factor will be larger when the device is waking up due to a touch or
         * gesture.
         */
        @JvmStatic
        fun getFalsingThresholdFactor(wakefulness: WakefulnessModel): Float {
            return if (wakefulness.isAwakeFromTapOrGesture()) 1.5f else 1.0f
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
    val view: ViewGroup?
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
