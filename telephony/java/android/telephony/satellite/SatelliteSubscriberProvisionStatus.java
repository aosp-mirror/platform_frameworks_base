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
 * Represents the provisioning state of SatelliteSubscriberInfo.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
public class SatelliteSubscriberProvisionStatus implements Parcelable {
    private SatelliteSubscriberInfo mSubscriberInfo;
    /** {@code true} mean the satellite subscriber is provisioned, {@code false} otherwise. */
    private boolean mProvisionStatus;

    public SatelliteSubscriberProvisionStatus(@NonNull Builder builder) {
        mSubscriberInfo = builder.mSubscriberInfo;
        mProvisionStatus = builder.mProvisionStatus;
    }

    /**
     * Builder class for constructing SatelliteSubscriberProvisionStatus objects
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public static class Builder {
        private SatelliteSubscriberInfo mSubscriberInfo;
        private boolean mProvisionStatus;

        /**
         * Set the SatelliteSubscriberInfo and returns the Builder class.
         * @hide
         */
        public Builder setSatelliteSubscriberInfo(SatelliteSubscriberInfo satelliteSubscriberInfo) {
            mSubscriberInfo = satelliteSubscriberInfo;
            return this;
        }

        /**
         * Set the SatelliteSubscriberInfo's provisionStatus and returns the Builder class.
         * @hide
         */
        @NonNull
        public Builder setProvisionStatus(boolean provisionStatus) {
            mProvisionStatus = provisionStatus;
            return this;
        }

        /**
         * Returns SatelliteSubscriberProvisionStatus object.
         * @hide
         */
        @NonNull
        public SatelliteSubscriberProvisionStatus build() {
            return new SatelliteSubscriberProvisionStatus(this);
        }
    }

    private SatelliteSubscriberProvisionStatus(Parcel in) {
        readFromParcel(in);
    }

    /**
     * @hide
     */
    @Override
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(mSubscriberInfo, flags);
        out.writeBoolean(mProvisionStatus);
    }

    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public static final @android.annotation.NonNull Creator<SatelliteSubscriberProvisionStatus>
            CREATOR =
            new Creator<SatelliteSubscriberProvisionStatus>() {
                @Override
                public SatelliteSubscriberProvisionStatus createFromParcel(Parcel in) {
                    return new SatelliteSubscriberProvisionStatus(in);
                }

                @Override
                public SatelliteSubscriberProvisionStatus[] newArray(int size) {
                    return new SatelliteSubscriberProvisionStatus[size];
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
     * SatelliteSubscriberInfo that has a provisioning state.
     * @return SatelliteSubscriberInfo.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public @NonNull SatelliteSubscriberInfo getSatelliteSubscriberInfo() {
        return mSubscriberInfo;
    }

    /**
     * SatelliteSubscriberInfo's provisioning state.
     * @return {@code true} means provisioning. {@code false} means deprovisioning.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    public @NonNull  boolean getProvisionStatus() {
        return mProvisionStatus;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("SatelliteSubscriberInfo:");
        sb.append(mSubscriberInfo);
        sb.append(",");

        sb.append("ProvisionStatus:");
        sb.append(mProvisionStatus);
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSubscriberInfo, mProvisionStatus);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SatelliteSubscriberProvisionStatus)) return false;
        SatelliteSubscriberProvisionStatus that = (SatelliteSubscriberProvisionStatus) o;
        return Objects.equals(mSubscriberInfo, that.mSubscriberInfo)
                && mProvisionStatus == that.mProvisionStatus;
    }

    private void readFromParcel(Parcel in) {
        mSubscriberInfo = in.readParcelable(SatelliteSubscriberInfo.class.getClassLoader(),
                SatelliteSubscriberInfo.class);
        mProvisionStatus = in.readBoolean();
    }
}
