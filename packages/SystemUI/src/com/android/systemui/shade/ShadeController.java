/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade;

import android.view.MotionEvent;

import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

/**
 * {@link ShadeController} is an abstraction of the work that used to be hard-coded in
 * {@link CentralSurfaces}. The shade itself represents the concept of the status bar window state,
 * and can be in multiple states: dozing, locked, showing the bouncer, occluded, etc. All/some of
 * these are coordinated with {@link StatusBarKeyguardViewManager} via
 * {@link com.android.systemui.keyguard.KeyguardViewMediator} and others.
 */
public interface ShadeController {

    /** Make our window larger and the shade expanded */
    void instantExpandShade();

    /** Collapse the shade instantly with no animation. */
    void instantCollapseShade();

    /** See {@link #animateCollapsePanels(int, boolean, boolean, float)}. */
    void animateCollapseShade();

    /** See {@link #animateCollapsePanels(int, boolean, boolean, float)}. */
    void animateCollapseShade(int flags);

    /** See {@link #animateCollapsePanels(int, boolean, boolean, float)}. */
    void animateCollapseShadeForced();

    /** See {@link #animateCollapsePanels(int, boolean, boolean, float)}. */
    void animateCollapseShadeDelayed();

    /**
     * Collapse the shade animated, showing the bouncer when on {@link StatusBarState#KEYGUARD} or
     * dismissing status bar when on {@link StatusBarState#SHADE}.
     */
    void animateCollapsePanels(int flags, boolean force, boolean delayed, float speedUpFactor);

    /**
     * If the shade is not fully expanded, collapse it animated.
     *
     * @return Seems to always return false
     */
    boolean closeShadeIfOpen();

    /**
     * Returns whether the shade is currently open.
     * Even though in the current implementation shade is in expanded state on keyguard, this
     * method makes distinction between shade being truly open and plain keyguard state:
     * - if QS and notifications are visible on the screen, return true
     * - for any other state, including keyguard, return false
     */
    boolean isShadeFullyOpen();

    /**
     * Add a runnable for NotificationPanelView to post when the panel is expanded.
     *
     * @param action the action to post
     */
    void postOnShadeExpanded(Runnable action);

    /**
     * Add a runnable to be executed after the shade collapses. Post-collapse runnables are
     * aggregated and run serially.
     *
     * @param action the action to execute
     */
    void addPostCollapseAction(Runnable action);

    /** Run all of the runnables added by {@link #addPostCollapseAction}. */
    void runPostCollapseRunnables();

    /**
     * Close the shade if it was open
     *
     * @return true if the shade was open, else false
     */
    boolean collapseShade();

    /**
     * If animate is true, does the same as {@link #collapseShade()}. Otherwise, instantly collapse
     * the shade. Post collapse runnables will be executed
     *
     * @param animate true to animate the collapse, false for instantaneous collapse
     */
    void collapseShade(boolean animate);

    /** Makes shade expanded but not visible. */
    void makeExpandedInvisible();

    /** Makes shade expanded and visible. */
    void makeExpandedVisible(boolean force);

    /** Returns whether the shade is expanded and visible. */
    boolean isExpandedVisible();

    /** Handle status bar touch event. */
    void onStatusBarTouch(MotionEvent event);

    /** Called when the shade finishes collapsing. */
    void onClosingFinished();

    /** Sets the listener for when the visibility of the shade changes. */
    void setVisibilityListener(ShadeVisibilityListener listener);

    /** */
    void setNotificationPresenter(NotificationPresenter presenter);

    /** */
    void setNotificationShadeWindowViewController(
            NotificationShadeWindowViewController notificationShadeWindowViewController);

    /** */
    void setNotificationPanelViewController(
            NotificationPanelViewController notificationPanelViewController);

    /** Listens for shade visibility changes. */
    interface ShadeVisibilityListener {
        /** Called when the visibility of the shade changes. */
        void visibilityChanged(boolean visible);

        /** Called when shade expanded and visible state changed. */
        void expandedVisibleChanged(boolean expandedVisible);
    }
}
