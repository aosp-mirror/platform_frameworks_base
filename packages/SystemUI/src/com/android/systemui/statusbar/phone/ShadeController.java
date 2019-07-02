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

import android.annotation.NonNull;
import android.view.View;

import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;

/**
 * {@link ShadeController} is an abstraction of the work that used to be hard-coded in
 * {@link StatusBar}. The shade itself represents the concept of the status bar window state, and
 * can be in multiple states: dozing, locked, showing the bouncer, occluded, etc. All/some of these
 * are coordinated with {@link StatusBarKeyguardViewManager} via
 * {@link com.android.systemui.keyguard.KeyguardViewMediator} and others.
 */
public interface ShadeController {

    /**
     * Shows the keyguard bouncer - the password challenge on the lock screen
     *
     * @param scrimmed true when the bouncer should show scrimmed, false when the user will be
     * dragging it and translation should be deferred {@see KeyguardBouncer#show(boolean, boolean)}
     */
    void showBouncer(boolean scrimmed);

    /**
     * Make our window larger and the panel expanded
     */
    void instantExpandNotificationsPanel();

    /**
     * Collapse the shade animated, showing the bouncer when on {@link StatusBarState#KEYGUARD} or
     * dismissing {@link StatusBar} when on {@link StatusBarState#SHADE}.
     */
    void animateCollapsePanels(int flags, boolean force);

    /**
     * If the notifications panel is not fully expanded, collapse it animated.
     *
     * @return Seems to always return false
     */
    boolean closeShadeIfOpen();

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
     * Ask shade controller to set the state to {@link StatusBarState#KEYGUARD}, but only from
     * {@link StatusBarState#SHADE_LOCKED}
     */
    void goToKeyguard();

    /**
     * When the keyguard is showing and covered by something (bouncer, keyguard activity, etc.) it
     * is occluded. This is controlled by {@link com.android.server.policy.PhoneWindowManager}
     *
     * @return whether the keyguard is currently occluded
     */
    boolean isOccluded();

    /**
     * Notify the shade controller that the current user changed
     *
     * @param newUserId userId of the new user
     */
    void setLockscreenUser(int newUserId);

    /**
     * Dozing is when the screen is in AOD or asleep
     *
     * @return true if we are dozing
     */
    boolean isDozing();

    /**
     * Ask the display to wake up if currently dozing, else do nothing
     *
     * @param time when to wake up
     * @param view the view requesting the wakeup
     * @param why the reason for the wake up
     */
    void wakeUpIfDozing(long time, View view, @NonNull String why);

    /**
     * If secure with redaction: Show bouncer, go to unlocked shade.
     *
     * <p>If secure without redaction or no security: Go to {@link StatusBarState#SHADE_LOCKED}.</p>
     *
     * @param startingChild The view to expand after going to the shade.
     */
    void goToLockedShade(View startingChild);

    /**
     * Adds a {@param runnable} to be executed after Keyguard is gone.
     */
    void addAfterKeyguardGoneRunnable(Runnable runnable);

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

    /**
     * Callback to tell the shade controller that an activity launch animation was canceled
     */
    void onLaunchAnimationCancelled();

    /**
     * When notifications update, give the shade controller a chance to do thing in response to
     * the new data set
     */
    void updateAreThereNotifications();

    /**
     * Callback to notify the shade controller that a {@link ActivatableNotificationView} has become
     * inactive
     */
    void onActivationReset();

}
