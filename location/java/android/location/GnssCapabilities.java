/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * GNSS chipset capabilities.
 */
public final class GnssCapabilities implements Parcelable {

    // IMPORTANT - must match the Capabilities enum in IGnssCallback.hal
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_SCHEDULING = 1;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_MSB = 1 << 1;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_MSA = 1 << 2;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_SINGLE_SHOT = 1 << 3;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_ON_DEMAND_TIME = 1 << 4;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_GEOFENCING = 1 << 5;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_MEASUREMENTS = 1 << 6;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_NAV_MESSAGES = 1 << 7;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_LOW_POWER_MODE = 1 << 8;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_SATELLITE_BLOCKLIST = 1 << 9;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_MEASUREMENT_CORRECTIONS = 1 << 10;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_ANTENNA_INFO = 1 << 11;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_CORRELATION_VECTOR = 1 << 12;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_SATELLITE_PVT = 1 << 13;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_MEASUREMENT_CORRECTIONS_FOR_DRIVING = 1 << 14;
    /** @hide */
    public static final int TOP_HAL_CAPABILITY_ACCUMULATED_DELTA_RANGE = 1 << 15;

    /** @hide */
    @IntDef(flag = true, prefix = {"TOP_HAL_CAPABILITY_"}, value = {TOP_HAL_CAPABILITY_SCHEDULING,
            TOP_HAL_CAPABILITY_MSB, TOP_HAL_CAPABILITY_MSA, TOP_HAL_CAPABILITY_SINGLE_SHOT,
            TOP_HAL_CAPABILITY_ON_DEMAND_TIME, TOP_HAL_CAPABILITY_GEOFENCING,
            TOP_HAL_CAPABILITY_MEASUREMENTS, TOP_HAL_CAPABILITY_NAV_MESSAGES,
            TOP_HAL_CAPABILITY_LOW_POWER_MODE, TOP_HAL_CAPABILITY_SATELLITE_BLOCKLIST,
            TOP_HAL_CAPABILITY_MEASUREMENT_CORRECTIONS, TOP_HAL_CAPABILITY_ANTENNA_INFO,
            TOP_HAL_CAPABILITY_CORRELATION_VECTOR, TOP_HAL_CAPABILITY_SATELLITE_PVT,
            TOP_HAL_CAPABILITY_MEASUREMENT_CORRECTIONS_FOR_DRIVING,
            TOP_HAL_CAPABILITY_ACCUMULATED_DELTA_RANGE})

    @Retention(RetentionPolicy.SOURCE)
    public @interface TopHalCapabilityFlags {}

    // IMPORTANT - must match the Capabilities enum in IMeasurementCorrectionsCallback.hal
    /** @hide */
    public static final int SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_LOS_SATS = 1;
    /** @hide */
    public static final int SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_EXCESS_PATH_LENGTH = 2;
    /** @hide */
    public static final int SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_REFLECTING_PLANE = 4;

    /** @hide */
    @IntDef(flag = true, prefix = {"SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_"}, value = {
            SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_LOS_SATS,
            SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_EXCESS_PATH_LENGTH,
            SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_REFLECTING_PLANE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SubHalMeasurementCorrectionsCapabilityFlags {}

    // IMPORATANT - must match values in IGnssPowerIndicationCallback.aidl
    /** @hide */
    public static final int SUB_HAL_POWER_CAPABILITY_TOTAL = 1;
    /** @hide */
    public static final int SUB_HAL_POWER_CAPABILITY_SINGLEBAND_TRACKING = 2;
    /** @hide */
    public static final int SUB_HAL_POWER_CAPABILITY_MULTIBAND_TRACKING = 4;
    /** @hide */
    public static final int SUB_HAL_POWER_CAPABILITY_SINGLEBAND_ACQUISITION = 8;
    /** @hide */
    public static final int SUB_HAL_POWER_CAPABILITY_MULTIBAND_ACQUISITION = 16;
    /** @hide */
    public static final int SUB_HAL_POWER_CAPABILITY_OTHER_MODES = 32;

    /** @hide */
    @IntDef(flag = true, prefix = {"SUB_HAL_POWER_CAPABILITY_"}, value = {
            SUB_HAL_POWER_CAPABILITY_TOTAL, SUB_HAL_POWER_CAPABILITY_SINGLEBAND_TRACKING,
            SUB_HAL_POWER_CAPABILITY_MULTIBAND_TRACKING,
            SUB_HAL_POWER_CAPABILITY_SINGLEBAND_ACQUISITION,
            SUB_HAL_POWER_CAPABILITY_MULTIBAND_ACQUISITION,
            SUB_HAL_POWER_CAPABILITY_OTHER_MODES})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SubHalPowerCapabilityFlags {}

    /**
     * Returns an empty GnssCapabilities object.
     *
     * @hide
     */
    public static GnssCapabilities empty() {
        return new GnssCapabilities(0, 0, 0, Collections.emptyList());
    }

    private final @TopHalCapabilityFlags int mTopFlags;
    private final @SubHalMeasurementCorrectionsCapabilityFlags int mMeasurementCorrectionsFlags;
    private final @SubHalPowerCapabilityFlags int mPowerFlags;
    private final @NonNull List<GnssSignalType> mGnssSignalTypes;

    private GnssCapabilities(
            @TopHalCapabilityFlags int topFlags,
            @SubHalMeasurementCorrectionsCapabilityFlags int measurementCorrectionsFlags,
            @SubHalPowerCapabilityFlags int powerFlags,
            @NonNull List<GnssSignalType> gnssSignalTypes) {
        Objects.requireNonNull(gnssSignalTypes);
        mTopFlags = topFlags;
        mMeasurementCorrectionsFlags = measurementCorrectionsFlags;
        mPowerFlags = powerFlags;
        mGnssSignalTypes = Collections.unmodifiableList(gnssSignalTypes);
    }

    /**
     * Returns a new GnssCapabilities object with top hal values set from the given flags.
     *
     * @hide
     */
    public GnssCapabilities withTopHalFlags(@TopHalCapabilityFlags int flags) {
        if (mTopFlags == flags) {
            return this;
        } else {
            return new GnssCapabilities(flags, mMeasurementCorrectionsFlags, mPowerFlags,
                    mGnssSignalTypes);
        }
    }

    /**
     * Returns a new GnssCapabilities object with gnss measurement corrections sub hal values set
     * from the given flags.
     *
     * @hide
     */
    public GnssCapabilities withSubHalMeasurementCorrectionsFlags(
            @SubHalMeasurementCorrectionsCapabilityFlags int flags) {
        if (mMeasurementCorrectionsFlags == flags) {
            return this;
        } else {
            return new GnssCapabilities(mTopFlags, flags, mPowerFlags,
                    mGnssSignalTypes);
        }
    }

    /**
     * Returns a new GnssCapabilities object with gnss measurement corrections sub hal values set
     * from the given flags.
     *
     * @hide
     */
    public GnssCapabilities withSubHalPowerFlags(@SubHalPowerCapabilityFlags int flags) {
        if (mPowerFlags == flags) {
            return this;
        } else {
            return new GnssCapabilities(mTopFlags, mMeasurementCorrectionsFlags, flags,
                    mGnssSignalTypes);
        }
    }

    /**
     * Returns a new GnssCapabilities object with a list of GnssSignalType.
     *
     * @hide
     */
    public GnssCapabilities withSignalTypes(@NonNull List<GnssSignalType> gnssSignalTypes) {
        Objects.requireNonNull(gnssSignalTypes);
        if (mGnssSignalTypes.equals(gnssSignalTypes)) {
            return this;
        } else {
            return new GnssCapabilities(mTopFlags, mMeasurementCorrectionsFlags, mPowerFlags,
                    new ArrayList<>(gnssSignalTypes));
        }
    }

    /**
     * Returns {@code true} if GNSS chipset supports scheduling, {@code false} otherwise.
     */
    public boolean hasScheduling() {
        return (mTopFlags & TOP_HAL_CAPABILITY_SCHEDULING) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports Mobile Station Based assistance, {@code false}
     * otherwise.
     */
    public boolean hasMsb() {
        return (mTopFlags & TOP_HAL_CAPABILITY_MSB) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports Mobile Station Assisted assitance,
     * {@code false} otherwise.
     */
    public boolean hasMsa() {
        return (mTopFlags & TOP_HAL_CAPABILITY_MSA) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports single shot locating, {@code false} otherwise.
     */
    public boolean hasSingleShotFix() {
        return (mTopFlags & TOP_HAL_CAPABILITY_SINGLE_SHOT) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset requests periodic time signal injection from the
     * platform in addition to on-demand and occasional time updates, {@code false} otherwise.
     *
     * <p><em>Note: The naming of this capability and the behavior it controls differ substantially.
     * This is the result of a historic implementation bug, b/73893222.</em>
     */
    public boolean hasOnDemandTime() {
        return (mTopFlags & TOP_HAL_CAPABILITY_ON_DEMAND_TIME) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports geofencing, {@code false} otherwise.
     */
    public boolean hasGeofencing() {
        return (mTopFlags & TOP_HAL_CAPABILITY_GEOFENCING) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports measurements, {@code false} otherwise.
     *
     * @see LocationManager#registerGnssMeasurementsCallback(Executor, GnssMeasurementsEvent.Callback)
     */
    public boolean hasMeasurements() {
        return (mTopFlags & TOP_HAL_CAPABILITY_MEASUREMENTS) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports navigation messages, {@code false} otherwise.
     *
     * @deprecated Use {@link #hasNavigationMessages()} instead.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    public boolean hasNavMessages() {
        return hasNavigationMessages();
    }

    /**
     * Returns {@code true} if GNSS chipset supports navigation messages, {@code false} otherwise.
     *
     * @see LocationManager#registerGnssNavigationMessageCallback(Executor, GnssNavigationMessage.Callback)
     */
    public boolean hasNavigationMessages() {
        return (mTopFlags & TOP_HAL_CAPABILITY_NAV_MESSAGES) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports low power mode, {@code false} otherwise.
     *
     * <p>The low power mode is defined in GNSS HAL. When the low power mode is active, the GNSS
     * hardware must make strong tradeoffs to substantially restrict power use.
     */
    public boolean hasLowPowerMode() {
        return (mTopFlags & TOP_HAL_CAPABILITY_LOW_POWER_MODE) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports satellite blocklists, {@code false} otherwise.
     *
     * @deprecated Use {@link #hasSatelliteBlocklist} instead.
     *
     * @hide
     */
    @SystemApi
    @Deprecated
    public boolean hasSatelliteBlacklist() {
        return (mTopFlags & TOP_HAL_CAPABILITY_SATELLITE_BLOCKLIST) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports satellite blocklists, {@code false} otherwise.
     */
    public boolean hasSatelliteBlocklist() {
        return (mTopFlags & TOP_HAL_CAPABILITY_SATELLITE_BLOCKLIST) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports satellite PVT, {@code false} otherwise.
     */
    public boolean hasSatellitePvt() {
        return (mTopFlags & TOP_HAL_CAPABILITY_SATELLITE_PVT) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports measurement corrections, {@code false}
     * otherwise.
     */
    public boolean hasMeasurementCorrections() {
        return (mTopFlags & TOP_HAL_CAPABILITY_MEASUREMENT_CORRECTIONS) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports antenna info, {@code false} otherwise.
     *
     * @deprecated Use {@link #hasAntennaInfo()} instead.
     */
    @Deprecated
    public boolean hasGnssAntennaInfo() {
        return hasAntennaInfo();
    }

    /**
     * Returns {@code true} if GNSS chipset supports antenna info, {@code false} otherwise.
     *
     * @see LocationManager#registerAntennaInfoListener(Executor, GnssAntennaInfo.Listener)
     */
    public boolean hasAntennaInfo() {
        return (mTopFlags & TOP_HAL_CAPABILITY_ANTENNA_INFO) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports correlation vectors as part of measurements
     * outputs, {@code false} otherwise.
     */
    public boolean hasMeasurementCorrelationVectors() {
        return (mTopFlags & TOP_HAL_CAPABILITY_CORRELATION_VECTOR) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset will benefit from measurement corrections for driving
     * use case if provided, {@code false} otherwise.
     */
    public boolean hasMeasurementCorrectionsForDriving() {
        return (mTopFlags & TOP_HAL_CAPABILITY_MEASUREMENT_CORRECTIONS_FOR_DRIVING) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports accumulated delta range, {@code false}
     * otherwise.
     *
     * <p>The accumulated delta range information can be queried in
     * {@link android.location.GnssMeasurement#getAccumulatedDeltaRangeState()},
     * {@link android.location.GnssMeasurement#getAccumulatedDeltaRangeMeters()}, and
     * {@link android.location.GnssMeasurement#getAccumulatedDeltaRangeUncertaintyMeters()}.
     */
    public boolean hasAccumulatedDeltaRange() {
        return (mTopFlags & TOP_HAL_CAPABILITY_ACCUMULATED_DELTA_RANGE) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports line-of-sight satellite identification
     * measurement corrections, {@code false} otherwise.
     */
    public boolean hasMeasurementCorrectionsLosSats() {
        return (mMeasurementCorrectionsFlags & SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_LOS_SATS)
                != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports per satellite excess-path-length measurement
     * corrections, {@code false} otherwise.
     */
    public boolean hasMeasurementCorrectionsExcessPathLength() {
        return (mMeasurementCorrectionsFlags
                & SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_EXCESS_PATH_LENGTH) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports reflecting plane measurement corrections,
     * {@code false} otherwise.
     *
     * @deprecated Use {@link #hasMeasurementCorrectionsReflectingPlane()} instead.
     *
     * @hide
     */
    @SystemApi
    public boolean hasMeasurementCorrectionsReflectingPane() {
        return hasMeasurementCorrectionsReflectingPlane();
    }

    /**
     * Returns {@code true} if GNSS chipset supports reflecting plane measurement corrections,
     * {@code false} otherwise.
     */
    public boolean hasMeasurementCorrectionsReflectingPlane() {
        return (mMeasurementCorrectionsFlags
                & SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_REFLECTING_PLANE) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports measuring power totals, {@code false}
     * otherwise.
     */
    public boolean hasPowerTotal() {
        return (mPowerFlags & SUB_HAL_POWER_CAPABILITY_TOTAL) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports measuring single-band tracking power,
     * {@code false} otherwise.
     */
    public boolean hasPowerSinglebandTracking() {
        return (mPowerFlags & SUB_HAL_POWER_CAPABILITY_SINGLEBAND_TRACKING) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports measuring multi-band tracking power,
     * {@code false} otherwise.
     */
    public boolean hasPowerMultibandTracking() {
        return (mPowerFlags & SUB_HAL_POWER_CAPABILITY_MULTIBAND_TRACKING) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports measuring single-band acquisition power,
     * {@code false} otherwise.
     */
    public boolean hasPowerSinglebandAcquisition() {
        return (mPowerFlags & SUB_HAL_POWER_CAPABILITY_SINGLEBAND_ACQUISITION) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports measuring multi-band acquisition power,
     * {@code false} otherwise.
     */
    public boolean hasPowerMultibandAcquisition() {
        return (mPowerFlags & SUB_HAL_POWER_CAPABILITY_MULTIBAND_ACQUISITION) != 0;
    }

    /**
     * Returns {@code true} if GNSS chipset supports measuring OEM defined mode power, {@code false}
     * otherwise.
     */
    public boolean hasPowerOtherModes() {
        return (mPowerFlags & SUB_HAL_POWER_CAPABILITY_OTHER_MODES) != 0;
    }

    /**
     * Returns the list of {@link GnssSignalType}s that the GNSS chipset supports.
     */
    @NonNull
    public List<GnssSignalType> getGnssSignalTypes() {
        return mGnssSignalTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GnssCapabilities)) {
            return false;
        }

        GnssCapabilities that = (GnssCapabilities) o;
        return mTopFlags == that.mTopFlags
                && mMeasurementCorrectionsFlags == that.mMeasurementCorrectionsFlags
                && mPowerFlags == that.mPowerFlags
                && mGnssSignalTypes.equals(that.mGnssSignalTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTopFlags, mMeasurementCorrectionsFlags, mPowerFlags, mGnssSignalTypes);
    }

    public static final @NonNull Creator<GnssCapabilities> CREATOR =
            new Creator<GnssCapabilities>() {
                @Override
                public GnssCapabilities createFromParcel(Parcel in) {
                    return new GnssCapabilities(in.readInt(), in.readInt(), in.readInt(),
                            in.createTypedArrayList(GnssSignalType.CREATOR));
                }

                @Override
                public GnssCapabilities[] newArray(int size) {
                    return new GnssCapabilities[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mTopFlags);
        parcel.writeInt(mMeasurementCorrectionsFlags);
        parcel.writeInt(mPowerFlags);
        parcel.writeTypedList(mGnssSignalTypes);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        if (hasScheduling()) {
            builder.append("SCHEDULING ");
        }
        if (hasMsb()) {
            builder.append("MSB ");
        }
        if (hasMsa()) {
            builder.append("MSA ");
        }
        if (hasSingleShotFix()) {
            builder.append("SINGLE_SHOT ");
        }
        if (hasOnDemandTime()) {
            builder.append("ON_DEMAND_TIME ");
        }
        if (hasGeofencing()) {
            builder.append("GEOFENCING ");
        }
        if (hasMeasurementCorrections()) {
            builder.append("MEASUREMENTS ");
        }
        if (hasNavigationMessages()) {
            builder.append("NAVIGATION_MESSAGES ");
        }
        if (hasLowPowerMode()) {
            builder.append("LOW_POWER_MODE ");
        }
        if (hasSatelliteBlocklist()) {
            builder.append("SATELLITE_BLOCKLIST ");
        }
        if (hasSatellitePvt()) {
            builder.append("SATELLITE_PVT ");
        }
        if (hasMeasurementCorrections()) {
            builder.append("MEASUREMENT_CORRECTIONS ");
        }
        if (hasAntennaInfo()) {
            builder.append("ANTENNA_INFO ");
        }
        if (hasMeasurementCorrelationVectors()) {
            builder.append("MEASUREMENT_CORRELATION_VECTORS ");
        }
        if (hasMeasurementCorrectionsForDriving()) {
            builder.append("MEASUREMENT_CORRECTIONS_FOR_DRIVING ");
        }
        if (hasAccumulatedDeltaRange()) {
            builder.append("ACCUMULATED_DELTA_RANGE ");
        }
        if (hasMeasurementCorrectionsLosSats()) {
            builder.append("LOS_SATS ");
        }
        if (hasMeasurementCorrectionsExcessPathLength()) {
            builder.append("EXCESS_PATH_LENGTH ");
        }
        if (hasMeasurementCorrectionsReflectingPlane()) {
            builder.append("REFLECTING_PLANE ");
        }
        if (hasPowerTotal()) {
            builder.append("TOTAL_POWER ");
        }
        if (hasPowerSinglebandTracking()) {
            builder.append("SINGLEBAND_TRACKING_POWER ");
        }
        if (hasPowerMultibandTracking()) {
            builder.append("MULTIBAND_TRACKING_POWER ");
        }
        if (hasPowerSinglebandAcquisition()) {
            builder.append("SINGLEBAND_ACQUISITION_POWER ");
        }
        if (hasPowerMultibandAcquisition()) {
            builder.append("MULTIBAND_ACQUISITION_POWER ");
        }
        if (hasPowerOtherModes()) {
            builder.append("OTHER_MODES_POWER ");
        }
        if (!mGnssSignalTypes.isEmpty()) {
            builder.append("signalTypes=").append(mGnssSignalTypes).append(" ");
        }
        if (builder.length() > 1) {
            builder.setLength(builder.length() - 1);
        } else {
            builder.append("NONE");
        }
        builder.append("]");
        return builder.toString();
    }

    /**
     * Builder for GnssCapabilities.
     */
    public static final class Builder {

        private @TopHalCapabilityFlags int mTopFlags;
        private @SubHalMeasurementCorrectionsCapabilityFlags int mMeasurementCorrectionsFlags;
        private @SubHalPowerCapabilityFlags int mPowerFlags;
        private @NonNull List<GnssSignalType> mGnssSignalTypes;

        public Builder() {
            mTopFlags = 0;
            mMeasurementCorrectionsFlags = 0;
            mPowerFlags = 0;
            mGnssSignalTypes = Collections.emptyList();
        }

        public Builder(@NonNull GnssCapabilities capabilities) {
            mTopFlags = capabilities.mTopFlags;
            mMeasurementCorrectionsFlags = capabilities.mMeasurementCorrectionsFlags;
            mPowerFlags = capabilities.mPowerFlags;
            mGnssSignalTypes = capabilities.mGnssSignalTypes;
        }

        /**
         * Sets scheduling capability.
         */
        public @NonNull Builder setHasScheduling(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_SCHEDULING, capable);
            return this;
        }

        /**
         * Sets Mobile Station Based capability.
         */
        public @NonNull Builder setHasMsb(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_MSB, capable);
            return this;
        }

        /**
         * Sets Mobile Station Assisted capability.
         */
        public @NonNull Builder setHasMsa(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_MSA, capable);
            return this;
        }

        /**
         * Sets single shot locating capability.
         */
        public @NonNull Builder setHasSingleShotFix(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_SINGLE_SHOT, capable);
            return this;
        }

        /**
         * Sets on demand time capability.
         */
        public @NonNull Builder setHasOnDemandTime(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_ON_DEMAND_TIME, capable);
            return this;
        }

        /**
         * Sets geofencing capability.
         */
        public @NonNull Builder setHasGeofencing(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_GEOFENCING, capable);
            return this;
        }

        /**
         * Sets measurements capability.
         */
        public @NonNull Builder setHasMeasurements(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_MEASUREMENTS, capable);
            return this;
        }

        /**
         * Sets navigation messages capability.
         */
        public @NonNull Builder setHasNavigationMessages(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_NAV_MESSAGES, capable);
            return this;
        }

        /**
         * Sets low power mode capability.
         *
         * <p>The low power mode is defined in GNSS HAL. When the low power mode is active, the GNSS
         * hardware must make strong tradeoffs to substantially restrict power use.
         */
        public @NonNull Builder setHasLowPowerMode(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_LOW_POWER_MODE, capable);
            return this;
        }

        /**
         * Sets satellite blocklist capability.
         */
        public @NonNull Builder setHasSatelliteBlocklist(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_SATELLITE_BLOCKLIST, capable);
            return this;
        }

        /**
         * Sets satellite PVT capability.
         */
        public @NonNull Builder setHasSatellitePvt(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_SATELLITE_PVT, capable);
            return this;
        }

        /**
         * Sets measurement corrections capability.
         */
        public @NonNull Builder setHasMeasurementCorrections(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_MEASUREMENT_CORRECTIONS, capable);
            return this;
        }

        /**
         * Sets antenna info capability.
         */
        public @NonNull Builder setHasAntennaInfo(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_ANTENNA_INFO, capable);
            return this;
        }

        /**
         * Sets correlation vector capability.
         */
        public @NonNull Builder setHasMeasurementCorrelationVectors(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_CORRELATION_VECTOR, capable);
            return this;
        }

        /**
         * Sets measurement corrections for driving capability.
         */
        public @NonNull Builder setHasMeasurementCorrectionsForDriving(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_MEASUREMENT_CORRECTIONS_FOR_DRIVING,
                    capable);
            return this;
        }

        /**
         * Sets accumulated delta range capability.
         */
        public @NonNull Builder setHasAccumulatedDeltaRange(boolean capable) {
            mTopFlags = setFlag(mTopFlags, TOP_HAL_CAPABILITY_ACCUMULATED_DELTA_RANGE,
                    capable);
            return this;
        }

        /**
         * Sets measurement corrections line-of-sight satellites capability.
         */
        public @NonNull Builder setHasMeasurementCorrectionsLosSats(boolean capable) {
            mMeasurementCorrectionsFlags = setFlag(mMeasurementCorrectionsFlags,
                    SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_LOS_SATS, capable);
            return this;
        }

        /**
         * Sets measurement corrections excess path length capability.
         */
        public @NonNull Builder setHasMeasurementCorrectionsExcessPathLength(boolean capable) {
            mMeasurementCorrectionsFlags = setFlag(mMeasurementCorrectionsFlags,
                    SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_EXCESS_PATH_LENGTH, capable);
            return this;
        }

        /**
         * Sets measurement corrections reflecting plane capability.
         */
        public @NonNull Builder setHasMeasurementCorrectionsReflectingPlane(boolean capable) {
            mMeasurementCorrectionsFlags = setFlag(mMeasurementCorrectionsFlags,
                    SUB_HAL_MEASUREMENT_CORRECTIONS_CAPABILITY_REFLECTING_PLANE, capable);
            return this;
        }

        /**
         * Sets power totals capability.
         */
        public @NonNull Builder setHasPowerTotal(boolean capable) {
            mPowerFlags = setFlag(mPowerFlags, SUB_HAL_POWER_CAPABILITY_TOTAL, capable);
            return this;
        }

        /**
         * Sets power single-band tracking capability.
         */
        public @NonNull Builder setHasPowerSinglebandTracking(boolean capable) {
            mPowerFlags = setFlag(mPowerFlags, SUB_HAL_POWER_CAPABILITY_SINGLEBAND_TRACKING,
                    capable);
            return this;
        }

        /**
         * Sets power multi-band tracking capability.
         */
        public @NonNull Builder setHasPowerMultibandTracking(boolean capable) {
            mPowerFlags = setFlag(mPowerFlags, SUB_HAL_POWER_CAPABILITY_MULTIBAND_TRACKING,
                    capable);
            return this;
        }

        /**
         * Sets power single-band acquisition capability.
         */
        public @NonNull Builder setHasPowerSinglebandAcquisition(boolean capable) {
            mPowerFlags = setFlag(mPowerFlags, SUB_HAL_POWER_CAPABILITY_SINGLEBAND_ACQUISITION,
                    capable);
            return this;
        }

        /**
         * Sets power multi-band acquisition capability.
         */
        public @NonNull Builder setHasPowerMultibandAcquisition(boolean capable) {
            mPowerFlags = setFlag(mPowerFlags, SUB_HAL_POWER_CAPABILITY_MULTIBAND_ACQUISITION,
                    capable);
            return this;
        }

        /**
         * Sets OEM-defined power modes capability.
         */
        public @NonNull Builder setHasPowerOtherModes(boolean capable) {
            mPowerFlags = setFlag(mPowerFlags, SUB_HAL_POWER_CAPABILITY_OTHER_MODES, capable);
            return this;
        }

        /**
         * Sets a list of {@link GnssSignalType}.
         */
        public @NonNull Builder setGnssSignalTypes(@NonNull List<GnssSignalType> gnssSignalTypes) {
            mGnssSignalTypes = gnssSignalTypes;
            return this;
        }

        /**
         * Builds a new GnssCapabilities.
         */
        public @NonNull GnssCapabilities build() {
            return new GnssCapabilities(mTopFlags, mMeasurementCorrectionsFlags, mPowerFlags,
                    new ArrayList<>(mGnssSignalTypes));
        }

        private static int setFlag(int value, int flag, boolean set) {
            if (set) {
                return value | flag;
            } else {
                return value & ~flag;
            }
        }
    }
}
