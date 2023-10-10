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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SatelliteCapabilities is used to represent the capabilities of the satellite service
 * received from satellite modem.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
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
     * Antenna Position received from satellite modem which gives information about antenna
     * direction to be used with satellite communication and suggested device hold positions.
     * Map key: {@link SatelliteManager.DeviceHoldPosition} value: AntennaPosition
     */
    @NonNull
    private Map<Integer, AntennaPosition> mAntennaPositionMap;

    /**
     * @hide
     */
    public SatelliteCapabilities(@Nullable Set<Integer> supportedRadioTechnologies,
            boolean isPointingRequired, int maxBytesPerOutgoingDatagram,
            @NonNull Map<Integer, AntennaPosition> antennaPositionMap) {
        mSupportedRadioTechnologies = supportedRadioTechnologies == null
                ? new HashSet<>() : supportedRadioTechnologies;
        mIsPointingRequired = isPointingRequired;
        mMaxBytesPerOutgoingDatagram = maxBytesPerOutgoingDatagram;
        mAntennaPositionMap = antennaPositionMap;
    }

    private SatelliteCapabilities(Parcel in) {
        readFromParcel(in);
    }

    @Override
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public int describeContents() {
        return 0;
    }

    @Override
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
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

        if (mAntennaPositionMap != null && !mAntennaPositionMap.isEmpty()) {
            int size = mAntennaPositionMap.size();
            out.writeInt(size);
            for (Map.Entry<Integer, AntennaPosition> entry : mAntennaPositionMap.entrySet()) {
                out.writeInt(entry.getKey());
                out.writeParcelable(entry.getValue(), flags);
            }
        } else {
            out.writeInt(0);
        }
    }

    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
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

        sb.append("maxBytesPerOutgoingDatagram:");
        sb.append(mMaxBytesPerOutgoingDatagram);
        sb.append(",");

        sb.append("antennaPositionMap:");
        sb.append(mAntennaPositionMap);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SatelliteCapabilities that = (SatelliteCapabilities) o;
        return Objects.equals(mSupportedRadioTechnologies, that.mSupportedRadioTechnologies)
                && mIsPointingRequired == that.mIsPointingRequired
                && mMaxBytesPerOutgoingDatagram == that.mMaxBytesPerOutgoingDatagram
                && Objects.equals(mAntennaPositionMap, that.mAntennaPositionMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSupportedRadioTechnologies, mIsPointingRequired,
                mMaxBytesPerOutgoingDatagram, mAntennaPositionMap);
    }

    /**
     * @return The list of technologies supported by the satellite modem.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
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
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public boolean isPointingRequired() {
        return mIsPointingRequired;
    }

    /**
     * The maximum number of bytes per datagram that can be sent over satellite.
     *
     * @return The maximum number of bytes per datagram that can be sent over satellite.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public int getMaxBytesPerOutgoingDatagram() {
        return mMaxBytesPerOutgoingDatagram;
    }

    /**
     * Antenna Position received from satellite modem which gives information about antenna
     * direction to be used with satellite communication and suggested device hold positions.
     * @return Map key: {@link SatelliteManager.DeviceHoldPosition} value: AntennaPosition
     */
    @NonNull
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public Map<Integer, AntennaPosition> getAntennaPositionMap() {
        return mAntennaPositionMap;
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

        mAntennaPositionMap = new HashMap<>();
        int antennaPositionMapSize = in.readInt();
        for (int i = 0; i < antennaPositionMapSize; i++) {
            int key = in.readInt();
            AntennaPosition antennaPosition = in.readParcelable(
                    AntennaPosition.class.getClassLoader(), AntennaPosition.class);
            mAntennaPositionMap.put(key, antennaPosition);
        }
    }
}
