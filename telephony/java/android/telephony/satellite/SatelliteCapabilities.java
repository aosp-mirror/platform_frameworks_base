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
import android.compat.annotation.UnsupportedAppUsage;
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
     * Whether UE needs to point to a satellite to send and receive data.
     */
    private boolean mIsPointingRequired;

    /**
     * The maximum number of bytes per datagram that can be sent over satellite.
     */
    private int mMaxBytesPerOutgoingDatagram;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public SatelliteCapabilities(Set<Integer> supportedRadioTechnologies,
            boolean isPointingRequired, int maxBytesPerOutgoingDatagram) {
        mSupportedRadioTechnologies = supportedRadioTechnologies == null
                ? new HashSet<>() : supportedRadioTechnologies;
        mIsPointingRequired = isPointingRequired;
        mMaxBytesPerOutgoingDatagram = maxBytesPerOutgoingDatagram;
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

        out.writeBoolean(mIsPointingRequired);
        out.writeInt(mMaxBytesPerOutgoingDatagram);
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

        sb.append("isPointingRequired:");
        sb.append(mIsPointingRequired);
        sb.append(",");

        sb.append("maxBytesPerOutgoingDatagram");
        sb.append(mMaxBytesPerOutgoingDatagram);
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
     * Get whether UE needs to point to a satellite to send and receive data.
     *
     * @return {@code true} if UE needs to point to a satellite to send and receive data and
     *         {@code false} otherwise.
     */
    public boolean isPointingRequired() {
        return mIsPointingRequired;
    }

    /**
     * The maximum number of bytes per datagram that can be sent over satellite.
     *
     * @return The maximum number of bytes per datagram that can be sent over satellite.
     */
    public int getMaxBytesPerOutgoingDatagram() {
        return mMaxBytesPerOutgoingDatagram;
    }

    private void readFromParcel(Parcel in) {
        mSupportedRadioTechnologies = new HashSet<>();
        int numSupportedRadioTechnologies = in.readInt();
        if (numSupportedRadioTechnologies > 0) {
            for (int i = 0; i < numSupportedRadioTechnologies; i++) {
                mSupportedRadioTechnologies.add(in.readInt());
            }
        }

        mIsPointingRequired = in.readBoolean();
        mMaxBytesPerOutgoingDatagram = in.readInt();
    }
}
