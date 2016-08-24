/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.backup;

import android.app.INotificationManager;
import android.app.backup.BlobBackupHelper;
import android.content.Context;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

public class NotificationBackupHelper extends BlobBackupHelper {
    static final String TAG = "NotifBackupHelper";   // must be < 23 chars
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Current version of the blob schema
    static final int BLOB_VERSION = 1;

    // Key under which the payload blob is stored
    static final String KEY_NOTIFICATIONS = "notifications";

    public NotificationBackupHelper(Context context) {
        super(BLOB_VERSION, KEY_NOTIFICATIONS);
        // context is currently unused
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        byte[] newPayload = null;
        if (KEY_NOTIFICATIONS.equals(key)) {
            try {
                INotificationManager nm = INotificationManager.Stub.asInterface(
                        ServiceManager.getService("notification"));
                // TODO: http://b/22388012
                newPayload = nm.getBackupPayload(UserHandle.USER_SYSTEM);
            } catch (Exception e) {
                // Treat as no data
                Slog.e(TAG, "Couldn't communicate with notification manager");
                newPayload = null;
            }
        }
        return newPayload;
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        if (DEBUG) {
            Slog.v(TAG, "Got restore of " + key);
        }

        if (KEY_NOTIFICATIONS.equals(key)) {
            try {
                INotificationManager nm = INotificationManager.Stub.asInterface(
                        ServiceManager.getService("notification"));
                // TODO: http://b/22388012
                nm.applyRestore(payload, UserHandle.USER_SYSTEM);
            } catch (Exception e) {
                Slog.e(TAG, "Couldn't communicate with notification manager");
            }
        }
    }

}
