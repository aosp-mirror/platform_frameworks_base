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
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.TunerVersionChecker;
import android.media.tv.tuner.frontend.FrontendSettings.FrontendSpectralInversion;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for DVBC.
 *
 * @hide
 */
@SystemApi
public class DvbcFrontendSettings extends FrontendSettings {

    /** @hide */
    @IntDef(flag = true,
            prefix = "MODULATION_",
            value = {MODULATION_UNDEFINED, MODULATION_AUTO, MODULATION_MOD_16QAM,
                    MODULATION_MOD_32QAM, MODULATION_MOD_64QAM, MODULATION_MOD_128QAM,
                    MODULATION_MOD_256QAM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Modulation {}

    /**
     * Modulation undefined.
     */
    public static final int MODULATION_UNDEFINED = Constants.FrontendDvbcModulation.UNDEFINED;
    /**
     * Hardware is able to detect and set modulation automatically
     */
    public static final int MODULATION_AUTO = Constants.FrontendDvbcModulation.AUTO;
    /**
     * 16QAM Modulation.
     */
    public static final int MODULATION_MOD_16QAM = Constants.FrontendDvbcModulation.MOD_16QAM;
    /**
     * 32QAM Modulation.
     */
    public static final int MODULATION_MOD_32QAM = Constants.FrontendDvbcModulation.MOD_32QAM;
    /**
     * 64QAM Modulation.
     */
    public static final int MODULATION_MOD_64QAM = Constants.FrontendDvbcModulation.MOD_64QAM;
    /**
     * 128QAM Modulation.
     */
    public static final int MODULATION_MOD_128QAM = Constants.FrontendDvbcModulation.MOD_128QAM;
    /**
     * 256QAM Modulation.
     */
    public static final int MODULATION_MOD_256QAM = Constants.FrontendDvbcModulation.MOD_256QAM;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "OUTER_FEC_",
            value = {OUTER_FEC_UNDEFINED, OUTER_FEC_OUTER_FEC_NONE, OUTER_FEC_OUTER_FEC_RS})
    public @interface OuterFec {}

    /**
     * Outer Forward Error Correction (FEC) Type undefined.
     */
    public static final int OUTER_FEC_UNDEFINED = Constants.FrontendDvbcOuterFec.UNDEFINED;
    /**
     * None Outer Forward Error Correction (FEC) Type.
     */
    public static final int OUTER_FEC_OUTER_FEC_NONE =
            Constants.FrontendDvbcOuterFec.OUTER_FEC_NONE;
    /**
     * RS Outer Forward Error Correction (FEC) Type.
     */
    public static final int OUTER_FEC_OUTER_FEC_RS = Constants.FrontendDvbcOuterFec.OUTER_FEC_RS;


    /** @hide */
    @IntDef(flag = true,
            prefix = "ANNEX_",
            value = {ANNEX_UNDEFINED, ANNEX_A, ANNEX_B, ANNEX_C})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Annex {}

    /**
     * Annex Type undefined.
     */
    public static final int ANNEX_UNDEFINED = Constants.FrontendDvbcAnnex.UNDEFINED;
    /**
     * Annex Type A.
     */
    public static final int ANNEX_A = Constants.FrontendDvbcAnnex.A;
    /**
     * Annex Type B.
     */
    public static final int ANNEX_B = Constants.FrontendDvbcAnnex.B;
    /**
     * Annex Type C.
     */
    public static final int ANNEX_C = Constants.FrontendDvbcAnnex.C;


    /**
     * @deprecated Use the {@code FrontendSpectralInversion} instead.
     * @hide
     */
    @Deprecated
    @IntDef(prefix = "SPECTRAL_INVERSION_",
            value = {SPECTRAL_INVERSION_UNDEFINED, SPECTRAL_INVERSION_NORMAL,
                    SPECTRAL_INVERSION_INVERTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SpectralInversion {}

    /**
     * Spectral Inversion Type undefined.
     *
     * @deprecated Use the {@link FrontendSettings#FRONTEND_SPECTRAL_INVERSION_UNDEFINED} instead.
     */
    @Deprecated
    public static final int SPECTRAL_INVERSION_UNDEFINED =
            Constants.FrontendDvbcSpectralInversion.UNDEFINED;
    /**
     * Normal Spectral Inversion.
     *
     * @deprecated Use the {@link FrontendSettings#FRONTEND_SPECTRAL_INVERSION_NORMAL} instead.
     */
    @Deprecated
    public static final int SPECTRAL_INVERSION_NORMAL =
            Constants.FrontendDvbcSpectralInversion.NORMAL;
    /**
     * Inverted Spectral Inversion.
     *
     * @deprecated Use the {@link FrontendSettings#FRONTEND_SPECTRAL_INVERSION_INVERTED} instead.
     */
    @Deprecated
    public static final int SPECTRAL_INVERSION_INVERTED =
            Constants.FrontendDvbcSpectralInversion.INVERTED;

    /** @hide */
    @IntDef(flag = true,
            prefix = "TIME_INTERLEAVE_MODE_",
            value = {TIME_INTERLEAVE_MODE_UNDEFINED, TIME_INTERLEAVE_MODE_AUTO,
                    TIME_INTERLEAVE_MODE_128_1_0, TIME_INTERLEAVE_MODE_128_1_1,
                    TIME_INTERLEAVE_MODE_64_2, TIME_INTERLEAVE_MODE_32_4,
                    TIME_INTERLEAVE_MODE_16_8, TIME_INTERLEAVE_MODE_8_16,
                    TIME_INTERLEAVE_MODE_128_2, TIME_INTERLEAVE_MODE_128_3,
                    TIME_INTERLEAVE_MODE_128_4})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TimeInterleaveMode {}

    /**
     * Time interleave mode undefined.
     */
    public static final int TIME_INTERLEAVE_MODE_UNDEFINED =
            android.hardware.tv.tuner.V1_1.Constants.FrontendCableTimeInterleaveMode.UNDEFINED;
    /**
     * Hardware is able to detect and set Time Interleave Mode automatically.
     */
    public static final int TIME_INTERLEAVE_MODE_AUTO =
            android.hardware.tv.tuner.V1_1.Constants.FrontendCableTimeInterleaveMode.AUTO;
    /**
     * 128/1/0 Time Interleave Mode.
     */
    public static final int TIME_INTERLEAVE_MODE_128_1_0 = android.hardware.tv.tuner.V1_1.Constants
            .FrontendCableTimeInterleaveMode.INTERLEAVING_128_1_0;
    /**
     * 128/1/1 Time Interleave Mode.
     */
    public static final int TIME_INTERLEAVE_MODE_128_1_1 = android.hardware.tv.tuner.V1_1.Constants
            .FrontendCableTimeInterleaveMode.INTERLEAVING_128_1_1;
    /**
     * 64/2 Time Interleave Mode.
     */
    public static final int TIME_INTERLEAVE_MODE_64_2 = android.hardware.tv.tuner.V1_1.Constants
            .FrontendCableTimeInterleaveMode.INTERLEAVING_64_2;
    /**
     * 32/4 Time Interleave Mode.
     */
    public static final int TIME_INTERLEAVE_MODE_32_4 = android.hardware.tv.tuner.V1_1.Constants
            .FrontendCableTimeInterleaveMode.INTERLEAVING_32_4;
    /**
     * 16/8 Time Interleave Mode.
     */
    public static final int TIME_INTERLEAVE_MODE_16_8 = android.hardware.tv.tuner.V1_1.Constants
            .FrontendCableTimeInterleaveMode.INTERLEAVING_16_8;
    /**
     * 8/16 Time Interleave Mode.
     */
    public static final int TIME_INTERLEAVE_MODE_8_16 = android.hardware.tv.tuner.V1_1.Constants
            .FrontendCableTimeInterleaveMode.INTERLEAVING_8_16;
    /**
     * 128/2 Time Interleave Mode.
     */
    public static final int TIME_INTERLEAVE_MODE_128_2 = android.hardware.tv.tuner.V1_1.Constants
            .FrontendCableTimeInterleaveMode.INTERLEAVING_128_2;
    /**
     * 128/3 Time Interleave Mode.
     */
    public static final int TIME_INTERLEAVE_MODE_128_3 = android.hardware.tv.tuner.V1_1.Constants
            .FrontendCableTimeInterleaveMode.INTERLEAVING_128_3;
    /**
     * 128/4 Time Interleave Mode.
     */
    public static final int TIME_INTERLEAVE_MODE_128_4 = android.hardware.tv.tuner.V1_1.Constants
            .FrontendCableTimeInterleaveMode.INTERLEAVING_128_4;

    /** @hide */
    @IntDef(flag = true,
            prefix = "BANDWIDTH_",
            value = {BANDWIDTH_UNDEFINED, BANDWIDTH_5MHZ, BANDWIDTH_6MHZ, BANDWIDTH_7MHZ,
                    BANDWIDTH_8MHZ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Bandwidth {}

    /**
     * Bandwidth undefined.
     */
    public static final int BANDWIDTH_UNDEFINED =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbcBandwidth.UNDEFINED;
    /**
     * 5 MHz bandwidth.
     */
    public static final int BANDWIDTH_5MHZ =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbcBandwidth.BANDWIDTH_5MHZ;
    /**
     * 6 MHz bandwidth.
     */
    public static final int BANDWIDTH_6MHZ =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbcBandwidth.BANDWIDTH_6MHZ;
    /**
     * 7 MHz bandwidth.
     */
    public static final int BANDWIDTH_7MHZ =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbcBandwidth.BANDWIDTH_7MHZ;
    /**
     * 8 MHz bandwidth.
     */
    public static final int BANDWIDTH_8MHZ =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbcBandwidth.BANDWIDTH_8MHZ;


    private final int mModulation;
    private final long mInnerFec;
    private final int mSymbolRate;
    private final int mOuterFec;
    private final int mAnnex;
    private final int mSpectralInversion;
    // Dvbc time interleave mode is only supported in Tuner 1.1 or higher.
    private final int mInterleaveMode;
    // Dvbc bandwidth is only supported in Tuner 1.1 or higher.
    private final int mBandwidth;

    private DvbcFrontendSettings(int frequency, int modulation, long innerFec, int symbolRate,
            int outerFec, int annex, int spectralInversion, int interleaveMode, int bandwidth) {
        super(frequency);
        mModulation = modulation;
        mInnerFec = innerFec;
        mSymbolRate = symbolRate;
        mOuterFec = outerFec;
        mAnnex = annex;
        mSpectralInversion = spectralInversion;
        mInterleaveMode = interleaveMode;
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
     * Gets Inner Forward Error Correction.
     */
    @InnerFec
    public long getInnerFec() {
        return mInnerFec;
    }
    /**
     * Gets Symbol Rate in symbols per second.
     */
    public int getSymbolRate() {
        return mSymbolRate;
    }
    /**
     * Gets Outer Forward Error Correction.
     */
    @OuterFec
    public int getOuterFec() {
        return mOuterFec;
    }
    /**
     * Gets Annex.
     */
    @Annex
    public int getAnnex() {
        return mAnnex;
    }
    /**
     * Gets Spectral Inversion.
     */
    @FrontendSpectralInversion
    public int getSpectralInversion() {
        return mSpectralInversion;
    }
    /**
     * Gets Time Interleave Mode.
     */
    @TimeInterleaveMode
    public int getTimeInterleaveMode() {
        return mInterleaveMode;
    }
    /**
     * Gets Bandwidth.
     */
    @Bandwidth
    public int getBandwidth() {
        return mBandwidth;
    }

    /**
     * Creates a builder for {@link DvbcFrontendSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DvbcFrontendSettings}.
     */
    public static class Builder {
        private int mFrequency = 0;
        private int mModulation = MODULATION_UNDEFINED;
        private long mInnerFec = FEC_UNDEFINED;
        private int mSymbolRate = 0;
        private int mOuterFec = OUTER_FEC_UNDEFINED;
        private int mAnnex = ANNEX_UNDEFINED;
        private int mSpectralInversion = FrontendSettings.FRONTEND_SPECTRAL_INVERSION_UNDEFINED;
        private int mInterleaveMode = TIME_INTERLEAVE_MODE_UNDEFINED;
        private int mBandwidth = BANDWIDTH_UNDEFINED;

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
         * Sets Inner Forward Error Correction.
         *
         * <p>Default value is {@link #FEC_UNDEFINED}.
         */
        @NonNull
        public Builder setInnerFec(@InnerFec long fec) {
            mInnerFec = fec;
            return this;
        }
        /**
         * Sets Symbol Rate in symbols per second.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setSymbolRate(int symbolRate) {
            mSymbolRate = symbolRate;
            return this;
        }
        /**
         * Sets Outer Forward Error Correction.
         *
         * <p>Default value is {@link #OUTER_FEC_UNDEFINED}.
         */
        @NonNull
        public Builder setOuterFec(@OuterFec int outerFec) {
            mOuterFec = outerFec;
            return this;
        }
        /**
         * Sets Annex.
         *
         * <p>Default value is {@link #ANNEX_UNDEFINED}.
         */
        @NonNull
        public Builder setAnnex(@Annex int annex) {
            mAnnex = annex;
            return this;
        }
        /**
         * Sets Spectral Inversion.
         *
         * <p>Default value is {@link FrontendSettings#FRONTEND_SPECTRAL_INVERSION_UNDEFINED}.
         */
        @NonNull
        public Builder setSpectralInversion(@FrontendSpectralInversion int spectralInversion) {
            mSpectralInversion = spectralInversion;
            return this;
        }
        /**
         * Set the time interleave mode.
         *
         * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
         * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
         *
         * @param interleaveMode the value to set as the time interleave mode. Default value is
         * {@link #TIME_INTERLEAVE_MODE_UNDEFINED}.
         */
        @NonNull
        public Builder setTimeInterleaveMode(@TimeInterleaveMode int interleaveMode) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_1_1, "setTimeInterleaveMode")) {
                mInterleaveMode = interleaveMode;
            }
            return this;
        }
        /**
         * Set the Bandwidth.
         *
         * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
         * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
         *
         * @param bandwidth the value to set as the bandwidth. Default value is
         * {@link #BANDWIDTH_UNDEFINED}.
         */
        @NonNull
        public Builder setBandwidth(@Bandwidth int bandwidth) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_1_1, "setBandwidth")) {
                mBandwidth = bandwidth;
            }
            return this;
        }

        /**
         * Builds a {@link DvbcFrontendSettings} object.
         */
        @NonNull
        public DvbcFrontendSettings build() {
            return new DvbcFrontendSettings(mFrequency, mModulation, mInnerFec, mSymbolRate,
                mOuterFec, mAnnex, mSpectralInversion, mInterleaveMode, mBandwidth);
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_DVBC;
    }
}
