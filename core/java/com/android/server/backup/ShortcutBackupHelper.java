/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.backup.BlobBackupHelper;
import android.content.Context;
import android.content.pm.IShortcutService;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

public class ShortcutBackupHelper extends BlobBackupHelper {
    private static final String TAG = "ShortcutBackupAgent";
    private static final int BLOB_VERSION = 1;

    private static final String KEY_USER_FILE = "shortcutuser.xml";

    public ShortcutBackupHelper() {
        super(BLOB_VERSION, KEY_USER_FILE);
    }

    private IShortcutService getShortcutService() {
        return IShortcutService.Stub.asInterface(
                ServiceManager.getService(Context.SHORTCUT_SERVICE));
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        switch (key) {
            case KEY_USER_FILE:
                try {
                    return getShortcutService().getBackupPayload(UserHandle.USER_SYSTEM);
                } catch (Exception e) {
                    Slog.wtf(TAG, "Backup failed", e);
                }
                break;
            default:
                Slog.w(TAG, "Unknown key: " + key);
        }
        return null;
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        switch (key) {
            case KEY_USER_FILE:
                try {
                    getShortcutService().applyRestore(payload, UserHandle.USER_SYSTEM);
                } catch (Exception e) {
                    Slog.wtf(TAG, "Restore failed", e);
                }
                break;
            default:
                Slog.w(TAG, "Unknown key: " + key);
        }
    }
}
