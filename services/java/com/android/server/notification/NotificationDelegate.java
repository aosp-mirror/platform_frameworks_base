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

package com.android.server.notification;

public interface NotificationDelegate {
    void onSetDisabled(int status);
    void onClearAll();
    void onNotificationClick(String pkg, String tag, int id);
    void onNotificationClear(String pkg, String tag, int id);
    void onNotificationError(String pkg, String tag, int id,
            int uid, int initialPid, String message);
    void onPanelRevealed();
}
