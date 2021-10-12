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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.RunningAppProcessInfo.Importance;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides accessors and listeners for all application foreground status. An application is
 * considered foreground if it's uid's importance level is at or more important than
 * {@link android.app.ActivityManager.RunningAppProcessInfo#IMPORTANCE_FOREGROUND_SERVICE}.
 */
public abstract class AppForegroundHelper {

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
    protected static final int FOREGROUND_IMPORTANCE_CUTOFF = IMPORTANCE_FOREGROUND_SERVICE;

    protected static boolean isForeground(@Importance int importance) {
        return importance <= FOREGROUND_IMPORTANCE_CUTOFF;
    }

    private final CopyOnWriteArrayList<AppForegroundListener> mListeners;

    public AppForegroundHelper() {
        mListeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Adds a listener for app foreground changed events. Callbacks occur on an unspecified thread.
     */
    public final void addListener(AppForegroundListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for app foreground changed events.
     */
    public final void removeListener(AppForegroundListener listener) {
        mListeners.remove(listener);
    }

    protected final void notifyAppForeground(int uid, boolean foreground) {
        for (AppForegroundListener listener : mListeners) {
            listener.onAppForegroundChanged(uid, foreground);
        }
    }

    /**
     * Whether the given uid is currently foreground.
     */
    public abstract boolean isAppForeground(int uid);
}
