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
 * State (oom score) for processes known to activity manager.
 * {@hide}
 */
public final class ProcessMemoryState implements Parcelable {
    public final int uid;
    public final int pid;
    public final String processName;
    public final int oomScore;
    public final boolean hasForegroundServices;

    public ProcessMemoryState(int uid, int pid, String processName, int oomScore,
            boolean hasForegroundServices) {
        this.uid = uid;
        this.pid = pid;
        this.processName = processName;
        this.oomScore = oomScore;
        this.hasForegroundServices = hasForegroundServices;
    }

    private ProcessMemoryState(Parcel in) {
        uid = in.readInt();
        pid = in.readInt();
        processName = in.readString();
        oomScore = in.readInt();
        hasForegroundServices = in.readInt() == 1;
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
        parcel.writeInt(pid);
        parcel.writeString(processName);
        parcel.writeInt(oomScore);
        parcel.writeInt(hasForegroundServices ? 1 : 0);
    }
}
