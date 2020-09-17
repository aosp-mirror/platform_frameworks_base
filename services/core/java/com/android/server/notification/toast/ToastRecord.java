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

import android.os.Binder;
import android.os.IBinder;

import com.android.server.notification.NotificationManagerService;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;

/**
 * Represents a toast, a transient notification.
 */
public abstract class ToastRecord {
    public final int uid;
    public final int pid;
    public final String pkg;
    public final IBinder token;
    public final int displayId;
    public final Binder windowToken;
    protected final NotificationManagerService mNotificationManager;
    private int mDuration;

    protected ToastRecord(NotificationManagerService notificationManager, int uid, int pid,
            String pkg, IBinder token, int duration, Binder windowToken, int displayId) {
        this.mNotificationManager = notificationManager;
        this.uid = uid;
        this.pid = pid;
        this.pkg = pkg;
        this.token = token;
        this.windowToken = windowToken;
        this.displayId = displayId;
        mDuration = duration;
    }

    /**
     * This method is responsible for showing the toast represented by this object.
     *
     * @return True if it was successfully shown.
     */
    public abstract boolean show();

    /**
     * This method is responsible for hiding the toast represented by this object.
     */
    public abstract void hide();

    /**
     * Returns the duration of this toast, which can be {@link android.widget.Toast#LENGTH_SHORT}
     * or {@link android.widget.Toast#LENGTH_LONG}.
     */
    public int getDuration() {
        return mDuration;
    }

    /**
     * Updates toast duration.
     */
    public void update(int duration) {
        mDuration = duration;
    }

    /**
     * Dumps a textual representation of this object.
     */
    public void dump(PrintWriter pw, String prefix, DumpFilter filter) {
        if (filter != null && !filter.matches(pkg)) {
            return;
        }
        pw.println(prefix + this);
    }
}
