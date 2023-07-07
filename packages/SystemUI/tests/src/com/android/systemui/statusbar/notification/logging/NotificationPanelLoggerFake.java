/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.logging;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.nano.Notifications;

import java.util.ArrayList;
import java.util.List;

public class NotificationPanelLoggerFake implements NotificationPanelLogger {
    private List<CallRecord> mCalls = new ArrayList<>();

    List<CallRecord> getCalls() {
        return mCalls;
    }

    CallRecord get(int index) {
        return mCalls.get(index);
    }

    @Override
    public void logPanelShown(boolean isLockscreen,
            List<NotificationEntry> visibleNotifications) {
        mCalls.add(new CallRecord(isLockscreen,
                NotificationPanelLogger.toNotificationProto(visibleNotifications)));
    }

    @Override
    public void logNotificationDrag(NotificationEntry draggedNotification) {
    }

    public static class CallRecord {
        public boolean isLockscreen;
        public Notifications.NotificationList list;
        CallRecord(boolean isLockscreen, Notifications.NotificationList list) {
            this.isLockscreen = isLockscreen;
            this.list = list;
        }
    }
}
