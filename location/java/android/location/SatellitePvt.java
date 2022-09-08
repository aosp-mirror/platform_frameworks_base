/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class that contains GNSS satellite position, velocity and time information at the
 * same signal transmission time {@link GnssMeasurement#getReceivedSvTimeNanos()}.
 *
 * <p>The position and velocity must be in ECEF coordinates.
 *
 * <p>If {@link GnssMeasurement#getSatellitePvt()} is derived from Broadcast ephemeris, then the
 * position is already w.r.t. the antenna phase center. However, if
 * {@link GnssMeasurement#getSatellitePvt()} is derived from other modeled orbits, such as
 * long-term orbits, or precise orbits, then the orbits may have been computed w.r.t.
 * the satellite center of mass, and then GNSS vendors are expected to correct for the effect
 * on different phase centers (can differ by meters) of different GNSS signals (e.g. L1, L5)
 * on the reported satellite position. Accordingly, we might observe a different satellite
 * position reported for L1 GnssMeasurement struct compared to L5 GnssMeasurement struct.
 *
 * <p>If {@link GnssMeasurement#getReceivedSvTimeNanos()} is not fully decoded,
 * {@link GnssMeasurement#getSatellitePvt()} could still be reported and
 * {@link GnssMeasurement#getReceivedSvTimeUncertaintyNanos()} would be used to provide confidence.
 * @hide
 */
@SystemApi
public final class SatellitePvt implements Parcelable {
    /**
     * Bit mask for {@link #mFlags} indicating valid satellite position, velocity and clock info
     * fields are stored in the SatellitePvt.
     */
    private static final int HAS_POSITION_VELOCITY_CLOCK_INFO = 1 << 0;

    /**
     * Bit mask for {@link #mFlags} indicating a valid iono delay field is stored in the
     * SatellitePvt.
     */
    private static final int HAS_IONO = 1 << 1;

    /**
     * Bit mask for {@link #mFlags} indicating a valid tropo delay field is stored in the
     * SatellitePvt.
     */
    private static final int HAS_TROPO = 1 << 2;

    /**
     * Bit mask for {@link #mFlags} indicating a valid Issue of Data, Clock field is stored in the
     * SatellitePvt.
     */
    private static final int HAS_ISSUE_OF_DATA_CLOCK = 1 << 3;

    /**
     * Bit mask for {@link #mFlags} indicating a valid Issue of Data, Ephemeris field is stored in
     * the SatellitePvt.
     */
    private static final int HAS_ISSUE_OF_DATA_EPHEMERIS = 1 << 4;

    /**
     * Bit mask for {@link #mFlags} indicating a valid Time of Clock field is stored in the
     * SatellitePvt.
     */
    private static final int HAS_TIME_OF_CLOCK = 1 << 5;

    /**
     * Bit mask for {@link #mFlags} indicating a valid Time of Ephemeris field is stored in
     * the SatellitePvt.
     */
    private static final int HAS_TIME_OF_EPHEMERIS = 1 << 6;


    /** Ephemeris demodulated from broadcast signals */
    public static final int EPHEMERIS_SOURCE_DEMODULATED = 0;

    /**
     * Server provided Normal type ephemeris data, which is similar to broadcast ephemeris in
     * longevity (e.g. SUPL) - lasting for few hours and providing satellite orbit and clock
     * with accuracy of 1 - 2 meters.
     */
    public static final int EPHEMERIS_SOURCE_SERVER_NORMAL = 1;

    /**
     * Server provided Long-Term type ephemeris data, which lasts for many hours to several days
     * and often provides satellite orbit and clock accuracy of 2 - 20 meters.
     */
    public static final int EPHEMERIS_SOURCE_SERVER_LONG_TERM = 2;

    /** Other ephemeris source */
    public static final int EPHEMERIS_SOURCE_OTHER = 3;

    /**
     * Satellite ephemeris source
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({EPHEMERIS_SOURCE_DEMODULATED, EPHEMERIS_SOURCE_SERVER_NORMAL,
            EPHEMERIS_SOURCE_SERVER_LONG_TERM, EPHEMERIS_SOURCE_OTHER})
    public @interface EphemerisSource {
    }

    /**
     * A bitfield of flags indicating the validity of the fields in this SatellitePvt.
     * The bit masks are defined in the constants with prefix HAS_*
     *
     * <p>Fields for which there is no corresponding flag must be filled in with a valid value.
     * For convenience, these are marked as mandatory.
     *
     * <p>Others fields may have invalid information in them, if not marked as valid by the
     * corresponding bit in flags.
     */
    private final int mFlags;

    @Nullable
    private final PositionEcef mPositionEcef;
    @Nullable
    private final VelocityEcef mVelocityEcef;
    @Nullable
    private final ClockInfo mClockInfo;
    private final double mIonoDelayMeters;
    private final double mTropoDelayMeters;
    private final long mTimeOfClockSeconds;
    private final long mTimeOfEphemerisSeconds;
    private final int mIssueOfDataClock;
    private final int mIssueOfDataEphemeris;
    @EphemerisSource
    private final int mEphemerisSource;

    /**
     * Class containing estimates of the satellite position fields in ECEF coordinate frame.
     *
     * <p>The satellite position must be defined at the time of transmission of the signal
     * receivedSvTimeNs.
     */
    public static final class PositionEcef implements Parcelable {
        private final double mXMeters;
        private final double mYMeters;
        private final double mZMeters;
        private final double mUreMeters;

        public PositionEcef(
                double xMeters,
                double yMeters,
                double zMeters,
                double ureMeters) {
            mXMeters = xMeters;
            mYMeters = yMeters;
            mZMeters = zMeters;
            mUreMeters = ureMeters;
        }

        public static final @NonNull Creator<PositionEcef> CREATOR =
                new Creator<PositionEcef>() {
                    @Override
                    public PositionEcef createFromParcel(Parcel in) {
                        return new PositionEcef(
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble()
                        );
                    }

                    @Override
                    public PositionEcef[] newArray(int size) {
                        return new PositionEcef[size];
                    }
                };

        /**
         * Returns the satellite position X in WGS84 ECEF (meters).
         */
        @FloatRange()
        public double getXMeters() {
            return mXMeters;
        }

        /**
         * Returns the satellite position Y in WGS84 ECEF (meters).
         */
        @FloatRange()
        public double getYMeters() {
            return mYMeters;
        }

        /**
         * Returns the satellite position Z in WGS84 ECEF (meters).
         */
        @FloatRange()
        public double getZMeters() {
            return mZMeters;
        }

        /**
         * Returns the signal in Space User Range Error (URE) (meters).
         */
        @FloatRange(from = 0.0f, fromInclusive = false)
        public double getUreMeters() {
            return mUreMeters;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeDouble(mXMeters);
            dest.writeDouble(mYMeters);
            dest.writeDouble(mZMeters);
            dest.writeDouble(mUreMeters);
        }

        @Override
        public String toString() {
            return "PositionEcef{"
                    + "xMeters=" + mXMeters
                    + ", yMeters=" + mYMeters
                    + ", zMeters=" + mZMeters
                    + ", ureMeters=" + mUreMeters
                    + "}";
        }
    }

    /**
     * Class containing estimates of the satellite velocity fields in the ECEF coordinate frame.
     *
     * <p>The satellite velocity must be defined at the time of transmission of the signal
     * receivedSvTimeNs.
     */
    public static final class VelocityEcef implements Parcelable {
        private final double mXMetersPerSecond;
        private final double mYMetersPerSecond;
        private final double mZMetersPerSecond;
        private final double mUreRateMetersPerSecond;

        public VelocityEcef(
                double xMetersPerSecond,
                double yMetersPerSecond,
                double zMetersPerSecond,
                double ureRateMetersPerSecond) {
            mXMetersPerSecond = xMetersPerSecond;
            mYMetersPerSecond = yMetersPerSecond;
            mZMetersPerSecond = zMetersPerSecond;
            mUreRateMetersPerSecond = ureRateMetersPerSecond;
        }

        public static final @NonNull Creator<VelocityEcef> CREATOR =
                new Creator<VelocityEcef>() {
                    @Override
                    public VelocityEcef createFromParcel(Parcel in) {
                        return new VelocityEcef(
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble()
                        );
                    }

                    @Override
                    public VelocityEcef[] newArray(int size) {
                        return new VelocityEcef[size];
                    }
                };

        /**
         * Returns the satellite velocity X in WGS84 ECEF (meters per second).
         */
        @FloatRange()
        public double getXMetersPerSecond() {
            return mXMetersPerSecond;
        }

        /**
         * Returns the satellite velocity Y in WGS84 ECEF (meters per second).
         */
        @FloatRange()
        public double getYMetersPerSecond() {
            return mYMetersPerSecond;
        }

        /**
         *Returns the satellite velocity Z in WGS84 ECEF (meters per second).
         */
        @FloatRange()
        public double getZMetersPerSecond() {
            return mZMetersPerSecond;
        }

        /**
         * Returns the signal in Space User Range Error Rate (URE Rate) (meters per second).
         *
         * <p>It covers satellite velocity error and Satellite clock drift
         * projected to the pseudorange rate measurements.
         */
        @FloatRange(from = 0.0f, fromInclusive = false)
        public double getUreRateMetersPerSecond() {
            return mUreRateMetersPerSecond;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeDouble(mXMetersPerSecond);
            dest.writeDouble(mYMetersPerSecond);
            dest.writeDouble(mZMetersPerSecond);
            dest.writeDouble(mUreRateMetersPerSecond);
        }

        @Override
        public String toString() {
            return "VelocityEcef{"
                    + "xMetersPerSecond=" + mXMetersPerSecond
                    + ", yMetersPerSecond=" + mYMetersPerSecond
                    + ", zMetersPerSecond=" + mZMetersPerSecond
                    + ", ureRateMetersPerSecond=" + mUreRateMetersPerSecond
                    + "}";
        }
    }

    /**
     * Class containing estimates of the satellite clock info.
     */
    public static final class ClockInfo implements Parcelable {
        private final double mHardwareCodeBiasMeters;
        private final double mTimeCorrectionMeters;
        private final double mClockDriftMetersPerSecond;

        public ClockInfo(
                double hardwareCodeBiasMeters,
                double timeCorrectionMeters,
                double clockDriftMetersPerSecond) {
            mHardwareCodeBiasMeters = hardwareCodeBiasMeters;
            mTimeCorrectionMeters = timeCorrectionMeters;
            mClockDriftMetersPerSecond = clockDriftMetersPerSecond;
        }

        public static final @NonNull Creator<ClockInfo> CREATOR =
                new Creator<ClockInfo>() {
                    @Override
                    public ClockInfo createFromParcel(Parcel in) {
                        return new ClockInfo(
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble()
                        );
                    }

                    @Override
                    public ClockInfo[] newArray(int size) {
                        return new ClockInfo[size];
                    }
                };

        /**
         * Returns the satellite hardware code bias of the reported code type w.r.t
         * ionosphere-free measurement in meters.
         *
         * <p>When broadcast ephemeris is used, this is the offset caused
         * by the satellite hardware delays at different frequencies;
         * e.g. in IS-GPS-705D, this term is described in Section
         * 20.3.3.3.1.2.1.
         *
         * <p>For GPS this term is ~10ns, and affects the satellite position
         * computation by less than a millimeter.
         */
        @FloatRange()
        public double getHardwareCodeBiasMeters() {
            return mHardwareCodeBiasMeters;
        }

        /**
         * Returns the satellite time correction for ionospheric-free signal measurement
         * (meters). The satellite clock correction for the given signal type
         * = satTimeCorrectionMeters - satHardwareCodeBiasMeters.
         *
         * <p>When broadcast ephemeris is used, this is the offset modeled in the
         * clock terms broadcast over the air by the satellites;
         * e.g. in IS-GPS-200H, Section 20.3.3.3.3.1, this term is
         * ∆tsv = af0 + af1(t - toc) + af2(t - toc)^2 + ∆tr.
         *
         * <p>If another source of ephemeris is used for SatellitePvt, then the
         * equivalent value of satTimeCorrection must be provided.
         *
         * <p>For GPS this term is ~1ms, and affects the satellite position
         * computation by ~1m.
         */
        @FloatRange()
        public double getTimeCorrectionMeters() {
            return mTimeCorrectionMeters;
        }

        /**
         * Returns the satellite clock drift (meters per second).
         */
        @FloatRange()
        public double getClockDriftMetersPerSecond() {
            return mClockDriftMetersPerSecond;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeDouble(mHardwareCodeBiasMeters);
            dest.writeDouble(mTimeCorrectionMeters);
            dest.writeDouble(mClockDriftMetersPerSecond);
        }

        @Override
        public String toString() {
            return "ClockInfo{"
                    + "hardwareCodeBiasMeters=" + mHardwareCodeBiasMeters
                    + ", timeCorrectionMeters=" + mTimeCorrectionMeters
                    + ", clockDriftMetersPerSecond=" + mClockDriftMetersPerSecond
                    + "}";
        }
    }

    private SatellitePvt(
            int flags,
            @Nullable PositionEcef positionEcef,
            @Nullable VelocityEcef velocityEcef,
            @Nullable ClockInfo clockInfo,
            double ionoDelayMeters,
            double tropoDelayMeters,
            long timeOfClockSeconds,
            long timeOfEphemerisSeconds,
            int issueOfDataClock,
            int issueOfDataEphemeris,
            @EphemerisSource int ephemerisSource) {
        mFlags = flags;
        mPositionEcef = positionEcef;
        mVelocityEcef = velocityEcef;
        mClockInfo = clockInfo;
        mIonoDelayMeters = ionoDelayMeters;
        mTropoDelayMeters = tropoDelayMeters;
        mTimeOfClockSeconds = timeOfClockSeconds;
        mTimeOfEphemerisSeconds = timeOfEphemerisSeconds;
        mIssueOfDataClock = issueOfDataClock;
        mIssueOfDataEphemeris = issueOfDataEphemeris;
        mEphemerisSource = ephemerisSource;
    }

    /**
     * Returns a {@link PositionEcef} object that contains estimates of the satellite
     * position fields in ECEF coordinate frame.
     */
    @Nullable
    public PositionEcef getPositionEcef() {
        return mPositionEcef;
    }

    /**
     * Returns a {@link VelocityEcef} object that contains estimates of the satellite
     * velocity fields in the ECEF coordinate frame.
     */
    @Nullable
    public VelocityEcef getVelocityEcef() {
        return mVelocityEcef;
    }

    /**
     * Returns a {@link ClockInfo} object that contains estimates of the satellite
     * clock info.
     */
    @Nullable
    public ClockInfo getClockInfo() {
        return mClockInfo;
    }

    /**
     * Returns the ionospheric delay in meters.
     */
    @FloatRange()
    public double getIonoDelayMeters() {
        return mIonoDelayMeters;
    }

    /**
     * Returns the tropospheric delay in meters.
     */
    @FloatRange()
    public double getTropoDelayMeters() {
        return mTropoDelayMeters;
    }

    /**
     * Issue of Data, Clock.
     *
     * <p>This is defined in GPS ICD200 documentation (e.g.,
     * <a href="https://www.gps.gov/technical/icwg/IS-GPS-200H.pdf"></a>).
     *
     * <p>This field is valid if {@link #hasIssueOfDataClock()} is true.
     */
    @IntRange(from = 0, to = 1023)
    public int getIssueOfDataClock() {
        return mIssueOfDataClock;
    }

    /**
     * Issue of Data, Ephemeris.
     *
     * <p>This is defined in GPS ICD200 documentation (e.g.,
     * <a href="https://www.gps.gov/technical/icwg/IS-GPS-200H.pdf"></a>).
     *
     * <p>This field is valid if {@link #hasIssueOfDataEphemeris()} is true.
     */
    @IntRange(from = 0, to = 255)
    public int getIssueOfDataEphemeris() {
        return mIssueOfDataEphemeris;
    }

    /**
     * Time of Clock in seconds.
     *
     * <p>The value is in seconds since GPS epoch, regardless of the constellation.
     *
     * <p>The value is not encoded as in GPS ICD200 documentation.
     *
     * <p>This field is valid if {@link #hasTimeOfClockSeconds()} is true.
     */
    @IntRange(from = 0)
    public long getTimeOfClockSeconds() {
        return mTimeOfClockSeconds;
    }

    /**
     * Time of ephemeris in seconds.
     *
     * <p>The value is in seconds since GPS epoch, regardless of the constellation.
     *
     * <p>The value is not encoded as in GPS ICD200 documentation.
     *
     * <p>This field is valid if {@link #hasTimeOfEphemerisSeconds()} is true.
     */
    @IntRange(from = 0)
    public long getTimeOfEphemerisSeconds() {
        return mTimeOfEphemerisSeconds;
    }

    /**
     * Satellite ephemeris source.
     */
    @EphemerisSource
    public int getEphemerisSource() {
        return mEphemerisSource;
    }

    /** Returns {@code true} if {@link #getPositionEcef()}, {@link #getVelocityEcef()},
     * and {@link #getClockInfo()} are valid.
     */
    public boolean hasPositionVelocityClockInfo() {
        return (mFlags & HAS_POSITION_VELOCITY_CLOCK_INFO) != 0;
    }

    /** Returns {@code true} if {@link #getIonoDelayMeters()} is valid. */
    public boolean hasIono() {
        return (mFlags & HAS_IONO) != 0;
    }

    /** Returns {@code true} if {@link #getTropoDelayMeters()} is valid. */
    public boolean hasTropo() {
        return (mFlags & HAS_TROPO) != 0;
    }

    /** Returns {@code true} if {@link #getIssueOfDataClock()} is valid. */
    public boolean hasIssueOfDataClock() {
        return (mFlags & HAS_ISSUE_OF_DATA_CLOCK) != 0;
    }

    /** Returns {@code true} if {@link #getIssueOfDataEphemeris()} is valid. */
    public boolean hasIssueOfDataEphemeris() {
        return (mFlags & HAS_ISSUE_OF_DATA_EPHEMERIS) != 0;
    }

    /** Returns {@code true} if {@link #getTimeOfClockSeconds()} ()} is valid. */
    public boolean hasTimeOfClockSeconds() {
        return (mFlags & HAS_TIME_OF_CLOCK) != 0;
    }

    /** Returns {@code true} if {@link #getTimeOfEphemerisSeconds()} is valid. */
    public boolean hasTimeOfEphemerisSeconds() {
        return (mFlags & HAS_TIME_OF_EPHEMERIS) != 0;
    }

    public static final @android.annotation.NonNull Creator<SatellitePvt> CREATOR =
            new Creator<SatellitePvt>() {
                @Override
                @Nullable
                public SatellitePvt createFromParcel(Parcel in) {
                    int flags = in.readInt();
                    ClassLoader classLoader = getClass().getClassLoader();
                    PositionEcef positionEcef = in.readParcelable(classLoader,
                            android.location.SatellitePvt.PositionEcef.class);
                    VelocityEcef velocityEcef = in.readParcelable(classLoader,
                            android.location.SatellitePvt.VelocityEcef.class);
                    ClockInfo clockInfo = in.readParcelable(classLoader,
                            android.location.SatellitePvt.ClockInfo.class);
                    double ionoDelayMeters = in.readDouble();
                    double tropoDelayMeters = in.readDouble();
                    long toc = in.readLong();
                    long toe = in.readLong();
                    int iodc = in.readInt();
                    int iode = in.readInt();
                    int ephemerisSource = in.readInt();

                    return new SatellitePvt(
                            flags,
                            positionEcef,
                            velocityEcef,
                            clockInfo,
                            ionoDelayMeters,
                            tropoDelayMeters,
                            toc,
                            toe,
                            iodc,
                            iode,
                            ephemerisSource);
                }

                @Override
                public SatellitePvt[] newArray(int size) {
                    return new SatellitePvt[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mFlags);
        parcel.writeParcelable(mPositionEcef, flags);
        parcel.writeParcelable(mVelocityEcef, flags);
        parcel.writeParcelable(mClockInfo, flags);
        parcel.writeDouble(mIonoDelayMeters);
        parcel.writeDouble(mTropoDelayMeters);
        parcel.writeLong(mTimeOfClockSeconds);
        parcel.writeLong(mTimeOfEphemerisSeconds);
        parcel.writeInt(mIssueOfDataClock);
        parcel.writeInt(mIssueOfDataEphemeris);
        parcel.writeInt(mEphemerisSource);
    }

    @Override
    public String toString() {
        return "SatellitePvt["
                + "Flags=" + mFlags
                + ", PositionEcef=" + mPositionEcef
                + ", VelocityEcef=" + mVelocityEcef
                + ", ClockInfo=" + mClockInfo
                + ", IonoDelayMeters=" + mIonoDelayMeters
                + ", TropoDelayMeters=" + mTropoDelayMeters
                + ", TimeOfClockSeconds=" + mTimeOfClockSeconds
                + ", TimeOfEphemerisSeconds=" + mTimeOfEphemerisSeconds
                + ", IssueOfDataClock=" + mIssueOfDataClock
                + ", IssueOfDataEphemeris=" + mIssueOfDataEphemeris
                + ", EphemerisSource=" + mEphemerisSource
                + "]";
    }

    /**
     * Builder class for SatellitePvt.
     */
    public static final class Builder {
        /**
         * For documentation of below fields, see corresponding fields in {@link
         * SatellitePvt}.
         */
        private int mFlags;
        @Nullable private PositionEcef mPositionEcef;
        @Nullable private VelocityEcef mVelocityEcef;
        @Nullable private ClockInfo mClockInfo;
        private double mIonoDelayMeters;
        private double mTropoDelayMeters;
        private long mTimeOfClockSeconds;
        private long mTimeOfEphemerisSeconds;
        private int mIssueOfDataClock;
        private int mIssueOfDataEphemeris;
        @EphemerisSource
        private int mEphemerisSource = EPHEMERIS_SOURCE_OTHER;

        /**
         * Set position ECEF.
         *
         * @param positionEcef position ECEF object
         * @return builder object
         */
        @NonNull
        public Builder setPositionEcef(@NonNull PositionEcef positionEcef) {
            mPositionEcef = positionEcef;
            updateFlags();
            return this;
        }

        /**
         * Set velocity ECEF.
         *
         * @param velocityEcef velocity ECEF object
         * @return builder object
         */
        @NonNull
        public Builder setVelocityEcef(@NonNull VelocityEcef velocityEcef) {
            mVelocityEcef = velocityEcef;
            updateFlags();
            return this;
        }

        /**
         * Set clock info.
         *
         * @param clockInfo clock info object
         * @return builder object
         */
        @NonNull
        public Builder setClockInfo(@NonNull ClockInfo clockInfo) {
            mClockInfo = clockInfo;
            updateFlags();
            return this;
        }

        private void updateFlags() {
            if (mPositionEcef != null && mVelocityEcef != null && mClockInfo != null) {
                mFlags = (byte) (mFlags | HAS_POSITION_VELOCITY_CLOCK_INFO);
            }
        }

        /**
         * Set ionospheric delay in meters.
         *
         * @param ionoDelayMeters ionospheric delay (meters)
         * @return builder object
         */
        @NonNull
        public Builder setIonoDelayMeters(
                @FloatRange(from = 0.0f, to = 100.0f) double ionoDelayMeters) {
            mIonoDelayMeters = ionoDelayMeters;
            mFlags = (byte) (mFlags | HAS_IONO);
            return this;
        }

        /**
         * Set tropospheric delay in meters.
         *
         * @param tropoDelayMeters tropospheric delay (meters)
         * @return builder object
         */
        @NonNull
        public Builder setTropoDelayMeters(
                @FloatRange(from = 0.0f, to = 100.0f) double tropoDelayMeters) {
            mTropoDelayMeters = tropoDelayMeters;
            mFlags = (byte) (mFlags | HAS_TROPO);
            return this;
        }

        /**
         * Set time of clock in seconds.
         *
         * <p>The value is in seconds since GPS epoch, regardless of the constellation.
         *
         * <p>The value is not encoded as in GPS ICD200 documentation.
         *
         * @param timeOfClockSeconds time of clock (seconds)
         * @return builder object
         */
        @NonNull
        public Builder setTimeOfClockSeconds(@IntRange(from = 0) long timeOfClockSeconds) {
            Preconditions.checkArgumentNonnegative(timeOfClockSeconds);
            mTimeOfClockSeconds = timeOfClockSeconds;
            mFlags = (byte) (mFlags | HAS_TIME_OF_CLOCK);
            return this;
        }

        /**
         * Set time of ephemeris in seconds.
         *
         * <p>The value is in seconds since GPS epoch, regardless of the constellation.
         *
         * <p>The value is not encoded as in GPS ICD200 documentation.
         *
         * @param timeOfEphemerisSeconds time of ephemeris (seconds)
         * @return builder object
         */
        @NonNull
        public Builder setTimeOfEphemerisSeconds(@IntRange(from = 0) long timeOfEphemerisSeconds) {
            Preconditions.checkArgumentNonnegative(timeOfEphemerisSeconds);
            mTimeOfEphemerisSeconds = timeOfEphemerisSeconds;
            mFlags = (byte) (mFlags | HAS_TIME_OF_EPHEMERIS);
            return this;
        }

        /**
         * Set issue of data, clock.
         *
         * @param issueOfDataClock issue of data, clock.
         * @return builder object
         */
        @NonNull
        public Builder setIssueOfDataClock(@IntRange(from = 0, to = 1023) int issueOfDataClock) {
            Preconditions.checkArgumentInRange(issueOfDataClock, 0, 1023, "issueOfDataClock");
            mIssueOfDataClock = issueOfDataClock;
            mFlags = (byte) (mFlags | HAS_ISSUE_OF_DATA_CLOCK);
            return this;
        }

        /**
         * Set issue of data, ephemeris.
         *
         * @param issueOfDataEphemeris issue of data, ephemeris.
         * @return builder object
         */
        @NonNull
        public Builder setIssueOfDataEphemeris(
                @IntRange(from = 0, to = 255) int issueOfDataEphemeris) {
            Preconditions.checkArgumentInRange(issueOfDataEphemeris, 0, 255,
                    "issueOfDataEphemeris");
            mIssueOfDataEphemeris = issueOfDataEphemeris;
            mFlags = (byte) (mFlags | HAS_ISSUE_OF_DATA_EPHEMERIS);
            return this;
        }

        /**
         * Set satellite ephemeris source.
         *
         * @param ephemerisSource satellite ephemeris source
         * @return builder object
         */
        @NonNull
        public Builder setEphemerisSource(@EphemerisSource int ephemerisSource) {
            Preconditions.checkArgument(ephemerisSource == EPHEMERIS_SOURCE_DEMODULATED
                    || ephemerisSource == EPHEMERIS_SOURCE_SERVER_NORMAL
                    || ephemerisSource == EPHEMERIS_SOURCE_SERVER_LONG_TERM
                    || ephemerisSource == EPHEMERIS_SOURCE_OTHER);
            mEphemerisSource = ephemerisSource;
            return this;
        }

        /**
         * Build SatellitePvt object.
         *
         * @return instance of SatellitePvt
         */
        @NonNull
        public SatellitePvt build() {
            return new SatellitePvt(mFlags, mPositionEcef, mVelocityEcef, mClockInfo,
                    mIonoDelayMeters, mTropoDelayMeters, mTimeOfClockSeconds,
                    mTimeOfEphemerisSeconds,
                    mIssueOfDataClock, mIssueOfDataEphemeris,
                    mEphemerisSource);
        }
    }
}
