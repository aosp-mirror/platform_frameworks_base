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

import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStatsHistory.DataStreamUtils.readFullLongArray;
import static android.net.NetworkStatsHistory.DataStreamUtils.readVarLongArray;
import static android.net.NetworkStatsHistory.DataStreamUtils.writeVarLongArray;
import static android.net.NetworkStatsHistory.Entry.UNKNOWN;
import static android.net.NetworkStatsHistory.ParcelUtils.readLongArray;
import static android.net.NetworkStatsHistory.ParcelUtils.writeLongArray;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.CharArrayWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ProtocolException;
import java.util.Arrays;
import java.util.Random;

/**
 * Collection of historical network statistics, recorded into equally-sized
 * "buckets" in time. Internally it stores data in {@code long} series for more
 * efficient persistence.
 * <p>
 * Each bucket is defined by a {@link #bucketStart} timestamp, and lasts for
 * {@link #bucketDuration}. Internally assumes that {@link #bucketStart} is
 * sorted at all times.
 *
 * @hide
 */
public class NetworkStatsHistory implements Parcelable {
    private static final int VERSION_INIT = 1;
    private static final int VERSION_ADD_PACKETS = 2;

    public static final int FIELD_RX_BYTES = 0x01;
    public static final int FIELD_RX_PACKETS = 0x02;
    public static final int FIELD_TX_BYTES = 0x04;
    public static final int FIELD_TX_PACKETS = 0x08;
    public static final int FIELD_OPERATIONS = 0x10;

    public static final int FIELD_ALL = 0xFFFFFFFF;

    private long bucketDuration;
    private int bucketCount;
    private long[] bucketStart;
    private long[] rxBytes;
    private long[] rxPackets;
    private long[] txBytes;
    private long[] txPackets;
    private long[] operations;

    public static class Entry {
        public static final long UNKNOWN = -1;

        public long bucketStart;
        public long bucketDuration;
        public long rxBytes;
        public long rxPackets;
        public long txBytes;
        public long txPackets;
        public long operations;
    }

    public NetworkStatsHistory(long bucketDuration) {
        this(bucketDuration, 10, FIELD_ALL);
    }

    public NetworkStatsHistory(long bucketDuration, int initialSize) {
        this(bucketDuration, initialSize, FIELD_ALL);
    }

    public NetworkStatsHistory(long bucketDuration, int initialSize, int fields) {
        this.bucketDuration = bucketDuration;
        bucketStart = new long[initialSize];
        if ((fields & FIELD_RX_BYTES) != 0) rxBytes = new long[initialSize];
        if ((fields & FIELD_RX_PACKETS) != 0) rxPackets = new long[initialSize];
        if ((fields & FIELD_TX_BYTES) != 0) txBytes = new long[initialSize];
        if ((fields & FIELD_TX_PACKETS) != 0) txPackets = new long[initialSize];
        if ((fields & FIELD_OPERATIONS) != 0) operations = new long[initialSize];
        bucketCount = 0;
    }

    public NetworkStatsHistory(Parcel in) {
        bucketDuration = in.readLong();
        bucketStart = readLongArray(in);
        rxBytes = readLongArray(in);
        rxPackets = readLongArray(in);
        txBytes = readLongArray(in);
        txPackets = readLongArray(in);
        operations = readLongArray(in);
        bucketCount = bucketStart.length;
    }

    /** {@inheritDoc} */
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(bucketDuration);
        writeLongArray(out, bucketStart, bucketCount);
        writeLongArray(out, rxBytes, bucketCount);
        writeLongArray(out, rxPackets, bucketCount);
        writeLongArray(out, txBytes, bucketCount);
        writeLongArray(out, txPackets, bucketCount);
        writeLongArray(out, operations, bucketCount);
    }

    public NetworkStatsHistory(DataInputStream in) throws IOException {
        final int version = in.readInt();
        switch (version) {
            case VERSION_INIT: {
                bucketDuration = in.readLong();
                bucketStart = readFullLongArray(in);
                rxBytes = readFullLongArray(in);
                rxPackets = new long[bucketStart.length];
                txBytes = readFullLongArray(in);
                txPackets = new long[bucketStart.length];
                operations = new long[bucketStart.length];
                bucketCount = bucketStart.length;
                break;
            }
            case VERSION_ADD_PACKETS: {
                bucketDuration = in.readLong();
                bucketStart = readVarLongArray(in);
                rxBytes = readVarLongArray(in);
                rxPackets = readVarLongArray(in);
                txBytes = readVarLongArray(in);
                txPackets = readVarLongArray(in);
                operations = readVarLongArray(in);
                bucketCount = bucketStart.length;
                break;
            }
            default: {
                throw new ProtocolException("unexpected version: " + version);
            }
        }
    }

    public void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(VERSION_ADD_PACKETS);
        out.writeLong(bucketDuration);
        writeVarLongArray(out, bucketStart, bucketCount);
        writeVarLongArray(out, rxBytes, bucketCount);
        writeVarLongArray(out, rxPackets, bucketCount);
        writeVarLongArray(out, txBytes, bucketCount);
        writeVarLongArray(out, txPackets, bucketCount);
        writeVarLongArray(out, operations, bucketCount);
    }

    /** {@inheritDoc} */
    public int describeContents() {
        return 0;
    }

    public int size() {
        return bucketCount;
    }

    public long getBucketDuration() {
        return bucketDuration;
    }

    public long getStart() {
        if (bucketCount > 0) {
            return bucketStart[0];
        } else {
            return Long.MAX_VALUE;
        }
    }

    public long getEnd() {
        if (bucketCount > 0) {
            return bucketStart[bucketCount - 1] + bucketDuration;
        } else {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Return specific stats entry.
     */
    public Entry getValues(int i, Entry recycle) {
        final Entry entry = recycle != null ? recycle : new Entry();
        entry.bucketStart = bucketStart[i];
        entry.bucketDuration = bucketDuration;
        entry.rxBytes = getLong(rxBytes, i, UNKNOWN);
        entry.rxPackets = getLong(rxPackets, i, UNKNOWN);
        entry.txBytes = getLong(txBytes, i, UNKNOWN);
        entry.txPackets = getLong(txPackets, i, UNKNOWN);
        entry.operations = getLong(operations, i, UNKNOWN);
        return entry;
    }

    /**
     * Record that data traffic occurred in the given time range. Will
     * distribute across internal buckets, creating new buckets as needed.
     */
    @Deprecated
    public void recordData(long start, long end, long rxBytes, long txBytes) {
        recordData(start, end, new NetworkStats.Entry(
                IFACE_ALL, UID_ALL, SET_DEFAULT, TAG_NONE, rxBytes, 0L, txBytes, 0L, 0L));
    }

    /**
     * Record that data traffic occurred in the given time range. Will
     * distribute across internal buckets, creating new buckets as needed.
     */
    public void recordData(long start, long end, NetworkStats.Entry entry) {
        if (entry.rxBytes < 0 || entry.rxPackets < 0 || entry.txBytes < 0 || entry.txPackets < 0
                || entry.operations < 0) {
            throw new IllegalArgumentException("tried recording negative data");
        }

        // create any buckets needed by this range
        ensureBuckets(start, end);

        // distribute data usage into buckets
        long duration = end - start;
        for (int i = bucketCount - 1; i >= 0; i--) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // bucket is older than record; we're finished
            if (curEnd < start) break;
            // bucket is newer than record; keep looking
            if (curStart > end) continue;

            final long overlap = Math.min(curEnd, end) - Math.max(curStart, start);
            if (overlap <= 0) continue;

            // integer math each time is faster than floating point
            final long fracRxBytes = entry.rxBytes * overlap / duration;
            final long fracRxPackets = entry.rxPackets * overlap / duration;
            final long fracTxBytes = entry.txBytes * overlap / duration;
            final long fracTxPackets = entry.txPackets * overlap / duration;
            final int fracOperations = (int) (entry.operations * overlap / duration);

            addLong(rxBytes, i, fracRxBytes); entry.rxBytes -= fracRxBytes;
            addLong(rxPackets, i, fracRxPackets); entry.rxPackets -= fracRxPackets;
            addLong(txBytes, i, fracTxBytes); entry.txBytes -= fracTxBytes;
            addLong(txPackets, i, fracTxPackets); entry.txPackets -= fracTxPackets;
            addLong(operations, i, fracOperations); entry.operations -= fracOperations;

            duration -= overlap;
        }
    }

    /**
     * Record an entire {@link NetworkStatsHistory} into this history. Usually
     * for combining together stats for external reporting.
     */
    public void recordEntireHistory(NetworkStatsHistory input) {
        final NetworkStats.Entry entry = new NetworkStats.Entry(
                IFACE_ALL, UID_ALL, SET_DEFAULT, TAG_NONE, 0L, 0L, 0L, 0L, 0L);
        for (int i = 0; i < input.bucketCount; i++) {
            final long start = input.bucketStart[i];
            final long end = start + input.bucketDuration;

            entry.rxBytes = getLong(input.rxBytes, i, 0L);
            entry.rxPackets = getLong(input.rxPackets, i, 0L);
            entry.txBytes = getLong(input.txBytes, i, 0L);
            entry.txPackets = getLong(input.txPackets, i, 0L);
            entry.operations = getLong(input.operations, i, 0L);

            recordData(start, end, entry);
        }
    }

    /**
     * Ensure that buckets exist for given time range, creating as needed.
     */
    private void ensureBuckets(long start, long end) {
        // normalize incoming range to bucket boundaries
        start -= start % bucketDuration;
        end += (bucketDuration - (end % bucketDuration)) % bucketDuration;

        for (long now = start; now < end; now += bucketDuration) {
            // try finding existing bucket
            final int index = Arrays.binarySearch(bucketStart, 0, bucketCount, now);
            if (index < 0) {
                // bucket missing, create and insert
                insertBucket(~index, now);
            }
        }
    }

    /**
     * Insert new bucket at requested index and starting time.
     */
    private void insertBucket(int index, long start) {
        // create more buckets when needed
        if (bucketCount >= bucketStart.length) {
            final int newLength = Math.max(bucketStart.length, 10) * 3 / 2;
            bucketStart = Arrays.copyOf(bucketStart, newLength);
            if (rxBytes != null) rxBytes = Arrays.copyOf(rxBytes, newLength);
            if (rxPackets != null) rxPackets = Arrays.copyOf(rxPackets, newLength);
            if (txBytes != null) txBytes = Arrays.copyOf(txBytes, newLength);
            if (txPackets != null) txPackets = Arrays.copyOf(txPackets, newLength);
            if (operations != null) operations = Arrays.copyOf(operations, newLength);
        }

        // create gap when inserting bucket in middle
        if (index < bucketCount) {
            final int dstPos = index + 1;
            final int length = bucketCount - index;

            System.arraycopy(bucketStart, index, bucketStart, dstPos, length);
            if (rxBytes != null) System.arraycopy(rxBytes, index, rxBytes, dstPos, length);
            if (rxPackets != null) System.arraycopy(rxPackets, index, rxPackets, dstPos, length);
            if (txBytes != null) System.arraycopy(txBytes, index, txBytes, dstPos, length);
            if (txPackets != null) System.arraycopy(txPackets, index, txPackets, dstPos, length);
            if (operations != null) System.arraycopy(operations, index, operations, dstPos, length);
        }

        bucketStart[index] = start;
        setLong(rxBytes, index, 0L);
        setLong(rxPackets, index, 0L);
        setLong(txBytes, index, 0L);
        setLong(txPackets, index, 0L);
        setLong(operations, index, 0L);
        bucketCount++;
    }

    /**
     * Remove buckets older than requested cutoff.
     */
    public void removeBucketsBefore(long cutoff) {
        int i;
        for (i = 0; i < bucketCount; i++) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // cutoff happens before or during this bucket; everything before
            // this bucket should be removed.
            if (curEnd > cutoff) break;
        }

        if (i > 0) {
            final int length = bucketStart.length;
            bucketStart = Arrays.copyOfRange(bucketStart, i, length);
            if (rxBytes != null) rxBytes = Arrays.copyOfRange(rxBytes, i, length);
            if (rxPackets != null) rxPackets = Arrays.copyOfRange(rxPackets, i, length);
            if (txBytes != null) txBytes = Arrays.copyOfRange(txBytes, i, length);
            if (txPackets != null) txPackets = Arrays.copyOfRange(txPackets, i, length);
            if (operations != null) operations = Arrays.copyOfRange(operations, i, length);
            bucketCount -= i;
        }
    }

    /**
     * Return interpolated data usage across the requested range. Interpolates
     * across buckets, so values may be rounded slightly.
     */
    public Entry getValues(long start, long end, Entry recycle) {
        return getValues(start, end, Long.MAX_VALUE, recycle);
    }

    /**
     * Return interpolated data usage across the requested range. Interpolates
     * across buckets, so values may be rounded slightly.
     */
    public Entry getValues(long start, long end, long now, Entry recycle) {
        final Entry entry = recycle != null ? recycle : new Entry();
        entry.bucketStart = start;
        entry.bucketDuration = end - start;
        entry.rxBytes = rxBytes != null ? 0 : UNKNOWN;
        entry.rxPackets = rxPackets != null ? 0 : UNKNOWN;
        entry.txBytes = txBytes != null ? 0 : UNKNOWN;
        entry.txPackets = txPackets != null ? 0 : UNKNOWN;
        entry.operations = operations != null ? 0 : UNKNOWN;

        for (int i = bucketCount - 1; i >= 0; i--) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // bucket is older than record; we're finished
            if (curEnd < start) break;
            // bucket is newer than record; keep looking
            if (curStart > end) continue;

            // include full value for active buckets, otherwise only fractional
            final boolean activeBucket = curStart < now && curEnd > now;
            final long overlap = activeBucket ? bucketDuration
                    : Math.min(curEnd, end) - Math.max(curStart, start);
            if (overlap <= 0) continue;

            // integer math each time is faster than floating point
            if (rxBytes != null) entry.rxBytes += rxBytes[i] * overlap / bucketDuration;
            if (rxPackets != null) entry.rxPackets += rxPackets[i] * overlap / bucketDuration;
            if (txBytes != null) entry.txBytes += txBytes[i] * overlap / bucketDuration;
            if (txPackets != null) entry.txPackets += txPackets[i] * overlap / bucketDuration;
            if (operations != null) entry.operations += operations[i] * overlap / bucketDuration;
        }

        return entry;
    }

    /**
     * @deprecated only for temporary testing
     */
    @Deprecated
    public void generateRandom(long start, long end, long rxBytes, long rxPackets, long txBytes,
            long txPackets, long operations) {
        ensureBuckets(start, end);

        final NetworkStats.Entry entry = new NetworkStats.Entry(
                IFACE_ALL, UID_ALL, SET_DEFAULT, TAG_NONE, 0L, 0L, 0L, 0L, 0L);
        final Random r = new Random();
        while (rxBytes > 1024 || rxPackets > 128 || txBytes > 1024 || txPackets > 128
                || operations > 32) {
            final long curStart = randomLong(r, start, end);
            final long curEnd = randomLong(r, curStart, end);

            entry.rxBytes = randomLong(r, 0, rxBytes);
            entry.rxPackets = randomLong(r, 0, rxPackets);
            entry.txBytes = randomLong(r, 0, txBytes);
            entry.txPackets = randomLong(r, 0, txPackets);
            entry.operations = randomLong(r, 0, operations);

            rxBytes -= entry.rxBytes;
            rxPackets -= entry.rxPackets;
            txBytes -= entry.txBytes;
            txPackets -= entry.txPackets;
            operations -= entry.operations;

            recordData(curStart, curEnd, entry);
        }
    }

    private static long randomLong(Random r, long start, long end) {
        return (long) (start + (r.nextFloat() * (end - start)));
    }

    public void dump(String prefix, PrintWriter pw, boolean fullHistory) {
        pw.print(prefix);
        pw.print("NetworkStatsHistory: bucketDuration="); pw.println(bucketDuration);

        final int start = fullHistory ? 0 : Math.max(0, bucketCount - 32);
        if (start > 0) {
            pw.print(prefix);
            pw.print("  (omitting "); pw.print(start); pw.println(" buckets)");
        }

        for (int i = start; i < bucketCount; i++) {
            pw.print(prefix);
            pw.print("  bucketStart="); pw.print(bucketStart[i]);
            if (rxBytes != null) pw.print(" rxBytes="); pw.print(rxBytes[i]);
            if (rxPackets != null) pw.print(" rxPackets="); pw.print(rxPackets[i]);
            if (txBytes != null) pw.print(" txBytes="); pw.print(txBytes[i]);
            if (txPackets != null) pw.print(" txPackets="); pw.print(txPackets[i]);
            if (operations != null) pw.print(" operations="); pw.print(operations[i]);
            pw.println();
        }
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump("", new PrintWriter(writer), false);
        return writer.toString();
    }

    public static final Creator<NetworkStatsHistory> CREATOR = new Creator<NetworkStatsHistory>() {
        public NetworkStatsHistory createFromParcel(Parcel in) {
            return new NetworkStatsHistory(in);
        }

        public NetworkStatsHistory[] newArray(int size) {
            return new NetworkStatsHistory[size];
        }
    };

    private static long getLong(long[] array, int i, long value) {
        return array != null ? array[i] : value;
    }

    private static void setLong(long[] array, int i, long value) {
        if (array != null) array[i] = value;
    }

    private static void addLong(long[] array, int i, long value) {
        if (array != null) array[i] += value;
    }

    /**
     * Utility methods for interacting with {@link DataInputStream} and
     * {@link DataOutputStream}, mostly dealing with writing partial arrays.
     */
    public static class DataStreamUtils {
        @Deprecated
        public static long[] readFullLongArray(DataInputStream in) throws IOException {
            final int size = in.readInt();
            final long[] values = new long[size];
            for (int i = 0; i < values.length; i++) {
                values[i] = in.readLong();
            }
            return values;
        }

        /**
         * Read variable-length {@link Long} using protobuf-style approach.
         */
        public static long readVarLong(DataInputStream in) throws IOException {
            int shift = 0;
            long result = 0;
            while (shift < 64) {
                byte b = in.readByte();
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0)
                    return result;
                shift += 7;
            }
            throw new ProtocolException("malformed long");
        }

        /**
         * Write variable-length {@link Long} using protobuf-style approach.
         */
        public static void writeVarLong(DataOutputStream out, long value) throws IOException {
            while (true) {
                if ((value & ~0x7FL) == 0) {
                    out.writeByte((int) value);
                    return;
                } else {
                    out.writeByte(((int) value & 0x7F) | 0x80);
                    value >>>= 7;
                }
            }
        }

        public static long[] readVarLongArray(DataInputStream in) throws IOException {
            final int size = in.readInt();
            if (size == -1) return null;
            final long[] values = new long[size];
            for (int i = 0; i < values.length; i++) {
                values[i] = readVarLong(in);
            }
            return values;
        }

        public static void writeVarLongArray(DataOutputStream out, long[] values, int size)
                throws IOException {
            if (values == null) {
                out.writeInt(-1);
                return;
            }
            if (size > values.length) {
                throw new IllegalArgumentException("size larger than length");
            }
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                writeVarLong(out, values[i]);
            }
        }
    }

    /**
     * Utility methods for interacting with {@link Parcel} structures, mostly
     * dealing with writing partial arrays.
     */
    public static class ParcelUtils {
        public static long[] readLongArray(Parcel in) {
            final int size = in.readInt();
            if (size == -1) return null;
            final long[] values = new long[size];
            for (int i = 0; i < values.length; i++) {
                values[i] = in.readLong();
            }
            return values;
        }

        public static void writeLongArray(Parcel out, long[] values, int size) {
            if (values == null) {
                out.writeInt(-1);
                return;
            }
            if (size > values.length) {
                throw new IllegalArgumentException("size larger than length");
            }
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                out.writeLong(values[i]);
            }
        }
    }

}
