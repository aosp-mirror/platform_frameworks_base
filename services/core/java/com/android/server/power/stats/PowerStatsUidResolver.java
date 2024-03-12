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

package com.android.server.power.stats;

import android.util.IntArray;
import android.util.Slog;
import android.util.SparseIntArray;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maintains a map of isolated UIDs to their respective owner UIDs, to support combining
 * power stats for isolated UIDs, which are typically short-lived, into the corresponding app UID.
 */
public class PowerStatsUidResolver {
    private static final String TAG = "PowerStatsUidResolver";

    /**
     * Listener notified when isolated UIDs are created and removed.
     */
    public interface Listener {

        /**
         * Callback invoked when a new isolated UID is registered.
         */
        void onIsolatedUidAdded(int isolatedUid, int parentUid);

        /**
         * Callback invoked before an isolated UID is evicted from the resolver.
         * If the listener calls {@link PowerStatsUidResolver#retainIsolatedUid}, the mapping
         * will be retained until {@link PowerStatsUidResolver#releaseIsolatedUid} is called.
         */
        void onBeforeIsolatedUidRemoved(int isolatedUid, int parentUid);

        /**
         * Callback invoked when an isolated UID to owner UID mapping is removed.
         */
        void onAfterIsolatedUidRemoved(int isolatedUid, int parentUid);
    }

    /**
     * Mapping isolated uids to the actual owning app uid.
     */
    private final SparseIntArray mIsolatedUids = new SparseIntArray();

    /**
     * Internal reference count of isolated uids.
     */
    private final SparseIntArray mIsolatedUidRefCounts = new SparseIntArray();

    // Keep the list read-only in order to avoid locking during the delivery of listener calls.
    private volatile List<Listener> mListeners = Collections.emptyList();

    /**
     * Adds a listener.
     */
    public void addListener(Listener listener) {
        synchronized (this) {
            List<Listener> newList = new ArrayList<>(mListeners);
            newList.add(listener);
            mListeners = Collections.unmodifiableList(newList);
        }
    }

    /**
     * Removes a listener.
     */
    public void removeListener(Listener listener) {
        synchronized (this) {
            List<Listener> newList = new ArrayList<>(mListeners);
            newList.remove(listener);
            mListeners = Collections.unmodifiableList(newList);
        }
    }

    /**
     * Remembers the connection between a newly created isolated UID and its owner app UID.
     * Calls {@link Listener#onIsolatedUidAdded} on each registered listener.
     */
    public void noteIsolatedUidAdded(int isolatedUid, int parentUid) {
        synchronized (this) {
            mIsolatedUids.put(isolatedUid, parentUid);
            mIsolatedUidRefCounts.put(isolatedUid, 1);
        }

        List<Listener> listeners = mListeners;
        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onIsolatedUidAdded(isolatedUid, parentUid);
        }
    }

    /**
     * Handles the removal of an isolated UID by invoking
     * {@link Listener#onBeforeIsolatedUidRemoved} on each registered listener and the releases
     * the UID, see {@link #releaseIsolatedUid}.
     */
    public void noteIsolatedUidRemoved(int isolatedUid, int parentUid) {
        synchronized (this) {
            int curUid = mIsolatedUids.get(isolatedUid, -1);
            if (curUid != parentUid) {
                Slog.wtf(TAG, "Attempt to remove an isolated UID " + isolatedUid
                              + " with the parent UID " + parentUid
                              + ". The registered parent UID is " + curUid);
                return;
            }
        }

        List<Listener> listeners = mListeners;
        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onBeforeIsolatedUidRemoved(isolatedUid, parentUid);
        }

        releaseIsolatedUid(isolatedUid);
    }

    /**
     * Increments the ref count for an isolated uid.
     * Call #releaseIsolatedUid to decrement.
     */
    public void retainIsolatedUid(int uid) {
        synchronized (this) {
            final int refCount = mIsolatedUidRefCounts.get(uid, 0);
            if (refCount <= 0) {
                // Uid is not mapped or referenced
                Slog.w(TAG,
                        "Attempted to increment ref counted of untracked isolated uid (" + uid
                        + ")");
                return;
            }
            mIsolatedUidRefCounts.put(uid, refCount + 1);
        }
    }

    /**
     * Decrements the ref count for the given isolated UID.  If the ref count drops to zero,
     * removes the mapping and calls {@link Listener#onAfterIsolatedUidRemoved} on each registered
     * listener.
     */
    public void releaseIsolatedUid(int isolatedUid) {
        int parentUid;
        synchronized (this) {
            final int refCount = mIsolatedUidRefCounts.get(isolatedUid, 0) - 1;
            if (refCount > 0) {
                // Isolated uid is still being tracked
                mIsolatedUidRefCounts.put(isolatedUid, refCount);
                return;
            }

            final int idx = mIsolatedUids.indexOfKey(isolatedUid);
            if (idx >= 0) {
                parentUid = mIsolatedUids.valueAt(idx);
                mIsolatedUids.removeAt(idx);
                mIsolatedUidRefCounts.delete(isolatedUid);
            } else {
                Slog.w(TAG, "Attempted to remove untracked child uid (" + isolatedUid + ")");
                return;
            }
        }

        List<Listener> listeners = mListeners;
        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).onAfterIsolatedUidRemoved(isolatedUid, parentUid);
        }
    }

    /**
     * Releases all isolated UIDs in the specified range, both ends inclusive.
     */
    public void releaseUidsInRange(int startUid, int endUid) {
        IntArray toRelease;
        synchronized (this) {
            int startIndex = mIsolatedUids.indexOfKey(startUid);
            int endIndex = mIsolatedUids.indexOfKey(endUid);

            if (startIndex < 0) {
                startIndex = ~startIndex;
            }

            if (endIndex < 0) {
                // In this ~endIndex is pointing just past where endUid would be, so we must -1.
                endIndex = ~endIndex - 1;
            }

            if (startIndex > endIndex) {
                return;
            }

            toRelease = new IntArray(endIndex - startIndex);
            for (int i = startIndex; i <= endIndex; i++) {
                toRelease.add(mIsolatedUids.keyAt(i));
            }
        }

        for (int i = toRelease.size() - 1; i >= 0; i--) {
            releaseIsolatedUid(toRelease.get(i));
        }
    }

    /**
     * Given an isolated UID, returns the corresponding owner UID.  For a non-isolated
     * UID, returns the UID itself.
     */
    public int mapUid(int uid) {
        synchronized (this) {
            return mIsolatedUids.get(/*key=*/uid, /*valueIfKeyNotFound=*/uid);
        }
    }

    /**
     * Dumps the current contents of the resolver for the sake of dumpsys.
     */
    public void dump(PrintWriter pw) {
        pw.println("Currently mapped isolated uids:");
        synchronized (this) {
            final int numIsolatedUids = mIsolatedUids.size();
            for (int i = 0; i < numIsolatedUids; i++) {
                final int isolatedUid = mIsolatedUids.keyAt(i);
                final int ownerUid = mIsolatedUids.valueAt(i);
                final int refs = mIsolatedUidRefCounts.get(isolatedUid);
                pw.println("  " + isolatedUid + "->" + ownerUid + " (ref count = " + refs + ")");
            }
        }
    }
}
