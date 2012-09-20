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

import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.UID_REMOVED;

import android.net.NetworkIdentity;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.TrafficStats;
import android.text.format.DateUtils;
import android.util.AtomicFile;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FileRotator;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Objects;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import libcore.io.IoUtils;

/**
 * Collection of {@link NetworkStatsHistory}, stored based on combined key of
 * {@link NetworkIdentitySet}, UID, set, and tag. Knows how to persist itself.
 */
public class NetworkStatsCollection implements FileRotator.Reader {
    /** File header magic number: "ANET" */
    private static final int FILE_MAGIC = 0x414E4554;

    private static final int VERSION_NETWORK_INIT = 1;

    private static final int VERSION_UID_INIT = 1;
    private static final int VERSION_UID_WITH_IDENT = 2;
    private static final int VERSION_UID_WITH_TAG = 3;
    private static final int VERSION_UID_WITH_SET = 4;

    private static final int VERSION_UNIFIED_INIT = 16;

    private HashMap<Key, NetworkStatsHistory> mStats = Maps.newHashMap();

    private final long mBucketDuration;

    private long mStartMillis;
    private long mEndMillis;
    private long mTotalBytes;
    private boolean mDirty;

    public NetworkStatsCollection(long bucketDuration) {
        mBucketDuration = bucketDuration;
        reset();
    }

    public void reset() {
        mStats.clear();
        mStartMillis = Long.MAX_VALUE;
        mEndMillis = Long.MIN_VALUE;
        mTotalBytes = 0;
        mDirty = false;
    }

    public long getStartMillis() {
        return mStartMillis;
    }

    /**
     * Return first atomic bucket in this collection, which is more conservative
     * than {@link #mStartMillis}.
     */
    public long getFirstAtomicBucketMillis() {
        if (mStartMillis == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        } else {
            return mStartMillis + mBucketDuration;
        }
    }

    public long getEndMillis() {
        return mEndMillis;
    }

    public long getTotalBytes() {
        return mTotalBytes;
    }

    public boolean isDirty() {
        return mDirty;
    }

    public void clearDirty() {
        mDirty = false;
    }

    public boolean isEmpty() {
        return mStartMillis == Long.MAX_VALUE && mEndMillis == Long.MIN_VALUE;
    }

    /**
     * Combine all {@link NetworkStatsHistory} in this collection which match
     * the requested parameters.
     */
    public NetworkStatsHistory getHistory(
            NetworkTemplate template, int uid, int set, int tag, int fields) {
        return getHistory(template, uid, set, tag, fields, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * Combine all {@link NetworkStatsHistory} in this collection which match
     * the requested parameters.
     */
    public NetworkStatsHistory getHistory(
            NetworkTemplate template, int uid, int set, int tag, int fields, long start, long end) {
        final NetworkStatsHistory combined = new NetworkStatsHistory(
                mBucketDuration, estimateBuckets(), fields);
        for (Map.Entry<Key, NetworkStatsHistory> entry : mStats.entrySet()) {
            final Key key = entry.getKey();
            final boolean setMatches = set == SET_ALL || key.set == set;
            if (key.uid == uid && setMatches && key.tag == tag
                    && templateMatches(template, key.ident)) {
                combined.recordHistory(entry.getValue(), start, end);
            }
        }
        return combined;
    }

    /**
     * Summarize all {@link NetworkStatsHistory} in this collection which match
     * the requested parameters.
     */
    public NetworkStats getSummary(NetworkTemplate template, long start, long end) {
        final long now = System.currentTimeMillis();

        final NetworkStats stats = new NetworkStats(end - start, 24);
        final NetworkStats.Entry entry = new NetworkStats.Entry();
        NetworkStatsHistory.Entry historyEntry = null;

        // shortcut when we know stats will be empty
        if (start == end) return stats;

        for (Map.Entry<Key, NetworkStatsHistory> mapEntry : mStats.entrySet()) {
            final Key key = mapEntry.getKey();
            if (templateMatches(template, key.ident)) {
                final NetworkStatsHistory history = mapEntry.getValue();
                historyEntry = history.getValues(start, end, now, historyEntry);

                entry.iface = IFACE_ALL;
                entry.uid = key.uid;
                entry.set = key.set;
                entry.tag = key.tag;
                entry.rxBytes = historyEntry.rxBytes;
                entry.rxPackets = historyEntry.rxPackets;
                entry.txBytes = historyEntry.txBytes;
                entry.txPackets = historyEntry.txPackets;
                entry.operations = historyEntry.operations;

                if (!entry.isEmpty()) {
                    stats.combineValues(entry);
                }
            }
        }

        return stats;
    }

    /**
     * Record given {@link android.net.NetworkStats.Entry} into this collection.
     */
    public void recordData(NetworkIdentitySet ident, int uid, int set, int tag, long start,
            long end, NetworkStats.Entry entry) {
        final NetworkStatsHistory history = findOrCreateHistory(ident, uid, set, tag);
        history.recordData(start, end, entry);
        noteRecordedHistory(history.getStart(), history.getEnd(), entry.rxBytes + entry.txBytes);
    }

    /**
     * Record given {@link NetworkStatsHistory} into this collection.
     */
    private void recordHistory(Key key, NetworkStatsHistory history) {
        if (history.size() == 0) return;
        noteRecordedHistory(history.getStart(), history.getEnd(), history.getTotalBytes());

        NetworkStatsHistory target = mStats.get(key);
        if (target == null) {
            target = new NetworkStatsHistory(history.getBucketDuration());
            mStats.put(key, target);
        }
        target.recordEntireHistory(history);
    }

    /**
     * Record all {@link NetworkStatsHistory} contained in the given collection
     * into this collection.
     */
    public void recordCollection(NetworkStatsCollection another) {
        for (Map.Entry<Key, NetworkStatsHistory> entry : another.mStats.entrySet()) {
            recordHistory(entry.getKey(), entry.getValue());
        }
    }

    private NetworkStatsHistory findOrCreateHistory(
            NetworkIdentitySet ident, int uid, int set, int tag) {
        final Key key = new Key(ident, uid, set, tag);
        final NetworkStatsHistory existing = mStats.get(key);

        // update when no existing, or when bucket duration changed
        NetworkStatsHistory updated = null;
        if (existing == null) {
            updated = new NetworkStatsHistory(mBucketDuration, 10);
        } else if (existing.getBucketDuration() != mBucketDuration) {
            updated = new NetworkStatsHistory(existing, mBucketDuration);
        }

        if (updated != null) {
            mStats.put(key, updated);
            return updated;
        } else {
            return existing;
        }
    }

    @Override
    public void read(InputStream in) throws IOException {
        read(new DataInputStream(in));
    }

    public void read(DataInputStream in) throws IOException {
        // verify file magic header intact
        final int magic = in.readInt();
        if (magic != FILE_MAGIC) {
            throw new ProtocolException("unexpected magic: " + magic);
        }

        final int version = in.readInt();
        switch (version) {
            case VERSION_UNIFIED_INIT: {
                // uid := size *(NetworkIdentitySet size *(uid set tag NetworkStatsHistory))
                final int identSize = in.readInt();
                for (int i = 0; i < identSize; i++) {
                    final NetworkIdentitySet ident = new NetworkIdentitySet(in);

                    final int size = in.readInt();
                    for (int j = 0; j < size; j++) {
                        final int uid = in.readInt();
                        final int set = in.readInt();
                        final int tag = in.readInt();

                        final Key key = new Key(ident, uid, set, tag);
                        final NetworkStatsHistory history = new NetworkStatsHistory(in);
                        recordHistory(key, history);
                    }
                }
                break;
            }
            default: {
                throw new ProtocolException("unexpected version: " + version);
            }
        }
    }

    public void write(DataOutputStream out) throws IOException {
        // cluster key lists grouped by ident
        final HashMap<NetworkIdentitySet, ArrayList<Key>> keysByIdent = Maps.newHashMap();
        for (Key key : mStats.keySet()) {
            ArrayList<Key> keys = keysByIdent.get(key.ident);
            if (keys == null) {
                keys = Lists.newArrayList();
                keysByIdent.put(key.ident, keys);
            }
            keys.add(key);
        }

        out.writeInt(FILE_MAGIC);
        out.writeInt(VERSION_UNIFIED_INIT);

        out.writeInt(keysByIdent.size());
        for (NetworkIdentitySet ident : keysByIdent.keySet()) {
            final ArrayList<Key> keys = keysByIdent.get(ident);
            ident.writeToStream(out);

            out.writeInt(keys.size());
            for (Key key : keys) {
                final NetworkStatsHistory history = mStats.get(key);
                out.writeInt(key.uid);
                out.writeInt(key.set);
                out.writeInt(key.tag);
                history.writeToStream(out);
            }
        }

        out.flush();
    }

    @Deprecated
    public void readLegacyNetwork(File file) throws IOException {
        final AtomicFile inputFile = new AtomicFile(file);

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(inputFile.openRead()));

            // verify file magic header intact
            final int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new ProtocolException("unexpected magic: " + magic);
            }

            final int version = in.readInt();
            switch (version) {
                case VERSION_NETWORK_INIT: {
                    // network := size *(NetworkIdentitySet NetworkStatsHistory)
                    final int size = in.readInt();
                    for (int i = 0; i < size; i++) {
                        final NetworkIdentitySet ident = new NetworkIdentitySet(in);
                        final NetworkStatsHistory history = new NetworkStatsHistory(in);

                        final Key key = new Key(ident, UID_ALL, SET_ALL, TAG_NONE);
                        recordHistory(key, history);
                    }
                    break;
                }
                default: {
                    throw new ProtocolException("unexpected version: " + version);
                }
            }
        } catch (FileNotFoundException e) {
            // missing stats is okay, probably first boot
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    @Deprecated
    public void readLegacyUid(File file, boolean onlyTags) throws IOException {
        final AtomicFile inputFile = new AtomicFile(file);

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(inputFile.openRead()));

            // verify file magic header intact
            final int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new ProtocolException("unexpected magic: " + magic);
            }

            final int version = in.readInt();
            switch (version) {
                case VERSION_UID_INIT: {
                    // uid := size *(UID NetworkStatsHistory)

                    // drop this data version, since we don't have a good
                    // mapping into NetworkIdentitySet.
                    break;
                }
                case VERSION_UID_WITH_IDENT: {
                    // uid := size *(NetworkIdentitySet size *(UID NetworkStatsHistory))

                    // drop this data version, since this version only existed
                    // for a short time.
                    break;
                }
                case VERSION_UID_WITH_TAG:
                case VERSION_UID_WITH_SET: {
                    // uid := size *(NetworkIdentitySet size *(uid set tag NetworkStatsHistory))
                    final int identSize = in.readInt();
                    for (int i = 0; i < identSize; i++) {
                        final NetworkIdentitySet ident = new NetworkIdentitySet(in);

                        final int size = in.readInt();
                        for (int j = 0; j < size; j++) {
                            final int uid = in.readInt();
                            final int set = (version >= VERSION_UID_WITH_SET) ? in.readInt()
                                    : SET_DEFAULT;
                            final int tag = in.readInt();

                            final Key key = new Key(ident, uid, set, tag);
                            final NetworkStatsHistory history = new NetworkStatsHistory(in);

                            if ((tag == TAG_NONE) != onlyTags) {
                                recordHistory(key, history);
                            }
                        }
                    }
                    break;
                }
                default: {
                    throw new ProtocolException("unexpected version: " + version);
                }
            }
        } catch (FileNotFoundException e) {
            // missing stats is okay, probably first boot
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    /**
     * Remove any {@link NetworkStatsHistory} attributed to the requested UID,
     * moving any {@link NetworkStats#TAG_NONE} series to
     * {@link TrafficStats#UID_REMOVED}.
     */
    public void removeUids(int[] uids) {
        final ArrayList<Key> knownKeys = Lists.newArrayList();
        knownKeys.addAll(mStats.keySet());

        // migrate all UID stats into special "removed" bucket
        for (Key key : knownKeys) {
            if (ArrayUtils.contains(uids, key.uid)) {
                // only migrate combined TAG_NONE history
                if (key.tag == TAG_NONE) {
                    final NetworkStatsHistory uidHistory = mStats.get(key);
                    final NetworkStatsHistory removedHistory = findOrCreateHistory(
                            key.ident, UID_REMOVED, SET_DEFAULT, TAG_NONE);
                    removedHistory.recordEntireHistory(uidHistory);
                }
                mStats.remove(key);
                mDirty = true;
            }
        }
    }

    private void noteRecordedHistory(long startMillis, long endMillis, long totalBytes) {
        if (startMillis < mStartMillis) mStartMillis = startMillis;
        if (endMillis > mEndMillis) mEndMillis = endMillis;
        mTotalBytes += totalBytes;
        mDirty = true;
    }

    private int estimateBuckets() {
        return (int) (Math.min(mEndMillis - mStartMillis, DateUtils.WEEK_IN_MILLIS * 5)
                / mBucketDuration);
    }

    public void dump(IndentingPrintWriter pw) {
        final ArrayList<Key> keys = Lists.newArrayList();
        keys.addAll(mStats.keySet());
        Collections.sort(keys);

        for (Key key : keys) {
            pw.print("ident="); pw.print(key.ident.toString());
            pw.print(" uid="); pw.print(key.uid);
            pw.print(" set="); pw.print(NetworkStats.setToString(key.set));
            pw.print(" tag="); pw.println(NetworkStats.tagToString(key.tag));

            final NetworkStatsHistory history = mStats.get(key);
            pw.increaseIndent();
            history.dump(pw, true);
            pw.decreaseIndent();
        }
    }

    /**
     * Test if given {@link NetworkTemplate} matches any {@link NetworkIdentity}
     * in the given {@link NetworkIdentitySet}.
     */
    private static boolean templateMatches(NetworkTemplate template, NetworkIdentitySet identSet) {
        for (NetworkIdentity ident : identSet) {
            if (template.matches(ident)) {
                return true;
            }
        }
        return false;
    }

    private static class Key implements Comparable<Key> {
        public final NetworkIdentitySet ident;
        public final int uid;
        public final int set;
        public final int tag;

        private final int hashCode;

        public Key(NetworkIdentitySet ident, int uid, int set, int tag) {
            this.ident = ident;
            this.uid = uid;
            this.set = set;
            this.tag = tag;
            hashCode = Objects.hashCode(ident, uid, set, tag);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Key) {
                final Key key = (Key) obj;
                return uid == key.uid && set == key.set && tag == key.tag
                        && Objects.equal(ident, key.ident);
            }
            return false;
        }

        @Override
        public int compareTo(Key another) {
            return Integer.compare(uid, another.uid);
        }
    }
}
