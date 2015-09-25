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

import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import com.android.internal.statusbar.StatusBarIcon;

/** @hide */
oneway interface IStatusBar
{
    void setIcon(int index, in StatusBarIcon icon);
    void removeIcon(int index);
    void disable(int state1, int state2);
    void animateExpandNotificationsPanel();
    void animateExpandSettingsPanel();
    void animateCollapsePanels();
    void setSystemUiVisibility(int vis, int mask);
    void topAppWindowChanged(boolean menuVisible);
    void setImeWindowStatus(in IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher);
    void setWindowState(int window, int state);
    void buzzBeepBlinked();
    void notificationLightOff();
    void notificationLightPulse(int argb, int millisOn, int millisOff);

    void showRecentApps(boolean triggeredFromAltTab);
    void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey);
    void toggleRecentApps();
    void preloadRecentApps();
    void cancelPreloadRecentApps();
    void showScreenPinningRequest();

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

    void showAssistDisclosure();
    void startAssist(in Bundle args);

    /**
     * Notifies the status bar that a camera launch gesture has been detected.
     *
     * @param source the identifier for the gesture, see {@link StatusBarManager}
     */
    void onCameraLaunchGestureDetected(int source);
}

