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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.Tuner;
import android.media.tv.tuner.TunerVersionChecker;


import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for DVBS.
 *
 * @hide
 */
@SystemApi
public class DvbsFrontendSettings extends FrontendSettings {
    /** @hide */
    @IntDef(flag = true,
            prefix = "SCAN_TYPE_",
            value = {SCAN_TYPE_UNDEFINED, SCAN_TYPE_DIRECT, SCAN_TYPE_DISEQC,
                    SCAN_TYPE_UNICABLE, SCAN_TYPE_JESS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanType {}

    /**
     * Dvbs scan type undefined.
     */
    public static final int SCAN_TYPE_UNDEFINED =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbsScanType.UNDEFINED;

    /**
     * Dvbs scan type DIRECT.
     */
    public static final int SCAN_TYPE_DIRECT =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbsScanType.DIRECT;

    /**
     * Dvbs scan type DISEQC.
     */
    public static final int SCAN_TYPE_DISEQC =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbsScanType.DISEQC;

    /**
     * Dvbs scan type UNICABLE.
     */
    public static final int SCAN_TYPE_UNICABLE =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbsScanType.UNICABLE;

    /**
     * Dvbs scan type JESS.
     */
    public static final int SCAN_TYPE_JESS =
            android.hardware.tv.tuner.V1_1.Constants.FrontendDvbsScanType.JESS;

    /** @hide */
    @IntDef(flag = true,
            prefix = "MODULATION_",
            value = {MODULATION_UNDEFINED, MODULATION_AUTO, MODULATION_MOD_QPSK,
                    MODULATION_MOD_8PSK, MODULATION_MOD_16QAM, MODULATION_MOD_16PSK,
                    MODULATION_MOD_32PSK, MODULATION_MOD_ACM, MODULATION_MOD_8APSK,
                    MODULATION_MOD_16APSK, MODULATION_MOD_32APSK, MODULATION_MOD_64APSK,
                    MODULATION_MOD_128APSK, MODULATION_MOD_256APSK, MODULATION_MOD_RESERVED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Modulation {}

    /**
     * Modulation undefined.
     */
    public static final int MODULATION_UNDEFINED = Constants.FrontendDvbsModulation.UNDEFINED;
    /**
     * Hardware is able to detect and set modulation automatically
     */
    public static final int MODULATION_AUTO = Constants.FrontendDvbsModulation.AUTO;
    /**
     * QPSK Modulation.
     */
    public static final int MODULATION_MOD_QPSK = Constants.FrontendDvbsModulation.MOD_QPSK;
    /**
     * 8PSK Modulation.
     */
    public static final int MODULATION_MOD_8PSK = Constants.FrontendDvbsModulation.MOD_8PSK;
    /**
     * 16QAM Modulation.
     */
    public static final int MODULATION_MOD_16QAM = Constants.FrontendDvbsModulation.MOD_16QAM;
    /**
     * 16PSK Modulation.
     */
    public static final int MODULATION_MOD_16PSK = Constants.FrontendDvbsModulation.MOD_16PSK;
    /**
     * 32PSK Modulation.
     */
    public static final int MODULATION_MOD_32PSK = Constants.FrontendDvbsModulation.MOD_32PSK;
    /**
     * ACM Modulation.
     */
    public static final int MODULATION_MOD_ACM = Constants.FrontendDvbsModulation.MOD_ACM;
    /**
     * 8APSK Modulation.
     */
    public static final int MODULATION_MOD_8APSK = Constants.FrontendDvbsModulation.MOD_8APSK;
    /**
     * 16APSK Modulation.
     */
    public static final int MODULATION_MOD_16APSK = Constants.FrontendDvbsModulation.MOD_16APSK;
    /**
     * 32APSK Modulation.
     */
    public static final int MODULATION_MOD_32APSK = Constants.FrontendDvbsModulation.MOD_32APSK;
    /**
     * 64APSK Modulation.
     */
    public static final int MODULATION_MOD_64APSK = Constants.FrontendDvbsModulation.MOD_64APSK;
    /**
     * 128APSK Modulation.
     */
    public static final int MODULATION_MOD_128APSK = Constants.FrontendDvbsModulation.MOD_128APSK;
    /**
     * 256APSK Modulation.
     */
    public static final int MODULATION_MOD_256APSK = Constants.FrontendDvbsModulation.MOD_256APSK;
    /**
     * Reversed Modulation.
     */
    public static final int MODULATION_MOD_RESERVED = Constants.FrontendDvbsModulation.MOD_RESERVED;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ROLLOFF_",
            value = {ROLLOFF_UNDEFINED, ROLLOFF_0_35, ROLLOFF_0_25, ROLLOFF_0_20, ROLLOFF_0_15,
                    ROLLOFF_0_10, ROLLOFF_0_5})
    public @interface Rolloff {}

    /**
     * Rolloff range undefined.
     */
    public static final int ROLLOFF_UNDEFINED = Constants.FrontendDvbsRolloff.UNDEFINED;
    /**
     * Rolloff range 0,35.
     */
    public static final int ROLLOFF_0_35 = Constants.FrontendDvbsRolloff.ROLLOFF_0_35;
    /**
     * Rolloff range 0,25.
     */
    public static final int ROLLOFF_0_25 = Constants.FrontendDvbsRolloff.ROLLOFF_0_25;
    /**
     * Rolloff range 0,20.
     */
    public static final int ROLLOFF_0_20 = Constants.FrontendDvbsRolloff.ROLLOFF_0_20;
    /**
     * Rolloff range 0,15.
     */
    public static final int ROLLOFF_0_15 = Constants.FrontendDvbsRolloff.ROLLOFF_0_15;
    /**
     * Rolloff range 0,10.
     */
    public static final int ROLLOFF_0_10 = Constants.FrontendDvbsRolloff.ROLLOFF_0_10;
    /**
     * Rolloff range 0,5.
     */
    public static final int ROLLOFF_0_5 = Constants.FrontendDvbsRolloff.ROLLOFF_0_5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "PILOT_",
            value = {PILOT_UNDEFINED, PILOT_ON, PILOT_OFF, PILOT_AUTO})
    public @interface Pilot {}

    /**
     * Pilot mode undefined.
     */
    public static final int PILOT_UNDEFINED = Constants.FrontendDvbsPilot.UNDEFINED;
    /**
     * Pilot mode on.
     */
    public static final int PILOT_ON = Constants.FrontendDvbsPilot.ON;
    /**
     * Pilot mode off.
     */
    public static final int PILOT_OFF = Constants.FrontendDvbsPilot.OFF;
    /**
     * Pilot mode auto.
     */
    public static final int PILOT_AUTO = Constants.FrontendDvbsPilot.AUTO;


    /** @hide */
    @IntDef(flag = true,
            prefix = "STANDARD_",
            value = {STANDARD_AUTO, STANDARD_S, STANDARD_S2, STANDARD_S2X})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Standard {}

    /**
     * Standard undefined.
     */
    public static final int STANDARD_AUTO = Constants.FrontendDvbsStandard.AUTO;
    /**
     * Standard S.
     */
    public static final int STANDARD_S = Constants.FrontendDvbsStandard.S;
    /**
     * Standard S2.
     */
    public static final int STANDARD_S2 = Constants.FrontendDvbsStandard.S2;
    /**
     * Standard S2X.
     */
    public static final int STANDARD_S2X = Constants.FrontendDvbsStandard.S2X;

    /** @hide */
    @IntDef(prefix = "VCM_MODE_",
            value = {VCM_MODE_UNDEFINED, VCM_MODE_AUTO, VCM_MODE_MANUAL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface VcmMode {}

    /**
     * VCM mode undefined.
     */
    public static final int VCM_MODE_UNDEFINED = Constants.FrontendDvbsVcmMode.UNDEFINED;
    /**
     * Auto VCM mode.
     */
    public static final int VCM_MODE_AUTO = Constants.FrontendDvbsVcmMode.AUTO;
    /**
     * Manual VCM mode.
     */
    public static final int VCM_MODE_MANUAL = Constants.FrontendDvbsVcmMode.MANUAL;


    private final int mModulation;
    private final DvbsCodeRate mCodeRate;
    private final int mSymbolRate;
    private final int mRolloff;
    private final int mPilot;
    private final int mInputStreamId;
    private final int mStandard;
    private final int mVcmMode;
    // Dvbs scan type is only supported in Tuner 1.1 or higher.
    private final int mScanType;
    // isDiseqcRxMessage is only supported in Tuner 1.1 or higher.
    private final boolean mIsDiseqcRxMessage;

    private DvbsFrontendSettings(int frequency, int modulation, DvbsCodeRate codeRate,
            int symbolRate, int rolloff, int pilot, int inputStreamId, int standard, int vcm,
            int scanType, boolean isDiseqcRxMessage) {
        super(frequency);
        mModulation = modulation;
        mCodeRate = codeRate;
        mSymbolRate = symbolRate;
        mRolloff = rolloff;
        mPilot = pilot;
        mInputStreamId = inputStreamId;
        mStandard = standard;
        mVcmMode = vcm;
        mScanType = scanType;
        mIsDiseqcRxMessage = isDiseqcRxMessage;
    }

    /**
     * Gets Modulation.
     */
    @Modulation
    public int getModulation() {
        return mModulation;
    }
    /**
     * Gets Code rate.
     */
    @Nullable
    public DvbsCodeRate getCodeRate() {
        return mCodeRate;
    }
    /**
     * Gets Symbol Rate in symbols per second.
     */
    public int getSymbolRate() {
        return mSymbolRate;
    }
    /**
     * Gets Rolloff.
     */
    @Rolloff
    public int getRolloff() {
        return mRolloff;
    }
    /**
     * Gets Pilot mode.
     */
    @Pilot
    public int getPilot() {
        return mPilot;
    }
    /**
     * Gets Input Stream ID.
     */
    public int getInputStreamId() {
        return mInputStreamId;
    }
    /**
     * Gets DVBS sub-standard.
     */
    @Standard
    public int getStandard() {
        return mStandard;
    }
    /**
     * Gets VCM mode.
     */
    @VcmMode
    public int getVcmMode() {
        return mVcmMode;
    }
    /**
     * Get scan type.
     */
    @ScanType
    public int getScanType() {
        return mScanType;
    }
    /**
     * Get if the client can handle the Diseqc Rx Message or not. Default value is false.
     *
     * The setter {@link Builder#setCanHandleDiseqcRxMessage(boolean)} is only supported with
     * Tuner HAL 1.1 or higher. Use {@link TunerVersionChecker#getTunerVersion()} to check the
     * version.
     */
    public boolean canHandleDiseqcRxMessage() {
        return mIsDiseqcRxMessage;
    }

    /**
     * Creates a builder for {@link DvbsFrontendSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DvbsFrontendSettings}.
     */
    public static class Builder {
        private int mFrequency = 0;
        private int mModulation = MODULATION_UNDEFINED;
        private DvbsCodeRate mCodeRate = null;
        private int mSymbolRate = 0;
        private int mRolloff = ROLLOFF_UNDEFINED;
        private int mPilot = PILOT_UNDEFINED;
        private int mInputStreamId = Tuner.INVALID_STREAM_ID;
        private int mStandard = STANDARD_AUTO;
        private int mVcmMode = VCM_MODE_UNDEFINED;
        private int mScanType = SCAN_TYPE_UNDEFINED;
        private boolean mIsDiseqcRxMessage = false;

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
         * Set the scan type.
         *
         * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
         * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
         *
         * @param scanType the value to set as the scan type. Default value is
         * {@link android.media.tv.tuner.frontend.DvbsFrontendSettings#DVBS_SCAN_TYPE_UNDEFINED}.
         */
        @NonNull
        public Builder setScanType(@ScanType int scanType) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_1_1, "setScanType")) {
                mScanType = scanType;
            }
            return this;
        }

        /**
         * Set true to indicate the client can handle the Diseqc Messages. Note that it's still
         * possible that the client won't receive the messages when HAL is not able to setup Rx
         * channel in the hardware layer.
         *
         * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
         * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
         */
        @NonNull
        public Builder setCanHandleDiseqcRxMessage(boolean canHandleDiseqcMessage) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_1_1, "setCanHandleDiseqcRxMessage")) {
                mIsDiseqcRxMessage = canHandleDiseqcMessage;
            }
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
         * Sets Code rate.
         *
         * <p>Default value is {@code null}.
         */
        @NonNull
        public Builder setCodeRate(@Nullable DvbsCodeRate codeRate) {
            mCodeRate = codeRate;
            return this;
        }
        /**
         * Sets Symbol Rate.
         *
         * <p>Default value is 0.
         */
        @NonNull
        public Builder setSymbolRate(int symbolRate) {
            mSymbolRate = symbolRate;
            return this;
        }
        /**
         * Sets Rolloff.
         *
         * <p>Default value is {@link #ROLLOFF_UNDEFINED}.
         */
        @NonNull
        public Builder setRolloff(@Rolloff int rolloff) {
            mRolloff = rolloff;
            return this;
        }
        /**
         * Sets Pilot mode.
         *
         * <p>Default value is {@link #PILOT_UNDEFINED}.
         */
        @NonNull
        public Builder setPilot(@Pilot int pilot) {
            mPilot = pilot;
            return this;
        }
        /**
         * Sets Input Stream ID.
         *
         * <p>Default value is {@link Tuner#INVALID_STREAM_ID}.
         */
        @NonNull
        public Builder setInputStreamId(int inputStreamId) {
            mInputStreamId = inputStreamId;
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
         * Sets VCM mode.
         *
         * <p>Default value is {@link #VCM_MODE_UNDEFINED}.
         */
        @NonNull
        public Builder setVcmMode(@VcmMode int vcm) {
            mVcmMode = vcm;
            return this;
        }

        /**
         * Builds a {@link DvbsFrontendSettings} object.
         */
        @NonNull
        public DvbsFrontendSettings build() {
            return new DvbsFrontendSettings(mFrequency, mModulation, mCodeRate, mSymbolRate,
                    mRolloff, mPilot, mInputStreamId, mStandard, mVcmMode, mScanType,
                    mIsDiseqcRxMessage);
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_DVBS;
    }
}
