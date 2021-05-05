/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * A class that caches snapshots.  Instances are instantiated on a {@link Watchable}; when the
 * {@link Watchable} reports a change, the cache is cleared.  The snapshot() method fetches the
 * cache if it is valid, or rebuilds the cache if it has been cleared.
 *
 * The class is abstract; clients must implement the createSnapshot() method.
 *
 * @param <T> The type returned by the snapshot() method.
 */
public abstract class SnapshotCache<T> extends Watcher{

    /**
     * Global snapshot cache enable flag.  Set to false for testing or debugging.
     */
    private static final boolean ENABLED = true;

    // The source object from which snapshots are created.  This may be null if createSnapshot()
    // does not require it.
    protected final T mSource;

    // The cached snapshot
    private T mSnapshot = null;

    // True if the snapshot is sealed and may not be modified.
    private boolean mSealed = false;

    /**
     * Create a cache with a source object for rebuilding snapshots and a
     * {@link Watchable} that notifies when the cache is invalid.
     * @param source Source data for rebuilding snapshots.
     * @param watchable The object that notifies when the cache is invalid.
     */
    public SnapshotCache(@Nullable T source, @NonNull Watchable watchable) {
        mSource = source;
        watchable.registerObserver(this);
    }

    /**
     * A private constructor that sets fields to null and mSealed to true.  This supports
     * the Sealed subclass.
     */
    public SnapshotCache() {
        mSource = null;
        mSealed = true;
    }

    /**
     * Notify the object that the source object has changed.  If the local object is sealed then
     * IllegalStateException is thrown.  Otherwise, the cache is cleared.
     */
    public void onChange(@Nullable Watchable what) {
        if (mSealed) {
            throw new IllegalStateException("attempt to change a sealed object");
        }
        mSnapshot = null;
    }

    /**
     * Seal the cache.  Attempts to modify the cache will generate an exception.
     */
    public void seal() {
        mSealed = true;
    }

    /**
     * Return a snapshot.  This uses the cache if it is non-null.  Otherwise it creates a
     * new snapshot and saves it in the cache.
     * @return A snapshot as returned by createSnapshot() and possibly cached.
     */
    public T snapshot() {
        T s = mSnapshot;
        if (s == null || !ENABLED) {
            s = createSnapshot();
            mSnapshot = s;
        }
        return s;
    }

    /**
     * Create a single, uncached snapshot.  Clients must implement this per local rules.
     * @return A snapshot
     */
    public abstract T createSnapshot();

    /**
     * A snapshot cache suitable for sealed snapshots.  Attempting to retrieve the
     * snapshot will throw an UnsupportedOperationException.
     * @param <T> the type of object being cached.  This is needed for compilation only.  It
     * has no effect on execution.
     */
    public static class Sealed<T> extends SnapshotCache<T> {
        /**
         * Create a sealed SnapshotCache that cannot be used to create new snapshots.
         */
        public Sealed() {
        }
        /**
         * Provide a concrete implementation of createSnapshot() that throws
         * UnsupportedOperationException.
         */
        public T createSnapshot() {
            throw new UnsupportedOperationException("cannot snapshot a sealed snaphot");
        }
    }
}
