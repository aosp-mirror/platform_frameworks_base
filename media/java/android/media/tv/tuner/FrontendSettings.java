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

package android.media.tv.tuner;

import android.media.tv.tuner.TunerConstants.FrontendSettingsType;

import java.util.List;

/**
 * @hide
 */
public abstract class FrontendSettings {
    protected int mFrequency;

    /**
     * Returns the frontend type.
     */
    @FrontendSettingsType
    public abstract int getType();

    public int getFrequency() {
        return mFrequency;
    }

    // TODO: use hal constants for enum fields
    // TODO: javaDoc
    // TODO: add builders and getters for other settings type

    /**
     * Frontend settings for analog.
     */
    public static class FrontendAnalogSettings extends FrontendSettings {
        private int mAnalogType;
        private int mSifStandard;

        @Override
        public int getType() {
            return TunerConstants.FRONTEND_TYPE_ANALOG;
        }

        public int getAnalogType() {
            return mAnalogType;
        }

        public int getSifStandard() {
            return mSifStandard;
        }

        /**
         * Creates a new builder object.
         */
        public static Builder newBuilder() {
            return new Builder();
        }

        private FrontendAnalogSettings(int frequency, int analogType, int sifStandard) {
            mFrequency = frequency;
            mAnalogType = analogType;
            mSifStandard = sifStandard;
        }

        /**
         * Builder for FrontendAnalogSettings.
         */
        public static class Builder {
            private int mFrequency;
            private int mAnalogType;
            private int mSifStandard;

            private Builder() {}

            /**
             * Sets frequency.
             */
            public Builder setFrequency(int frequency) {
                mFrequency = frequency;
                return this;
            }

            /**
             * Sets analog type.
             */
            public Builder setAnalogType(int analogType) {
                mAnalogType = analogType;
                return this;
            }

            /**
             * Sets sif standard.
             */
            public Builder setSifStandard(int sifStandard) {
                mSifStandard = sifStandard;
                return this;
            }

            /**
             * Builds a FrontendAnalogSettings instance.
             */
            public FrontendAnalogSettings build() {
                return new FrontendAnalogSettings(mFrequency, mAnalogType, mSifStandard);
            }
        }
    }

    /**
     * Frontend settings for ATSC.
     */
    public static class FrontendAtscSettings extends FrontendSettings {
        public int modulation;

        @Override
        public int getType() {
            return TunerConstants.FRONTEND_TYPE_ATSC;
        }
    }

    /**
     * Frontend settings for ATSC-3.
     */
    public static class FrontendAtsc3Settings extends FrontendSettings {
        public int bandwidth;
        public byte demodOutputFormat;
        public List<FrontendAtsc3PlpSettings> plpSettings;

        @Override
        public int getType() {
            return TunerConstants.FRONTEND_TYPE_ATSC3;
        }
    }

    /**
     * Frontend settings for DVBS.
     */
    public static class FrontendDvbsSettings extends FrontendSettings {
        public int modulation;
        public FrontendDvbsCodeRate coderate;
        public int symbolRate;
        public int rolloff;
        public int pilot;
        public int inputStreamId;
        public byte standard;

        @Override
        public int getType() {
            return TunerConstants.FRONTEND_TYPE_DVBS;
        }
    }

    /**
     * Frontend settings for DVBC.
     */
    public static class FrontendDvbcSettings extends FrontendSettings {
        public int modulation;
        public long fec;
        public int symbolRate;
        public int outerFec;
        public byte annex;
        public int spectralInversion;

        @Override
        public int getType() {
            return TunerConstants.FRONTEND_TYPE_DVBC;
        }
    }

    /**
     * Frontend settings for DVBT.
     */
    public static class FrontendDvbtSettings extends FrontendSettings {
        public int transmissionMode;
        public int bandwidth;
        public int constellation;
        public int hierarchy;
        public int hpCoderate;
        public int lpCoderate;
        public int guardInterval;
        public boolean isHighPriority;
        public byte standard;
        public boolean isMiso;
        public int plpMode;
        public byte plpId;
        public byte plpGroupId;

        @Override
        public int getType() {
            return TunerConstants.FRONTEND_TYPE_DVBT;
        }
    }

    /**
     * Frontend settings for ISDBS.
     */
    public static class FrontendIsdbsSettings extends FrontendSettings {
        public int streamId;
        public int streamIdType;
        public int modulation;
        public int coderate;
        public int symbolRate;
        public int rolloff;

        @Override
        public int getType() {
            return TunerConstants.FRONTEND_TYPE_ISDBS;
        }
    }

    /**
     * Frontend settings for ISDBS-3.
     */
    public static class FrontendIsdbs3Settings extends FrontendSettings {
        public int streamId;
        public int streamIdType;
        public int modulation;
        public int coderate;
        public int symbolRate;
        public int rolloff;

        @Override
        public int getType() {
            return TunerConstants.FRONTEND_TYPE_ISDBS3;
        }
    }

    /**
     * Frontend settings for ISDBT.
     */
    public static class FrontendIsdbtSettings extends FrontendSettings {
        public int modulation;
        public int bandwidth;
        public int coderate;
        public int guardInterval;
        public int serviceAreaId;

        @Override
        public int getType() {
            return TunerConstants.FRONTEND_TYPE_ISDBT;
        }
    }

    /**
     * PLP settings for ATSC-3.
     */
    public static class FrontendAtsc3PlpSettings {
        public byte plpId;
        public int modulation;
        public int interleaveMode;
        public int codeRate;
        public int fec;
    }

    /**
     * Code rate for DVBS.
     */
    public static class FrontendDvbsCodeRate {
        public long fec;
        public boolean isLinear;
        public boolean isShortFrames;
        public int bitsPer1000Symbol;
    }
}
