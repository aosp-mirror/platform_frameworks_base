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

package android.app.usage;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A pair of {package, estimated launch time} to denote the estimated launch time for a given
 * package.
 * Used as a vehicle of data across the binder IPC.
 *
 * @hide
 */
public final class AppLaunchEstimateInfo implements Parcelable {

    public final String packageName;
    @CurrentTimeMillisLong
    public final long estimatedLaunchTime;

    private AppLaunchEstimateInfo(Parcel in) {
        packageName = in.readString();
        estimatedLaunchTime = in.readLong();
    }

    public AppLaunchEstimateInfo(String packageName,
            @CurrentTimeMillisLong long estimatedLaunchTime) {
        this.packageName = packageName;
        this.estimatedLaunchTime = estimatedLaunchTime;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeLong(estimatedLaunchTime);
    }

    @NonNull
    public static final Creator<AppLaunchEstimateInfo> CREATOR =
            new Creator<AppLaunchEstimateInfo>() {
                @Override
                public AppLaunchEstimateInfo createFromParcel(Parcel source) {
                    return new AppLaunchEstimateInfo(source);
                }

                @Override
                public AppLaunchEstimateInfo[] newArray(int size) {
                    return new AppLaunchEstimateInfo[size];
                }
            };
}
