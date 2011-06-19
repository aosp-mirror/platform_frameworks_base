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

    /**
     * {@link SystemClock#elapsedRealtime()} timestamp when this data was
     * generated.
     */
    public final long elapsedRealtime;
    public int size;
    public String[] iface;
    public int[] uid;
    public int[] tag;
    public long[] rx;
    public long[] tx;

    public NetworkStats(long elapsedRealtime, int initialSize) {
        this.elapsedRealtime = elapsedRealtime;
        this.size = 0;
        this.iface = new String[initialSize];
        this.uid = new int[initialSize];
        this.tag = new int[initialSize];
        this.rx = new long[initialSize];
        this.tx = new long[initialSize];
    }

    public NetworkStats(Parcel parcel) {
        elapsedRealtime = parcel.readLong();
        size = parcel.readInt();
        iface = parcel.createStringArray();
        uid = parcel.createIntArray();
        tag = parcel.createIntArray();
        rx = parcel.createLongArray();
        tx = parcel.createLongArray();
    }

    /**
     * Add new stats entry with given values.
     */
    public NetworkStats addEntry(String iface, int uid, int tag, long rx, long tx) {
        if (size >= this.iface.length) {
            final int newLength = Math.max(this.iface.length, 10) * 3 / 2;
            this.iface = Arrays.copyOf(this.iface, newLength);
            this.uid = Arrays.copyOf(this.uid, newLength);
            this.tag = Arrays.copyOf(this.tag, newLength);
            this.rx = Arrays.copyOf(this.rx, newLength);
            this.tx = Arrays.copyOf(this.tx, newLength);
        }

        this.iface[size] = iface;
        this.uid[size] = uid;
        this.tag[size] = tag;
        this.rx[size] = rx;
        this.tx[size] = tx;
        size++;

        return this;
    }

    /**
     * Combine given values with an existing row, or create a new row if
     * {@link #findIndex(String, int, int)} is unable to find match. Can also be
     * used to subtract values from existing rows.
     */
    public NetworkStats combineEntry(String iface, int uid, int tag, long rx, long tx) {
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
        final NetworkStats result = new NetworkStats(deltaRealtime, size);
        for (int i = 0; i < size; i++) {
            final String iface = this.iface[i];
            final int uid = this.uid[i];
            final int tag = this.tag[i];

            // find remote row that matches, and subtract
            final int j = value.findIndex(iface, uid, tag);
            if (j == -1) {
                // newly appearing row, return entire value
                result.addEntry(iface, uid, tag, this.rx[i], this.tx[i]);
            } else {
                // existing row, subtract remote value
                long rx = this.rx[i] - value.rx[j];
                long tx = this.tx[i] - value.tx[j];
                if (enforceMonotonic && (rx < 0 || tx < 0)) {
                    throw new IllegalArgumentException("found non-monotonic values");
                }
                if (clampNegative) {
                    rx = Math.max(0, rx);
                    tx = Math.max(0, tx);
                }
                result.addEntry(iface, uid, tag, rx, tx);
            }
        }

        return result;
    }

    private static boolean equal(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("NetworkStats: elapsedRealtime="); pw.println(elapsedRealtime);
        for (int i = 0; i < iface.length; i++) {
            pw.print(prefix);
            pw.print("  iface="); pw.print(iface[i]);
            pw.print(" uid="); pw.print(uid[i]);
            pw.print(" tag="); pw.print(tag[i]);
            pw.print(" rx="); pw.print(rx[i]);
            pw.print(" tx="); pw.println(tx[i]);
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
        dest.writeLongArray(tx);
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
