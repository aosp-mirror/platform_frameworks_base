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
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.TunerUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The Settings for the record in DVR.
 *
 * @hide
 */
@SystemApi
public class RecordSettings extends Settings {
    /**
     * Indexes can be tagged through TS (Transport Stream) header.
     *
     * @hide
     */
    @IntDef(flag = true,
            value = {TS_INDEX_INVALID, TS_INDEX_FIRST_PACKET, TS_INDEX_PAYLOAD_UNIT_START_INDICATOR,
                    TS_INDEX_CHANGE_TO_NOT_SCRAMBLED, TS_INDEX_CHANGE_TO_EVEN_SCRAMBLED,
                    TS_INDEX_CHANGE_TO_ODD_SCRAMBLED, TS_INDEX_DISCONTINUITY_INDICATOR,
                    TS_INDEX_RANDOM_ACCESS_INDICATOR, TS_INDEX_PRIORITY_INDICATOR,
                    TS_INDEX_PCR_FLAG, TS_INDEX_OPCR_FLAG, TS_INDEX_SPLICING_POINT_FLAG,
                    TS_INDEX_PRIVATE_DATA, TS_INDEX_ADAPTATION_EXTENSION_FLAG,
                    MPT_INDEX_MPT, MPT_INDEX_VIDEO, MPT_INDEX_AUDIO,
                    MPT_INDEX_TIMESTAMP_TARGET_VIDEO,
                    MPT_INDEX_TIMESTAMP_TARGET_AUDIO})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TsIndexMask {}

    /**
     * Invalid Transport Stream (TS) index.
     */
    public static final int TS_INDEX_INVALID = 0;
    /**
     * TS index FIRST_PACKET.
     */
    public static final int TS_INDEX_FIRST_PACKET = Constants.DemuxTsIndex.FIRST_PACKET;
    /**
     * TS index PAYLOAD_UNIT_START_INDICATOR.
     */
    public static final int TS_INDEX_PAYLOAD_UNIT_START_INDICATOR =
            Constants.DemuxTsIndex.PAYLOAD_UNIT_START_INDICATOR;
    /**
     * TS index CHANGE_TO_NOT_SCRAMBLED.
     */
    public static final int TS_INDEX_CHANGE_TO_NOT_SCRAMBLED =
            Constants.DemuxTsIndex.CHANGE_TO_NOT_SCRAMBLED;
    /**
     * TS index CHANGE_TO_EVEN_SCRAMBLED.
     */
    public static final int TS_INDEX_CHANGE_TO_EVEN_SCRAMBLED =
            Constants.DemuxTsIndex.CHANGE_TO_EVEN_SCRAMBLED;
    /**
     * TS index CHANGE_TO_ODD_SCRAMBLED.
     */
    public static final int TS_INDEX_CHANGE_TO_ODD_SCRAMBLED =
            Constants.DemuxTsIndex.CHANGE_TO_ODD_SCRAMBLED;
    /**
     * TS index DISCONTINUITY_INDICATOR.
     */
    public static final int TS_INDEX_DISCONTINUITY_INDICATOR =
            Constants.DemuxTsIndex.DISCONTINUITY_INDICATOR;
    /**
     * TS index RANDOM_ACCESS_INDICATOR.
     */
    public static final int TS_INDEX_RANDOM_ACCESS_INDICATOR =
            Constants.DemuxTsIndex.RANDOM_ACCESS_INDICATOR;
    /**
     * TS index PRIORITY_INDICATOR.
     */
    public static final int TS_INDEX_PRIORITY_INDICATOR = Constants.DemuxTsIndex.PRIORITY_INDICATOR;
    /**
     * TS index PCR_FLAG.
     */
    public static final int TS_INDEX_PCR_FLAG = Constants.DemuxTsIndex.PCR_FLAG;
    /**
     * TS index OPCR_FLAG.
     */
    public static final int TS_INDEX_OPCR_FLAG = Constants.DemuxTsIndex.OPCR_FLAG;
    /**
     * TS index SPLICING_POINT_FLAG.
     */
    public static final int TS_INDEX_SPLICING_POINT_FLAG =
            Constants.DemuxTsIndex.SPLICING_POINT_FLAG;
    /**
     * TS index PRIVATE_DATA.
     */
    public static final int TS_INDEX_PRIVATE_DATA = Constants.DemuxTsIndex.PRIVATE_DATA;
    /**
     * TS index ADAPTATION_EXTENSION_FLAG.
     */
    public static final int TS_INDEX_ADAPTATION_EXTENSION_FLAG =
            Constants.DemuxTsIndex.ADAPTATION_EXTENSION_FLAG;
    /**
     * Index the address of MPEG Media Transport Packet Table(MPT).
     */
    public static final int MPT_INDEX_MPT =
            android.hardware.tv.tuner.V1_1.Constants.DemuxTsIndex.MPT_INDEX_MPT;
    /**
     * Index the address of Video.
     */
    public static final int MPT_INDEX_VIDEO =
            android.hardware.tv.tuner.V1_1.Constants.DemuxTsIndex.MPT_INDEX_VIDEO;
    /**
     * Index the address of Audio.
     */
    public static final int MPT_INDEX_AUDIO =
            android.hardware.tv.tuner.V1_1.Constants.DemuxTsIndex.MPT_INDEX_AUDIO;
    /**
     * Index to indicate this is a target of timestamp extraction for video.
     */
    public static final int MPT_INDEX_TIMESTAMP_TARGET_VIDEO =
            android.hardware.tv.tuner.V1_1.Constants.DemuxTsIndex.MPT_INDEX_TIMESTAMP_TARGET_VIDEO;
    /**
     * Index to indicate this is a target of timestamp extraction for audio.
     */
    public static final int MPT_INDEX_TIMESTAMP_TARGET_AUDIO =
            android.hardware.tv.tuner.V1_1.Constants.DemuxTsIndex.MPT_INDEX_TIMESTAMP_TARGET_AUDIO;


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "INDEX_TYPE_", value =
            {INDEX_TYPE_NONE, INDEX_TYPE_SC, INDEX_TYPE_SC_HEVC})
    public @interface ScIndexType {}

    /**
     * Start Code Index is not used.
     */
    public static final int INDEX_TYPE_NONE = Constants.DemuxRecordScIndexType.NONE;
    /**
     * Start Code index.
     */
    public static final int INDEX_TYPE_SC = Constants.DemuxRecordScIndexType.SC;
    /**
     * Start Code index for HEVC.
     */
    public static final int INDEX_TYPE_SC_HEVC = Constants.DemuxRecordScIndexType.SC_HEVC;

    /**
     * Indexes can be tagged by Start Code in PES (Packetized Elementary Stream)
     * according to ISO/IEC 13818-1.
     * @hide
     */
    @IntDef(prefix = "SC_INDEX_",
            flag = true,
            value = {SC_INDEX_I_FRAME, SC_INDEX_P_FRAME, SC_INDEX_B_FRAME,
                    SC_INDEX_SEQUENCE, SC_INDEX_I_SLICE, SC_INDEX_P_SLICE,
                    SC_INDEX_B_SLICE, SC_INDEX_SI_SLICE, SC_INDEX_SP_SLICE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScIndex {}

    /**
     * SC index for a new I-frame.
     */
    public static final int SC_INDEX_I_FRAME = Constants.DemuxScIndex.I_FRAME;
    /**
     * SC index for a new P-frame.
     */
    public static final int SC_INDEX_P_FRAME = Constants.DemuxScIndex.P_FRAME;
    /**
     * SC index for a new B-frame.
     */
    public static final int SC_INDEX_B_FRAME = Constants.DemuxScIndex.B_FRAME;
    /**
     * SC index for a new sequence.
     */
    public static final int SC_INDEX_SEQUENCE = Constants.DemuxScIndex.SEQUENCE;
    /**
     * All blocks are coded as I blocks.
     */
    public static final int SC_INDEX_I_SLICE =
            android.hardware.tv.tuner.V1_1.Constants.DemuxScIndex.I_SLICE;
    /**
     * Blocks are coded as I or P blocks.
     */
    public static final int SC_INDEX_P_SLICE =
            android.hardware.tv.tuner.V1_1.Constants.DemuxScIndex.P_SLICE;
    /**
     * Blocks are coded as I, P or B blocks.
     */
    public static final int SC_INDEX_B_SLICE =
            android.hardware.tv.tuner.V1_1.Constants.DemuxScIndex.B_SLICE;
    /**
     * A so-called switching I slice that is coded.
     */
    public static final int SC_INDEX_SI_SLICE =
            android.hardware.tv.tuner.V1_1.Constants.DemuxScIndex.SI_SLICE;
    /**
     * A so-called switching P slice that is coded.
     */
    public static final int SC_INDEX_SP_SLICE =
            android.hardware.tv.tuner.V1_1.Constants.DemuxScIndex.SP_SLICE;

    /**
     * Indexes can be tagged by NAL unit group in HEVC according to ISO/IEC 23008-2.
     *
     * @hide
     */
    @IntDef(flag = true,
            value = {SC_HEVC_INDEX_SPS, SC_HEVC_INDEX_AUD, SC_HEVC_INDEX_SLICE_CE_BLA_W_LP,
            SC_HEVC_INDEX_SLICE_BLA_W_RADL, SC_HEVC_INDEX_SLICE_BLA_N_LP,
            SC_HEVC_INDEX_SLICE_IDR_W_RADL, SC_HEVC_INDEX_SLICE_IDR_N_LP,
            SC_HEVC_INDEX_SLICE_TRAIL_CRA})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScHevcIndex {}

    /**
     * SC HEVC index SPS.
     */
    public static final int SC_HEVC_INDEX_SPS = Constants.DemuxScHevcIndex.SPS;
    /**
     * SC HEVC index AUD.
     */
    public static final int SC_HEVC_INDEX_AUD = Constants.DemuxScHevcIndex.AUD;
    /**
     * SC HEVC index SLICE_CE_BLA_W_LP.
     */
    public static final int SC_HEVC_INDEX_SLICE_CE_BLA_W_LP =
            Constants.DemuxScHevcIndex.SLICE_CE_BLA_W_LP;
    /**
     * SC HEVC index SLICE_BLA_W_RADL.
     */
    public static final int SC_HEVC_INDEX_SLICE_BLA_W_RADL =
            Constants.DemuxScHevcIndex.SLICE_BLA_W_RADL;
    /**
     * SC HEVC index SLICE_BLA_N_LP.
     */
    public static final int SC_HEVC_INDEX_SLICE_BLA_N_LP =
            Constants.DemuxScHevcIndex.SLICE_BLA_N_LP;
    /**
     * SC HEVC index SLICE_IDR_W_RADL.
     */
    public static final int SC_HEVC_INDEX_SLICE_IDR_W_RADL =
            Constants.DemuxScHevcIndex.SLICE_IDR_W_RADL;
    /**
     * SC HEVC index SLICE_IDR_N_LP.
     */
    public static final int SC_HEVC_INDEX_SLICE_IDR_N_LP =
            Constants.DemuxScHevcIndex.SLICE_IDR_N_LP;
    /**
     * SC HEVC index SLICE_TRAIL_CRA.
     */
    public static final int SC_HEVC_INDEX_SLICE_TRAIL_CRA =
            Constants.DemuxScHevcIndex.SLICE_TRAIL_CRA;

    /**
     * @hide
     */
    @IntDef(flag = true,
            prefix = "SC_",
            value = {
                SC_INDEX_I_FRAME,
                SC_INDEX_P_FRAME,
                SC_INDEX_B_FRAME,
                SC_INDEX_SEQUENCE,
                SC_HEVC_INDEX_SPS,
                SC_HEVC_INDEX_AUD,
                SC_HEVC_INDEX_SLICE_CE_BLA_W_LP,
                SC_HEVC_INDEX_SLICE_BLA_W_RADL,
                SC_HEVC_INDEX_SLICE_BLA_N_LP,
                SC_HEVC_INDEX_SLICE_IDR_W_RADL,
                SC_HEVC_INDEX_SLICE_IDR_N_LP,
                SC_HEVC_INDEX_SLICE_TRAIL_CRA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScIndexMask {}



    private final int mTsIndexMask;
    private final int mScIndexType;
    private final int mScIndexMask;

    private RecordSettings(int mainType, int tsIndexType, int scIndexType, int scIndexMask) {
        super(TunerUtils.getFilterSubtype(mainType, Filter.SUBTYPE_RECORD));
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
     * @param mainType the filter main type.
     */
    @NonNull
    public static Builder builder(@Filter.Type int mainType) {
        return new Builder(mainType);
    }

    /**
     * Builder for {@link RecordSettings}.
     */
    public static class Builder {
        private final int mMainType;
        private int mTsIndexMask;
        private int mScIndexType;
        private int mScIndexMask;

        private Builder(int mainType) {
            mMainType = mainType;
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
    }

}
