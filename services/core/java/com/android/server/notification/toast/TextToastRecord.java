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

import android.annotation.Nullable;
import android.app.ITransientNotificationCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.notification.NotificationManagerService;
import com.android.server.statusbar.StatusBarManagerInternal;

/**
 * Represents a text toast, a toast rendered by the system that contains only text.
 */
public class TextToastRecord extends ToastRecord {
    private static final String TAG = NotificationManagerService.TAG;

    public final CharSequence text;
    @Nullable
    private final StatusBarManagerInternal mStatusBar;
    @Nullable
    private final ITransientNotificationCallback mCallback;

    public TextToastRecord(NotificationManagerService notificationManager,
            @Nullable StatusBarManagerInternal statusBarManager, int uid, int pid,
            String packageName, boolean isSystemToast, IBinder token, CharSequence text,
            int duration, Binder windowToken, int displayId,
            @Nullable ITransientNotificationCallback callback) {
        super(notificationManager, uid, pid, packageName, isSystemToast, token, duration,
                windowToken, displayId);
        mStatusBar = statusBarManager;
        mCallback = callback;
        this.text = checkNotNull(text);
    }

    @Override
    public boolean show() {
        if (DBG) {
            Slog.d(TAG, "Show pkg=" + pkg + " text=" + text);
        }
        if (mStatusBar == null) {
            Slog.w(TAG, "StatusBar not available to show text toast for package " + pkg);
            return false;
        }
        mStatusBar.showToast(uid, pkg, token, text, windowToken, getDuration(), mCallback);
        return true;
    }

    @Override
    public void hide() {
        // If it's null, show() would have returned false
        checkNotNull(mStatusBar, "Cannot hide toast that wasn't shown");

        mStatusBar.hideToast(pkg, token);
    }

    @Override
    public boolean isAppRendered() {
        return false;
    }

    @Override
    public String toString() {
        return "TextToastRecord{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + pid + ":" +  pkg + "/" + UserHandle.formatUid(uid)
                + " isSystemToast=" + isSystemToast
                + " token=" + token
                + " text=" + text
                + " duration=" + getDuration()
                + "}";
    }
}
