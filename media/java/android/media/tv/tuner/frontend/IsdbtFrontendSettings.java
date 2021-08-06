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
import android.hardware.tv.tuner.FrontendIsdbtBandwidth;
import android.hardware.tv.tuner.FrontendIsdbtMode;
import android.hardware.tv.tuner.FrontendIsdbtModulation;
import android.media.tv.tuner.frontend.DvbtFrontendSettings.CodeRate;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for ISDBT.
 *
 * @hide
 */
@SystemApi
public class IsdbtFrontendSettings extends FrontendSettings {
    /** @hide */
    @IntDef(flag = true,
            prefix = "MODULATION_",
            value = {MODULATION_UNDEFINED, MODULATION_AUTO, MODULATION_MOD_DQPSK,
                    MODULATION_MOD_QPSK, MODULATION_MOD_16QAM, MODULATION_MOD_64QAM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Modulation {}

    /**
     * Modulation undefined.
     */
    public static final int MODULATION_UNDEFINED = FrontendIsdbtModulation.UNDEFINED;
    /**
     * Hardware is able to detect and set modulation automatically
     */
    public static final int MODULATION_AUTO = FrontendIsdbtModulation.AUTO;
    /**
     * DQPSK Modulation.
     */
    public static final int MODULATION_MOD_DQPSK = FrontendIsdbtModulation.MOD_DQPSK;
    /**
     * QPSK Modulation.
     */
    public static final int MODULATION_MOD_QPSK = FrontendIsdbtModulation.MOD_QPSK;
    /**
     * 16QAM Modulation.
     */
    public static final int MODULATION_MOD_16QAM = FrontendIsdbtModulation.MOD_16QAM;
    /**
     * 64QAM Modulation.
     */
    public static final int MODULATION_MOD_64QAM = FrontendIsdbtModulation.MOD_64QAM;


    /** @hide */
    @IntDef(flag = true,
            prefix = "MODE_",
            value = {MODE_UNDEFINED, MODE_AUTO, MODE_1, MODE_2, MODE_3})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    /**
     * Mode undefined.
     */
    public static final int MODE_UNDEFINED = FrontendIsdbtMode.UNDEFINED;
    /**
     * Hardware is able to detect and set Mode automatically.
     */
    public static final int MODE_AUTO = FrontendIsdbtMode.AUTO;
    /**
     * Mode 1
     */
    public static final int MODE_1 = FrontendIsdbtMode.MODE_1;
    /**
     * Mode 2
     */
    public static final int MODE_2 = FrontendIsdbtMode.MODE_2;
    /**
     * Mode 3
     */
    public static final int MODE_3 = FrontendIsdbtMode.MODE_3;


    /** @hide */
    @IntDef(flag = true,
            prefix = "BANDWIDTH_",
            value = {BANDWIDTH_UNDEFINED, BANDWIDTH_AUTO, BANDWIDTH_8MHZ, BANDWIDTH_7MHZ,
                    BANDWIDTH_6MHZ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Bandwidth {}

    /**
     * Bandwidth undefined.
     */
    public static final int BANDWIDTH_UNDEFINED = FrontendIsdbtBandwidth.UNDEFINED;
    /**
     * Hardware is able to detect and set Bandwidth automatically.
     */
    public static final int BANDWIDTH_AUTO = FrontendIsdbtBandwidth.AUTO;
    /**
     * 8 MHz bandwidth.
     */
    public static final int BANDWIDTH_8MHZ = FrontendIsdbtBandwidth.BANDWIDTH_8MHZ;
    /**
     * 7 MHz bandwidth.
     */
    public static final int BANDWIDTH_7MHZ = FrontendIsdbtBandwidth.BANDWIDTH_7MHZ;
    /**
     * 6 MHz bandwidth.
     */
    public static final int BANDWIDTH_6MHZ = FrontendIsdbtBandwidth.BANDWIDTH_6MHZ;

    private final int mModulation;
    private final int mBandwidth;
    private final int mMode;
    private final int mCodeRate;
    private final int mGuardInterval;
    private final int mServiceAreaId;

    private IsdbtFrontendSettings(int frequency, int modulation, int bandwidth, int mode,
            int codeRate, int guardInterval, int serviceAreaId) {
        super(frequency);
        mModulation = modulation;
        mBandwidth = bandwidth;
        mMode = mode;
        mCodeRate = codeRate;
        mGuardInterval = guardInterval;
        mServiceAreaId = serviceAreaId;
    }

    /**
     * Gets Modulation.
     */
    @Modulation
    public int getModulation() {
        return mModulation;
    }
    /**
     * Gets Bandwidth.
     */
    @Bandwidth
    public int getBandwidth() {
        return mBandwidth;
    }
    /**
     * Gets ISDBT mode.
     */
    @Mode
    public int getMode() {
        return mMode;
    }
    /**
     * Gets Code rate.
     */
    @CodeRate
    public int getCodeRate() {
        return mCodeRate;
    }
    /**
     * Gets Guard Interval.
     */
    @DvbtFrontendSettings.GuardInterval
    public int getGuardInterval() {
        return mGuardInterval;
    }
    /**
     * Gets Service Area ID.
     */
    public int getServiceAreaId() {
        return mServiceAreaId;
    }

    /**
     * Creates a builder for {@link IsdbtFrontendSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link IsdbtFrontendSettings}.
     */
    public static class Builder {
        private int mFrequency = 0;
        private int mModulation = MODULATION_UNDEFINED;
        private int mBandwidth = BANDWIDTH_UNDEFINED;
        private int mMode = MODE_UNDEFINED;
        private int mCodeRate = DvbtFrontendSettings.CODERATE_UNDEFINED;
        private int mGuardInterval = DvbtFrontendSettings.GUARD_INTERVAL_UNDEFINED;
        private int mServiceAreaId = 0;

        private Builder() {
        }

        /**
         * Sets frequency in Hz.
         *
         * <p>Default value is 0.
         */
        @NonNull
        @IntRange(from = 1)
        public Builder setFrequency(int frequency) {
            mFrequency = frequency;
            return this;
        }

        /**
         * Sets Modulation.
         *
         * <p>Default value is {@link #MODULATION_UNDEFINED}.
         */
        @NonNull
        public Builder setModulation(@Modulation int modulation) {
            mModulation = modulation;
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
         * Sets ISDBT mode.
         *
         * <p>Default value is {@link #MODE_UNDEFINED}.
         */
        @NonNull
        public Builder setMode(@Mode int mode) {
            mMode = mode;
            return this;
        }
        /**
         * Sets Code rate.
         *
         * <p>Default value is {@link DvbtFrontendSettings#CODERATE_UNDEFINED}.
         */
        @NonNull
        public Builder setCodeRate(@DvbtFrontendSettings.CodeRate int codeRate) {
            mCodeRate = codeRate;
            return this;
        }
        /**
         * Sets Guard Interval.
         *
         * <p>Default value is {@link DvbtFrontendSettings#GUARD_INTERVAL_UNDEFINED}.
         */
        @NonNull
        public Builder setGuardInterval(@DvbtFrontendSettings.GuardInterval int guardInterval) {
            mGuardInterval = guardInterval;
            return this;
        }
        /**
         * Sets Service Area ID.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setServiceAreaId(int serviceAreaId) {
            mServiceAreaId = serviceAreaId;
            return this;
        }

        /**
         * Builds a {@link IsdbtFrontendSettings} object.
         */
        @NonNull
        public IsdbtFrontendSettings build() {
            return new IsdbtFrontendSettings(mFrequency, mModulation, mBandwidth, mMode, mCodeRate,
                    mGuardInterval, mServiceAreaId);
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_ISDBT;
    }
}
