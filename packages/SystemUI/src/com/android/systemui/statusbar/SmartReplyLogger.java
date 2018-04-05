/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import com.android.internal.statusbar.IStatusBarService;

/**
 * Handles reporting when smart replies are added to a notification
 * and clicked upon.
 */
public class SmartReplyLogger {
    protected IStatusBarService mBarService;

    public SmartReplyLogger(Context context) {
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
    }

    public void smartReplySent(NotificationData.Entry entry, int replyIndex) {
        try {
            mBarService.onNotificationSmartReplySent(entry.notification.getKey(),
                    replyIndex);
        } catch (RemoteException e) {
            // Nothing to do, system going down
        }
    }

    public void smartRepliesAdded(final NotificationData.Entry entry, int replyCount) {
        try {
            mBarService.onNotificationSmartRepliesAdded(entry.notification.getKey(),
                    replyCount);
        } catch (RemoteException e) {
            // Nothing to do, system going down
        }
    }
}
