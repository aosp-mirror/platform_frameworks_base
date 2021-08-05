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
import android.hardware.tv.tuner.FrontendIsdbs3Coderate;
import android.hardware.tv.tuner.FrontendIsdbs3Modulation;
import android.hardware.tv.tuner.FrontendIsdbs3Rolloff;
import android.media.tv.tuner.Tuner;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for ISDBS-3.
 *
 * @hide
 */
@SystemApi
public class Isdbs3FrontendSettings extends FrontendSettings {
    /** @hide */
    @IntDef(flag = true,
            prefix = "MODULATION_",
            value = {MODULATION_UNDEFINED, MODULATION_AUTO, MODULATION_MOD_BPSK,
            MODULATION_MOD_QPSK, MODULATION_MOD_8PSK, MODULATION_MOD_16APSK,
            MODULATION_MOD_32APSK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Modulation {}

    /**
     * Modulation undefined.
     */
    public static final int MODULATION_UNDEFINED = FrontendIsdbs3Modulation.UNDEFINED;
    /**
     * Hardware is able to detect and set modulation automatically.
     */
    public static final int MODULATION_AUTO = FrontendIsdbs3Modulation.AUTO;
    /**
     * BPSK Modulation.
     */
    public static final int MODULATION_MOD_BPSK = FrontendIsdbs3Modulation.MOD_BPSK;
    /**
     * QPSK Modulation.
     */
    public static final int MODULATION_MOD_QPSK = FrontendIsdbs3Modulation.MOD_QPSK;
    /**
     * 8PSK Modulation.
     */
    public static final int MODULATION_MOD_8PSK = FrontendIsdbs3Modulation.MOD_8PSK;
    /**
     * 16APSK Modulation.
     */
    public static final int MODULATION_MOD_16APSK = FrontendIsdbs3Modulation.MOD_16APSK;
    /**
     * 32APSK Modulation.
     */
    public static final int MODULATION_MOD_32APSK = FrontendIsdbs3Modulation.MOD_32APSK;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            prefix = "CODERATE_",
            value = {CODERATE_UNDEFINED, CODERATE_AUTO, CODERATE_1_3, CODERATE_2_5, CODERATE_1_2,
                    CODERATE_3_5, CODERATE_2_3, CODERATE_3_4, CODERATE_7_9, CODERATE_4_5,
                    CODERATE_5_6, CODERATE_7_8, CODERATE_9_10})
    public @interface CodeRate {}

    /**
     * Code rate undefined.
     */
    public static final int CODERATE_UNDEFINED = FrontendIsdbs3Coderate.UNDEFINED;
    /**
     * Hardware is able to detect and set code rate automatically.
     */
    public static final int CODERATE_AUTO = FrontendIsdbs3Coderate.AUTO;
    /**
     * 1/3 code rate.
     */
    public static final int CODERATE_1_3 = FrontendIsdbs3Coderate.CODERATE_1_3;
    /**
     * 2/5 code rate.
     */
    public static final int CODERATE_2_5 = FrontendIsdbs3Coderate.CODERATE_2_5;
    /**
     * 1/2 code rate.
     */
    public static final int CODERATE_1_2 = FrontendIsdbs3Coderate.CODERATE_1_2;
    /**
     * 3/5 code rate.
     */
    public static final int CODERATE_3_5 = FrontendIsdbs3Coderate.CODERATE_3_5;
    /**
     * 2/3 code rate.
     */
    public static final int CODERATE_2_3 = FrontendIsdbs3Coderate.CODERATE_2_3;
    /**
     * 3/4 code rate.
     */
    public static final int CODERATE_3_4 = FrontendIsdbs3Coderate.CODERATE_3_4;
    /**
     * 7/9 code rate.
     */
    public static final int CODERATE_7_9 = FrontendIsdbs3Coderate.CODERATE_7_9;
    /**
     * 4/5 code rate.
     */
    public static final int CODERATE_4_5 = FrontendIsdbs3Coderate.CODERATE_4_5;
    /**
     * 5/6 code rate.
     */
    public static final int CODERATE_5_6 = FrontendIsdbs3Coderate.CODERATE_5_6;
    /**
     * 7/8 code rate.
     */
    public static final int CODERATE_7_8 = FrontendIsdbs3Coderate.CODERATE_7_8;
    /**
     * 9/10 code rate.
     */
    public static final int CODERATE_9_10 = FrontendIsdbs3Coderate.CODERATE_9_10;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ROLLOFF_",
            value = {ROLLOFF_UNDEFINED, ROLLOFF_0_03})
    public @interface Rolloff {}

    /**
     * Rolloff type undefined.
     */
    public static final int ROLLOFF_UNDEFINED = FrontendIsdbs3Rolloff.UNDEFINED;
    /**
     * 0,03 Rolloff.
     */
    public static final int ROLLOFF_0_03 = FrontendIsdbs3Rolloff.ROLLOFF_0_03;


    private final int mStreamId;
    private final int mStreamIdType;
    private final int mModulation;
    private final int mCodeRate;
    private final int mSymbolRate;
    private final int mRolloff;

    private Isdbs3FrontendSettings(int frequency, int streamId, int streamIdType, int modulation,
            int codeRate, int symbolRate, int rolloff) {
        super(frequency);
        mStreamId = streamId;
        mStreamIdType = streamIdType;
        mModulation = modulation;
        mCodeRate = codeRate;
        mSymbolRate = symbolRate;
        mRolloff = rolloff;
    }

    /**
     * Gets Stream ID.
     */
    public int getStreamId() {
        return mStreamId;
    }
    /**
     * Gets Stream ID Type.
     */
    @IsdbsFrontendSettings.StreamIdType
    public int getStreamIdType() {
        return mStreamIdType;
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
    @CodeRate
    public int getCodeRate() {
        return mCodeRate;
    }
    /**
     * Gets Symbol Rate in symbols per second.
     */
    public int getSymbolRate() {
        return mSymbolRate;
    }
    /**
     * Gets Roll off type.
     */
    @Rolloff
    public int getRolloff() {
        return mRolloff;
    }

    /**
     * Creates a builder for {@link Isdbs3FrontendSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link Isdbs3FrontendSettings}.
     */
    public static class Builder {
        private int mFrequency = 0;
        private int mStreamId = Tuner.INVALID_STREAM_ID;
        private int mStreamIdType = IsdbsFrontendSettings.STREAM_ID_TYPE_ID;
        private int mModulation = MODULATION_UNDEFINED;
        private int mCodeRate = CODERATE_UNDEFINED;
        private int mSymbolRate = 0;
        private int mRolloff = ROLLOFF_UNDEFINED;

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
         * Sets Stream ID.
         *
         * <p>Default value is {@link Tuner#INVALID_STREAM_ID}.
         */
        @NonNull
        public Builder setStreamId(int streamId) {
            mStreamId = streamId;
            return this;
        }
        /**
         * Sets StreamIdType.
         *
         * <p>Default value is {@link IsdbsFrontendSettings#STREAM_ID_TYPE_ID}.
         */
        @NonNull
        public Builder setStreamIdType(@IsdbsFrontendSettings.StreamIdType int streamIdType) {
            mStreamIdType = streamIdType;
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
         * <p>Default value is {@link #CODERATE_UNDEFINED}.
         */
        @NonNull
        public Builder setCodeRate(@CodeRate int codeRate) {
            mCodeRate = codeRate;
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
         * Sets Roll off type.
         *
         * <p>Default value is {@link #ROLLOFF_UNDEFINED}.
         */
        @NonNull
        public Builder setRolloff(@Rolloff int rolloff) {
            mRolloff = rolloff;
            return this;
        }

        /**
         * Builds a {@link Isdbs3FrontendSettings} object.
         */
        @NonNull
        public Isdbs3FrontendSettings build() {
            return new Isdbs3FrontendSettings(mFrequency, mStreamId, mStreamIdType, mModulation,
                    mCodeRate, mSymbolRate, mRolloff);
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_ISDBS3;
    }
}
