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

import android.media.tv.tuner.FrontendSettings;
import android.media.tv.tuner.TunerConstants;

/**
 * Frontend settings for analog.
 * @hide
 */
public class AnalogFrontendSettings extends FrontendSettings {
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

    private AnalogFrontendSettings(int frequency, int analogType, int sifStandard) {
        super(frequency);
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
        public AnalogFrontendSettings build() {
            return new AnalogFrontendSettings(mFrequency, mAnalogType, mSifStandard);
        }
    }
}
