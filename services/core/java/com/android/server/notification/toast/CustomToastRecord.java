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

package com.android.server.notification.toast;

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.notification.NotificationManagerService.DBG;

import android.app.ITransientNotification;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.notification.NotificationManagerService;

/**
 * Represents a custom toast, a toast whose view is provided by the app.
 */
public class CustomToastRecord extends ToastRecord {
    private static final String TAG = NotificationManagerService.TAG;

    public final ITransientNotification callback;

    public CustomToastRecord(NotificationManagerService notificationManager, int uid, int pid,
            String packageName, IBinder token, ITransientNotification callback, int duration,
            Binder windowToken, int displayId) {
        super(notificationManager, uid, pid, packageName, token, duration, windowToken, displayId);
        this.callback = checkNotNull(callback);
    }

    @Override
    public boolean show() {
        if (DBG) {
            Slog.d(TAG, "Show pkg=" + pkg + " callback=" + callback);
        }
        try {
            callback.show(windowToken);
            return true;
        } catch (RemoteException e) {
            Slog.w(TAG, "Object died trying to show custom toast " + token + " in package "
                    + pkg);
            mNotificationManager.keepProcessAliveForToastIfNeeded(pid);
            return false;
        }
    }

    @Override
    public void hide() {
        try {
            callback.hide();
        } catch (RemoteException e) {
            Slog.w(TAG, "Object died trying to hide custom toast " + token + " in package "
                    + pkg);

        }
    }

    @Override
    public String toString() {
        return "CustomToastRecord{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + pid + ":" +  pkg + "/" + UserHandle.formatUid(uid)
                + " token=" + token
                + " callback=" + callback
                + " duration=" + getDuration()
                + "}";
    }
}
