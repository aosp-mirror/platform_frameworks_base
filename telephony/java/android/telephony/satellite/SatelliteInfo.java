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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.android.internal.telephony.flags.Flags;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SatelliteInfo stores a satellite's identification, position, and frequency information
 * facilitating efficient satellite communications.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
public final class SatelliteInfo implements Parcelable {
    /**
     * Unique identification number for the satellite.
     * This ID is used to distinguish between different satellites in the network.
     */
    @NonNull
    private UUID mId;

    /**
     * Position information of a geostationary satellite.
     * This includes the longitude and altitude of the satellite.
     * If the SatellitePosition is invalid,
     * longitudeDegree and altitudeKm will be represented as DOUBLE.NaN.
     */
    @NonNull
    private SatellitePosition mPosition;

    /**
     * The frequency band list to scan. Bands and earfcns won't overlap.
     * Bands will be filled only if the whole band is needed.
     * Maximum length of the vector is 8.
     */
    private List<Integer> mBandList;

    /**
     * EARFCN (E-UTRA Absolute Radio Frequency Channel Number) range list
     * The supported frequency range list.
     * Maximum length of the vector is 8.
     */
    private final List<EarfcnRange> mEarfcnRangeList;

    /**
     * @hide
     */
    protected SatelliteInfo(Parcel in) {
        ParcelUuid parcelUuid = in.readParcelable(
                ParcelUuid.class.getClassLoader(), ParcelUuid.class);
        if (parcelUuid != null) {
            mId = parcelUuid.getUuid();
        }
        mPosition = in.readParcelable(SatellitePosition.class.getClassLoader(),
                SatellitePosition.class);
        mBandList = new ArrayList<>();
        in.readList(mBandList, Integer.class.getClassLoader(), Integer.class);
        mEarfcnRangeList = in.createTypedArrayList(EarfcnRange.CREATOR);
    }

    /**
     * Constructor for {@link SatelliteInfo}.
     *
     * @param satelliteId       The ID of the satellite.
     * @param satellitePosition The {@link SatellitePosition} of the satellite.
     * @param bandList          The list of frequency bandList supported by the satellite.
     * @param earfcnRanges      The list of {@link EarfcnRange} objects representing the EARFCN
     *                          ranges supported by the satellite.
     * @hide
     */
    public SatelliteInfo(@NonNull UUID satelliteId, @NonNull SatellitePosition satellitePosition,
            @NonNull List<Integer> bandList, @NonNull List<EarfcnRange> earfcnRanges) {
        mId = satelliteId;
        mPosition = satellitePosition;
        mBandList = bandList;
        mEarfcnRangeList = earfcnRanges;
    }

    @NonNull
    public static final Creator<SatelliteInfo> CREATOR = new Creator<SatelliteInfo>() {
        @Override
        public SatelliteInfo createFromParcel(Parcel in) {
            return new SatelliteInfo(in);
        }

        @Override
        public SatelliteInfo[] newArray(int size) {
            return new SatelliteInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(new ParcelUuid(mId), flags);
        dest.writeParcelable(mPosition, flags);
        dest.writeList(mBandList);
        dest.writeTypedList(mEarfcnRangeList);
    }

    /**
     * Returns the ID of the satellite.
     *
     * @return The satellite ID.
     */
    @NonNull
    public UUID getSatelliteId() {
        return mId;
    }

    /**
     * Returns the position of the satellite.
     * Position information of a geostationary satellite.
     * This includes the longitude and altitude of the satellite.
     * If the SatellitePosition is invalid,
     * longitudeDegree and altitudeKm will be represented as DOUBLE.NaN.
     *
     * @return The {@link SatellitePosition} of the satellite.
     */
    @NonNull
    public SatellitePosition getSatellitePosition() {
        return mPosition;
    }

    /**
     * Returns the list of frequency bands supported by the satellite.
     *
     * Refer specification 3GPP TS 36.101 for detailed information on frequency bands.
     *
     * @return The list of frequency bands.
     */
    @NonNull
    public List<Integer> getBands() {
        return mBandList;
    }

    /**
     * Returns the list of EARFCN ranges supported by the satellite.
     *
     * @return The list of {@link EarfcnRange} objects.
     */
    @NonNull
    public List<EarfcnRange> getEarfcnRanges() {
        return mEarfcnRangeList;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SatelliteInfo that)) return false;

        return mId.equals(that.mId)
                && Objects.equals(mPosition, that.mPosition)
                && Objects.equals(mBandList, that.mBandList)
                && mEarfcnRangeList.equals(that.mEarfcnRangeList);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mId, mPosition, mEarfcnRangeList);
        result = 31 * result + Objects.hashCode(mBandList);
        return result;
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SatelliteInfo{");
        sb.append("mId=").append(mId);
        sb.append(", mPosition=").append(mPosition);
        sb.append(", mBandList=").append(mBandList);
        sb.append(", mEarfcnRangeList=").append(mEarfcnRangeList);
        sb.append('}');
        return sb.toString();
    }
}
