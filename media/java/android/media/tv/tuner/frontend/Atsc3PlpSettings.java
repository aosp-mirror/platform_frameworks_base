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

import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * Physical Layer Pipe (PLP) settings for ATSC-3.
 *
 * @hide
 */
@SystemApi
public class Atsc3PlpSettings {
    private final int mPlpId;
    private final int mModulation;
    private final int mInterleaveMode;
    private final int mCodeRate;
    private final int mFec;

    private Atsc3PlpSettings(int plpId, int modulation, int interleaveMode, int codeRate, int fec) {
        mPlpId = plpId;
        mModulation = modulation;
        mInterleaveMode = interleaveMode;
        mCodeRate = codeRate;
        mFec = fec;
    }

    /**
     * Gets Physical Layer Pipe (PLP) ID.
     */
    public int getPlpId() {
        return mPlpId;
    }
    /**
     * Gets Modulation.
     */
    @Atsc3FrontendSettings.Modulation
    public int getModulation() {
        return mModulation;
    }
    /**
     * Gets Interleave Mode.
     */
    @Atsc3FrontendSettings.TimeInterleaveMode
    public int getInterleaveMode() {
        return mInterleaveMode;
    }
    /**
     * Gets Code Rate.
     */
    @Atsc3FrontendSettings.CodeRate
    public int getCodeRate() {
        return mCodeRate;
    }
    /**
     * Gets Forward Error Correction.
     */
    @Atsc3FrontendSettings.Fec
    public int getFec() {
        return mFec;
    }

    /**
     * Creates a builder for {@link Atsc3PlpSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link Atsc3PlpSettings}.
     */
    public static class Builder {
        private int mPlpId;
        private int mModulation;
        private int mInterleaveMode;
        private int mCodeRate;
        private int mFec;

        private Builder() {
        }

        /**
         * Sets Physical Layer Pipe (PLP) ID.
         */
        @NonNull
        public Builder setPlpId(int plpId) {
            mPlpId = plpId;
            return this;
        }
        /**
         * Sets Modulation.
         */
        @NonNull
        public Builder setModulation(@Atsc3FrontendSettings.Modulation int modulation) {
            mModulation = modulation;
            return this;
        }
        /**
         * Sets Interleave Mode.
         */
        @NonNull
        public Builder setInterleaveMode(
                @Atsc3FrontendSettings.TimeInterleaveMode int interleaveMode) {
            mInterleaveMode = interleaveMode;
            return this;
        }
        /**
         * Sets Code Rate.
         */
        @NonNull
        public Builder setCodeRate(@Atsc3FrontendSettings.CodeRate int codeRate) {
            mCodeRate = codeRate;
            return this;
        }
        /**
         * Sets Forward Error Correction.
         */
        @NonNull
        public Builder setFec(@Atsc3FrontendSettings.Fec int fec) {
            mFec = fec;
            return this;
        }

        /**
         * Builds a {@link Atsc3PlpSettings} object.
         */
        @NonNull
        public Atsc3PlpSettings build() {
            return new Atsc3PlpSettings(mPlpId, mModulation, mInterleaveMode, mCodeRate, mFec);
        }
    }
}
