/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import com.android.systemui.statusbar.StatusBarState;

/**
 * {@link ShadeController} is an abstraction of the work that used to be hard-coded in
 * {@link CentralSurfaces}. The shade itself represents the concept of the status bar window state,
 * and can be in multiple states: dozing, locked, showing the bouncer, occluded, etc. All/some of
 * these are coordinated with {@link StatusBarKeyguardViewManager} via
 * {@link com.android.systemui.keyguard.KeyguardViewMediator} and others.
 */
public interface ShadeController {

    /**
     * Make our window larger and the panel expanded
     */
    void instantExpandNotificationsPanel();

    /** See {@link #animateCollapsePanels(int, boolean)}. */
    void animateCollapsePanels();

    /** See {@link #animateCollapsePanels(int, boolean)}. */
    void animateCollapsePanels(int flags);

    /**
     * Collapse the shade animated, showing the bouncer when on {@link StatusBarState#KEYGUARD} or
     * dismissing {@link CentralSurfaces} when on {@link StatusBarState#SHADE}.
     */
    void animateCollapsePanels(int flags, boolean force);

    /** See {@link #animateCollapsePanels(int, boolean)}. */
    void animateCollapsePanels(int flags, boolean force, boolean delayed);

    /** See {@link #animateCollapsePanels(int, boolean)}. */
    void animateCollapsePanels(int flags, boolean force, boolean delayed, float speedUpFactor);

    /**
     * If the notifications panel is not fully expanded, collapse it animated.
     *
     * @return Seems to always return false
     */
    boolean closeShadeIfOpen();

    /** Returns whether the shade is currently open or opening. */
    boolean isShadeOpen();

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

    /**
     * Run all of the runnables added by {@link #addPostCollapseAction}.
     */
    void runPostCollapseRunnables();

    /**
     * Close the shade if it was open
     *
     * @return true if the shade was open, else false
     */
    boolean collapsePanel();

    /**
     * If {@param animate}, does the same as {@link #collapsePanel()}. Otherwise, instantly collapse
     * the panel. Post collapse runnables will be executed
     *
     * @param animate
     */
    void collapsePanel(boolean animate);
}
