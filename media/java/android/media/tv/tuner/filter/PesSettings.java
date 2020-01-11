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

import android.annotation.NonNull;
import android.media.tv.tuner.TunerConstants;
import android.media.tv.tuner.TunerUtils;
import android.media.tv.tuner.filter.FilterConfiguration.FilterType;

/**
 * Filter Settings for a PES Data.
 *
 * @hide
 */
public class PesSettings extends Settings {
    private final int mStreamId;
    private final boolean mIsRaw;

    private PesSettings(@FilterType int mainType, int streamId, boolean isRaw) {
        super(TunerUtils.getFilterSubtype(mainType, TunerConstants.FILTER_SUBTYPE_PES));
        mStreamId = streamId;
        mIsRaw = isRaw;
    }

    /**
     * Creates a builder for {@link PesSettings}.
     *
     * @param mainType the filter main type of the settings.
     */
    @NonNull
    public static Builder newBuilder(@FilterType int mainType) {
        return new Builder(mainType);
    }

    /**
     * Builder for {@link PesSettings}.
     */
    public static class Builder {
        private final int mMainType;
        private int mStreamId;
        private boolean mIsRaw;

        private Builder(int mainType) {
            mMainType = mainType;
        }

        /**
         * Sets stream ID.
         *
         * @param streamId the stream ID.
         */
        @NonNull
        public Builder setStreamId(int streamId) {
            mStreamId = streamId;
            return this;
        }

        /**
         * Sets whether it's raw.
         *
         * @param isRaw {@code true} if the data is raw. Filter sends onFilterStatus callback
         * instead of onFilterEvent for raw data. {@code false} otherwise.
         */
        @NonNull
        public Builder setIsRaw(boolean isRaw) {
            mIsRaw = isRaw;
            return this;
        }

        /**
         * Builds a {@link PesSettings} object.
         */
        @NonNull
        public PesSettings build() {
            return new PesSettings(mMainType, mStreamId, mIsRaw);
        }
    }
}
