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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @deprecated nobody should be using this, but keep it around returning stub
 *             values to prevent app crashes.
 * @hide
 */
@Deprecated
public class NetworkQuotaInfo implements Parcelable {
    public static final long NO_LIMIT = -1;

    /** {@hide} */
    public NetworkQuotaInfo() {
    }

    /** {@hide} */
    public NetworkQuotaInfo(Parcel in) {
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getEstimatedBytes() {
        return 0;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getSoftLimitBytes() {
        return NO_LIMIT;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getHardLimitBytes() {
        return NO_LIMIT;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Creator<NetworkQuotaInfo> CREATOR = new Creator<NetworkQuotaInfo>() {
        @Override
        public NetworkQuotaInfo createFromParcel(Parcel in) {
            return new NetworkQuotaInfo(in);
        }

        @Override
        public NetworkQuotaInfo[] newArray(int size) {
            return new NetworkQuotaInfo[size];
        }
    };
}
