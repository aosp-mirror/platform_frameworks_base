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
 * The memory high-water mark value for a process.
 * {@hide}
 */
public final class ProcessMemoryHighWaterMark implements Parcelable {
    public final int uid;
    public final String processName;
    public final long rssHighWaterMarkInBytes;

    public ProcessMemoryHighWaterMark(int uid, String processName, long rssHighWaterMarkInBytes) {
        this.uid = uid;
        this.processName = processName;
        this.rssHighWaterMarkInBytes = rssHighWaterMarkInBytes;
    }

    private ProcessMemoryHighWaterMark(Parcel in) {
        uid = in.readInt();
        processName = in.readString();
        rssHighWaterMarkInBytes = in.readLong();
    }

    public static final Creator<ProcessMemoryHighWaterMark> CREATOR =
            new Creator<ProcessMemoryHighWaterMark>() {
                @Override
                public ProcessMemoryHighWaterMark createFromParcel(Parcel in) {
                    return new ProcessMemoryHighWaterMark(in);
                }

                @Override
                public ProcessMemoryHighWaterMark[] newArray(int size) {
                    return new ProcessMemoryHighWaterMark[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(uid);
        parcel.writeString(processName);
        parcel.writeLong(rssHighWaterMarkInBytes);
    }
}
