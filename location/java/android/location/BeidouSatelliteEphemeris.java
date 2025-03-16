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

/**
 * A class contains ephemeris parameters specific to Beidou satellites.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class BeidouSatelliteEphemeris implements Parcelable {
    /** The PRN number of the Beidou satellite. */
    private final int mPrn;

    /** Satellite clock model. */
    private final BeidouSatelliteClockModel mSatelliteClockModel;

    /** Satellite orbit model. */
    private final KeplerianOrbitModel mSatelliteOrbitModel;

    /** Satellite health. */
    private final BeidouSatelliteHealth mSatelliteHealth;

    /** Satellite ephemeris time. */
    private final BeidouSatelliteEphemerisTime mSatelliteEphemerisTime;

    private BeidouSatelliteEphemeris(Builder builder) {
        // Allow PRN beyond the range to support potential future extensibility.
        Preconditions.checkArgument(builder.mPrn >= 1);
        Preconditions.checkNotNull(builder.mSatelliteClockModel,
                "SatelliteClockModel cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteOrbitModel,
                "SatelliteOrbitModel cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteHealth,
                "SatelliteHealth cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteEphemerisTime,
                "SatelliteEphemerisTime cannot be null");
        mPrn = builder.mPrn;
        mSatelliteClockModel = builder.mSatelliteClockModel;
        mSatelliteOrbitModel = builder.mSatelliteOrbitModel;
        mSatelliteHealth = builder.mSatelliteHealth;
        mSatelliteEphemerisTime = builder.mSatelliteEphemerisTime;
    }

    /** Returns the PRN of the satellite. */
    @IntRange(from = 1, to = 63)
    public int getPrn() {
        return mPrn;
    }

    /** Returns the satellite clock model. */
    @NonNull
    public BeidouSatelliteClockModel getSatelliteClockModel() {
        return mSatelliteClockModel;
    }

    /** Returns the satellite orbit model. */
    @NonNull
    public KeplerianOrbitModel getSatelliteOrbitModel() {
        return mSatelliteOrbitModel;
    }

    /** Returns the satellite health. */
    @NonNull
    public BeidouSatelliteHealth getSatelliteHealth() {
        return mSatelliteHealth;
    }

    /** Returns the satellite ephemeris time. */
    @NonNull
    public BeidouSatelliteEphemerisTime getSatelliteEphemerisTime() {
        return mSatelliteEphemerisTime;
    }

    public static final @NonNull Creator<BeidouSatelliteEphemeris> CREATOR =
            new Creator<BeidouSatelliteEphemeris>() {
                @Override
                @NonNull
                public BeidouSatelliteEphemeris createFromParcel(Parcel in) {
                    final BeidouSatelliteEphemeris.Builder beidouSatelliteEphemeris =
                            new Builder()
                                    .setPrn(in.readInt())
                                    .setSatelliteClockModel(
                                            in.readTypedObject(BeidouSatelliteClockModel.CREATOR))
                                    .setSatelliteOrbitModel(
                                            in.readTypedObject(KeplerianOrbitModel.CREATOR))
                                    .setSatelliteHealth(
                                            in.readTypedObject(BeidouSatelliteHealth.CREATOR))
                                    .setSatelliteEphemerisTime(
                                            in.readTypedObject(
                                                    BeidouSatelliteEphemerisTime.CREATOR));
                    return beidouSatelliteEphemeris.build();
                }

                @Override
                public BeidouSatelliteEphemeris[] newArray(int size) {
                    return new BeidouSatelliteEphemeris[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mPrn);
        parcel.writeTypedObject(mSatelliteClockModel, flags);
        parcel.writeTypedObject(mSatelliteOrbitModel, flags);
        parcel.writeTypedObject(mSatelliteHealth, flags);
        parcel.writeTypedObject(mSatelliteEphemerisTime, flags);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("BeidouSatelliteEphemeris[");
        builder.append("prn = ").append(mPrn);
        builder.append(", satelliteClockModel = ").append(mSatelliteClockModel);
        builder.append(", satelliteOrbitModel = ").append(mSatelliteOrbitModel);
        builder.append(", satelliteHealth = ").append(mSatelliteHealth);
        builder.append(", satelliteEphemerisTime = ").append(mSatelliteEphemerisTime);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link BeidouSatelliteEphemeris} */
    public static final class Builder {
        private int mPrn;
        private BeidouSatelliteClockModel mSatelliteClockModel;
        private KeplerianOrbitModel mSatelliteOrbitModel;
        private BeidouSatelliteHealth mSatelliteHealth;
        private BeidouSatelliteEphemerisTime mSatelliteEphemerisTime;

        /** Sets the PRN of the satellite. */
        @NonNull
        public Builder setPrn(int prn) {
            mPrn = prn;
            return this;
        }

        /** Sets the satellite clock model. */
        @NonNull
        public Builder setSatelliteClockModel(
                @NonNull BeidouSatelliteClockModel satelliteClockModel) {
            mSatelliteClockModel = satelliteClockModel;
            return this;
        }

        /** Sets the satellite orbit model. */
        @NonNull
        public Builder setSatelliteOrbitModel(@NonNull KeplerianOrbitModel satelliteOrbitModel) {
            mSatelliteOrbitModel = satelliteOrbitModel;
            return this;
        }

        /** Sets the satellite health. */
        @NonNull
        public Builder setSatelliteHealth(@NonNull BeidouSatelliteHealth satelliteHealth) {
            mSatelliteHealth = satelliteHealth;
            return this;
        }

        /** Sets the satellite ephemeris time. */
        @NonNull
        public Builder setSatelliteEphemerisTime(
                @NonNull BeidouSatelliteEphemerisTime satelliteEphemerisTime) {
            mSatelliteEphemerisTime = satelliteEphemerisTime;
            return this;
        }

        /** Builds a {@link BeidouSatelliteEphemeris} instance as specified by this builder. */
        @NonNull
        public BeidouSatelliteEphemeris build() {
            return new BeidouSatelliteEphemeris(this);
        }
    }

    /**
     * A class contains the set of parameters needed for Beidou satellite clock correction.
     *
     * <p>This is defined in BDS-SIS-ICD-B1I-3.0, section 5.2.4.9, 5.2.4.10.
     */
    public static final class BeidouSatelliteClockModel implements Parcelable {
        /**
         * Time of the clock in seconds since Beidou epoch.
         *
         * <p>Corresponds to the 'Epoch' field within the 'SV/EPOCH/SV CLK' record of Beidou
         * navigation message in RINEX 3.05 Table A14.
         */
        private final long mTimeOfClockSeconds;

        /** SV clock bias in seconds. */
        private final double mAf0;

        /** SV clock drift in seconds per second. */
        private final double mAf1;

        /** SV clock drift in seconds per second squared. */
        private final double mAf2;

        /** Group delay differential 1 B1/B3 in seconds. */
        private final double mTgd1;

        /** Group delay differential 2 B2/B3 in seconds. */
        private final double mTgd2;

        /**
         * Age of Data Clock.
         * <p>This is defined in BDS-SIS-ICD-B1I-3.0 Section 5.2.4.8 Table 5-6.
         */
        private final int mAodc;

        private BeidouSatelliteClockModel(Builder builder) {
            Preconditions.checkArgument(builder.mTimeOfClockSeconds >= 0);
            Preconditions.checkArgumentInRange(builder.mAf0, -9.77e-3f, 9.77e-3f, "Af0");
            Preconditions.checkArgumentInRange(builder.mAf1, -1.87e-9f, 1.87e-9f, "Af1");
            Preconditions.checkArgumentInRange(builder.mAf2, -1.39e-17f, 1.39e-17f, "Af2");
            Preconditions.checkArgumentInRange(builder.mTgd1, -5.12e-8f, 5.12e-8f, "Tgd1");
            Preconditions.checkArgumentInRange(builder.mTgd2, -5.12e-8f, 5.12e-8f, "Tgd2");
            Preconditions.checkArgumentInRange(builder.mAodc, 0, 31, "Aodc");
            mTimeOfClockSeconds = builder.mTimeOfClockSeconds;
            mAf0 = builder.mAf0;
            mAf1 = builder.mAf1;
            mAf2 = builder.mAf2;
            mTgd1 = builder.mTgd1;
            mTgd2 = builder.mTgd2;
            mAodc = builder.mAodc;
        }

        /** Returns the time of the clock in seconds since Beidou epoch. */
        @IntRange(from = 0)
        public long getTimeOfClockSeconds() {
            return mTimeOfClockSeconds;
        }

        /** Returns the SV clock bias in seconds. */
        @FloatRange(from = -9.77e-3f, to = 9.77e-3f)
        public double getAf0() {
            return mAf0;
        }

        /** Returns the SV clock drift in seconds per second. */
        @FloatRange(from = -1.87e-9f, to = 1.87e-9f)
        public double getAf1() {
            return mAf1;
        }

        /** Returns the SV clock drift in seconds per second squared. */
        @FloatRange(from = -1.39e-17f, to = 1.39e-17f)
        public double getAf2() {
            return mAf2;
        }

        /** Returns the group delay differential 1 B1/B3 in seconds. */
        @FloatRange(from = -5.12e-8f, to = 5.12e-8f)
        public double getTgd1() {
            return mTgd1;
        }

        /** Returns the group delay differential 2 B2/B3 in seconds. */
        @FloatRange(from = -5.12e-8f, to = 5.12e-8f)
        public double getTgd2() {
            return mTgd2;
        }

        /** Returns the age of data clock. */
        @IntRange(from = 0, to = 31)
        public int getAodc() {
            return mAodc;
        }

        public static final @NonNull Creator<BeidouSatelliteClockModel> CREATOR =
                new Creator<BeidouSatelliteClockModel>() {
                    @Override
                    @NonNull
                    public BeidouSatelliteClockModel createFromParcel(Parcel in) {
                        final BeidouSatelliteClockModel.Builder beidouSatelliteClockModel =
                                new Builder()
                                        .setTimeOfClockSeconds(in.readLong())
                                        .setAf0(in.readDouble())
                                        .setAf1(in.readDouble())
                                        .setAf2(in.readDouble())
                                        .setTgd1(in.readDouble())
                                        .setTgd2(in.readDouble())
                                        .setAodc(in.readInt());
                        return beidouSatelliteClockModel.build();
                    }

                    @Override
                    public BeidouSatelliteClockModel[] newArray(int size) {
                        return new BeidouSatelliteClockModel[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeLong(mTimeOfClockSeconds);
            parcel.writeDouble(mAf0);
            parcel.writeDouble(mAf1);
            parcel.writeDouble(mAf2);
            parcel.writeDouble(mTgd1);
            parcel.writeDouble(mTgd2);
            parcel.writeInt(mAodc);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("BeidouSatelliteClockModel[");
            builder.append("timeOfClockSeonds = ").append(mTimeOfClockSeconds);
            builder.append(", af0 = ").append(mAf0);
            builder.append(", af1 = ").append(mAf1);
            builder.append(", af2 = ").append(mAf2);
            builder.append(", tgd1 = ").append(mTgd1);
            builder.append(", tgd2 = ").append(mTgd2);
            builder.append(", aodc = ").append(mAodc);
            return builder.toString();
        }

        /** Builder for {@link BeidouSatelliteClockModel} */
        public static final class Builder {
            private long mTimeOfClockSeconds;
            private double mAf0;
            private double mAf1;
            private double mAf2;
            private double mTgd1;
            private double mTgd2;
            private int mAodc;

            /** Sets the time of the clock in seconds since Beidou epoch. */
            @NonNull
            public Builder setTimeOfClockSeconds(@IntRange(from = 0) long timeOfClockSeconds) {
                mTimeOfClockSeconds = timeOfClockSeconds;
                return this;
            }

            /** Sets the SV clock bias in seconds. */
            @NonNull
            public Builder setAf0(@FloatRange(from = -9.77e-3f, to = 9.77e-3f)double af0) {
                mAf0 = af0;
                return this;
            }

            /** Sets the SV clock drift in seconds per second. */
            @NonNull
            public Builder setAf1(@FloatRange(from = -1.87e-9f, to = 1.87e-9f) double af1) {
                mAf1 = af1;
                return this;
            }

            /** Sets the SV clock drift in seconds per second squared. */
            @NonNull
            public Builder setAf2(@FloatRange(from = -1.39e-17f, to = 1.39e-17f) double af2) {
                mAf2 = af2;
                return this;
            }

            /** Sets the group delay differential 1 B1/B3 in seconds. */
            @NonNull
            public Builder setTgd1(@FloatRange(from = -5.12e-8f, to = 5.12e-8f) double tgd1) {
                mTgd1 = tgd1;
                return this;
            }

            /** Sets the group delay differential 2 B2/B3 in seconds. */
            @NonNull
            public Builder setTgd2(@FloatRange(from = -5.12e-8f, to = 5.12e-8f) double tgd2) {
                mTgd2 = tgd2;
                return this;
            }

            /** Sets the age of data clock. */
            @NonNull
            public Builder setAodc(@IntRange(from = 0, to = 31) int aodc) {
                mAodc = aodc;
                return this;
            }

            /** Builds a {@link BeidouSatelliteClockModel} instance as specified by this builder. */
            @NonNull
            public BeidouSatelliteClockModel build() {
                return new BeidouSatelliteClockModel(this);
            }
        }
    }

    /** A class contains Beidou satellite health. */
    public static final class BeidouSatelliteHealth implements Parcelable {
        /**
         * The autonomous satellite health flag (SatH1) occupies 1 bit.
         *
         * <p>“0” means broadcasting satellite is good and “1” means not.
         *
         * <p>This is defined in BDS-SIS-ICD-B1I-3.0 section 5.2.4.6.
         */
        private final int mSatH1;

        /**
         * SV accuracy in meters.
         *
         * <p>This is defined in the "BROADCAST ORBIT - 6" record of RINEX 3.05
         * Table A14, pp.78.
         */
        private final double mSvAccur;

        private BeidouSatelliteHealth(Builder builder) {
            // Allow SatH1 beyond the range to support potential future extensibility.
            Preconditions.checkArgument(builder.mSatH1 >= 0);
            Preconditions.checkArgumentInRange(builder.mSvAccur, 0.0f, 8192.0f, "SvAccur");
            mSatH1 = builder.mSatH1;
            mSvAccur = builder.mSvAccur;
        }

        /** Returns the autonomous satellite health flag (SatH1) */
        @IntRange(from = 0, to = 1)
        public int getSatH1() {
            return mSatH1;
        }

        /** Returns the SV accuracy in meters. */
        @FloatRange(from = 0.0f, to = 8192.0f)
        public double getSvAccur() {
            return mSvAccur;
        }

        public static final @NonNull Creator<BeidouSatelliteHealth> CREATOR =
                new Creator<BeidouSatelliteHealth>() {
                    @Override
                    @NonNull
                    public BeidouSatelliteHealth createFromParcel(Parcel in) {
                        final BeidouSatelliteHealth.Builder beidouSatelliteHealth =
                                new Builder().setSatH1(in.readInt()).setSvAccur(in.readDouble());
                        return beidouSatelliteHealth.build();
                    }

                    @Override
                    public BeidouSatelliteHealth[] newArray(int size) {
                        return new BeidouSatelliteHealth[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeInt(mSatH1);
            parcel.writeDouble(mSvAccur);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("BeidouSatelliteHealth[");
            builder.append("satH1 = ").append(mSatH1);
            builder.append(", svAccur = ").append(mSvAccur);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link BeidouSatelliteHealth} */
        public static final class Builder {
            private int mSatH1;
            private double mSvAccur;

            /** Sets the autonomous satellite health flag (SatH1) */
            @NonNull
            public Builder setSatH1(int satH1) {
                mSatH1 = satH1;
                return this;
            }

            /** Sets the SV accuracy in meters. */
            @NonNull
            public Builder setSvAccur(double svAccur) {
                mSvAccur = svAccur;
                return this;
            }

            /** Builds a {@link BeidouSatelliteHealth} instance as specified by this builder. */
            @NonNull
            public BeidouSatelliteHealth build() {
                return new BeidouSatelliteHealth(this);
            }
        }
    }

    /** A class contains Beidou satellite ephemeris time. */
    public static final class BeidouSatelliteEphemerisTime implements Parcelable {
        /**
         * AODE Age of Data, Ephemeris.
         *
         * <p>This is defined in BDS-SIS-ICD-B1I-3.0 section 5.2.4.11 Table 5-8.
         */
        private final int mIode;

        /** Beidou week number without rollover */
        private final int mBeidouWeekNumber;

        /**
         * Time of ephemeris in seconds.
         *
         * <p>This is defined in BDS-SIS-ICD-B1I-3.0 section 5.2.4.12.
         */
        private final int mToeSeconds;

        private BeidouSatelliteEphemerisTime(Builder builder) {
            Preconditions.checkArgumentInRange(builder.mIode, 0, 31, "Iode");
            Preconditions.checkArgument(builder.mBeidouWeekNumber >= 0);
            Preconditions.checkArgumentInRange(builder.mToeSeconds, 0, 604792, "ToeSeconds");
            mIode = builder.mIode;
            mBeidouWeekNumber = builder.mBeidouWeekNumber;
            mToeSeconds = builder.mToeSeconds;
        }

        /** Returns the AODE Age of Data, Ephemeris. */
        @IntRange(from = 0, to = 31)
        public int getIode() {
            return mIode;
        }

        /** Returns the Beidou week number without rollover . */
        @IntRange(from = 0)
        public int getBeidouWeekNumber() {
            return mBeidouWeekNumber;
        }

        /** Returns the time of ephemeris in seconds. */
        @IntRange(from = 0, to = 604792)
        public int getToeSeconds() {
            return mToeSeconds;
        }

        public static final @NonNull Creator<BeidouSatelliteEphemerisTime> CREATOR =
                new Creator<BeidouSatelliteEphemerisTime>() {
                    @Override
                    @NonNull
                    public BeidouSatelliteEphemerisTime createFromParcel(Parcel in) {
                        final BeidouSatelliteEphemerisTime.Builder beidouSatelliteEphemerisTime =
                                new Builder()
                                        .setIode(in.readInt())
                                        .setBeidouWeekNumber(in.readInt())
                                        .setToeSeconds(in.readInt());
                        return beidouSatelliteEphemerisTime.build();
                    }

                    @Override
                    public BeidouSatelliteEphemerisTime[] newArray(int size) {
                        return new BeidouSatelliteEphemerisTime[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeInt(mIode);
            parcel.writeInt(mBeidouWeekNumber);
            parcel.writeInt(mToeSeconds);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("BeidouSatelliteEphemerisTime[");
            builder.append("iode = ").append(mIode);
            builder.append(", beidouWeekNumber = ").append(mBeidouWeekNumber);
            builder.append(", toeSeconds = ").append(mToeSeconds);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link BeidouSatelliteEphemerisTime} */
        public static final class Builder {
            private int mIode;
            private int mBeidouWeekNumber;
            private int mToeSeconds;

            /** Sets the AODE Age of Data, Ephemeris. */
            @NonNull
            public Builder setIode(int iode) {
                mIode = iode;
                return this;
            }

            /** Sets the Beidou week number without rollover */
            @NonNull
            public Builder setBeidouWeekNumber(
                    @IntRange(from = 0) int beidouWeekNumber) {
                mBeidouWeekNumber = beidouWeekNumber;
                return this;
            }

            /** Sets the time of ephemeris in seconds. */
            @NonNull
            public Builder setToeSeconds(@IntRange(from = 0, to = 604792) int toeSeconds) {
                mToeSeconds = toeSeconds;
                return this;
            }

            /**
             * Builds a {@link BeidouSatelliteEphemerisTime} instance as specified by this builder.
             */
            @NonNull
            public BeidouSatelliteEphemerisTime build() {
                return new BeidouSatelliteEphemerisTime(this);
            }
        }
    }
}
