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
 * A class contains almanac parameters for GPS, QZSS, Galileo, Beidou.
 *
 * <p>For Beidou, this is defined in BDS-SIS-ICD-B1I-3.0 section 5.2.4.15.
 *
 * <p>For GPS, this is defined in IS-GPS-200 section 20.3.3.5.1.2.
 *
 * <p>For QZSS, this is defined in IS-QZSS-PNT section 4.1.2.6.
 *
 * <p>For Galileo, this is defined in Galileo-OS-SIS-ICD-v2.1 section 5.1.10.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class GnssAlmanac implements Parcelable {
    /**
     * Almanac issue date in milliseconds (UTC).
     *
     * <p>This is unused for GPS/QZSS/Baidou.
     */
    private final long mIssueDateMillis;

    /**
     * Almanac issue of data.
     *
     * <p>This is unused for GPS/QZSS/Baidou.
     */
    private final int mIod;

    /**
     * Almanac reference week number.
     *
     * <p>For GPS and QZSS, this is GPS week number (modulo 1024).
     *
     * <p>For Beidou, this is Baidou week number (modulo 8192).
     *
     * <p>For Galileo, this is modulo 4 representation of the Galileo week number.
     */
    private final int mWeekNumber;

    /** Almanac reference time in seconds. */
    private final int mToaSeconds;

    /** The list of GnssSatelliteAlmanacs. */
    @NonNull private final List<GnssSatelliteAlmanac> mGnssSatelliteAlmanacs;

    private GnssAlmanac(Builder builder) {
        Preconditions.checkArgument(builder.mIssueDateMillis >= 0);
        Preconditions.checkArgument(builder.mIod >= 0);
        Preconditions.checkArgument(builder.mWeekNumber >= 0);
        Preconditions.checkArgumentInRange(builder.mToaSeconds, 0, 604800, "ToaSeconds");
        Preconditions.checkNotNull(
                builder.mGnssSatelliteAlmanacs, "GnssSatelliteAlmanacs cannot be null");
        mIssueDateMillis = builder.mIssueDateMillis;
        mIod = builder.mIod;
        mWeekNumber = builder.mWeekNumber;
        mToaSeconds = builder.mToaSeconds;
        mGnssSatelliteAlmanacs =
                Collections.unmodifiableList(new ArrayList<>(builder.mGnssSatelliteAlmanacs));
    }

    /** Returns the almanac issue date in milliseconds (UTC). */
    @IntRange(from = 0)
    public long getIssueDateMillis() {
        return mIssueDateMillis;
    }

    /** Returns the almanac issue of data. */
    @IntRange(from = 0)
    public int getIod() {
        return mIod;
    }

    /**
     * Returns the almanac reference week number.
     *
     * <p>For GPS and QZSS, this is GPS week number (modulo 1024).
     *
     * <p>For Beidou, this is Baidou week number (modulo 8192).
     *
     * <p>For Galileo, this is modulo 4 representation of the Galileo week number.
     */
    @IntRange(from = 0)
    public int getWeekNumber() {
        return mWeekNumber;
    }

    /** Returns the almanac reference time in seconds. */
    @IntRange(from = 0, to = 604800)
    public int getToaSeconds() {
        return mToaSeconds;
    }

    /** Returns the list of GnssSatelliteAlmanacs. */
    @NonNull
    public List<GnssSatelliteAlmanac> getGnssSatelliteAlmanacs() {
        return mGnssSatelliteAlmanacs;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mIssueDateMillis);
        dest.writeInt(mIod);
        dest.writeInt(mWeekNumber);
        dest.writeInt(mToaSeconds);
        dest.writeTypedList(mGnssSatelliteAlmanacs);
    }

    public static final @NonNull Creator<GnssAlmanac> CREATOR =
            new Creator<GnssAlmanac>() {
                @Override
                public GnssAlmanac createFromParcel(Parcel in) {
                    GnssAlmanac.Builder gnssAlmanac = new GnssAlmanac.Builder();
                    gnssAlmanac.setIssueDateMillis(in.readLong());
                    gnssAlmanac.setIod(in.readInt());
                    gnssAlmanac.setWeekNumber(in.readInt());
                    gnssAlmanac.setToaSeconds(in.readInt());
                    List<GnssSatelliteAlmanac> satelliteAlmanacs = new ArrayList<>();
                    in.readTypedList(satelliteAlmanacs, GnssSatelliteAlmanac.CREATOR);
                    gnssAlmanac.setGnssSatelliteAlmanacs(satelliteAlmanacs);
                    return gnssAlmanac.build();
                }

                @Override
                public GnssAlmanac[] newArray(int size) {
                    return new GnssAlmanac[size];
                }
            };

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("GnssAlmanac[");
        builder.append("issueDateMillis=").append(mIssueDateMillis);
        builder.append(", iod=").append(mIod);
        builder.append(", weekNumber=").append(mWeekNumber);
        builder.append(", toaSeconds=").append(mToaSeconds);
        builder.append(", satelliteAlmanacs=").append(mGnssSatelliteAlmanacs);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link GnssAlmanac}. */
    public static final class Builder {
        private long mIssueDateMillis;
        private int mIod;
        private int mWeekNumber;
        private int mToaSeconds;
        private List<GnssSatelliteAlmanac> mGnssSatelliteAlmanacs;

        /** Sets the almanac issue date in milliseconds (UTC). */
        @NonNull
        public Builder setIssueDateMillis(@IntRange(from = 0) long issueDateMillis) {
            mIssueDateMillis = issueDateMillis;
            return this;
        }

        /** Sets the almanac issue of data. */
        @NonNull
        public Builder setIod(@IntRange(from = 0) int iod) {
            mIod = iod;
            return this;
        }

        /**
         * Sets the almanac reference week number.
         *
         * <p>For GPS and QZSS, this is GPS week number (modulo 1024).
         *
         * <p>For Beidou, this is Baidou week number (modulo 8192).
         *
         * <p>For Galileo, this is modulo 4 representation of the Galileo week number.
         */
        @NonNull
        public Builder setWeekNumber(@IntRange(from = 0) int weekNumber) {
            mWeekNumber = weekNumber;
            return this;
        }

        /** Sets the almanac reference time in seconds. */
        @NonNull
        public Builder setToaSeconds(@IntRange(from = 0, to = 604800) int toaSeconds) {
            mToaSeconds = toaSeconds;
            return this;
        }

        /** Sets the list of GnssSatelliteAlmanacs. */
        @NonNull
        public Builder setGnssSatelliteAlmanacs(
                @NonNull List<GnssSatelliteAlmanac> gnssSatelliteAlmanacs) {
            mGnssSatelliteAlmanacs = gnssSatelliteAlmanacs;
            return this;
        }

        /** Builds a {@link GnssAlmanac} instance as specified by this builder. */
        @NonNull
        public GnssAlmanac build() {
            return new GnssAlmanac(this);
        }
    }

    /**
     * A class contains almanac parameters for GPS, QZSS, Galileo, Beidou.
     *
     * <p>For Beidou, this is defined in BDS-SIS-ICD-B1I-3.0 section 5.2.4.15.
     *
     * <p>For GPS, this is defined in IS-GPS-200 section 20.3.3.5.1.2.
     *
     * <p>For QZSS, this is defined in IS-QZSS-PNT section 4.1.2.6.
     *
     * <p>For Galileo, this is defined in Galileo-OS-SIS-ICD-v2.1 section 5.1.10.
     */
    public static final class GnssSatelliteAlmanac implements Parcelable {
        /** The PRN number of the GNSS satellite. */
        private final int mSvid;

        /**
         * Satellite health information.
         *
         * <p>For GPS, this is satellite subframe 4 and 5, page 25 6-bit health code as defined in
         * IS-GPS-200 Table 20-VIII expressed in integer form.
         *
         * <p>For QZSS, this is the 5-bit health code as defined in IS-QZSS-PNT, Table 4.1.2-5-2
         * expressed in integer form.
         *
         * <p>For Beidou, this is 1-bit health information. (0=healthy, 1=unhealthy).
         *
         * <p>For Galileo, this is 6-bit health, bit 0 and 1 is for E5a, bit 2 and 3 is for E5b, bit
         * 4 and 5 is for E1b.
         */
        private final int mSvHealth;

        /** Eccentricity. */
        private final double mEccentricity;

        /**
         * Inclination in semi-circles.
         *
         * <p>For GPS and Galileo, this is the difference between the inclination angle at reference
         * time and the nominal inclination in semi-circles.
         *
         * <p>For Beidou and QZSS, this is the inclination angle at reference time in semi-circles.
         */
        private final double mInclination;

        /** Argument of perigee in semi-circles. */
        private final double mOmega;

        /** Longitude of ascending node of orbital plane at weekly epoch in semi-circles. */
        private final double mOmega0;

        /** Rate of right ascension in semi-circles per second. */
        private final double mOmegaDot;

        /**
         * Square root of semi-major axis in square root of meters.
         *
         * <p>For Galileo, this is the difference with respect to the square root of the nominal
         * semi-major axis in square root of meters.
         */
        private final double mRootA;

        /** Mean anomaly at reference time in semi-circles. */
        private final double mM0;

        /** Satellite clock time bias correction coefficient in seconds. */
        private final double mAf0;

        /** Satellite clock time drift correction coefficient in seconds per second. */
        private final double mAf1;

        private GnssSatelliteAlmanac(Builder builder) {
            Preconditions.checkArgument(builder.mSvid > 0);
            Preconditions.checkArgument(builder.mSvHealth >= 0);
            Preconditions.checkArgument(builder.mEccentricity >= 0.0f);
            Preconditions.checkArgumentInRange(builder.mInclination, -1.0f, 1.0f, "Inclination");
            Preconditions.checkArgumentInRange(builder.mOmega, -1.0f, 1.0f, "Omega");
            Preconditions.checkArgumentInRange(builder.mOmega0, -1.0f, 1.0f, "Omega0");
            Preconditions.checkArgumentInRange(builder.mOmegaDot, -1.0f, 1.0f, "OmegaDot");
            Preconditions.checkArgumentInRange(builder.mRootA, 0.0f, 8192.0f, "RootA");
            Preconditions.checkArgumentInRange(builder.mM0, -1.0f, 1.0f, "M0");
            Preconditions.checkArgumentInRange(builder.mAf0, -0.0625f, 0.0625f, "Af0");
            Preconditions.checkArgumentInRange(builder.mAf1, -1.5e-8f, 1.5e-8f, "Af1");
            mSvid = builder.mSvid;
            mSvHealth = builder.mSvHealth;
            mEccentricity = builder.mEccentricity;
            mInclination = builder.mInclination;
            mOmega = builder.mOmega;
            mOmega0 = builder.mOmega0;
            mOmegaDot = builder.mOmegaDot;
            mRootA = builder.mRootA;
            mM0 = builder.mM0;
            mAf0 = builder.mAf0;
            mAf1 = builder.mAf1;
        }

        /** Returns the PRN number of the GNSS satellite. */
        @IntRange(from = 1)
        public int getSvid() {
            return mSvid;
        }

        /**
         * Returns the satellite health information.
         *
         * <p>For GPS, this is satellite subframe 4 and 5, page 25 6-bit health code as defined in
         * IS-GPS-200 Table 20-VIII expressed in integer form.
         *
         * <p>For QZSS, this is the 5-bit health code as defined in IS-QZSS-PNT, Table 4.1.2-5-2
         * expressed in integer form.
         *
         * <p>For Beidou, this is 1-bit health information. (0=healthy, 1=unhealthy).
         *
         * <p>For Galileo, this is 6-bit health, bit 0 and 1 is for E5a, bit 2 and 3 is for E5b,
         * bit 4 and 5 is for E1b.
         */
        @IntRange(from = 0)
        public int getSvHealth() {
            return mSvHealth;
        }

        /** Returns the eccentricity. */
        @FloatRange(from = 0.0f)
        public double getEccentricity() {
            return mEccentricity;
        }

        /**
         * Returns the inclination in semi-circles.
         *
         * <p>For GPS and Galileo, this is the difference between the inclination angle at reference
         * time and the nominal inclination in semi-circles.
         *
         * <p>For Beidou and QZSS, this is the inclination angle at reference time in semi-circles.
         */
        @FloatRange(from = -1.0f, to = 1.0f)
        public double getInclination() {
            return mInclination;
        }

        /** Returns the argument of perigee in semi-circles. */
        @FloatRange(from = -1.0f, to = 1.0f)
        public double getOmega() {
            return mOmega;
        }

        /**
         * Returns the longitude of ascending node of orbital plane at weekly epoch in semi-circles.
         */
        @FloatRange(from = -1.0f, to = 1.0f)
        public double getOmega0() {
            return mOmega0;
        }

        /** Returns the rate of right ascension in semi-circles per second. */
        @FloatRange(from = -1.0f, to = 1.0f)
        public double getOmegaDot() {
            return mOmegaDot;
        }

        /**
         * Returns the square root of semi-major axis in square root of meters.
         *
         * <p>For Galileo, this is the difference with respect to the square root of the nominal
         * semi-major axis in square root of meters.
         */
        @FloatRange(from = 0.0f, to = 8192.0f)
        public double getRootA() {
            return mRootA;
        }

        /** Returns the mean anomaly at reference time in semi-circles. */
        @FloatRange(from = -1.0f, to = 1.0f)
        public double getM0() {
            return mM0;
        }

        /** Returns the satellite clock time bias correction coefficient in seconds. */
        @FloatRange(from = -0.0625f, to = 0.0625f)
        public double getAf0() {
            return mAf0;
        }

        /** Returns the satellite clock time drift correction coefficient in seconds per second. */
        @FloatRange(from = -1.5e-8f, to = 1.5e-8f)
        public double getAf1() {
            return mAf1;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mSvid);
            dest.writeInt(mSvHealth);
            dest.writeDouble(mEccentricity);
            dest.writeDouble(mInclination);
            dest.writeDouble(mOmega);
            dest.writeDouble(mOmega0);
            dest.writeDouble(mOmegaDot);
            dest.writeDouble(mRootA);
            dest.writeDouble(mM0);
            dest.writeDouble(mAf0);
            dest.writeDouble(mAf1);
        }

        public static final @NonNull Creator<GnssSatelliteAlmanac> CREATOR =
                new Creator<GnssSatelliteAlmanac>() {
                    @Override
                    public GnssSatelliteAlmanac createFromParcel(Parcel in) {
                        return new GnssSatelliteAlmanac(
                                new Builder()
                                        .setSvid(in.readInt())
                                        .setSvHealth(in.readInt())
                                        .setEccentricity(in.readDouble())
                                        .setInclination(in.readDouble())
                                        .setOmega(in.readDouble())
                                        .setOmega0(in.readDouble())
                                        .setOmegaDot(in.readDouble())
                                        .setRootA(in.readDouble())
                                        .setM0(in.readDouble())
                                        .setAf0(in.readDouble())
                                        .setAf1(in.readDouble()));
                    }

                    @Override
                    public GnssSatelliteAlmanac[] newArray(int size) {
                        return new GnssSatelliteAlmanac[size];
                    }
                };

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GnssSatelliteAlmanac[");
            builder.append("svid = ").append(mSvid);
            builder.append(", svHealth = ").append(mSvHealth);
            builder.append(", eccentricity = ").append(mEccentricity);
            builder.append(", inclination = ").append(mInclination);
            builder.append(", omega = ").append(mOmega);
            builder.append(", omega0 = ").append(mOmega0);
            builder.append(", omegaDot = ").append(mOmegaDot);
            builder.append(", rootA = ").append(mRootA);
            builder.append(", m0 = ").append(mM0);
            builder.append(", af0 = ").append(mAf0);
            builder.append(", af1 = ").append(mAf1);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link GnssSatelliteAlmanac}. */
        public static final class Builder {
            private int mSvid;
            private int mSvHealth;
            private double mEccentricity;
            private double mInclination;
            private double mOmega;
            private double mOmega0;
            private double mOmegaDot;
            private double mRootA;
            private double mM0;
            private double mAf0;
            private double mAf1;

            /** Sets the PRN number of the GNSS satellite. */
            @NonNull
            public Builder setSvid(@IntRange(from = 1) int svid) {
                mSvid = svid;
                return this;
            }

            /**
             * Sets the satellite health information.
             *
             * <p>For GPS, this is satellite subframe 4 and 5, page 25 6-bit health code as defined
             * in IS-GPS-200 Table 20-VIII expressed in integer form.
             *
             * <p>For QZSS, this is the 5-bit health code as defined in IS-QZSS-PNT, Table 4.1.2-5-2
             * expressed in integer form.
             *
             * <p>For Beidou, this is 1-bit health information. (0=healthy, 1=unhealthy).
             *
             * <p>For Galileo, this is 6-bit health, bit 0 and 1 is for E5a,
             * bit 2 and 3 is for E5b, bit 4 and 5 is for E1b.
             */
            @NonNull
            public Builder setSvHealth(@IntRange(from = 0) int svHealth) {
                mSvHealth = svHealth;
                return this;
            }

            /** Sets the eccentricity. */
            @NonNull
            public Builder setEccentricity(@FloatRange(from = 0.0f) double eccentricity) {
                mEccentricity = eccentricity;
                return this;
            }

            /**
             * Sets the inclination in semi-circles.
             *
             * <p>For GPS and Galileo, this is the difference between the inclination angle at
             * reference time and the nominal inclination in semi-circles.
             *
             * <p>For Beidou and QZSS, this is the inclination angle at reference time in
             * semi-circles.
             */
            @NonNull
            public Builder setInclination(@FloatRange(from = -1.0f, to = 1.0f) double inclination) {
                mInclination = inclination;
                return this;
            }

            /** Sets the argument of perigee in semi-circles. */
            @NonNull
            public Builder setOmega(@FloatRange(from = -1.0f, to = 1.0f) double omega) {
                mOmega = omega;
                return this;
            }

            /**
             * Sets the longitude of ascending node of orbital plane at weekly epoch in
             * semi-circles.
             */
            @NonNull
            public Builder setOmega0(@FloatRange(from = -1.0f, to = 1.0f) double omega0) {
                mOmega0 = omega0;
                return this;
            }

            /** Sets the rate of right ascension in semi-circles per second. */
            @NonNull
            public Builder setOmegaDot(@FloatRange(from = -1.0f, to = 1.0f) double omegaDot) {
                mOmegaDot = omegaDot;
                return this;
            }

            /**
             * Sets the square root of semi-major axis in square root of meters.
             *
             * <p>For Galileo, this is the difference with respect to the square root of the nominal
             * semi-major axis in square root of meters.
             */
            @NonNull
            public Builder setRootA(@FloatRange(from = 0.0f, to = 8192.0f) double rootA) {
                mRootA = rootA;
                return this;
            }

            /** Sets the mean anomaly at reference time in semi-circles. */
            @NonNull
            public Builder setM0(@FloatRange(from = -1.0f, to = 1.0f) double m0) {
                mM0 = m0;
                return this;
            }

            /** Sets the satellite clock time bias correction coefficient in seconds. */
            @NonNull
            public Builder setAf0(@FloatRange(from = -0.0625f, to = 0.0625f) double af0) {
                mAf0 = af0;
                return this;
            }

            /** Sets the satellite clock time drift correction coefficient in seconds per second. */
            @NonNull
            public Builder setAf1(@FloatRange(from = -1.5e-8f, to = 1.5e-8f) double af1) {
                mAf1 = af1;
                return this;
            }

            /** Builds a {@link GnssSatelliteAlmanac} instance as specified by this builder. */
            @NonNull
            public GnssSatelliteAlmanac build() {
                return new GnssSatelliteAlmanac(this);
            }
        }
    }
}
