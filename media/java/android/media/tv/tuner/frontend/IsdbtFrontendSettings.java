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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.FrontendIsdbtBandwidth;
import android.hardware.tv.tuner.FrontendIsdbtMode;
import android.hardware.tv.tuner.FrontendIsdbtModulation;
import android.hardware.tv.tuner.FrontendIsdbtPartialReceptionFlag;
import android.hardware.tv.tuner.FrontendIsdbtTimeInterleaveMode;
import android.media.tv.tuner.TunerVersionChecker;
import android.media.tv.tuner.frontend.DvbtFrontendSettings.CodeRate;
import android.util.Log;
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
    @IntDef(prefix = "MODULATION_",
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
    @IntDef(prefix = "MODE_",
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
    @IntDef(prefix = "BANDWIDTH_",
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

    /** @hide */
    @IntDef(prefix = "PARTIAL_RECEPTION_FLAG_",
            value = {PARTIAL_RECEPTION_FLAG_UNDEFINED, PARTIAL_RECEPTION_FLAG_FALSE,
                    PARTIAL_RECEPTION_FLAG_TRUE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PartialReceptionFlag {}

    /**
     * Partial Reception Flag undefined.
     */
    public static final int PARTIAL_RECEPTION_FLAG_UNDEFINED =
            FrontendIsdbtPartialReceptionFlag.UNDEFINED;
    /**
     * Partial Reception Flag false.
     */
    public static final int PARTIAL_RECEPTION_FLAG_FALSE = FrontendIsdbtPartialReceptionFlag.FALSE;
    /**
     * Partial Reception Flag true.
     */
    public static final int PARTIAL_RECEPTION_FLAG_TRUE = FrontendIsdbtPartialReceptionFlag.TRUE;

    /** @hide */
    @IntDef(prefix = "TIME_INTERLEAVE_MODE_",
            value = {TIME_INTERLEAVE_MODE_UNDEFINED, TIME_INTERLEAVE_MODE_AUTO,
                    TIME_INTERLEAVE_MODE_1_0, TIME_INTERLEAVE_MODE_1_4, TIME_INTERLEAVE_MODE_1_8,
                    TIME_INTERLEAVE_MODE_1_16, TIME_INTERLEAVE_MODE_2_0, TIME_INTERLEAVE_MODE_2_2,
                    TIME_INTERLEAVE_MODE_2_4, TIME_INTERLEAVE_MODE_2_8, TIME_INTERLEAVE_MODE_3_0,
                    TIME_INTERLEAVE_MODE_3_1, TIME_INTERLEAVE_MODE_3_2, TIME_INTERLEAVE_MODE_3_4})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TimeInterleaveMode {}

    /**
     * Time Interleave Mode undefined.
     */
    public static final int TIME_INTERLEAVE_MODE_UNDEFINED =
            FrontendIsdbtTimeInterleaveMode.UNDEFINED;
    /**
     * Hardware is able to detect and set time interleave mode automatically
     */
    public static final int TIME_INTERLEAVE_MODE_AUTO = FrontendIsdbtTimeInterleaveMode.AUTO;
    /**
     * Time Interleave Mode 1: 0.
     */
    public static final int TIME_INTERLEAVE_MODE_1_0 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_1_0;
    /**
     * Time Interleave Mode 1: 4.
     */
    public static final int TIME_INTERLEAVE_MODE_1_4 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_1_4;
    /**
     * Time Interleave Mode 1: 8.
     */
    public static final int TIME_INTERLEAVE_MODE_1_8 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_1_8;
    /**
     * Time Interleave Mode 1: 16.
     */
    public static final int TIME_INTERLEAVE_MODE_1_16 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_1_16;
    /**
     * Time Interleave Mode 2: 0.
     */
    public static final int TIME_INTERLEAVE_MODE_2_0 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_2_0;
    /**
     * Time Interleave Mode 2: 2.
     */
    public static final int TIME_INTERLEAVE_MODE_2_2 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_2_2;
    /**
     * Time Interleave Mode 2: 4.
     */
    public static final int TIME_INTERLEAVE_MODE_2_4 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_2_4;
    /**
     * Time Interleave Mode 2: 8.
     */
    public static final int TIME_INTERLEAVE_MODE_2_8 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_2_8;
    /**
     * Time Interleave Mode 3: 0.
     */
    public static final int TIME_INTERLEAVE_MODE_3_0 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_3_0;
    /**
     * Time Interleave Mode 3: 1.
     */
    public static final int TIME_INTERLEAVE_MODE_3_1 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_3_1;
    /**
     * Time Interleave Mode 3: 2.
     */
    public static final int TIME_INTERLEAVE_MODE_3_2 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_3_2;
    /**
     * Time Interleave Mode 3: 4.
     */
    public static final int TIME_INTERLEAVE_MODE_3_4 =
            FrontendIsdbtTimeInterleaveMode.INTERLEAVE_3_4;

    private final int mBandwidth;
    private final int mMode;
    private final int mGuardInterval;
    private final int mServiceAreaId;
    private final IsdbtLayerSettings[] mLayerSettings;
    private final int mPartialReceptionFlag;
    private static final String TAG = "IsdbtFrontendSettings";

    private IsdbtFrontendSettings(long frequency, int bandwidth, int mode, int guardInterval,
            int serviceAreaId, IsdbtLayerSettings[] layerSettings, int partialReceptionFlag) {
        super(frequency);
        mBandwidth = bandwidth;
        mMode = mode;
        mGuardInterval = guardInterval;
        mServiceAreaId = serviceAreaId;
        mLayerSettings = new IsdbtLayerSettings[layerSettings.length];
        for (int i = 0; i < layerSettings.length; i++) {
            mLayerSettings[i] = layerSettings[i];
        }
        mPartialReceptionFlag = partialReceptionFlag;
    }

    /**
     * Gets Modulation.
     *
     * <p>This query is only supported in Tuner 1.1 or lowner version. Unsupported version will
     * return {@link MODULATION_UNDEFINED}.
     * Use {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     * @deprecated Use {@link #getLayerSettings()} and {@link IsdbtLayerSettings#getModulation()}
     * instead.
     */
    @Deprecated
    @Modulation
    public int getModulation() {
        if (TunerVersionChecker.isHigherOrEqualVersionTo(TunerVersionChecker.TUNER_VERSION_2_0)) {
            return MODULATION_UNDEFINED;
        }
        return mLayerSettings.length > 0 ? mLayerSettings[0].getModulation() : MODULATION_UNDEFINED;
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
     *
     * <p>This query is only supported in Tuner 1.1 or lowner version. Unsupported version will
     * return {@link DvbtFrontendSettings#CODERATE_UNDEFINED}.
     * Use {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     * @deprecated Use {@link #getLayerSettings()} and {@link IsdbtLayerSettings#getCodeRate()}
     * instead.
     */
    @Deprecated
    @CodeRate
    public int getCodeRate() {
        if (TunerVersionChecker.isHigherOrEqualVersionTo(TunerVersionChecker.TUNER_VERSION_2_0)) {
            return DvbtFrontendSettings.CODERATE_UNDEFINED;
        }
        return mLayerSettings.length > 0 ? mLayerSettings[0].getCodeRate()
                                         : DvbtFrontendSettings.CODERATE_UNDEFINED;
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
     * Gets ISDB-T Layer Settings.
     *
     * <p>This query is only supported in Tuner 2.0 or higher version. Unsupported version will
     * return an empty array.
     * Use {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     */
    @SuppressLint("ArrayReturn")
    @NonNull
    public IsdbtLayerSettings[] getLayerSettings() {
        return mLayerSettings;
    }
    /**
     * Gets ISDB-T Partial Reception Flag.
     *
     * <p>This query is only supported in Tuner 2.0 or higher version. Unsupported version will
     * return {@link PARTIALRECEPTIONFLAG_UNDEFINED}.
     * Use {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     */
    @PartialReceptionFlag
    public int getPartialReceptionFlag() {
        return mPartialReceptionFlag;
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
        private long mFrequency = 0;
        private int mBandwidth = BANDWIDTH_UNDEFINED;
        private int mMode = MODE_UNDEFINED;
        private int mGuardInterval = DvbtFrontendSettings.GUARD_INTERVAL_UNDEFINED;
        private int mServiceAreaId = 0;
        private IsdbtLayerSettings[] mLayerSettings = {};
        private int mPartialReceptionFlag = PARTIAL_RECEPTION_FLAG_UNDEFINED;

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
         * Sets Modulation.
         *
         * <p>This configuration is only supported in Tuner 1.1 or lowner version. Unsupported
         * version will cause no-op. Use {@link TunerVersionChecker#getTunerVersion()} to get the
         * version information.
         *
         * <p>Default value is {@link #MODULATION_UNDEFINED}.
         */
        @Deprecated
        @NonNull
        public Builder setModulation(@Modulation int modulation) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_2_0, "setModulation")) {
                Log.d(TAG, "Use IsdbtLayerSettings on HAL 2.0 or higher");
            } else {
                IsdbtLayerSettings.Builder layerBuilder = IsdbtLayerSettings.builder();
                layerBuilder.setModulation(modulation);
                if (mLayerSettings.length == 0) {
                    mLayerSettings = new IsdbtLayerSettings[1];
                } else {
                    layerBuilder.setCodeRate(mLayerSettings[0].getCodeRate());
                }
                mLayerSettings[0] = layerBuilder.build();
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
         * <p>This configuration is only supported in Tuner 1.1 or lowner version. Unsupported
         * version will cause no-op. Use {@link TunerVersionChecker#getTunerVersion()} to get the
         * version information.
         *
         * <p>Default value is {@link DvbtFrontendSettings#CODERATE_UNDEFINED}.
         */
        @Deprecated
        @NonNull
        public Builder setCodeRate(@DvbtFrontendSettings.CodeRate int codeRate) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_2_0, "setModulation")) {
                Log.d(TAG, "Use IsdbtLayerSettings on HAL 2.0 or higher");
            } else {
                IsdbtLayerSettings.Builder layerBuilder = IsdbtLayerSettings.builder();
                layerBuilder.setCodeRate(codeRate);
                if (mLayerSettings.length == 0) {
                    mLayerSettings = new IsdbtLayerSettings[1];
                } else {
                    layerBuilder.setModulation(mLayerSettings[0].getModulation());
                }
                mLayerSettings[0] = layerBuilder.build();
            }
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
         * Sets ISDB-T Layer Settings.
         *
         * <p>This configuration is only supported in Tuner 2.0 or higher version. Unsupported
         * version will cause no-op. Use {@link TunerVersionChecker#getTunerVersion()} to get the
         * version information.
         *
         * <p>Default value is an empty array.
         */
        @NonNull
        public Builder setLayerSettings(
                @SuppressLint("ArrayReturn") @NonNull IsdbtLayerSettings[] layerSettings) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_2_0, "setLayerSettings")) {
                mLayerSettings = new IsdbtLayerSettings[layerSettings.length];
                for (int i = 0; i < layerSettings.length; i++) {
                    mLayerSettings[i] = layerSettings[i];
                }
            }
            return this;
        }
        /**
         * Sets ISDB-T Partial Reception Flag.
         *
         * <p>This configuration is only supported in Tuner 2.0 or higher version. Unsupported
         * version will cause no-op. Use {@link TunerVersionChecker#getTunerVersion()} to get the
         * version information.
         *
         * <p>Default value is {@link PARTIALRECEPTIONFLAG_UNDEFINED}.
         */
        @NonNull
        public Builder setPartialReceptionFlag(@PartialReceptionFlag int flag) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_2_0, "setPartialReceptionFlag")) {
                mPartialReceptionFlag = flag;
            }
            return this;
        }

        /**
         * Builds a {@link IsdbtFrontendSettings} object.
         */
        @NonNull
        public IsdbtFrontendSettings build() {
            return new IsdbtFrontendSettings(mFrequency, mBandwidth, mMode, mGuardInterval,
                    mServiceAreaId, mLayerSettings, mPartialReceptionFlag);
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_ISDBT;
    }

    /**
     * Layer Settings for ISDB-T Frontend.
     *
     * <p>Layer Settings is only supported in Tuner 2.0 or higher version. Use
     * {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     */
    public static final class IsdbtLayerSettings {
        private final int mModulation;
        private final int mTimeInterleaveMode;
        private final int mCodeRate;
        private final int mNumOfSegments;

        private IsdbtLayerSettings(
                int modulation, int timeInterleaveMode, int codeRate, int numOfSegments) {
            mModulation = modulation;
            mTimeInterleaveMode = timeInterleaveMode;
            mCodeRate = codeRate;
            mNumOfSegments = numOfSegments;
        }

        /**
         * Gets Modulation.
         */
        @Modulation
        public int getModulation() {
            return mModulation;
        }
        /**
         * Gets Time Interleave Mode.
         */
        @TimeInterleaveMode
        public int getTimeInterleaveMode() {
            return mTimeInterleaveMode;
        }
        /**
         * Gets Code rate.
         */
        @CodeRate
        public int getCodeRate() {
            return mCodeRate;
        }
        /**
         * Gets Number of Segments.
         */
        @IntRange(from = 0, to = 0xff)
        public int getNumberOfSegments() {
            return mNumOfSegments;
        }

        /**
         * Creates a builder for {@link IsdbtLayerSettings}.
         */
        @NonNull
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder for {@link IsdbtLayerSettings}.
         */
        public static final class Builder {
            private int mModulation = MODULATION_UNDEFINED;
            private int mTimeInterleaveMode = TIME_INTERLEAVE_MODE_UNDEFINED;
            private int mCodeRate = DvbtFrontendSettings.CODERATE_UNDEFINED;
            private int mNumOfSegments = 0;

            private Builder() {}

            /**
             * Sets modulation.
             *
             * <p>Default value is {@link #MODULATION_UNDEFINED}.
             */
            @NonNull
            public Builder setModulation(@Modulation int modulation) {
                mModulation = modulation;
                return this;
            }
            /**
             * Sets time interleave mode.
             *
             * <p>Default value is {@link #TIME_INTERLEAVE_MODE_UNDEFINED}.
             */
            @NonNull
            public Builder setTimeInterleaveMode(@TimeInterleaveMode int mode) {
                mTimeInterleaveMode = mode;
                return this;
            }
            /**
             * Sets code rate.
             */
            @NonNull
            public Builder setCodeRate(@DvbtFrontendSettings.CodeRate int codeRate) {
                mCodeRate = codeRate;
                return this;
            }
            /**
             * Sets number of segments.
             *
             * <p>Default value is 0.
             */
            @NonNull
            @IntRange(from = 0, to = 0xff)
            public Builder setNumberOfSegments(int numOfSegments) {
                mNumOfSegments = numOfSegments;
                return this;
            }

            /**
             * Builds a {@link IsdbtLayerSettings} object.
             */
            @NonNull
            public IsdbtLayerSettings build() {
                return new IsdbtLayerSettings(
                        mModulation, mTimeInterleaveMode, mCodeRate, mNumOfSegments);
            }
        }
    }
}
