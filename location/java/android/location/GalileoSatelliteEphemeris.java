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
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Contains ephemeris parameters specific to Galileo satellites.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class GalileoSatelliteEphemeris implements Parcelable {

    /** Satellite code number. */
    private int mSatelliteCodeNumber;

    /** Array of satellite clock model. */
    @NonNull private final List<GalileoSatelliteClockModel> mSatelliteClockModels;

    /** Satellite orbit model. */
    @NonNull private final KeplerianOrbitModel mSatelliteOrbitModel;

    /** Satellite health. */
    @NonNull private final GalileoSvHealth mSatelliteHealth;

    /** Satellite ephemeris time. */
    @NonNull private final SatelliteEphemerisTime mSatelliteEphemerisTime;

    private GalileoSatelliteEphemeris(Builder builder) {
        // Allow satelliteCodeNumber beyond the range to support potential future extensibility.
        Preconditions.checkArgument(builder.mSatelliteCodeNumber >= 1);
        Preconditions.checkNotNull(
                builder.mSatelliteClockModels, "SatelliteClockModels cannot be null");
        Preconditions.checkNotNull(
                builder.mSatelliteOrbitModel, "SatelliteOrbitModel cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteHealth, "SatelliteHealth cannot be null");
        Preconditions.checkNotNull(
                builder.mSatelliteEphemerisTime, "SatelliteEphemerisTime cannot be null");
        mSatelliteCodeNumber = builder.mSatelliteCodeNumber;
        final List<GalileoSatelliteClockModel> satelliteClockModels = builder.mSatelliteClockModels;
        mSatelliteClockModels = Collections.unmodifiableList(new ArrayList<>(satelliteClockModels));
        mSatelliteOrbitModel = builder.mSatelliteOrbitModel;
        mSatelliteHealth = builder.mSatelliteHealth;
        mSatelliteEphemerisTime = builder.mSatelliteEphemerisTime;
    }

    /** Returns the satellite code number. */
    @IntRange(from = 1, to = 36)
    public int getSatelliteCodeNumber() {
        return mSatelliteCodeNumber;
    }

    /** Returns the list of satellite clock models. */
    @NonNull
    public List<GalileoSatelliteClockModel> getSatelliteClockModels() {
        return mSatelliteClockModels;
    }

    /** Returns the satellite orbit model. */
    @NonNull
    public KeplerianOrbitModel getSatelliteOrbitModel() {
        return mSatelliteOrbitModel;
    }

    /** Returns the satellite health. */
    @NonNull
    public GalileoSvHealth getSatelliteHealth() {
        return mSatelliteHealth;
    }

    /** Returns the satellite ephemeris time. */
    @NonNull
    public SatelliteEphemerisTime getSatelliteEphemerisTime() {
        return mSatelliteEphemerisTime;
    }

    public static final @NonNull Creator<GalileoSatelliteEphemeris> CREATOR =
            new Creator<GalileoSatelliteEphemeris>() {
                @Override
                @NonNull
                public GalileoSatelliteEphemeris createFromParcel(Parcel in) {
                    final GalileoSatelliteEphemeris.Builder galileoSatelliteEphemeris =
                            new Builder();
                    galileoSatelliteEphemeris.setSatelliteCodeNumber(in.readInt());
                    List<GalileoSatelliteClockModel> satelliteClockModels = new ArrayList<>();
                    in.readTypedList(satelliteClockModels, GalileoSatelliteClockModel.CREATOR);
                    galileoSatelliteEphemeris.setSatelliteClockModels(satelliteClockModels);
                    galileoSatelliteEphemeris.setSatelliteOrbitModel(
                            in.readTypedObject(KeplerianOrbitModel.CREATOR));
                    galileoSatelliteEphemeris.setSatelliteHealth(
                            in.readTypedObject(GalileoSvHealth.CREATOR));
                    galileoSatelliteEphemeris.setSatelliteEphemerisTime(
                            in.readTypedObject(SatelliteEphemerisTime.CREATOR));
                    return galileoSatelliteEphemeris.build();
                }

                @Override
                public GalileoSatelliteEphemeris[] newArray(int size) {
                    return new GalileoSatelliteEphemeris[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mSatelliteCodeNumber);
        parcel.writeTypedList(mSatelliteClockModels, flags);
        parcel.writeTypedObject(mSatelliteOrbitModel, flags);
        parcel.writeTypedObject(mSatelliteHealth, flags);
        parcel.writeTypedObject(mSatelliteEphemerisTime, flags);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("GalileoSatelliteEphemeris[");
        builder.append("satelliteCodeNumber = ").append(mSatelliteCodeNumber);
        builder.append(", satelliteClockModels = ").append(mSatelliteClockModels);
        builder.append(", satelliteOrbitModel = ").append(mSatelliteOrbitModel);
        builder.append(", satelliteHealth = ").append(mSatelliteHealth);
        builder.append(", satelliteEphemerisTime = ").append(mSatelliteEphemerisTime);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link GalileoSatelliteEphemeris}. */
    public static final class Builder {
        private int mSatelliteCodeNumber;
        private List<GalileoSatelliteClockModel> mSatelliteClockModels;
        private KeplerianOrbitModel mSatelliteOrbitModel;
        private GalileoSvHealth mSatelliteHealth;
        private SatelliteEphemerisTime mSatelliteEphemerisTime;

        /** Sets the satellite code number. */
        @NonNull
        public Builder setSatelliteCodeNumber(
                @IntRange(from = 1, to = 36) int satelliteCodeNumber) {
            mSatelliteCodeNumber = satelliteCodeNumber;
            return this;
        }

        /** Sets the array of satellite clock model. */
        @NonNull
        public Builder setSatelliteClockModels(
                @NonNull List<GalileoSatelliteClockModel> satelliteClockModels) {
            mSatelliteClockModels = satelliteClockModels;
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
        public Builder setSatelliteHealth(@NonNull GalileoSvHealth satelliteHealth) {
            mSatelliteHealth = satelliteHealth;
            return this;
        }

        /** Sets the satellite ephemeris time. */
        @NonNull
        public Builder setSatelliteEphemerisTime(
                @NonNull SatelliteEphemerisTime satelliteEphemerisTime) {
            mSatelliteEphemerisTime = satelliteEphemerisTime;
            return this;
        }

        /** Builds a {@link GalileoSatelliteEphemeris} instance as specified by this builder. */
        @NonNull
        public GalileoSatelliteEphemeris build() {
            return new GalileoSatelliteEphemeris(this);
        }
    }

    /**
     * A class contains the set of parameters needed for Galileo satellite health.
     *
     * <p>This is defined in Galileo-OS-SIS-ICD 5.1.9.3.
     */
    public static final class GalileoSvHealth implements Parcelable {
        /** E1-B data validity status. */
        private int mDataValidityStatusE1b;

        /** E1-B/C signal health status. */
        private int mSignalHealthStatusE1b;

        /** E5a data validity status. */
        private int mDataValidityStatusE5a;

        /** E5a signal health status. */
        private int mSignalHealthStatusE5a;

        /** E5b data validity status. */
        private int mDataValidityStatusE5b;

        /** E5b signal health status. */
        private int mSignalHealthStatusE5b;

        private GalileoSvHealth(Builder builder) {
            Preconditions.checkArgumentInRange(
                    builder.mDataValidityStatusE1b, 0, 1, "DataValidityStatusE1b");
            Preconditions.checkArgumentInRange(
                    builder.mSignalHealthStatusE1b, 0, 3, "SignalHealthStatusE1b");
            Preconditions.checkArgumentInRange(
                    builder.mDataValidityStatusE5a, 0, 1, "DataValidityStatusE5a");
            Preconditions.checkArgumentInRange(
                    builder.mSignalHealthStatusE5a, 0, 3, "SignalHealthStatusE5a");
            Preconditions.checkArgumentInRange(
                    builder.mDataValidityStatusE5b, 0, 1, "DataValidityStatusE5b");
            Preconditions.checkArgumentInRange(
                    builder.mSignalHealthStatusE5b, 0, 3, "SignalHealthStatusE5b");
            mDataValidityStatusE1b = builder.mDataValidityStatusE1b;
            mSignalHealthStatusE1b = builder.mSignalHealthStatusE1b;
            mDataValidityStatusE5a = builder.mDataValidityStatusE5a;
            mSignalHealthStatusE5a = builder.mSignalHealthStatusE5a;
            mDataValidityStatusE5b = builder.mDataValidityStatusE5b;
            mSignalHealthStatusE5b = builder.mSignalHealthStatusE5b;
        }

        /** Returns the E1-B data validity status. */
        @IntRange(from = 0, to = 1)
        public int getDataValidityStatusE1b() {
            return mDataValidityStatusE1b;
        }

        /** Returns the E1-B/C signal health status. */
        @IntRange(from = 0, to = 3)
        public int getSignalHealthStatusE1b() {
            return mSignalHealthStatusE1b;
        }

        /** Returns the E5a data validity status. */
        @IntRange(from = 0, to = 1)
        public int getDataValidityStatusE5a() {
            return mDataValidityStatusE5a;
        }

        /** Returns the E5a signal health status. */
        @IntRange(from = 0, to = 3)
        public int getSignalHealthStatusE5a() {
            return mSignalHealthStatusE5a;
        }

        /** Returns the E5b data validity status. */
        @IntRange(from = 0, to = 1)
        public int getDataValidityStatusE5b() {
            return mDataValidityStatusE5b;
        }

        /** Returns the E5b signal health status. */
        @IntRange(from = 0, to = 3)
        public int getSignalHealthStatusE5b() {
            return mSignalHealthStatusE5b;
        }

        public static final @NonNull Creator<GalileoSvHealth> CREATOR =
                new Creator<GalileoSvHealth>() {
                    @Override
                    @NonNull
                    public GalileoSvHealth createFromParcel(Parcel in) {
                        final GalileoSvHealth.Builder galileoSvHealth = new Builder();
                        galileoSvHealth.setDataValidityStatusE1b(in.readInt());
                        galileoSvHealth.setSignalHealthStatusE1b(in.readInt());
                        galileoSvHealth.setDataValidityStatusE5a(in.readInt());
                        galileoSvHealth.setSignalHealthStatusE5a(in.readInt());
                        galileoSvHealth.setDataValidityStatusE5b(in.readInt());
                        galileoSvHealth.setSignalHealthStatusE5b(in.readInt());
                        return galileoSvHealth.build();
                    }

                    @Override
                    public GalileoSvHealth[] newArray(int size) {
                        return new GalileoSvHealth[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeInt(mDataValidityStatusE1b);
            parcel.writeInt(mSignalHealthStatusE1b);
            parcel.writeInt(mDataValidityStatusE5a);
            parcel.writeInt(mSignalHealthStatusE5a);
            parcel.writeInt(mDataValidityStatusE5b);
            parcel.writeInt(mSignalHealthStatusE5b);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GalileoSvHealth[");
            builder.append("dataValidityStatusE1b = ").append(mDataValidityStatusE1b);
            builder.append(", signalHealthStatusE1b = ").append(mSignalHealthStatusE1b);
            builder.append(", dataValidityStatusE5a = ").append(mDataValidityStatusE5a);
            builder.append(", signalHealthStatusE5a = ").append(mSignalHealthStatusE5a);
            builder.append(", dataValidityStatusE5b = ").append(mDataValidityStatusE5b);
            builder.append(", signalHealthStatusE5b = ").append(mSignalHealthStatusE5b);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link GalileoSvHealth}. */
        public static final class Builder {
            private int mDataValidityStatusE1b;
            private int mSignalHealthStatusE1b;
            private int mDataValidityStatusE5a;
            private int mSignalHealthStatusE5a;
            private int mDataValidityStatusE5b;
            private int mSignalHealthStatusE5b;

            /** Sets the E1-B data validity status. */
            @NonNull
            public Builder setDataValidityStatusE1b(
                    @IntRange(from = 0, to = 1) int dataValidityStatusE1b) {
                mDataValidityStatusE1b = dataValidityStatusE1b;
                return this;
            }

            /** Sets the E1-B/C signal health status. */
            @NonNull
            public Builder setSignalHealthStatusE1b(
                    @IntRange(from = 0, to = 3) int signalHealthStatusE1b) {
                mSignalHealthStatusE1b = signalHealthStatusE1b;
                return this;
            }

            /** Sets the E5a data validity status. */
            @NonNull
            public Builder setDataValidityStatusE5a(
                    @IntRange(from = 0, to = 1) int dataValidityStatusE5a) {
                mDataValidityStatusE5a = dataValidityStatusE5a;
                return this;
            }

            /** Sets the E5a signal health status. */
            @NonNull
            public Builder setSignalHealthStatusE5a(
                    @IntRange(from = 0, to = 3) int signalHealthStatusE5a) {
                mSignalHealthStatusE5a = signalHealthStatusE5a;
                return this;
            }

            /** Sets the E5b data validity status. */
            @NonNull
            public Builder setDataValidityStatusE5b(
                    @IntRange(from = 0, to = 1) int dataValidityStatusE5b) {
                mDataValidityStatusE5b = dataValidityStatusE5b;
                return this;
            }

            /** Sets the E5b signal health status. */
            @NonNull
            public Builder setSignalHealthStatusE5b(
                    @IntRange(from = 0, to = 3) int signalHealthStatusE5b) {
                mSignalHealthStatusE5b = signalHealthStatusE5b;
                return this;
            }

            /** Builds a {@link GalileoSvHealth}. */
            @NonNull
            public GalileoSvHealth build() {
                return new GalileoSvHealth(this);
            }
        }
    }

    /**
     * A class contains the set of parameters needed for Galileo satellite clock correction.
     *
     * <p>This is defined in Galileo-OS-SIS-ICD 5.1.3.
     */
    public static final class GalileoSatelliteClockModel implements Parcelable {

        /**
         * The type of the satellite clock.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({TYPE_UNDEFINED, TYPE_FNAV, TYPE_INAV})
        public @interface SatelliteClockType {}

        /**
         * The following enumerations must be in sync with the values declared in
         * GalileoSatelliteEphemeris.aidl.
         */

        /** The type of the satellite clock is unknown. */
        public static final int TYPE_UNDEFINED = 0;

        /** The type of the satellite clock is FNAV. */
        public static final int TYPE_FNAV = 1;

        /** The type of the satellite clock is INAV. */
        public static final int TYPE_INAV = 2;

        /**
         * Time of the clock in seconds since Galileo epoch.
         *
         * <p>Corresponds to the 'Epoch' field within the 'SV/EPOCH/SV CLK' record of Galileo
         * navigation message in RINEX 3.05 Table A8.
         */
        private final long mTimeOfClockSeconds;

        /** SV clock bias correction coefficient in seconds. */
        private double mAf0;

        /** SV clock drift correction coefficient in seconds per second. */
        private double mAf1;

        /** SV clock drift rate correction coefficient in seconds per second squared. */
        private double mAf2;

        /**
         * Broadcast group delay in seconds.
         *
         * <p>This is defined in Galileo-OS-SIS-ICD 5.1.5.
         */
        private double mBgdSeconds;

        /**
         * Signal in space accuracy in meters.
         *
         * <p>This is defined in Galileo-OS-SIS-ICD 5.1.12.
         */
        private double mSisaMeters;

        /** Type of satellite clock . */
        private final @SatelliteClockType int mSatelliteClockType;

        private GalileoSatelliteClockModel(Builder builder) {
            Preconditions.checkArgument(builder.mTimeOfClockSeconds >= 0);
            Preconditions.checkArgumentInRange(builder.mAf0, -0.0625f, 0.0625f, "AF0");
            Preconditions.checkArgumentInRange(builder.mAf1, -1.5e-8f, 1.5e-8f, "AF1");
            Preconditions.checkArgumentInRange(builder.mAf2, -5.56e-17f, 5.56e-17f, "AF2");
            Preconditions.checkArgumentInRange(
                    builder.mBgdSeconds, -1.2e-7f, 1.2e-7f, "BgdSeconds");
            Preconditions.checkArgument(builder.mSisaMeters >= 0.0f);
            Preconditions.checkArgumentInRange(
                    builder.mSatelliteClockType, TYPE_UNDEFINED, TYPE_INAV, "SatelliteClockType");
            mTimeOfClockSeconds = builder.mTimeOfClockSeconds;
            mAf0 = builder.mAf0;
            mAf1 = builder.mAf1;
            mAf2 = builder.mAf2;
            mBgdSeconds = builder.mBgdSeconds;
            mSisaMeters = builder.mSisaMeters;
            mSatelliteClockType = builder.mSatelliteClockType;
        }

        /** Returns the time of the clock in seconds since Galileo epoch. */
        @IntRange(from = 0)
        public long getTimeOfClockSeconds() {
            return mTimeOfClockSeconds;
        }

        /** Returns the SV clock bias correction coefficient in seconds. */
        @FloatRange(from = -0.0625f, to = 0.0625f)
        public double getAf0() {
            return mAf0;
        }

        /** Returns the SV clock drift correction coefficient in seconds per second. */
        @FloatRange(from = -1.5e-8f, to = 1.5e-8f)
        public double getAf1() {
            return mAf1;
        }

        /** Returns the SV clock drift rate correction coefficient in seconds per second squared. */
        @FloatRange(from = -5.56e-17f, to = 5.56e-17f)
        public double getAf2() {
            return mAf2;
        }

        /** Returns the broadcast group delay in seconds. */
        @FloatRange(from = -1.2e-7f, to = 1.2e-7f)
        public double getBgdSeconds() {
            return mBgdSeconds;
        }

        /** Returns the signal in space accuracy in meters. */
        @FloatRange(from = 0.0f)
        public double getSisaMeters() {
            return mSisaMeters;
        }

        /** Returns the type of satellite clock. */
        public @SatelliteClockType int getSatelliteClockType() {
            return mSatelliteClockType;
        }

        public static final @NonNull Creator<GalileoSatelliteClockModel> CREATOR =
                new Creator<GalileoSatelliteClockModel>() {
                    @Override
                    @NonNull
                    public GalileoSatelliteClockModel createFromParcel(Parcel in) {
                        final GalileoSatelliteClockModel.Builder galileoSatelliteClockModel =
                                new Builder();
                        galileoSatelliteClockModel.setTimeOfClockSeconds(in.readLong());
                        galileoSatelliteClockModel.setAf0(in.readDouble());
                        galileoSatelliteClockModel.setAf1(in.readDouble());
                        galileoSatelliteClockModel.setAf2(in.readDouble());
                        galileoSatelliteClockModel.setBgdSeconds(in.readDouble());
                        galileoSatelliteClockModel.setSisaMeters(in.readDouble());
                        galileoSatelliteClockModel.setSatelliteClockType(in.readInt());
                        return galileoSatelliteClockModel.build();
                    }

                    @Override
                    public GalileoSatelliteClockModel[] newArray(int size) {
                        return new GalileoSatelliteClockModel[size];
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
            parcel.writeDouble(mBgdSeconds);
            parcel.writeDouble(mSisaMeters);
            parcel.writeInt(mSatelliteClockType);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GalileoSatelliteClockModel[");
            builder.append("timeOfClockSeconds = ").append(mTimeOfClockSeconds);
            builder.append(", af0 = ").append(mAf0);
            builder.append(", af1 = ").append(mAf1);
            builder.append(", af2 = ").append(mAf2);
            builder.append(", bgdSeconds = ").append(mBgdSeconds);
            builder.append(", sisaMeters = ").append(mSisaMeters);
            builder.append(", satelliteClockType = ").append(mSatelliteClockType);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link GalileoSatelliteClockModel}. */
        public static final class Builder {
            private long mTimeOfClockSeconds;
            private double mAf0;
            private double mAf1;
            private double mAf2;
            private double mBgdSeconds;
            private double mSisaMeters;
            private @SatelliteClockType int mSatelliteClockType;

            /** Sets the time of the clock in seconds since Galileo epoch. */
            @NonNull
            public Builder setTimeOfClockSeconds(@IntRange(from = 0) long timeOfClockSeconds) {
                mTimeOfClockSeconds = timeOfClockSeconds;
                return this;
            }

            /** Sets the SV clock bias correction coefficient in seconds. */
            @NonNull
            public Builder setAf0(@FloatRange(from = -0.0625f, to = 0.0625f) double af0) {
                mAf0 = af0;
                return this;
            }

            /** Sets the SV clock drift correction coefficient in seconds per second. */
            @NonNull
            public Builder setAf1(@FloatRange(from = -1.5e-8f, to = 1.5e-8f) double af1) {
                mAf1 = af1;
                return this;
            }

            /**
             * Sets the SV clock drift rate correction coefficient in seconds per second squared.
             */
            @NonNull
            public Builder setAf2(@FloatRange(from = -5.56e-17f, to = 5.56e-17f) double af2) {
                mAf2 = af2;
                return this;
            }

            /** Sets the broadcast group delay in seconds. */
            @NonNull
            public Builder setBgdSeconds(
                    @FloatRange(from = -1.2e-7f, to = 1.2e-7f) double bgdSeconds) {
                mBgdSeconds = bgdSeconds;
                return this;
            }

            /** Sets the signal in space accuracy in meters. */
            @NonNull
            public Builder setSisaMeters(@FloatRange(from = 0.0f) double sisaMeters) {
                mSisaMeters = sisaMeters;
                return this;
            }

            /** Sets the type of satellite clock. */
            @NonNull
            public Builder setSatelliteClockType(@SatelliteClockType int satelliteClockType) {
                mSatelliteClockType = satelliteClockType;
                return this;
            }

            /**
             * Builds a {@link GalileoSatelliteClockModel} instance as specified by this builder.
             */
            @NonNull
            public GalileoSatelliteClockModel build() {
                return new GalileoSatelliteClockModel(this);
            }
        }
    }
}
