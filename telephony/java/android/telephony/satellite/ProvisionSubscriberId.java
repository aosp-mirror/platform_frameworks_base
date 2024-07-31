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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.util.Objects;

/**
 * ProvisionSubscriberId
 *
 * Satellite Gateway client will use these subscriber ids to register with satellite gateway service
 * which identify user subscription with unique subscriber ids. These subscriber ids can be any
 * unique value like iccid, imsi or msisdn which is decided based upon carrier requirements.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
public final class ProvisionSubscriberId implements Parcelable {
    /** provision subscriberId */
    @NonNull
    private String mSubscriberId;

    /** carrier id */
    private int mCarrierId;

    /** apn */
    private String mNiddApn;

    /**
     * @hide
     */
    public ProvisionSubscriberId(@NonNull String subscriberId, @NonNull int carrierId,
            @NonNull String niddApn) {
        this.mCarrierId = carrierId;
        this.mSubscriberId = subscriberId;
        this.mNiddApn = niddApn;
    }

    private ProvisionSubscriberId(Parcel in) {
        readFromParcel(in);
    }

    /**
     * @hide
     */
    @Override
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(mSubscriberId);
        out.writeInt(mCarrierId);
        out.writeString(mNiddApn);
    }

    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public static final @android.annotation.NonNull Creator<ProvisionSubscriberId> CREATOR =
            new Creator<ProvisionSubscriberId>() {
                @Override
                public ProvisionSubscriberId createFromParcel(Parcel in) {
                    return new ProvisionSubscriberId(in);
                }

                @Override
                public ProvisionSubscriberId[] newArray(int size) {
                    return new ProvisionSubscriberId[size];
                }
            };

    /**
     * @hide
     */
    @Override
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public int describeContents() {
        return 0;
    }

    /**
     * @return provision subscriberId.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public String getSubscriberId() {
        return mSubscriberId;
    }

    /**
     * @return carrierId.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public int getCarrierId() {
        return mCarrierId;
    }

    /**
     * @return niddApn.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public String getNiddApn() {
        return mNiddApn;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("SubscriberId:");
        sb.append(mSubscriberId);
        sb.append(",");

        sb.append("CarrierId:");
        sb.append(mCarrierId);
        sb.append(",");

        sb.append("NiddApn:");
        sb.append(mNiddApn);
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSubscriberId, mCarrierId, mNiddApn);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProvisionSubscriberId that = (ProvisionSubscriberId) o;
        return mSubscriberId.equals(that.mSubscriberId) && mCarrierId
                == that.mCarrierId && mNiddApn.equals(that.mNiddApn);
    }

    private void readFromParcel(Parcel in) {
        mSubscriberId = in.readString();
        mCarrierId = in.readInt();
        mNiddApn = in.readString();
    }
}
