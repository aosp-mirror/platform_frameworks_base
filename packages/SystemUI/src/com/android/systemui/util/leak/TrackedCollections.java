/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.util.leak;

import android.os.SystemClock;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Tracks the size of collections.
 */
public class TrackedCollections {
    private static final long MILLIS_IN_MINUTE = 60 * 1000;
    private static final long HALFWAY_DELAY = 30 * MILLIS_IN_MINUTE;

    private final WeakIdentityHashMap<Collection<?>, CollectionState> mCollections
            = new WeakIdentityHashMap<>();

    /**
     * @see LeakDetector#trackCollection(Collection, String)
     */
    public synchronized void track(Collection<?> collection, String tag) {
        CollectionState collectionState = mCollections.get(collection);
        if (collectionState == null) {
            collectionState = new CollectionState();
            collectionState.tag = tag;
            collectionState.startUptime = SystemClock.uptimeMillis();
            mCollections.put(collection, collectionState);
        }
        if (collectionState.halfwayCount == -1
                && SystemClock.uptimeMillis() - collectionState.startUptime > HALFWAY_DELAY) {
            collectionState.halfwayCount = collectionState.lastCount;
        }
        collectionState.lastCount = collection.size();
        collectionState.lastUptime = SystemClock.uptimeMillis();
    }

    private static class CollectionState {
        String tag;
        long startUptime;
        /** The number of elements in the collection at startUptime + HALFWAY_DELAY */
        int halfwayCount = -1;
        /** The number of elements in the collection at lastUptime */
        int lastCount = -1;
        long lastUptime;

        /**
         * Dump statistics about the tracked collection:
         * - the tag
         * - average elements inserted per hour during
         *   - the first 30min of its existence
         *   - after the first 30min
         *   - overall
         * - the current size of the collection
         */
        void dump(PrintWriter pw) {
            long now = SystemClock.uptimeMillis();

            pw.format("%s: %.2f (start-30min) / %.2f (30min-now) / %.2f (start-now)"
                            + " (growth rate in #/hour); %d (current size)",
                    tag,
                    ratePerHour(startUptime, 0, startUptime + HALFWAY_DELAY, halfwayCount),
                    ratePerHour(startUptime + HALFWAY_DELAY, halfwayCount, now, lastCount),
                    ratePerHour(startUptime, 0, now, lastCount),
                    lastCount);
        }

        private float ratePerHour(long uptime1, int count1, long uptime2, int count2) {
            if (uptime1 >= uptime2 || count1 < 0 || count2 < 0) {
                return Float.NaN;
            }
            return ((float) count2 - count1) / (uptime2 - uptime1) * 60 * MILLIS_IN_MINUTE;
        }
    }

    public synchronized void dump(PrintWriter pw, Predicate<Collection<?>> filter) {
        for (Map.Entry<WeakReference<Collection<?>>, CollectionState> entry
                : mCollections.entrySet()) {
            Collection<?> key = entry.getKey().get();
            if (filter == null || key != null && filter.test(key)) {
                entry.getValue().dump(pw);
                pw.println();
            }
        }
    }
}
