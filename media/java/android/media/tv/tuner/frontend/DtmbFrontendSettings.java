/*
 * Copyright 2020 The Android Open Source Project
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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.FrontendDtmbBandwidth;
import android.hardware.tv.tuner.FrontendDtmbCodeRate;
import android.hardware.tv.tuner.FrontendDtmbGuardInterval;
import android.hardware.tv.tuner.FrontendDtmbModulation;
import android.hardware.tv.tuner.FrontendDtmbTimeInterleaveMode;
import android.hardware.tv.tuner.FrontendDtmbTransmissionMode;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for DTMB.
 *
 * <p>DTMB Frontend is only supported in Tuner HAL 1.1 or higher. Use {@link
 * android.media.tv.tuner.TunerVersionChecker#getTunerVersion()} to get the version information.
 *
 * @hide
 */
@SystemApi
public final class DtmbFrontendSettings extends FrontendSettings {

    /** @hide */
    @IntDef(prefix = "BANDWIDTH_",
            value = {BANDWIDTH_UNDEFINED, BANDWIDTH_AUTO, BANDWIDTH_6MHZ, BANDWIDTH_8MHZ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Bandwidth {}

    /**
     * Bandwidth not defined.
     */
    public static final int BANDWIDTH_UNDEFINED = FrontendDtmbBandwidth.UNDEFINED;
    /**
     * Hardware is able to detect and set bandwidth automatically
     */
    public static final int BANDWIDTH_AUTO = FrontendDtmbBandwidth.AUTO;
    /**
     * 6 MHz bandwidth.
     */
    public static final int BANDWIDTH_6MHZ = FrontendDtmbBandwidth.BANDWIDTH_6MHZ;
    /**
     * 8 MHz bandwidth.
     */
    public static final int BANDWIDTH_8MHZ = FrontendDtmbBandwidth.BANDWIDTH_8MHZ;


    /** @hide */
    @IntDef(prefix = "TIME_INTERLEAVE_MODE_",
            value = {TIME_INTERLEAVE_MODE_UNDEFINED, TIME_INTERLEAVE_MODE_AUTO,
                    TIME_INTERLEAVE_MODE_TIMER_INT_240, TIME_INTERLEAVE_MODE_TIMER_INT_720})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TimeInterleaveMode {}

    /**
     * Time Interleave Mode undefined.
     */
    public static final int TIME_INTERLEAVE_MODE_UNDEFINED =
            FrontendDtmbTimeInterleaveMode.UNDEFINED;
    /**
     * Hardware is able to detect and set time interleave mode automatically
     */
    public static final int TIME_INTERLEAVE_MODE_AUTO = FrontendDtmbTimeInterleaveMode.AUTO;
    /**
     * Time Interleave Mode timer int 240.
     */
    public static final int TIME_INTERLEAVE_MODE_TIMER_INT_240 =
            FrontendDtmbTimeInterleaveMode.TIMER_INT_240;
    /**
     * Time Interleave Mode timer int 720.
     */
    public static final int TIME_INTERLEAVE_MODE_TIMER_INT_720 =
            FrontendDtmbTimeInterleaveMode.TIMER_INT_720;


    /** @hide */
    @IntDef(prefix = "GUARD_INTERVAL_",
            value = {GUARD_INTERVAL_UNDEFINED, GUARD_INTERVAL_AUTO,
            GUARD_INTERVAL_PN_420_VARIOUS, GUARD_INTERVAL_PN_595_CONST,
            GUARD_INTERVAL_PN_945_VARIOUS, GUARD_INTERVAL_PN_420_CONST,
            GUARD_INTERVAL_PN_945_CONST, GUARD_INTERVAL_PN_RESERVED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GuardInterval {}

    /**
     * Guard Interval undefined.
     */
    public static final int GUARD_INTERVAL_UNDEFINED = FrontendDtmbGuardInterval.UNDEFINED;
    /**
     * Hardware is able to detect and set Guard Interval automatically.
     */
    public static final int GUARD_INTERVAL_AUTO = FrontendDtmbGuardInterval.AUTO;
    /**
     * PN_420_VARIOUS Guard Interval.
     */
    public static final int GUARD_INTERVAL_PN_420_VARIOUS =
            FrontendDtmbGuardInterval.PN_420_VARIOUS;
    /**
     * PN_595_CONST Guard Interval.
     */
    public static final int GUARD_INTERVAL_PN_595_CONST = FrontendDtmbGuardInterval.PN_595_CONST;
    /**
     * PN_945_VARIOUS Guard Interval.
     */
    public static final int GUARD_INTERVAL_PN_945_VARIOUS =
            FrontendDtmbGuardInterval.PN_945_VARIOUS;
    /**
     * PN_420_CONST Guard Interval.
     */
    public static final int GUARD_INTERVAL_PN_420_CONST = FrontendDtmbGuardInterval.PN_420_CONST;
    /**
     * PN_945_CONST Guard Interval.
     */
    public static final int GUARD_INTERVAL_PN_945_CONST = FrontendDtmbGuardInterval.PN_945_CONST;
    /**
     * PN_RESERVED Guard Interval.
     */
    public static final int GUARD_INTERVAL_PN_RESERVED = FrontendDtmbGuardInterval.PN_RESERVED;


    /** @hide */
    @IntDef(prefix = "MODULATION_",
            value = {MODULATION_CONSTELLATION_UNDEFINED, MODULATION_CONSTELLATION_AUTO,
                    MODULATION_CONSTELLATION_4QAM, MODULATION_CONSTELLATION_4QAM_NR,
                    MODULATION_CONSTELLATION_16QAM, MODULATION_CONSTELLATION_32QAM,
                    MODULATION_CONSTELLATION_64QAM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Modulation {}

    /**
     * Constellation not defined.
     */
    public static final int MODULATION_CONSTELLATION_UNDEFINED = FrontendDtmbModulation.UNDEFINED;
    /**
     * Hardware is able to detect and set Constellation automatically.
     */
    public static final int MODULATION_CONSTELLATION_AUTO = FrontendDtmbModulation.AUTO;
    /**
     * 4QAM Constellation.
     */
    public static final int MODULATION_CONSTELLATION_4QAM =
            FrontendDtmbModulation.CONSTELLATION_4QAM;
    /**
     * 4QAM_NR Constellation.
     */
    public static final int MODULATION_CONSTELLATION_4QAM_NR =
            FrontendDtmbModulation.CONSTELLATION_4QAM_NR;
    /**
     * 16QAM Constellation.
     */
    public static final int MODULATION_CONSTELLATION_16QAM =
            FrontendDtmbModulation.CONSTELLATION_16QAM;
    /**
     * 32QAM Constellation.
     */
    public static final int MODULATION_CONSTELLATION_32QAM =
            FrontendDtmbModulation.CONSTELLATION_32QAM;
    /**
     * 64QAM Constellation.
     */
    public static final int MODULATION_CONSTELLATION_64QAM =
            FrontendDtmbModulation.CONSTELLATION_64QAM;

    /** @hide */
    @IntDef(prefix = "CODERATE_",
            value = {CODERATE_UNDEFINED, CODERATE_AUTO, CODERATE_2_5, CODERATE_3_5, CODERATE_4_5})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CodeRate {}

    /**
     * Code rate undefined.
     */
    public static final int CODERATE_UNDEFINED = FrontendDtmbCodeRate.UNDEFINED;
    /**
     * Hardware is able to detect and set code rate automatically.
     */
    public static final int CODERATE_AUTO = FrontendDtmbCodeRate.AUTO;
    /**
     * 2/5 code rate.
     */
    public static final int CODERATE_2_5 = FrontendDtmbCodeRate.CODERATE_2_5;
    /**
     * 3/5 code rate.
     */
    public static final int CODERATE_3_5 = FrontendDtmbCodeRate.CODERATE_3_5;
    /**
     * 4/5 code rate.
     */
    public static final int CODERATE_4_5 = FrontendDtmbCodeRate.CODERATE_4_5;

    /** @hide */
    @IntDef(prefix = "TRANSMISSION_MODE_",
            value = {TRANSMISSION_MODE_UNDEFINED, TRANSMISSION_MODE_AUTO,
                    TRANSMISSION_MODE_C1, TRANSMISSION_MODE_C3780})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransmissionMode {}

    /**
     * Transmission Mode undefined.
     */
    public static final int TRANSMISSION_MODE_UNDEFINED = FrontendDtmbTransmissionMode.UNDEFINED;
    /**
     * Hardware is able to detect and set Transmission Mode automatically
     */
    public static final int TRANSMISSION_MODE_AUTO = FrontendDtmbTransmissionMode.AUTO;
    /**
     * C1 Transmission Mode.
     */
    public static final int TRANSMISSION_MODE_C1 = FrontendDtmbTransmissionMode.C1;
    /**
     * C3780 Transmission Mode.
     */
    public static final int TRANSMISSION_MODE_C3780 = FrontendDtmbTransmissionMode.C3780;


    private final int mModulation;
    private final int mCodeRate;
    private final int mTransmissionMode;
    private final int mBandwidth;
    private final int mGuardInterval;
    private final int mTimeInterleaveMode;

    private DtmbFrontendSettings(long frequency, int modulation, int codeRate, int transmissionMode,
            int guardInterval, int timeInterleaveMode, int bandwidth) {
        super(frequency);
        mModulation = modulation;
        mCodeRate = codeRate;
        mTransmissionMode = transmissionMode;
        mGuardInterval = guardInterval;
        mTimeInterleaveMode = timeInterleaveMode;
        mBandwidth = bandwidth;
    }

    /**
     * Gets Modulation.
     */
    @Modulation
    public int getModulation() {
        return mModulation;
    }

    /**
     * Gets Code Rate.
     */
    @CodeRate
    public int getCodeRate() {
        return mCodeRate;
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
     * Gets Time Interleave Mode.
     */
    @TimeInterleaveMode
    public int getTimeInterleaveMode() {
        return mTimeInterleaveMode;
    }

    /**
     * Gets Guard Interval.
     */
    @GuardInterval
    public int getGuardInterval() {
        return mGuardInterval;
    }

    /**
     * Creates a builder for {@link AtscFrontendSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link AtscFrontendSettings}.
     */
    public static final class Builder {
        private long mFrequency = 0;
        private int mModulation = MODULATION_CONSTELLATION_UNDEFINED;
        private int mCodeRate = CODERATE_UNDEFINED;
        private int mTransmissionMode = TRANSMISSION_MODE_UNDEFINED;
        private int mBandwidth = BANDWIDTH_UNDEFINED;
        private int mTimeInterleaveMode = TIME_INTERLEAVE_MODE_UNDEFINED;
        private int mGuardInterval = GUARD_INTERVAL_UNDEFINED;

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
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setFrequencyLong(long frequency) {
            mFrequency = frequency;
            return this;
        }

        /**
         * Sets Modulation.
         *
         * <p>Default value is {@link #MODULATION_CONSTELLATION_UNDEFINED}.
         */
        @NonNull
        public Builder setModulation(@Modulation int modulation) {
            mModulation = modulation;
            return this;
        }

        /**
         * Sets Code Rate.
         *
         * <p>Default value is {@link #CODERATE_UNDEFINED}.
         */
        @NonNull
        public Builder setCodeRate(@CodeRate int codeRate) {
            mCodeRate = codeRate;
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
         * Sets Time Interleave Mode.
         *
         * <p>Default value is {@link #TIME_INTERLEAVE_MODE_UNDEFINED}.
         */
        @NonNull
        public Builder setTimeInterleaveMode(@TimeInterleaveMode int timeInterleaveMode) {
            mTimeInterleaveMode = timeInterleaveMode;
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
         * Sets Transmission Mode.
         *
         * <p>Default value is {@link #TRANSMISSION_MODE_UNDEFINED}.
         */
        @NonNull
        public Builder setTransmissionMode(@TransmissionMode int transmissionMode) {
            mTransmissionMode = transmissionMode;
            return this;
        }

        /**
         * Builds a {@link DtmbFrontendSettings} object.
         */
        @NonNull
        public DtmbFrontendSettings build() {
            return new DtmbFrontendSettings(mFrequency, mModulation, mCodeRate,
                    mTransmissionMode, mGuardInterval, mTimeInterleaveMode, mBandwidth);
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_DTMB;
    }
}
