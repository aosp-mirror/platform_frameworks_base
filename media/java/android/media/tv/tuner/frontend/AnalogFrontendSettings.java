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
import android.hardware.tv.tuner.FrontendAnalogAftFlag;
import android.hardware.tv.tuner.FrontendAnalogSifStandard;
import android.hardware.tv.tuner.FrontendAnalogType;
import android.media.tv.tuner.TunerVersionChecker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for analog tuner.
 *
 * @hide
 */
@SystemApi
public class AnalogFrontendSettings extends FrontendSettings {
    /** @hide */
    @IntDef(prefix = "SIGNAL_TYPE_",
            value = {SIGNAL_TYPE_UNDEFINED, SIGNAL_TYPE_AUTO, SIGNAL_TYPE_PAL, SIGNAL_TYPE_PAL_M,
              SIGNAL_TYPE_PAL_N, SIGNAL_TYPE_PAL_60, SIGNAL_TYPE_NTSC, SIGNAL_TYPE_NTSC_443,
              SIGNAL_TYPE_SECAM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SignalType {}

    /**
     * Undefined analog signal type.
     */
    public static final int SIGNAL_TYPE_UNDEFINED = FrontendAnalogType.UNDEFINED;
    /**
     * AUTO analog signal type.
     */
    public static final int SIGNAL_TYPE_AUTO = FrontendAnalogType.AUTO;
    /**
     * PAL analog signal type.
     */
    public static final int SIGNAL_TYPE_PAL = FrontendAnalogType.PAL;
    /**
     * PAL M analog signal type.
     */
    public static final int SIGNAL_TYPE_PAL_M = FrontendAnalogType.PAL_M;
    /**
     * PAL N analog signal type.
     */
    public static final int SIGNAL_TYPE_PAL_N = FrontendAnalogType.PAL_N;
    /**
     * PAL 60 analog signal type.
     */
    public static final int SIGNAL_TYPE_PAL_60 = FrontendAnalogType.PAL_60;
    /**
     * NTSC analog signal type.
     */
    public static final int SIGNAL_TYPE_NTSC = FrontendAnalogType.NTSC;
    /**
     * NTSC 443 analog signal type.
     */
    public static final int SIGNAL_TYPE_NTSC_443 = FrontendAnalogType.NTSC_443;
    /**
     * SECM analog signal type.
     */
    public static final int SIGNAL_TYPE_SECAM = FrontendAnalogType.SECAM;

    /** @hide */
    @IntDef(prefix = "SIF_",
            value = {SIF_UNDEFINED, SIF_AUTO, SIF_BG, SIF_BG_A2, SIF_BG_NICAM, SIF_I, SIF_DK,
            SIF_DK1_A2, SIF_DK2_A2, SIF_DK3_A2, SIF_DK_NICAM, SIF_L, SIF_M, SIF_M_BTSC, SIF_M_A2,
            SIF_M_EIAJ, SIF_I_NICAM, SIF_L_NICAM, SIF_L_PRIME})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SifStandard {}

    /**
     * Undefined Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_UNDEFINED = FrontendAnalogSifStandard.UNDEFINED;
    /**
     * Audo Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_AUTO = FrontendAnalogSifStandard.AUTO;
     /**
     * BG Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_BG = FrontendAnalogSifStandard.BG;
    /**
     * BG-A2 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_BG_A2 = FrontendAnalogSifStandard.BG_A2;
    /**
     * BG-NICAM Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_BG_NICAM = FrontendAnalogSifStandard.BG_NICAM;
    /**
     * I Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_I = FrontendAnalogSifStandard.I;
    /**
     * DK Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK = FrontendAnalogSifStandard.DK;
    /**
     * DK1 A2 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK1_A2 = FrontendAnalogSifStandard.DK1_A2;
    /**
     * DK2 A2 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK2_A2 = FrontendAnalogSifStandard.DK2_A2;
    /**
     * DK3 A2 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK3_A2 = FrontendAnalogSifStandard.DK3_A2;
    /**
     * DK-NICAM Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK_NICAM = FrontendAnalogSifStandard.DK_NICAM;
    /**
     * L Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_L = FrontendAnalogSifStandard.L;
    /**
     * M Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_M = FrontendAnalogSifStandard.M;
    /**
     * M-BTSC Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_M_BTSC = FrontendAnalogSifStandard.M_BTSC;
    /**
     * M-A2 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_M_A2 = FrontendAnalogSifStandard.M_A2;
    /**
     * M-EIAJ Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_M_EIAJ = FrontendAnalogSifStandard.M_EIAJ;
    /**
     * I-NICAM Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_I_NICAM = FrontendAnalogSifStandard.I_NICAM;
    /**
     * L-NICAM Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_L_NICAM = FrontendAnalogSifStandard.L_NICAM;
    /**
     * L-PRIME Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_L_PRIME = FrontendAnalogSifStandard.L_PRIME;

    /** @hide */
    @IntDef(prefix = "AFT_FLAG_",
            value = {AFT_FLAG_UNDEFINED, AFT_FLAG_TRUE, AFT_FLAG_FALSE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AftFlag {}

    /**
     * Aft flag is not defined.
     */
    public static final int AFT_FLAG_UNDEFINED = FrontendAnalogAftFlag.UNDEFINED;
    /**
     * Aft flag is set true.
     */
    public static final int AFT_FLAG_TRUE = FrontendAnalogAftFlag.AFT_TRUE;
    /**
     * Aft flag is not set.
     */
    public static final int AFT_FLAG_FALSE = FrontendAnalogAftFlag.AFT_FALSE;


    private final int mSignalType;
    private final int mSifStandard;
    private final int mAftFlag;

    @Override
    public int getType() {
        return FrontendSettings.TYPE_ANALOG;
    }


    /**
     * Gets analog signal type.
     */
    @SignalType
    public int getSignalType() {
        return mSignalType;
    }

    /**
     * Gets Standard Interchange Format (SIF).
     */
    @SifStandard
    public int getSifStandard() {
        return mSifStandard;
    }

    /**
     * Gets AFT flag.
     */
    @AftFlag
    public int getAftFlag() {
        return mAftFlag;
    }

    /**
     * Creates a builder for {@link AnalogFrontendSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    private AnalogFrontendSettings(long frequency, int signalType, int sifStandard, int aftFlag) {
        super(frequency);
        mSignalType = signalType;
        mSifStandard = sifStandard;
        mAftFlag = aftFlag;
    }

    /**
     * Builder for {@link AnalogFrontendSettings}.
     */
    public static class Builder {
        private long mFrequency = 0;
        private int mSignalType = SIGNAL_TYPE_UNDEFINED;
        private int mSifStandard = SIF_UNDEFINED;
        private int mAftFlag = AFT_FLAG_UNDEFINED;

        private Builder() {}

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
         * Set Aft flag.
         *
         * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
         * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
         *
         * @param aftFlag the value to set the aft flag. The default value is
         * {@link #AFT_FLAG_UNDEFINED}.
         */
        @NonNull
        public Builder setAftFlag(@AftFlag int aftFlag) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                    TunerVersionChecker.TUNER_VERSION_1_1, "setAftFlag")) {
                mAftFlag = aftFlag;
            }
            return this;
        }

        /**
         * Sets analog signal type.
         *
         * <p>Default value is {@link #SIGNAL_TYPE_UNDEFINED}.
         */
        @NonNull
        public Builder setSignalType(@SignalType int signalType) {
            mSignalType = signalType;
            return this;
        }

        /**
         * Sets Standard Interchange Format (SIF).
         *
         * <p>Default value is {@link #SIF_UNDEFINED}.
         */
        @NonNull
        public Builder setSifStandard(@SifStandard int sifStandard) {
            mSifStandard = sifStandard;
            return this;
        }

        /**
         * Builds a {@link AnalogFrontendSettings} object.
         */
        @NonNull
        public AnalogFrontendSettings build() {
            return new AnalogFrontendSettings(mFrequency, mSignalType, mSifStandard, mAftFlag);
        }
    }
}
