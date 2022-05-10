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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * A class that contains GNSS Automatic Gain Control (AGC) information.
 *
 * <p> AGC acts as a variable gain amplifier adjusting the power of the incoming signal. The AGC
 * level may be used to indicate potential interference. Higher gain (and/or lower input power)
 * shall be output as a positive number. Hence in cases of strong jamming, in the band of this
 * signal, this value will go more negative. This value must be consistent given the same level
 * of the incoming signal power.
 *
 * <p> Note: Different hardware designs (e.g. antenna, pre-amplification, or other RF HW
 * components) may also affect the typical output of this value on any given hardware design
 * in an open sky test - the important aspect of this output is that changes in this value are
 * indicative of changes on input signal power in the frequency band for this measurement.
 */
public final class GnssAutomaticGainControl implements Parcelable {
    private final double mLevelDb;
    private final int mConstellationType;
    private final long mCarrierFrequencyHz;

    /**
     * Creates a {@link GnssAutomaticGainControl} with a full list of parameters.
     */
    private GnssAutomaticGainControl(double levelDb, int constellationType,
            long carrierFrequencyHz) {
        mLevelDb = levelDb;
        mConstellationType = constellationType;
        mCarrierFrequencyHz = carrierFrequencyHz;
    }

    /**
     * Gets the Automatic Gain Control level in dB.
     */
    @FloatRange(from = -10000, to = 10000)
    public double getLevelDb() {
        return mLevelDb;
    }

    /**
     * Gets the constellation type.
     *
     * <p>The return value is one of those constants with {@code CONSTELLATION_} prefix in
     * {@link GnssStatus}.
     */
    @GnssStatus.ConstellationType
    public int getConstellationType() {
        return mConstellationType;
    }

    /**
     * Gets the carrier frequency of the tracked signal.
     *
     * <p>For example it can be the GPS central frequency for L1 = 1575.45 MHz, or L2 = 1227.60 MHz,
     * L5 = 1176.45 MHz, varying GLO channels, etc.
     *
     * @return the carrier frequency of the signal tracked in Hz.
     */
    @IntRange(from = 0)
    public long getCarrierFrequencyHz() {
        return mCarrierFrequencyHz;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flag) {
        parcel.writeDouble(mLevelDb);
        parcel.writeInt(mConstellationType);
        parcel.writeLong(mCarrierFrequencyHz);
    }

    @NonNull
    public static final Creator<GnssAutomaticGainControl> CREATOR =
            new Creator<GnssAutomaticGainControl>() {
                @Override
                @NonNull
                public GnssAutomaticGainControl createFromParcel(@NonNull Parcel parcel) {
                    return new GnssAutomaticGainControl(parcel.readDouble(), parcel.readInt(),
                            parcel.readLong());
                }

                @Override
                public GnssAutomaticGainControl[] newArray(int i) {
                    return new GnssAutomaticGainControl[i];
                }
            };

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("GnssAutomaticGainControl[");
        s.append("Level=").append(mLevelDb).append(" dB");
        s.append(" Constellation=").append(
                GnssStatus.constellationTypeToString(mConstellationType));
        s.append(" CarrierFrequency=").append(mCarrierFrequencyHz).append(" Hz");
        s.append(']');
        return s.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof GnssAutomaticGainControl)) {
            return false;
        }

        GnssAutomaticGainControl other = (GnssAutomaticGainControl) obj;
        if (Double.compare(mLevelDb, other.mLevelDb)
                != 0) {
            return false;
        }
        if (mConstellationType != other.mConstellationType) {
            return false;
        }
        if (mCarrierFrequencyHz != other.mCarrierFrequencyHz) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLevelDb, mConstellationType, mCarrierFrequencyHz);
    }

    /** Builder for {@link GnssAutomaticGainControl} */
    public static final class Builder {
        private double mLevelDb;
        private int mConstellationType;
        private long mCarrierFrequencyHz;

        /**
         * Constructs a {@link GnssAutomaticGainControl.Builder} instance.
         */
        public Builder() {
        }

        /**
         * Constructs a {@link GnssAutomaticGainControl.Builder} instance by copying a
         * {@link GnssAutomaticGainControl}.
         */
        public Builder(@NonNull GnssAutomaticGainControl agc) {
            mLevelDb = agc.getLevelDb();
            mConstellationType = agc.getConstellationType();
            mCarrierFrequencyHz = agc.getCarrierFrequencyHz();
        }

        /**
         * Sets the Automatic Gain Control level in dB.
         */
        @NonNull
        public Builder setLevelDb(@FloatRange(from = -10000, to = 10000) double levelDb) {
            Preconditions.checkArgument(levelDb >= -10000 && levelDb <= 10000);
            mLevelDb = levelDb;
            return this;
        }

        /**
         * Sets the constellation type.
         */
        @NonNull
        public Builder setConstellationType(@GnssStatus.ConstellationType int constellationType) {
            mConstellationType = constellationType;
            return this;
        }

        /**
         * Sets the Carrier frequency in Hz.
         */
        @NonNull public Builder setCarrierFrequencyHz(@IntRange(from = 0) long carrierFrequencyHz) {
            Preconditions.checkArgumentNonnegative(carrierFrequencyHz);
            mCarrierFrequencyHz = carrierFrequencyHz;
            return this;
        }

        /** Builds a {@link GnssAutomaticGainControl} instance as specified by this builder. */
        @NonNull
        public GnssAutomaticGainControl build() {
            return new GnssAutomaticGainControl(mLevelDb, mConstellationType, mCarrierFrequencyHz);
        }
    }
}
