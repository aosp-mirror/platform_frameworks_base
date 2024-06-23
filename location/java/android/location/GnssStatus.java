/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * This class represents the current state of the GNSS engine and is used in conjunction with
 * {@link GnssStatus.Callback}.
 *
 * @see LocationManager#registerGnssStatusCallback
 * @see GnssStatus.Callback
 */
public final class GnssStatus implements Parcelable {

    // These must match the definitions in GNSS HAL.
    //
    // Note: these constants are also duplicated in GnssStatusCompat.java in the androidx support
    // library. if adding a constellation, please update that file as well.

    /** Unknown constellation type. */
    public static final int CONSTELLATION_UNKNOWN = 0;
    /** Constellation type constant for GPS. */
    public static final int CONSTELLATION_GPS = 1;
    /** Constellation type constant for SBAS. */
    public static final int CONSTELLATION_SBAS = 2;
    /** Constellation type constant for Glonass. */
    public static final int CONSTELLATION_GLONASS = 3;
    /** Constellation type constant for QZSS. */
    public static final int CONSTELLATION_QZSS = 4;
    /** Constellation type constant for Beidou. */
    public static final int CONSTELLATION_BEIDOU = 5;
    /** Constellation type constant for Galileo. */
    public static final int CONSTELLATION_GALILEO = 6;
    /** Constellation type constant for IRNSS. */
    public static final int CONSTELLATION_IRNSS = 7;
    /** @hide */
    public static final int CONSTELLATION_COUNT = 8;

    private static final int SVID_FLAGS_NONE = 0;
    private static final int SVID_FLAGS_HAS_EPHEMERIS_DATA = (1 << 0);
    private static final int SVID_FLAGS_HAS_ALMANAC_DATA = (1 << 1);
    private static final int SVID_FLAGS_USED_IN_FIX = (1 << 2);
    private static final int SVID_FLAGS_HAS_CARRIER_FREQUENCY = (1 << 3);
    private static final int SVID_FLAGS_HAS_BASEBAND_CN0 = (1 << 4);

    private static final int SVID_SHIFT_WIDTH = 12;
    private static final int CONSTELLATION_TYPE_SHIFT_WIDTH = 8;
    private static final int CONSTELLATION_TYPE_MASK = 0xf;

    /**
     * Used for receiving notifications when GNSS events happen.
     *
     * @see LocationManager#registerGnssStatusCallback
     */
    public static abstract class Callback {
        /**
         * Called when GNSS system has started.
         */
        public void onStarted() {
        }

        /**
         * Called when GNSS system has stopped.
         */
        public void onStopped() {
        }

        /**
         * Called when the GNSS system has received its first fix since starting.
         *
         * @param ttffMillis the time from start to first fix in milliseconds.
         */
        public void onFirstFix(int ttffMillis) {
        }

        /**
         * Called periodically to report GNSS satellite status.
         *
         * @param status the current status of all satellites.
         */
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
        }
    }

    /**
     * Constellation type.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CONSTELLATION_UNKNOWN, CONSTELLATION_GPS, CONSTELLATION_SBAS, CONSTELLATION_GLONASS,
            CONSTELLATION_QZSS, CONSTELLATION_BEIDOU, CONSTELLATION_GALILEO, CONSTELLATION_IRNSS})
    public @interface ConstellationType {
    }

    /**
     * Create a GnssStatus that wraps the given arguments without any additional overhead. Callers
     * are responsible for guaranteeing that the arguments are never modified after calling this
     * method.
     *
     * @hide
     */
    @NonNull
    public static GnssStatus wrap(int svCount, int[] svidWithFlags, float[] cn0DbHzs,
            float[] elevations, float[] azimuths, float[] carrierFrequencies,
            float[] basebandCn0DbHzs) {
        Preconditions.checkState(svCount >= 0);
        Preconditions.checkState(svidWithFlags.length >= svCount);
        Preconditions.checkState(elevations.length >= svCount);
        Preconditions.checkState(azimuths.length >= svCount);
        Preconditions.checkState(carrierFrequencies.length >= svCount);
        Preconditions.checkState(basebandCn0DbHzs.length >= svCount);

        return new GnssStatus(svCount, svidWithFlags, cn0DbHzs, elevations, azimuths,
                carrierFrequencies, basebandCn0DbHzs);
    }

    private final int mSvCount;
    private final int[] mSvidWithFlags;
    private final float[] mCn0DbHzs;
    private final float[] mElevations;
    private final float[] mAzimuths;
    private final float[] mCarrierFrequencies;
    private final float[] mBasebandCn0DbHzs;

    private GnssStatus(int svCount, int[] svidWithFlags, float[] cn0DbHzs, float[] elevations,
            float[] azimuths, float[] carrierFrequencies, float[] basebandCn0DbHzs) {
        mSvCount = svCount;
        mSvidWithFlags = svidWithFlags;
        mCn0DbHzs = cn0DbHzs;
        mElevations = elevations;
        mAzimuths = azimuths;
        mCarrierFrequencies = carrierFrequencies;
        mBasebandCn0DbHzs = basebandCn0DbHzs;
    }

    /**
     * Gets the total number of satellites in satellite list.
     */
    @IntRange(from = 0)
    public int getSatelliteCount() {
        return mSvCount;
    }

    /**
     * Retrieves the constellation type of the satellite at the specified index.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @ConstellationType
    public int getConstellationType(@IntRange(from = 0) int satelliteIndex) {
        return ((mSvidWithFlags[satelliteIndex] >> CONSTELLATION_TYPE_SHIFT_WIDTH)
                & CONSTELLATION_TYPE_MASK);
    }

    /**
     * Gets the identification number for the satellite at the specific index.
     *
     * <p>This svid is pseudo-random number for most constellations. It is FCN &amp; OSN number for
     * Glonass.
     *
     * <p>The distinction is made by looking at constellation field
     * {@link #getConstellationType(int)} Expected values are in the range of:
     *
     * <ul>
     * <li>GPS: 1-32</li>
     * <li>SBAS: 120-151, 183-192</li>
     * <li>GLONASS: One of: OSN, or FCN+100
     * <ul>
     * <li>1-25 as the orbital slot number (OSN) (preferred, if known)</li>
     * <li>93-106 as the frequency channel number (FCN) (-7 to +6) plus 100.
     * i.e. encode FCN of -7 as 93, 0 as 100, and +6 as 106</li>
     * </ul></li>
     * <li>QZSS: 183-206</li>
     * <li>Galileo: 1-36</li>
     * <li>Beidou: 1-63</li>
     * <li>IRNSS: 1-14</li>
     * </ul>
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @IntRange(from = 1, to = 206)
    public int getSvid(@IntRange(from = 0) int satelliteIndex) {
        return mSvidWithFlags[satelliteIndex] >> SVID_SHIFT_WIDTH;
    }

    /**
     * Retrieves the carrier-to-noise density at the antenna of the satellite at the specified index
     * in dB-Hz.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = 0, to = 63)
    public float getCn0DbHz(@IntRange(from = 0) int satelliteIndex) {
        return mCn0DbHzs[satelliteIndex];
    }

    /**
     * Retrieves the elevation of the satellite at the specified index.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = -90, to = 90)
    public float getElevationDegrees(@IntRange(from = 0) int satelliteIndex) {
        return mElevations[satelliteIndex];
    }

    /**
     * Retrieves the azimuth the satellite at the specified index.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = 0, to = 360)
    public float getAzimuthDegrees(@IntRange(from = 0) int satelliteIndex) {
        return mAzimuths[satelliteIndex];
    }

    /**
     * Reports whether the satellite at the specified index has ephemeris data.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean hasEphemerisData(@IntRange(from = 0) int satelliteIndex) {
        return (mSvidWithFlags[satelliteIndex] & SVID_FLAGS_HAS_EPHEMERIS_DATA) != 0;
    }

    /**
     * Reports whether the satellite at the specified index has almanac data.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean hasAlmanacData(@IntRange(from = 0) int satelliteIndex) {
        return (mSvidWithFlags[satelliteIndex] & SVID_FLAGS_HAS_ALMANAC_DATA) != 0;
    }

    /**
     * Reports whether the satellite at the specified index was used in the calculation of the most
     * recent position fix.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean usedInFix(@IntRange(from = 0) int satelliteIndex) {
        return (mSvidWithFlags[satelliteIndex] & SVID_FLAGS_USED_IN_FIX) != 0;
    }

    /**
     * Reports whether a valid {@link #getCarrierFrequencyHz(int satelliteIndex)} is available.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean hasCarrierFrequencyHz(@IntRange(from = 0) int satelliteIndex) {
        return (mSvidWithFlags[satelliteIndex] & SVID_FLAGS_HAS_CARRIER_FREQUENCY) != 0;
    }

    /**
     * Gets the carrier frequency of the signal tracked.
     *
     * <p>For example it can be the GPS central frequency for L1 = 1575.45 MHz, or L2 = 1227.60
     * MHz, L5 = 1176.45 MHz, varying GLO channels, etc.
     *
     * <p>The value is only available if {@link #hasCarrierFrequencyHz(int satelliteIndex)} is
     * {@code true}.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = 0)
    public float getCarrierFrequencyHz(@IntRange(from = 0) int satelliteIndex) {
        return mCarrierFrequencies[satelliteIndex];
    }

    /**
     * Reports whether a valid {@link #getBasebandCn0DbHz(int satelliteIndex)} is available.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean hasBasebandCn0DbHz(@IntRange(from = 0) int satelliteIndex) {
        return (mSvidWithFlags[satelliteIndex] & SVID_FLAGS_HAS_BASEBAND_CN0) != 0;
    }

    /**
     * Retrieves the baseband carrier-to-noise density of the satellite at the specified index in
     * dB-Hz.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = 0, to = 63)
    public float getBasebandCn0DbHz(@IntRange(from = 0) int satelliteIndex) {
        return mBasebandCn0DbHzs[satelliteIndex];
    }

    /**
     * Returns the string representation of a constellation type.
     *
     * @param constellationType the constellation type.
     * @return the string representation.
     * @hide
     */
    @NonNull
    public static String constellationTypeToString(@ConstellationType int constellationType) {
        switch (constellationType) {
            case CONSTELLATION_UNKNOWN:
                return "UNKNOWN";
            case CONSTELLATION_GPS:
                return "GPS";
            case CONSTELLATION_SBAS:
                return "SBAS";
            case CONSTELLATION_GLONASS:
                return "GLONASS";
            case CONSTELLATION_QZSS:
                return "QZSS";
            case CONSTELLATION_BEIDOU:
                return "BEIDOU";
            case CONSTELLATION_GALILEO:
                return "GALILEO";
            case CONSTELLATION_IRNSS:
                return "IRNSS";
            default:
                return Integer.toString(constellationType);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GnssStatus)) {
            return false;
        }

        GnssStatus that = (GnssStatus) o;
        return mSvCount == that.mSvCount
                && Arrays.equals(mSvidWithFlags, that.mSvidWithFlags)
                && Arrays.equals(mCn0DbHzs, that.mCn0DbHzs)
                && Arrays.equals(mElevations, that.mElevations)
                && Arrays.equals(mAzimuths, that.mAzimuths)
                && Arrays.equals(mCarrierFrequencies, that.mCarrierFrequencies)
                && Arrays.equals(mBasebandCn0DbHzs, that.mBasebandCn0DbHzs);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mSvCount);
        result = 31 * result + Arrays.hashCode(mSvidWithFlags);
        result = 31 * result + Arrays.hashCode(mCn0DbHzs);
        return result;
    }

    public static final @NonNull Creator<GnssStatus> CREATOR = new Creator<GnssStatus>() {
        @Override
        public GnssStatus createFromParcel(Parcel in) {
            int svCount = in.readInt();
            int[] svidWithFlags = new int[svCount];
            float[] cn0DbHzs = new float[svCount];
            float[] elevations = new float[svCount];
            float[] azimuths = new float[svCount];
            float[] carrierFrequencies = new float[svCount];
            float[] basebandCn0DbHzs = new float[svCount];
            for (int i = 0; i < svCount; i++) {
                svidWithFlags[i] = in.readInt();
                cn0DbHzs[i] = in.readFloat();
                elevations[i] = in.readFloat();
                azimuths[i] = in.readFloat();
                carrierFrequencies[i] = in.readFloat();
                basebandCn0DbHzs[i] = in.readFloat();
            }

            return new GnssStatus(svCount, svidWithFlags, cn0DbHzs, elevations, azimuths,
                    carrierFrequencies, basebandCn0DbHzs);
        }

        @Override
        public GnssStatus[] newArray(int size) {
            return new GnssStatus[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mSvCount);
        for (int i = 0; i < mSvCount; i++) {
            parcel.writeInt(mSvidWithFlags[i]);
            parcel.writeFloat(mCn0DbHzs[i]);
            parcel.writeFloat(mElevations[i]);
            parcel.writeFloat(mAzimuths[i]);
            parcel.writeFloat(mCarrierFrequencies[i]);
            parcel.writeFloat(mBasebandCn0DbHzs[i]);
        }
    }

    /**
     * Builder class to help create new GnssStatus instances.
     */
    public static final class Builder {

        private final ArrayList<GnssSvInfo> mSatellites = new ArrayList<>();

        /**
         * Adds a new satellite to the Builder.
         *
         * @param constellationType one of the CONSTELLATION_* constants
         * @param svid the space vehicle identifier
         * @param cn0DbHz carrier-to-noise density at the antenna in dB-Hz
         * @param elevation satellite elevation in degrees
         * @param azimuth satellite azimuth in degrees
         * @param hasEphemeris whether the satellite has ephemeris data
         * @param hasAlmanac whether the satellite has almanac data
         * @param usedInFix whether the satellite was used in the most recent location fix
         * @param hasCarrierFrequency whether carrier frequency data is available
         * @param carrierFrequency satellite carrier frequency in Hz
         * @param hasBasebandCn0DbHz whether baseband carrier-to-noise density is available
         * @param basebandCn0DbHz baseband carrier-to-noise density in dB-Hz
         */
        @NonNull
        public Builder addSatellite(@ConstellationType int constellationType,
                @IntRange(from = 1, to = 200) int svid,
                @FloatRange(from = 0, to = 63) float cn0DbHz,
                @FloatRange(from = -90, to = 90) float elevation,
                @FloatRange(from = 0, to = 360) float azimuth,
                boolean hasEphemeris,
                boolean hasAlmanac,
                boolean usedInFix,
                boolean hasCarrierFrequency,
                @FloatRange(from = 0) float carrierFrequency,
                boolean hasBasebandCn0DbHz,
                @FloatRange(from = 0, to = 63) float basebandCn0DbHz) {
            mSatellites.add(new GnssSvInfo(constellationType, svid, cn0DbHz, elevation, azimuth,
                    hasEphemeris, hasAlmanac, usedInFix, hasCarrierFrequency, carrierFrequency,
                    hasBasebandCn0DbHz, basebandCn0DbHz));
            return this;
        }

        /**
         * Clears all satellites in the Builder.
         */
        @NonNull
        public Builder clearSatellites() {
            mSatellites.clear();
            return this;
        }

        /**
         * Builds a new GnssStatus based on the satellite information in the Builder.
         */
        @NonNull
        public GnssStatus build() {
            int svCount = mSatellites.size();
            int[] svidWithFlags = new int[svCount];
            float[] cn0DbHzs = new float[svCount];
            float[] elevations = new float[svCount];
            float[] azimuths = new float[svCount];
            float[] carrierFrequencies = new float[svCount];
            float[] basebandCn0DbHzs = new float[svCount];

            for (int i = 0; i < svidWithFlags.length; i++) {
                svidWithFlags[i] = mSatellites.get(i).mSvidWithFlags;
            }
            for (int i = 0; i < cn0DbHzs.length; i++) {
                cn0DbHzs[i] = mSatellites.get(i).mCn0DbHz;
            }
            for (int i = 0; i < elevations.length; i++) {
                elevations[i] = mSatellites.get(i).mElevation;
            }
            for (int i = 0; i < azimuths.length; i++) {
                azimuths[i] = mSatellites.get(i).mAzimuth;
            }
            for (int i = 0; i < carrierFrequencies.length; i++) {
                carrierFrequencies[i] = mSatellites.get(i).mCarrierFrequency;
            }
            for (int i = 0; i < basebandCn0DbHzs.length; i++) {
                basebandCn0DbHzs[i] = mSatellites.get(i).mBasebandCn0DbHz;
            }

            return new GnssStatus(svCount, svidWithFlags, cn0DbHzs, elevations, azimuths,
                    carrierFrequencies, basebandCn0DbHzs);
        }
    }

    private static class GnssSvInfo {

        private final int mSvidWithFlags;
        private final float mCn0DbHz;
        private final float mElevation;
        private final float mAzimuth;
        private final float mCarrierFrequency;
        private final float mBasebandCn0DbHz;

        private GnssSvInfo(int constellationType, int svid, float cn0DbHz,
                float elevation, float azimuth, boolean hasEphemeris, boolean hasAlmanac,
                boolean usedInFix, boolean hasCarrierFrequency, float carrierFrequency,
                boolean hasBasebandCn0DbHz, float basebandCn0DbHz) {
            mSvidWithFlags = (svid << SVID_SHIFT_WIDTH)
                    | ((constellationType & CONSTELLATION_TYPE_MASK)
                    << CONSTELLATION_TYPE_SHIFT_WIDTH)
                    | (hasEphemeris ? SVID_FLAGS_HAS_EPHEMERIS_DATA : SVID_FLAGS_NONE)
                    | (hasAlmanac ? SVID_FLAGS_HAS_ALMANAC_DATA : SVID_FLAGS_NONE)
                    | (usedInFix ? SVID_FLAGS_USED_IN_FIX : SVID_FLAGS_NONE)
                    | (hasCarrierFrequency ? SVID_FLAGS_HAS_CARRIER_FREQUENCY : SVID_FLAGS_NONE)
                    | (hasBasebandCn0DbHz ? SVID_FLAGS_HAS_BASEBAND_CN0 : SVID_FLAGS_NONE);
            mCn0DbHz = cn0DbHz;
            mElevation = elevation;
            mAzimuth = azimuth;
            mCarrierFrequency = hasCarrierFrequency ? carrierFrequency : 0;
            mBasebandCn0DbHz = hasBasebandCn0DbHz ? basebandCn0DbHz : 0;
        }
    }
}
