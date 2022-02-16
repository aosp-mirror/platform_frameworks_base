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

package android.media.tv.tuner.dvr;

import android.annotation.BytesLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.filter.Filter;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * DVR settings used to configure {@link DvrPlayback} and {@link DvrRecorder}.
 *
 * @hide
 */
@SystemApi
public class DvrSettings {

    /** @hide */
    @IntDef(prefix = "DATA_FORMAT_",
            value = {DATA_FORMAT_TS, DATA_FORMAT_PES, DATA_FORMAT_ES, DATA_FORMAT_SHV_TLV})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataFormat {}

    /**
     * Transport Stream.
     */
    public static final int DATA_FORMAT_TS = Constants.DataFormat.TS;
    /**
     * Packetized Elementary Stream.
     */
    public static final int DATA_FORMAT_PES = Constants.DataFormat.PES;
    /**
     * Elementary Stream.
     */
    public static final int DATA_FORMAT_ES = Constants.DataFormat.ES;
    /**
     * TLV (type-length-value) Stream for SHV
     */
    public static final int DATA_FORMAT_SHV_TLV = Constants.DataFormat.SHV_TLV;


    @Filter.Status
    private final int mStatusMask;
    @BytesLong
    private final long mLowThreshold;
    @BytesLong
    private final long mHighThreshold;
    @BytesLong
    private final long mPacketSize;
    @DataFormat
    private final int mDataFormat;

    private DvrSettings(@Filter.Status int statusMask, @BytesLong long lowThreshold,
            @BytesLong long highThreshold, @BytesLong long packetSize, @DataFormat int dataFormat) {
        mStatusMask = statusMask;
        mLowThreshold = lowThreshold;
        mHighThreshold = highThreshold;
        mPacketSize = packetSize;
        mDataFormat = dataFormat;
    }

    /**
     * Gets status mask.
     */
    @Filter.Status
    public int getStatusMask() {
        return mStatusMask;
    }

    /**
     * Gets low threshold in bytes.
     */
    @BytesLong
    public long getLowThreshold() {
        return mLowThreshold;
    }

    /**
     * Sets high threshold in bytes.
     */
    @BytesLong
    public long getHighThreshold() {
        return mHighThreshold;
    }

    /**
     * Gets packet size in bytes.
     */
    @BytesLong
    public long getPacketSize() {
        return mPacketSize;
    }

    /**
     * Gets data format.
     */
    @DataFormat
    public int getDataFormat() {
        return mDataFormat;
    }

    /**
     * Creates a builder for {@link DvrSettings}.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DvrSettings}.
     */
    public static final class Builder {
        private int mStatusMask;
        private long mLowThreshold;
        private long mHighThreshold;
        private long mPacketSize;
        @DataFormat
        private int mDataFormat;

        /**
         * Sets status mask.
         *
         * <p>Use Filter.STATUS_ for {@link DvrRecorder} and DvrPlayback.STATUS_ for
         * {@link DvrPlayback}.
         * <p>If status mask is not set, no status is send to the listener.
         */
        @NonNull
        public Builder setStatusMask(@Filter.Status int statusMask) {
            this.mStatusMask = statusMask;
            return this;
        }

        /**
         * Sets low threshold in bytes.
         */
        @NonNull
        public Builder setLowThreshold(@BytesLong long lowThreshold) {
            this.mLowThreshold = lowThreshold;
            return this;
        }

        /**
         * Sets high threshold in bytes.
         */
        @NonNull
        public Builder setHighThreshold(@BytesLong long highThreshold) {
            this.mHighThreshold = highThreshold;
            return this;
        }

        /**
         * Sets packet size in bytes.
         */
        @NonNull
        public Builder setPacketSize(@BytesLong long packetSize) {
            this.mPacketSize = packetSize;
            return this;
        }

        /**
         * Sets data format.
         */
        @NonNull
        public Builder setDataFormat(@DataFormat int dataFormat) {
            this.mDataFormat = dataFormat;
            return this;
        }

        /**
         * Builds a {@link DvrSettings} object.
         */
        @NonNull
        public DvrSettings build() {
            return new DvrSettings(
                    mStatusMask, mLowThreshold, mHighThreshold, mPacketSize, mDataFormat);
        }
    }
}
