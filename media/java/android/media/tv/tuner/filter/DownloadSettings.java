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
import android.media.tv.tuner.TunerUtils;
import android.media.tv.tuner.TunerVersionChecker;

/**
 * Filter Settings for a Download.
 *
 * @hide
 */
@SystemApi
public class DownloadSettings extends Settings {
    private final boolean mUseDownloadId;
    private final int mDownloadId;

    private DownloadSettings(int mainType, boolean useDownloadId, int downloadId) {
        super(TunerUtils.getFilterSubtype(mainType, Filter.SUBTYPE_DOWNLOAD));
        mUseDownloadId = useDownloadId;
        mDownloadId = downloadId;
    }

    /**
     * Gets download ID.
     */
    public int getDownloadId() {
        return mDownloadId;
    }

    /**
     * Gets whether download ID is used.
     *
     * If it's set to false, HAL will begin to send data before it knows downloadId and document
     * structures.
     *
     * <p>This query is only supported in Tuner 2.0 or higher version. Unsupported version will
     * return {@code false}. Use {@link TunerVersionChecker#getTunerVersion()} to get the version
     * information.
     */
    public boolean useDownloadId() { return mUseDownloadId; }

    /**
     * Creates a builder for {@link DownloadSettings}.
     *
     * @param mainType the filter main type.
     */
    @NonNull
    public static Builder builder(@Filter.Type int mainType) {
        return new Builder(mainType);
    }

    /**
     * Builder for {@link DownloadSettings}.
     */
    public static class Builder {
        private final int mMainType;
        private boolean mUseDownloadId = false;
        private int mDownloadId;

        private Builder(int mainType) {
            mMainType = mainType;
        }

        /**
         * Sets whether download ID is used or not.
         *
         * If it's set to false, HAL will begin to send data before it knows downloadId and document
         * structures.
         *
         * <p>This configuration is only supported in Tuner 2.0 or higher version. Unsupported
         * version will cause no-op. Use {@link TunerVersionChecker#getTunerVersion()} to get the
         * version information.
         *
         * <p>Default value is {@code false}.
         */
        @NonNull
        public Builder setUseDownloadId(boolean useDownloadId) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                        TunerVersionChecker.TUNER_VERSION_2_0, "setUseDownloadId")) {
                mUseDownloadId = useDownloadId;
            }
            return this;
        }

        /**
         * Sets download ID.
         */
        @NonNull
        public Builder setDownloadId(int downloadId) {
            mDownloadId = downloadId;
            return this;
        }

        /**
         * Builds a {@link DownloadSettings} object.
         */
        @NonNull
        public DownloadSettings build() {
            return new DownloadSettings(mMainType, mUseDownloadId, mDownloadId);
        }
    }
}
