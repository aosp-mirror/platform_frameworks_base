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
import android.hardware.tv.tuner.V1_0.Constants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for analog tuner.
 *
 * @hide
 */
public class AnalogFrontendSettings extends FrontendSettings {
    /** @hide */
    @IntDef(flag = true,
            prefix = "SIGNAL_TYPE_",
            value = {SIGNAL_TYPE_UNDEFINED, SIGNAL_TYPE_PAL, SIGNAL_TYPE_SECAM, SIGNAL_TYPE_NTSC})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SignalType {}

    /**
     * Undefined analog signal type.
     */
    public static final int SIGNAL_TYPE_UNDEFINED = Constants.FrontendAnalogType.UNDEFINED;
    /**
     * PAL analog signal type.
     */
    public static final int SIGNAL_TYPE_PAL = Constants.FrontendAnalogType.PAL;
    /**
     * SECM analog signal type.
     */
    public static final int SIGNAL_TYPE_SECAM = Constants.FrontendAnalogType.SECAM;
    /**
     * NTSC analog signal type.
     */
    public static final int SIGNAL_TYPE_NTSC = Constants.FrontendAnalogType.NTSC;


    /** @hide */
    @IntDef(flag = true,
            prefix = "SIF_",
            value = {SIF_UNDEFINED, SIF_BG, SIF_BG_A2, SIF_BG_NICAM, SIF_I, SIF_DK,
            SIF_DK1, SIF_DK2, SIF_DK3, SIF_DK_NICAM, SIF_L, SIF_M, SIF_M_BTSC, SIF_M_A2,
            SIF_M_EIA_J, SIF_I_NICAM, SIF_L_NICAM, SIF_L_PRIME})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SifStandard {}

    /**
     * Undefined Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_UNDEFINED = Constants.FrontendAnalogSifStandard.UNDEFINED;
    /**
     * BG Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_BG = Constants.FrontendAnalogSifStandard.BG;
    /**
     * BG-A2 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_BG_A2 = Constants.FrontendAnalogSifStandard.BG_A2;
    /**
     * BG-NICAM Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_BG_NICAM = Constants.FrontendAnalogSifStandard.BG_NICAM;
    /**
     * I Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_I = Constants.FrontendAnalogSifStandard.I;
    /**
     * DK Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK = Constants.FrontendAnalogSifStandard.DK;
    /**
     * DK1 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK1 = Constants.FrontendAnalogSifStandard.DK1;
    /**
     * DK2 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK2 = Constants.FrontendAnalogSifStandard.DK2;
    /**
     * DK3 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK3 = Constants.FrontendAnalogSifStandard.DK3;
    /**
     * DK-NICAM Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_DK_NICAM = Constants.FrontendAnalogSifStandard.DK_NICAM;
    /**
     * L Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_L = Constants.FrontendAnalogSifStandard.L;
    /**
     * M Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_M = Constants.FrontendAnalogSifStandard.M;
    /**
     * M-BTSC Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_M_BTSC = Constants.FrontendAnalogSifStandard.M_BTSC;
    /**
     * M-A2 Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_M_A2 = Constants.FrontendAnalogSifStandard.M_A2;
    /**
     * M-EIA-J Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_M_EIA_J = Constants.FrontendAnalogSifStandard.M_EIA_J;
    /**
     * I-NICAM Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_I_NICAM = Constants.FrontendAnalogSifStandard.I_NICAM;
    /**
     * L-NICAM Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_L_NICAM = Constants.FrontendAnalogSifStandard.L_NICAM;
    /**
     * L-PRIME Analog Standard Interchange Format (SIF).
     */
    public static final int SIF_L_PRIME = Constants.FrontendAnalogSifStandard.L_PRIME;


    private final int mAnalogType;
    private final int mSifStandard;

    @Override
    public int getType() {
        return FrontendSettings.TYPE_ANALOG;
    }


    /**
     * Gets analog signal type.
     */
    @SignalType
    public int getAnalogType() {
        return mAnalogType;
    }

    /**
     * Gets Standard Interchange Format (SIF).
     */
    @SifStandard
    public int getSifStandard() {
        return mSifStandard;
    }

    /**
     * Creates a builder for {@link AnalogFrontendSettings}.
     */
    @NonNull
    public static Builder newBuilder() {
        return new Builder();
    }

    private AnalogFrontendSettings(int frequency, int analogType, int sifStandard) {
        super(frequency);
        mAnalogType = analogType;
        mSifStandard = sifStandard;
    }

    /**
     * Builder for {@link AnalogFrontendSettings}.
     */
    public static class Builder {
        private int mFrequency;
        private int mAnalogType;
        private int mSifStandard;

        private Builder() {}

        /**
         * Sets frequency in Hz.
         */
        @NonNull
        public Builder setFrequency(int frequency) {
            mFrequency = frequency;
            return this;
        }

        /**
         * Sets analog type.
         */
        @NonNull
        public Builder setAnalogType(@SignalType int analogType) {
            mAnalogType = analogType;
            return this;
        }

        /**
         * Sets Standard Interchange Format (SIF).
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
            return new AnalogFrontendSettings(mFrequency, mAnalogType, mSifStandard);
        }
    }
}
