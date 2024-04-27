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
import com.android.systemui.power.shared.model.WakefulnessModel
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController
import com.android.systemui.statusbar.phone.KeyguardStatusBarView
import com.android.systemui.statusbar.phone.KeyguardStatusBarViewController
import java.util.function.Consumer

/**
 * Controller for the top level shade view.
 *
 * @see NotificationPanelViewController
 */
interface ShadeViewController {
    /** Returns whether the shade's top level view is enabled. */
    @Deprecated("No longer supported. Do not add new calls to this.") val isViewEnabled: Boolean

    /** If the latency tracker is enabled, begins tracking expand latency. */
    @Deprecated("No longer supported. Do not add new calls to this.")
    fun startExpandLatencyTracking()

    /** Sets the alpha value of the shade to a value between 0 and 255. */
    @Deprecated("No longer supported. Do not add new calls to this.")
    fun setAlpha(alpha: Int, animate: Boolean)

    /**
     * Sets the runnable to run after the alpha change animation completes.
     *
     * @see .setAlpha
     */
    @Deprecated("No longer supported. Do not add new calls to this.")
    fun setAlphaChangeAnimationEndAction(r: Runnable)

    /** Sets Qs ScrimEnabled and updates QS state. */
    @Deprecated("Does nothing when scene container is enabled.")
    fun setQsScrimEnabled(qsScrimEnabled: Boolean)

    /** Sets the top spacing for the ambient indicator. */
    @Deprecated("Does nothing when scene container is enabled.")
    fun setAmbientIndicationTop(ambientIndicationTop: Int, ambientTextVisible: Boolean)

    /** Updates notification panel-specific flags on [SysUiState]. */
    @Deprecated("Does nothing when scene container is enabled.") fun updateSystemUiStateFlags()

    /** Ensures that the touchable region is updated. */
    @Deprecated("No longer supported. Do not add new calls to this.") fun updateTouchableRegion()

    /**
     * Sends an external (e.g. Status Bar) touch event to the Shade touch handler.
     *
     * This is different from [startInputFocusTransfer] as it doesn't rely on setting the launcher
     * window slippery to allow the frameworks to route those events after passing the initial
     * threshold.
     */
    fun handleExternalTouch(event: MotionEvent): Boolean

    /** Sends an external (e.g. Status Bar) intercept touch event to the Shade touch handler. */
    fun handleExternalInterceptTouch(event: MotionEvent): Boolean

    /**
     * Triggered when an input focus transfer gesture has started.
     *
     * Used to dispatch initial touch events before crossing the threshold to pull down the
     * notification shade. After that, since the launcher window is set to slippery, input
     * frameworks take care of routing the events to the notification shade.
     */
    @Deprecated("No longer supported. Do not add new calls to this.") fun startInputFocusTransfer()

    /** Triggered when the input focus transfer was cancelled. */
    @Deprecated("No longer supported. Do not add new calls to this.") fun cancelInputFocusTransfer()

    /**
     * Triggered when the input focus transfer has finished successfully.
     *
     * @param velocity unit is in px / millis
     */
    @Deprecated("No longer supported. Do not add new calls to this.")
    fun finishInputFocusTransfer(velocity: Float)

    /** Returns the ShadeHeadsUpTracker. */
    val shadeHeadsUpTracker: ShadeHeadsUpTracker

    /** Returns the ShadeFoldAnimator. */
    @Deprecated("This interface is deprecated in Scene Container")
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
@Deprecated("This interface should not be used in scene container. Needs flexiglass equivalent.")
interface ShadeFoldAnimator {
    /** Updates the views to the initial state for the fold to AOD animation. */
    @Deprecated("Used by the Keyguard Fold Transition. Needs flexiglass equivalent.")
    fun prepareFoldToAodAnimation()

    /**
     * Starts fold to AOD animation.
     *
     * @param startAction invoked when the animation starts.
     * @param endAction invoked when the animation finishes, also if it was cancelled.
     * @param cancelAction invoked when the animation is cancelled, before endAction.
     */
    @Deprecated("Not used when migrateClocksToBlueprint enabled.")
    fun startFoldToAodAnimation(startAction: Runnable, endAction: Runnable, cancelAction: Runnable)

    /** Cancels fold to AOD transition and resets view state. */
    @Deprecated("Used by the Keyguard Fold Transition. Needs flexiglass equivalent.")
    fun cancelFoldToAodAnimation()

    /** Returns the main view of the shade. */
    @Deprecated("Not used when migrateClocksToBlueprint enabled.") val view: ViewGroup?
}

/**
 * An interface that provides the current state of the notification panel and related views, which
 * is needed to calculate [KeyguardStatusBarView]'s state in [KeyguardStatusBarViewController].
 */
@Deprecated("This interface should not be used in scene container.")
interface ShadeViewStateProvider {
    /** Returns the expanded height of the panel view. */
    @Deprecated("deprecated by SceneContainerFlag.isEnabled") val panelViewExpandedHeight: Float

    /**
     * Returns true if heads up should be visible.
     *
     * TODO(b/138786270): If HeadsUpAppearanceController was injectable, we could inject it into
     *   [KeyguardStatusBarViewController] and remove this method.
     */
    @Deprecated("deprecated in Flexiglass.") fun shouldHeadsUpBeVisible(): Boolean

    /** Return the fraction of the shade that's expanded, when in lockscreen. */
    @Deprecated("deprecated by SceneContainerFlag.isEnabled") val lockscreenShadeDragProgress: Float
}
