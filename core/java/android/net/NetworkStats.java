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
    /** {@link #tag} value for without tag. */
    public static final int TAG_NONE = 0;

    // TODO: move public fields to Entry accessors, then undeprecate
    // TODO: refactor rx/tx to rxBytes/txBytes

    /**
     * {@link SystemClock#elapsedRealtime()} timestamp when this data was
     * generated.
     */
    @Deprecated
    public final long elapsedRealtime;
    @Deprecated
    public int size;
    @Deprecated
    public String[] iface;
    @Deprecated
    public int[] uid;
    @Deprecated
    public int[] tag;
    @Deprecated
    public long[] rx;
    @Deprecated
    public long[] rxPackets;
    @Deprecated
    public long[] tx;
    @Deprecated
    public long[] txPackets;

    public static class Entry {
        public String iface;
        public int uid;
        public int tag;
        public long rxBytes;
        public long rxPackets;
        public long txBytes;
        public long txPackets;
    }

    public NetworkStats(long elapsedRealtime, int initialSize) {
        this.elapsedRealtime = elapsedRealtime;
        this.size = 0;
        this.iface = new String[initialSize];
        this.uid = new int[initialSize];
        this.tag = new int[initialSize];
        this.rx = new long[initialSize];
        this.rxPackets = new long[initialSize];
        this.tx = new long[initialSize];
        this.txPackets = new long[initialSize];
    }

    public NetworkStats(Parcel parcel) {
        elapsedRealtime = parcel.readLong();
        size = parcel.readInt();
        iface = parcel.createStringArray();
        uid = parcel.createIntArray();
        tag = parcel.createIntArray();
        rx = parcel.createLongArray();
        rxPackets = parcel.createLongArray();
        tx = parcel.createLongArray();
        txPackets = parcel.createLongArray();
    }

    /**
     * Add new stats entry with given values.
     */
    public NetworkStats addEntry(String iface, int uid, int tag, long rx, long tx) {
        final Entry entry = new Entry();
        entry.iface = iface;
        entry.uid = uid;
        entry.tag = tag;
        entry.rxBytes = rx;
        entry.txBytes = tx;
        return addValues(entry);
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
            tag = Arrays.copyOf(tag, newLength);
            rx = Arrays.copyOf(rx, newLength);
            rxPackets = Arrays.copyOf(rxPackets, newLength);
            tx = Arrays.copyOf(tx, newLength);
            txPackets = Arrays.copyOf(txPackets, newLength);
        }

        iface[size] = entry.iface;
        uid[size] = entry.uid;
        tag[size] = entry.tag;
        rx[size] = entry.rxBytes;
        rxPackets[size] = entry.rxPackets;
        tx[size] = entry.txBytes;
        txPackets[size] = entry.txPackets;
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
        entry.tag = tag[i];
        entry.rxBytes = rx[i];
        entry.rxPackets = rxPackets[i];
        entry.txBytes = tx[i];
        entry.txPackets = txPackets[i];
        return entry;
    }

    public long getElapsedRealtime() {
        return elapsedRealtime;
    }

    public int size() {
        return size;
    }

    /**
     * Combine given values with an existing row, or create a new row if
     * {@link #findIndex(String, int, int)} is unable to find match. Can also be
     * used to subtract values from existing rows.
     */
    public NetworkStats combineEntry(String iface, int uid, int tag, long rx, long tx) {
        // TODO: extent to accept rxPackets/txPackets
        final int i = findIndex(iface, uid, tag);
        if (i == -1) {
            // only create new entry when positive contribution
            addEntry(iface, uid, tag, rx, tx);
        } else {
            this.rx[i] += rx;
            this.tx[i] += tx;
        }
        return this;
    }

    /**
     * Find first stats index that matches the requested parameters.
     */
    public int findIndex(String iface, int uid, int tag) {
        for (int i = 0; i < size; i++) {
            if (equal(iface, this.iface[i]) && uid == this.uid[i] && tag == this.tag[i]) {
                return i;
            }
        }
        return -1;
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
     * Subtract the given {@link NetworkStats}, effectively leaving the delta
     * between two snapshots in time. Assumes that statistics rows collect over
     * time, and that none of them have disappeared.
     *
     * @throws IllegalArgumentException when given {@link NetworkStats} is
     *             non-monotonic.
     */
    public NetworkStats subtract(NetworkStats value) {
        return subtract(value, true, false);
    }

    /**
     * Subtract the given {@link NetworkStats}, effectively leaving the delta
     * between two snapshots in time. Assumes that statistics rows collect over
     * time, and that none of them have disappeared.
     * <p>
     * Instead of throwing when counters are non-monotonic, this variant clamps
     * results to never be negative.
     */
    public NetworkStats subtractClamped(NetworkStats value) {
        return subtract(value, false, true);
    }

    /**
     * Subtract the given {@link NetworkStats}, effectively leaving the delta
     * between two snapshots in time. Assumes that statistics rows collect over
     * time, and that none of them have disappeared.
     *
     * @param enforceMonotonic Validate that incoming value is strictly
     *            monotonic compared to this object.
     * @param clampNegative Instead of throwing like {@code enforceMonotonic},
     *            clamp resulting counters at 0 to prevent negative values.
     */
    private NetworkStats subtract(
            NetworkStats value, boolean enforceMonotonic, boolean clampNegative) {
        final long deltaRealtime = this.elapsedRealtime - value.elapsedRealtime;
        if (enforceMonotonic && deltaRealtime < 0) {
            throw new IllegalArgumentException("found non-monotonic realtime");
        }

        // result will have our rows, and elapsed time between snapshots
        final Entry entry = new Entry();
        final NetworkStats result = new NetworkStats(deltaRealtime, size);
        for (int i = 0; i < size; i++) {
            entry.iface = iface[i];
            entry.uid = uid[i];
            entry.tag = tag[i];

            // find remote row that matches, and subtract
            final int j = value.findIndex(entry.iface, entry.uid, entry.tag);
            if (j == -1) {
                // newly appearing row, return entire value
                entry.rxBytes = rx[i];
                entry.rxPackets = rxPackets[i];
                entry.txBytes = tx[i];
                entry.txPackets = txPackets[i];
            } else {
                // existing row, subtract remote value
                entry.rxBytes = rx[i] - value.rx[j];
                entry.rxPackets = rxPackets[i] - value.rxPackets[j];
                entry.txBytes = tx[i] - value.tx[j];
                entry.txPackets = txPackets[i] - value.txPackets[j];
                if (enforceMonotonic
                        && (entry.rxBytes < 0 || entry.rxPackets < 0 || entry.txBytes < 0
                                || entry.txPackets < 0)) {
                    throw new IllegalArgumentException("found non-monotonic values");
                }
                if (clampNegative) {
                    entry.rxBytes = Math.max(0, entry.rxBytes);
                    entry.rxPackets = Math.max(0, entry.rxPackets);
                    entry.txBytes = Math.max(0, entry.txBytes);
                    entry.txPackets = Math.max(0, entry.txPackets);
                }
            }

            result.addValues(entry);
        }

        return result;
    }

    private static boolean equal(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("NetworkStats: elapsedRealtime="); pw.println(elapsedRealtime);
        for (int i = 0; i < size; i++) {
            pw.print(prefix);
            pw.print("  iface="); pw.print(iface[i]);
            pw.print(" uid="); pw.print(uid[i]);
            pw.print(" tag="); pw.print(tag[i]);
            pw.print(" rxBytes="); pw.print(rx[i]);
            pw.print(" rxPackets="); pw.print(rxPackets[i]);
            pw.print(" txBytes="); pw.print(tx[i]);
            pw.print(" txPackets="); pw.println(txPackets[i]);
        }
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump("", new PrintWriter(writer));
        return writer.toString();
    }

    /** {@inheritDoc} */
    public int describeContents() {
        return 0;
    }

    /** {@inheritDoc} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(elapsedRealtime);
        dest.writeInt(size);
        dest.writeStringArray(iface);
        dest.writeIntArray(uid);
        dest.writeIntArray(tag);
        dest.writeLongArray(rx);
        dest.writeLongArray(rxPackets);
        dest.writeLongArray(tx);
        dest.writeLongArray(txPackets);
    }

    public static final Creator<NetworkStats> CREATOR = new Creator<NetworkStats>() {
        public NetworkStats createFromParcel(Parcel in) {
            return new NetworkStats(in);
        }

        public NetworkStats[] newArray(int size) {
            return new NetworkStats[size];
        }
    };
}
