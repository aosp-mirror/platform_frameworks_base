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
import android.util.ArrayMap;

import java.io.PrintWriter;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Map;

/**
 * Tracks objects that have been marked as garbage.
 */
public class TrackedGarbage {

    /** Duration after which we consider garbage to be old. */
    private static final long GARBAGE_COLLECTION_DEADLINE_MILLIS = 60000; // 1min

    private final HashSet<LeakReference> mGarbage = new HashSet<>();
    private final ReferenceQueue<Object> mRefQueue = new ReferenceQueue<>();
    private final TrackedCollections mTrackedCollections;

    public TrackedGarbage(TrackedCollections trackedCollections) {
        mTrackedCollections = trackedCollections;
    }

    /**
     * @see LeakDetector#trackGarbage(Object)
     */
    public synchronized void track(Object o) {
        cleanUp();
        mGarbage.add(new LeakReference(o, mRefQueue));
        mTrackedCollections.track(mGarbage, "Garbage");
    }

    private void cleanUp() {
        Reference<?> ref;
        while ((ref = mRefQueue.poll()) != null) {
            mGarbage.remove(ref);
        }
    }

    /**
     * A reference to something we consider leaked if it still has strong references.
     *
     * Helpful for finding potential leaks in a heapdump: Simply find an instance of
     * LeakReference, find the object it refers to, then find a strong path to a GC root.
     */
    private static class LeakReference extends WeakReference<Object> {
        private final Class<?> clazz;
        private final long createdUptimeMillis;

        LeakReference(Object t, ReferenceQueue<Object> queue) {
            super(t, queue);
            clazz = t.getClass();
            createdUptimeMillis = SystemClock.uptimeMillis();
        }
    }

    /**
     * Dump statistics about the garbage.
     *
     * For each class, dumps the number of "garbage objects" that have not been collected yet.
     * A large number of old instances indicates a probable leak.
     */
    public synchronized void dump(PrintWriter pw) {
        cleanUp();

        long now = SystemClock.uptimeMillis();

        ArrayMap<Class<?>, Integer> acc = new ArrayMap<>();
        ArrayMap<Class<?>, Integer> accOld = new ArrayMap<>();
        for (LeakReference ref : mGarbage) {
            acc.put(ref.clazz, acc.getOrDefault(ref.clazz, 0) + 1);
            if (isOld(ref.createdUptimeMillis, now)) {
                accOld.put(ref.clazz, accOld.getOrDefault(ref.clazz, 0) + 1);
            }
        }

        for (Map.Entry<Class<?>, Integer> entry : acc.entrySet()) {
            pw.print(entry.getKey().getName());
            pw.print(": ");
            pw.print(entry.getValue());
            pw.print(" total, ");
            pw.print(accOld.getOrDefault(entry.getKey(), 0));
            pw.print(" old");
            pw.println();
        }
    }

    public synchronized int countOldGarbage() {
        cleanUp();

        long now = SystemClock.uptimeMillis();

        int result = 0;
        for (LeakReference ref : mGarbage) {
            if (isOld(ref.createdUptimeMillis, now)) {
                result++;
            }
        }
        return result;
    }

    private boolean isOld(long createdUptimeMillis, long now) {
        return createdUptimeMillis + GARBAGE_COLLECTION_DEADLINE_MILLIS < now;
    }
}
