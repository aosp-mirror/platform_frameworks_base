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

package com.android.server.location.injector;

import static android.app.ActivityManager.RunningAppProcessInfo.Importance;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;

import com.android.internal.util.Preconditions;
import com.android.server.FgThread;

import java.util.Objects;

/**
 * Provides accessors and listeners for all application foreground status. An application is
 * considered foreground if it's uid's importance level is at or more important than
 * {@link android.app.ActivityManager.RunningAppProcessInfo#IMPORTANCE_FOREGROUND_SERVICE}.
 */
public class SystemAppForegroundHelper extends AppForegroundHelper {

    private final Context mContext;

    private ActivityManager mActivityManager;

    public SystemAppForegroundHelper(Context context) {
        mContext = context;
    }

    /** Called when system is ready. */
    public void onSystemReady() {
        if (mActivityManager != null) {
            return;
        }

        mActivityManager = Objects.requireNonNull(mContext.getSystemService(ActivityManager.class));
        mActivityManager.addOnUidImportanceListener(this::onAppForegroundChanged,
                FOREGROUND_IMPORTANCE_CUTOFF);
    }

    private void onAppForegroundChanged(int uid, @Importance int importance) {
        // invoked on ui thread, move to fg thread so we don't block the ui thread
        boolean foreground = isForeground(importance);
        FgThread.getHandler().post(() -> notifyAppForeground(uid, foreground));
    }

    @Override
    public boolean isAppForeground(int uid) {
        Preconditions.checkState(mActivityManager != null);

        final long identity = Binder.clearCallingIdentity();
        try {
            return isForeground(mActivityManager.getUidImportance(uid));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
