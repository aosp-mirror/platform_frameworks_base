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
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import libcore.util.EmptyArray;

import java.io.CharArrayWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

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
    private static final String TAG = "NetworkStats";
    /** {@link #iface} value when interface details unavailable. */
    public static final String IFACE_ALL = null;
    /** {@link #uid} value when UID details unavailable. */
    public static final int UID_ALL = -1;
    /** {@link #tag} value matching any tag. */
    // TODO: Rename TAG_ALL to TAG_ANY.
    public static final int TAG_ALL = -1;
    /** {@link #set} value for all sets combined, not including debug sets. */
    public static final int SET_ALL = -1;
    /** {@link #set} value where background data is accounted. */
    public static final int SET_DEFAULT = 0;
    /** {@link #set} value where foreground data is accounted. */
    public static final int SET_FOREGROUND = 1;
    /** All {@link #set} value greater than SET_DEBUG_START are debug {@link #set} values. */
    public static final int SET_DEBUG_START = 1000;
    /** Debug {@link #set} value when the VPN stats are moved in. */
    public static final int SET_DBG_VPN_IN = 1001;
    /** Debug {@link #set} value when the VPN stats are moved out of a vpn UID. */
    public static final int SET_DBG_VPN_OUT = 1002;

    /** Include all interfaces when filtering */
    public static final String[] INTERFACES_ALL = null;

    /** {@link #tag} value for total data across all tags. */
    // TODO: Rename TAG_NONE to TAG_ALL.
    public static final int TAG_NONE = 0;

    /** {@link #metered} value to account for all metered states. */
    public static final int METERED_ALL = -1;
    /** {@link #metered} value where native, unmetered data is accounted. */
    public static final int METERED_NO = 0;
    /** {@link #metered} value where metered data is accounted. */
    public static final int METERED_YES = 1;

    /** {@link #roaming} value to account for all roaming states. */
    public static final int ROAMING_ALL = -1;
    /** {@link #roaming} value where native, non-roaming data is accounted. */
    public static final int ROAMING_NO = 0;
    /** {@link #roaming} value where roaming data is accounted. */
    public static final int ROAMING_YES = 1;

    /** {@link #onDefaultNetwork} value to account for all default network states. */
    public static final int DEFAULT_NETWORK_ALL = -1;
    /** {@link #onDefaultNetwork} value to account for usage while not the default network. */
    public static final int DEFAULT_NETWORK_NO = 0;
    /** {@link #onDefaultNetwork} value to account for usage while the default network. */
    public static final int DEFAULT_NETWORK_YES = 1;

    /** Denotes a request for stats at the interface level. */
    public static final int STATS_PER_IFACE = 0;
    /** Denotes a request for stats at the interface and UID level. */
    public static final int STATS_PER_UID = 1;

    private static final String CLATD_INTERFACE_PREFIX = "v4-";
    // Delta between IPv4 header (20b) and IPv6 header (40b).
    // Used for correct stats accounting on clatd interfaces.
    private static final int IPV4V6_HEADER_DELTA = 20;

    // TODO: move fields to "mVariable" notation

    /**
     * {@link SystemClock#elapsedRealtime()} timestamp when this data was
     * generated.
     */
    private long elapsedRealtime;
    private int size;
    private int capacity;
    private String[] iface;
    private int[] uid;
    private int[] set;
    private int[] tag;
    private int[] metered;
    private int[] roaming;
    private int[] defaultNetwork;
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
        /**
         * Note that this is only populated w/ the default value when read from /proc or written
         * to disk. We merge in the correct value when reporting this value to clients of
         * getSummary().
         */
        public int metered;
        /**
         * Note that this is only populated w/ the default value when read from /proc or written
         * to disk. We merge in the correct value when reporting this value to clients of
         * getSummary().
         */
        public int roaming;
        /**
         * Note that this is only populated w/ the default value when read from /proc or written
         * to disk. We merge in the correct value when reporting this value to clients of
         * getSummary().
         */
        public int defaultNetwork;
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
            this(iface, uid, set, tag, METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO,
                    rxBytes, rxPackets, txBytes, txPackets, operations);
        }

        public Entry(String iface, int uid, int set, int tag, int metered, int roaming,
                 int defaultNetwork, long rxBytes, long rxPackets, long txBytes, long txPackets,
                 long operations) {
            this.iface = iface;
            this.uid = uid;
            this.set = set;
            this.tag = tag;
            this.metered = metered;
            this.roaming = roaming;
            this.defaultNetwork = defaultNetwork;
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
            builder.append(" metered=").append(meteredToString(metered));
            builder.append(" roaming=").append(roamingToString(roaming));
            builder.append(" defaultNetwork=").append(defaultNetworkToString(defaultNetwork));
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
                return uid == e.uid && set == e.set && tag == e.tag && metered == e.metered
                        && roaming == e.roaming && defaultNetwork == e.defaultNetwork
                        && rxBytes == e.rxBytes && rxPackets == e.rxPackets
                        && txBytes == e.txBytes && txPackets == e.txPackets
                        && operations == e.operations && iface.equals(e.iface);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(uid, set, tag, metered, roaming, defaultNetwork, iface);
        }
    }

    public NetworkStats(long elapsedRealtime, int initialSize) {
        this.elapsedRealtime = elapsedRealtime;
        this.size = 0;
        if (initialSize > 0) {
            this.capacity = initialSize;
            this.iface = new String[initialSize];
            this.uid = new int[initialSize];
            this.set = new int[initialSize];
            this.tag = new int[initialSize];
            this.metered = new int[initialSize];
            this.roaming = new int[initialSize];
            this.defaultNetwork = new int[initialSize];
            this.rxBytes = new long[initialSize];
            this.rxPackets = new long[initialSize];
            this.txBytes = new long[initialSize];
            this.txPackets = new long[initialSize];
            this.operations = new long[initialSize];
        } else {
            // Special case for use by NetworkStatsFactory to start out *really* empty.
            clear();
        }
    }

    public NetworkStats(Parcel parcel) {
        elapsedRealtime = parcel.readLong();
        size = parcel.readInt();
        capacity = parcel.readInt();
        iface = parcel.createStringArray();
        uid = parcel.createIntArray();
        set = parcel.createIntArray();
        tag = parcel.createIntArray();
        metered = parcel.createIntArray();
        roaming = parcel.createIntArray();
        defaultNetwork = parcel.createIntArray();
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
        dest.writeInt(capacity);
        dest.writeStringArray(iface);
        dest.writeIntArray(uid);
        dest.writeIntArray(set);
        dest.writeIntArray(tag);
        dest.writeIntArray(metered);
        dest.writeIntArray(roaming);
        dest.writeIntArray(defaultNetwork);
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

    /**
     * Clear all data stored in this object.
     */
    public void clear() {
        this.capacity = 0;
        this.iface = EmptyArray.STRING;
        this.uid = EmptyArray.INT;
        this.set = EmptyArray.INT;
        this.tag = EmptyArray.INT;
        this.metered = EmptyArray.INT;
        this.roaming = EmptyArray.INT;
        this.defaultNetwork = EmptyArray.INT;
        this.rxBytes = EmptyArray.LONG;
        this.rxPackets = EmptyArray.LONG;
        this.txBytes = EmptyArray.LONG;
        this.txPackets = EmptyArray.LONG;
        this.operations = EmptyArray.LONG;
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

    @VisibleForTesting
    public NetworkStats addValues(String iface, int uid, int set, int tag, int metered, int roaming,
            int defaultNetwork, long rxBytes, long rxPackets, long txBytes, long txPackets,
            long operations) {
        return addValues(new Entry(
                iface, uid, set, tag, metered, roaming, defaultNetwork, rxBytes, rxPackets,
                txBytes, txPackets, operations));
    }

    /**
     * Add new stats entry, copying from given {@link Entry}. The {@link Entry}
     * object can be recycled across multiple calls.
     */
    public NetworkStats addValues(Entry entry) {
        if (size >= capacity) {
            final int newLength = Math.max(size, 10) * 3 / 2;
            iface = Arrays.copyOf(iface, newLength);
            uid = Arrays.copyOf(uid, newLength);
            set = Arrays.copyOf(set, newLength);
            tag = Arrays.copyOf(tag, newLength);
            metered = Arrays.copyOf(metered, newLength);
            roaming = Arrays.copyOf(roaming, newLength);
            defaultNetwork = Arrays.copyOf(defaultNetwork, newLength);
            rxBytes = Arrays.copyOf(rxBytes, newLength);
            rxPackets = Arrays.copyOf(rxPackets, newLength);
            txBytes = Arrays.copyOf(txBytes, newLength);
            txPackets = Arrays.copyOf(txPackets, newLength);
            operations = Arrays.copyOf(operations, newLength);
            capacity = newLength;
        }

        setValues(size, entry);
        size++;

        return this;
    }

    private void setValues(int i, Entry entry) {
        iface[i] = entry.iface;
        uid[i] = entry.uid;
        set[i] = entry.set;
        tag[i] = entry.tag;
        metered[i] = entry.metered;
        roaming[i] = entry.roaming;
        defaultNetwork[i] = entry.defaultNetwork;
        rxBytes[i] = entry.rxBytes;
        rxPackets[i] = entry.rxPackets;
        txBytes[i] = entry.txBytes;
        txPackets[i] = entry.txPackets;
        operations[i] = entry.operations;
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
        entry.metered = metered[i];
        entry.roaming = roaming[i];
        entry.defaultNetwork = defaultNetwork[i];
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

    public void setElapsedRealtime(long time) {
        elapsedRealtime = time;
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
        return capacity;
    }

    @Deprecated
    public NetworkStats combineValues(String iface, int uid, int tag, long rxBytes, long rxPackets,
            long txBytes, long txPackets, long operations) {
        return combineValues(
                iface, uid, SET_DEFAULT, tag, rxBytes, rxPackets, txBytes,
                txPackets, operations);
    }

    public NetworkStats combineValues(String iface, int uid, int set, int tag,
            long rxBytes, long rxPackets, long txBytes, long txPackets, long operations) {
        return combineValues(new Entry(
                iface, uid, set, tag, rxBytes, rxPackets, txBytes, txPackets, operations));
    }

    /**
     * Combine given values with an existing row, or create a new row if
     * {@link #findIndex(String, int, int, int, int)} is unable to find match. Can
     * also be used to subtract values from existing rows.
     */
    public NetworkStats combineValues(Entry entry) {
        final int i = findIndex(entry.iface, entry.uid, entry.set, entry.tag, entry.metered,
                entry.roaming, entry.defaultNetwork);
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
    public int findIndex(String iface, int uid, int set, int tag, int metered, int roaming,
            int defaultNetwork) {
        for (int i = 0; i < size; i++) {
            if (uid == this.uid[i] && set == this.set[i] && tag == this.tag[i]
                    && metered == this.metered[i] && roaming == this.roaming[i]
                    && defaultNetwork == this.defaultNetwork[i]
                    && Objects.equals(iface, this.iface[i])) {
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
    public int findIndexHinted(String iface, int uid, int set, int tag, int metered, int roaming,
            int defaultNetwork, int hintIndex) {
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
                    && metered == this.metered[i] && roaming == this.roaming[i]
                    && defaultNetwork == this.defaultNetwork[i]
                    && Objects.equals(iface, this.iface[i])) {
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
            final int j = stats.findIndex(iface[i], uid[i], set[i], tag[i], metered[i], roaming[i],
                    defaultNetwork[i]);
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
        entry.metered = METERED_ALL;
        entry.roaming = ROAMING_ALL;
        entry.defaultNetwork = DEFAULT_NETWORK_ALL;
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
     * Fast path for battery stats.
     */
    public long getTotalPackets() {
        long total = 0;
        for (int i = size-1; i >= 0; i--) {
            total += rxPackets[i] + txPackets[i];
        }
        return total;
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
    public static <C> NetworkStats subtract(NetworkStats left, NetworkStats right,
            NonMonotonicObserver<C> observer, C cookie) {
        return subtract(left, right, observer, cookie, null);
    }

    /**
     * Subtract the two given {@link NetworkStats} objects, returning the delta
     * between two snapshots in time. Assumes that statistics rows collect over
     * time, and that none of them have disappeared.
     * <p>
     * If counters have rolled backwards, they are clamped to {@code 0} and
     * reported to the given {@link NonMonotonicObserver}.
     * <p>
     * If <var>recycle</var> is supplied, this NetworkStats object will be
     * reused (and returned) as the result if it is large enough to contain
     * the data.
     */
    public static <C> NetworkStats subtract(NetworkStats left, NetworkStats right,
            NonMonotonicObserver<C> observer, C cookie, NetworkStats recycle) {
        long deltaRealtime = left.elapsedRealtime - right.elapsedRealtime;
        if (deltaRealtime < 0) {
            if (observer != null) {
                observer.foundNonMonotonic(left, -1, right, -1, cookie);
            }
            deltaRealtime = 0;
        }

        // result will have our rows, and elapsed time between snapshots
        final Entry entry = new Entry();
        final NetworkStats result;
        if (recycle != null && recycle.capacity >= left.size) {
            result = recycle;
            result.size = 0;
            result.elapsedRealtime = deltaRealtime;
        } else {
            result = new NetworkStats(deltaRealtime, left.size);
        }
        for (int i = 0; i < left.size; i++) {
            entry.iface = left.iface[i];
            entry.uid = left.uid[i];
            entry.set = left.set[i];
            entry.tag = left.tag[i];
            entry.metered = left.metered[i];
            entry.roaming = left.roaming[i];
            entry.defaultNetwork = left.defaultNetwork[i];
            entry.rxBytes = left.rxBytes[i];
            entry.rxPackets = left.rxPackets[i];
            entry.txBytes = left.txBytes[i];
            entry.txPackets = left.txPackets[i];
            entry.operations = left.operations[i];

            // find remote row that matches, and subtract
            final int j = right.findIndexHinted(entry.iface, entry.uid, entry.set, entry.tag,
                    entry.metered, entry.roaming, entry.defaultNetwork, i);
            if (j != -1) {
                // Found matching row, subtract remote value.
                entry.rxBytes -= right.rxBytes[j];
                entry.rxPackets -= right.rxPackets[j];
                entry.txBytes -= right.txBytes[j];
                entry.txPackets -= right.txPackets[j];
                entry.operations -= right.operations[j];
            }

            if (entry.isNegative()) {
                if (observer != null) {
                    observer.foundNonMonotonic(left, i, right, j, cookie);
                }
                entry.rxBytes = Math.max(entry.rxBytes, 0);
                entry.rxPackets = Math.max(entry.rxPackets, 0);
                entry.txBytes = Math.max(entry.txBytes, 0);
                entry.txPackets = Math.max(entry.txPackets, 0);
                entry.operations = Math.max(entry.operations, 0);
            }

            result.addValues(entry);
        }

        return result;
    }

    /**
     * Calculate and apply adjustments to captured statistics for 464xlat traffic counted twice.
     *
     * <p>This mutates both base and stacked traffic stats, to account respectively for
     * double-counted traffic and IPv4/IPv6 header size difference.
     *
     * <p>For 464xlat traffic, xt_qtaguid sees every IPv4 packet twice, once as a native IPv4
     * packet on the stacked interface, and once as translated to an IPv6 packet on the
     * base interface. For correct stats accounting on the base interface, every 464xlat
     * packet needs to be subtracted from the root UID on the base interface both for tx
     * and rx traffic (http://b/12249687, http:/b/33681750).
     *
     * As for eBPF, the per uid stats is collected by different hook, the rx packets on base
     * interface will not be counted. Thus, the adjustment on root uid is only needed in tx
     * direction.
     *
     * <p>This method will behave fine if {@code stackedIfaces} is an non-synchronized but add-only
     * {@code ConcurrentHashMap}
     * @param baseTraffic Traffic on the base interfaces. Will be mutated.
     * @param stackedTraffic Stats with traffic stacked on top of our ifaces. Will also be mutated.
     * @param stackedIfaces Mapping ipv6if -> ipv4if interface where traffic is counted on both.
     * @param useBpfStats True if eBPF is in use.
     */
    public static void apply464xlatAdjustments(NetworkStats baseTraffic,
            NetworkStats stackedTraffic, Map<String, String> stackedIfaces, boolean useBpfStats) {
        // Total 464xlat traffic to subtract from uid 0 on all base interfaces.
        // stackedIfaces may grow afterwards, but NetworkStats will just be resized automatically.
        final NetworkStats adjustments = new NetworkStats(0, stackedIfaces.size());

        // For recycling
        Entry entry = null;
        Entry adjust = new NetworkStats.Entry(IFACE_ALL, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L);

        for (int i = 0; i < stackedTraffic.size; i++) {
            entry = stackedTraffic.getValues(i, entry);
            if (entry.iface == null || !entry.iface.startsWith(CLATD_INTERFACE_PREFIX)) {
                continue;
            }
            final String baseIface = stackedIfaces.get(entry.iface);
            if (baseIface == null) {
                continue;
            }
            // Subtract any 464lat traffic seen for the root UID on the current base interface.
            // However, for eBPF, the per uid stats is collected by different hook, the rx packets
            // on base interface will not be counted. Thus, the adjustment on root uid is only
            // needed in tx direction.
            adjust.iface = baseIface;
            if (!useBpfStats) {
                adjust.rxBytes = -(entry.rxBytes + entry.rxPackets * IPV4V6_HEADER_DELTA);
                adjust.rxPackets = -entry.rxPackets;
            }
            adjust.txBytes = -(entry.txBytes + entry.txPackets * IPV4V6_HEADER_DELTA);
            adjust.txPackets = -entry.txPackets;
            adjustments.combineValues(adjust);

            // For 464xlat traffic, per uid stats only counts the bytes of the native IPv4 packet
            // sent on the stacked interface with prefix "v4-" and drops the IPv6 header size after
            // unwrapping. To account correctly for on-the-wire traffic, add the 20 additional bytes
            // difference for all packets (http://b/12249687, http:/b/33681750).
            entry.rxBytes += entry.rxPackets * IPV4V6_HEADER_DELTA;
            entry.txBytes += entry.txPackets * IPV4V6_HEADER_DELTA;
            stackedTraffic.setValues(i, entry);
        }

        baseTraffic.combineAllValues(adjustments);
    }

    /**
     * Calculate and apply adjustments to captured statistics for 464xlat traffic counted twice.
     *
     * <p>This mutates the object this method is called on. Equivalent to calling
     * {@link #apply464xlatAdjustments(NetworkStats, NetworkStats, Map)} with {@code this} as
     * base and stacked traffic.
     * @param stackedIfaces Mapping ipv6if -> ipv4if interface where traffic is counted on both.
     */
    public void apply464xlatAdjustments(Map<String, String> stackedIfaces, boolean useBpfStats) {
        apply464xlatAdjustments(this, this, stackedIfaces, useBpfStats);
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
        entry.metered = METERED_ALL;
        entry.roaming = ROAMING_ALL;
        entry.defaultNetwork = DEFAULT_NETWORK_ALL;
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
        entry.metered = METERED_ALL;
        entry.roaming = ROAMING_ALL;
        entry.defaultNetwork = DEFAULT_NETWORK_ALL;

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

    /**
     * Only keep entries that match all specified filters.
     *
     * <p>This mutates the original structure in place. After this method is called,
     * size is the number of matching entries, and capacity is the previous capacity.
     * @param limitUid UID to filter for, or {@link #UID_ALL}.
     * @param limitIfaces Interfaces to filter for, or {@link #INTERFACES_ALL}.
     * @param limitTag Tag to filter for, or {@link #TAG_ALL}.
     */
    public void filter(int limitUid, String[] limitIfaces, int limitTag) {
        if (limitUid == UID_ALL && limitTag == TAG_ALL && limitIfaces == INTERFACES_ALL) {
            return;
        }

        Entry entry = new Entry();
        int nextOutputEntry = 0;
        for (int i = 0; i < size; i++) {
            entry = getValues(i, entry);
            final boolean matches =
                    (limitUid == UID_ALL || limitUid == entry.uid)
                    && (limitTag == TAG_ALL || limitTag == entry.tag)
                    && (limitIfaces == INTERFACES_ALL
                            || ArrayUtils.contains(limitIfaces, entry.iface));

            if (matches) {
                setValues(nextOutputEntry, entry);
                nextOutputEntry++;
            }
        }

        size = nextOutputEntry;
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
            pw.print(" metered="); pw.print(meteredToString(metered[i]));
            pw.print(" roaming="); pw.print(roamingToString(roaming[i]));
            pw.print(" defaultNetwork="); pw.print(defaultNetworkToString(defaultNetwork[i]));
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
            case SET_DBG_VPN_IN:
                return "DBG_VPN_IN";
            case SET_DBG_VPN_OUT:
                return "DBG_VPN_OUT";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Return text description of {@link #set} value.
     */
    public static String setToCheckinString(int set) {
        switch (set) {
            case SET_ALL:
                return "all";
            case SET_DEFAULT:
                return "def";
            case SET_FOREGROUND:
                return "fg";
            case SET_DBG_VPN_IN:
                return "vpnin";
            case SET_DBG_VPN_OUT:
                return "vpnout";
            default:
                return "unk";
        }
    }

    /**
     * @return true if the querySet matches the dataSet.
     */
    public static boolean setMatches(int querySet, int dataSet) {
        if (querySet == dataSet) {
            return true;
        }
        // SET_ALL matches all non-debugging sets.
        return querySet == SET_ALL && dataSet < SET_DEBUG_START;
    }

    /**
     * Return text description of {@link #tag} value.
     */
    public static String tagToString(int tag) {
        return "0x" + Integer.toHexString(tag);
    }

    /**
     * Return text description of {@link #metered} value.
     */
    public static String meteredToString(int metered) {
        switch (metered) {
            case METERED_ALL:
                return "ALL";
            case METERED_NO:
                return "NO";
            case METERED_YES:
                return "YES";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Return text description of {@link #roaming} value.
     */
    public static String roamingToString(int roaming) {
        switch (roaming) {
            case ROAMING_ALL:
                return "ALL";
            case ROAMING_NO:
                return "NO";
            case ROAMING_YES:
                return "YES";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Return text description of {@link #defaultNetwork} value.
     */
    public static String defaultNetworkToString(int defaultNetwork) {
        switch (defaultNetwork) {
            case DEFAULT_NETWORK_ALL:
                return "ALL";
            case DEFAULT_NETWORK_NO:
                return "NO";
            case DEFAULT_NETWORK_YES:
                return "YES";
            default:
                return "UNKNOWN";
        }
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
        public void foundNonMonotonic(
                NetworkStats stats, int statsIndex, C cookie);
    }

    /**
     * VPN accounting. Move some VPN's underlying traffic to other UIDs that use tun0 iface.
     *
     * This method should only be called on delta NetworkStats. Do not call this method on a
     * snapshot {@link NetworkStats} object because the tunUid and/or the underlyingIface may
     * change over time.
     *
     * This method performs adjustments for one active VPN package and one VPN iface at a time.
     *
     * It is possible for the VPN software to use multiple underlying networks. This method
     * only migrates traffic for the primary underlying network.
     *
     * @param tunUid uid of the VPN application
     * @param tunIface iface of the vpn tunnel
     * @param underlyingIface the primary underlying network iface used by the VPN application
     * @return true if it successfully adjusts the accounting for VPN, false otherwise
     */
    public boolean migrateTun(int tunUid, String tunIface, String underlyingIface) {
        Entry tunIfaceTotal = new Entry();
        Entry underlyingIfaceTotal = new Entry();

        tunAdjustmentInit(tunUid, tunIface, underlyingIface, tunIfaceTotal, underlyingIfaceTotal);

        // If tunIface < underlyingIface, it leaves the overhead traffic in the VPN app.
        // If tunIface > underlyingIface, the VPN app doesn't get credit for data compression.
        // Negative stats should be avoided.
        Entry pool = tunGetPool(tunIfaceTotal, underlyingIfaceTotal);
        if (pool.isEmpty()) {
            return true;
        }
        Entry moved =
                addTrafficToApplications(tunUid, tunIface, underlyingIface, tunIfaceTotal, pool);
        deductTrafficFromVpnApp(tunUid, underlyingIface, moved);

        if (!moved.isEmpty()) {
            Slog.wtf(TAG, "Failed to deduct underlying network traffic from VPN package. Moved="
                    + moved);
            return false;
        }
        return true;
    }

    /**
     * Initializes the data used by the migrateTun() method.
     *
     * This is the first pass iteration which does the following work:
     * (1) Adds up all the traffic through the tunUid's underlyingIface
     *     (both foreground and background).
     * (2) Adds up all the traffic through tun0 excluding traffic from the vpn app itself.
     */
    private void tunAdjustmentInit(int tunUid, String tunIface, String underlyingIface,
            Entry tunIfaceTotal, Entry underlyingIfaceTotal) {
        Entry recycle = new Entry();
        for (int i = 0; i < size; i++) {
            getValues(i, recycle);
            if (recycle.uid == UID_ALL) {
                throw new IllegalStateException(
                        "Cannot adjust VPN accounting on an iface aggregated NetworkStats.");
            } if (recycle.set == SET_DBG_VPN_IN || recycle.set == SET_DBG_VPN_OUT) {
                throw new IllegalStateException(
                        "Cannot adjust VPN accounting on a NetworkStats containing SET_DBG_VPN_*");
            }

            if (recycle.uid == tunUid && recycle.tag == TAG_NONE
                    && Objects.equals(underlyingIface, recycle.iface)) {
                underlyingIfaceTotal.add(recycle);
            }

            if (recycle.uid != tunUid && recycle.tag == TAG_NONE
                    && Objects.equals(tunIface, recycle.iface)) {
                // Add up all tunIface traffic excluding traffic from the vpn app itself.
                tunIfaceTotal.add(recycle);
            }
        }
    }

    private static Entry tunGetPool(Entry tunIfaceTotal, Entry underlyingIfaceTotal) {
        Entry pool = new Entry();
        pool.rxBytes = Math.min(tunIfaceTotal.rxBytes, underlyingIfaceTotal.rxBytes);
        pool.rxPackets = Math.min(tunIfaceTotal.rxPackets, underlyingIfaceTotal.rxPackets);
        pool.txBytes = Math.min(tunIfaceTotal.txBytes, underlyingIfaceTotal.txBytes);
        pool.txPackets = Math.min(tunIfaceTotal.txPackets, underlyingIfaceTotal.txPackets);
        pool.operations = Math.min(tunIfaceTotal.operations, underlyingIfaceTotal.operations);
        return pool;
    }

    private Entry addTrafficToApplications(int tunUid, String tunIface, String underlyingIface,
            Entry tunIfaceTotal, Entry pool) {
        Entry moved = new Entry();
        Entry tmpEntry = new Entry();
        tmpEntry.iface = underlyingIface;
        for (int i = 0; i < size; i++) {
            // the vpn app is excluded from the redistribution but all moved traffic will be
            // deducted from the vpn app (see deductTrafficFromVpnApp below).
            if (Objects.equals(iface[i], tunIface) && uid[i] != tunUid) {
                if (tunIfaceTotal.rxBytes > 0) {
                    tmpEntry.rxBytes = pool.rxBytes * rxBytes[i] / tunIfaceTotal.rxBytes;
                } else {
                    tmpEntry.rxBytes = 0;
                }
                if (tunIfaceTotal.rxPackets > 0) {
                    tmpEntry.rxPackets = pool.rxPackets * rxPackets[i] / tunIfaceTotal.rxPackets;
                } else {
                    tmpEntry.rxPackets = 0;
                }
                if (tunIfaceTotal.txBytes > 0) {
                    tmpEntry.txBytes = pool.txBytes * txBytes[i] / tunIfaceTotal.txBytes;
                } else {
                    tmpEntry.txBytes = 0;
                }
                if (tunIfaceTotal.txPackets > 0) {
                    tmpEntry.txPackets = pool.txPackets * txPackets[i] / tunIfaceTotal.txPackets;
                } else {
                    tmpEntry.txPackets = 0;
                }
                if (tunIfaceTotal.operations > 0) {
                    tmpEntry.operations =
                            pool.operations * operations[i] / tunIfaceTotal.operations;
                } else {
                    tmpEntry.operations = 0;
                }
                tmpEntry.uid = uid[i];
                tmpEntry.tag = tag[i];
                tmpEntry.set = set[i];
                tmpEntry.metered = metered[i];
                tmpEntry.roaming = roaming[i];
                tmpEntry.defaultNetwork = defaultNetwork[i];
                combineValues(tmpEntry);
                if (tag[i] == TAG_NONE) {
                    moved.add(tmpEntry);
                    // Add debug info
                    tmpEntry.set = SET_DBG_VPN_IN;
                    combineValues(tmpEntry);
                }
            }
        }
        return moved;
    }

    private void deductTrafficFromVpnApp(int tunUid, String underlyingIface, Entry moved) {
        // Add debug info
        moved.uid = tunUid;
        moved.set = SET_DBG_VPN_OUT;
        moved.tag = TAG_NONE;
        moved.iface = underlyingIface;
        moved.metered = METERED_ALL;
        moved.roaming = ROAMING_ALL;
        moved.defaultNetwork = DEFAULT_NETWORK_ALL;
        combineValues(moved);

        // Caveat: if the vpn software uses tag, the total tagged traffic may be greater than
        // the TAG_NONE traffic.
        //
        // Relies on the fact that the underlying traffic only has state ROAMING_NO and METERED_NO,
        // which should be the case as it comes directly from the /proc file. We only blend in the
        // roaming data after applying these adjustments, by checking the NetworkIdentity of the
        // underlying iface.
        int idxVpnBackground = findIndex(underlyingIface, tunUid, SET_DEFAULT, TAG_NONE,
                METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO);
        if (idxVpnBackground != -1) {
            tunSubtract(idxVpnBackground, this, moved);
        }

        int idxVpnForeground = findIndex(underlyingIface, tunUid, SET_FOREGROUND, TAG_NONE,
                METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO);
        if (idxVpnForeground != -1) {
            tunSubtract(idxVpnForeground, this, moved);
        }
    }

    private static void tunSubtract(int i, NetworkStats left, Entry right) {
        long rxBytes = Math.min(left.rxBytes[i], right.rxBytes);
        left.rxBytes[i] -= rxBytes;
        right.rxBytes -= rxBytes;

        long rxPackets = Math.min(left.rxPackets[i], right.rxPackets);
        left.rxPackets[i] -= rxPackets;
        right.rxPackets -= rxPackets;

        long txBytes = Math.min(left.txBytes[i], right.txBytes);
        left.txBytes[i] -= txBytes;
        right.txBytes -= txBytes;

        long txPackets = Math.min(left.txPackets[i], right.txPackets);
        left.txPackets[i] -= txPackets;
        right.txPackets -= txPackets;
    }
}
