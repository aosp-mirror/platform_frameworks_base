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

package com.android.server.wm;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.os.Handler;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.RemoteAnimationAdapter;

/**
 * Registry to keep track of remote animations to be run for activity starts from a certain package.
 *
 * @see ActivityTaskManagerService#registerRemoteAnimationForNextActivityStart
 */
class PendingRemoteAnimationRegistry {

    private static final long TIMEOUT_MS = 3000;

    private final ArrayMap<String, Entry> mEntries = new ArrayMap<>();
    private final Handler mHandler;
    private final WindowManagerGlobalLock mLock;

    PendingRemoteAnimationRegistry(WindowManagerGlobalLock lock, Handler handler) {
        mLock = lock;
        mHandler = handler;
    }

    /**
     * Adds a remote animation to be run for all activity starts originating from a certain package.
     */
    void addPendingAnimation(String packageName, RemoteAnimationAdapter adapter,
            @Nullable IBinder launchCookie) {
        mEntries.put(packageName, new Entry(packageName, adapter, launchCookie));
    }

    /**
     * Overrides the activity options with a registered remote animation for a certain calling
     * package if such a remote animation is registered.
     */
    ActivityOptions overrideOptionsIfNeeded(String callingPackage,
            @Nullable ActivityOptions options) {
        final Entry entry = mEntries.get(callingPackage);
        if (entry == null) {
            return options;
        }
        if (options == null) {
            options = ActivityOptions.makeRemoteAnimation(entry.adapter);
        } else {
            options.setRemoteAnimationAdapter(entry.adapter);
        }
        IBinder launchCookie = entry.launchCookie;
        if (launchCookie != null) {
            options.setLaunchCookie(launchCookie);
        }
        mEntries.remove(callingPackage);
        return options;
    }

    private class Entry {
        final String packageName;
        final RemoteAnimationAdapter adapter;
        @Nullable
        final IBinder launchCookie;

        Entry(String packageName, RemoteAnimationAdapter adapter, @Nullable IBinder launchCookie) {
            this.packageName = packageName;
            this.adapter = adapter;
            this.launchCookie = launchCookie;
            mHandler.postDelayed(() -> {
                synchronized (mLock) {
                    final Entry entry = mEntries.get(packageName);
                    if (entry == this) {
                        mEntries.remove(packageName);
                    }
                }
            }, TIMEOUT_MS);
        }
    }
}
