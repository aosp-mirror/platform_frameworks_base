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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.TunerConstants;
import android.media.tv.tuner.TunerConstants.ScIndexType;
import android.media.tv.tuner.TunerUtils;
import android.media.tv.tuner.filter.FilterConfiguration.FilterType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The Settings for the record in DVR.
 * @hide
 */
public class RecordSettings extends Settings {
    /**
     * Indexes can be tagged through TS (Transport Stream) header.
     *
     * @hide
     */
    @IntDef(flag = true,
            prefix = "TS_INDEX_",
            value = {TS_INDEX_FIRST_PACKET, TS_INDEX_PAYLOAD_UNIT_START_INDICATOR,
                    TS_INDEX_CHANGE_TO_NOT_SCRAMBLED, TS_INDEX_CHANGE_TO_EVEN_SCRAMBLED,
                    TS_INDEX_CHANGE_TO_ODD_SCRAMBLED, TS_INDEX_DISCONTINUITY_INDICATOR,
                    TS_INDEX_RANDOM_ACCESS_INDICATOR, TS_INDEX_PRIORITY_INDICATOR,
                    TS_INDEX_PCR_FLAG, TS_INDEX_OPCR_FLAG, TS_INDEX_SPLICING_POINT_FLAG,
                    TS_INDEX_PRIVATE_DATA, TS_INDEX_ADAPTATION_EXTENSION_FLAG})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TsIndexMask {}

    /**
     * TS index FIRST_PACKET.
     * @hide
     */
    public static final int TS_INDEX_FIRST_PACKET = Constants.DemuxTsIndex.FIRST_PACKET;
    /**
     * TS index PAYLOAD_UNIT_START_INDICATOR.
     * @hide
     */
    public static final int TS_INDEX_PAYLOAD_UNIT_START_INDICATOR =
            Constants.DemuxTsIndex.PAYLOAD_UNIT_START_INDICATOR;
    /**
     * TS index CHANGE_TO_NOT_SCRAMBLED.
     * @hide
     */
    public static final int TS_INDEX_CHANGE_TO_NOT_SCRAMBLED =
            Constants.DemuxTsIndex.CHANGE_TO_NOT_SCRAMBLED;
    /**
     * TS index CHANGE_TO_EVEN_SCRAMBLED.
     * @hide
     */
    public static final int TS_INDEX_CHANGE_TO_EVEN_SCRAMBLED =
            Constants.DemuxTsIndex.CHANGE_TO_EVEN_SCRAMBLED;
    /**
     * TS index CHANGE_TO_ODD_SCRAMBLED.
     * @hide
     */
    public static final int TS_INDEX_CHANGE_TO_ODD_SCRAMBLED =
            Constants.DemuxTsIndex.CHANGE_TO_ODD_SCRAMBLED;
    /**
     * TS index DISCONTINUITY_INDICATOR.
     * @hide
     */
    public static final int TS_INDEX_DISCONTINUITY_INDICATOR =
            Constants.DemuxTsIndex.DISCONTINUITY_INDICATOR;
    /**
     * TS index RANDOM_ACCESS_INDICATOR.
     * @hide
     */
    public static final int TS_INDEX_RANDOM_ACCESS_INDICATOR =
            Constants.DemuxTsIndex.RANDOM_ACCESS_INDICATOR;
    /**
     * TS index PRIORITY_INDICATOR.
     * @hide
     */
    public static final int TS_INDEX_PRIORITY_INDICATOR = Constants.DemuxTsIndex.PRIORITY_INDICATOR;
    /**
     * TS index PCR_FLAG.
     * @hide
     */
    public static final int TS_INDEX_PCR_FLAG = Constants.DemuxTsIndex.PCR_FLAG;
    /**
     * TS index OPCR_FLAG.
     * @hide
     */
    public static final int TS_INDEX_OPCR_FLAG = Constants.DemuxTsIndex.OPCR_FLAG;
    /**
     * TS index SPLICING_POINT_FLAG.
     * @hide
     */
    public static final int TS_INDEX_SPLICING_POINT_FLAG =
            Constants.DemuxTsIndex.SPLICING_POINT_FLAG;
    /**
     * TS index PRIVATE_DATA.
     * @hide
     */
    public static final int TS_INDEX_PRIVATE_DATA = Constants.DemuxTsIndex.PRIVATE_DATA;
    /**
     * TS index ADAPTATION_EXTENSION_FLAG.
     * @hide
     */
    public static final int TS_INDEX_ADAPTATION_EXTENSION_FLAG =
            Constants.DemuxTsIndex.ADAPTATION_EXTENSION_FLAG;
    /**
     * @hide
     */
    @IntDef(flag = true,
            prefix = "SC_",
            value = {
                TunerConstants.SC_INDEX_I_FRAME,
                TunerConstants.SC_INDEX_P_FRAME,
                TunerConstants.SC_INDEX_B_FRAME,
                TunerConstants.SC_INDEX_SEQUENCE,
                TunerConstants.SC_HEVC_INDEX_SPS,
                TunerConstants.SC_HEVC_INDEX_AUD,
                TunerConstants.SC_HEVC_INDEX_SLICE_CE_BLA_W_LP,
                TunerConstants.SC_HEVC_INDEX_SLICE_BLA_W_RADL,
                TunerConstants.SC_HEVC_INDEX_SLICE_BLA_N_LP,
                TunerConstants.SC_HEVC_INDEX_SLICE_IDR_W_RADL,
                TunerConstants.SC_HEVC_INDEX_SLICE_IDR_N_LP,
                TunerConstants.SC_HEVC_INDEX_SLICE_TRAIL_CRA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScIndexMask {}


    private final int mTsIndexMask;
    private final int mScIndexType;
    private final int mScIndexMask;

    private RecordSettings(int mainType, int tsIndexType, int scIndexType, int scIndexMask) {
        super(TunerUtils.getFilterSubtype(mainType, TunerConstants.FILTER_SUBTYPE_RECORD));
        mTsIndexMask = tsIndexType;
        mScIndexType = scIndexType;
        mScIndexMask = scIndexMask;
    }

    /**
     * Gets TS index mask.
     */
    @TsIndexMask
    public int getTsIndexMask() {
        return mTsIndexMask;
    }
    /**
     * Gets Start Code index type.
     */
    @ScIndexType
    public int getScIndexType() {
        return mScIndexType;
    }
    /**
     * Gets Start Code index mask.
     */
    @ScIndexMask
    public int getScIndexMask() {
        return mScIndexMask;
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
        private int mTsIndexMask;
        private int mScIndexType;
        private int mScIndexMask;

        private Builder(int mainType) {
            super(mainType);
        }

        /**
         * Sets TS index mask.
         */
        @NonNull
        public Builder setTsIndexMask(@TsIndexMask int indexMask) {
            mTsIndexMask = indexMask;
            return this;
        }
        /**
         * Sets index type.
         */
        @NonNull
        public Builder setScIndexType(@ScIndexType int indexType) {
            mScIndexType = indexType;
            return this;
        }
        /**
         * Sets Start Code index mask.
         */
        @NonNull
        public Builder setScIndexMask(@ScIndexMask int indexMask) {
            mScIndexMask = indexMask;
            return this;
        }

        /**
         * Builds a {@link RecordSettings} object.
         */
        @NonNull
        public RecordSettings build() {
            return new RecordSettings(mMainType, mTsIndexMask, mScIndexType, mScIndexMask);
        }

        @Override
        Builder self() {
            return this;
        }
    }

}
