/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * The memory stats for a process.
 * {@hide}
 */
public final class ProcessMemoryState implements Parcelable {
    public final int uid;
    public final String processName;
    public final int oomScore;
    public final long pgfault;
    public final long pgmajfault;
    public final long rssInBytes;
    public final long cacheInBytes;
    public final long swapInBytes;
    public final long startTimeNanos;

    public ProcessMemoryState(int uid, String processName, int oomScore, long pgfault,
                              long pgmajfault, long rssInBytes, long cacheInBytes,
                              long swapInBytes, long startTimeNanos) {
        this.uid = uid;
        this.processName = processName;
        this.oomScore = oomScore;
        this.pgfault = pgfault;
        this.pgmajfault = pgmajfault;
        this.rssInBytes = rssInBytes;
        this.cacheInBytes = cacheInBytes;
        this.swapInBytes = swapInBytes;
        this.startTimeNanos = startTimeNanos;
    }

    private ProcessMemoryState(Parcel in) {
        uid = in.readInt();
        processName = in.readString();
        oomScore = in.readInt();
        pgfault = in.readLong();
        pgmajfault = in.readLong();
        rssInBytes = in.readLong();
        cacheInBytes = in.readLong();
        swapInBytes = in.readLong();
        startTimeNanos = in.readLong();
    }

    public static final @android.annotation.NonNull Creator<ProcessMemoryState> CREATOR = new Creator<ProcessMemoryState>() {
        @Override
        public ProcessMemoryState createFromParcel(Parcel in) {
            return new ProcessMemoryState(in);
        }

        @Override
        public ProcessMemoryState[] newArray(int size) {
            return new ProcessMemoryState[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeInt(uid);
        parcel.writeString(processName);
        parcel.writeInt(oomScore);
        parcel.writeLong(pgfault);
        parcel.writeLong(pgmajfault);
        parcel.writeLong(rssInBytes);
        parcel.writeLong(cacheInBytes);
        parcel.writeLong(swapInBytes);
        parcel.writeLong(startTimeNanos);
    }
}
