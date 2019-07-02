/*
 * Copyright (C) 2015 The Android Open Source Project
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

/**
 * CPU usage information per core.
 */
public final class CpuUsageInfo implements Parcelable {
    private long mActive;
    private long mTotal;

    public static final @android.annotation.NonNull Parcelable.Creator<CpuUsageInfo> CREATOR = new
            Parcelable.Creator<CpuUsageInfo>() {
                    public CpuUsageInfo createFromParcel(Parcel in) {
                        return new CpuUsageInfo(in);
                    }

                    public CpuUsageInfo[] newArray(int size) {
                        return new CpuUsageInfo[size];
                    }
                };

    /** @hide */
    public CpuUsageInfo(long activeTime, long totalTime) {
        mActive = activeTime;
        mTotal = totalTime;
    }

    private CpuUsageInfo(Parcel in) {
        readFromParcel(in);
    }

    /**
     * Gets the active time in milliseconds since the system last booted.
     *
     * @return Active time in milliseconds.
     */
    public long getActive() {
        return mActive;
    }

    /**
     * Gets the total time in milliseconds that the CPU has been enabled since the system last
     * booted. This includes time the CPU spent idle.
     *
     * @return Total time in milliseconds.
     */
    public long getTotal() {
        return mTotal;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mActive);
        out.writeLong(mTotal);
    }

    private void readFromParcel(Parcel in) {
        mActive = in.readLong();
        mTotal = in.readLong();
    }
}
