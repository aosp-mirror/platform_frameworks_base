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
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.TunerUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for ISDBT.
 * @hide
 */
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
    public static final int MODULATION_UNDEFINED = Constants.FrontendIsdbtModulation.UNDEFINED;
    /**
     * Hardware is able to detect and set modulation automatically
     */
    public static final int MODULATION_AUTO = Constants.FrontendIsdbtModulation.AUTO;
    /**
     * DQPSK Modulation.
     */
    public static final int MODULATION_MOD_DQPSK = Constants.FrontendIsdbtModulation.MOD_DQPSK;
    /**
     * QPSK Modulation.
     */
    public static final int MODULATION_MOD_QPSK = Constants.FrontendIsdbtModulation.MOD_QPSK;
    /**
     * 16QAM Modulation.
     */
    public static final int MODULATION_MOD_16QAM = Constants.FrontendIsdbtModulation.MOD_16QAM;
    /**
     * 64QAM Modulation.
     */
    public static final int MODULATION_MOD_64QAM = Constants.FrontendIsdbtModulation.MOD_64QAM;


    /** @hide */
    @IntDef(flag = true,
            prefix = "MODE_",
            value = {MODE_UNDEFINED, MODE_AUTO, MODE_1, MODE_2, MODE_3})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    /**
     * Mode undefined.
     */
    public static final int MODE_UNDEFINED = Constants.FrontendIsdbtMode.UNDEFINED;
    /**
     * Hardware is able to detect and set Mode automatically.
     */
    public static final int MODE_AUTO = Constants.FrontendIsdbtMode.AUTO;
    /**
     * Mode 1
     */
    public static final int MODE_1 = Constants.FrontendIsdbtMode.MODE_1;
    /**
     * Mode 2
     */
    public static final int MODE_2 = Constants.FrontendIsdbtMode.MODE_2;
    /**
     * Mode 3
     */
    public static final int MODE_3 = Constants.FrontendIsdbtMode.MODE_3;


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
    public static final int BANDWIDTH_UNDEFINED = Constants.FrontendIsdbtBandwidth.UNDEFINED;
    /**
     * Hardware is able to detect and set Bandwidth automatically.
     */
    public static final int BANDWIDTH_AUTO = Constants.FrontendIsdbtBandwidth.AUTO;
    /**
     * 8 MHz bandwidth.
     */
    public static final int BANDWIDTH_8MHZ = Constants.FrontendIsdbtBandwidth.BANDWIDTH_8MHZ;
    /**
     * 7 MHz bandwidth.
     */
    public static final int BANDWIDTH_7MHZ = Constants.FrontendIsdbtBandwidth.BANDWIDTH_7MHZ;
    /**
     * 6 MHz bandwidth.
     */
    public static final int BANDWIDTH_6MHZ = Constants.FrontendIsdbtBandwidth.BANDWIDTH_6MHZ;

    private final int mModulation;
    private final int mBandwidth;
    private final int mCoderate;
    private final int mGuardInterval;
    private final int mServiceAreaId;

    private IsdbtFrontendSettings(int frequency, int modulation, int bandwidth, int coderate,
            int guardInterval, int serviceAreaId) {
        super(frequency);
        mModulation = modulation;
        mBandwidth = bandwidth;
        mCoderate = coderate;
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
     * Gets Code rate.
     */
    @DvbtFrontendSettings.Coderate
    public int getCoderate() {
        return mCoderate;
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
     *
     * @param context the context of the caller.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @NonNull
    public static Builder builder(@NonNull Context context) {
        TunerUtils.checkTunerPermission(context);
        return new Builder();
    }

    /**
     * Builder for {@link IsdbtFrontendSettings}.
     */
    public static class Builder extends FrontendSettings.Builder<Builder> {
        private int mModulation;
        private int mBandwidth;
        private int mCoderate;
        private int mGuardInterval;
        private int mServiceAreaId;

        private Builder() {
        }

        /**
         * Sets Modulation.
         */
        @NonNull
        public Builder setModulation(@Modulation int modulation) {
            mModulation = modulation;
            return this;
        }
        /**
         * Sets Bandwidth.
         */
        @NonNull
        public Builder setBandwidth(@Bandwidth int bandwidth) {
            mBandwidth = bandwidth;
            return this;
        }
        /**
         * Sets Code rate.
         */
        @NonNull
        public Builder setCoderate(@DvbtFrontendSettings.Coderate int coderate) {
            mCoderate = coderate;
            return this;
        }
        /**
         * Sets Guard Interval.
         */
        @NonNull
        public Builder setGuardInterval(@DvbtFrontendSettings.GuardInterval int guardInterval) {
            mGuardInterval = guardInterval;
            return this;
        }
        /**
         * Sets Service Area ID.
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
            return new IsdbtFrontendSettings(
                    mFrequency, mModulation, mBandwidth, mCoderate, mGuardInterval, mServiceAreaId);
        }

        @Override
        Builder self() {
            return this;
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_ISDBT;
    }
}
