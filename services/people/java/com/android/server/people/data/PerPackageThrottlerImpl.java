/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.people.data;

import android.os.Handler;
import android.util.Pair;

import java.util.HashSet;

/**
 * A class that implements a per-package throttler that prevents a runnable from executing more than
 * once every {@code debounceTime}.
 */
public class PerPackageThrottlerImpl implements PerPackageThrottler {
    private final Handler mBackgroundHandler;
    private final int mDebounceTime;
    private final HashSet<Pair<String, Integer>> mPkgScheduledTasks = new HashSet<>();

    PerPackageThrottlerImpl(Handler backgroundHandler, int debounceTime) {
        mBackgroundHandler = backgroundHandler;
        mDebounceTime = debounceTime;
    }

    @Override
    public synchronized void scheduleDebounced(
            Pair<String, Integer> pkgUserId, Runnable runnable) {
        if (mPkgScheduledTasks.contains(pkgUserId)) {
            return;
        }
        mPkgScheduledTasks.add(pkgUserId);
        mBackgroundHandler.postDelayed(() -> {
            synchronized (this) {
                mPkgScheduledTasks.remove(pkgUserId);
                runnable.run();
            }
        }, mDebounceTime);
    }
}
