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

package android.media.tv.tuner.filter;

import android.media.tv.tuner.TunerConstants;
import android.media.tv.tuner.TunerUtils;

/**
 * Filter Settings for a PES Data.
 * @hide
 */
public class PesSettings extends Settings {
    private int mStreamId;
    private boolean mIsRaw;

    private PesSettings(int mainType, int streamId, boolean isRaw) {
        super(TunerUtils.getFilterSubtype(mainType, TunerConstants.FILTER_SUBTYPE_PES));
        mStreamId = streamId;
        mIsRaw = isRaw;
    }

    /**
     * Creates a builder for PesSettings.
     */
    public static Builder newBuilder(int mainType) {
        return new Builder(mainType);
    }

    /**
     * Builder for PesSettings.
     */
    public static class Builder {
        private final int mMainType;
        private int mStreamId;
        private boolean mIsRaw;

        public Builder(int mainType) {
            mMainType = mainType;
        }

        /**
         * Sets stream ID.
         */
        public Builder setStreamId(int streamId) {
            mStreamId = streamId;
            return this;
        }

        /**
         * Sets whether it's raw.
         * true if the filter send onFilterStatus instead of onFilterEvent.
         */
        public Builder setIsRaw(boolean isRaw) {
            mIsRaw = isRaw;
            return this;
        }

        /**
         * Builds a PesSettings instance.
         */
        public PesSettings build() {
            return new PesSettings(mMainType, mStreamId, mIsRaw);
        }
    }
}
