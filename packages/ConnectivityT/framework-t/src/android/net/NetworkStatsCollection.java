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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
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

import static com.android.net.module.util.NetworkStatsUtils.multiplySafeByRational;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.NetworkStats.State;
import android.net.NetworkStatsHistory.Entry;
import android.os.Binder;
import android.service.NetworkStatsCollectionKeyProto;
import android.service.NetworkStatsCollectionProto;
import android.service.NetworkStatsCollectionStatsProto;
import android.telephony.SubscriptionPlan;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Range;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FileRotator;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.NetworkStatsUtils;

import libcore.io.IoUtils;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Collection of {@link NetworkStatsHistory}, stored based on combined key of
 * {@link NetworkIdentitySet}, UID, set, and tag. Knows how to persist itself.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public class NetworkStatsCollection implements FileRotator.Reader, FileRotator.Writer {
    private static final String TAG = NetworkStatsCollection.class.getSimpleName();
    /** File header magic number: "ANET" */
    private static final int FILE_MAGIC = 0x414E4554;

    private static final int VERSION_NETWORK_INIT = 1;

    private static final int VERSION_UID_INIT = 1;
    private static final int VERSION_UID_WITH_IDENT = 2;
    private static final int VERSION_UID_WITH_TAG = 3;
    private static final int VERSION_UID_WITH_SET = 4;

    private static final int VERSION_UNIFIED_INIT = 16;

    private ArrayMap<Key, NetworkStatsHistory> mStats = new ArrayMap<>();

    private final long mBucketDurationMillis;

    private long mStartMillis;
    private long mEndMillis;
    private long mTotalBytes;
    private boolean mDirty;

    /**
     * Construct a {@link NetworkStatsCollection} object.
     *
     * @param bucketDuration duration of the buckets in this object, in milliseconds.
     * @hide
     */
    public NetworkStatsCollection(long bucketDurationMillis) {
        mBucketDurationMillis = bucketDurationMillis;
        reset();
    }

    /** @hide */
    public void clear() {
        reset();
    }

    /** @hide */
    public void reset() {
        mStats.clear();
        mStartMillis = Long.MAX_VALUE;
        mEndMillis = Long.MIN_VALUE;
        mTotalBytes = 0;
        mDirty = false;
    }

    /** @hide */
    public long getStartMillis() {
        return mStartMillis;
    }

    /**
     * Return first atomic bucket in this collection, which is more conservative
     * than {@link #mStartMillis}.
     * @hide
     */
    public long getFirstAtomicBucketMillis() {
        if (mStartMillis == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        } else {
            return mStartMillis + mBucketDurationMillis;
        }
    }

    /** @hide */
    public long getEndMillis() {
        return mEndMillis;
    }

    /** @hide */
    public long getTotalBytes() {
        return mTotalBytes;
    }

    /** @hide */
    public boolean isDirty() {
        return mDirty;
    }

    /** @hide */
    public void clearDirty() {
        mDirty = false;
    }

    /** @hide */
    public boolean isEmpty() {
        return mStartMillis == Long.MAX_VALUE && mEndMillis == Long.MIN_VALUE;
    }

    /** @hide */
    @VisibleForTesting
    public long roundUp(long time) {
        if (time == Long.MIN_VALUE || time == Long.MAX_VALUE
                || time == SubscriptionPlan.TIME_UNKNOWN) {
            return time;
        } else {
            final long mod = time % mBucketDurationMillis;
            if (mod > 0) {
                time -= mod;
                time += mBucketDurationMillis;
            }
            return time;
        }
    }

    /** @hide */
    @VisibleForTesting
    public long roundDown(long time) {
        if (time == Long.MIN_VALUE || time == Long.MAX_VALUE
                || time == SubscriptionPlan.TIME_UNKNOWN) {
            return time;
        } else {
            final long mod = time % mBucketDurationMillis;
            if (mod > 0) {
                time -= mod;
            }
            return time;
        }
    }

    /** @hide */
    public int[] getRelevantUids(@NetworkStatsAccess.Level int accessLevel) {
        return getRelevantUids(accessLevel, Binder.getCallingUid());
    }

    /** @hide */
    public int[] getRelevantUids(@NetworkStatsAccess.Level int accessLevel,
                final int callerUid) {
        final ArrayList<Integer> uids = new ArrayList<>();
        for (int i = 0; i < mStats.size(); i++) {
            final Key key = mStats.keyAt(i);
            if (NetworkStatsAccess.isAccessibleToUser(key.uid, callerUid, accessLevel)) {
                int j = Collections.binarySearch(uids, new Integer(key.uid));

                if (j < 0) {
                    j = ~j;
                    uids.add(j, key.uid);
                }
            }
        }
        return CollectionUtils.toIntArray(uids);
    }

    /**
     * Combine all {@link NetworkStatsHistory} in this collection which match
     * the requested parameters.
     * @hide
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
        final int bucketEstimate = (int) NetworkStatsUtils.constrain(
                ((end - start) / mBucketDurationMillis), 0,
                (180 * DateUtils.DAY_IN_MILLIS) / mBucketDurationMillis);
        final NetworkStatsHistory combined = new NetworkStatsHistory(
                mBucketDurationMillis, bucketEstimate, fields);

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
                Log.d(TAG, "Augmented network usage by " + deltaTotal + " bytes");
            }

            // Finally we can slice data as originally requested
            final NetworkStatsHistory sliced = new NetworkStatsHistory(
                    mBucketDurationMillis, bucketEstimate, fields);
            sliced.recordHistory(combined, start, end);
            return sliced;
        } else {
            return combined;
        }
    }

    /**
     * Summarize all {@link NetworkStatsHistory} in this collection which match
     * the requested parameters across the requested range.
     *
     * @param template - a predicate for filtering netstats.
     * @param start - start of the range, timestamp in milliseconds since the epoch.
     * @param end - end of the range, timestamp in milliseconds since the epoch.
     * @param accessLevel - caller access level.
     * @param callerUid - caller UID.
     * @hide
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
                entry.defaultNetwork = key.ident.areAllMembersOnDefaultNetwork()
                        ? DEFAULT_NETWORK_YES : DEFAULT_NETWORK_NO;
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
     * @hide
     */
    public void recordData(NetworkIdentitySet ident, int uid, int set, int tag, long start,
            long end, NetworkStats.Entry entry) {
        final NetworkStatsHistory history = findOrCreateHistory(ident, uid, set, tag);
        history.recordData(start, end, entry);
        noteRecordedHistory(history.getStart(), history.getEnd(), entry.rxBytes + entry.txBytes);
    }

    /**
     * Record given {@link NetworkStatsHistory} into this collection.
     *
     * @hide
     */
    public void recordHistory(@NonNull Key key, @NonNull NetworkStatsHistory history) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(history);
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
     *
     * @hide
     */
    public void recordCollection(@NonNull NetworkStatsCollection another) {
        Objects.requireNonNull(another);
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
            updated = new NetworkStatsHistory(mBucketDurationMillis, 10);
        } else if (existing.getBucketDuration() != mBucketDurationMillis) {
            updated = new NetworkStatsHistory(existing, mBucketDurationMillis);
        }

        if (updated != null) {
            mStats.put(key, updated);
            return updated;
        } else {
            return existing;
        }
    }

    /** @hide */
    @Override
    public void read(InputStream in) throws IOException {
        read((DataInput) new DataInputStream(in));
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

    /** @hide */
    @Override
    public void write(OutputStream out) throws IOException {
        write((DataOutput) new DataOutputStream(out));
        out.flush();
    }

    private void write(DataOutput out) throws IOException {
        // cluster key lists grouped by ident
        final HashMap<NetworkIdentitySet, ArrayList<Key>> keysByIdent = new HashMap<>();
        for (Key key : mStats.keySet()) {
            ArrayList<Key> keys = keysByIdent.get(key.ident);
            if (keys == null) {
                keys = new ArrayList<>();
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

    /**
     * Read legacy network summary statistics file format into the collection,
     * See {@code NetworkStatsService#maybeUpgradeLegacyStatsLocked}.
     *
     * @deprecated
     * @hide
     */
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

    /**
     * Read legacy Uid statistics file format into the collection,
     * See {@code NetworkStatsService#maybeUpgradeLegacyStatsLocked}.
     *
     * @deprecated
     * @hide
     */
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
     * @hide
     */
    public void removeUids(int[] uids) {
        final ArrayList<Key> knownKeys = new ArrayList<>();
        knownKeys.addAll(mStats.keySet());

        // migrate all UID stats into special "removed" bucket
        for (Key key : knownKeys) {
            if (CollectionUtils.contains(uids, key.uid)) {
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
                / mBucketDurationMillis);
    }

    private ArrayList<Key> getSortedKeys() {
        final ArrayList<Key> keys = new ArrayList<>();
        keys.addAll(mStats.keySet());
        Collections.sort(keys, (left, right) -> Key.compare(left, right));
        return keys;
    }

    /** @hide */
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

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long tag) {
        final long start = proto.start(tag);

        for (Key key : getSortedKeys()) {
            final long startStats = proto.start(NetworkStatsCollectionProto.STATS_FIELD_NUMBER);

            // Key
            final long startKey = proto.start(NetworkStatsCollectionStatsProto.KEY_FIELD_NUMBER);
            key.ident.dumpDebug(proto, NetworkStatsCollectionKeyProto.IDENTITY_FIELD_NUMBER);
            proto.write(NetworkStatsCollectionKeyProto.UID_FIELD_NUMBER, key.uid);
            proto.write(NetworkStatsCollectionKeyProto.SET_FIELD_NUMBER, key.set);
            proto.write(NetworkStatsCollectionKeyProto.TAG_FIELD_NUMBER, key.tag);
            proto.end(startKey);

            // Value
            final NetworkStatsHistory history = mStats.get(key);
            history.dumpDebug(proto, NetworkStatsCollectionStatsProto.HISTORY_FIELD_NUMBER);
            proto.end(startStats);
        }

        proto.end(start);
    }

    /** @hide */
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

    /**
     * Get the all historical stats of the collection {@link NetworkStatsCollection}.
     *
     * @return All {@link NetworkStatsHistory} in this collection.
     */
    @NonNull
    public Map<Key, NetworkStatsHistory> getEntries() {
        return new ArrayMap(mStats);
    }

    /**
     * Builder class for {@link NetworkStatsCollection}.
     */
    public static final class Builder {
        private final long mBucketDurationMillis;
        private final ArrayMap<Key, NetworkStatsHistory> mEntries = new ArrayMap<>();

        /**
         * Creates a new Builder with given bucket duration.
         *
         * @param bucketDuration Duration of the buckets of the object, in milliseconds.
         */
        public Builder(long bucketDurationMillis) {
            mBucketDurationMillis = bucketDurationMillis;
        }

        /**
         * Add association of the history with the specified key in this map.
         *
         * @param key The object used to identify a network, see {@link Key}.
         * @param history {@link NetworkStatsHistory} instance associated to the given {@link Key}.
         * @return The builder object.
         */
        @NonNull
        public NetworkStatsCollection.Builder addEntry(@NonNull Key key,
                @NonNull NetworkStatsHistory history) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(history);
            final List<Entry> historyEntries = history.getEntries();

            final NetworkStatsHistory.Builder historyBuilder =
                    new NetworkStatsHistory.Builder(mBucketDurationMillis, historyEntries.size());
            for (Entry entry : historyEntries) {
                historyBuilder.addEntry(entry);
            }

            mEntries.put(key, historyBuilder.build());
            return this;
        }

        /**
         * Builds the instance of the {@link NetworkStatsCollection}.
         *
         * @return the built instance of {@link NetworkStatsCollection}.
         */
        @NonNull
        public NetworkStatsCollection build() {
            final NetworkStatsCollection collection =
                    new NetworkStatsCollection(mBucketDurationMillis);
            for (int i = 0; i < mEntries.size(); i++) {
                collection.recordHistory(mEntries.keyAt(i), mEntries.valueAt(i));
            }
            return collection;
        }
    }

    /**
     * the identifier that associate with the {@link NetworkStatsHistory} object to identify
     * a certain record in the {@link NetworkStatsCollection} object.
     */
    public static final class Key {
        /** @hide */
        public final NetworkIdentitySet ident;
        /** @hide */
        public final int uid;
        /** @hide */
        public final int set;
        /** @hide */
        public final int tag;

        private final int mHashCode;

        /**
         * Construct a {@link Key} object.
         *
         * @param ident a Set of {@link NetworkIdentity} that associated with the record.
         * @param uid Uid of the record.
         * @param set Set of the record, see {@code NetworkStats#SET_*}.
         * @param tag Tag of the record, see {@link TrafficStats#setThreadStatsTag(int)}.
         */
        public Key(@NonNull Set<NetworkIdentity> ident, int uid, @State int set, int tag) {
            this(new NetworkIdentitySet(Objects.requireNonNull(ident)), uid, set, tag);
        }

        /** @hide */
        public Key(@NonNull NetworkIdentitySet ident, int uid, int set, int tag) {
            this.ident = Objects.requireNonNull(ident);
            this.uid = uid;
            this.set = set;
            this.tag = tag;
            mHashCode = Objects.hash(ident, uid, set, tag);
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof Key) {
                final Key key = (Key) obj;
                return uid == key.uid && set == key.set && tag == key.tag
                        && Objects.equals(ident, key.ident);
            }
            return false;
        }

        /** @hide */
        public static int compare(@NonNull Key left, @NonNull Key right) {
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
            int res = 0;
            if (left.ident != null && right.ident != null) {
                res = NetworkIdentitySet.compare(left.ident, right.ident);
            }
            if (res == 0) {
                res = Integer.compare(left.uid, right.uid);
            }
            if (res == 0) {
                res = Integer.compare(left.set, right.set);
            }
            if (res == 0) {
                res = Integer.compare(left.tag, right.tag);
            }
            return res;
        }
    }
}
