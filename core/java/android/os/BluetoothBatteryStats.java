/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of Bluetooth battery stats.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class BluetoothBatteryStats implements Parcelable {

    /** @hide */
    public static class UidStats {
        public final int uid;
        public final long scanTimeMs;
        public final long unoptimizedScanTimeMs;
        public final int scanResultCount;
        public final long rxTimeMs;
        public final long txTimeMs;

        public UidStats(int uid, long scanTimeMs, long unoptimizedScanTimeMs, int scanResultCount,
                long rxTimeMs, long txTimeMs) {
            this.uid = uid;
            this.scanTimeMs = scanTimeMs;
            this.unoptimizedScanTimeMs = unoptimizedScanTimeMs;
            this.scanResultCount = scanResultCount;
            this.rxTimeMs = rxTimeMs;
            this.txTimeMs = txTimeMs;
        }

        private UidStats(Parcel in) {
            uid = in.readInt();
            scanTimeMs = in.readLong();
            unoptimizedScanTimeMs = in.readLong();
            scanResultCount = in.readInt();
            rxTimeMs = in.readLong();
            txTimeMs = in.readLong();
        }

        private void writeToParcel(Parcel out) {
            out.writeInt(uid);
            out.writeLong(scanTimeMs);
            out.writeLong(unoptimizedScanTimeMs);
            out.writeInt(scanResultCount);
            out.writeLong(rxTimeMs);
            out.writeLong(txTimeMs);
        }

        @Override
        public String toString() {
            return "UidStats{"
                    + "uid=" + uid
                    + ", scanTimeMs=" + scanTimeMs
                    + ", unoptimizedScanTimeMs=" + unoptimizedScanTimeMs
                    + ", scanResultCount=" + scanResultCount
                    + ", rxTimeMs=" + rxTimeMs
                    + ", txTimeMs=" + txTimeMs
                    + '}';
        }
    }

    private final List<UidStats> mUidStats;

    public BluetoothBatteryStats(@NonNull List<UidStats> uidStats) {
        mUidStats = uidStats;
    }

    @NonNull
    public List<UidStats> getUidStats() {
        return mUidStats;
    }

    protected BluetoothBatteryStats(Parcel in) {
        final int size = in.readInt();
        mUidStats = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            mUidStats.add(new UidStats(in));
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        final int size = mUidStats.size();
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            UidStats stats = mUidStats.get(i);
            stats.writeToParcel(out);
        }
    }

    public static final Creator<BluetoothBatteryStats> CREATOR =
            new Creator<BluetoothBatteryStats>() {
                @Override
                public BluetoothBatteryStats createFromParcel(Parcel in) {
                    return new BluetoothBatteryStats(in);
                }

                @Override
                public BluetoothBatteryStats[] newArray(int size) {
                    return new BluetoothBatteryStats[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }
}
