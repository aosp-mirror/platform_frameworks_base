/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Objects;

import java.io.CharArrayWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Collection of active network statistics. Can contain summary details across
 * all interfaces, or details with per-UID granularity. Internally stores data
 * as a large table, closely matching {@code /proc/} data format. This structure
 * optimizes for rapid in-memory comparison, but consider using
 * {@link NetworkStatsHistory} when persisting.
 *
 * @hide
 */
public class NetworkStats implements Parcelable {
    /** {@link #iface} value when interface details unavailable. */
    public static final String IFACE_ALL = null;
    /** {@link #uid} value when UID details unavailable. */
    public static final int UID_ALL = -1;
    /** {@link #set} value when all sets combined. */
    public static final int SET_ALL = -1;
    /** {@link #set} value where background data is accounted. */
    public static final int SET_DEFAULT = 0;
    /** {@link #set} value where foreground data is accounted. */
    public static final int SET_FOREGROUND = 1;
    /** {@link #tag} value for total data across all tags. */
    public static final int TAG_NONE = 0;

    // TODO: move fields to "mVariable" notation

    /**
     * {@link SystemClock#elapsedRealtime()} timestamp when this data was
     * generated.
     */
    private final long elapsedRealtime;
    private int size;
    private String[] iface;
    private int[] uid;
    private int[] set;
    private int[] tag;
    private long[] rxBytes;
    private long[] rxPackets;
    private long[] txBytes;
    private long[] txPackets;
    private long[] operations;

    public static class Entry {
        public String iface;
        public int uid;
        public int set;
        public int tag;
        public long rxBytes;
        public long rxPackets;
        public long txBytes;
        public long txPackets;
        public long operations;

        public Entry() {
            this(IFACE_ALL, UID_ALL, SET_DEFAULT, TAG_NONE, 0L, 0L, 0L, 0L, 0L);
        }

        public Entry(long rxBytes, long rxPackets, long txBytes, long txPackets, long operations) {
            this(IFACE_ALL, UID_ALL, SET_DEFAULT, TAG_NONE, rxBytes, rxPackets, txBytes, txPackets,
                    operations);
        }

        public Entry(String iface, int uid, int set, int tag, long rxBytes, long rxPackets,
                long txBytes, long txPackets, long operations) {
            this.iface = iface;
            this.uid = uid;
            this.set = set;
            this.tag = tag;
            this.rxBytes = rxBytes;
            this.rxPackets = rxPackets;
            this.txBytes = txBytes;
            this.txPackets = txPackets;
            this.operations = operations;
        }

        public boolean isNegative() {
            return rxBytes < 0 || rxPackets < 0 || txBytes < 0 || txPackets < 0 || operations < 0;
        }

        public boolean isEmpty() {
            return rxBytes == 0 && rxPackets == 0 && txBytes == 0 && txPackets == 0
                    && operations == 0;
        }

        public void add(Entry another) {
            this.rxBytes += another.rxBytes;
            this.rxPackets += another.rxPackets;
            this.txBytes += another.txBytes;
            this.txPackets += another.txPackets;
            this.operations += another.operations;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            builder.append("iface=").append(iface);
            builder.append(" uid=").append(uid);
            builder.append(" set=").append(setToString(set));
            builder.append(" tag=").append(tagToString(tag));
            builder.append(" rxBytes=").append(rxBytes);
            builder.append(" rxPackets=").append(rxPackets);
            builder.append(" txBytes=").append(txBytes);
            builder.append(" txPackets=").append(txPackets);
            builder.append(" operations=").append(operations);
            return builder.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Entry) {
                final Entry e = (Entry) o;
                return uid == e.uid && set == e.set && tag == e.tag && rxBytes == e.rxBytes
                        && rxPackets == e.rxPackets && txBytes == e.txBytes
                        && txPackets == e.txPackets && operations == e.operations
                        && iface.equals(e.iface);
            }
            return false;
        }
    }

    public NetworkStats(long elapsedRealtime, int initialSize) {
        this.elapsedRealtime = elapsedRealtime;
        this.size = 0;
        this.iface = new String[initialSize];
        this.uid = new int[initialSize];
        this.set = new int[initialSize];
        this.tag = new int[initialSize];
        this.rxBytes = new long[initialSize];
        this.rxPackets = new long[initialSize];
        this.txBytes = new long[initialSize];
        this.txPackets = new long[initialSize];
        this.operations = new long[initialSize];
    }

    public NetworkStats(Parcel parcel) {
        elapsedRealtime = parcel.readLong();
        size = parcel.readInt();
        iface = parcel.createStringArray();
        uid = parcel.createIntArray();
        set = parcel.createIntArray();
        tag = parcel.createIntArray();
        rxBytes = parcel.createLongArray();
        rxPackets = parcel.createLongArray();
        txBytes = parcel.createLongArray();
        txPackets = parcel.createLongArray();
        operations = parcel.createLongArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(elapsedRealtime);
        dest.writeInt(size);
        dest.writeStringArray(iface);
        dest.writeIntArray(uid);
        dest.writeIntArray(set);
        dest.writeIntArray(tag);
        dest.writeLongArray(rxBytes);
        dest.writeLongArray(rxPackets);
        dest.writeLongArray(txBytes);
        dest.writeLongArray(txPackets);
        dest.writeLongArray(operations);
    }

    @Override
    public NetworkStats clone() {
        final NetworkStats clone = new NetworkStats(elapsedRealtime, size);
        NetworkStats.Entry entry = null;
        for (int i = 0; i < size; i++) {
            entry = getValues(i, entry);
            clone.addValues(entry);
        }
        return clone;
    }

    @VisibleForTesting
    public NetworkStats addIfaceValues(
            String iface, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        return addValues(
                iface, UID_ALL, SET_DEFAULT, TAG_NONE, rxBytes, rxPackets, txBytes, txPackets, 0L);
    }

    @VisibleForTesting
    public NetworkStats addValues(String iface, int uid, int set, int tag, long rxBytes,
            long rxPackets, long txBytes, long txPackets, long operations) {
        return addValues(new Entry(
                iface, uid, set, tag, rxBytes, rxPackets, txBytes, txPackets, operations));
    }

    /**
     * Add new stats entry, copying from given {@link Entry}. The {@link Entry}
     * object can be recycled across multiple calls.
     */
    public NetworkStats addValues(Entry entry) {
        if (size >= this.iface.length) {
            final int newLength = Math.max(iface.length, 10) * 3 / 2;
            iface = Arrays.copyOf(iface, newLength);
            uid = Arrays.copyOf(uid, newLength);
            set = Arrays.copyOf(set, newLength);
            tag = Arrays.copyOf(tag, newLength);
            rxBytes = Arrays.copyOf(rxBytes, newLength);
            rxPackets = Arrays.copyOf(rxPackets, newLength);
            txBytes = Arrays.copyOf(txBytes, newLength);
            txPackets = Arrays.copyOf(txPackets, newLength);
            operations = Arrays.copyOf(operations, newLength);
        }

        iface[size] = entry.iface;
        uid[size] = entry.uid;
        set[size] = entry.set;
        tag[size] = entry.tag;
        rxBytes[size] = entry.rxBytes;
        rxPackets[size] = entry.rxPackets;
        txBytes[size] = entry.txBytes;
        txPackets[size] = entry.txPackets;
        operations[size] = entry.operations;
        size++;

        return this;
    }

    /**
     * Return specific stats entry.
     */
    public Entry getValues(int i, Entry recycle) {
        final Entry entry = recycle != null ? recycle : new Entry();
        entry.iface = iface[i];
        entry.uid = uid[i];
        entry.set = set[i];
        entry.tag = tag[i];
        entry.rxBytes = rxBytes[i];
        entry.rxPackets = rxPackets[i];
        entry.txBytes = txBytes[i];
        entry.txPackets = txPackets[i];
        entry.operations = operations[i];
        return entry;
    }

    public long getElapsedRealtime() {
        return elapsedRealtime;
    }

    /**
     * Return age of this {@link NetworkStats} object with respect to
     * {@link SystemClock#elapsedRealtime()}.
     */
    public long getElapsedRealtimeAge() {
        return SystemClock.elapsedRealtime() - elapsedRealtime;
    }

    public int size() {
        return size;
    }

    @VisibleForTesting
    public int internalSize() {
        return iface.length;
    }

    @Deprecated
    public NetworkStats combineValues(String iface, int uid, int tag, long rxBytes, long rxPackets,
            long txBytes, long txPackets, long operations) {
        return combineValues(
                iface, uid, SET_DEFAULT, tag, rxBytes, rxPackets, txBytes, txPackets, operations);
    }

    public NetworkStats combineValues(String iface, int uid, int set, int tag, long rxBytes,
            long rxPackets, long txBytes, long txPackets, long operations) {
        return combineValues(new Entry(
                iface, uid, set, tag, rxBytes, rxPackets, txBytes, txPackets, operations));
    }

    /**
     * Combine given values with an existing row, or create a new row if
     * {@link #findIndex(String, int, int, int)} is unable to find match. Can
     * also be used to subtract values from existing rows.
     */
    public NetworkStats combineValues(Entry entry) {
        final int i = findIndex(entry.iface, entry.uid, entry.set, entry.tag);
        if (i == -1) {
            // only create new entry when positive contribution
            addValues(entry);
        } else {
            rxBytes[i] += entry.rxBytes;
            rxPackets[i] += entry.rxPackets;
            txBytes[i] += entry.txBytes;
            txPackets[i] += entry.txPackets;
            operations[i] += entry.operations;
        }
        return this;
    }

    /**
     * Combine all values from another {@link NetworkStats} into this object.
     */
    public void combineAllValues(NetworkStats another) {
        NetworkStats.Entry entry = null;
        for (int i = 0; i < another.size; i++) {
            entry = another.getValues(i, entry);
            combineValues(entry);
        }
    }

    /**
     * Find first stats index that matches the requested parameters.
     */
    public int findIndex(String iface, int uid, int set, int tag) {
        for (int i = 0; i < size; i++) {
            if (uid == this.uid[i] && set == this.set[i] && tag == this.tag[i]
                    && Objects.equal(iface, this.iface[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find first stats index that matches the requested parameters, starting
     * search around the hinted index as an optimization.
     */
    @VisibleForTesting
    public int findIndexHinted(String iface, int uid, int set, int tag, int hintIndex) {
        for (int offset = 0; offset < size; offset++) {
            final int halfOffset = offset / 2;

            // search outwards from hint index, alternating forward and backward
            final int i;
            if (offset % 2 == 0) {
                i = (hintIndex + halfOffset) % size;
            } else {
                i = (size + hintIndex - halfOffset - 1) % size;
            }

            if (uid == this.uid[i] && set == this.set[i] && tag == this.tag[i]
                    && Objects.equal(iface, this.iface[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Splice in {@link #operations} from the given {@link NetworkStats} based
     * on matching {@link #uid} and {@link #tag} rows. Ignores {@link #iface},
     * since operation counts are at data layer.
     */
    public void spliceOperationsFrom(NetworkStats stats) {
        for (int i = 0; i < size; i++) {
            final int j = stats.findIndex(iface[i], uid[i], set[i], tag[i]);
            if (j == -1) {
                operations[i] = 0;
            } else {
                operations[i] = stats.operations[j];
            }
        }
    }

    /**
     * Return list of unique interfaces known by this data structure.
     */
    public String[] getUniqueIfaces() {
        final HashSet<String> ifaces = new HashSet<String>();
        for (String iface : this.iface) {
            if (iface != IFACE_ALL) {
                ifaces.add(iface);
            }
        }
        return ifaces.toArray(new String[ifaces.size()]);
    }

    /**
     * Return list of unique UIDs known by this data structure.
     */
    public int[] getUniqueUids() {
        final SparseBooleanArray uids = new SparseBooleanArray();
        for (int uid : this.uid) {
            uids.put(uid, true);
        }

        final int size = uids.size();
        final int[] result = new int[size];
        for (int i = 0; i < size; i++) {
            result[i] = uids.keyAt(i);
        }
        return result;
    }

    /**
     * Return total bytes represented by this snapshot object, usually used when
     * checking if a {@link #subtract(NetworkStats)} delta passes a threshold.
     */
    public long getTotalBytes() {
        final Entry entry = getTotal(null);
        return entry.rxBytes + entry.txBytes;
    }

    /**
     * Return total of all fields represented by this snapshot object.
     */
    public Entry getTotal(Entry recycle) {
        return getTotal(recycle, null, UID_ALL, false);
    }

    /**
     * Return total of all fields represented by this snapshot object matching
     * the requested {@link #uid}.
     */
    public Entry getTotal(Entry recycle, int limitUid) {
        return getTotal(recycle, null, limitUid, false);
    }

    /**
     * Return total of all fields represented by this snapshot object matching
     * the requested {@link #iface}.
     */
    public Entry getTotal(Entry recycle, HashSet<String> limitIface) {
        return getTotal(recycle, limitIface, UID_ALL, false);
    }

    public Entry getTotalIncludingTags(Entry recycle) {
        return getTotal(recycle, null, UID_ALL, true);
    }

    /**
     * Return total of all fields represented by this snapshot object matching
     * the requested {@link #iface} and {@link #uid}.
     *
     * @param limitIface Set of {@link #iface} to include in total; or {@code
     *            null} to include all ifaces.
     */
    private Entry getTotal(
            Entry recycle, HashSet<String> limitIface, int limitUid, boolean includeTags) {
        final Entry entry = recycle != null ? recycle : new Entry();

        entry.iface = IFACE_ALL;
        entry.uid = limitUid;
        entry.set = SET_ALL;
        entry.tag = TAG_NONE;
        entry.rxBytes = 0;
        entry.rxPackets = 0;
        entry.txBytes = 0;
        entry.txPackets = 0;
        entry.operations = 0;

        for (int i = 0; i < size; i++) {
            final boolean matchesUid = (limitUid == UID_ALL) || (limitUid == uid[i]);
            final boolean matchesIface = (limitIface == null) || (limitIface.contains(iface[i]));

            if (matchesUid && matchesIface) {
                // skip specific tags, since already counted in TAG_NONE
                if (tag[i] != TAG_NONE && !includeTags) continue;

                entry.rxBytes += rxBytes[i];
                entry.rxPackets += rxPackets[i];
                entry.txBytes += txBytes[i];
                entry.txPackets += txPackets[i];
                entry.operations += operations[i];
            }
        }
        return entry;
    }

    /**
     * Subtract the given {@link NetworkStats}, effectively leaving the delta
     * between two snapshots in time. Assumes that statistics rows collect over
     * time, and that none of them have disappeared.
     */
    public NetworkStats subtract(NetworkStats right) {
        return subtract(this, right, null, null);
    }

    /**
     * Subtract the two given {@link NetworkStats} objects, returning the delta
     * between two snapshots in time. Assumes that statistics rows collect over
     * time, and that none of them have disappeared.
     * <p>
     * If counters have rolled backwards, they are clamped to {@code 0} and
     * reported to the given {@link NonMonotonicObserver}.
     */
    public static <C> NetworkStats subtract(
            NetworkStats left, NetworkStats right, NonMonotonicObserver<C> observer, C cookie) {
        long deltaRealtime = left.elapsedRealtime - right.elapsedRealtime;
        if (deltaRealtime < 0) {
            if (observer != null) {
                observer.foundNonMonotonic(left, -1, right, -1, cookie);
            }
            deltaRealtime = 0;
        }

        // result will have our rows, and elapsed time between snapshots
        final Entry entry = new Entry();
        final NetworkStats result = new NetworkStats(deltaRealtime, left.size);
        for (int i = 0; i < left.size; i++) {
            entry.iface = left.iface[i];
            entry.uid = left.uid[i];
            entry.set = left.set[i];
            entry.tag = left.tag[i];

            // find remote row that matches, and subtract
            final int j = right.findIndexHinted(entry.iface, entry.uid, entry.set, entry.tag, i);
            if (j == -1) {
                // newly appearing row, return entire value
                entry.rxBytes = left.rxBytes[i];
                entry.rxPackets = left.rxPackets[i];
                entry.txBytes = left.txBytes[i];
                entry.txPackets = left.txPackets[i];
                entry.operations = left.operations[i];
            } else {
                // existing row, subtract remote value
                entry.rxBytes = left.rxBytes[i] - right.rxBytes[j];
                entry.rxPackets = left.rxPackets[i] - right.rxPackets[j];
                entry.txBytes = left.txBytes[i] - right.txBytes[j];
                entry.txPackets = left.txPackets[i] - right.txPackets[j];
                entry.operations = left.operations[i] - right.operations[j];

                if (entry.rxBytes < 0 || entry.rxPackets < 0 || entry.txBytes < 0
                        || entry.txPackets < 0 || entry.operations < 0) {
                    if (observer != null) {
                        observer.foundNonMonotonic(left, i, right, j, cookie);
                    }
                    entry.rxBytes = Math.max(entry.rxBytes, 0);
                    entry.rxPackets = Math.max(entry.rxPackets, 0);
                    entry.txBytes = Math.max(entry.txBytes, 0);
                    entry.txPackets = Math.max(entry.txPackets, 0);
                    entry.operations = Math.max(entry.operations, 0);
                }
            }

            result.addValues(entry);
        }

        return result;
    }

    /**
     * Return total statistics grouped by {@link #iface}; doesn't mutate the
     * original structure.
     */
    public NetworkStats groupedByIface() {
        final NetworkStats stats = new NetworkStats(elapsedRealtime, 10);

        final Entry entry = new Entry();
        entry.uid = UID_ALL;
        entry.set = SET_ALL;
        entry.tag = TAG_NONE;
        entry.operations = 0L;

        for (int i = 0; i < size; i++) {
            // skip specific tags, since already counted in TAG_NONE
            if (tag[i] != TAG_NONE) continue;

            entry.iface = iface[i];
            entry.rxBytes = rxBytes[i];
            entry.rxPackets = rxPackets[i];
            entry.txBytes = txBytes[i];
            entry.txPackets = txPackets[i];
            stats.combineValues(entry);
        }

        return stats;
    }

    /**
     * Return total statistics grouped by {@link #uid}; doesn't mutate the
     * original structure.
     */
    public NetworkStats groupedByUid() {
        final NetworkStats stats = new NetworkStats(elapsedRealtime, 10);

        final Entry entry = new Entry();
        entry.iface = IFACE_ALL;
        entry.set = SET_ALL;
        entry.tag = TAG_NONE;

        for (int i = 0; i < size; i++) {
            // skip specific tags, since already counted in TAG_NONE
            if (tag[i] != TAG_NONE) continue;

            entry.uid = uid[i];
            entry.rxBytes = rxBytes[i];
            entry.rxPackets = rxPackets[i];
            entry.txBytes = txBytes[i];
            entry.txPackets = txPackets[i];
            entry.operations = operations[i];
            stats.combineValues(entry);
        }

        return stats;
    }

    /**
     * Return all rows except those attributed to the requested UID; doesn't
     * mutate the original structure.
     */
    public NetworkStats withoutUids(int[] uids) {
        final NetworkStats stats = new NetworkStats(elapsedRealtime, 10);

        Entry entry = new Entry();
        for (int i = 0; i < size; i++) {
            entry = getValues(i, entry);
            if (!ArrayUtils.contains(uids, entry.uid)) {
                stats.addValues(entry);
            }
        }

        return stats;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("NetworkStats: elapsedRealtime="); pw.println(elapsedRealtime);
        for (int i = 0; i < size; i++) {
            pw.print(prefix);
            pw.print("  ["); pw.print(i); pw.print("]");
            pw.print(" iface="); pw.print(iface[i]);
            pw.print(" uid="); pw.print(uid[i]);
            pw.print(" set="); pw.print(setToString(set[i]));
            pw.print(" tag="); pw.print(tagToString(tag[i]));
            pw.print(" rxBytes="); pw.print(rxBytes[i]);
            pw.print(" rxPackets="); pw.print(rxPackets[i]);
            pw.print(" txBytes="); pw.print(txBytes[i]);
            pw.print(" txPackets="); pw.print(txPackets[i]);
            pw.print(" operations="); pw.println(operations[i]);
        }
    }

    /**
     * Return text description of {@link #set} value.
     */
    public static String setToString(int set) {
        switch (set) {
            case SET_ALL:
                return "ALL";
            case SET_DEFAULT:
                return "DEFAULT";
            case SET_FOREGROUND:
                return "FOREGROUND";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Return text description of {@link #tag} value.
     */
    public static String tagToString(int tag) {
        return "0x" + Integer.toHexString(tag);
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump("", new PrintWriter(writer));
        return writer.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NetworkStats> CREATOR = new Creator<NetworkStats>() {
        @Override
        public NetworkStats createFromParcel(Parcel in) {
            return new NetworkStats(in);
        }

        @Override
        public NetworkStats[] newArray(int size) {
            return new NetworkStats[size];
        }
    };

    public interface NonMonotonicObserver<C> {
        public void foundNonMonotonic(
                NetworkStats left, int leftIndex, NetworkStats right, int rightIndex, C cookie);
    }
}
