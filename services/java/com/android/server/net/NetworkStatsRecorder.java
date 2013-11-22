/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.net;

import static android.net.NetworkStats.TAG_NONE;
import static android.net.TrafficStats.KB_IN_BYTES;
import static android.net.TrafficStats.MB_IN_BYTES;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.net.NetworkStats;
import android.net.NetworkStats.NonMonotonicObserver;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.TrafficStats;
import android.os.DropBoxManager;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;

import com.android.internal.util.FileRotator;
import com.android.internal.util.IndentingPrintWriter;
import com.google.android.collect.Sets;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import libcore.io.IoUtils;

/**
 * Logic to record deltas between periodic {@link NetworkStats} snapshots into
 * {@link NetworkStatsHistory} that belong to {@link NetworkStatsCollection}.
 * Keeps pending changes in memory until they pass a specific threshold, in
 * bytes. Uses {@link FileRotator} for persistence logic.
 * <p>
 * Not inherently thread safe.
 */
public class NetworkStatsRecorder {
    private static final String TAG = "NetworkStatsRecorder";
    private static final boolean LOGD = false;
    private static final boolean LOGV = false;

    private static final String TAG_NETSTATS_DUMP = "netstats_dump";

    /** Dump before deleting in {@link #recoverFromWtf()}. */
    private static final boolean DUMP_BEFORE_DELETE = true;

    private final FileRotator mRotator;
    private final NonMonotonicObserver<String> mObserver;
    private final DropBoxManager mDropBox;
    private final String mCookie;

    private final long mBucketDuration;
    private final boolean mOnlyTags;

    private long mPersistThresholdBytes = 2 * MB_IN_BYTES;
    private NetworkStats mLastSnapshot;

    private final NetworkStatsCollection mPending;
    private final NetworkStatsCollection mSinceBoot;

    private final CombiningRewriter mPendingRewriter;

    private WeakReference<NetworkStatsCollection> mComplete;

    public NetworkStatsRecorder(FileRotator rotator, NonMonotonicObserver<String> observer,
            DropBoxManager dropBox, String cookie, long bucketDuration, boolean onlyTags) {
        mRotator = checkNotNull(rotator, "missing FileRotator");
        mObserver = checkNotNull(observer, "missing NonMonotonicObserver");
        mDropBox = checkNotNull(dropBox, "missing DropBoxManager");
        mCookie = cookie;

        mBucketDuration = bucketDuration;
        mOnlyTags = onlyTags;

        mPending = new NetworkStatsCollection(bucketDuration);
        mSinceBoot = new NetworkStatsCollection(bucketDuration);

        mPendingRewriter = new CombiningRewriter(mPending);
    }

    public void setPersistThreshold(long thresholdBytes) {
        if (LOGV) Slog.v(TAG, "setPersistThreshold() with " + thresholdBytes);
        mPersistThresholdBytes = MathUtils.constrain(
                thresholdBytes, 1 * KB_IN_BYTES, 100 * MB_IN_BYTES);
    }

    public void resetLocked() {
        mLastSnapshot = null;
        mPending.reset();
        mSinceBoot.reset();
        mComplete.clear();
    }

    public NetworkStats.Entry getTotalSinceBootLocked(NetworkTemplate template) {
        return mSinceBoot.getSummary(template, Long.MIN_VALUE, Long.MAX_VALUE).getTotal(null);
    }

    /**
     * Load complete history represented by {@link FileRotator}. Caches
     * internally as a {@link WeakReference}, and updated with future
     * {@link #recordSnapshotLocked(NetworkStats, Map, long)} snapshots as long
     * as reference is valid.
     */
    public NetworkStatsCollection getOrLoadCompleteLocked() {
        NetworkStatsCollection complete = mComplete != null ? mComplete.get() : null;
        if (complete == null) {
            if (LOGD) Slog.d(TAG, "getOrLoadCompleteLocked() reading from disk for " + mCookie);
            try {
                complete = new NetworkStatsCollection(mBucketDuration);
                mRotator.readMatching(complete, Long.MIN_VALUE, Long.MAX_VALUE);
                complete.recordCollection(mPending);
                mComplete = new WeakReference<NetworkStatsCollection>(complete);
            } catch (IOException e) {
                Log.wtf(TAG, "problem completely reading network stats", e);
                recoverFromWtf();
            } catch (OutOfMemoryError e) {
                Log.wtf(TAG, "problem completely reading network stats", e);
                recoverFromWtf();
            }
        }
        return complete;
    }

    /**
     * Record any delta that occurred since last {@link NetworkStats} snapshot,
     * using the given {@link Map} to identify network interfaces. First
     * snapshot is considered bootstrap, and is not counted as delta.
     */
    public void recordSnapshotLocked(NetworkStats snapshot,
            Map<String, NetworkIdentitySet> ifaceIdent, long currentTimeMillis) {
        final HashSet<String> unknownIfaces = Sets.newHashSet();

        // skip recording when snapshot missing
        if (snapshot == null) return;

        // assume first snapshot is bootstrap and don't record
        if (mLastSnapshot == null) {
            mLastSnapshot = snapshot;
            return;
        }

        final NetworkStatsCollection complete = mComplete != null ? mComplete.get() : null;

        final NetworkStats delta = NetworkStats.subtract(
                snapshot, mLastSnapshot, mObserver, mCookie);
        final long end = currentTimeMillis;
        final long start = end - delta.getElapsedRealtime();

        NetworkStats.Entry entry = null;
        for (int i = 0; i < delta.size(); i++) {
            entry = delta.getValues(i, entry);
            final NetworkIdentitySet ident = ifaceIdent.get(entry.iface);
            if (ident == null) {
                unknownIfaces.add(entry.iface);
                continue;
            }

            // skip when no delta occurred
            if (entry.isEmpty()) continue;

            // only record tag data when requested
            if ((entry.tag == TAG_NONE) != mOnlyTags) {
                mPending.recordData(ident, entry.uid, entry.set, entry.tag, start, end, entry);

                // also record against boot stats when present
                if (mSinceBoot != null) {
                    mSinceBoot.recordData(ident, entry.uid, entry.set, entry.tag, start, end, entry);
                }

                // also record against complete dataset when present
                if (complete != null) {
                    complete.recordData(ident, entry.uid, entry.set, entry.tag, start, end, entry);
                }
            }
        }

        mLastSnapshot = snapshot;

        if (LOGV && unknownIfaces.size() > 0) {
            Slog.w(TAG, "unknown interfaces " + unknownIfaces + ", ignoring those stats");
        }
    }

    /**
     * Consider persisting any pending deltas, if they are beyond
     * {@link #mPersistThresholdBytes}.
     */
    public void maybePersistLocked(long currentTimeMillis) {
        final long pendingBytes = mPending.getTotalBytes();
        if (pendingBytes >= mPersistThresholdBytes) {
            forcePersistLocked(currentTimeMillis);
        } else {
            mRotator.maybeRotate(currentTimeMillis);
        }
    }

    /**
     * Force persisting any pending deltas.
     */
    public void forcePersistLocked(long currentTimeMillis) {
        if (mPending.isDirty()) {
            if (LOGD) Slog.d(TAG, "forcePersistLocked() writing for " + mCookie);
            try {
                mRotator.rewriteActive(mPendingRewriter, currentTimeMillis);
                mRotator.maybeRotate(currentTimeMillis);
                mPending.reset();
            } catch (IOException e) {
                Log.wtf(TAG, "problem persisting pending stats", e);
                recoverFromWtf();
            } catch (OutOfMemoryError e) {
                Log.wtf(TAG, "problem persisting pending stats", e);
                recoverFromWtf();
            }
        }
    }

    /**
     * Remove the given UID from all {@link FileRotator} history, migrating it
     * to {@link TrafficStats#UID_REMOVED}.
     */
    public void removeUidsLocked(int[] uids) {
        try {
            // Rewrite all persisted data to migrate UID stats
            mRotator.rewriteAll(new RemoveUidRewriter(mBucketDuration, uids));
        } catch (IOException e) {
            Log.wtf(TAG, "problem removing UIDs " + Arrays.toString(uids), e);
            recoverFromWtf();
        } catch (OutOfMemoryError e) {
            Log.wtf(TAG, "problem removing UIDs " + Arrays.toString(uids), e);
            recoverFromWtf();
        }

        // Remove any pending stats
        mPending.removeUids(uids);
        mSinceBoot.removeUids(uids);

        // Clear UID from current stats snapshot
        if (mLastSnapshot != null) {
            mLastSnapshot = mLastSnapshot.withoutUids(uids);
        }

        final NetworkStatsCollection complete = mComplete != null ? mComplete.get() : null;
        if (complete != null) {
            complete.removeUids(uids);
        }
    }

    /**
     * Rewriter that will combine current {@link NetworkStatsCollection} values
     * with anything read from disk, and write combined set to disk. Clears the
     * original {@link NetworkStatsCollection} when finished writing.
     */
    private static class CombiningRewriter implements FileRotator.Rewriter {
        private final NetworkStatsCollection mCollection;

        public CombiningRewriter(NetworkStatsCollection collection) {
            mCollection = checkNotNull(collection, "missing NetworkStatsCollection");
        }

        @Override
        public void reset() {
            // ignored
        }

        @Override
        public void read(InputStream in) throws IOException {
            mCollection.read(in);
        }

        @Override
        public boolean shouldWrite() {
            return true;
        }

        @Override
        public void write(OutputStream out) throws IOException {
            mCollection.write(new DataOutputStream(out));
            mCollection.reset();
        }
    }

    /**
     * Rewriter that will remove any {@link NetworkStatsHistory} attributed to
     * the requested UID, only writing data back when modified.
     */
    public static class RemoveUidRewriter implements FileRotator.Rewriter {
        private final NetworkStatsCollection mTemp;
        private final int[] mUids;

        public RemoveUidRewriter(long bucketDuration, int[] uids) {
            mTemp = new NetworkStatsCollection(bucketDuration);
            mUids = uids;
        }

        @Override
        public void reset() {
            mTemp.reset();
        }

        @Override
        public void read(InputStream in) throws IOException {
            mTemp.read(in);
            mTemp.clearDirty();
            mTemp.removeUids(mUids);
        }

        @Override
        public boolean shouldWrite() {
            return mTemp.isDirty();
        }

        @Override
        public void write(OutputStream out) throws IOException {
            mTemp.write(new DataOutputStream(out));
        }
    }

    public void importLegacyNetworkLocked(File file) throws IOException {
        // legacy file still exists; start empty to avoid double importing
        mRotator.deleteAll();

        final NetworkStatsCollection collection = new NetworkStatsCollection(mBucketDuration);
        collection.readLegacyNetwork(file);

        final long startMillis = collection.getStartMillis();
        final long endMillis = collection.getEndMillis();

        if (!collection.isEmpty()) {
            // process legacy data, creating active file at starting time, then
            // using end time to possibly trigger rotation.
            mRotator.rewriteActive(new CombiningRewriter(collection), startMillis);
            mRotator.maybeRotate(endMillis);
        }
    }

    public void importLegacyUidLocked(File file) throws IOException {
        // legacy file still exists; start empty to avoid double importing
        mRotator.deleteAll();

        final NetworkStatsCollection collection = new NetworkStatsCollection(mBucketDuration);
        collection.readLegacyUid(file, mOnlyTags);

        final long startMillis = collection.getStartMillis();
        final long endMillis = collection.getEndMillis();

        if (!collection.isEmpty()) {
            // process legacy data, creating active file at starting time, then
            // using end time to possibly trigger rotation.
            mRotator.rewriteActive(new CombiningRewriter(collection), startMillis);
            mRotator.maybeRotate(endMillis);
        }
    }

    public void dumpLocked(IndentingPrintWriter pw, boolean fullHistory) {
        pw.print("Pending bytes: "); pw.println(mPending.getTotalBytes());
        if (fullHistory) {
            pw.println("Complete history:");
            getOrLoadCompleteLocked().dump(pw);
        } else {
            pw.println("History since boot:");
            mSinceBoot.dump(pw);
        }
    }

    /**
     * Recover from {@link FileRotator} failure by dumping state to
     * {@link DropBoxManager} and deleting contents.
     */
    private void recoverFromWtf() {
        if (DUMP_BEFORE_DELETE) {
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            try {
                mRotator.dumpAll(os);
            } catch (IOException e) {
                // ignore partial contents
                os.reset();
            } finally {
                IoUtils.closeQuietly(os);
            }
            mDropBox.addData(TAG_NETSTATS_DUMP, os.toByteArray(), 0);
        }

        mRotator.deleteAll();
    }
}
