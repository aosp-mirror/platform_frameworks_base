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

package com.android.server.location;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.RunningAppProcessInfo.Importance;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides accessors and listeners for all application foreground status. An application is
 * considered foreground if it's uid's importance level is at or more important than
 * {@link android.app.ActivityManager.RunningAppProcessInfo#IMPORTANCE_FOREGROUND_SERVICE}.
 */
public class AppForegroundHelper {

    /**
     * Listener for application foreground state changes.
     */
    public interface AppForegroundListener {
        /**
         * Called when an application's foreground state changes.
         */
        void onAppForegroundChanged(int uid, boolean foreground);
    }

    // importance constants decrement with increasing importance - this is our limit for an
    // importance level we consider foreground.
    private static final int FOREGROUND_IMPORTANCE_CUTOFF = IMPORTANCE_FOREGROUND_SERVICE;

    private static boolean isForeground(@Importance int importance) {
        return importance <= FOREGROUND_IMPORTANCE_CUTOFF;
    }

    private final Context mContext;
    private final CopyOnWriteArrayList<AppForegroundListener> mListeners;

    @GuardedBy("this")
    @Nullable private ActivityManager mActivityManager;

    public AppForegroundHelper(Context context) {
        mContext = context;
        mListeners = new CopyOnWriteArrayList<>();
    }

    /** Called when system is ready. */
    public synchronized void onSystemReady() {
        if (mActivityManager != null) {
            return;
        }

        mActivityManager = Objects.requireNonNull(mContext.getSystemService(ActivityManager.class));
        mActivityManager.addOnUidImportanceListener(this::onAppForegroundChanged,
                FOREGROUND_IMPORTANCE_CUTOFF);
    }

    /**
     * Adds a listener for app foreground changed events. Callbacks occur on an unspecified thread.
     */
    public void addListener(AppForegroundListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for app foreground changed events.
     */
    public void removeListener(AppForegroundListener listener) {
        mListeners.remove(listener);
    }

    private void onAppForegroundChanged(int uid, @Importance int importance) {
        // invoked on ui thread, move to fg thread so we don't block the ui thread
        boolean foreground = isForeground(importance);
        FgThread.getHandler().post(() -> {
            for (AppForegroundListener listener : mListeners) {
                listener.onAppForegroundChanged(uid, foreground);
            }
        });
    }

    /**
     * Whether the given uid is currently foreground.
     */
    public boolean isAppForeground(int uid) {
        return isForeground(getImportance(uid));
    }

    /**
     * Retrieves the current importance of the given uid.
     *
     * @deprecated Prefer {@link #isAppForeground(int)}.
     */
    @Deprecated
    @Importance
    public int getImportance(int uid) {
        synchronized (this) {
            Preconditions.checkState(mActivityManager != null);
        }

        long identity = Binder.clearCallingIdentity();
        try {
            return mActivityManager.getUidImportance(uid);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
