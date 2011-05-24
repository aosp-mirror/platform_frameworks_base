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

import java.io.CharArrayWriter;
import java.io.PrintWriter;

/**
 * Collection of network statistics. Can contain summary details across all
 * interfaces, or details with per-UID granularity. Designed to parcel quickly
 * across process boundaries.
 *
 * @hide
 */
public class NetworkStats implements Parcelable {
    /** {@link #iface} value when entry is summarized over all interfaces. */
    public static final String IFACE_ALL = null;
    /** {@link #uid} value when entry is summarized over all UIDs. */
    public static final int UID_ALL = 0;

    // NOTE: data should only be accounted for once in this structure; if data
    // is broken out, the summarized version should not be included.

    /**
     * {@link SystemClock#elapsedRealtime()} timestamp when this data was
     * generated.
     */
    public final long elapsedRealtime;
    public final String[] iface;
    public final int[] uid;
    public final long[] rx;
    public final long[] tx;

    // TODO: add fg/bg stats and tag granularity

    private NetworkStats(long elapsedRealtime, String[] iface, int[] uid, long[] rx, long[] tx) {
        this.elapsedRealtime = elapsedRealtime;
        this.iface = iface;
        this.uid = uid;
        this.rx = rx;
        this.tx = tx;
    }

    public NetworkStats(Parcel parcel) {
        elapsedRealtime = parcel.readLong();
        iface = parcel.createStringArray();
        uid = parcel.createIntArray();
        rx = parcel.createLongArray();
        tx = parcel.createLongArray();
    }

    public static class Builder {
        private long mElapsedRealtime;
        private final String[] mIface;
        private final int[] mUid;
        private final long[] mRx;
        private final long[] mTx;

        private int mIndex = 0;

        public Builder(long elapsedRealtime, int size) {
            mElapsedRealtime = elapsedRealtime;
            mIface = new String[size];
            mUid = new int[size];
            mRx = new long[size];
            mTx = new long[size];
        }

        public Builder addEntry(String iface, int uid, long rx, long tx) {
            mIface[mIndex] = iface;
            mUid[mIndex] = uid;
            mRx[mIndex] = rx;
            mTx[mIndex] = tx;
            mIndex++;
            return this;
        }

        public NetworkStats build() {
            if (mIndex != mIface.length) {
                throw new IllegalArgumentException("unexpected number of entries");
            }
            return new NetworkStats(mElapsedRealtime, mIface, mUid, mRx, mTx);
        }
    }

    public int length() {
        // length is identical for all fields
        return iface.length;
    }

    /**
     * Find first stats index that matches the requested parameters.
     */
    public int findIndex(String iface, int uid) {
        final int length = length();
        for (int i = 0; i < length; i++) {
            if (equal(iface, this.iface[i]) && uid == this.uid[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Subtract the given {@link NetworkStats}, effectively leaving the delta
     * between two snapshots in time. Assumes that statistics rows collect over
     * time, and that none of them have disappeared.
     */
    public NetworkStats subtract(NetworkStats value) {
        // result will have our rows, but no meaningful timestamp
        final int length = length();
        final NetworkStats.Builder result = new NetworkStats.Builder(-1, length);

        for (int i = 0; i < length; i++) {
            final String iface = this.iface[i];
            final int uid = this.uid[i];

            // find remote row that matches, and subtract
            final int j = value.findIndex(iface, uid);
            if (j == -1) {
                // newly appearing row, return entire value
                result.addEntry(iface, uid, this.rx[i], this.tx[i]);
            } else {
                // existing row, subtract remote value
                final long rx = this.rx[i] - value.rx[j];
                final long tx = this.tx[i] - value.tx[j];
                result.addEntry(iface, uid, rx, tx);
            }
        }

        return result.build();
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
        dest.writeStringArray(iface);
        dest.writeIntArray(uid);
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
