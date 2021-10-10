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

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.DEFAULT_NETWORK_YES;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.ROAMING_YES;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.UID_REMOVED;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;

import static com.android.internal.net.NetworkUtilsInternal.multiplySafeByRational;
import static com.android.server.net.NetworkStatsService.TAG;

import android.net.NetworkIdentity;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.TrafficStats;
import android.os.Binder;
import android.service.NetworkStatsCollectionKeyProto;
import android.service.NetworkStatsCollectionProto;
import android.service.NetworkStatsCollectionStatsProto;
import android.telephony.SubscriptionPlan;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.IntArray;
import android.util.MathUtils;
import android.util.Range;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastDataInput;
import com.android.internal.util.FastDataOutput;
import com.android.internal.util.FileRotator;
import com.android.internal.util.IndentingPrintWriter;

import libcore.io.IoUtils;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ProtocolException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;

/**
 * Collection of {@link NetworkStatsHistory}, stored based on combined key of
 * {@link NetworkIdentitySet}, UID, set, and tag. Knows how to persist itself.
 */
public class NetworkStatsCollection implements FileRotator.Reader, FileRotator.Writer {
    /** File header magic number: "ANET" */
    private static final int FILE_MAGIC = 0x414E4554;

    /** Default buffer size from BufferedInputStream */
    private static final int BUFFER_SIZE = 8192;

    private static final int VERSION_NETWORK_INIT = 1;

    private static final int VERSION_UID_INIT = 1;
    private static final int VERSION_UID_WITH_IDENT = 2;
    private static final int VERSION_UID_WITH_TAG = 3;
    private static final int VERSION_UID_WITH_SET = 4;

    private static final int VERSION_UNIFIED_INIT = 16;

    private ArrayMap<Key, NetworkStatsHistory> mStats = new ArrayMap<>();

    private final long mBucketDuration;

    private long mStartMillis;
    private long mEndMillis;
    private long mTotalBytes;
    private boolean mDirty;

    public NetworkStatsCollection(long bucketDuration) {
        mBucketDuration = bucketDuration;
        reset();
    }

    public void clear() {
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

    @VisibleForTesting
    public long roundUp(long time) {
        if (time == Long.MIN_VALUE || time == Long.MAX_VALUE
                || time == SubscriptionPlan.TIME_UNKNOWN) {
            return time;
        } else {
            final long mod = time % mBucketDuration;
            if (mod > 0) {
                time -= mod;
                time += mBucketDuration;
            }
            return time;
        }
    }

    @VisibleForTesting
    public long roundDown(long time) {
        if (time == Long.MIN_VALUE || time == Long.MAX_VALUE
                || time == SubscriptionPlan.TIME_UNKNOWN) {
            return time;
        } else {
            final long mod = time % mBucketDuration;
            if (mod > 0) {
                time -= mod;
            }
            return time;
        }
    }

    public int[] getRelevantUids(@NetworkStatsAccess.Level int accessLevel) {
        return getRelevantUids(accessLevel, Binder.getCallingUid());
    }

    public int[] getRelevantUids(@NetworkStatsAccess.Level int accessLevel,
                final int callerUid) {
        IntArray uids = new IntArray();
        for (int i = 0; i < mStats.size(); i++) {
            final Key key = mStats.keyAt(i);
            if (NetworkStatsAccess.isAccessibleToUser(key.uid, callerUid, accessLevel)) {
                int j = uids.binarySearch(key.uid);

                if (j < 0) {
                    j = ~j;
                    uids.add(j, key.uid);
                }
            }
        }
        return uids.toArray();
    }

    /**
     * Combine all {@link NetworkStatsHistory} in this collection which match
     * the requested parameters.
     */
    public NetworkStatsHistory getHistory(NetworkTemplate template, SubscriptionPlan augmentPlan,
            int uid, int set, int tag, int fields, long start, long end,
            @NetworkStatsAccess.Level int accessLevel, int callerUid) {
        if (!NetworkStatsAccess.isAccessibleToUser(uid, callerUid, accessLevel)) {
            throw new SecurityException("Network stats history of uid " + uid
                    + " is forbidden for caller " + callerUid);
        }

        // 180 days of history should be enough for anyone; if we end up needing
        // more, we'll dynamically grow the history object.
        final int bucketEstimate = (int) MathUtils.constrain(((end - start) / mBucketDuration), 0,
                (180 * DateUtils.DAY_IN_MILLIS) / mBucketDuration);
        final NetworkStatsHistory combined = new NetworkStatsHistory(
                mBucketDuration, bucketEstimate, fields);

        // shortcut when we know stats will be empty
        if (start == end) return combined;

        // Figure out the window of time that we should be augmenting (if any)
        long augmentStart = SubscriptionPlan.TIME_UNKNOWN;
        long augmentEnd = (augmentPlan != null) ? augmentPlan.getDataUsageTime()
                : SubscriptionPlan.TIME_UNKNOWN;
        // And if augmenting, we might need to collect more data to adjust with
        long collectStart = start;
        long collectEnd = end;

        if (augmentEnd != SubscriptionPlan.TIME_UNKNOWN) {
            final Iterator<Range<ZonedDateTime>> it = augmentPlan.cycleIterator();
            while (it.hasNext()) {
                final Range<ZonedDateTime> cycle = it.next();
                final long cycleStart = cycle.getLower().toInstant().toEpochMilli();
                final long cycleEnd = cycle.getUpper().toInstant().toEpochMilli();
                if (cycleStart <= augmentEnd && augmentEnd < cycleEnd) {
                    augmentStart = cycleStart;
                    collectStart = Long.min(collectStart, augmentStart);
                    collectEnd = Long.max(collectEnd, augmentEnd);
                    break;
                }
            }
        }

        if (augmentStart != SubscriptionPlan.TIME_UNKNOWN) {
            // Shrink augmentation window so we don't risk undercounting.
            augmentStart = roundUp(augmentStart);
            augmentEnd = roundDown(augmentEnd);
            // Grow collection window so we get all the stats needed.
            collectStart = roundDown(collectStart);
            collectEnd = roundUp(collectEnd);
        }

        for (int i = 0; i < mStats.size(); i++) {
            final Key key = mStats.keyAt(i);
            if (key.uid == uid && NetworkStats.setMatches(set, key.set) && key.tag == tag
                    && templateMatches(template, key.ident)) {
                final NetworkStatsHistory value = mStats.valueAt(i);
                combined.recordHistory(value, collectStart, collectEnd);
            }
        }

        if (augmentStart != SubscriptionPlan.TIME_UNKNOWN) {
            final NetworkStatsHistory.Entry entry = combined.getValues(
                    augmentStart, augmentEnd, null);

            // If we don't have any recorded data for this time period, give
            // ourselves something to scale with.
            if (entry.rxBytes == 0 || entry.txBytes == 0) {
                combined.recordData(augmentStart, augmentEnd,
                        new NetworkStats.Entry(1, 0, 1, 0, 0));
                combined.getValues(augmentStart, augmentEnd, entry);
            }

            final long rawBytes = (entry.rxBytes + entry.txBytes) == 0 ? 1 :
                    (entry.rxBytes + entry.txBytes);
            final long rawRxBytes = entry.rxBytes == 0 ? 1 : entry.rxBytes;
            final long rawTxBytes = entry.txBytes == 0 ? 1 : entry.txBytes;
            final long targetBytes = augmentPlan.getDataUsageBytes();

            final long targetRxBytes = multiplySafeByRational(targetBytes, rawRxBytes, rawBytes);
            final long targetTxBytes = multiplySafeByRational(targetBytes, rawTxBytes, rawBytes);


            // Scale all matching buckets to reach anchor target
            final long beforeTotal = combined.getTotalBytes();
            for (int i = 0; i < combined.size(); i++) {
                combined.getValues(i, entry);
                if (entry.bucketStart >= augmentStart
                        && entry.bucketStart + entry.bucketDuration <= augmentEnd) {
                    entry.rxBytes = multiplySafeByRational(
                            targetRxBytes, entry.rxBytes, rawRxBytes);
                    entry.txBytes = multiplySafeByRational(
                            targetTxBytes, entry.txBytes, rawTxBytes);
                    // We purposefully clear out packet counters to indicate
                    // that this data has been augmented.
                    entry.rxPackets = 0;
                    entry.txPackets = 0;
                    combined.setValues(i, entry);
                }
            }

            final long deltaTotal = combined.getTotalBytes() - beforeTotal;
            if (deltaTotal != 0) {
                Slog.d(TAG, "Augmented network usage by " + deltaTotal + " bytes");
            }

            // Finally we can slice data as originally requested
            final NetworkStatsHistory sliced = new NetworkStatsHistory(
                    mBucketDuration, bucketEstimate, fields);
            sliced.recordHistory(combined, start, end);
            return sliced;
        } else {
            return combined;
        }
    }

    /**
     * Summarize all {@link NetworkStatsHistory} in this collection which match
     * the requested parameters.
     */
    public NetworkStats getSummary(NetworkTemplate template, long start, long end,
            @NetworkStatsAccess.Level int accessLevel, int callerUid) {
        final long now = System.currentTimeMillis();

        final NetworkStats stats = new NetworkStats(end - start, 24);

        // shortcut when we know stats will be empty
        if (start == end) return stats;

        final NetworkStats.Entry entry = new NetworkStats.Entry();
        NetworkStatsHistory.Entry historyEntry = null;

        for (int i = 0; i < mStats.size(); i++) {
            final Key key = mStats.keyAt(i);
            if (templateMatches(template, key.ident)
                    && NetworkStatsAccess.isAccessibleToUser(key.uid, callerUid, accessLevel)
                    && key.set < NetworkStats.SET_DEBUG_START) {
                final NetworkStatsHistory value = mStats.valueAt(i);
                historyEntry = value.getValues(start, end, now, historyEntry);

                entry.iface = IFACE_ALL;
                entry.uid = key.uid;
                entry.set = key.set;
                entry.tag = key.tag;
                entry.defaultNetwork = key.ident.areAllMembersOnDefaultNetwork() ?
                        DEFAULT_NETWORK_YES : DEFAULT_NETWORK_NO;
                entry.metered = key.ident.isAnyMemberMetered() ? METERED_YES : METERED_NO;
                entry.roaming = key.ident.isAnyMemberRoaming() ? ROAMING_YES : ROAMING_NO;
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
        for (int i = 0; i < another.mStats.size(); i++) {
            final Key key = another.mStats.keyAt(i);
            final NetworkStatsHistory value = another.mStats.valueAt(i);
            recordHistory(key, value);
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
        final FastDataInput dataIn = new FastDataInput(in, BUFFER_SIZE);
        read(dataIn);
    }

    private void read(DataInput in) throws IOException {
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

    @Override
    public void write(OutputStream out) throws IOException {
        final FastDataOutput dataOut = new FastDataOutput(out, BUFFER_SIZE);
        write(dataOut);
        dataOut.flush();
    }

    private void write(DataOutput out) throws IOException {
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
        return (int) (Math.min(mEndMillis - mStartMillis, WEEK_IN_MILLIS * 5)
                / mBucketDuration);
    }

    private ArrayList<Key> getSortedKeys() {
        final ArrayList<Key> keys = Lists.newArrayList();
        keys.addAll(mStats.keySet());
        Collections.sort(keys);
        return keys;
    }

    public void dump(IndentingPrintWriter pw) {
        for (Key key : getSortedKeys()) {
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

    public void dumpDebug(ProtoOutputStream proto, long tag) {
        final long start = proto.start(tag);

        for (Key key : getSortedKeys()) {
            final long startStats = proto.start(NetworkStatsCollectionProto.STATS);

            // Key
            final long startKey = proto.start(NetworkStatsCollectionStatsProto.KEY);
            key.ident.dumpDebug(proto, NetworkStatsCollectionKeyProto.IDENTITY);
            proto.write(NetworkStatsCollectionKeyProto.UID, key.uid);
            proto.write(NetworkStatsCollectionKeyProto.SET, key.set);
            proto.write(NetworkStatsCollectionKeyProto.TAG, key.tag);
            proto.end(startKey);

            // Value
            final NetworkStatsHistory history = mStats.get(key);
            history.dumpDebug(proto, NetworkStatsCollectionStatsProto.HISTORY);
            proto.end(startStats);
        }

        proto.end(start);
    }

    public void dumpCheckin(PrintWriter pw, long start, long end) {
        dumpCheckin(pw, start, end, NetworkTemplate.buildTemplateMobileWildcard(), "cell");
        dumpCheckin(pw, start, end, NetworkTemplate.buildTemplateWifiWildcard(), "wifi");
        dumpCheckin(pw, start, end, NetworkTemplate.buildTemplateEthernet(), "eth");
        dumpCheckin(pw, start, end, NetworkTemplate.buildTemplateBluetooth(), "bt");
    }

    /**
     * Dump all contained stats that match requested parameters, but group
     * together all matching {@link NetworkTemplate} under a single prefix.
     */
    private void dumpCheckin(PrintWriter pw, long start, long end, NetworkTemplate groupTemplate,
            String groupPrefix) {
        final ArrayMap<Key, NetworkStatsHistory> grouped = new ArrayMap<>();

        // Walk through all history, grouping by matching network templates
        for (int i = 0; i < mStats.size(); i++) {
            final Key key = mStats.keyAt(i);
            final NetworkStatsHistory value = mStats.valueAt(i);

            if (!templateMatches(groupTemplate, key.ident)) continue;
            if (key.set >= NetworkStats.SET_DEBUG_START) continue;

            final Key groupKey = new Key(null, key.uid, key.set, key.tag);
            NetworkStatsHistory groupHistory = grouped.get(groupKey);
            if (groupHistory == null) {
                groupHistory = new NetworkStatsHistory(value.getBucketDuration());
                grouped.put(groupKey, groupHistory);
            }
            groupHistory.recordHistory(value, start, end);
        }

        for (int i = 0; i < grouped.size(); i++) {
            final Key key = grouped.keyAt(i);
            final NetworkStatsHistory value = grouped.valueAt(i);

            if (value.size() == 0) continue;

            pw.print("c,");
            pw.print(groupPrefix); pw.print(',');
            pw.print(key.uid); pw.print(',');
            pw.print(NetworkStats.setToCheckinString(key.set)); pw.print(',');
            pw.print(key.tag);
            pw.println();

            value.dumpCheckin(pw);
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
            hashCode = Objects.hash(ident, uid, set, tag);
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
                        && Objects.equals(ident, key.ident);
            }
            return false;
        }

        @Override
        public int compareTo(Key another) {
            int res = 0;
            if (ident != null && another.ident != null) {
                res = ident.compareTo(another.ident);
            }
            if (res == 0) {
                res = Integer.compare(uid, another.uid);
            }
            if (res == 0) {
                res = Integer.compare(set, another.set);
            }
            if (res == 0) {
                res = Integer.compare(tag, another.tag);
            }
            return res;
        }
    }
}
