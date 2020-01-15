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
import android.annotation.RequiresPermission;
import android.content.Context;
import android.media.tv.tuner.TunerConstants;
import android.media.tv.tuner.TunerConstants.IndexType;
import android.media.tv.tuner.TunerUtils;
import android.media.tv.tuner.filter.FilterConfiguration.FilterType;

/**
 * The Settings for the record in DVR.
 * @hide
 */
public class RecordSettings extends Settings {
    private final int mIndexType;
    private final int mIndexMask;

    private RecordSettings(int mainType, int indexType, int indexMask) {
        super(TunerUtils.getFilterSubtype(mainType, TunerConstants.FILTER_SUBTYPE_RECORD));
        mIndexType = indexType;
        mIndexMask = indexMask;
    }

    /**
     * Gets index type.
     */
    @IndexType
    public int getIndexType() {
        return mIndexType;
    }
    /**
     * Gets index mask.
     */
    @TsRecordEvent.IndexMask
    public int getIndexMask() {
        return mIndexMask;
    }

    /**
     * Creates a builder for {@link RecordSettings}.
     *
     * @param context the context of the caller.
     * @param mainType the filter main type.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @NonNull
    public static Builder builder(@NonNull Context context, @FilterType int mainType) {
        TunerUtils.checkTunerPermission(context);
        return new Builder(mainType);
    }

    /**
     * Builder for {@link RecordSettings}.
     */
    public static class Builder extends Settings.Builder<Builder> {
        private int mIndexType;
        private int mIndexMask;

        private Builder(int mainType) {
            super(mainType);
        }

        /**
         * Sets index type.
         */
        @NonNull
        public Builder setIndexType(@IndexType int indexType) {
            mIndexType = indexType;
            return this;
        }
        /**
         * Sets index mask.
         */
        @NonNull
        public Builder setIndexMask(@TsRecordEvent.IndexMask int indexMask) {
            mIndexMask = indexMask;
            return this;
        }

        /**
         * Builds a {@link RecordSettings} object.
         */
        @NonNull
        public RecordSettings build() {
            return new RecordSettings(mMainType, mIndexType, mIndexMask);
        }

        @Override
        Builder self() {
            return this;
        }
    }

}
