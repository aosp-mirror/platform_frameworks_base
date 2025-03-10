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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.util.Objects;

/**
 * Represents the provisioning state of SatelliteSubscriberInfo.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
public final class SatelliteSubscriberProvisionStatus implements Parcelable {
    private SatelliteSubscriberInfo mSubscriberInfo;
    /** {@code true} mean the satellite subscriber is provisioned, {@code false} otherwise. */
    private boolean mProvisioned;

    /**
     * @hide
     */
    public SatelliteSubscriberProvisionStatus(@NonNull Builder builder) {
        mSubscriberInfo = builder.mSubscriberInfo;
        mProvisioned = builder.mProvisioned;
    }

    /**
     * Builder class for constructing SatelliteSubscriberProvisionStatus objects
     */
    public static final class Builder {
        private SatelliteSubscriberInfo mSubscriberInfo;
        private boolean mProvisioned;

        /**
         * Set the SatelliteSubscriberInfo and returns the Builder class.
         */
        @NonNull
        public Builder setSatelliteSubscriberInfo(
                @NonNull SatelliteSubscriberInfo satelliteSubscriberInfo) {
            mSubscriberInfo = satelliteSubscriberInfo;
            return this;
        }

        /**
         * Set the SatelliteSubscriberInfo's provisionStatus and returns the Builder class.
         */
        @NonNull
        public Builder setProvisioned(boolean provisioned) {
            mProvisioned = provisioned;
            return this;
        }

        /**
         * Returns SatelliteSubscriberProvisionStatus object.
         */
        @NonNull
        public SatelliteSubscriberProvisionStatus build() {
            return new SatelliteSubscriberProvisionStatus(this);
        }
    }

    private SatelliteSubscriberProvisionStatus(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(mSubscriberInfo, flags);
        out.writeBoolean(mProvisioned);
    }

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

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * SatelliteSubscriberInfo that has a provisioning state.
     * @return SatelliteSubscriberInfo.
     */
    public @NonNull SatelliteSubscriberInfo getSatelliteSubscriberInfo() {
        return mSubscriberInfo;
    }

    /**
     * SatelliteSubscriberInfo's provisioning state.
     * @return {@code true} means provisioning. {@code false} means deprovisioning.
     */
    public boolean isProvisioned() {
        return mProvisioned;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("SatelliteSubscriberInfo:");
        sb.append(mSubscriberInfo);
        sb.append(",");

        sb.append("ProvisionStatus:");
        sb.append(mProvisioned);
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSubscriberInfo, mProvisioned);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SatelliteSubscriberProvisionStatus)) return false;
        SatelliteSubscriberProvisionStatus that = (SatelliteSubscriberProvisionStatus) o;
        return Objects.equals(mSubscriberInfo, that.mSubscriberInfo)
                && mProvisioned == that.mProvisioned;
    }

    private void readFromParcel(Parcel in) {
        mSubscriberInfo = in.readParcelable(SatelliteSubscriberInfo.class.getClassLoader(),
                SatelliteSubscriberInfo.class);
        mProvisioned = in.readBoolean();
    }
}
