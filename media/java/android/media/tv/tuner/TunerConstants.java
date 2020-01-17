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

import android.annotation.IntDef;
import android.annotation.LongDef;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.frontend.DvbcFrontendSettings;
import android.media.tv.tuner.frontend.DvbsFrontendSettings;
import android.media.tv.tuner.frontend.Isdbs3FrontendSettings;
import android.media.tv.tuner.frontend.IsdbsFrontendSettings;
import android.media.tv.tuner.frontend.IsdbtFrontendSettings;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Constants for tuner framework.
 *
 * @hide
 */
@SystemApi
public final class TunerConstants {
    /**
     * Invalid TS packet ID.
     * @hide
     */
    public static final int INVALID_TS_PID = Constants.Constant.INVALID_TS_PID;
    /**
     * Invalid stream ID.
     * @hide
     */
    public static final int INVALID_STREAM_ID = Constants.Constant.INVALID_STREAM_ID;


    /** @hide */
    @IntDef(prefix = "FRONTEND_EVENT_TYPE_",
            value = {FRONTEND_EVENT_TYPE_LOCKED, FRONTEND_EVENT_TYPE_NO_SIGNAL,
                    FRONTEND_EVENT_TYPE_LOST_LOCK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendEventType {}
    /**
     * Frontend locked.
     * @hide
     */
    public static final int FRONTEND_EVENT_TYPE_LOCKED = Constants.FrontendEventType.LOCKED;
    /**
     * No signal detected.
     * @hide
     */
    public static final int FRONTEND_EVENT_TYPE_NO_SIGNAL = Constants.FrontendEventType.NO_SIGNAL;
    /**
     * Frontend lock lost.
     * @hide
     */
    public static final int FRONTEND_EVENT_TYPE_LOST_LOCK = Constants.FrontendEventType.LOST_LOCK;


    /** @hide */
    @IntDef(flag = true, prefix = "FILTER_STATUS_", value = {FILTER_STATUS_DATA_READY,
            FILTER_STATUS_LOW_WATER, FILTER_STATUS_HIGH_WATER, FILTER_STATUS_OVERFLOW})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FilterStatus {}

    /**
     * The status of a filter that the data in the filter buffer is ready to be read.
     */
    public static final int FILTER_STATUS_DATA_READY = Constants.DemuxFilterStatus.DATA_READY;
    /**
     * The status of a filter that the amount of available data in the filter buffer is at low
     * level.
     *
     * The value is set to 25 percent of the buffer size by default. It can be changed when
     * configuring the filter.
     */
    public static final int FILTER_STATUS_LOW_WATER = Constants.DemuxFilterStatus.LOW_WATER;
    /**
     * The status of a filter that the amount of available data in the filter buffer is at high
     * level.
     * The value is set to 75 percent of the buffer size by default. It can be changed when
     * configuring the filter.
     */
    public static final int FILTER_STATUS_HIGH_WATER = Constants.DemuxFilterStatus.HIGH_WATER;
    /**
     * The status of a filter that the filter buffer is full and newly filtered data is being
     * discarded.
     */
    public static final int FILTER_STATUS_OVERFLOW = Constants.DemuxFilterStatus.OVERFLOW;


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "INDEX_TYPE_", value =
            {INDEX_TYPE_NONE, INDEX_TYPE_SC, INDEX_TYPE_SC_HEVC})
    public @interface ScIndexType {}

    /**
     * Start Code Index is not used.
     * @hide
     */
    public static final int INDEX_TYPE_NONE = Constants.DemuxRecordScIndexType.NONE;
    /**
     * Start Code index.
     * @hide
     */
    public static final int INDEX_TYPE_SC = Constants.DemuxRecordScIndexType.SC;
    /**
     * Start Code index for HEVC.
     * @hide
     */
    public static final int INDEX_TYPE_SC_HEVC = Constants.DemuxRecordScIndexType.SC_HEVC;


    /**
     * Indexes can be tagged by Start Code in PES (Packetized Elementary Stream)
     * according to ISO/IEC 13818-1.
     * @hide
     */
    @IntDef(flag = true, value = {SC_INDEX_I_FRAME, SC_INDEX_P_FRAME, SC_INDEX_B_FRAME,
            SC_INDEX_SEQUENCE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScIndex {}

    /**
     * SC index for a new I-frame.
     * @hide
     */
    public static final int SC_INDEX_I_FRAME = Constants.DemuxScIndex.I_FRAME;
    /**
     * SC index for a new P-frame.
     * @hide
     */
    public static final int SC_INDEX_P_FRAME = Constants.DemuxScIndex.P_FRAME;
    /**
     * SC index for a new B-frame.
     * @hide
     */
    public static final int SC_INDEX_B_FRAME = Constants.DemuxScIndex.B_FRAME;
    /**
     * SC index for a new sequence.
     * @hide
     */
    public static final int SC_INDEX_SEQUENCE = Constants.DemuxScIndex.SEQUENCE;


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
     * @hide
     */
    public static final int SC_HEVC_INDEX_SPS = Constants.DemuxScHevcIndex.SPS;
    /**
     * SC HEVC index AUD.
     * @hide
     */
    public static final int SC_HEVC_INDEX_AUD = Constants.DemuxScHevcIndex.AUD;
    /**
     * SC HEVC index SLICE_CE_BLA_W_LP.
     * @hide
     */
    public static final int SC_HEVC_INDEX_SLICE_CE_BLA_W_LP =
            Constants.DemuxScHevcIndex.SLICE_CE_BLA_W_LP;
    /**
     * SC HEVC index SLICE_BLA_W_RADL.
     * @hide
     */
    public static final int SC_HEVC_INDEX_SLICE_BLA_W_RADL =
            Constants.DemuxScHevcIndex.SLICE_BLA_W_RADL;
    /**
     * SC HEVC index SLICE_BLA_N_LP.
     * @hide
     */
    public static final int SC_HEVC_INDEX_SLICE_BLA_N_LP =
            Constants.DemuxScHevcIndex.SLICE_BLA_N_LP;
    /**
     * SC HEVC index SLICE_IDR_W_RADL.
     * @hide
     */
    public static final int SC_HEVC_INDEX_SLICE_IDR_W_RADL =
            Constants.DemuxScHevcIndex.SLICE_IDR_W_RADL;
    /**
     * SC HEVC index SLICE_IDR_N_LP.
     * @hide
     */
    public static final int SC_HEVC_INDEX_SLICE_IDR_N_LP =
            Constants.DemuxScHevcIndex.SLICE_IDR_N_LP;
    /**
     * SC HEVC index SLICE_TRAIL_CRA.
     * @hide
     */
    public static final int SC_HEVC_INDEX_SLICE_TRAIL_CRA =
            Constants.DemuxScHevcIndex.SLICE_TRAIL_CRA;


    /** @hide */
    @IntDef({FRONTEND_SCAN_UNDEFINED, FRONTEND_SCAN_AUTO, FRONTEND_SCAN_BLIND})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendScanType {}
    /** @hide */
    public static final int FRONTEND_SCAN_UNDEFINED = Constants.FrontendScanType.SCAN_UNDEFINED;
    /** @hide */
    public static final int FRONTEND_SCAN_AUTO = Constants.FrontendScanType.SCAN_AUTO;
    /** @hide */
    public static final int FRONTEND_SCAN_BLIND = Constants.FrontendScanType.SCAN_BLIND;



    /** @hide */
    @LongDef({FEC_UNDEFINED, FEC_AUTO, FEC_1_2, FEC_1_3, FEC_1_4, FEC_1_5, FEC_2_3, FEC_2_5,
            FEC_2_9, FEC_3_4, FEC_3_5, FEC_4_5, FEC_4_15, FEC_5_6, FEC_5_9, FEC_6_7, FEC_7_8,
            FEC_7_9, FEC_7_15, FEC_8_9, FEC_8_15, FEC_9_10, FEC_9_20, FEC_11_15, FEC_11_20,
            FEC_11_45, FEC_13_18, FEC_13_45, FEC_14_45, FEC_23_36, FEC_25_36, FEC_26_45, FEC_28_45,
            FEC_29_45, FEC_31_45, FEC_32_45, FEC_77_90})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendInnerFec {}

    /**
     * FEC not defined
     * @hide
     */
    public static final long FEC_UNDEFINED = Constants.FrontendInnerFec.FEC_UNDEFINED;
    /**
     * hardware is able to detect and set FEC automatically
     * @hide
     */
    public static final long FEC_AUTO = Constants.FrontendInnerFec.AUTO;
    /**
     * 1/2 conv. code rate
     * @hide
     */
    public static final long FEC_1_2 = Constants.FrontendInnerFec.FEC_1_2;
    /**
     * 1/3 conv. code rate
     * @hide
     */
    public static final long FEC_1_3 = Constants.FrontendInnerFec.FEC_1_3;
    /**
     * 1/4 conv. code rate
     * @hide
     */
    public static final long FEC_1_4 = Constants.FrontendInnerFec.FEC_1_4;
    /**
     * 1/5 conv. code rate
     * @hide
     */
    public static final long FEC_1_5 = Constants.FrontendInnerFec.FEC_1_5;
    /**
     * 2/3 conv. code rate
     * @hide
     */
    public static final long FEC_2_3 = Constants.FrontendInnerFec.FEC_2_3;
    /**
     * 2/5 conv. code rate
     * @hide
     */
    public static final long FEC_2_5 = Constants.FrontendInnerFec.FEC_2_5;
    /**
     * 2/9 conv. code rate
     * @hide
     */
    public static final long FEC_2_9 = Constants.FrontendInnerFec.FEC_2_9;
    /**
     * 3/4 conv. code rate
     * @hide
     */
    public static final long FEC_3_4 = Constants.FrontendInnerFec.FEC_3_4;
    /**
     * 3/5 conv. code rate
     * @hide
     */
    public static final long FEC_3_5 = Constants.FrontendInnerFec.FEC_3_5;
    /**
     * 4/5 conv. code rate
     * @hide
     */
    public static final long FEC_4_5 = Constants.FrontendInnerFec.FEC_4_5;
    /**
     * 4/15 conv. code rate
     * @hide
     */
    public static final long FEC_4_15 = Constants.FrontendInnerFec.FEC_4_15;
    /**
     * 5/6 conv. code rate
     * @hide
     */
    public static final long FEC_5_6 = Constants.FrontendInnerFec.FEC_5_6;
    /**
     * 5/9 conv. code rate
     * @hide
     */
    public static final long FEC_5_9 = Constants.FrontendInnerFec.FEC_5_9;
    /**
     * 6/7 conv. code rate
     * @hide
     */
    public static final long FEC_6_7 = Constants.FrontendInnerFec.FEC_6_7;
    /**
     * 7/8 conv. code rate
     * @hide
     */
    public static final long FEC_7_8 = Constants.FrontendInnerFec.FEC_7_8;
    /**
     * 7/9 conv. code rate
     * @hide
     */
    public static final long FEC_7_9 = Constants.FrontendInnerFec.FEC_7_9;
    /**
     * 7/15 conv. code rate
     * @hide
     */
    public static final long FEC_7_15 = Constants.FrontendInnerFec.FEC_7_15;
    /**
     * 8/9 conv. code rate
     * @hide
     */
    public static final long FEC_8_9 = Constants.FrontendInnerFec.FEC_8_9;
    /**
     * 8/15 conv. code rate
     * @hide
     */
    public static final long FEC_8_15 = Constants.FrontendInnerFec.FEC_8_15;
    /**
     * 9/10 conv. code rate
     * @hide
     */
    public static final long FEC_9_10 = Constants.FrontendInnerFec.FEC_9_10;
    /**
     * 9/20 conv. code rate
     * @hide
     */
    public static final long FEC_9_20 = Constants.FrontendInnerFec.FEC_9_20;
    /**
     * 11/15 conv. code rate
     * @hide
     */
    public static final long FEC_11_15 = Constants.FrontendInnerFec.FEC_11_15;
    /**
     * 11/20 conv. code rate
     * @hide
     */
    public static final long FEC_11_20 = Constants.FrontendInnerFec.FEC_11_20;
    /**
     * 11/45 conv. code rate
     * @hide
     */
    public static final long FEC_11_45 = Constants.FrontendInnerFec.FEC_11_45;
    /**
     * 13/18 conv. code rate
     * @hide
     */
    public static final long FEC_13_18 = Constants.FrontendInnerFec.FEC_13_18;
    /**
     * 13/45 conv. code rate
     * @hide
     */
    public static final long FEC_13_45 = Constants.FrontendInnerFec.FEC_13_45;
    /**
     * 14/45 conv. code rate
     * @hide
     */
    public static final long FEC_14_45 = Constants.FrontendInnerFec.FEC_14_45;
    /**
     * 23/36 conv. code rate
     * @hide
     */
    public static final long FEC_23_36 = Constants.FrontendInnerFec.FEC_23_36;
    /**
     * 25/36 conv. code rate
     * @hide
     */
    public static final long FEC_25_36 = Constants.FrontendInnerFec.FEC_25_36;
    /**
     * 26/45 conv. code rate
     * @hide
     */
    public static final long FEC_26_45 = Constants.FrontendInnerFec.FEC_26_45;
    /**
     * 28/45 conv. code rate
     * @hide
     */
    public static final long FEC_28_45 = Constants.FrontendInnerFec.FEC_28_45;
    /**
     * 29/45 conv. code rate
     * @hide
     */
    public static final long FEC_29_45 = Constants.FrontendInnerFec.FEC_29_45;
    /**
     * 31/45 conv. code rate
     * @hide
     */
    public static final long FEC_31_45 = Constants.FrontendInnerFec.FEC_31_45;
    /**
     * 32/45 conv. code rate
     * @hide
     */
    public static final long FEC_32_45 = Constants.FrontendInnerFec.FEC_32_45;
    /**
     * 77/90 conv. code rate
     * @hide
     */
    public static final long FEC_77_90 = Constants.FrontendInnerFec.FEC_77_90;


    /** @hide */
    @IntDef(value = {
            DvbcFrontendSettings.MODULATION_UNDEFINED,
            DvbcFrontendSettings.MODULATION_AUTO,
            DvbcFrontendSettings.MODULATION_MOD_16QAM,
            DvbcFrontendSettings.MODULATION_MOD_32QAM,
            DvbcFrontendSettings.MODULATION_MOD_64QAM,
            DvbcFrontendSettings.MODULATION_MOD_128QAM,
            DvbcFrontendSettings.MODULATION_MOD_256QAM,
            DvbsFrontendSettings.MODULATION_UNDEFINED,
            DvbsFrontendSettings.MODULATION_AUTO,
            DvbsFrontendSettings.MODULATION_MOD_QPSK,
            DvbsFrontendSettings.MODULATION_MOD_8PSK,
            DvbsFrontendSettings.MODULATION_MOD_16QAM,
            DvbsFrontendSettings.MODULATION_MOD_16PSK,
            DvbsFrontendSettings.MODULATION_MOD_32PSK,
            DvbsFrontendSettings.MODULATION_MOD_ACM,
            DvbsFrontendSettings.MODULATION_MOD_8APSK,
            DvbsFrontendSettings.MODULATION_MOD_16APSK,
            DvbsFrontendSettings.MODULATION_MOD_32APSK,
            DvbsFrontendSettings.MODULATION_MOD_64APSK,
            DvbsFrontendSettings.MODULATION_MOD_128APSK,
            DvbsFrontendSettings.MODULATION_MOD_256APSK,
            DvbsFrontendSettings.MODULATION_MOD_RESERVED,
            IsdbsFrontendSettings.MODULATION_UNDEFINED,
            IsdbsFrontendSettings.MODULATION_AUTO,
            IsdbsFrontendSettings.MODULATION_MOD_BPSK,
            IsdbsFrontendSettings.MODULATION_MOD_QPSK,
            IsdbsFrontendSettings.MODULATION_MOD_TC8PSK,
            Isdbs3FrontendSettings.MODULATION_UNDEFINED,
            Isdbs3FrontendSettings.MODULATION_AUTO,
            Isdbs3FrontendSettings.MODULATION_MOD_BPSK,
            Isdbs3FrontendSettings.MODULATION_MOD_QPSK,
            Isdbs3FrontendSettings.MODULATION_MOD_8PSK,
            Isdbs3FrontendSettings.MODULATION_MOD_16APSK,
            Isdbs3FrontendSettings.MODULATION_MOD_32APSK,
            IsdbtFrontendSettings.MODULATION_UNDEFINED,
            IsdbtFrontendSettings.MODULATION_AUTO,
            IsdbtFrontendSettings.MODULATION_MOD_DQPSK,
            IsdbtFrontendSettings.MODULATION_MOD_QPSK,
            IsdbtFrontendSettings.MODULATION_MOD_16QAM,
            IsdbtFrontendSettings.MODULATION_MOD_64QAM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendModulation {}


    /** @hide */
    @IntDef({RESULT_SUCCESS, RESULT_UNAVAILABLE, RESULT_NOT_INITIALIZED, RESULT_INVALID_STATE,
            RESULT_INVALID_ARGUMENT, RESULT_OUT_OF_MEMORY, RESULT_UNKNOWN_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Result {}

    /**
     * Operation succeeded.
     */
    public static final int RESULT_SUCCESS = Constants.Result.SUCCESS;
    /**
     * Operation failed because the corresponding resources are not available.
     */
    public static final int RESULT_UNAVAILABLE = Constants.Result.UNAVAILABLE;
    /**
     * Operation failed because the corresponding resources are not initialized.
     */
    public static final int RESULT_NOT_INITIALIZED = Constants.Result.NOT_INITIALIZED;
    /**
     * Operation failed because it's not in a valid state.
     */
    public static final int RESULT_INVALID_STATE = Constants.Result.INVALID_STATE;
    /**
     * Operation failed because there are invalid arguments.
     */
    public static final int RESULT_INVALID_ARGUMENT = Constants.Result.INVALID_ARGUMENT;
    /**
     * Memory allocation failed.
     */
    public static final int RESULT_OUT_OF_MEMORY = Constants.Result.OUT_OF_MEMORY;
    /**
     * Operation failed due to unknown errors.
     */
    public static final int RESULT_UNKNOWN_ERROR = Constants.Result.UNKNOWN_ERROR;

    private TunerConstants() {
    }
}
