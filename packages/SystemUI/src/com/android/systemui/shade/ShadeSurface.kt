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
import com.android.systemui.shade.domain.interactor.ShadeBackActionInteractor
import com.android.systemui.statusbar.GestureRecorder
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.policy.HeadsUpManager

/**
 * Allows CentralSurfacesImpl to interact with the shade. Only CentralSurfacesImpl should reference
 * this class. If any method in this class is needed outside of CentralSurfacesImpl, it must be
 * pulled up into ShadeViewController.
 */
interface ShadeSurface : ShadeViewController, ShadeBackActionInteractor {
    /** Initialize objects instead of injecting to avoid circular dependencies. */
    fun initDependencies(
        centralSurfaces: CentralSurfaces,
        recorder: GestureRecorder,
        hideExpandedRunnable: Runnable,
        headsUpManager: HeadsUpManager
    )

    /** Cancels any pending collapses. */
    fun cancelPendingCollapse()

    /** Cancels the views current animation. */
    fun cancelAnimation()

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
     * Notify us that {@link NotificationWakeUpCoordinator} is going to play the doze wakeup
     * animation after a delay. If so, we'll keep the clock centered until that animation starts.
     */
    fun setWillPlayDelayedDozeAmountAnimation(willPlay: Boolean)

    /**
     * Sets the dozing state.
     *
     * @param dozing `true` when dozing.
     * @param animate if transition should be animated.
     */
    fun setDozing(dozing: Boolean, animate: Boolean)

    /** @see view.setImportantForAccessibility */
    fun setImportantForAccessibility(mode: Int)

    /** Sets the view's X translation to zero. */
    fun resetTranslation()

    /** Sets the view's alpha to max. */
    fun resetAlpha()

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
