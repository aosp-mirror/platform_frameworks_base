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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * SatelliteSubscriberInfo
 *
 * Satellite Gateway client will use these subscriber ids to register with satellite gateway service
 * which identify user subscription with unique subscriber ids. These subscriber ids can be any
 * unique value like iccid, imsi or msisdn which is decided based upon carrier requirements.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
public final class SatelliteSubscriberInfo implements Parcelable {
    /** provision subscriberId */
    @NonNull
    private String mSubscriberId;
    /** carrier id */
    private int mCarrierId;

    /** apn */
    private String mNiddApn;
    private int mSubId;

    /** SubscriberId format is the ICCID. */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public static final int ICCID = 0;
    /** SubscriberId format is the 6 digit of IMSI + MSISDN. */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public static final int IMSI_MSISDN = 1;

    /** Type of subscriber id */
    @SubscriberIdType private int mSubscriberIdType;
    /** @hide */
    @IntDef(prefix = "SubscriberId_Type_", value = {
            ICCID,
            IMSI_MSISDN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SubscriberIdType {}

    private SatelliteSubscriberInfo(Parcel in) {
        readFromParcel(in);
    }

    public SatelliteSubscriberInfo(@NonNull Builder builder) {
        this.mSubscriberId = builder.mSubscriberId;
        this.mCarrierId = builder.mCarrierId;
        this.mNiddApn = builder.mNiddApn;
        this.mSubId = builder.mSubId;
        this.mSubscriberIdType = builder.mSubscriberIdType;
    }

    /**
     * Builder class for constructing SatelliteSubscriberInfo objects
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public static class Builder {
        @NonNull private String mSubscriberId;
        private int mCarrierId;
        @NonNull
        private String mNiddApn;
        private int mSubId;
        @SubscriberIdType
        private int mSubscriberIdType;

        /**
         * Set the SubscriberId and returns the Builder class.
         *
         * @hide
         */
        public Builder setSubscriberId(String subscriberId) {
            mSubscriberId = subscriberId;
            return this;
        }

        /**
         * Set the CarrierId and returns the Builder class.
         * @hide
         */
        @NonNull
        public Builder setCarrierId(int carrierId) {
            mCarrierId = carrierId;
            return this;
        }

        /**
         * Set the niddApn and returns the Builder class.
         * @hide
         */
        @NonNull
        public Builder setNiddApn(String niddApn) {
            mNiddApn = niddApn;
            return this;
        }

        /**
         * Set the subId and returns the Builder class.
         * @hide
         */
        @NonNull
        public Builder setSubId(int subId) {
            mSubId = subId;
            return this;
        }

        /**
         * Set the SubscriberIdType and returns the Builder class.
         * @hide
         */
        @NonNull
        public Builder setSubscriberIdType(@SubscriberIdType int subscriberIdType) {
            mSubscriberIdType = subscriberIdType;
            return this;
        }

        /**
         * Returns SatelliteSubscriberInfo object.
         * @hide
         */
        @NonNull
        public SatelliteSubscriberInfo build() {
            return new SatelliteSubscriberInfo(this);
        }
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
        out.writeInt(mSubId);
        out.writeInt(mSubscriberIdType);
    }

    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public static final @android.annotation.NonNull Creator<SatelliteSubscriberInfo> CREATOR =
            new Creator<SatelliteSubscriberInfo>() {
                @Override
                public SatelliteSubscriberInfo createFromParcel(Parcel in) {
                    return new SatelliteSubscriberInfo(in);
                }

                @Override
                public SatelliteSubscriberInfo[] newArray(int size) {
                    return new SatelliteSubscriberInfo[size];
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

    /**
     * @return subId.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public int getSubId() {
        return mSubId;
    }

    /**
     * @return subscriberIdType.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public @SubscriberIdType int getSubscriberIdType() {
        return mSubscriberIdType;
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
        sb.append(",");

        sb.append("SubId:");
        sb.append(mSubId);
        sb.append(",");

        sb.append("SubscriberIdType:");
        sb.append(mSubscriberIdType);
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSubscriberId, mCarrierId, mNiddApn, mSubId, mSubscriberIdType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SatelliteSubscriberInfo)) return false;
        SatelliteSubscriberInfo that = (SatelliteSubscriberInfo) o;
        return Objects.equals(mSubscriberId, that.mSubscriberId) && mCarrierId == that.mCarrierId
                && Objects.equals(mNiddApn, that.mNiddApn) && mSubId == that.mSubId
                && mSubscriberIdType == that.mSubscriberIdType;
    }

    private void readFromParcel(Parcel in) {
        mSubscriberId = in.readString();
        mCarrierId = in.readInt();
        mNiddApn = in.readString();
        mSubId = in.readInt();
        mSubscriberIdType = in.readInt();
    }
}
