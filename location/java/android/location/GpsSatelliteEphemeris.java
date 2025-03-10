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
 * This class contains ephemeris parameters specific to GPS satellites.
 *
 * <p>This is defined in IS-GPS-200 section 20.3.3.3.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class GpsSatelliteEphemeris implements Parcelable {
    /** Satellite PRN */
    private final int mPrn;

    /** L2 parameters. */
    @NonNull private final GpsL2Params mGpsL2Params;

    /** Clock model. */
    @NonNull private final GpsSatelliteClockModel mSatelliteClockModel;

    /** Orbit model. */
    @NonNull private final KeplerianOrbitModel mSatelliteOrbitModel;

    /** Satellite health. */
    @NonNull private final GpsSatelliteHealth mSatelliteHealth;

    /** Ephemeris time. */
    @NonNull private final SatelliteEphemerisTime mSatelliteEphemerisTime;

    private GpsSatelliteEphemeris(Builder builder) {
        // Allow PRN beyond the range to support potential future extensibility.
        Preconditions.checkArgument(builder.mPrn >= 1);
        Preconditions.checkNotNull(builder.mGpsL2Params, "GPSL2Params cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteClockModel,
                "SatelliteClockModel cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteOrbitModel,
                "SatelliteOrbitModel cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteHealth,
                "SatelliteHealth cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteEphemerisTime,
                "SatelliteEphemerisTime cannot be null");
        mPrn = builder.mPrn;
        mGpsL2Params = builder.mGpsL2Params;
        mSatelliteClockModel = builder.mSatelliteClockModel;
        mSatelliteOrbitModel = builder.mSatelliteOrbitModel;
        mSatelliteHealth = builder.mSatelliteHealth;
        mSatelliteEphemerisTime = builder.mSatelliteEphemerisTime;
    }

    /** Returns the PRN of the satellite. */
    @IntRange(from = 1, to = 32)
    public int getPrn() {
        return mPrn;
    }

    /** Returns the L2 parameters of the satellite. */
    @NonNull
    public GpsL2Params getGpsL2Params() {
        return mGpsL2Params;
    }

    /** Returns the clock model of the satellite. */
    @NonNull
    public GpsSatelliteClockModel getSatelliteClockModel() {
        return mSatelliteClockModel;
    }

    /** Returns the orbit model of the satellite. */
    @NonNull
    public KeplerianOrbitModel getSatelliteOrbitModel() {
        return mSatelliteOrbitModel;
    }

    /** Returns the satellite health. */
    @NonNull
    public GpsSatelliteHealth getSatelliteHealth() {
        return mSatelliteHealth;
    }

    /** Returns the ephemeris time. */
    @NonNull
    public SatelliteEphemerisTime getSatelliteEphemerisTime() {
        return mSatelliteEphemerisTime;
    }

    public static final @NonNull Creator<GpsSatelliteEphemeris> CREATOR =
            new Creator<GpsSatelliteEphemeris>() {
                @Override
                @NonNull
                public GpsSatelliteEphemeris createFromParcel(Parcel in) {
                    final GpsSatelliteEphemeris.Builder gpsSatelliteEphemeris =
                            new Builder()
                                    .setPrn(in.readInt())
                                    .setGpsL2Params(in.readTypedObject(GpsL2Params.CREATOR))
                                    .setSatelliteClockModel(
                                            in.readTypedObject(GpsSatelliteClockModel.CREATOR))
                                    .setSatelliteOrbitModel(
                                            in.readTypedObject(KeplerianOrbitModel.CREATOR))
                                    .setSatelliteHealth(
                                            in.readTypedObject(GpsSatelliteHealth.CREATOR))
                                    .setSatelliteEphemerisTime(
                                            in.readTypedObject(SatelliteEphemerisTime.CREATOR));
                    return gpsSatelliteEphemeris.build();
                }

                @Override
                public GpsSatelliteEphemeris[] newArray(int size) {
                    return new GpsSatelliteEphemeris[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mPrn);
        parcel.writeTypedObject(mGpsL2Params, flags);
        parcel.writeTypedObject(mSatelliteClockModel, flags);
        parcel.writeTypedObject(mSatelliteOrbitModel, flags);
        parcel.writeTypedObject(mSatelliteHealth, flags);
        parcel.writeTypedObject(mSatelliteEphemerisTime, flags);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("GpsSatelliteEphemeris[");
        builder.append("prn = ").append(mPrn);
        builder.append(", gpsL2Params = ").append(mGpsL2Params);
        builder.append(", satelliteClockModel = ").append(mSatelliteClockModel);
        builder.append(", satelliteOrbitModel = ").append(mSatelliteOrbitModel);
        builder.append(", satelliteHealth = ").append(mSatelliteHealth);
        builder.append(", satelliteEphemerisTime = ").append(mSatelliteEphemerisTime);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link GpsSatelliteEphemeris} */
    public static final class Builder {
        private int mPrn = 0;
        private GpsL2Params mGpsL2Params;
        private GpsSatelliteClockModel mSatelliteClockModel;
        private KeplerianOrbitModel mSatelliteOrbitModel;
        private GpsSatelliteHealth mSatelliteHealth;
        private SatelliteEphemerisTime mSatelliteEphemerisTime;

        /** Sets the PRN of the satellite. */
        @NonNull
        public Builder setPrn(@IntRange(from = 1, to = 32) int prn) {
            mPrn = prn;
            return this;
        }

        /** Sets the L2 parameters of the satellite. */
        @NonNull
        public Builder setGpsL2Params(@NonNull GpsL2Params gpsL2Params) {
            mGpsL2Params = gpsL2Params;
            return this;
        }

        /** Sets the clock model of the satellite. */
        @NonNull
        public Builder setSatelliteClockModel(@NonNull GpsSatelliteClockModel satelliteClockModel) {
            mSatelliteClockModel = satelliteClockModel;
            return this;
        }

        /** Sets the orbit model of the satellite. */
        @NonNull
        public Builder setSatelliteOrbitModel(@NonNull KeplerianOrbitModel satelliteOrbitModel) {
            mSatelliteOrbitModel = satelliteOrbitModel;
            return this;
        }

        /** Sets the satellite health. */
        @NonNull
        public Builder setSatelliteHealth(@NonNull GpsSatelliteHealth satelliteHealth) {
            mSatelliteHealth = satelliteHealth;
            return this;
        }

        /** Sets the ephemeris time. */
        @NonNull
        public Builder setSatelliteEphemerisTime(
                @NonNull SatelliteEphemerisTime satelliteEphemerisTime) {
            mSatelliteEphemerisTime = satelliteEphemerisTime;
            return this;
        }

        /** Builds a {@link GpsSatelliteEphemeris} instance as specified by this builder. */
        @NonNull
        public GpsSatelliteEphemeris build() {
            return new GpsSatelliteEphemeris(this);
        }
    }

    /**
     * A class contains information about GPS health. The information is tied to Legacy Navigation
     * (LNAV) data, not Civil Navigation (CNAV) data.
     */
    public static final class GpsSatelliteHealth implements Parcelable {
        /**
         * Represents "SV health" in the "BROADCAST ORBIT - 6" record of RINEX 3.05. Table A6,
         * pp.68.
         */
        private final int mSvHealth;

        /**
         * Represents "SV accuracy" in meters in the "BROADCAST ORBIT - 6" record of RINEX 3.05.
         * Table A6, pp.68.
         */
        private final double mSvAccur;

        /**
         * Represents the "Fit Interval" in hours in the "BROADCAST ORBIT - 7" record of RINEX 3.05.
         * Table A6, pp.69.
         */
        private final double mFitInt;

        /** Returns the SV health. */
        @IntRange(from = 0, to = 63)
        public int getSvHealth() {
            return mSvHealth;
        }

        /** Returns the SV accuracy in meters. */
        @FloatRange(from = 0.0f, to = 8192.0f)
        public double getSvAccur() {
            return mSvAccur;
        }

        /** Returns the fit interval in hours. */
        @FloatRange(from = 0.0f)
        public double getFitInt() {
            return mFitInt;
        }

        private GpsSatelliteHealth(Builder builder) {
            // Allow SV health beyond the range to support potential future extensibility.
            Preconditions.checkArgument(builder.mSvHealth >= 0);
            Preconditions.checkArgumentInRange(builder.mSvAccur, 0.0f, 8192.0f, "SvAccur");
            Preconditions.checkArgument(builder.mFitInt >= 0.0f);
            mSvHealth = builder.mSvHealth;
            mSvAccur = builder.mSvAccur;
            mFitInt = builder.mFitInt;
        }

        public static final @NonNull Creator<GpsSatelliteHealth> CREATOR =
                new Creator<GpsSatelliteHealth>() {
                    @Override
                    @NonNull
                    public GpsSatelliteHealth createFromParcel(Parcel in) {
                        final GpsSatelliteHealth.Builder gpsSatelliteHealth =
                                new Builder()
                                        .setSvHealth(in.readInt())
                                        .setSvAccur(in.readDouble())
                                        .setFitInt(in.readDouble());
                        return gpsSatelliteHealth.build();
                    }

                    @Override
                    public GpsSatelliteHealth[] newArray(int size) {
                        return new GpsSatelliteHealth[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeInt(mSvHealth);
            parcel.writeDouble(mSvAccur);
            parcel.writeDouble(mFitInt);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GpsSatelliteHealth[");
            builder.append("svHealth = ").append(mSvHealth);
            builder.append(", svAccur = ").append(mSvAccur);
            builder.append(", fitInt = ").append(mFitInt);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link GpsSatelliteHealth}. */
        public static final class Builder {
            private int mSvHealth;
            private double mSvAccur;
            private double mFitInt;

            /** Sets the SV health. */
            @NonNull
            public Builder setSvHealth(@IntRange(from = 0, to = 63) int svHealth) {
                mSvHealth = svHealth;
                return this;
            }

            /** Sets the SV accuracy in meters. */
            @NonNull
            public Builder setSvAccur(@FloatRange(from = 0.0f, to = 8192.0f) double svAccur) {
                mSvAccur = svAccur;
                return this;
            }

            /** Sets the fit interval in hours. */
            @NonNull
            public Builder setFitInt(@FloatRange(from = 0.0f) double fitInt) {
                mFitInt = fitInt;
                return this;
            }

            /** Builds a {@link GpsSatelliteHealth} instance as specified by this builder. */
            @NonNull
            public GpsSatelliteHealth build() {
                return new GpsSatelliteHealth(this);
            }
        }
    }

    /** A class contains L2 parameters specific to GPS satellites. */
    public static final class GpsL2Params implements Parcelable {
        /** Code(s) on L2 Channel. */
        private final int mL2Code;

        /** Data Flag for L2 P-Code. */
        private final int mL2Flag;

        /** Returns the code(s) on L2 channel. */
        @IntRange(from = 0, to = 3)
        public int getL2Code() {
            return mL2Code;
        }

        /** Returns the data flag for L2 P-code. */
        @IntRange(from = 0, to = 1)
        public int getL2Flag() {
            return mL2Flag;
        }

        private GpsL2Params(Builder builder) {
            Preconditions.checkArgumentInRange(builder.mL2Code, 0, 3, "L2 code");
            Preconditions.checkArgumentInRange(builder.mL2Flag, 0, 1, "L2 flag");
            mL2Code = builder.mL2Code;
            mL2Flag = builder.mL2Flag;
        }

        public static final @NonNull Creator<GpsL2Params> CREATOR =
                new Creator<GpsL2Params>() {
                    @Override
                    @NonNull
                    public GpsL2Params createFromParcel(Parcel in) {
                        final GpsL2Params.Builder gpsL2Params =
                                new Builder().setL2Code(in.readInt()).setL2Flag(in.readInt());
                        return gpsL2Params.build();
                    }

                    @Override
                    public GpsL2Params[] newArray(int size) {
                        return new GpsL2Params[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeInt(mL2Code);
            parcel.writeInt(mL2Flag);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GpsL2Params[");
            builder.append("l2Code = ").append(mL2Code);
            builder.append(", l2Flag = ").append(mL2Flag);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link GpsL2Params}. */
        public static final class Builder {
            private int mL2Code = 0;
            private int mL2Flag = 0;

            /** Sets the code(s) on L2 channel. */
            @NonNull
            public Builder setL2Code(@IntRange(from = 0, to = 3) int l2Code) {
                mL2Code = l2Code;
                return this;
            }

            /** Sets the data flag for L2 P-code. */
            @NonNull
            public Builder setL2Flag(@IntRange(from = 0, to = 1) int l2Flag) {
                mL2Flag = l2Flag;
                return this;
            }

            /** Builds a {@link GpsL2Params} instance as specified by this builder. */
            @NonNull
            public GpsL2Params build() {
                return new GpsL2Params(this);
            }
        }
    }

    /** A class contains the set of parameters needed for GPS satellite clock correction. */
    public static final class GpsSatelliteClockModel implements Parcelable {
        /**
         * Time of the clock in seconds since GPS epoch.
         *
         * <p>Corresponds to the 'Epoch' field within the 'SV/EPOCH/SV CLK' record of GPS
         * navigation message in RINEX 3.05 Table A6.
         */
        private final long mTimeOfClockSeconds;

        /** SV clock bias in seconds. */
        private final double mAf0;

        /** SV clock drift in seconds per second. */
        private final double mAf1;

        /** Clock drift rate in seconds per second squared. */
        private final double mAf2;

        /** Group delay differential in seconds. */
        private final double mTgd;

        /** Issue of data, clock. */
        private final int mIodc;

        private GpsSatelliteClockModel(Builder builder) {
            Preconditions.checkArgument(builder.mTimeOfClockSeconds >= 0);
            Preconditions.checkArgumentInRange(builder.mAf0, -9.77e-3f, 9.77e-3f, "Af0");
            Preconditions.checkArgumentInRange(builder.mAf1, -3.73e-9f, 3.73e-9f, "Af1");
            Preconditions.checkArgumentInRange(builder.mAf2, -3.56e-15f, 3.56e-15f, "Af2");
            Preconditions.checkArgumentInRange(builder.mTgd, -5.97e-8f, 5.97e-8f, "Tgd");
            Preconditions.checkArgumentInRange(builder.mIodc, 0, 1023, "Iodc");
            mTimeOfClockSeconds = builder.mTimeOfClockSeconds;
            mAf0 = builder.mAf0;
            mAf1 = builder.mAf1;
            mAf2 = builder.mAf2;
            mTgd = builder.mTgd;
            mIodc = builder.mIodc;
        }

        /** Returns the time of the clock in seconds since GPS epoch. */
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
        @FloatRange(from = -3.73e-9f, to = 3.73e-9f)
        public double getAf1() {
            return mAf1;
        }

        /** Returns the clock drift rate in seconds per second squared. */
        @FloatRange(from = -3.56e-15f, to = 3.56e-15f)
        public double getAf2() {
            return mAf2;
        }

        /** Returns the group delay differential in seconds. */
        @FloatRange(from = -5.97e-8f, to = 5.97e-8f)
        public double getTgd() {
            return mTgd;
        }

        /** Returns the issue of data, clock. */
        @IntRange(from = 0, to = 1023)
        public int getIodc() {
            return mIodc;
        }

        public static final @NonNull Creator<GpsSatelliteClockModel> CREATOR =
                new Creator<GpsSatelliteClockModel>() {
                    @Override
                    @NonNull
                    public GpsSatelliteClockModel createFromParcel(Parcel in) {
                        final GpsSatelliteClockModel.Builder gpsSatelliteClockModel =
                                new Builder()
                                        .setTimeOfClockSeconds(in.readLong())
                                        .setAf0(in.readDouble())
                                        .setAf1(in.readDouble())
                                        .setAf2(in.readDouble())
                                        .setTgd(in.readDouble())
                                        .setIodc(in.readInt());
                        return gpsSatelliteClockModel.build();
                    }

                    @Override
                    public GpsSatelliteClockModel[] newArray(int size) {
                        return new GpsSatelliteClockModel[size];
                    }
                };

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeLong(mTimeOfClockSeconds);
            parcel.writeDouble(mAf0);
            parcel.writeDouble(mAf1);
            parcel.writeDouble(mAf2);
            parcel.writeDouble(mTgd);
            parcel.writeInt(mIodc);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GpsSatelliteClockModel[");
            builder.append("timeOfClockSeconds = ").append(mTimeOfClockSeconds);
            builder.append(", af0 = ").append(mAf0);
            builder.append(", af1 = ").append(mAf1);
            builder.append(", af2 = ").append(mAf2);
            builder.append(", tgd = ").append(mTgd);
            builder.append(", iodc = ").append(mIodc);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link GpsSatelliteClockModel}. */
        public static final class Builder {
            private long mTimeOfClockSeconds;
            private double mAf0;
            private double mAf1;
            private double mAf2;
            private double mTgd;
            private int mIodc;

            /** Sets the time of the clock in seconds since GPS epoch. */
            @NonNull
            public Builder setTimeOfClockSeconds(@IntRange(from = 0) long timeOfClockSeconds) {
                mTimeOfClockSeconds = timeOfClockSeconds;
                return this;
            }

            /** Sets the SV clock bias in seconds. */
            @NonNull
            public Builder setAf0(@FloatRange(from = -9.77e-3f, to = 9.77e-3f) double af0) {
                mAf0 = af0;
                return this;
            }

            /** Sets the SV clock drift in seconds per second. */
            @NonNull
            public Builder setAf1(@FloatRange(from = -3.73e-9f, to = 3.73e-9f) double af1) {
                mAf1 = af1;
                return this;
            }

            /** Sets the clock drift rate in seconds per second squared. */
            @NonNull
            public Builder setAf2(@FloatRange(from = -3.56e-15f, to = 3.56e-15f) double af2) {
                mAf2 = af2;
                return this;
            }

            /** Sets the group delay differential in seconds. */
            @NonNull
            public Builder setTgd(@FloatRange(from = -5.97e-8f, to = 5.97e-8f) double tgd) {
                mTgd = tgd;
                return this;
            }

            /** Sets the issue of data, clock. */
            @NonNull
            public Builder setIodc(@IntRange(from = 0, to = 1023) int iodc) {
                mIodc = iodc;
                return this;
            }

            /** Builds a {@link GpsSatelliteClockModel} instance as specified by this builder. */
            @NonNull
            public GpsSatelliteClockModel build() {
                return new GpsSatelliteClockModel(this);
            }
        }
    }
}
