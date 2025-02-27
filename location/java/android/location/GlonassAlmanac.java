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

package android.location;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A class contains Glonass almanac data.
 *
 * <p>This is defined in Glonass ICD v5.1 section 4.5.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class GlonassAlmanac implements Parcelable {

    /** Almanac issue date in milliseconds (UTC) */
    private final long mIssueDateMillis;

    /** List of GlonassSatelliteAlmanacs. */
    @NonNull private final List<GlonassSatelliteAlmanac> mSatelliteAlmanacs;

    /**
     * Constructor for GlonassAlmanac.
     *
     * @param issueDateMillis The almanac issue date in milliseconds (UTC).
     * @param satelliteAlmanacs The list of GlonassSatelliteAlmanac.
     */
    public GlonassAlmanac(
            @IntRange(from = 0) long issueDateMillis,
            @NonNull List<GlonassSatelliteAlmanac> satelliteAlmanacs) {
        Preconditions.checkArgument(issueDateMillis >= 0);
        Preconditions.checkNotNull(satelliteAlmanacs, "satelliteAlmanacs cannot be null");
        mIssueDateMillis = issueDateMillis;
        mSatelliteAlmanacs = Collections.unmodifiableList(new ArrayList<>(satelliteAlmanacs));
    }

    /** Returns the almanac issue date in milliseconds (UTC). */
    @IntRange(from = 0)
    public long getIssueDateMillis() {
        return mIssueDateMillis;
    }

    /** Returns the list of GlonassSatelliteAlmanacs. */
    @NonNull
    public List<GlonassSatelliteAlmanac> getSatelliteAlmanacs() {
        return mSatelliteAlmanacs;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mIssueDateMillis);
        dest.writeTypedList(mSatelliteAlmanacs);
    }

    public static final @NonNull Parcelable.Creator<GlonassAlmanac> CREATOR =
            new Parcelable.Creator<GlonassAlmanac>() {
                @Override
                public GlonassAlmanac createFromParcel(@NonNull Parcel in) {
                    long issueDateMillis = in.readLong();
                    List<GlonassSatelliteAlmanac> satelliteAlmanacs = new ArrayList<>();
                    in.readTypedList(satelliteAlmanacs, GlonassSatelliteAlmanac.CREATOR);
                    return new GlonassAlmanac(issueDateMillis, satelliteAlmanacs);
                }

                @Override
                public GlonassAlmanac[] newArray(int size) {
                    return new GlonassAlmanac[size];
                }
            };

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("GlonassAlmanac[");
        builder.append("issueDateMillis = ").append(mIssueDateMillis);
        builder.append(", satelliteAlmanacs = ").append(mSatelliteAlmanacs);
        builder.append("]");
        return builder.toString();
    }

    /**
     * A class contains Glonass satellite almanac data.
     *
     * <p>This is defined in Glonass ICD v5.1 section 4.5.
     */
    public static final class GlonassSatelliteAlmanac implements Parcelable {
        /** Slot number. */
        private final int mSlotNumber;

        /** Satellite health information (0=healthy, 1=unhealthy). */
        private final int mSvHealth;

        /** Frequency channel number. */
        private final int mFreqChannel;

        /** Coarse value of satellite time correction to GLONASS time in seconds. */
        private final double mTau;

        /** Time of first ascending node passage of satellite in seconds. */
        private final double mTLambda;

        /** Longitude of the first ascending node in semi-circles. */
        private final double mLambda;

        /** Correction to the mean value of inclination in semi-circles. */
        private final double mDeltaI;

        /** Correction to the mean value of the draconian period in seconds per orbital period */
        private final double mDeltaT;

        /** Rate of change of draconian period in seconds per orbital period squared. */
        private final double mDeltaTDot;

        /** Eccentricity. */
        private final double mEccentricity;

        /** Argument of perigee in radians. */
        private final double mOmega;

        private GlonassSatelliteAlmanac(Builder builder) {
            // Allow slotNumber beyond the range to support potential future extensibility.
            Preconditions.checkArgument(builder.mSlotNumber >= 1);
            // Allow svHealth beyond the range to support potential future extensibility.
            Preconditions.checkArgument(builder.mSvHealth >= 0);
            Preconditions.checkArgumentInRange(builder.mFreqChannel, 0, 31, "FreqChannel");
            Preconditions.checkArgumentInRange(builder.mTau, -1.9e-3f, 1.9e-3f, "Tau");
            Preconditions.checkArgumentInRange(builder.mTLambda, 0.0f, 44100.0f, "TLambda");
            Preconditions.checkArgumentInRange(builder.mLambda, -1.0f, 1.0f, "Lambda");
            Preconditions.checkArgumentInRange(builder.mDeltaI, -0.067f, 0.067f, "DeltaI");
            Preconditions.checkArgumentInRange(builder.mDeltaT, -3600.0f, 3600.0f, "DeltaT");
            Preconditions.checkArgumentInRange(builder.mDeltaTDot, -0.004f, 0.004f, "DeltaTDot");
            Preconditions.checkArgumentInRange(builder.mEccentricity, 0.0f, 0.03f, "Eccentricity");
            Preconditions.checkArgumentInRange(builder.mOmega, -1.0f, 1.0f, "Omega");
            mSlotNumber = builder.mSlotNumber;
            mSvHealth = builder.mSvHealth;
            mFreqChannel = builder.mFreqChannel;
            mTau = builder.mTau;
            mTLambda = builder.mTLambda;
            mLambda = builder.mLambda;
            mDeltaI = builder.mDeltaI;
            mDeltaT = builder.mDeltaT;
            mDeltaTDot = builder.mDeltaTDot;
            mEccentricity = builder.mEccentricity;
            mOmega = builder.mOmega;
        }

        /** Returns the slot number. */
        @IntRange(from = 1, to = 25)
        public int getSlotNumber() {
            return mSlotNumber;
        }

        /** Returns the Satellite health information (0=healthy, 1=unhealthy). */
        @IntRange(from = 0, to = 1)
        public int getSvHealth() {
            return mSvHealth;
        }

        /** Returns the frequency channel number. */
        @IntRange(from = 0, to = 31)
        public int getFreqChannel() {
            return mFreqChannel;
        }

        /** Returns the coarse value of satellite time correction to GLONASS time in seconds. */
        @FloatRange(from = -1.9e-3f, to = 1.9e-3f)
        public double getTau() {
            return mTau;
        }

        /** Returns the time of first ascending node passage of satellite in seconds. */
        @FloatRange(from = 0.0f, to = 44100.0f)
        public double getTLambda() {
            return mTLambda;
        }

        /** Returns the longitude of the first ascending node in semi-circles. */
        @FloatRange(from = -1.0f, to = 1.0f)
        public double getLambda() {
            return mLambda;
        }

        /** Returns the correction to the mean value of inclination in semi-circles. */
        @FloatRange(from = -0.067f, to = 0.067f)
        public double getDeltaI() {
            return mDeltaI;
        }

        /**
         * Returns the correction to the mean value of the draconian period in seconds per orbital
         * period
         */
        @FloatRange(from = -3600.0f, to = 3600.0f)
        public double getDeltaT() {
            return mDeltaT;
        }

        /** Returns the rate of change of draconian period in seconds per orbital period squared. */
        @FloatRange(from = -0.004f, to = 0.004f)
        public double getDeltaTDot() {
            return mDeltaTDot;
        }

        /** Returns the eccentricity. */
        @FloatRange(from = 0.0f, to = 0.03f)
        public double getEccentricity() {
            return mEccentricity;
        }

        /** Returns the argument of perigee in radians. */
        @FloatRange(from = -1.0f, to = 1.0f)
        public double getOmega() {
            return mOmega;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mSlotNumber);
            dest.writeInt(mSvHealth);
            dest.writeInt(mFreqChannel);
            dest.writeDouble(mTau);
            dest.writeDouble(mTLambda);
            dest.writeDouble(mLambda);
            dest.writeDouble(mDeltaI);
            dest.writeDouble(mDeltaT);
            dest.writeDouble(mDeltaTDot);
            dest.writeDouble(mEccentricity);
            dest.writeDouble(mOmega);
        }

        public static final @NonNull Parcelable.Creator<GlonassSatelliteAlmanac> CREATOR =
                new Parcelable.Creator<GlonassSatelliteAlmanac>() {
                    @Override
                    public GlonassSatelliteAlmanac createFromParcel(@NonNull Parcel source) {
                        return new GlonassSatelliteAlmanac.Builder()
                                .setSlotNumber(source.readInt())
                                .setSvHealth(source.readInt())
                                .setFreqChannel(source.readInt())
                                .setTau(source.readDouble())
                                .setTLambda(source.readDouble())
                                .setLambda(source.readDouble())
                                .setDeltaI(source.readDouble())
                                .setDeltaT(source.readDouble())
                                .setDeltaTDot(source.readDouble())
                                .setEccentricity(source.readDouble())
                                .setOmega(source.readDouble())
                                .build();
                    }

                    @Override
                    public GlonassSatelliteAlmanac[] newArray(int size) {
                        return new GlonassSatelliteAlmanac[size];
                    }
                };

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GlonassSatelliteAlmanac[");
            builder.append("slotNumber = ").append(mSlotNumber);
            builder.append(", svHealth = ").append(mSvHealth);
            builder.append(", freqChannel = ").append(mFreqChannel);
            builder.append(", tau = ").append(mTau);
            builder.append(", tLambda = ").append(mTLambda);
            builder.append(", lambda = ").append(mLambda);
            builder.append(", deltaI = ").append(mDeltaI);
            builder.append(", deltaT = ").append(mDeltaT);
            builder.append(", deltaTDot = ").append(mDeltaTDot);
            builder.append(", eccentricity = ").append(mEccentricity);
            builder.append(", omega = ").append(mOmega);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link GlonassSatelliteAlmanac}. */
        public static final class Builder {
            private int mSlotNumber;
            private int mSvHealth;
            private int mFreqChannel;
            private double mTau;
            private double mTLambda;
            private double mLambda;
            private double mDeltaI;
            private double mDeltaT;
            private double mDeltaTDot;
            private double mEccentricity;
            private double mOmega;

            /** Sets the slot number. */
            @NonNull
            public Builder setSlotNumber(@IntRange(from = 1, to = 25) int slotNumber) {
                mSlotNumber = slotNumber;
                return this;
            }

            /** Sets the Satellite health information (0=healthy, 1=unhealthy). */
            @NonNull
            public Builder setSvHealth(@IntRange(from = 0, to = 1) int svHealth) {
                mSvHealth = svHealth;
                return this;
            }

            /** Sets the frequency channel number. */
            @NonNull
            public Builder setFreqChannel(@IntRange(from = 0, to = 31) int freqChannel) {
                mFreqChannel = freqChannel;
                return this;
            }

            /** Sets the coarse value of satellite time correction to GLONASS time in seconds. */
            @NonNull
            public Builder setTau(@FloatRange(from = -1.9e-3f, to = 1.9e-3f) double tau) {
                mTau = tau;
                return this;
            }

            /** Sets the time of first ascending node passage of satellite in seconds. */
            @NonNull
            public Builder setTLambda(@FloatRange(from = 0.0f, to = 44100.0f) double tLambda) {
                mTLambda = tLambda;
                return this;
            }

            /** Sets the longitude of the first ascending node in semi-circles. */
            @NonNull
            public Builder setLambda(@FloatRange(from = -1.0f, to = 1.0f) double lambda) {
                mLambda = lambda;
                return this;
            }

            /** Sets the correction to the mean value of inclination in semi-circles. */
            @NonNull
            public Builder setDeltaI(@FloatRange(from = -0.067f, to = 0.067f) double deltaI) {
                mDeltaI = deltaI;
                return this;
            }

            /**
             * Sets the correction to the mean value of the draconian period in seconds per orbital
             * period.
             */
            @NonNull
            public Builder setDeltaT(@FloatRange(from = -3600.0f, to = 3600.0f) double deltaT) {
                mDeltaT = deltaT;
                return this;
            }

            /**
             * Sets the rate of change of draconian period in seconds per orbital period squared.
             */
            @NonNull
            public Builder setDeltaTDot(@FloatRange(from = -0.004f, to = 0.004f) double deltaTDot) {
                mDeltaTDot = deltaTDot;
                return this;
            }

            /** Sets the eccentricity. */
            @NonNull
            public Builder setEccentricity(
                    @FloatRange(from = 0.0f, to = 0.03f) double eccentricity) {
                mEccentricity = eccentricity;
                return this;
            }

            /** Sets the argument of perigee in radians. */
            @NonNull
            public Builder setOmega(@FloatRange(from = -1.0f, to = 1.0f) double omega) {
                mOmega = omega;
                return this;
            }

            /** Builds a {@link GlonassSatelliteAlmanac}. */
            @NonNull
            public GlonassSatelliteAlmanac build() {
                return new GlonassSatelliteAlmanac(this);
            }
        }
    }
}
