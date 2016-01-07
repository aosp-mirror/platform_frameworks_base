/**
 * Copyright (c) 2007, The Android Open Source Project
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

package com.android.internal.statusbar;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import com.android.internal.statusbar.StatusBarIcon;

/** @hide */
oneway interface IStatusBar
{
    void setIcon(String slot, in StatusBarIcon icon);
    void removeIcon(String slot);
    void disable(int state1, int state2);
    void animateExpandNotificationsPanel();
    void animateExpandSettingsPanel(String subPanel);
    void animateCollapsePanels();

    /**
     * Notifies the status bar of a System UI visibility flag change.
     *
     * @param vis the visibility flags except SYSTEM_UI_FLAG_LIGHT_STATUS_BAR which will be reported
     *            separately in fullscreenStackVis and dockedStackVis
     * @param fullscreenStackVis the flags which only apply in the region of the fullscreen stack,
     *                           which is currently only SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
     * @param dockedStackVis the flags that only apply in the region of the docked stack, which is
     *                       currently only SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
     * @param mask which flags to change
     * @param fullscreenBounds the current bounds of the fullscreen stack, in screen coordinates
     * @param dockedBounds the current bounds of the docked stack, in screen coordinates
     */
    void setSystemUiVisibility(int vis, int fullscreenStackVis, int dockedStackVis, int mask,
            in Rect fullscreenBounds, in Rect dockedBounds);

    void topAppWindowChanged(boolean menuVisible);
    void setImeWindowStatus(in IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher);
    void setWindowState(int window, int state);
    void buzzBeepBlinked();
    void notificationLightOff();
    void notificationLightPulse(int argb, int millisOn, int millisOff);

    void showRecentApps(boolean triggeredFromAltTab, boolean fromHome);
    void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey);
    void toggleRecentApps();
    void toggleSplitScreen();
    void preloadRecentApps();
    void cancelPreloadRecentApps();
    void showScreenPinningRequest(int taskId);

    void dismissKeyboardShortcutsMenu();
    void toggleKeyboardShortcutsMenu(int deviceId);

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

    /**
     * Notifies the status bar that an app transition is done.
     */
    void appTransitionFinished();

    void showAssistDisclosure();
    void startAssist(in Bundle args);
    void screenPinningStateChanged(boolean enabled);

    /**
     * Notifies the status bar that a camera launch gesture has been detected.
     *
     * @param source the identifier for the gesture, see {@link StatusBarManager}
     */
    void onCameraLaunchGestureDetected(int source);

    /**
     * Shows the TV's picture-in-picture menu if an activity is in picture-in-picture mode.
     */
    void showTvPictureInPictureMenu();

    void addQsTile(in ComponentName tile);
    void remQsTile(in ComponentName tile);
    void clickQsTile(in ComponentName tile);
    void handleSystemNavigationKey(in int key);
}
