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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Constants for tuner framework.
 *
 * @hide
 */
@SystemApi
public final class TunerConstants {
    /** @hide */
    public static final int INVALID_TS_PID = Constants.Constant.INVALID_TS_PID;
    /** @hide */
    public static final int INVALID_STREAM_ID = Constants.Constant.INVALID_STREAM_ID;


    /** @hide */
    @IntDef({FRONTEND_TYPE_UNDEFINED, FRONTEND_TYPE_ANALOG, FRONTEND_TYPE_ATSC, FRONTEND_TYPE_ATSC3,
            FRONTEND_TYPE_DVBC, FRONTEND_TYPE_DVBS, FRONTEND_TYPE_DVBT, FRONTEND_TYPE_ISDBS,
            FRONTEND_TYPE_ISDBS3, FRONTEND_TYPE_ISDBT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendType {}

    /** @hide */
    public static final int FRONTEND_TYPE_UNDEFINED = Constants.FrontendType.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_TYPE_ANALOG = Constants.FrontendType.ANALOG;
    /** @hide */
    public static final int FRONTEND_TYPE_ATSC = Constants.FrontendType.ATSC;
    /** @hide */
    public static final int FRONTEND_TYPE_ATSC3 = Constants.FrontendType.ATSC3;
    /** @hide */
    public static final int FRONTEND_TYPE_DVBC = Constants.FrontendType.DVBC;
    /** @hide */
    public static final int FRONTEND_TYPE_DVBS = Constants.FrontendType.DVBS;
    /** @hide */
    public static final int FRONTEND_TYPE_DVBT = Constants.FrontendType.DVBT;
    /** @hide */
    public static final int FRONTEND_TYPE_ISDBS = Constants.FrontendType.ISDBS;
    /** @hide */
    public static final int FRONTEND_TYPE_ISDBS3 = Constants.FrontendType.ISDBS3;
    /** @hide */
    public static final int FRONTEND_TYPE_ISDBT = Constants.FrontendType.ISDBT;


    /** @hide */
    @IntDef({FRONTEND_EVENT_TYPE_LOCKED, FRONTEND_EVENT_TYPE_NO_SIGNAL,
            FRONTEND_EVENT_TYPE_LOST_LOCK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendEventType {}
    /** @hide */
    public static final int FRONTEND_EVENT_TYPE_LOCKED = Constants.FrontendEventType.LOCKED;
    /** @hide */
    public static final int FRONTEND_EVENT_TYPE_NO_SIGNAL = Constants.FrontendEventType.NO_SIGNAL;
    /** @hide */
    public static final int FRONTEND_EVENT_TYPE_LOST_LOCK = Constants.FrontendEventType.LOST_LOCK;


    /** @hide */
    @IntDef({DATA_FORMAT_TS, DATA_FORMAT_PES, DATA_FORMAT_ES, DATA_FORMAT_SHV_TLV})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataFormat {}
    /** @hide */
    public static final int DATA_FORMAT_TS = Constants.DataFormat.TS;
    /** @hide */
    public static final int DATA_FORMAT_PES = Constants.DataFormat.PES;
    /** @hide */
    public static final int DATA_FORMAT_ES = Constants.DataFormat.ES;
    /** @hide */
    public static final int DATA_FORMAT_SHV_TLV = Constants.DataFormat.SHV_TLV;


    /** @hide */
    @IntDef({DEMUX_T_PID, DEMUX_MMPT_PID})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DemuxPidType {}
    /** @hide */
    public static final int DEMUX_T_PID = 1;
    /** @hide */
    public static final int DEMUX_MMPT_PID = 2;

    /** @hide */
    @IntDef({FRONTEND_SETTINGS_ANALOG, FRONTEND_SETTINGS_ATSC, FRONTEND_SETTINGS_ATSC3,
            FRONTEND_SETTINGS_DVBS, FRONTEND_SETTINGS_DVBC, FRONTEND_SETTINGS_DVBT,
            FRONTEND_SETTINGS_ISDBS, FRONTEND_SETTINGS_ISDBS3, FRONTEND_SETTINGS_ISDBT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendSettingsType {}
    /** @hide */
    public static final int FRONTEND_SETTINGS_ANALOG = 1;
    /** @hide */
    public static final int FRONTEND_SETTINGS_ATSC = 2;
    /** @hide */
    public static final int FRONTEND_SETTINGS_ATSC3 = 3;
    /** @hide */
    public static final int FRONTEND_SETTINGS_DVBS = 4;
    /** @hide */
    public static final int FRONTEND_SETTINGS_DVBC = 5;
    /** @hide */
    public static final int FRONTEND_SETTINGS_DVBT = 6;
    /** @hide */
    public static final int FRONTEND_SETTINGS_ISDBS = 7;
    /** @hide */
    public static final int FRONTEND_SETTINGS_ISDBS3 = 8;
    /** @hide */
    public static final int FRONTEND_SETTINGS_ISDBT = 9;

    /** @hide */
    @IntDef({FILTER_TYPE_TS, FILTER_TYPE_MMTP, FILTER_TYPE_IP, FILTER_TYPE_TLV, FILTER_TYPE_ALP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FilterType {}
    /** @hide */
    public static final int FILTER_TYPE_TS = Constants.DemuxFilterMainType.TS;
    /** @hide */
    public static final int FILTER_TYPE_MMTP = Constants.DemuxFilterMainType.MMTP;
    /** @hide */
    public static final int FILTER_TYPE_IP = Constants.DemuxFilterMainType.IP;
    /** @hide */
    public static final int FILTER_TYPE_TLV = Constants.DemuxFilterMainType.TLV;
    /** @hide */
    public static final int FILTER_TYPE_ALP = Constants.DemuxFilterMainType.ALP;

    /** @hide */
    @IntDef({FILTER_SUBTYPE_UNDEFINED, FILTER_SUBTYPE_SECTION, FILTER_SUBTYPE_PES,
            FILTER_SUBTYPE_AUDIO, FILTER_SUBTYPE_VIDEO, FILTER_SUBTYPE_DOWNLOAD,
            FILTER_SUBTYPE_RECORD, FILTER_SUBTYPE_TS, FILTER_SUBTYPE_PCR, FILTER_SUBTYPE_TEMI,
            FILTER_SUBTYPE_MMPT, FILTER_SUBTYPE_NTP, FILTER_SUBTYPE_IP_PAYLOAD, FILTER_SUBTYPE_IP,
            FILTER_SUBTYPE_PAYLOAD_THROUGH, FILTER_SUBTYPE_TLV, FILTER_SUBTYPE_PTP, })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FilterSubtype {}
    /** @hide */
    public static final int FILTER_SUBTYPE_UNDEFINED = 0;
    /** @hide */
    public static final int FILTER_SUBTYPE_SECTION = 1;
    /** @hide */
    public static final int FILTER_SUBTYPE_PES = 2;
    /** @hide */
    public static final int FILTER_SUBTYPE_AUDIO = 3;
    /** @hide */
    public static final int FILTER_SUBTYPE_VIDEO = 4;
    /** @hide */
    public static final int FILTER_SUBTYPE_DOWNLOAD = 5;
    /** @hide */
    public static final int FILTER_SUBTYPE_RECORD = 6;
    /** @hide */
    public static final int FILTER_SUBTYPE_TS = 7;
    /** @hide */
    public static final int FILTER_SUBTYPE_PCR = 8;
    /** @hide */
    public static final int FILTER_SUBTYPE_TEMI = 9;
    /** @hide */
    public static final int FILTER_SUBTYPE_MMPT = 10;
    /** @hide */
    public static final int FILTER_SUBTYPE_NTP = 11;
    /** @hide */
    public static final int FILTER_SUBTYPE_IP_PAYLOAD = 12;
    /** @hide */
    public static final int FILTER_SUBTYPE_IP = 13;
    /** @hide */
    public static final int FILTER_SUBTYPE_PAYLOAD_THROUGH = 14;
    /** @hide */
    public static final int FILTER_SUBTYPE_TLV = 15;
    /** @hide */
    public static final int FILTER_SUBTYPE_PTP = 16;

    /** @hide */
    @IntDef({FILTER_STATUS_DATA_READY, FILTER_STATUS_LOW_WATER, FILTER_STATUS_HIGH_WATER,
            FILTER_STATUS_OVERFLOW})
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
    @IntDef({FRONTEND_STATUS_TYPE_DEMOD_LOCK, FRONTEND_STATUS_TYPE_SNR, FRONTEND_STATUS_TYPE_BER,
            FRONTEND_STATUS_TYPE_PER, FRONTEND_STATUS_TYPE_PRE_BER,
            FRONTEND_STATUS_TYPE_SIGNAL_QUALITY, FRONTEND_STATUS_TYPE_SIGNAL_STRENGTH,
            FRONTEND_STATUS_TYPE_SYMBOL_RATE, FRONTEND_STATUS_TYPE_FEC,
            FRONTEND_STATUS_TYPE_MODULATION, FRONTEND_STATUS_TYPE_SPECTRAL,
            FRONTEND_STATUS_TYPE_LNB_VOLTAGE, FRONTEND_STATUS_TYPE_PLP_ID,
            FRONTEND_STATUS_TYPE_EWBS, FRONTEND_STATUS_TYPE_AGC, FRONTEND_STATUS_TYPE_LNA,
            FRONTEND_STATUS_TYPE_LAYER_ERROR, FRONTEND_STATUS_TYPE_VBER_CN,
            FRONTEND_STATUS_TYPE_LBER_CN, FRONTEND_STATUS_TYPE_XER_CN, FRONTEND_STATUS_TYPE_MER,
            FRONTEND_STATUS_TYPE_FREQ_OFFSET, FRONTEND_STATUS_TYPE_HIERARCHY,
            FRONTEND_STATUS_TYPE_RF_LOCK, FRONTEND_STATUS_TYPE_ATSC3_PLP_INFO})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendStatusType {}

    /**
     * Lock status for Demod.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_DEMOD_LOCK =
            Constants.FrontendStatusType.DEMOD_LOCK;
    /**
     * Signal to Noise Ratio.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_SNR = Constants.FrontendStatusType.SNR;
    /**
     * Bit Error Ratio.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_BER = Constants.FrontendStatusType.BER;
    /**
     * Packages Error Ratio.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_PER = Constants.FrontendStatusType.PER;
    /**
     * Bit Error Ratio before FEC.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_PRE_BER = Constants.FrontendStatusType.PRE_BER;
    /**
     * Signal Quality (0..100). Good data over total data in percent can be
     * used as a way to present Signal Quality.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_SIGNAL_QUALITY =
            Constants.FrontendStatusType.SIGNAL_QUALITY;
    /**
     * Signal Strength.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_SIGNAL_STRENGTH =
            Constants.FrontendStatusType.SIGNAL_STRENGTH;
    /**
     * Symbol Rate.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_SYMBOL_RATE =
            Constants.FrontendStatusType.SYMBOL_RATE;
    /**
     * Forward Error Correction Type.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_FEC = Constants.FrontendStatusType.FEC;
    /**
     * Modulation Type.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_MODULATION =
            Constants.FrontendStatusType.MODULATION;
    /**
     * Spectral Inversion Type.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_SPECTRAL = Constants.FrontendStatusType.SPECTRAL;
    /**
     * LNB Voltage.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_LNB_VOLTAGE =
            Constants.FrontendStatusType.LNB_VOLTAGE;
    /**
     * Physical Layer Pipe ID.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_PLP_ID = Constants.FrontendStatusType.PLP_ID;
    /**
     * Status for Emergency Warning Broadcasting System.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_EWBS = Constants.FrontendStatusType.EWBS;
    /**
     * Automatic Gain Control.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_AGC = Constants.FrontendStatusType.AGC;
    /**
     * Low Noise Amplifier.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_LNA = Constants.FrontendStatusType.LNA;
    /**
     * Error status by layer.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_LAYER_ERROR =
            Constants.FrontendStatusType.LAYER_ERROR;
    /**
     * CN value by VBER.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_VBER_CN = Constants.FrontendStatusType.VBER_CN;
    /**
     * CN value by LBER.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_LBER_CN = Constants.FrontendStatusType.LBER_CN;
    /**
     * CN value by XER.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_XER_CN = Constants.FrontendStatusType.XER_CN;
    /**
     * Moduration Error Ratio.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_MER = Constants.FrontendStatusType.MER;
    /**
     * Difference between tuning frequency and actual locked frequency.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_FREQ_OFFSET =
            Constants.FrontendStatusType.FREQ_OFFSET;
    /**
     * Hierarchy for DVBT.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_HIERARCHY = Constants.FrontendStatusType.HIERARCHY;
    /**
     * Lock status for RF.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_RF_LOCK = Constants.FrontendStatusType.RF_LOCK;
    /**
     * PLP information in a frequency band for ATSC3.0 frontend.
     * @hide
     */
    public static final int FRONTEND_STATUS_TYPE_ATSC3_PLP_INFO =
            Constants.FrontendStatusType.ATSC3_PLP_INFO;

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
    @IntDef({DVBC_MODULATION_UNDEFINED, DVBC_MODULATION_AUTO, DVBC_MODULATION_MOD_16QAM,
            DVBC_MODULATION_MOD_32QAM, DVBC_MODULATION_MOD_64QAM, DVBC_MODULATION_MOD_128QAM,
            DVBC_MODULATION_MOD_256QAM, DVBS_MODULATION_UNDEFINED, DVBS_MODULATION_AUTO,
            DVBS_MODULATION_MOD_QPSK, DVBS_MODULATION_MOD_8PSK, DVBS_MODULATION_MOD_16QAM,
            DVBS_MODULATION_MOD_16PSK, DVBS_MODULATION_MOD_32PSK, DVBS_MODULATION_MOD_ACM,
            DVBS_MODULATION_MOD_8APSK, DVBS_MODULATION_MOD_16APSK, DVBS_MODULATION_MOD_32APSK,
            DVBS_MODULATION_MOD_64APSK, DVBS_MODULATION_MOD_128APSK, DVBS_MODULATION_MOD_256APSK,
            DVBS_MODULATION_MOD_RESERVED, ISDBS_MODULATION_UNDEFINED, ISDBS_MODULATION_AUTO,
            ISDBS_MODULATION_MOD_BPSK, ISDBS_MODULATION_MOD_QPSK, ISDBS_MODULATION_MOD_TC8PSK,
            ISDBS3_MODULATION_UNDEFINED, ISDBS3_MODULATION_AUTO, ISDBS3_MODULATION_MOD_BPSK,
            ISDBS3_MODULATION_MOD_QPSK, ISDBS3_MODULATION_MOD_8PSK, ISDBS3_MODULATION_MOD_16APSK,
            ISDBS3_MODULATION_MOD_32APSK, ISDBT_MODULATION_UNDEFINED, ISDBT_MODULATION_AUTO,
            ISDBT_MODULATION_MOD_DQPSK, ISDBT_MODULATION_MOD_QPSK, ISDBT_MODULATION_MOD_16QAM,
            ISDBT_MODULATION_MOD_64QAM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendModulation {}
    /** @hide */
    public static final int DVBC_MODULATION_UNDEFINED = Constants.FrontendDvbcModulation.UNDEFINED;
    /** @hide */
    public static final int DVBC_MODULATION_AUTO = Constants.FrontendDvbcModulation.AUTO;
    /** @hide */
    public static final int DVBC_MODULATION_MOD_16QAM = Constants.FrontendDvbcModulation.MOD_16QAM;
    /** @hide */
    public static final int DVBC_MODULATION_MOD_32QAM = Constants.FrontendDvbcModulation.MOD_32QAM;
    /** @hide */
    public static final int DVBC_MODULATION_MOD_64QAM = Constants.FrontendDvbcModulation.MOD_64QAM;
    /** @hide */
    public static final int DVBC_MODULATION_MOD_128QAM =
            Constants.FrontendDvbcModulation.MOD_128QAM;
    /** @hide */
    public static final int DVBC_MODULATION_MOD_256QAM =
            Constants.FrontendDvbcModulation.MOD_256QAM;
    /** @hide */
    public static final int DVBS_MODULATION_UNDEFINED = Constants.FrontendDvbsModulation.UNDEFINED;
    /** @hide */
    public static final int DVBS_MODULATION_AUTO = Constants.FrontendDvbsModulation.AUTO;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_QPSK = Constants.FrontendDvbsModulation.MOD_QPSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_8PSK = Constants.FrontendDvbsModulation.MOD_8PSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_16QAM = Constants.FrontendDvbsModulation.MOD_16QAM;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_16PSK = Constants.FrontendDvbsModulation.MOD_16PSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_32PSK = Constants.FrontendDvbsModulation.MOD_32PSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_ACM = Constants.FrontendDvbsModulation.MOD_ACM;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_8APSK = Constants.FrontendDvbsModulation.MOD_8APSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_16APSK =
            Constants.FrontendDvbsModulation.MOD_16APSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_32APSK =
            Constants.FrontendDvbsModulation.MOD_32APSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_64APSK =
            Constants.FrontendDvbsModulation.MOD_64APSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_128APSK =
            Constants.FrontendDvbsModulation.MOD_128APSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_256APSK =
            Constants.FrontendDvbsModulation.MOD_256APSK;
    /** @hide */
    public static final int DVBS_MODULATION_MOD_RESERVED =
            Constants.FrontendDvbsModulation.MOD_RESERVED;
    /** @hide */
    public static final int ISDBS_MODULATION_UNDEFINED =
            Constants.FrontendIsdbsModulation.UNDEFINED;
    /** @hide */
    public static final int ISDBS_MODULATION_AUTO = Constants.FrontendIsdbsModulation.AUTO;
    /** @hide */
    public static final int ISDBS_MODULATION_MOD_BPSK = Constants.FrontendIsdbsModulation.MOD_BPSK;
    /** @hide */
    public static final int ISDBS_MODULATION_MOD_QPSK = Constants.FrontendIsdbsModulation.MOD_QPSK;
    /** @hide */
    public static final int ISDBS_MODULATION_MOD_TC8PSK =
            Constants.FrontendIsdbsModulation.MOD_TC8PSK;
    /** @hide */
    public static final int ISDBS3_MODULATION_UNDEFINED =
            Constants.FrontendIsdbs3Modulation.UNDEFINED;
    /** @hide */
    public static final int ISDBS3_MODULATION_AUTO = Constants.FrontendIsdbs3Modulation.AUTO;
    /** @hide */
    public static final int ISDBS3_MODULATION_MOD_BPSK =
            Constants.FrontendIsdbs3Modulation.MOD_BPSK;
    /** @hide */
    public static final int ISDBS3_MODULATION_MOD_QPSK =
            Constants.FrontendIsdbs3Modulation.MOD_QPSK;
    /** @hide */
    public static final int ISDBS3_MODULATION_MOD_8PSK =
            Constants.FrontendIsdbs3Modulation.MOD_8PSK;
    /** @hide */
    public static final int ISDBS3_MODULATION_MOD_16APSK =
            Constants.FrontendIsdbs3Modulation.MOD_16APSK;
    /** @hide */
    public static final int ISDBS3_MODULATION_MOD_32APSK =
            Constants.FrontendIsdbs3Modulation.MOD_32APSK;
    /** @hide */
    public static final int ISDBT_MODULATION_UNDEFINED =
            Constants.FrontendIsdbtModulation.UNDEFINED;
    /** @hide */
    public static final int ISDBT_MODULATION_AUTO = Constants.FrontendIsdbtModulation.AUTO;
    /** @hide */
    public static final int ISDBT_MODULATION_MOD_DQPSK =
            Constants.FrontendIsdbtModulation.MOD_DQPSK;
    /** @hide */
    public static final int ISDBT_MODULATION_MOD_QPSK = Constants.FrontendIsdbtModulation.MOD_QPSK;
    /** @hide */
    public static final int ISDBT_MODULATION_MOD_16QAM =
            Constants.FrontendIsdbtModulation.MOD_16QAM;
    /** @hide */
    public static final int ISDBT_MODULATION_MOD_64QAM =
            Constants.FrontendIsdbtModulation.MOD_64QAM;


    /** @hide */
    @IntDef({SPECTRAL_INVERSION_UNDEFINED, SPECTRAL_INVERSION_NORMAL, SPECTRAL_INVERSION_INVERTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendDvbcSpectralInversion {}
    /** @hide */
    public static final int SPECTRAL_INVERSION_UNDEFINED =
            Constants.FrontendDvbcSpectralInversion.UNDEFINED;
    /** @hide */
    public static final int SPECTRAL_INVERSION_NORMAL =
            Constants.FrontendDvbcSpectralInversion.NORMAL;
    /** @hide */
    public static final int SPECTRAL_INVERSION_INVERTED =
            Constants.FrontendDvbcSpectralInversion.INVERTED;


    /** @hide */
    @IntDef({HIERARCHY_UNDEFINED, HIERARCHY_AUTO, HIERARCHY_NON_NATIVE, HIERARCHY_1_NATIVE,
            HIERARCHY_2_NATIVE, HIERARCHY_4_NATIVE, HIERARCHY_NON_INDEPTH, HIERARCHY_1_INDEPTH,
            HIERARCHY_2_INDEPTH, HIERARCHY_4_INDEPTH})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendDvbtHierarchy {}
    /** @hide */
    public static final int HIERARCHY_UNDEFINED = Constants.FrontendDvbtHierarchy.UNDEFINED;
    /** @hide */
    public static final int HIERARCHY_AUTO = Constants.FrontendDvbtHierarchy.AUTO;
    /** @hide */
    public static final int HIERARCHY_NON_NATIVE =
            Constants.FrontendDvbtHierarchy.HIERARCHY_NON_NATIVE;
    /** @hide */
    public static final int HIERARCHY_1_NATIVE = Constants.FrontendDvbtHierarchy.HIERARCHY_1_NATIVE;
    /** @hide */
    public static final int HIERARCHY_2_NATIVE = Constants.FrontendDvbtHierarchy.HIERARCHY_2_NATIVE;
    /** @hide */
    public static final int HIERARCHY_4_NATIVE = Constants.FrontendDvbtHierarchy.HIERARCHY_4_NATIVE;
    /** @hide */
    public static final int HIERARCHY_NON_INDEPTH =
            Constants.FrontendDvbtHierarchy.HIERARCHY_NON_INDEPTH;
    /** @hide */
    public static final int HIERARCHY_1_INDEPTH =
            Constants.FrontendDvbtHierarchy.HIERARCHY_1_INDEPTH;
    /** @hide */
    public static final int HIERARCHY_2_INDEPTH =
            Constants.FrontendDvbtHierarchy.HIERARCHY_2_INDEPTH;
    /** @hide */
    public static final int HIERARCHY_4_INDEPTH =
            Constants.FrontendDvbtHierarchy.HIERARCHY_4_INDEPTH;

    /** @hide */
    @IntDef({FRONTEND_ANALOG_TYPE_UNDEFINED, FRONTEND_ANALOG_TYPE_PAL, FRONTEND_ANALOG_TYPE_SECAM,
            FRONTEND_ANALOG_TYPE_NTSC})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendAnalogType {}
    /** @hide */
    public static final int FRONTEND_ANALOG_TYPE_UNDEFINED = Constants.FrontendAnalogType.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ANALOG_TYPE_PAL = Constants.FrontendAnalogType.PAL;
    /** @hide */
    public static final int FRONTEND_ANALOG_TYPE_SECAM = Constants.FrontendAnalogType.SECAM;
    /** @hide */
    public static final int FRONTEND_ANALOG_TYPE_NTSC = Constants.FrontendAnalogType.NTSC;

    /** @hide */
    @IntDef({FRONTEND_ANALOG_SIF_UNDEFINED, FRONTEND_ANALOG_SIF_BG, FRONTEND_ANALOG_SIF_BG_A2,
            FRONTEND_ANALOG_SIF_BG_NICAM, FRONTEND_ANALOG_SIF_I, FRONTEND_ANALOG_SIF_DK,
            FRONTEND_ANALOG_SIF_DK1, FRONTEND_ANALOG_SIF_DK2, FRONTEND_ANALOG_SIF_DK3,
            FRONTEND_ANALOG_SIF_DK_NICAM, FRONTEND_ANALOG_SIF_L, FRONTEND_ANALOG_SIF_M,
            FRONTEND_ANALOG_SIF_M_BTSC, FRONTEND_ANALOG_SIF_M_A2, FRONTEND_ANALOG_SIF_M_EIA_J,
            FRONTEND_ANALOG_SIF_I_NICAM, FRONTEND_ANALOG_SIF_L_NICAM, FRONTEND_ANALOG_SIF_L_PRIME})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendAnalogSifStandard {}
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_UNDEFINED =
            Constants.FrontendAnalogSifStandard.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_BG = Constants.FrontendAnalogSifStandard.BG;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_BG_A2 = Constants.FrontendAnalogSifStandard.BG_A2;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_BG_NICAM =
            Constants.FrontendAnalogSifStandard.BG_NICAM;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_I = Constants.FrontendAnalogSifStandard.I;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_DK = Constants.FrontendAnalogSifStandard.DK;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_DK1 = Constants.FrontendAnalogSifStandard.DK1;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_DK2 = Constants.FrontendAnalogSifStandard.DK2;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_DK3 = Constants.FrontendAnalogSifStandard.DK3;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_DK_NICAM =
            Constants.FrontendAnalogSifStandard.DK_NICAM;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_L = Constants.FrontendAnalogSifStandard.L;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_M = Constants.FrontendAnalogSifStandard.M;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_M_BTSC = Constants.FrontendAnalogSifStandard.M_BTSC;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_M_A2 = Constants.FrontendAnalogSifStandard.M_A2;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_M_EIA_J =
            Constants.FrontendAnalogSifStandard.M_EIA_J;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_I_NICAM =
            Constants.FrontendAnalogSifStandard.I_NICAM;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_L_NICAM =
            Constants.FrontendAnalogSifStandard.L_NICAM;
    /** @hide */
    public static final int FRONTEND_ANALOG_SIF_L_PRIME =
            Constants.FrontendAnalogSifStandard.L_PRIME;

    /** @hide */
    @IntDef({FRONTEND_ATSC_MODULATION_UNDEFINED, FRONTEND_ATSC_MODULATION_AUTO,
            FRONTEND_ATSC_MODULATION_MOD_8VSB, FRONTEND_ATSC_MODULATION_MOD_16VSB})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendAtscModulation {}
    /** @hide */
    public static final int FRONTEND_ATSC_MODULATION_UNDEFINED =
            Constants.FrontendAtscModulation.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ATSC_MODULATION_AUTO = Constants.FrontendAtscModulation.AUTO;
    /** @hide */
    public static final int FRONTEND_ATSC_MODULATION_MOD_8VSB =
            Constants.FrontendAtscModulation.MOD_8VSB;
    /** @hide */
    public static final int FRONTEND_ATSC_MODULATION_MOD_16VSB =
            Constants.FrontendAtscModulation.MOD_16VSB;

    /** @hide */
    @IntDef({FRONTEND_ATSC3_BANDWIDTH_UNDEFINED, FRONTEND_ATSC3_BANDWIDTH_AUTO,
            FRONTEND_ATSC3_BANDWIDTH_BANDWIDTH_6MHZ, FRONTEND_ATSC3_BANDWIDTH_BANDWIDTH_7MHZ,
            FRONTEND_ATSC3_BANDWIDTH_BANDWIDTH_8MHZ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendAtsc3Bandwidth {}
    /** @hide */
    public static final int FRONTEND_ATSC3_BANDWIDTH_UNDEFINED =
            Constants.FrontendAtsc3Bandwidth.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ATSC3_BANDWIDTH_AUTO = Constants.FrontendAtsc3Bandwidth.AUTO;
    /** @hide */
    public static final int FRONTEND_ATSC3_BANDWIDTH_BANDWIDTH_6MHZ =
            Constants.FrontendAtsc3Bandwidth.BANDWIDTH_6MHZ;
    /** @hide */
    public static final int FRONTEND_ATSC3_BANDWIDTH_BANDWIDTH_7MHZ =
            Constants.FrontendAtsc3Bandwidth.BANDWIDTH_7MHZ;
    /** @hide */
    public static final int FRONTEND_ATSC3_BANDWIDTH_BANDWIDTH_8MHZ =
            Constants.FrontendAtsc3Bandwidth.BANDWIDTH_8MHZ;

    /** @hide */
    @IntDef({FRONTEND_ATSC3_MODULATION_UNDEFINED, FRONTEND_ATSC3_MODULATION_AUTO,
            FRONTEND_ATSC3_MODULATION_MOD_QPSK, FRONTEND_ATSC3_MODULATION_MOD_16QAM,
            FRONTEND_ATSC3_MODULATION_MOD_64QAM, FRONTEND_ATSC3_MODULATION_MOD_256QAM,
            FRONTEND_ATSC3_MODULATION_MOD_1024QAM, FRONTEND_ATSC3_MODULATION_MOD_4096QAM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendAtsc3Modulation {}
    /** @hide */
    public static final int FRONTEND_ATSC3_MODULATION_UNDEFINED =
            Constants.FrontendAtsc3Modulation.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ATSC3_MODULATION_AUTO = Constants.FrontendAtsc3Modulation.AUTO;
    /** @hide */
    public static final int FRONTEND_ATSC3_MODULATION_MOD_QPSK =
            Constants.FrontendAtsc3Modulation.MOD_QPSK;
    /** @hide */
    public static final int FRONTEND_ATSC3_MODULATION_MOD_16QAM =
            Constants.FrontendAtsc3Modulation.MOD_16QAM;
    /** @hide */
    public static final int FRONTEND_ATSC3_MODULATION_MOD_64QAM =
            Constants.FrontendAtsc3Modulation.MOD_64QAM;
    /** @hide */
    public static final int FRONTEND_ATSC3_MODULATION_MOD_256QAM =
            Constants.FrontendAtsc3Modulation.MOD_256QAM;
    /** @hide */
    public static final int FRONTEND_ATSC3_MODULATION_MOD_1024QAM =
            Constants.FrontendAtsc3Modulation.MOD_1024QAM;
    /** @hide */
    public static final int FRONTEND_ATSC3_MODULATION_MOD_4096QAM =
            Constants.FrontendAtsc3Modulation.MOD_4096QAM;

    /** @hide */
    @IntDef({FRONTEND_ATSC3_TIME_INTERLEAVE_MODE_UNDEFINED,
            FRONTEND_ATSC3_TIME_INTERLEAVE_MODE_AUTO, FRONTEND_ATSC3_TIME_INTERLEAVE_MODE_CTI,
            FRONTEND_ATSC3_TIME_INTERLEAVE_MODE_HTI})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendAtsc3TimeInterleaveMode {}
    /** @hide */
    public static final int FRONTEND_ATSC3_TIME_INTERLEAVE_MODE_UNDEFINED =
            Constants.FrontendAtsc3TimeInterleaveMode.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ATSC3_TIME_INTERLEAVE_MODE_AUTO =
            Constants.FrontendAtsc3TimeInterleaveMode.AUTO;
    /** @hide */
    public static final int FRONTEND_ATSC3_TIME_INTERLEAVE_MODE_CTI =
            Constants.FrontendAtsc3TimeInterleaveMode.CTI;
    /** @hide */
    public static final int FRONTEND_ATSC3_TIME_INTERLEAVE_MODE_HTI =
            Constants.FrontendAtsc3TimeInterleaveMode.HTI;

    /** @hide */
    @IntDef({FRONTEND_ATSC3_CODERATE_UNDEFINED, FRONTEND_ATSC3_CODERATE_AUTO,
            FRONTEND_ATSC3_CODERATE_2_15, FRONTEND_ATSC3_CODERATE_3_15,
            FRONTEND_ATSC3_CODERATE_4_15, FRONTEND_ATSC3_CODERATE_5_15,
            FRONTEND_ATSC3_CODERATE_6_15, FRONTEND_ATSC3_CODERATE_7_15,
            FRONTEND_ATSC3_CODERATE_8_15, FRONTEND_ATSC3_CODERATE_9_15,
            FRONTEND_ATSC3_CODERATE_10_15, FRONTEND_ATSC3_CODERATE_11_15,
            FRONTEND_ATSC3_CODERATE_12_15, FRONTEND_ATSC3_CODERATE_13_15})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendAtsc3CodeRate {}
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_UNDEFINED =
            Constants.FrontendAtsc3CodeRate.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_AUTO = Constants.FrontendAtsc3CodeRate.AUTO;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_2_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_2_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_3_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_3_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_4_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_4_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_5_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_5_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_6_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_6_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_7_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_7_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_8_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_8_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_9_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_9_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_10_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_10_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_11_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_11_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_12_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_12_15;
    /** @hide */
    public static final int FRONTEND_ATSC3_CODERATE_13_15 =
            Constants.FrontendAtsc3CodeRate.CODERATE_13_15;

    /** @hide */
    @IntDef({FRONTEND_ATSC3_FEC_UNDEFINED, FRONTEND_ATSC3_FEC_AUTO, FRONTEND_ATSC3_FEC_BCH_LDPC_16K,
            FRONTEND_ATSC3_FEC_BCH_LDPC_64K, FRONTEND_ATSC3_FEC_CRC_LDPC_16K,
            FRONTEND_ATSC3_FEC_CRC_LDPC_64K, FRONTEND_ATSC3_FEC_LDPC_16K,
            FRONTEND_ATSC3_FEC_LDPC_64K})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendAtsc3Fec {}
    /** @hide */
    public static final int FRONTEND_ATSC3_FEC_UNDEFINED = Constants.FrontendAtsc3Fec.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ATSC3_FEC_AUTO = Constants.FrontendAtsc3Fec.AUTO;
    /** @hide */
    public static final int FRONTEND_ATSC3_FEC_BCH_LDPC_16K =
            Constants.FrontendAtsc3Fec.BCH_LDPC_16K;
    /** @hide */
    public static final int FRONTEND_ATSC3_FEC_BCH_LDPC_64K =
            Constants.FrontendAtsc3Fec.BCH_LDPC_64K;
    /** @hide */
    public static final int FRONTEND_ATSC3_FEC_CRC_LDPC_16K =
            Constants.FrontendAtsc3Fec.CRC_LDPC_16K;
    /** @hide */
    public static final int FRONTEND_ATSC3_FEC_CRC_LDPC_64K =
            Constants.FrontendAtsc3Fec.CRC_LDPC_64K;
    /** @hide */
    public static final int FRONTEND_ATSC3_FEC_LDPC_16K = Constants.FrontendAtsc3Fec.LDPC_16K;
    /** @hide */
    public static final int FRONTEND_ATSC3_FEC_LDPC_64K = Constants.FrontendAtsc3Fec.LDPC_64K;

    /** @hide */
    @IntDef({FRONTEND_ATSC3_DEMOD_OUTPUT_FORMAT_UNDEFINED,
            FRONTEND_ATSC3_DEMOD_OUTPUT_FORMAT_ATSC3_LINKLAYER_PACKET,
            FRONTEND_ATSC3_DEMOD_OUTPUT_FORMAT_BASEBAND_PACKET})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendAtsc3DemodOutputFormat {}
    /** @hide */
    public static final int FRONTEND_ATSC3_DEMOD_OUTPUT_FORMAT_UNDEFINED =
            Constants.FrontendAtsc3DemodOutputFormat.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ATSC3_DEMOD_OUTPUT_FORMAT_ATSC3_LINKLAYER_PACKET =
            Constants.FrontendAtsc3DemodOutputFormat.ATSC3_LINKLAYER_PACKET;
    /** @hide */
    public static final int FRONTEND_ATSC3_DEMOD_OUTPUT_FORMAT_BASEBAND_PACKET =
            Constants.FrontendAtsc3DemodOutputFormat.BASEBAND_PACKET;

    /** @hide */
    @IntDef({FRONTEND_DVBS_STANDARD_AUTO, FRONTEND_DVBS_STANDARD_S, FRONTEND_DVBS_STANDARD_S2,
            FRONTEND_DVBS_STANDARD_S2X})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendDvbsStandard {}
    /** @hide */
    public static final int FRONTEND_DVBS_STANDARD_AUTO = Constants.FrontendDvbsStandard.AUTO;
    /** @hide */
    public static final int FRONTEND_DVBS_STANDARD_S = Constants.FrontendDvbsStandard.S;
    /** @hide */
    public static final int FRONTEND_DVBS_STANDARD_S2 = Constants.FrontendDvbsStandard.S2;
    /** @hide */
    public static final int FRONTEND_DVBS_STANDARD_S2X = Constants.FrontendDvbsStandard.S2X;

    /** @hide */
    @IntDef({FRONTEND_DVBC_ANNEX_UNDEFINED, FRONTEND_DVBC_ANNEX_A, FRONTEND_DVBC_ANNEX_B,
            FRONTEND_DVBC_ANNEX_C})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendDvbcAnnex {}
    /** @hide */
    public static final int FRONTEND_DVBC_ANNEX_UNDEFINED = Constants.FrontendDvbcAnnex.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_DVBC_ANNEX_A = Constants.FrontendDvbcAnnex.A;
    /** @hide */
    public static final int FRONTEND_DVBC_ANNEX_B = Constants.FrontendDvbcAnnex.B;
    /** @hide */
    public static final int FRONTEND_DVBC_ANNEX_C = Constants.FrontendDvbcAnnex.C;

    /** @hide */
    @IntDef({FRONTEND_DVBT_TRANSMISSION_MODE_UNDEFINED, FRONTEND_DVBT_TRANSMISSION_MODE_AUTO,
            FRONTEND_DVBT_TRANSMISSION_MODE_2K, FRONTEND_DVBT_TRANSMISSION_MODE_8K,
            FRONTEND_DVBT_TRANSMISSION_MODE_4K, FRONTEND_DVBT_TRANSMISSION_MODE_1K,
            FRONTEND_DVBT_TRANSMISSION_MODE_16K, FRONTEND_DVBT_TRANSMISSION_MODE_32K})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendDvbtTransmissionMode {}
    /** @hide */
    public static final int FRONTEND_DVBT_TRANSMISSION_MODE_UNDEFINED =
            Constants.FrontendDvbtTransmissionMode.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_DVBT_TRANSMISSION_MODE_AUTO =
            Constants.FrontendDvbtTransmissionMode.AUTO;
    /** @hide */
    public static final int FRONTEND_DVBT_TRANSMISSION_MODE_2K =
            Constants.FrontendDvbtTransmissionMode.MODE_2K;
    /** @hide */
    public static final int FRONTEND_DVBT_TRANSMISSION_MODE_8K =
            Constants.FrontendDvbtTransmissionMode.MODE_8K;
    /** @hide */
    public static final int FRONTEND_DVBT_TRANSMISSION_MODE_4K =
            Constants.FrontendDvbtTransmissionMode.MODE_4K;
    /** @hide */
    public static final int FRONTEND_DVBT_TRANSMISSION_MODE_1K =
            Constants.FrontendDvbtTransmissionMode.MODE_1K;
    /** @hide */
    public static final int FRONTEND_DVBT_TRANSMISSION_MODE_16K =
            Constants.FrontendDvbtTransmissionMode.MODE_16K;
    /** @hide */
    public static final int FRONTEND_DVBT_TRANSMISSION_MODE_32K =
            Constants.FrontendDvbtTransmissionMode.MODE_32K;

    /** @hide */
    @IntDef({FRONTEND_DVBT_BANDWIDTH_UNDEFINED, FRONTEND_DVBT_BANDWIDTH_AUTO,
            FRONTEND_DVBT_BANDWIDTH_8MHZ, FRONTEND_DVBT_BANDWIDTH_7MHZ,
            FRONTEND_DVBT_BANDWIDTH_6MHZ, FRONTEND_DVBT_BANDWIDTH_5MHZ,
            FRONTEND_DVBT_BANDWIDTH_1_7MHZ, FRONTEND_DVBT_BANDWIDTH_10MHZ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendDvbtBandwidth {}
    /** @hide */
    public static final int FRONTEND_DVBT_BANDWIDTH_UNDEFINED =
            Constants.FrontendDvbtBandwidth.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_DVBT_BANDWIDTH_AUTO = Constants.FrontendDvbtBandwidth.AUTO;
    /** @hide */
    public static final int FRONTEND_DVBT_BANDWIDTH_8MHZ =
            Constants.FrontendDvbtBandwidth.BANDWIDTH_8MHZ;
    /** @hide */
    public static final int FRONTEND_DVBT_BANDWIDTH_7MHZ =
            Constants.FrontendDvbtBandwidth.BANDWIDTH_7MHZ;
    /** @hide */
    public static final int FRONTEND_DVBT_BANDWIDTH_6MHZ =
            Constants.FrontendDvbtBandwidth.BANDWIDTH_6MHZ;
    /** @hide */
    public static final int FRONTEND_DVBT_BANDWIDTH_5MHZ =
            Constants.FrontendDvbtBandwidth.BANDWIDTH_5MHZ;
    /** @hide */
    public static final int FRONTEND_DVBT_BANDWIDTH_1_7MHZ =
            Constants.FrontendDvbtBandwidth.BANDWIDTH_1_7MHZ;
    /** @hide */
    public static final int FRONTEND_DVBT_BANDWIDTH_10MHZ =
            Constants.FrontendDvbtBandwidth.BANDWIDTH_10MHZ;

    /** @hide */
    @IntDef({FRONTEND_DVBT_CONSTELLATION_UNDEFINED, FRONTEND_DVBT_CONSTELLATION_AUTO,
            FRONTEND_DVBT_CONSTELLATION_CONSTELLATION_QPSK,
            FRONTEND_DVBT_CONSTELLATION_CONSTELLATION_16QAM,
            FRONTEND_DVBT_CONSTELLATION_CONSTELLATION_64QAM,
            FRONTEND_DVBT_CONSTELLATION_CONSTELLATION_256QAM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendDvbtConstellation {}
    /** @hide */
    public static final int FRONTEND_DVBT_CONSTELLATION_UNDEFINED =
            Constants.FrontendDvbtConstellation.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_DVBT_CONSTELLATION_AUTO =
            Constants.FrontendDvbtConstellation.AUTO;
    /** @hide */
    public static final int FRONTEND_DVBT_CONSTELLATION_CONSTELLATION_QPSK =
            Constants.FrontendDvbtConstellation.CONSTELLATION_QPSK;
    /** @hide */
    public static final int FRONTEND_DVBT_CONSTELLATION_CONSTELLATION_16QAM =
            Constants.FrontendDvbtConstellation.CONSTELLATION_16QAM;
    /** @hide */
    public static final int FRONTEND_DVBT_CONSTELLATION_CONSTELLATION_64QAM =
            Constants.FrontendDvbtConstellation.CONSTELLATION_64QAM;
    /** @hide */
    public static final int FRONTEND_DVBT_CONSTELLATION_CONSTELLATION_256QAM =
            Constants.FrontendDvbtConstellation.CONSTELLATION_256QAM;

    /** @hide */
    @IntDef({FRONTEND_DVBT_CODERATE_UNDEFINED, FRONTEND_DVBT_CODERATE_AUTO,
            FRONTEND_DVBT_CODERATE_1_2, FRONTEND_DVBT_CODERATE_2_3, FRONTEND_DVBT_CODERATE_3_4,
            FRONTEND_DVBT_CODERATE_5_6, FRONTEND_DVBT_CODERATE_7_8, FRONTEND_DVBT_CODERATE_3_5,
            FRONTEND_DVBT_CODERATE_4_5, FRONTEND_DVBT_CODERATE_6_7, FRONTEND_DVBT_CODERATE_8_9})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendDvbtCoderate {}
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_UNDEFINED =
            Constants.FrontendDvbtCoderate.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_AUTO = Constants.FrontendDvbtCoderate.AUTO;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_1_2 =
            Constants.FrontendDvbtCoderate.CODERATE_1_2;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_2_3 =
            Constants.FrontendDvbtCoderate.CODERATE_2_3;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_3_4 =
            Constants.FrontendDvbtCoderate.CODERATE_3_4;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_5_6 =
            Constants.FrontendDvbtCoderate.CODERATE_5_6;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_7_8 =
            Constants.FrontendDvbtCoderate.CODERATE_7_8;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_3_5 =
            Constants.FrontendDvbtCoderate.CODERATE_3_5;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_4_5 =
            Constants.FrontendDvbtCoderate.CODERATE_4_5;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_6_7 =
            Constants.FrontendDvbtCoderate.CODERATE_6_7;
    /** @hide */
    public static final int FRONTEND_DVBT_CODERATE_8_9 =
            Constants.FrontendDvbtCoderate.CODERATE_8_9;

    /** @hide */
    @IntDef({FRONTEND_DVBT_GUARD_INTERVAL_UNDEFINED, FRONTEND_DVBT_GUARD_INTERVAL_AUTO,
            FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_32, FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_16,
            FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_8, FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_4,
            FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_128,
            FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_19_128,
            FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_19_256})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendDvbtGuardInterval {}
    /** @hide */
    public static final int FRONTEND_DVBT_GUARD_INTERVAL_UNDEFINED =
            Constants.FrontendDvbtGuardInterval.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_DVBT_GUARD_INTERVAL_AUTO =
            Constants.FrontendDvbtGuardInterval.AUTO;
    /** @hide */
    public static final int FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_32 =
            Constants.FrontendDvbtGuardInterval.INTERVAL_1_32;
    /** @hide */
    public static final int FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_16 =
            Constants.FrontendDvbtGuardInterval.INTERVAL_1_16;
    /** @hide */
    public static final int FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_8 =
            Constants.FrontendDvbtGuardInterval.INTERVAL_1_8;
    /** @hide */
    public static final int FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_4 =
            Constants.FrontendDvbtGuardInterval.INTERVAL_1_4;
    /** @hide */
    public static final int FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_1_128 =
            Constants.FrontendDvbtGuardInterval.INTERVAL_1_128;
    /** @hide */
    public static final int FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_19_128 =
            Constants.FrontendDvbtGuardInterval.INTERVAL_19_128;
    /** @hide */
    public static final int FRONTEND_DVBT_GUARD_INTERVAL_INTERVAL_19_256 =
            Constants.FrontendDvbtGuardInterval.INTERVAL_19_256;

    /** @hide */
    @IntDef({FRONTEND_ISDBS_CODERATE_UNDEFINED, FRONTEND_ISDBS_CODERATE_AUTO,
            FRONTEND_ISDBS_CODERATE_1_2, FRONTEND_ISDBS_CODERATE_2_3, FRONTEND_ISDBS_CODERATE_3_4,
            FRONTEND_ISDBS_CODERATE_5_6, FRONTEND_ISDBS_CODERATE_7_8})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendIsdbsCoderate {}
    /** @hide */
    public static final int FRONTEND_ISDBS_CODERATE_UNDEFINED =
            Constants.FrontendIsdbsCoderate.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ISDBS_CODERATE_AUTO = Constants.FrontendIsdbsCoderate.AUTO;
    /** @hide */
    public static final int FRONTEND_ISDBS_CODERATE_1_2 =
            Constants.FrontendIsdbsCoderate.CODERATE_1_2;
    /** @hide */
    public static final int FRONTEND_ISDBS_CODERATE_2_3 =
            Constants.FrontendIsdbsCoderate.CODERATE_2_3;
    /** @hide */
    public static final int FRONTEND_ISDBS_CODERATE_3_4 =
            Constants.FrontendIsdbsCoderate.CODERATE_3_4;
    /** @hide */
    public static final int FRONTEND_ISDBS_CODERATE_5_6 =
            Constants.FrontendIsdbsCoderate.CODERATE_5_6;
    /** @hide */
    public static final int FRONTEND_ISDBS_CODERATE_7_8 =
            Constants.FrontendIsdbsCoderate.CODERATE_7_8;

    /** @hide */
    @IntDef({FRONTEND_ISDBT_MODE_UNDEFINED, FRONTEND_ISDBT_MODE_AUTO, FRONTEND_ISDBT_MODE_1,
            FRONTEND_ISDBT_MODE_2, FRONTEND_ISDBT_MODE_3})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendIsdbtMode {}
    /** @hide */
    public static final int FRONTEND_ISDBT_MODE_UNDEFINED = Constants.FrontendIsdbtMode.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ISDBT_MODE_AUTO = Constants.FrontendIsdbtMode.AUTO;
    /** @hide */
    public static final int FRONTEND_ISDBT_MODE_1 = Constants.FrontendIsdbtMode.MODE_1;
    /** @hide */
    public static final int FRONTEND_ISDBT_MODE_2 = Constants.FrontendIsdbtMode.MODE_2;
    /** @hide */
    public static final int FRONTEND_ISDBT_MODE_3 = Constants.FrontendIsdbtMode.MODE_3;

    /** @hide */
    @IntDef({FRONTEND_ISDBT_BANDWIDTH_UNDEFINED, FRONTEND_ISDBT_BANDWIDTH_AUTO,
            FRONTEND_ISDBT_BANDWIDTH_8MHZ, FRONTEND_ISDBT_BANDWIDTH_7MHZ,
            FRONTEND_ISDBT_BANDWIDTH_6MHZ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendIsdbtBandwidth {}
    /** @hide */
    public static final int FRONTEND_ISDBT_BANDWIDTH_UNDEFINED =
            Constants.FrontendIsdbtBandwidth.UNDEFINED;
    /** @hide */
    public static final int FRONTEND_ISDBT_BANDWIDTH_AUTO = Constants.FrontendIsdbtBandwidth.AUTO;
    /** @hide */
    public static final int FRONTEND_ISDBT_BANDWIDTH_8MHZ =
            Constants.FrontendIsdbtBandwidth.BANDWIDTH_8MHZ;
    /** @hide */
    public static final int FRONTEND_ISDBT_BANDWIDTH_7MHZ =
            Constants.FrontendIsdbtBandwidth.BANDWIDTH_7MHZ;
    /** @hide */
    public static final int FRONTEND_ISDBT_BANDWIDTH_6MHZ =
            Constants.FrontendIsdbtBandwidth.BANDWIDTH_6MHZ;

    /** @hide */
    @IntDef({FILTER_SETTINGS_TS, FILTER_SETTINGS_MMTP, FILTER_SETTINGS_IP, FILTER_SETTINGS_TLV,
            FILTER_SETTINGS_ALP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FilterSettingsType {}
    /** @hide */
    public static final int FILTER_SETTINGS_TS = Constants.DemuxFilterMainType.TS;
    /** @hide */
    public static final int FILTER_SETTINGS_MMTP = Constants.DemuxFilterMainType.MMTP;
    /** @hide */
    public static final int FILTER_SETTINGS_IP = Constants.DemuxFilterMainType.IP;
    /** @hide */
    public static final int FILTER_SETTINGS_TLV = Constants.DemuxFilterMainType.TLV;
    /** @hide */
    public static final int FILTER_SETTINGS_ALP = Constants.DemuxFilterMainType.ALP;

    /** @hide */
    @IntDef({DVR_SETTINGS_RECORD, DVR_SETTINGS_PLAYBACK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DvrSettingsType {}
    /** @hide */
    public static final int DVR_SETTINGS_RECORD = Constants.DvrType.RECORD;
    /** @hide */
    public static final int DVR_SETTINGS_PLAYBACK = Constants.DvrType.PLAYBACK;


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
