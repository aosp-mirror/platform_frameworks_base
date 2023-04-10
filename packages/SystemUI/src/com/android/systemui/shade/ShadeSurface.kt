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

import android.view.ViewPropertyAnimator
import com.android.systemui.statusbar.GestureRecorder
import com.android.systemui.statusbar.NotificationShelfController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone

/**
 * Allows CentralSurfacesImpl to interact with the shade. Only CentralSurfacesImpl should reference
 * this class. If any method in this class is needed outside of CentralSurfacesImpl, it must be
 * pulled up into ShadeViewController.
 */
interface ShadeSurface : ShadeViewController {
    /** Initialize objects instead of injecting to avoid circular dependencies. */
    fun initDependencies(
        centralSurfaces: CentralSurfaces,
        recorder: GestureRecorder,
        hideExpandedRunnable: Runnable,
        notificationShelfController: NotificationShelfController,
        headsUpManager: HeadsUpManagerPhone
    )

    /**
     * Animate QS collapse by flinging it. If QS is expanded, it will collapse into QQS and stop. If
     * in split shade, it will collapse the whole shade.
     *
     * @param fullyCollapse Do not stop when QS becomes QQS. Fling until QS isn't visible anymore.
     */
    fun animateCollapseQs(fullyCollapse: Boolean)

    /** Returns whether the shade can be collapsed. */
    fun canBeCollapsed(): Boolean

    /** Cancels any pending collapses. */
    fun cancelPendingCollapse()

    /** Cancels the views current animation. */
    fun cancelAnimation()

    /**
     * Close the keyguard user switcher if it is open and capable of closing.
     *
     * Has no effect if user switcher isn't supported, if the user switcher is already closed, or if
     * the user switcher uses "simple" mode. The simple user switcher cannot be closed.
     *
     * @return true if the keyguard user switcher was open, and is now closed
     */
    fun closeUserSwitcherIfOpen(): Boolean

    /** Input focus transfer is about to happen. */
    fun startWaitingForExpandGesture()

    /**
     * Called when this view is no longer waiting for input focus transfer.
     *
     * There are two scenarios behind this function call. First, input focus transfer has
     * successfully happened and this view already received synthetic DOWN event.
     * (mExpectingSynthesizedDown == false). Do nothing.
     *
     * Second, before input focus transfer finished, user may have lifted finger in previous window
     * and this window never received synthetic DOWN event. (mExpectingSynthesizedDown == true). In
     * this case, we use the velocity to trigger fling event.
     *
     * @param velocity unit is in px / millis
     */
    fun stopWaitingForExpandGesture(cancel: Boolean, velocity: Float)

    /** Animates the view from its current alpha to zero then runs the runnable. */
    fun fadeOut(startDelayMs: Long, durationMs: Long, endAction: Runnable): ViewPropertyAnimator

    /** Set whether the bouncer is showing. */
    fun setBouncerShowing(bouncerShowing: Boolean)

    /**
     * Sets whether the shade can handle touches and/or animate, canceling any touch handling or
     * animations in progress.
     */
    fun setTouchAndAnimationDisabled(disabled: Boolean)

    /**
     * Sets the dozing state.
     *
     * @param dozing `true` when dozing.
     * @param animate if transition should be animated.
     */
    fun setDozing(dozing: Boolean, animate: Boolean)

    /** @see view.setImportantForAccessibility */
    fun setImportantForAccessibility(mode: Int)

    /** Sets Qs ScrimEnabled and updates QS state. */
    fun setQsScrimEnabled(qsScrimEnabled: Boolean)

    /** Sets the view's X translation to zero. */
    fun resetTranslation()

    /** Sets the view's alpha to max. */
    fun resetAlpha()

    /** @see ViewGroupFadeHelper.reset */
    fun resetViewGroupFade()

    /** Called when Back gesture has been committed (i.e. a back event has definitely occurred) */
    fun onBackPressed()

    /** Sets progress of the predictive back animation. */
    fun onBackProgressed(progressFraction: Float)

    /** @see com.android.systemui.keyguard.ScreenLifecycle.Observer.onScreenTurningOn */
    fun onScreenTurningOn()

    /**
     * Called when the device's theme changes.
     *
     * TODO(b/274655539) delete?
     */
    fun onThemeChanged()

    /** Updates the shade expansion and [NotificationPanelView] visibility if necessary. */
    fun updateExpansionAndVisibility()

    /** Updates all field values drawn from Resources. */
    fun updateResources()
}
