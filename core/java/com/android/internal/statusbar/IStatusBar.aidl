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

import com.android.internal.statusbar.StatusBarIcon;
import android.service.notification.StatusBarNotification;

/** @hide */
oneway interface IStatusBar
{
    void setIcon(int index, in StatusBarIcon icon);
    void removeIcon(int index);
    void disable(int state);
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
}

