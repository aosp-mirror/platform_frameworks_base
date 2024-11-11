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
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.android.internal.telephony.flags.Flags;

import java.util.List;
import java.util.UUID;

/**
 * SatelliteInfo stores a satellite's identification, position, and frequency information
 * facilitating efficient satellite communications.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
public class SatelliteInfo implements Parcelable {
    /**
     * Unique identification number for the satellite.
     * This ID is used to distinguish between different satellites in the network.
     */
    @NonNull
    private UUID mId;

    /**
     * Position information of a satellite.
     * This includes the longitude and altitude of the satellite.
     */
    private SatellitePosition mPosition;

    /**
     * The frequency bands to scan. Bands and earfcns won't overlap.
     * Bands will be filled only if the whole band is needed.
     * Maximum length of the vector is 8.
     */
    private int[] mBands;

    /**
     * EARFCN (E-UTRA Absolute Radio Frequency Channel Number) Ranges
     * The supported frequency range list.
     * Maximum length of the vector is 8.
     */
    private final List<EarfcnRange> mEarfcnRangeList;

    protected SatelliteInfo(Parcel in) {
        ParcelUuid parcelUuid = in.readParcelable(
                ParcelUuid.class.getClassLoader(), ParcelUuid.class);
        if (parcelUuid != null) {
            mId = parcelUuid.getUuid();
        }
        mPosition = in.readParcelable(SatellitePosition.class.getClassLoader(),
                SatellitePosition.class);
        int numBands = in.readInt();
        mBands = new int[numBands];
        if (numBands > 0) {
            for (int i = 0; i < numBands; i++) {
                mBands[i] = in.readInt();
            }
        }
        mEarfcnRangeList = in.createTypedArrayList(EarfcnRange.CREATOR);
    }

    /**
     * Constructor for {@link SatelliteInfo}.
     *
     * @param satelliteId       The ID of the satellite.
     * @param satellitePosition The {@link SatellitePosition} of the satellite.
     * @param bands             The list of frequency bands supported by the satellite.
     * @param earfcnRanges      The list of {@link EarfcnRange} objects representing the EARFCN
     *                          ranges supported by the satellite.
     */
    public SatelliteInfo(@NonNull UUID satelliteId, @NonNull SatellitePosition satellitePosition,
            @NonNull int[] bands, @NonNull List<EarfcnRange> earfcnRanges) {
        mId = satelliteId;
        mPosition = satellitePosition;
        mBands = bands;
        mEarfcnRangeList = earfcnRanges;
    }

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
        if (mBands != null && mBands.length > 0) {
            dest.writeInt(mBands.length);
            dest.writeIntArray(mBands);
        } else {
            dest.writeInt(0);
        }
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
     *
     * @return The {@link SatellitePosition} of the satellite.
     */
    public SatellitePosition getSatellitePosition() {
        return mPosition;
    }

    /**
     * Returns the list of frequency bands supported by the satellite.
     *
     * @return The list of frequency bands.
     */
    @NonNull
    public int[] getBands() {
        return mBands;
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
}
