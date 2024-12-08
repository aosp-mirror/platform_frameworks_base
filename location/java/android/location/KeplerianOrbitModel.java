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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Contains Keplerian orbit model parameters for GPS/Galileo/QZSS/Beidou.
 * <p>For GPS, this is defined in IS-GPS-200 Table 20-II.
 * <p>For Galileo, this is defined in Galileo-OS-SIS-ICD-v2.1 section 5.1.1.
 * <p>For QZSS, this is defined in IS-QZSS-PNT section 4.1.2.
 * <p>For Baidou, this is defined in BDS-SIS-ICD-B1I-3.0 section 5.2.4.12.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class KeplerianOrbitModel implements Parcelable {
    /** Square root of the semi-major axis in square root of meters. */
    private final double mRootA;

    /** Eccentricity. */
    private final double mEccentricity;

    /** Inclination angle at reference time in radians. */
    private final double mI0;

    /** Rate of change of inclination angle in radians per second. */
    private final double mIDot;

    /** Argument of perigee in radians. */
    private final double mOmega;

    /** Longitude of ascending node of orbit plane at beginning of week in radians. */
    private final double mOmega0;

    /** Rate of right ascension in radians per second. */
    private final double mOmegaDot;

    /** Mean anomaly at reference time in radians. */
    private final double mM0;

    /** Mean motion difference from computed value in radians per second. */
    private final double mDeltaN;

    /** Second-order harmonic perturbations. */
    SecondOrderHarmonicPerturbation mSecondOrderHarmonicPerturbation;

    private KeplerianOrbitModel(Builder builder) {
        Preconditions.checkArgumentInRange(builder.mRootA, 0.0f, 8192.0f, "RootA");
        Preconditions.checkArgumentInRange(builder.mEccentricity, 0.0f, 0.5f, "Eccentricity");
        Preconditions.checkArgumentInRange(builder.mI0, -3.15f, 3.15f, "I0");
        Preconditions.checkArgumentInRange(builder.mIDot, -2.94e-9f, 2.94e-9f, "IDot");
        Preconditions.checkArgumentInRange(builder.mOmega, -3.15f, 3.15f, "Omega");
        Preconditions.checkArgumentInRange(builder.mOmega0, -3.15f, 3.15f, "Omega0");
        Preconditions.checkArgumentInRange(builder.mOmegaDot, -3.1e-6f, 3.1e-6f, "OmegaDot");
        Preconditions.checkArgumentInRange(builder.mM0, -3.15f, 3.15f, "M0");
        Preconditions.checkArgumentInRange(builder.mDeltaN, -1.18e-8f, 1.18e-8f, "DeltaN");
        mRootA = builder.mRootA;
        mEccentricity = builder.mEccentricity;
        mI0 = builder.mI0;
        mIDot = builder.mIDot;
        mOmega = builder.mOmega;
        mOmega0 = builder.mOmega0;
        mOmegaDot = builder.mOmegaDot;
        mM0 = builder.mM0;
        mDeltaN = builder.mDeltaN;
        mSecondOrderHarmonicPerturbation = builder.mSecondOrderHarmonicPerturbation;
    }

    /** Get the square root of the semi-major axis in square root of meters. */
    @FloatRange(from = 0.0f, to = 8192.0f)
    public double getRootA() {
        return mRootA;
    }

    /** Get the eccentricity. */
    @FloatRange(from = 0.0f, to = 0.5f)
    public double getEccentricity() {
        return mEccentricity;
    }

    /** Get the inclination angle at reference time in radians. */
    @FloatRange(from = -3.15f, to = 3.15f)
    public double getI0() {
        return mI0;
    }

    /** Get the rate of change of inclination angle in radians per second. */
    @FloatRange(from = -2.94e-9f, to = 2.94e-9f)
    public double getIDot() {
        return mIDot;
    }

    /** Get the argument of perigee in radians. */
    @FloatRange(from = -3.15f, to = 3.15f)
    public double getOmega() {
        return mOmega;
    }

    /** Get the longitude of ascending node of orbit plane at beginning of week in radians. */
    @FloatRange(from = -3.15f, to = 3.15f)
    public double getOmega0() {
        return mOmega0;
    }

    /** Get the rate of right ascension in radians per second. */
    @FloatRange(from = -3.1e-6f, to = 3.1e-6f)
    public double getOmegaDot() {
        return mOmegaDot;
    }

    /** Get the mean anomaly at reference time in radians. */
    @FloatRange(from = -3.15f, to = 3.15f)
    public double getM0() {
        return mM0;
    }

    /** Get the mean motion difference from computed value in radians per second. */
    @FloatRange(from = -1.18e-8f, to = 1.18e-8f)
    public double getDeltaN() {
        return mDeltaN;
    }

    /** Get the second-order harmonic perturbations. */
    @NonNull
    public SecondOrderHarmonicPerturbation getSecondOrderHarmonicPerturbation() {
        return mSecondOrderHarmonicPerturbation;
    }

    public static final @NonNull Creator<KeplerianOrbitModel> CREATOR =
            new Creator<KeplerianOrbitModel>() {
                @Override
                @NonNull
                public KeplerianOrbitModel createFromParcel(Parcel in) {
                    final KeplerianOrbitModel.Builder keplerianOrbitModel =
                            new Builder()
                                    .setRootA(in.readDouble())
                                    .setEccentricity(in.readDouble())
                                    .setI0(in.readDouble())
                                    .setIDot(in.readDouble())
                                    .setOmega(in.readDouble())
                                    .setOmega0(in.readDouble())
                                    .setOmegaDot(in.readDouble())
                                    .setM0(in.readDouble())
                                    .setDeltaN(in.readDouble())
                                    .setSecondOrderHarmonicPerturbation(
                                            in.readTypedObject(
                                                    SecondOrderHarmonicPerturbation.CREATOR));
                    return keplerianOrbitModel.build();
                }

                @Override
                public KeplerianOrbitModel[] newArray(int size) {
                    return new KeplerianOrbitModel[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeDouble(mRootA);
        parcel.writeDouble(mEccentricity);
        parcel.writeDouble(mI0);
        parcel.writeDouble(mIDot);
        parcel.writeDouble(mOmega);
        parcel.writeDouble(mOmega0);
        parcel.writeDouble(mOmegaDot);
        parcel.writeDouble(mM0);
        parcel.writeDouble(mDeltaN);
        parcel.writeTypedObject(mSecondOrderHarmonicPerturbation, flags);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("KeplerianOrbitModel[");
        builder.append("rootA = ").append(mRootA);
        builder.append(", eccentricity = ").append(mEccentricity);
        builder.append(", i0 = ").append(mI0);
        builder.append(", iDot = ").append(mIDot);
        builder.append(", omega = ").append(mOmega);
        builder.append(", omega0 = ").append(mOmega0);
        builder.append(", omegaDot = ").append(mOmegaDot);
        builder.append(", m0 = ").append(mM0);
        builder.append(", deltaN = ").append(mDeltaN);
        builder.append(", secondOrderHarmonicPerturbation = ")
                .append(mSecondOrderHarmonicPerturbation);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link KeplerianOrbitModel} */
    public static final class Builder {
        private double mRootA;
        private double mEccentricity;
        private double mI0;
        private double mIDot;
        private double mOmega;
        private double mOmega0;
        private double mOmegaDot;
        private double mM0;
        private double mDeltaN;
        private SecondOrderHarmonicPerturbation mSecondOrderHarmonicPerturbation;

        /** Sets the square root of the semi-major axis in square root of meters. */
        @NonNull
        public Builder setRootA(@FloatRange(from = 0.0f, to = 8192.0f) double rootA) {
            mRootA = rootA;
            return this;
        }

        /** Sets the eccentricity. */
        @NonNull
        public Builder setEccentricity(@FloatRange(from = 0.0f, to = 0.5f) double eccentricity) {
            mEccentricity = eccentricity;
            return this;
        }

        /** Sets the inclination angle at reference time in radians. */
        @NonNull
        public Builder setI0(@FloatRange(from = -3.15f, to = 3.15f) double i0) {
            mI0 = i0;
            return this;
        }

        /** Sets the rate of change of inclination angle in radians per second. */
        @NonNull
        public Builder setIDot(@FloatRange(from = -2.94e-9f, to = 2.94e-9f) double iDot) {
            mIDot = iDot;
            return this;
        }

        /** Sets the argument of perigee in radians. */
        @NonNull
        public Builder setOmega(@FloatRange(from = -3.15f, to = 3.15f) double omega) {
            mOmega = omega;
            return this;
        }

        /**
         * Sets the longitude of ascending node of orbit plane at beginning of week in radians.
         */
        @NonNull
        public Builder setOmega0(@FloatRange(from = -3.15f, to = 3.15f) double omega0) {
            mOmega0 = omega0;
            return this;
        }

        /** Sets the rate of right ascension in radians per second. */
        @NonNull
        public Builder setOmegaDot(@FloatRange(from = -3.1e-6f, to = 3.1e-6f) double omegaDot) {
            mOmegaDot = omegaDot;
            return this;
        }

        /** Sets the mean anomaly at reference time in radians. */
        @NonNull
        public Builder setM0(@FloatRange(from = -3.15f, to = 3.15f) double m0) {
            mM0 = m0;
            return this;
        }

        /** Sets the mean motion difference from computed value in radians per second. */
        @NonNull
        public Builder setDeltaN(@FloatRange(from = -1.18e-8f, to = 1.18e-8f) double deltaN) {
            mDeltaN = deltaN;
            return this;
        }

        /** Sets the second-order harmonic perturbations. */
        @NonNull
        public Builder setSecondOrderHarmonicPerturbation(
                @NonNull SecondOrderHarmonicPerturbation secondOrderHarmonicPerturbation) {
            mSecondOrderHarmonicPerturbation = secondOrderHarmonicPerturbation;
            return this;
        }

        /** Builds a {@link KeplerianOrbitModel} instance as specified by this builder. */
        @NonNull
        public KeplerianOrbitModel build() {
            return new KeplerianOrbitModel(this);
        }
    }

    /** A class contains second-order harmonic perturbations. */
    public static final class SecondOrderHarmonicPerturbation implements Parcelable {
        /** Amplitude of cosine harmonic correction term to angle of inclination in radians. */
        private final double mCic;

        /** Amplitude of sine harmonic correction term to angle of inclination in radians. */
        private final double mCis;

        /** Amplitude of cosine harmonic correction term to the orbit in meters. */
        private final double mCrc;

        /** Amplitude of sine harmonic correction term to the orbit in meters. */
        private final double mCrs;

        /** Amplitude of cosine harmonic correction term to the argument of latitude in radians. */
        private final double mCuc;

        /** Amplitude of sine harmonic correction term to the argument of latitude in radians. */
        private final double mCus;

        private SecondOrderHarmonicPerturbation(Builder builder) {
            Preconditions.checkArgumentInRange(builder.mCic, -6.11e-5f, 6.11e-5f, "Cic");
            Preconditions.checkArgumentInRange(builder.mCis, -6.11e-5f, 6.11e-5f, "Cis");
            Preconditions.checkArgumentInRange(builder.mCrc, -2048.0f, 2048.0f, "Crc");
            Preconditions.checkArgumentInRange(builder.mCrs, -2048.0f, 2048.0f, "Crs");
            Preconditions.checkArgumentInRange(builder.mCuc, -6.11e-5f, 6.11e-5f, "Cuc");
            Preconditions.checkArgumentInRange(builder.mCus, -6.11e-5f, 6.11e-5f, "Cus");
            mCic = builder.mCic;
            mCrc = builder.mCrc;
            mCis = builder.mCis;
            mCrs = builder.mCrs;
            mCuc = builder.mCuc;
            mCus = builder.mCus;
        }

        /**
         * Get the amplitude of cosine harmonic correction term to angle of inclination in radians.
         */
        @FloatRange(from = -6.11e-5f, to = 6.11e-5f)
        public double getCic() {
            return mCic;
        }

        /**
         * Get the amplitude of sine harmonic correction term to angle of inclination in radians.
         */
        @FloatRange(from = -6.11e-5f, to = 6.11e-5f)
        public double getCis() {
            return mCis;
        }

        /** Get the amplitude of cosine harmonic correction term to the orbit in meters. */
        @FloatRange(from = -2048.0f, to = 2048.0f)
        public double getCrc() {
            return mCrc;
        }

        /** Get the amplitude of sine harmonic correction term to the orbit in meters. */
        @FloatRange(from = -2048.0f, to = 2048.0f)
        public double getCrs() {
            return mCrs;
        }

        /**
         * Get the amplitude of cosine harmonic correction term to the argument of latitude in
         * radians.
         */
        @FloatRange(from = -6.11e-5f, to = 6.11e-5f)
        public double getCuc() {
            return mCuc;
        }

        /**
         * Get the amplitude of sine harmonic correction term to the argument of latitude in
         * radians.
         */
        @FloatRange(from = -6.11e-5f, to = 6.11e-5f)
        public double getCus() {
            return mCus;
        }

        public static final @NonNull Creator<SecondOrderHarmonicPerturbation> CREATOR =
                new Creator<SecondOrderHarmonicPerturbation>() {
                    @Override
                    @NonNull
                    public SecondOrderHarmonicPerturbation createFromParcel(Parcel in) {
                        final SecondOrderHarmonicPerturbation.Builder
                                secondOrderHarmonicPerturbation =
                                        new Builder()
                                                .setCic(in.readDouble())
                                                .setCis(in.readDouble())
                                                .setCrc(in.readDouble())
                                                .setCrs(in.readDouble())
                                                .setCuc(in.readDouble())
                                                .setCus(in.readDouble());
                        return secondOrderHarmonicPerturbation.build();
                    }

                    @Override
                    public SecondOrderHarmonicPerturbation[] newArray(int size) {
                        return new SecondOrderHarmonicPerturbation[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeDouble(mCic);
            parcel.writeDouble(mCis);
            parcel.writeDouble(mCrc);
            parcel.writeDouble(mCrs);
            parcel.writeDouble(mCuc);
            parcel.writeDouble(mCus);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("SecondOrderHarmonicPerturbation[");
            builder.append("cic = ").append(mCic);
            builder.append(", cis = ").append(mCis);
            builder.append(", crc = ").append(mCrc);
            builder.append(", crs = ").append(mCrs);
            builder.append(", cuc = ").append(mCuc);
            builder.append(", cus = ").append(mCus);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link SecondOrderHarmonicPerturbation} */
        public static final class Builder {
            private double mCic;
            private double mCis;
            private double mCrc;
            private double mCrs;
            private double mCuc;
            private double mCus;

            /**
             * Sets the amplitude of cosine harmonic correction term to angle of inclination in
             * radians.
             */
            @NonNull
            public Builder setCic(@FloatRange(from = -6.11e-5f, to = 6.11e-5f) double cic) {
                mCic = cic;
                return this;
            }

            /**
             * Sets the amplitude of sine harmonic correction term to angle of inclination in
             * radians.
             */
            @NonNull
            public Builder setCis(@FloatRange(from = -6.11e-5f, to = 6.11e-5f) double cis) {
                mCis = cis;
                return this;
            }

            /** Sets the amplitude of cosine harmonic correction term to the orbit in meters. */
            @NonNull
            public Builder setCrc(@FloatRange(from = -2048.0f, to = 2048.0f) double crc) {
                mCrc = crc;
                return this;
            }

            /** Sets the amplitude of sine harmonic correction term to the orbit in meters. */
            @NonNull
            public Builder setCrs(@FloatRange(from = -2048.0f, to = 2048.0f) double crs) {
                mCrs = crs;
                return this;
            }

            /**
             * Sets the amplitude of cosine harmonic correction term to the argument of latitude in
             * radians.
             */
            @NonNull
            public Builder setCuc(@FloatRange(from = -6.11e-5f, to = 6.11e-5f) double cuc) {
                mCuc = cuc;
                return this;
            }

            /**
             * Sets the amplitude of sine harmonic correction term to the argument of latitude in
             * radians.
             */
            @NonNull
            public Builder setCus(@FloatRange(from = -6.11e-5f, to = 6.11e-5f) double cus) {
                mCus = cus;
                return this;
            }

            /**
             * Builds a {@link SecondOrderHarmonicPerturbation} instance as specified by this
             * builder.
             */
            @NonNull
            public SecondOrderHarmonicPerturbation build() {
                return new SecondOrderHarmonicPerturbation(this);
            }
        }
    }
}
