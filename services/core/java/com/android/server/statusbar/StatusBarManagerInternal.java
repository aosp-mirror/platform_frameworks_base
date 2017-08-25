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

    void showRecentApps(boolean triggeredFromAltTab, boolean fromHome);

    void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey);

    void dismissKeyboardShortcutsMenu();
    void toggleKeyboardShortcutsMenu(int deviceId);

    /**
     * Show picture-in-picture menu.
     */
    void showPictureInPictureMenu();

    void setWindowState(int window, int state);

    /**
     * Notifies the status bar that an app transition is pending to delay applying some flags with
     * visual impact until {@link #appTransitionReady} is called.
     */
    void appTransitionPending();

    /**
     * Notifies the status bar that a pending app transition has been cancelled.
     */
    void appTransitionCancelled();

    /**
     * Notifies the status bar that an app transition is now being executed.
     *
     * @param statusBarAnimationsStartTime the desired start time for all visual animations in the
     *        status bar caused by this app transition in uptime millis
     * @param statusBarAnimationsDuration the duration for all visual animations in the status
     *        bar caused by this app transition in millis
     */
    void appTransitionStarting(long statusBarAnimationsStartTime, long statusBarAnimationsDuration);

    void startAssist(Bundle args);
    void onCameraLaunchGestureDetected(int source);
    void topAppWindowChanged(boolean menuVisible);
    void setSystemUiVisibility(int vis, int fullscreenStackVis, int dockedStackVis, int mask,
            Rect fullscreenBounds, Rect dockedBounds, String cause);
    void toggleSplitScreen();
    void appTransitionFinished();

    void toggleRecentApps();

    void setCurrentUser(int newUserId);

    void setGlobalActionsListener(GlobalActionsListener listener);
    void showGlobalActions();

    /**
     * Set whether the top app currently hides the statusbar.
     *
     * @param hidesStatusBar whether it is being hidden
     */
    void setTopAppHidesStatusBar(boolean hidesStatusBar);

    boolean showShutdownUi(boolean isReboot, String requestString);

    public interface GlobalActionsListener {
        /**
         * Called when sysui starts and connects its status bar, or when the status bar binder
         * dies indicating sysui is no longer alive.
         */
        void onStatusBarConnectedChanged(boolean connected);

        /**
         * Callback from sysui to notify system that global actions has been successfully shown.
         */
        void onGlobalActionsShown();

        /**
         * Callback from sysui to notify system that the user has dismissed global actions and
         * it no longer needs to be displayed (even if sysui dies).
         */
        void onGlobalActionsDismissed();
    }
}
