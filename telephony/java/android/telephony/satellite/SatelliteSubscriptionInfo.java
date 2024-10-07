/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.telephony.satellite;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * SatelliteSubscriptionInfo is used to pack subscription related info needed by modem to allow
 * carrier to roam to satellite.
 *
 * @hide
 */
public final class SatelliteSubscriptionInfo implements Parcelable {
    /**
     * The ICC ID used for satellite attachment.
     */
    @NonNull private final String mIccId;

    /**
     * The NIDD(Non IP Data) APN to be used for carrier roaming to satellite attachment.
     */
    @NonNull private final String mNiddApn;

    public SatelliteSubscriptionInfo(@NonNull String iccId, @NonNull String niddApn) {
        mIccId = iccId;
        mNiddApn = niddApn;
    }

    private SatelliteSubscriptionInfo(Parcel in) {
        mIccId = in.readString8();
        mNiddApn = in.readString8();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mIccId);
        dest.writeString8(mNiddApn);
    }

    @NonNull public static final Creator<SatelliteSubscriptionInfo> CREATOR = new Creator<>() {
        @Override
        public SatelliteSubscriptionInfo createFromParcel(Parcel in) {
            return new SatelliteSubscriptionInfo(in);
        }

        @Override
        public SatelliteSubscriptionInfo[] newArray(int size) {
            return new SatelliteSubscriptionInfo[size];
        }
    };

    @Override
    @NonNull public String toString() {
        return (new StringBuilder()).append("SatelliteSubscriptionInfo{")
                .append("IccId=").append(mIccId)
                .append(", NiddApn=").append(mNiddApn)
                .append("}")
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SatelliteSubscriptionInfo that = (SatelliteSubscriptionInfo) o;
        return mIccId.equals(that.getIccId()) && mNiddApn.equals(that.getNiddApn());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getIccId(), getNiddApn());
    }

    @NonNull
    public String getIccId() {
        return mIccId;
    }

    @NonNull
    public String getNiddApn() {
        return mNiddApn;
    }
}
