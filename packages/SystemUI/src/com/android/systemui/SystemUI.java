/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui;

import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.NonNull;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A top-level module of system UI code (sometimes called "system UI services" elsewhere in code).
 * Which SystemUI modules are loaded can be controlled via a config resource.
 *
 * @see SystemUIApplication#startServicesIfNeeded()
 */
public abstract class SystemUI implements Dumpable {
    protected final Context mContext;

    public SystemUI(Context context) {
        mContext = context;
    }

    public abstract void start();

    protected void onConfigurationChanged(Configuration newConfig) {
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
    }

    protected void onBootCompleted() {
    }

    public static void overrideNotificationAppName(Context context, Notification.Builder n,
            boolean system) {
        final Bundle extras = new Bundle();
        String appName = system
                ? context.getString(com.android.internal.R.string.notification_app_name_system)
                : context.getString(com.android.internal.R.string.notification_app_name_settings);
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, appName);

        n.addExtras(extras);
    }
}
