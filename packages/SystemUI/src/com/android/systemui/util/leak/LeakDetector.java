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

import android.os.Build;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.systemui.Dumpable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;

/**
 * Detects leaks.
 */
public class LeakDetector implements Dumpable {

    public static final boolean ENABLED = Build.IS_DEBUGGABLE;

    private final TrackedCollections mTrackedCollections;
    private final TrackedGarbage mTrackedGarbage;
    private final TrackedObjects mTrackedObjects;

    @VisibleForTesting
    public LeakDetector(TrackedCollections trackedCollections,
            TrackedGarbage trackedGarbage,
            TrackedObjects trackedObjects) {
        mTrackedCollections = trackedCollections;
        mTrackedGarbage = trackedGarbage;
        mTrackedObjects = trackedObjects;
    }

    /**
     * Tracks an instance that has a high leak risk (i.e. has complex ownership and references
     * a large amount of memory).
     *
     * The LeakDetector will monitor and keep weak references to such instances, dump statistics
     * about them in a bugreport, and in the future dump the heap if their count starts growing
     * unreasonably.
     *
     * This should be called when the instance is first constructed.
     */
    public <T> void trackInstance(T object) {
        if (mTrackedObjects != null) {
            mTrackedObjects.track(object);
        }
    }

    /**
     * Tracks a collection that is at risk of leaking large objects, e.g. a collection of
     * dynamically registered listeners.
     *
     * The LeakDetector will monitor and keep weak references to such collections, dump
     * statistics about them in a bugreport, and in the future dump the heap if their size starts
     * growing unreasonably.
     *
     * This should be called whenever the collection grows.
     *
     * @param tag A tag for labeling the collection in a bugreport
     */
    public <T> void trackCollection(Collection<T> collection, String tag) {
        if (mTrackedCollections != null) {
            mTrackedCollections.track(collection, tag);
        }
    }

    /**
     * Tracks an instance that should become garbage soon.
     *
     * The LeakDetector will monitor and keep weak references to such garbage, dump
     * statistics about them in a bugreport, and in the future dump the heap if it is not
     * collected reasonably soon.
     *
     * This should be called when the last strong reference to the instance is dropped.
     */
    public void trackGarbage(Object o) {
        if (mTrackedGarbage != null) {
            mTrackedGarbage.track(o);
        }
    }

    TrackedGarbage getTrackedGarbage() {
        return mTrackedGarbage;
    }

    @Override
    public void dump(FileDescriptor df, PrintWriter w, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(w, "  ");

        pw.println("SYSUI LEAK DETECTOR");
        pw.increaseIndent();

        if (mTrackedCollections != null && mTrackedGarbage != null) {
            pw.println("TrackedCollections:");
            pw.increaseIndent();
            mTrackedCollections.dump(pw, (col) -> !TrackedObjects.isTrackedObject(col));
            pw.decreaseIndent();
            pw.println();

            pw.println("TrackedObjects:");
            pw.increaseIndent();
            mTrackedCollections.dump(pw, TrackedObjects::isTrackedObject);
            pw.decreaseIndent();
            pw.println();

            pw.print("TrackedGarbage:");
            pw.increaseIndent();
            mTrackedGarbage.dump(pw);
            pw.decreaseIndent();
        } else {
            pw.println("disabled");
        }
        pw.decreaseIndent();
        pw.println();
    }

    public static LeakDetector create() {
        if (ENABLED) {
            TrackedCollections collections = new TrackedCollections();
            return new LeakDetector(collections, new TrackedGarbage(collections),
                    new TrackedObjects(collections));
        } else {
            return new LeakDetector(null, null, null);
        }
    }
}
