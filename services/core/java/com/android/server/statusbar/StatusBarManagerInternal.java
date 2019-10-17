/**
 * Copyright (c) 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.statusbar;

import android.graphics.Rect;
import android.os.Bundle;

import com.android.server.notification.NotificationDelegate;

public interface StatusBarManagerInternal {
    void setNotificationDelegate(NotificationDelegate delegate);
    void showScreenPinningRequest(int taskId);
    void showAssistDisclosure();

    void preloadRecentApps();

    void cancelPreloadRecentApps();

    void showRecentApps(boolean triggeredFromAltTab);

    void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey);

    void dismissKeyboardShortcutsMenu();
    void toggleKeyboardShortcutsMenu(int deviceId);

    void showChargingAnimation(int batteryLevel);

    /**
     * Show picture-in-picture menu.
     */
    void showPictureInPictureMenu();

    void setWindowState(int displayId, int window, int state);

    /**
     * Notifies the status bar that an app transition is pending to delay applying some flags with
     * visual impact until {@link #appTransitionReady} is called.
     *
     * @param displayId the ID of the display which has this event.
     */
    void appTransitionPending(int displayId);

    /**
     * Notifies the status bar that a pending app transition has been cancelled.
     *
     * @param displayId the ID of the display which has this event.
     */
    void appTransitionCancelled(int displayId);

    /**
     * Notifies the status bar that an app transition is now being executed.
     *
     * @param displayId the ID of the display which has this event.
     * @param statusBarAnimationsStartTime the desired start time for all visual animations in the
     *        status bar caused by this app transition in uptime millis
     * @param statusBarAnimationsDuration the duration for all visual animations in the status
     *        bar caused by this app transition in millis
     */
    void appTransitionStarting(int displayId, long statusBarAnimationsStartTime,
            long statusBarAnimationsDuration);

    void startAssist(Bundle args);
    void onCameraLaunchGestureDetected(int source);
    void topAppWindowChanged(int displayId, boolean menuVisible);
    void setSystemUiVisibility(int displayId, int vis, int fullscreenStackVis, int dockedStackVis,
            int mask, Rect fullscreenBounds, Rect dockedBounds, boolean isNavbarColorManagedByIme,
            String cause);
    void toggleSplitScreen();
    void appTransitionFinished(int displayId);

    void toggleRecentApps();

    void setCurrentUser(int newUserId);

    /**
     * Set whether the top app currently hides the statusbar.
     *
     * @param hidesStatusBar whether it is being hidden
     */
    void setTopAppHidesStatusBar(boolean hidesStatusBar);

    boolean showShutdownUi(boolean isReboot, String requestString);

    /**
     * Show a rotation suggestion that a user may approve to rotate the screen.
     *
     * @param rotation rotation suggestion
     */
    void onProposedRotationChanged(int rotation, boolean isValid);

    /**
     * Notifies System UI that the display is ready to show system decorations.
     *
     * @param displayId display ID
     */
    void onDisplayReady(int displayId);

    /**
     * Notifies System UI whether the recents animation is running.
     */
    void onRecentsAnimationStateChanged(boolean running);
}
