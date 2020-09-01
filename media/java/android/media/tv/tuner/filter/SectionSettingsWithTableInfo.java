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
import android.annotation.SystemApi;

/**
 * Table information for Section Filter.
 *
 * @hide
 */
@SystemApi
public class SectionSettingsWithTableInfo extends SectionSettings {
    private final int mTableId;
    private final int mVersion;

    private SectionSettingsWithTableInfo(int mainType, boolean isCheckCrc, boolean isRepeat,
            boolean isRaw, int tableId, int version) {
        super(mainType, isCheckCrc, isRepeat, isRaw);
        mTableId = tableId;
        mVersion = version;
    }

    /**
     * Gets table ID.
     */
    public int getTableId() {
        return mTableId;
    }
    /**
     * Gets version.
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Creates a builder for {@link SectionSettingsWithTableInfo}.
     *
     * @param mainType the filter main type.
     */
    @NonNull
    public static Builder builder(@Filter.Type int mainType) {
        return new Builder(mainType);
    }

    /**
     * Builder for {@link SectionSettingsWithTableInfo}.
     */
    public static class Builder extends SectionSettings.Builder<Builder> {
        private int mTableId;
        private int mVersion;

        private Builder(int mainType) {
            super(mainType);
        }

        /**
         * Sets table ID.
         */
        @NonNull
        public Builder setTableId(int tableId) {
            mTableId = tableId;
            return this;
        }
        /**
         * Sets version.
         */
        @NonNull
        public Builder setVersion(int version) {
            mVersion = version;
            return this;
        }

        /**
         * Builds a {@link SectionSettingsWithTableInfo} object.
         */
        @NonNull
        public SectionSettingsWithTableInfo build() {
            return new SectionSettingsWithTableInfo(
                    mMainType, mCrcEnabled, mIsRepeat, mIsRaw, mTableId, mVersion);
        }

        @Override
        Builder self() {
            return this;
        }
    }

}
