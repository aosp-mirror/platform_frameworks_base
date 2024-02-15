/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import android.window.TaskSnapshot;

/**
 * A snapshot cache for activity, the token is the hashCode of the activity.
 */
class ActivitySnapshotCache extends SnapshotCache<ActivityRecord> {

    ActivitySnapshotCache() {
        super("Activity");
    }

    @Override
    void putSnapshot(ActivityRecord ar, TaskSnapshot snapshot) {
        final int hasCode = System.identityHashCode(ar);
        synchronized (mLock) {
            final CacheEntry entry = mRunningCache.get(hasCode);
            if (entry != null) {
                mAppIdMap.remove(entry.topApp);
            }
            mAppIdMap.put(ar, hasCode);
            mRunningCache.put(hasCode, new CacheEntry(snapshot, ar));
        }
    }
}
