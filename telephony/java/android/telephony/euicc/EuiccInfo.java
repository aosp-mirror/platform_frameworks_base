/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.telephony.euicc;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Information about an eUICC chip/device.
 *
 * @see EuiccManager#getEuiccInfo
 */
// WARNING: Do not add any privacy-sensitive fields to this class (such as an eUICC identifier)!
// This API is accessible to all applications. Privacy-sensitive fields should be returned in their
// own APIs guarded with appropriate permission checks.
public final class EuiccInfo implements Parcelable {

    public static final @android.annotation.NonNull Creator<EuiccInfo> CREATOR =
            new Creator<EuiccInfo>() {
                @Override
                public EuiccInfo createFromParcel(Parcel in) {
                    return new EuiccInfo(in);
                }

                @Override
                public EuiccInfo[] newArray(int size) {
                    return new EuiccInfo[size];
                }
            };

    @Nullable
    @UnsupportedAppUsage
    private final String osVersion;

    /**
     * Gets the version of the operating system running on the eUICC. This field is
     * hardware-specific and is not guaranteed to match any particular format.
     */
    @Nullable
    public String getOsVersion() {
        return osVersion;
    }

    public EuiccInfo(@Nullable String osVersion) {
        this.osVersion = osVersion;
    }

    private EuiccInfo(Parcel in) {
        osVersion = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(osVersion);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
