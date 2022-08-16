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
import android.hardware.tv.tuner.FrontendIsdbsCoderate;
import android.hardware.tv.tuner.FrontendIsdbsModulation;
import android.hardware.tv.tuner.FrontendIsdbsRolloff;
import android.hardware.tv.tuner.FrontendIsdbsStreamIdType;
import android.media.tv.tuner.Tuner;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for ISDBS.
 *
 * @hide
 */
@SystemApi
public class IsdbsFrontendSettings extends FrontendSettings {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "STREAM_ID_TYPE_",
            value = {STREAM_ID_TYPE_ID, STREAM_ID_TYPE_RELATIVE_NUMBER})
    public @interface StreamIdType {}

    /**
     * Uses stream ID.
     */
    public static final int STREAM_ID_TYPE_ID = FrontendIsdbsStreamIdType.STREAM_ID;
    /**
     * Uses relative number.
     */
    public static final int STREAM_ID_TYPE_RELATIVE_NUMBER =
            FrontendIsdbsStreamIdType.RELATIVE_STREAM_NUMBER;


    /** @hide */
    @IntDef(prefix = "MODULATION_",
            value = {MODULATION_UNDEFINED, MODULATION_AUTO, MODULATION_MOD_BPSK,
                    MODULATION_MOD_QPSK, MODULATION_MOD_TC8PSK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Modulation {}

    /**
     * Modulation undefined.
     */
    public static final int MODULATION_UNDEFINED = FrontendIsdbsModulation.UNDEFINED;
    /**
     * Hardware is able to detect and set modulation automatically
     */
    public static final int MODULATION_AUTO = FrontendIsdbsModulation.AUTO;
    /**
     * BPSK Modulation.
     */
    public static final int MODULATION_MOD_BPSK = FrontendIsdbsModulation.MOD_BPSK;
    /**
     * QPSK Modulation.
     */
    public static final int MODULATION_MOD_QPSK = FrontendIsdbsModulation.MOD_QPSK;
    /**
     * TC8PSK Modulation.
     */
    public static final int MODULATION_MOD_TC8PSK = FrontendIsdbsModulation.MOD_TC8PSK;


    /** @hide */
    @IntDef(prefix = "CODERATE_",
            value = {CODERATE_UNDEFINED, CODERATE_AUTO, CODERATE_1_2, CODERATE_2_3, CODERATE_3_4,
                    CODERATE_5_6, CODERATE_7_8})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CodeRate {}

    /**
     * Code rate undefined.
     */
    public static final int CODERATE_UNDEFINED = FrontendIsdbsCoderate.UNDEFINED;
    /**
     * Hardware is able to detect and set code rate automatically.
     */
    public static final int CODERATE_AUTO = FrontendIsdbsCoderate.AUTO;
    /**
     * 1/2 code rate.
     */
    public static final int CODERATE_1_2 = FrontendIsdbsCoderate.CODERATE_1_2;
    /**
     * 2/3 code rate.
     */
    public static final int CODERATE_2_3 = FrontendIsdbsCoderate.CODERATE_2_3;
    /**
     * 3/4 code rate.
     */
    public static final int CODERATE_3_4 = FrontendIsdbsCoderate.CODERATE_3_4;
    /**
     * 5/6 code rate.
     */
    public static final int CODERATE_5_6 = FrontendIsdbsCoderate.CODERATE_5_6;
    /**
     * 7/8 code rate.
     */
    public static final int CODERATE_7_8 = FrontendIsdbsCoderate.CODERATE_7_8;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ROLLOFF_",
            value = {ROLLOFF_UNDEFINED, ROLLOFF_0_35})
    public @interface Rolloff {}

    /**
     * Rolloff type undefined.
     */
    public static final int ROLLOFF_UNDEFINED = FrontendIsdbsRolloff.UNDEFINED;
    /**
     * 0,35 rolloff.
     */
    public static final int ROLLOFF_0_35 = FrontendIsdbsRolloff.ROLLOFF_0_35;


    private final int mStreamId;
    private final int mStreamIdType;
    private final int mModulation;
    private final int mCodeRate;
    private final int mSymbolRate;
    private final int mRolloff;

    private IsdbsFrontendSettings(long frequency, int streamId, int streamIdType, int modulation,
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
    @StreamIdType
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
     * Creates a builder for {@link IsdbsFrontendSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link IsdbsFrontendSettings}.
     */
    public static class Builder {
        private long mFrequency = 0;
        private int mStreamId = Tuner.INVALID_STREAM_ID;
        private int mStreamIdType = STREAM_ID_TYPE_ID;
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
         * <p>Default value is {@link #STREAM_ID_TYPE_ID}.
         */
        @NonNull
        public Builder setStreamIdType(@StreamIdType int streamIdType) {
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
         * Builds a {@link IsdbsFrontendSettings} object.
         */
        @NonNull
        public IsdbsFrontendSettings build() {
            return new IsdbsFrontendSettings(mFrequency, mStreamId, mStreamIdType, mModulation,
                    mCodeRate, mSymbolRate, mRolloff);
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_ISDBS;
    }
}
