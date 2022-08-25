/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * Snapshot of wake lock stats.
 *  @hide
 */
public final class WakeLockStats implements Parcelable {

    /** @hide */
    public static class WakeLock {
        public final int uid;
        @NonNull
        public final String name;
        public final int timesAcquired;
        public final long totalTimeHeldMs;

        /**
         * Time in milliseconds that the lock has been held or 0 if not currently holding the lock
         */
        public final long timeHeldMs;

        public WakeLock(int uid, @NonNull String name, int timesAcquired, long totalTimeHeldMs,
                long timeHeldMs) {
            this.uid = uid;
            this.name = name;
            this.timesAcquired = timesAcquired;
            this.totalTimeHeldMs = totalTimeHeldMs;
            this.timeHeldMs = timeHeldMs;
        }

        private WakeLock(Parcel in) {
            uid = in.readInt();
            name = in.readString();
            timesAcquired = in.readInt();
            totalTimeHeldMs = in.readLong();
            timeHeldMs = in.readLong();
        }

        private void writeToParcel(Parcel out) {
            out.writeInt(uid);
            out.writeString(name);
            out.writeInt(timesAcquired);
            out.writeLong(totalTimeHeldMs);
            out.writeLong(timeHeldMs);
        }

        @Override
        public String toString() {
            return "WakeLock{"
                    + "uid=" + uid
                    + ", name='" + name + '\''
                    + ", timesAcquired=" + timesAcquired
                    + ", totalTimeHeldMs=" + totalTimeHeldMs
                    + ", timeHeldMs=" + timeHeldMs
                    + '}';
        }
    }

    private final List<WakeLock> mWakeLocks;

    /** @hide **/
    public WakeLockStats(@NonNull List<WakeLock> wakeLocks) {
        mWakeLocks = wakeLocks;
    }

    @NonNull
    public List<WakeLock> getWakeLocks() {
        return mWakeLocks;
    }

    private WakeLockStats(Parcel in) {
        final int size = in.readInt();
        mWakeLocks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            mWakeLocks.add(new WakeLock(in));
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        final int size = mWakeLocks.size();
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            WakeLock stats = mWakeLocks.get(i);
            stats.writeToParcel(out);
        }
    }

    @NonNull
    public static final Creator<WakeLockStats> CREATOR =
            new Creator<WakeLockStats>() {
                public WakeLockStats createFromParcel(Parcel in) {
                    return new WakeLockStats(in);
                }

                public WakeLockStats[] newArray(int size) {
                    return new WakeLockStats[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "WakeLockStats " + mWakeLocks;
    }
}
