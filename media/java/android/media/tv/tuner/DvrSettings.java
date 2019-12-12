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

import android.media.tv.tuner.TunerConstants.DataFormat;
import android.media.tv.tuner.TunerConstants.DvrSettingsType;

/**
 * DVR settings.
 *
 * @hide
 */
public class DvrSettings {
    private int mStatusMask;
    private int mLowThreshold;
    private int mHighThreshold;
    private int mPacketSize;

    @DataFormat
    private int mDataFormat;
    @DvrSettingsType
    private int mType;

    private DvrSettings(int statusMask, int lowThreshold, int highThreshold, int packetSize,
            @DataFormat int dataFormat, @DvrSettingsType int type) {
        mStatusMask = statusMask;
        mLowThreshold = lowThreshold;
        mHighThreshold = highThreshold;
        mPacketSize = packetSize;
        mDataFormat = dataFormat;
        mType = type;
    }

    /**
     * Creates a new builder.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder for DvrSettings.
     */
    public static final class Builder {
        private int mStatusMask;
        private int mLowThreshold;
        private int mHighThreshold;
        private int mPacketSize;
        @DataFormat
        private int mDataFormat;
        @DvrSettingsType
        private int mType;

        /**
         * Sets status mask.
         */
        public Builder setStatusMask(int statusMask) {
            this.mStatusMask = statusMask;
            return this;
        }

        /**
         * Sets low threshold.
         */
        public Builder setLowThreshold(int lowThreshold) {
            this.mLowThreshold = lowThreshold;
            return this;
        }

        /**
         * Sets high threshold.
         */
        public Builder setHighThreshold(int highThreshold) {
            this.mHighThreshold = highThreshold;
            return this;
        }

        /**
         * Sets packet size.
         */
        public Builder setPacketSize(int packetSize) {
            this.mPacketSize = packetSize;
            return this;
        }

        /**
         * Sets data format.
         */
        public Builder setDataFormat(@DataFormat int dataFormat) {
            this.mDataFormat = dataFormat;
            return this;
        }

        /**
         * Sets settings type.
         */
        public Builder setType(@DvrSettingsType int type) {
            this.mType = type;
            return this;
        }

        /**
         * Builds a DvrSettings instance.
         */
        public DvrSettings build() {
            return new DvrSettings(
                    mStatusMask, mLowThreshold, mHighThreshold, mPacketSize, mDataFormat, mType);
        }
    }
}
