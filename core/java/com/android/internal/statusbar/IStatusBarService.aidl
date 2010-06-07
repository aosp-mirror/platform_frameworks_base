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

import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;

/** @hide */
interface IStatusBarService
{
    void expand();
    void collapse();
    void disable(int what, IBinder token, String pkg);
    void setIcon(String slot, String iconPackage, int iconId, int iconLevel);
    void setIconVisibility(String slot, boolean visible);
    void removeIcon(String slot);

    // ---- Methods below are for use by the status bar policy services ----
    // You need the STATUS_BAR_SERVICE permission
    void registerStatusBar(IStatusBar callbacks, out StatusBarIconList iconList,
            out List<IBinder> notificationKeys, out List<StatusBarNotification> notifications);
    void onPanelRevealed();
    void onNotificationClick(String pkg, String tag, int id);
    void onNotificationError(String pkg, String tag, int id, String message);
    void onClearAllNotifications();
}
