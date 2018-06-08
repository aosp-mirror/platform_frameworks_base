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
 * limitations under the License.
 */
package com.android.server.content;

import android.app.usage.UsageStatsManagerInternal;
import android.os.SystemClock;
import android.util.Pair;

import com.android.server.AppStateTracker;
import com.android.server.LocalServices;

import java.util.HashMap;

class SyncAdapterStateFetcher {

    private final HashMap<Pair<Integer, String>, Integer> mBucketCache =
            new HashMap<>();

    public SyncAdapterStateFetcher() {
    }

    /**
     * Return sync adapter state with a cache.
     */
    public int getStandbyBucket(int userId, String packageName) {
        final Pair<Integer, String> key = Pair.create(userId, packageName);
        final Integer cached = mBucketCache.get(key);
        if (cached != null) {
            return cached;
        }
        final UsageStatsManagerInternal usmi =
                LocalServices.getService(UsageStatsManagerInternal.class);
        if (usmi == null) {
            return -1; // Unknown.
        }

        final int value = usmi.getAppStandbyBucket(packageName, userId,
                SystemClock.elapsedRealtime());
        mBucketCache.put(key, value);
        return value;
    }

    /**
     * Return UID active state.
     */
    public boolean isAppActive(int uid) {
        final AppStateTracker ast =
                LocalServices.getService(AppStateTracker.class);
        if (ast == null) {
            return false;
        }

        return ast.isUidActive(uid);
    }
}
