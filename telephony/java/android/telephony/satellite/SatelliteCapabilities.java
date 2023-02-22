/*
 * Copyright (C) 2022 The Android Open Source Project
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

import java.util.HashSet;
import java.util.Set;

/**
 * @hide
 */
public final class SatelliteCapabilities implements Parcelable {
    /**
     * List of technologies supported by the satellite modem.
     */
    @NonNull @SatelliteManager.NTRadioTechnology private Set<Integer> mSupportedRadioTechnologies;

    /**
     * Whether satellite modem is always on.
     * This indicates the power impact of keeping it on is very minimal.
     */
    private boolean mIsAlwaysOn;

    /**
     * Whether UE needs to point to a satellite to send and receive data.
     */
    private boolean mNeedsPointingToSatellite;

    /**
     * Whether UE needs a separate SIM profile to communicate with the satellite network.
     */
    private boolean mNeedsSeparateSimProfile;

    /**
     * @hide
     */
    public SatelliteCapabilities(Set<Integer> supportedRadioTechnologies, boolean isAlwaysOn,
            boolean needsPointingToSatellite, boolean needsSeparateSimProfile) {
        mSupportedRadioTechnologies = supportedRadioTechnologies == null
                ? new HashSet<>() : supportedRadioTechnologies;
        mIsAlwaysOn = isAlwaysOn;
        mNeedsPointingToSatellite = needsPointingToSatellite;
        mNeedsSeparateSimProfile = needsSeparateSimProfile;
    }

    private SatelliteCapabilities(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        if (mSupportedRadioTechnologies != null && !mSupportedRadioTechnologies.isEmpty()) {
            out.writeInt(mSupportedRadioTechnologies.size());
            for (int technology : mSupportedRadioTechnologies) {
                out.writeInt(technology);
            }
        } else {
            out.writeInt(0);
        }

        out.writeBoolean(mIsAlwaysOn);
        out.writeBoolean(mNeedsPointingToSatellite);
        out.writeBoolean(mNeedsSeparateSimProfile);
    }

    @NonNull public static final Creator<SatelliteCapabilities> CREATOR = new Creator<>() {
        @Override
        public SatelliteCapabilities createFromParcel(Parcel in) {
            return new SatelliteCapabilities(in);
        }

        @Override
        public SatelliteCapabilities[] newArray(int size) {
            return new SatelliteCapabilities[size];
        }
    };

    @Override
    @NonNull public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("SupportedRadioTechnology:");
        if (mSupportedRadioTechnologies != null && !mSupportedRadioTechnologies.isEmpty()) {
            for (int technology : mSupportedRadioTechnologies) {
                sb.append(technology);
                sb.append(",");
            }
        } else {
            sb.append("none,");
        }

        sb.append("isAlwaysOn:");
        sb.append(mIsAlwaysOn);
        sb.append(",");

        sb.append("needsPointingToSatellite:");
        sb.append(mNeedsPointingToSatellite);
        sb.append(",");

        sb.append("needsSeparateSimProfile:");
        sb.append(mNeedsSeparateSimProfile);
        return sb.toString();
    }

    /**
     * @return The list of technologies supported by the satellite modem.
     */
    @NonNull @SatelliteManager.NTRadioTechnology public Set<Integer>
            getSupportedRadioTechnologies() {
        return mSupportedRadioTechnologies;
    }

    /**
     * Get whether the satellite modem is always on.
     * This indicates the power impact of keeping it on is very minimal.
     *
     * @return {@code true} if the satellite modem is always on and {@code false} otherwise.
     */
    public boolean isAlwaysOn() {
        return mIsAlwaysOn;
    }

    /**
     * Get whether UE needs to point to a satellite to send and receive data.
     *
     * @return {@code true} if UE needs to pointing to a satellite to send and receive data and
     *         {@code false} otherwise.
     */
    public boolean needsPointingToSatellite() {
        return mNeedsPointingToSatellite;
    }

    /**
     * Get whether UE needs a separate SIM profile to communicate with the satellite network.
     *
     * @return {@code true} if UE needs a separate SIM profile to comunicate with the satellite
     *         network and {@code false} otherwise.
     */
    public boolean needsSeparateSimProfile() {
        return mNeedsSeparateSimProfile;
    }

    private void readFromParcel(Parcel in) {
        mSupportedRadioTechnologies = new HashSet<>();
        int numSupportedRadioTechnologies = in.readInt();
        if (numSupportedRadioTechnologies > 0) {
            for (int i = 0; i < numSupportedRadioTechnologies; i++) {
                mSupportedRadioTechnologies.add(in.readInt());
            }
        }

        mIsAlwaysOn = in.readBoolean();
        mNeedsPointingToSatellite = in.readBoolean();
        mNeedsSeparateSimProfile = in.readBoolean();
    }
}
