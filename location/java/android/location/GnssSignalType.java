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

package android.location;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * This class represents a GNSS signal type.
 */
public final class GnssSignalType implements Parcelable {

    /**
     * Creates a {@link GnssSignalType} with a full list of parameters.
     *
     * @param constellationType the constellation type
     * @param carrierFrequencyHz the carrier frequency in Hz
     * @param codeType the code type as defined in {@link GnssMeasurement#getCodeType()}
     */
    @NonNull
    public static GnssSignalType create(@GnssStatus.ConstellationType int constellationType,
            @FloatRange(from = 0.0f, fromInclusive = false) double carrierFrequencyHz,
            @NonNull String codeType) {
        Preconditions.checkArgument(carrierFrequencyHz > 0,
                "carrierFrequencyHz must be greater than 0.");
        Objects.requireNonNull(codeType);
        return new GnssSignalType(constellationType, carrierFrequencyHz, codeType);
    }

    @GnssStatus.ConstellationType
    private final int mConstellationType;
    @FloatRange(from = 0.0f, fromInclusive = false)
    private final double mCarrierFrequencyHz;
    @NonNull
    private final String mCodeType;

    /**
     * Creates a {@link GnssSignalType} with a full list of parameters.
     */
    private GnssSignalType(@GnssStatus.ConstellationType int constellationType,
            double carrierFrequencyHz, @NonNull String codeType) {
        this.mConstellationType = constellationType;
        this.mCarrierFrequencyHz = carrierFrequencyHz;
        this.mCodeType = codeType;
    }

    /** Returns the constellation type. */
    @GnssStatus.ConstellationType
    public int getConstellationType() {
        return mConstellationType;
    }

    /** Returns the carrier frequency in Hz. */
    @FloatRange(from = 0.0f, fromInclusive = false)
    public double getCarrierFrequencyHz() {
        return mCarrierFrequencyHz;
    }

    /**
     * Return the code type.
     *
     * @see GnssMeasurement#getCodeType()
     */
    @NonNull
    public String getCodeType() {
        return mCodeType;
    }

    @NonNull
    public static final Parcelable.Creator<GnssSignalType> CREATOR =
            new Parcelable.Creator<GnssSignalType>() {
                @Override
                @NonNull
                public GnssSignalType createFromParcel(@NonNull Parcel parcel) {
                    return new GnssSignalType(parcel.readInt(), parcel.readDouble(),
                            parcel.readString());
                }

                @Override
                public GnssSignalType[] newArray(int i) {
                    return new GnssSignalType[i];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mConstellationType);
        parcel.writeDouble(mCarrierFrequencyHz);
        parcel.writeString(mCodeType);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("GnssSignalType[");
        s.append("Constellation=").append(mConstellationType);
        s.append(", CarrierFrequencyHz=").append(mCarrierFrequencyHz);
        s.append(", CodeType=").append(mCodeType);
        s.append(']');
        return s.toString();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (obj instanceof GnssSignalType) {
            GnssSignalType other = (GnssSignalType) obj;
            return mConstellationType == other.mConstellationType
                    && Double.compare(mCarrierFrequencyHz, other.mCarrierFrequencyHz) == 0
                    && mCodeType.equals(other.mCodeType);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mConstellationType, mCarrierFrequencyHz, mCodeType);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
