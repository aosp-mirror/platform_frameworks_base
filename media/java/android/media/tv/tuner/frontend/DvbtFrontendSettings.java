/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.frontend;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.FrontendDvbtBandwidth;
import android.hardware.tv.tuner.FrontendDvbtCoderate;
import android.hardware.tv.tuner.FrontendDvbtConstellation;
import android.hardware.tv.tuner.FrontendDvbtGuardInterval;
import android.hardware.tv.tuner.FrontendDvbtHierarchy;
import android.hardware.tv.tuner.FrontendDvbtPlpMode;
import android.hardware.tv.tuner.FrontendDvbtStandard;
import android.hardware.tv.tuner.FrontendDvbtTransmissionMode;
import android.media.tv.tuner.TunerVersionChecker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for DVBT.
 *
 * @hide
 */
@SystemApi
public class DvbtFrontendSettings extends FrontendSettings {

    /** @hide */
    @IntDef(prefix = "TRANSMISSION_MODE_",
            value = {TRANSMISSION_MODE_UNDEFINED, TRANSMISSION_MODE_AUTO,
                    TRANSMISSION_MODE_2K, TRANSMISSION_MODE_8K, TRANSMISSION_MODE_4K,
                    TRANSMISSION_MODE_1K, TRANSMISSION_MODE_16K, TRANSMISSION_MODE_32K})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransmissionMode {}

    /**
     * Transmission Mode undefined.
     */
    public static final int TRANSMISSION_MODE_UNDEFINED = FrontendDvbtTransmissionMode.UNDEFINED;
    /**
     * Hardware is able to detect and set Transmission Mode automatically
     */
    public static final int TRANSMISSION_MODE_AUTO = FrontendDvbtTransmissionMode.AUTO;
    /**
     * 2K Transmission Mode.
     */
    public static final int TRANSMISSION_MODE_2K = FrontendDvbtTransmissionMode.MODE_2K;
    /**
     * 8K Transmission Mode.
     */
    public static final int TRANSMISSION_MODE_8K = FrontendDvbtTransmissionMode.MODE_8K;
    /**
     * 4K Transmission Mode.
     */
    public static final int TRANSMISSION_MODE_4K = FrontendDvbtTransmissionMode.MODE_4K;
    /**
     * 1K Transmission Mode.
     */
    public static final int TRANSMISSION_MODE_1K = FrontendDvbtTransmissionMode.MODE_1K;
    /**
     * 16K Transmission Mode.
     */
    public static final int TRANSMISSION_MODE_16K = FrontendDvbtTransmissionMode.MODE_16K;
    /**
     * 32K Transmission Mode.
     */
    public static final int TRANSMISSION_MODE_32K = FrontendDvbtTransmissionMode.MODE_32K;
    /**
     * 8K Transmission Extended Mode.
     */
    public static final int TRANSMISSION_MODE_EXTENDED_8K = FrontendDvbtTransmissionMode.MODE_8K_E;
    /**
     * 16K Transmission Extended Mode.
     */
    public static final int TRANSMISSION_MODE_EXTENDED_16K =
            FrontendDvbtTransmissionMode.MODE_16K_E;
    /**
     * 32K Transmission Extended Mode.
     */
    public static final int TRANSMISSION_MODE_EXTENDED_32K =
            FrontendDvbtTransmissionMode.MODE_32K_E;

    /** @hide */
    @IntDef(prefix = "BANDWIDTH_",
            value = {BANDWIDTH_UNDEFINED, BANDWIDTH_AUTO, BANDWIDTH_8MHZ, BANDWIDTH_7MHZ,
                    BANDWIDTH_6MHZ, BANDWIDTH_5MHZ, BANDWIDTH_1_7MHZ, BANDWIDTH_10MHZ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Bandwidth {}

    /**
     * Bandwidth undefined.
     */
    public static final int BANDWIDTH_UNDEFINED = FrontendDvbtBandwidth.UNDEFINED;
    /**
     * Hardware is able to detect and set Bandwidth automatically.
     */
    public static final int BANDWIDTH_AUTO = FrontendDvbtBandwidth.AUTO;
    /**
     * 8 MHz bandwidth.
     */
    public static final int BANDWIDTH_8MHZ = FrontendDvbtBandwidth.BANDWIDTH_8MHZ;
    /**
     * 7 MHz bandwidth.
     */
    public static final int BANDWIDTH_7MHZ = FrontendDvbtBandwidth.BANDWIDTH_7MHZ;
    /**
     * 6 MHz bandwidth.
     */
    public static final int BANDWIDTH_6MHZ = FrontendDvbtBandwidth.BANDWIDTH_6MHZ;
    /**
     * 5 MHz bandwidth.
     */
    public static final int BANDWIDTH_5MHZ = FrontendDvbtBandwidth.BANDWIDTH_5MHZ;
    /**
     * 1,7 MHz bandwidth.
     */
    public static final int BANDWIDTH_1_7MHZ = FrontendDvbtBandwidth.BANDWIDTH_1_7MHZ;
    /**
     * 10 MHz bandwidth.
     */
    public static final int BANDWIDTH_10MHZ = FrontendDvbtBandwidth.BANDWIDTH_10MHZ;


    /** @hide */
    @IntDef(prefix = "CONSTELLATION_",
            value = {CONSTELLATION_UNDEFINED, CONSTELLATION_AUTO, CONSTELLATION_QPSK,
                    CONSTELLATION_16QAM, CONSTELLATION_64QAM, CONSTELLATION_256QAM,
                    CONSTELLATION_QPSK_R, CONSTELLATION_16QAM_R, CONSTELLATION_64QAM_R,
                    CONSTELLATION_256QAM_R})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Constellation {}

    /**
     * Constellation not defined.
     */
    public static final int CONSTELLATION_UNDEFINED = FrontendDvbtConstellation.UNDEFINED;
    /**
     * Hardware is able to detect and set Constellation automatically.
     */
    public static final int CONSTELLATION_AUTO = FrontendDvbtConstellation.AUTO;
    /**
     * QPSK Constellation.
     */
    public static final int CONSTELLATION_QPSK = FrontendDvbtConstellation.CONSTELLATION_QPSK;
    /**
     * 16QAM Constellation.
     */
    public static final int CONSTELLATION_16QAM = FrontendDvbtConstellation.CONSTELLATION_16QAM;
    /**
     * 64QAM Constellation.
     */
    public static final int CONSTELLATION_64QAM = FrontendDvbtConstellation.CONSTELLATION_64QAM;
    /**
     * 256QAM Constellation.
     */
    public static final int CONSTELLATION_256QAM = FrontendDvbtConstellation.CONSTELLATION_256QAM;
    /**
     * QPSK Rotated Constellation.
     */
    public static final int CONSTELLATION_QPSK_R = FrontendDvbtConstellation.CONSTELLATION_QPSK_R;
    /**
     * 16QAM Rotated Constellation.
     */
    public static final int CONSTELLATION_16QAM_R = FrontendDvbtConstellation.CONSTELLATION_16QAM_R;
    /**
     * 64QAM Rotated Constellation.
     */
    public static final int CONSTELLATION_64QAM_R = FrontendDvbtConstellation.CONSTELLATION_64QAM_R;
    /**
     * 256QAM Rotated Constellation.
     */
    public static final int CONSTELLATION_256QAM_R =
            FrontendDvbtConstellation.CONSTELLATION_256QAM_R;

    /** @hide */
    @IntDef(prefix = "HIERARCHY_",
            value = {HIERARCHY_UNDEFINED, HIERARCHY_AUTO, HIERARCHY_NON_NATIVE, HIERARCHY_1_NATIVE,
            HIERARCHY_2_NATIVE, HIERARCHY_4_NATIVE, HIERARCHY_NON_INDEPTH, HIERARCHY_1_INDEPTH,
            HIERARCHY_2_INDEPTH, HIERARCHY_4_INDEPTH})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Hierarchy {}

    /**
     * Hierarchy undefined.
     */
    public static final int HIERARCHY_UNDEFINED = FrontendDvbtHierarchy.UNDEFINED;
    /**
     * Hardware is able to detect and set Hierarchy automatically.
     */
    public static final int HIERARCHY_AUTO = FrontendDvbtHierarchy.AUTO;
    /**
     * Non-native Hierarchy
     */
    public static final int HIERARCHY_NON_NATIVE = FrontendDvbtHierarchy.HIERARCHY_NON_NATIVE;
    /**
     * 1-native Hierarchy
     */
    public static final int HIERARCHY_1_NATIVE = FrontendDvbtHierarchy.HIERARCHY_1_NATIVE;
    /**
     * 2-native Hierarchy
     */
    public static final int HIERARCHY_2_NATIVE = FrontendDvbtHierarchy.HIERARCHY_2_NATIVE;
    /**
     * 4-native Hierarchy
     */
    public static final int HIERARCHY_4_NATIVE = FrontendDvbtHierarchy.HIERARCHY_4_NATIVE;
    /**
     * Non-indepth Hierarchy
     */
    public static final int HIERARCHY_NON_INDEPTH = FrontendDvbtHierarchy.HIERARCHY_NON_INDEPTH;
    /**
     * 1-indepth Hierarchy
     */
    public static final int HIERARCHY_1_INDEPTH = FrontendDvbtHierarchy.HIERARCHY_1_INDEPTH;
    /**
     * 2-indepth Hierarchy
     */
    public static final int HIERARCHY_2_INDEPTH = FrontendDvbtHierarchy.HIERARCHY_2_INDEPTH;
    /**
     * 4-indepth Hierarchy
     */
    public static final int HIERARCHY_4_INDEPTH = FrontendDvbtHierarchy.HIERARCHY_4_INDEPTH;


    /** @hide */
    @IntDef(prefix = "CODERATE_",
            value = {CODERATE_UNDEFINED, CODERATE_AUTO, CODERATE_1_2, CODERATE_2_3, CODERATE_3_4,
            CODERATE_5_6, CODERATE_7_8, CODERATE_3_5, CODERATE_4_5, CODERATE_6_7, CODERATE_8_9})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CodeRate {}

    /**
     * Code rate undefined.
     */
    public static final int CODERATE_UNDEFINED = FrontendDvbtCoderate.UNDEFINED;
    /**
     * Hardware is able to detect and set code rate automatically.
     */
    public static final int CODERATE_AUTO = FrontendDvbtCoderate.AUTO;
    /**
     * 1/2 code rate.
     */
    public static final int CODERATE_1_2 = FrontendDvbtCoderate.CODERATE_1_2;
    /**
     * 2/3 code rate.
     */
    public static final int CODERATE_2_3 = FrontendDvbtCoderate.CODERATE_2_3;
    /**
     * 3/4 code rate.
     */
    public static final int CODERATE_3_4 = FrontendDvbtCoderate.CODERATE_3_4;
    /**
     * 5/6 code rate.
     */
    public static final int CODERATE_5_6 = FrontendDvbtCoderate.CODERATE_5_6;
    /**
     * 7/8 code rate.
     */
    public static final int CODERATE_7_8 = FrontendDvbtCoderate.CODERATE_7_8;
    /**
     * 4/5 code rate.
     */
    public static final int CODERATE_3_5 = FrontendDvbtCoderate.CODERATE_3_5;
    /**
     * 4/5 code rate.
     */
    public static final int CODERATE_4_5 = FrontendDvbtCoderate.CODERATE_4_5;
    /**
     * 6/7 code rate.
     */
    public static final int CODERATE_6_7 = FrontendDvbtCoderate.CODERATE_6_7;
    /**
     * 8/9 code rate.
     */
    public static final int CODERATE_8_9 = FrontendDvbtCoderate.CODERATE_8_9;

    /** @hide */
    @IntDef(prefix = "GUARD_INTERVAL_",
            value = {GUARD_INTERVAL_UNDEFINED, GUARD_INTERVAL_AUTO,
            GUARD_INTERVAL_1_32, GUARD_INTERVAL_1_16,
            GUARD_INTERVAL_1_8, GUARD_INTERVAL_1_4,
            GUARD_INTERVAL_1_128,
            GUARD_INTERVAL_19_128,
            GUARD_INTERVAL_19_256})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GuardInterval {}

    /**
     * Guard Interval undefined.
     */
    public static final int GUARD_INTERVAL_UNDEFINED =
            FrontendDvbtGuardInterval.UNDEFINED;
    /**
     * Hardware is able to detect and set Guard Interval automatically.
     */
    public static final int GUARD_INTERVAL_AUTO = FrontendDvbtGuardInterval.AUTO;
    /**
     * 1/32 Guard Interval.
     */
    public static final int GUARD_INTERVAL_1_32 = FrontendDvbtGuardInterval.INTERVAL_1_32;
    /**
     * 1/16 Guard Interval.
     */
    public static final int GUARD_INTERVAL_1_16 = FrontendDvbtGuardInterval.INTERVAL_1_16;
    /**
     * 1/8 Guard Interval.
     */
    public static final int GUARD_INTERVAL_1_8 = FrontendDvbtGuardInterval.INTERVAL_1_8;
    /**
     * 1/4 Guard Interval.
     */
    public static final int GUARD_INTERVAL_1_4 = FrontendDvbtGuardInterval.INTERVAL_1_4;
    /**
     * 1/128 Guard Interval.
     */
    public static final int GUARD_INTERVAL_1_128 = FrontendDvbtGuardInterval.INTERVAL_1_128;
    /**
     * 19/128 Guard Interval.
     */
    public static final int GUARD_INTERVAL_19_128 = FrontendDvbtGuardInterval.INTERVAL_19_128;
    /**
     * 19/256 Guard Interval.
     */
    public static final int GUARD_INTERVAL_19_256 = FrontendDvbtGuardInterval.INTERVAL_19_256;

    /** @hide */
    @IntDef(prefix = "STANDARD_",
            value = {STANDARD_AUTO, STANDARD_T, STANDARD_T2}
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface Standard {}

    /**
     * Hardware is able to detect and set Standard automatically.
     */
    public static final int STANDARD_AUTO = FrontendDvbtStandard.AUTO;
    /**
     * T standard.
     */
    public static final int STANDARD_T = FrontendDvbtStandard.T;
    /**
     * T2 standard.
     */
    public static final int STANDARD_T2 = FrontendDvbtStandard.T2;

    /** @hide */
    @IntDef(prefix = "PLP_MODE_",
            value = {PLP_MODE_UNDEFINED, PLP_MODE_AUTO, PLP_MODE_MANUAL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlpMode {}

    /**
     * Physical Layer Pipe (PLP) Mode undefined.
     */
    public static final int PLP_MODE_UNDEFINED = FrontendDvbtPlpMode.UNDEFINED;
    /**
     * Hardware is able to detect and set Physical Layer Pipe (PLP) Mode automatically.
     */
    public static final int PLP_MODE_AUTO = FrontendDvbtPlpMode.AUTO;
    /**
     * Physical Layer Pipe (PLP) manual Mode.
     */
    public static final int PLP_MODE_MANUAL = FrontendDvbtPlpMode.MANUAL;

    private int mTransmissionMode;
    private final int mBandwidth;
    private final int mConstellation;
    private final int mHierarchy;
    private final int mHpCodeRate;
    private final int mLpCodeRate;
    private final int mGuardInterval;
    private final boolean mIsHighPriority;
    private final int mStandard;
    private final boolean mIsMiso;
    private final int mPlpMode;
    private final int mPlpId;
    private final int mPlpGroupId;

    private DvbtFrontendSettings(long frequency, int transmissionMode, int bandwidth,
            int constellation, int hierarchy, int hpCodeRate, int lpCodeRate, int guardInterval,
            boolean isHighPriority, int standard, boolean isMiso, int plpMode, int plpId,
            int plpGroupId) {
        super(frequency);
        mTransmissionMode = transmissionMode;
        mBandwidth = bandwidth;
        mConstellation = constellation;
        mHierarchy = hierarchy;
        mHpCodeRate = hpCodeRate;
        mLpCodeRate = lpCodeRate;
        mGuardInterval = guardInterval;
        mIsHighPriority = isHighPriority;
        mStandard = standard;
        mIsMiso = isMiso;
        mPlpMode = plpMode;
        mPlpId = plpId;
        mPlpGroupId = plpGroupId;
    }

    /**
     * Gets Transmission Mode.
     */
    @TransmissionMode
    public int getTransmissionMode() {
        return mTransmissionMode;
    }
    /**
     * Gets Bandwidth.
     */
    @Bandwidth
    public int getBandwidth() {
        return mBandwidth;
    }
    /**
     * Gets Constellation.
     */
    @Constellation
    public int getConstellation() {
        return mConstellation;
    }
    /**
     * Gets Hierarchy.
     */
    @Hierarchy
    public int getHierarchy() {
        return mHierarchy;
    }
    /**
     * Gets Code Rate for High Priority level.
     */
    @CodeRate
    public int getHighPriorityCodeRate() {
        return mHpCodeRate;
    }
    /**
     * Gets Code Rate for Low Priority level.
     */
    @CodeRate
    public int getLowPriorityCodeRate() {
        return mLpCodeRate;
    }
    /**
     * Gets Guard Interval.
     */
    @GuardInterval
    public int getGuardInterval() {
        return mGuardInterval;
    }
    /**
     * Checks whether it's high priority.
     */
    public boolean isHighPriority() {
        return mIsHighPriority;
    }
    /**
     * Gets Standard.
     */
    @Standard
    public int getStandard() {
        return mStandard;
    }
    /**
     * Gets whether it's MISO.
     */
    public boolean isMiso() {
        return mIsMiso;
    }
    /**
     * Gets Physical Layer Pipe (PLP) Mode.
     */
    @PlpMode
    public int getPlpMode() {
        return mPlpMode;
    }
    /**
     * Gets Physical Layer Pipe (PLP) ID.
     */
    public int getPlpId() {
        return mPlpId;
    }
    /**
     * Gets Physical Layer Pipe (PLP) group ID.
     */
    public int getPlpGroupId() {
        return mPlpGroupId;
    }

    private static boolean isExtendedTransmissionMode(@TransmissionMode int transmissionMode) {
        return transmissionMode == TRANSMISSION_MODE_EXTENDED_8K
                || transmissionMode == TRANSMISSION_MODE_EXTENDED_16K
                || transmissionMode == TRANSMISSION_MODE_EXTENDED_32K;
    }

    private static boolean isExtendedConstellation(@Constellation int constellation) {
        return constellation == CONSTELLATION_QPSK_R
                || constellation == CONSTELLATION_16QAM_R
                || constellation == CONSTELLATION_64QAM_R
                || constellation == CONSTELLATION_256QAM_R;
    }

    /**
     * Creates a builder for {@link DvbtFrontendSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DvbtFrontendSettings}.
     */
    public static class Builder {
        private long mFrequency = 0;
        private int mTransmissionMode = TRANSMISSION_MODE_UNDEFINED;
        private int mBandwidth = BANDWIDTH_UNDEFINED;
        private int mConstellation = CONSTELLATION_UNDEFINED;
        private int mHierarchy = HIERARCHY_UNDEFINED;
        private int mHpCodeRate = CODERATE_UNDEFINED;
        private int mLpCodeRate = CODERATE_UNDEFINED;
        private int mGuardInterval = GUARD_INTERVAL_UNDEFINED;
        private boolean mIsHighPriority = false;
        private int mStandard = STANDARD_AUTO;
        private boolean mIsMiso = false;
        private int mPlpMode = PLP_MODE_UNDEFINED;
        private int mPlpId = 0;
        private int mPlpGroupId = 0;

        private Builder() {
        }

        /**
         * Sets frequency in Hz.
         *
         * <p>Default value is 0.
         * @deprecated Use {@link #setFrequencyLong(long)}
         */
        @NonNull
        @IntRange(from = 1)
        @Deprecated
        public Builder setFrequency(int frequency) {
            return setFrequencyLong((long) frequency);
        }

        /**
         * Sets frequency in Hz.
         *
         * <p>Default value is 0.
         */
        @NonNull
        @IntRange(from = 1)
        public Builder setFrequencyLong(long frequency) {
            mFrequency = frequency;
            return this;
        }

        /**
         * Sets Transmission Mode.
         *
         * <p>{@link #TRANSMISSION_MODE_EXTENDED_8K}, {@link #TRANSMISSION_MODE_EXTENDED_16K} and
         * {@link #TRANSMISSION_MODE_EXTENDED_32K} are only supported by Tuner HAL 1.1 or higher.
         * Unsupported version would cause no-op. Use {@link TunerVersionChecker#getTunerVersion()}
         * to check the version.
         *
         * <p>Default value is {@link #TRANSMISSION_MODE_UNDEFINED}.
         */
        @NonNull
        public Builder setTransmissionMode(@TransmissionMode int transmissionMode) {
            if (!isExtendedTransmissionMode(transmissionMode)
                    || TunerVersionChecker.checkHigherOrEqualVersionTo(
                            TunerVersionChecker.TUNER_VERSION_1_1, "set TransmissionMode Ext")) {
                mTransmissionMode = transmissionMode;
            }
            return this;
        }

        /**
         * Sets Bandwidth.
         *
         * <p>Default value is {@link #BANDWIDTH_UNDEFINED}.
         */
        @NonNull
        public Builder setBandwidth(@Bandwidth int bandwidth) {
            mBandwidth = bandwidth;
            return this;
        }
        /**
         * Sets Constellation.
         *
         * <p>{@link #CONSTELLATION_QPSK_R}, {@link #CONSTELLATION_16QAM_R},
         * {@link #CONSTELLATION_64QAM_R} and {@link #CONSTELLATION_256QAM_Rare} are only supported
         * by Tuner HAL 1.1 or higher. Unsupported version would cause no-op. Use
         * {@link TunerVersionChecker#getTunerVersion()} to check the version.
         *
         * <p>Default value is {@link #CONSTELLATION_UNDEFINED}.
         */
        @NonNull
        public Builder setConstellation(@Constellation int constellation) {
            if (!isExtendedConstellation(constellation)
                    || TunerVersionChecker.checkHigherOrEqualVersionTo(
                            TunerVersionChecker.TUNER_VERSION_1_1, "set Constellation Ext")) {
                mConstellation = constellation;
            }
            return this;
        }
        /**
         * Sets Hierarchy.
         *
         * <p>Default value is {@link #HIERARCHY_UNDEFINED}.
         */
        @NonNull
        public Builder setHierarchy(@Hierarchy int hierarchy) {
            mHierarchy = hierarchy;
            return this;
        }
        /**
         * Sets Code Rate for High Priority level.
         *
         * <p>Default value is {@link #CODERATE_UNDEFINED}.
         */
        @NonNull
        public Builder setHighPriorityCodeRate(@CodeRate int hpCodeRate) {
            mHpCodeRate = hpCodeRate;
            return this;
        }
        /**
         * Sets Code Rate for Low Priority level.
         *
         * <p>Default value is {@link #CODERATE_UNDEFINED}.
         */
        @NonNull
        public Builder setLowPriorityCodeRate(@CodeRate int lpCodeRate) {
            mLpCodeRate = lpCodeRate;
            return this;
        }
        /**
         * Sets Guard Interval.
         *
         * <p>Default value is {@link #GUARD_INTERVAL_UNDEFINED}.
         */
        @NonNull
        public Builder setGuardInterval(@GuardInterval int guardInterval) {
            mGuardInterval = guardInterval;
            return this;
        }
        /**
         * Sets whether it's high priority.
         *
         * <p>Default value is {@code false}.
         */
        @NonNull
        public Builder setHighPriority(boolean isHighPriority) {
            mIsHighPriority = isHighPriority;
            return this;
        }
        /**
         * Sets Standard.
         *
         * <p>Default value is {@link #STANDARD_AUTO}.
         */
        @NonNull
        public Builder setStandard(@Standard int standard) {
            mStandard = standard;
            return this;
        }
        /**
         * Sets whether it's MISO.
         *
         * <p>Default value is {@code false}.
         */
        @NonNull
        public Builder setMiso(boolean isMiso) {
            mIsMiso = isMiso;
            return this;
        }
        /**
         * Sets Physical Layer Pipe (PLP) Mode.
         *
         * <p>Default value is {@link #PLP_MODE_UNDEFINED}.
         */
        @NonNull
        public Builder setPlpMode(@PlpMode int plpMode) {
            mPlpMode = plpMode;
            return this;
        }
        /**
         * Sets Physical Layer Pipe (PLP) ID.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setPlpId(int plpId) {
            mPlpId = plpId;
            return this;
        }
        /**
         * Sets Physical Layer Pipe (PLP) group ID.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setPlpGroupId(int plpGroupId) {
            mPlpGroupId = plpGroupId;
            return this;
        }

        /**
         * Builds a {@link DvbtFrontendSettings} object.
         */
        @NonNull
        public DvbtFrontendSettings build() {
            return new DvbtFrontendSettings(mFrequency, mTransmissionMode, mBandwidth,
                    mConstellation, mHierarchy, mHpCodeRate, mLpCodeRate, mGuardInterval,
                    mIsHighPriority, mStandard, mIsMiso, mPlpMode, mPlpId, mPlpGroupId);
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_DVBT;
    }
}
