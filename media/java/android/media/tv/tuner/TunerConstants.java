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
import android.hardware.tv.tuner.V1_0.Constants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
final class TunerConstants {
    public static final int INVALID_TS_PID = Constants.Constant.INVALID_TS_PID;
    public static final int INVALID_STREAM_ID = Constants.Constant.INVALID_STREAM_ID;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FRONTEND_TYPE_UNDEFINED, FRONTEND_TYPE_ANALOG, FRONTEND_TYPE_ATSC, FRONTEND_TYPE_ATSC3,
            FRONTEND_TYPE_DVBC, FRONTEND_TYPE_DVBS, FRONTEND_TYPE_DVBT, FRONTEND_TYPE_ISDBS,
            FRONTEND_TYPE_ISDBS3, FRONTEND_TYPE_ISDBT})
    public @interface FrontendType {}

    public static final int FRONTEND_TYPE_UNDEFINED = Constants.FrontendType.UNDEFINED;
    public static final int FRONTEND_TYPE_ANALOG = Constants.FrontendType.ANALOG;
    public static final int FRONTEND_TYPE_ATSC = Constants.FrontendType.ATSC;
    public static final int FRONTEND_TYPE_ATSC3 = Constants.FrontendType.ATSC3;
    public static final int FRONTEND_TYPE_DVBC = Constants.FrontendType.DVBC;
    public static final int FRONTEND_TYPE_DVBS = Constants.FrontendType.DVBS;
    public static final int FRONTEND_TYPE_DVBT = Constants.FrontendType.DVBT;
    public static final int FRONTEND_TYPE_ISDBS = Constants.FrontendType.ISDBS;
    public static final int FRONTEND_TYPE_ISDBS3 = Constants.FrontendType.ISDBS3;
    public static final int FRONTEND_TYPE_ISDBT = Constants.FrontendType.ISDBT;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FRONTEND_EVENT_TYPE_LOCKED, FRONTEND_EVENT_TYPE_NO_SIGNAL,
            FRONTEND_EVENT_TYPE_LOST_LOCK})
    public @interface FrontendEventType {}

    public static final int FRONTEND_EVENT_TYPE_LOCKED = Constants.FrontendEventType.LOCKED;
    public static final int FRONTEND_EVENT_TYPE_NO_SIGNAL = Constants.FrontendEventType.NO_SIGNAL;
    public static final int FRONTEND_EVENT_TYPE_LOST_LOCK = Constants.FrontendEventType.LOST_LOCK;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DATA_FORMAT_TS, DATA_FORMAT_PES, DATA_FORMAT_ES, DATA_FORMAT_SHV_TLV})
    public @interface DataFormat {}

    public static final int DATA_FORMAT_TS = Constants.DataFormat.TS;
    public static final int DATA_FORMAT_PES = Constants.DataFormat.PES;
    public static final int DATA_FORMAT_ES = Constants.DataFormat.ES;
    public static final int DATA_FORMAT_SHV_TLV = Constants.DataFormat.SHV_TLV;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DEMUX_T_PID, DEMUX_MMPT_PID})
    public @interface DemuxPidType {}

    public static final int DEMUX_T_PID = 1;
    public static final int DEMUX_MMPT_PID = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FRONTEND_SETTINGS_ANALOG, FRONTEND_SETTINGS_ATSC, FRONTEND_SETTINGS_ATSC3,
            FRONTEND_SETTINGS_DVBS, FRONTEND_SETTINGS_DVBC, FRONTEND_SETTINGS_DVBT,
            FRONTEND_SETTINGS_ISDBS, FRONTEND_SETTINGS_ISDBS3, FRONTEND_SETTINGS_ISDBT})
    public @interface FrontendSettingsType {}

    public static final int FRONTEND_SETTINGS_ANALOG = 1;
    public static final int FRONTEND_SETTINGS_ATSC = 2;
    public static final int FRONTEND_SETTINGS_ATSC3 = 3;
    public static final int FRONTEND_SETTINGS_DVBS = 4;
    public static final int FRONTEND_SETTINGS_DVBC = 5;
    public static final int FRONTEND_SETTINGS_DVBT = 6;
    public static final int FRONTEND_SETTINGS_ISDBS = 7;
    public static final int FRONTEND_SETTINGS_ISDBS3 = 8;
    public static final int FRONTEND_SETTINGS_ISDBT = 9;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FILTER_TYPE_TS, FILTER_TYPE_MMTP, FILTER_TYPE_IP, FILTER_TYPE_TLV, FILTER_TYPE_ALP})
    public @interface FilterType {}

    public static final int FILTER_TYPE_TS = Constants.DemuxFilterMainType.TS;
    public static final int FILTER_TYPE_MMTP = Constants.DemuxFilterMainType.MMTP;
    public static final int FILTER_TYPE_IP = Constants.DemuxFilterMainType.IP;
    public static final int FILTER_TYPE_TLV = Constants.DemuxFilterMainType.TLV;
    public static final int FILTER_TYPE_ALP = Constants.DemuxFilterMainType.ALP;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FILTER_SUBTYPE_UNDEFINED, FILTER_SUBTYPE_SECTION, FILTER_SUBTYPE_PES,
            FILTER_SUBTYPE_AUDIO, FILTER_SUBTYPE_VIDEO, FILTER_SUBTYPE_DOWNLOAD,
            FILTER_SUBTYPE_RECORD, FILTER_SUBTYPE_TS, FILTER_SUBTYPE_PCR, FILTER_SUBTYPE_TEMI,
            FILTER_SUBTYPE_MMPT, FILTER_SUBTYPE_NTP, FILTER_SUBTYPE_IP_PAYLOAD, FILTER_SUBTYPE_IP,
            FILTER_SUBTYPE_PAYLOAD_THROUGH, FILTER_SUBTYPE_TLV, FILTER_SUBTYPE_PTP, })
    public @interface FilterSubtype {}

    public static final int FILTER_SUBTYPE_UNDEFINED = 0;
    public static final int FILTER_SUBTYPE_SECTION = 1;
    public static final int FILTER_SUBTYPE_PES = 2;
    public static final int FILTER_SUBTYPE_AUDIO = 3;
    public static final int FILTER_SUBTYPE_VIDEO = 4;
    public static final int FILTER_SUBTYPE_DOWNLOAD = 5;
    public static final int FILTER_SUBTYPE_RECORD = 6;
    public static final int FILTER_SUBTYPE_TS = 7;
    public static final int FILTER_SUBTYPE_PCR = 8;
    public static final int FILTER_SUBTYPE_TEMI = 9;
    public static final int FILTER_SUBTYPE_MMPT = 10;
    public static final int FILTER_SUBTYPE_NTP = 11;
    public static final int FILTER_SUBTYPE_IP_PAYLOAD = 12;
    public static final int FILTER_SUBTYPE_IP = 13;
    public static final int FILTER_SUBTYPE_PAYLOAD_THROUGH = 14;
    public static final int FILTER_SUBTYPE_TLV = 15;
    public static final int FILTER_SUBTYPE_PTP = 16;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FRONTEND_SCAN_UNDEFINED, FRONTEND_SCAN_AUTO, FRONTEND_SCAN_BLIND})
    public @interface FrontendScanType {}

    public static final int FRONTEND_SCAN_UNDEFINED = Constants.FrontendScanType.SCAN_UNDEFINED;
    public static final int FRONTEND_SCAN_AUTO = Constants.FrontendScanType.SCAN_AUTO;
    public static final int FRONTEND_SCAN_BLIND = Constants.FrontendScanType.SCAN_BLIND;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SCAN_MESSAGE_TYPE_LOCKED, SCAN_MESSAGE_TYPE_END, SCAN_MESSAGE_TYPE_PROGRESS_PERCENT,
            SCAN_MESSAGE_TYPE_FREQUENCY, SCAN_MESSAGE_TYPE_SYMBOL_RATE, SCAN_MESSAGE_TYPE_PLP_IDS,
            SCAN_MESSAGE_TYPE_GROUP_IDS, SCAN_MESSAGE_TYPE_INPUT_STREAM_IDS,
            SCAN_MESSAGE_TYPE_STANDARD, SCAN_MESSAGE_TYPE_ATSC3_PLP_INFO})
    public @interface ScanMessageType {}

    public static final int SCAN_MESSAGE_TYPE_LOCKED = Constants.FrontendScanMessageType.LOCKED;
    public static final int SCAN_MESSAGE_TYPE_END = Constants.FrontendScanMessageType.END;
    public static final int SCAN_MESSAGE_TYPE_PROGRESS_PERCENT =
            Constants.FrontendScanMessageType.PROGRESS_PERCENT;
    public static final int SCAN_MESSAGE_TYPE_FREQUENCY =
            Constants.FrontendScanMessageType.FREQUENCY;
    public static final int SCAN_MESSAGE_TYPE_SYMBOL_RATE =
            Constants.FrontendScanMessageType.SYMBOL_RATE;
    public static final int SCAN_MESSAGE_TYPE_PLP_IDS = Constants.FrontendScanMessageType.PLP_IDS;
    public static final int SCAN_MESSAGE_TYPE_GROUP_IDS =
            Constants.FrontendScanMessageType.GROUP_IDS;
    public static final int SCAN_MESSAGE_TYPE_INPUT_STREAM_IDS =
            Constants.FrontendScanMessageType.INPUT_STREAM_IDS;
    public static final int SCAN_MESSAGE_TYPE_STANDARD =
            Constants.FrontendScanMessageType.STANDARD;
    public static final int SCAN_MESSAGE_TYPE_ATSC3_PLP_INFO =
            Constants.FrontendScanMessageType.ATSC3_PLP_INFO;


    @Retention(RetentionPolicy.SOURCE)
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
    public @interface FrontendStatusType {}

    /**
     * Lock status for Demod.
     */
    public static final int FRONTEND_STATUS_TYPE_DEMOD_LOCK =
            Constants.FrontendStatusType.DEMOD_LOCK;
    /**
     * Signal to Noise Ratio.
     */
    public static final int FRONTEND_STATUS_TYPE_SNR = Constants.FrontendStatusType.SNR;
    /**
     * Bit Error Ratio.
     */
    public static final int FRONTEND_STATUS_TYPE_BER = Constants.FrontendStatusType.BER;
    /**
     * Packages Error Ratio.
     */
    public static final int FRONTEND_STATUS_TYPE_PER = Constants.FrontendStatusType.PER;
    /**
     * Bit Error Ratio before FEC.
     */
    public static final int FRONTEND_STATUS_TYPE_PRE_BER = Constants.FrontendStatusType.PRE_BER;
    /**
     * Signal Quality (0..100). Good data over total data in percent can be
     * used as a way to present Signal Quality.
     */
    public static final int FRONTEND_STATUS_TYPE_SIGNAL_QUALITY =
            Constants.FrontendStatusType.SIGNAL_QUALITY;
    /**
     * Signal Strength.
     */
    public static final int FRONTEND_STATUS_TYPE_SIGNAL_STRENGTH =
            Constants.FrontendStatusType.SIGNAL_STRENGTH;
    /**
     * Symbol Rate.
     */
    public static final int FRONTEND_STATUS_TYPE_SYMBOL_RATE =
            Constants.FrontendStatusType.SYMBOL_RATE;
    /**
     * Forward Error Correction Type.
     */
    public static final int FRONTEND_STATUS_TYPE_FEC = Constants.FrontendStatusType.FEC;
    /**
     * Modulation Type.
     */
    public static final int FRONTEND_STATUS_TYPE_MODULATION =
            Constants.FrontendStatusType.MODULATION;
    /**
     * Spectral Inversion Type.
     */
    public static final int FRONTEND_STATUS_TYPE_SPECTRAL = Constants.FrontendStatusType.SPECTRAL;
    /**
     * LNB Voltage.
     */
    public static final int FRONTEND_STATUS_TYPE_LNB_VOLTAGE =
            Constants.FrontendStatusType.LNB_VOLTAGE;
    /**
     * Physical Layer Pipe ID.
     */
    public static final int FRONTEND_STATUS_TYPE_PLP_ID = Constants.FrontendStatusType.PLP_ID;
    /**
     * Status for Emergency Warning Broadcasting System.
     */
    public static final int FRONTEND_STATUS_TYPE_EWBS = Constants.FrontendStatusType.EWBS;
    /**
     * Automatic Gain Control.
     */
    public static final int FRONTEND_STATUS_TYPE_AGC = Constants.FrontendStatusType.AGC;
    /**
     * Low Noise Amplifier.
     */
    public static final int FRONTEND_STATUS_TYPE_LNA = Constants.FrontendStatusType.LNA;
    /**
     * Error status by layer.
     */
    public static final int FRONTEND_STATUS_TYPE_LAYER_ERROR =
            Constants.FrontendStatusType.LAYER_ERROR;
    /**
     * CN value by VBER.
     */
    public static final int FRONTEND_STATUS_TYPE_VBER_CN = Constants.FrontendStatusType.VBER_CN;
    /**
     * CN value by LBER.
     */
    public static final int FRONTEND_STATUS_TYPE_LBER_CN = Constants.FrontendStatusType.LBER_CN;
    /**
     * CN value by XER.
     */
    public static final int FRONTEND_STATUS_TYPE_XER_CN = Constants.FrontendStatusType.XER_CN;
    /**
     * Moduration Error Ratio.
     */
    public static final int FRONTEND_STATUS_TYPE_MER = Constants.FrontendStatusType.MER;
    /**
     * Difference between tuning frequency and actual locked frequency.
     */
    public static final int FRONTEND_STATUS_TYPE_FREQ_OFFSET =
            Constants.FrontendStatusType.FREQ_OFFSET;
    /**
     * Hierarchy for DVBT.
     */
    public static final int FRONTEND_STATUS_TYPE_HIERARCHY = Constants.FrontendStatusType.HIERARCHY;
    /**
     * Lock status for RF.
     */
    public static final int FRONTEND_STATUS_TYPE_RF_LOCK = Constants.FrontendStatusType.RF_LOCK;
    /**
     * PLP information in a frequency band for ATSC3.0 frontend.
     */
    public static final int FRONTEND_STATUS_TYPE_ATSC3_PLP_INFO =
            Constants.FrontendStatusType.ATSC3_PLP_INFO;


    @Retention(RetentionPolicy.SOURCE)
    @LongDef({FEC_UNDEFINED, FEC_AUTO, FEC_1_2, FEC_1_3, FEC_1_4, FEC_1_5, FEC_2_3, FEC_2_5,
            FEC_2_9, FEC_3_4, FEC_3_5, FEC_4_5, FEC_4_15, FEC_5_6, FEC_5_9, FEC_6_7, FEC_7_8,
            FEC_7_9, FEC_7_15, FEC_8_9, FEC_8_15, FEC_9_10, FEC_9_20, FEC_11_15, FEC_11_20,
            FEC_11_45, FEC_13_18, FEC_13_45, FEC_14_45, FEC_23_36, FEC_25_36, FEC_26_45, FEC_28_45,
            FEC_29_45, FEC_31_45, FEC_32_45, FEC_77_90})
    public @interface FrontendInnerFec {}

    /**
     * FEC not defined
     */
    public static final long FEC_UNDEFINED = Constants.FrontendInnerFec.FEC_UNDEFINED;
    /**
     * hardware is able to detect and set FEC automatically
     */
    public static final long FEC_AUTO = Constants.FrontendInnerFec.AUTO;
    /**
     * 1/2 conv. code rate
     */
    public static final long FEC_1_2 = Constants.FrontendInnerFec.FEC_1_2;
    /**
     * 1/3 conv. code rate
     */
    public static final long FEC_1_3 = Constants.FrontendInnerFec.FEC_1_3;
    /**
     * 1/4 conv. code rate
     */
    public static final long FEC_1_4 = Constants.FrontendInnerFec.FEC_1_4;
    /**
     * 1/5 conv. code rate
     */
    public static final long FEC_1_5 = Constants.FrontendInnerFec.FEC_1_5;
    /**
     * 2/3 conv. code rate
     */
    public static final long FEC_2_3 = Constants.FrontendInnerFec.FEC_2_3;
    /**
     * 2/5 conv. code rate
     */
    public static final long FEC_2_5 = Constants.FrontendInnerFec.FEC_2_5;
    /**
     * 2/9 conv. code rate
     */
    public static final long FEC_2_9 = Constants.FrontendInnerFec.FEC_2_9;
    /**
     * 3/4 conv. code rate
     */
    public static final long FEC_3_4 = Constants.FrontendInnerFec.FEC_3_4;
    /**
     * 3/5 conv. code rate
     */
    public static final long FEC_3_5 = Constants.FrontendInnerFec.FEC_3_5;
    /**
     * 4/5 conv. code rate
     */
    public static final long FEC_4_5 = Constants.FrontendInnerFec.FEC_4_5;
    /**
     * 4/15 conv. code rate
     */
    public static final long FEC_4_15 = Constants.FrontendInnerFec.FEC_4_15;
    /**
     * 5/6 conv. code rate
     */
    public static final long FEC_5_6 = Constants.FrontendInnerFec.FEC_5_6;
    /**
     * 5/9 conv. code rate
     */
    public static final long FEC_5_9 = Constants.FrontendInnerFec.FEC_5_9;
    /**
     * 6/7 conv. code rate
     */
    public static final long FEC_6_7 = Constants.FrontendInnerFec.FEC_6_7;
    /**
     * 7/8 conv. code rate
     */
    public static final long FEC_7_8 = Constants.FrontendInnerFec.FEC_7_8;
    /**
     * 7/9 conv. code rate
     */
    public static final long FEC_7_9 = Constants.FrontendInnerFec.FEC_7_9;
    /**
     * 7/15 conv. code rate
     */
    public static final long FEC_7_15 = Constants.FrontendInnerFec.FEC_7_15;
    /**
     * 8/9 conv. code rate
     */
    public static final long FEC_8_9 = Constants.FrontendInnerFec.FEC_8_9;
    /**
     * 8/15 conv. code rate
     */
    public static final long FEC_8_15 = Constants.FrontendInnerFec.FEC_8_15;
    /**
     * 9/10 conv. code rate
     */
    public static final long FEC_9_10 = Constants.FrontendInnerFec.FEC_9_10;
    /**
     * 9/20 conv. code rate
     */
    public static final long FEC_9_20 = Constants.FrontendInnerFec.FEC_9_20;
    /**
     * 11/15 conv. code rate
     */
    public static final long FEC_11_15 = Constants.FrontendInnerFec.FEC_11_15;
    /**
     * 11/20 conv. code rate
     */
    public static final long FEC_11_20 = Constants.FrontendInnerFec.FEC_11_20;
    /**
     * 11/45 conv. code rate
     */
    public static final long FEC_11_45 = Constants.FrontendInnerFec.FEC_11_45;
    /**
     * 13/18 conv. code rate
     */
    public static final long FEC_13_18 = Constants.FrontendInnerFec.FEC_13_18;
    /**
     * 13/45 conv. code rate
     */
    public static final long FEC_13_45 = Constants.FrontendInnerFec.FEC_13_45;
    /**
     * 14/45 conv. code rate
     */
    public static final long FEC_14_45 = Constants.FrontendInnerFec.FEC_14_45;
    /**
     * 23/36 conv. code rate
     */
    public static final long FEC_23_36 = Constants.FrontendInnerFec.FEC_23_36;
    /**
     * 25/36 conv. code rate
     */
    public static final long FEC_25_36 = Constants.FrontendInnerFec.FEC_25_36;
    /**
     * 26/45 conv. code rate
     */
    public static final long FEC_26_45 = Constants.FrontendInnerFec.FEC_26_45;
    /**
     * 28/45 conv. code rate
     */
    public static final long FEC_28_45 = Constants.FrontendInnerFec.FEC_28_45;
    /**
     * 29/45 conv. code rate
     */
    public static final long FEC_29_45 = Constants.FrontendInnerFec.FEC_29_45;
    /**
     * 31/45 conv. code rate
     */
    public static final long FEC_31_45 = Constants.FrontendInnerFec.FEC_31_45;
    /**
     * 32/45 conv. code rate
     */
    public static final long FEC_32_45 = Constants.FrontendInnerFec.FEC_32_45;
    /**
     * 77/90 conv. code rate
     */
    public static final long FEC_77_90 = Constants.FrontendInnerFec.FEC_77_90;


    @Retention(RetentionPolicy.SOURCE)
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
    public @interface FrontendModulation {}

    public static final int DVBC_MODULATION_UNDEFINED = Constants.FrontendDvbcModulation.UNDEFINED;
    public static final int DVBC_MODULATION_AUTO = Constants.FrontendDvbcModulation.AUTO;
    public static final int DVBC_MODULATION_MOD_16QAM = Constants.FrontendDvbcModulation.MOD_16QAM;
    public static final int DVBC_MODULATION_MOD_32QAM = Constants.FrontendDvbcModulation.MOD_32QAM;
    public static final int DVBC_MODULATION_MOD_64QAM = Constants.FrontendDvbcModulation.MOD_64QAM;
    public static final int DVBC_MODULATION_MOD_128QAM =
            Constants.FrontendDvbcModulation.MOD_128QAM;
    public static final int DVBC_MODULATION_MOD_256QAM =
            Constants.FrontendDvbcModulation.MOD_256QAM;
    public static final int DVBS_MODULATION_UNDEFINED = Constants.FrontendDvbsModulation.UNDEFINED;
    public static final int DVBS_MODULATION_AUTO = Constants.FrontendDvbsModulation.AUTO;
    public static final int DVBS_MODULATION_MOD_QPSK = Constants.FrontendDvbsModulation.MOD_QPSK;
    public static final int DVBS_MODULATION_MOD_8PSK = Constants.FrontendDvbsModulation.MOD_8PSK;
    public static final int DVBS_MODULATION_MOD_16QAM = Constants.FrontendDvbsModulation.MOD_16QAM;
    public static final int DVBS_MODULATION_MOD_16PSK = Constants.FrontendDvbsModulation.MOD_16PSK;
    public static final int DVBS_MODULATION_MOD_32PSK = Constants.FrontendDvbsModulation.MOD_32PSK;
    public static final int DVBS_MODULATION_MOD_ACM = Constants.FrontendDvbsModulation.MOD_ACM;
    public static final int DVBS_MODULATION_MOD_8APSK = Constants.FrontendDvbsModulation.MOD_8APSK;
    public static final int DVBS_MODULATION_MOD_16APSK =
            Constants.FrontendDvbsModulation.MOD_16APSK;
    public static final int DVBS_MODULATION_MOD_32APSK =
            Constants.FrontendDvbsModulation.MOD_32APSK;
    public static final int DVBS_MODULATION_MOD_64APSK =
            Constants.FrontendDvbsModulation.MOD_64APSK;
    public static final int DVBS_MODULATION_MOD_128APSK =
            Constants.FrontendDvbsModulation.MOD_128APSK;
    public static final int DVBS_MODULATION_MOD_256APSK =
            Constants.FrontendDvbsModulation.MOD_256APSK;
    public static final int DVBS_MODULATION_MOD_RESERVED =
            Constants.FrontendDvbsModulation.MOD_RESERVED;
    public static final int ISDBS_MODULATION_UNDEFINED =
            Constants.FrontendIsdbsModulation.UNDEFINED;
    public static final int ISDBS_MODULATION_AUTO = Constants.FrontendIsdbsModulation.AUTO;
    public static final int ISDBS_MODULATION_MOD_BPSK = Constants.FrontendIsdbsModulation.MOD_BPSK;
    public static final int ISDBS_MODULATION_MOD_QPSK = Constants.FrontendIsdbsModulation.MOD_QPSK;
    public static final int ISDBS_MODULATION_MOD_TC8PSK =
            Constants.FrontendIsdbsModulation.MOD_TC8PSK;
    public static final int ISDBS3_MODULATION_UNDEFINED =
            Constants.FrontendIsdbs3Modulation.UNDEFINED;
    public static final int ISDBS3_MODULATION_AUTO = Constants.FrontendIsdbs3Modulation.AUTO;
    public static final int ISDBS3_MODULATION_MOD_BPSK =
            Constants.FrontendIsdbs3Modulation.MOD_BPSK;
    public static final int ISDBS3_MODULATION_MOD_QPSK =
            Constants.FrontendIsdbs3Modulation.MOD_QPSK;
    public static final int ISDBS3_MODULATION_MOD_8PSK =
            Constants.FrontendIsdbs3Modulation.MOD_8PSK;
    public static final int ISDBS3_MODULATION_MOD_16APSK =
            Constants.FrontendIsdbs3Modulation.MOD_16APSK;
    public static final int ISDBS3_MODULATION_MOD_32APSK =
            Constants.FrontendIsdbs3Modulation.MOD_32APSK;
    public static final int ISDBT_MODULATION_UNDEFINED =
            Constants.FrontendIsdbtModulation.UNDEFINED;
    public static final int ISDBT_MODULATION_AUTO = Constants.FrontendIsdbtModulation.AUTO;
    public static final int ISDBT_MODULATION_MOD_DQPSK =
            Constants.FrontendIsdbtModulation.MOD_DQPSK;
    public static final int ISDBT_MODULATION_MOD_QPSK = Constants.FrontendIsdbtModulation.MOD_QPSK;
    public static final int ISDBT_MODULATION_MOD_16QAM =
            Constants.FrontendIsdbtModulation.MOD_16QAM;
    public static final int ISDBT_MODULATION_MOD_64QAM =
            Constants.FrontendIsdbtModulation.MOD_64QAM;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SPECTRAL_INVERSION_UNDEFINED, SPECTRAL_INVERSION_NORMAL, SPECTRAL_INVERSION_INVERTED})
    public @interface FrontendDvbcSpectralInversion {}

    public static final int SPECTRAL_INVERSION_UNDEFINED =
            Constants.FrontendDvbcSpectralInversion.UNDEFINED;
    public static final int SPECTRAL_INVERSION_NORMAL =
            Constants.FrontendDvbcSpectralInversion.NORMAL;
    public static final int SPECTRAL_INVERSION_INVERTED =
            Constants.FrontendDvbcSpectralInversion.INVERTED;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({LNB_VOLTAGE_NONE, LNB_VOLTAGE_VOLTAGE_5V, LNB_VOLTAGE_VOLTAGE_11V,
            LNB_VOLTAGE_VOLTAGE_12V, LNB_VOLTAGE_VOLTAGE_13V, LNB_VOLTAGE_VOLTAGE_14V,
            LNB_VOLTAGE_VOLTAGE_15V, LNB_VOLTAGE_VOLTAGE_18V})
    public @interface LnbVoltage {}

    public static final int LNB_VOLTAGE_NONE = Constants.LnbVoltage.NONE;
    public static final int LNB_VOLTAGE_VOLTAGE_5V = Constants.LnbVoltage.VOLTAGE_5V;
    public static final int LNB_VOLTAGE_VOLTAGE_11V = Constants.LnbVoltage.VOLTAGE_11V;
    public static final int LNB_VOLTAGE_VOLTAGE_12V = Constants.LnbVoltage.VOLTAGE_12V;
    public static final int LNB_VOLTAGE_VOLTAGE_13V = Constants.LnbVoltage.VOLTAGE_13V;
    public static final int LNB_VOLTAGE_VOLTAGE_14V = Constants.LnbVoltage.VOLTAGE_14V;
    public static final int LNB_VOLTAGE_VOLTAGE_15V = Constants.LnbVoltage.VOLTAGE_15V;
    public static final int LNB_VOLTAGE_VOLTAGE_18V = Constants.LnbVoltage.VOLTAGE_18V;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HIERARCHY_UNDEFINED, HIERARCHY_AUTO, HIERARCHY_NON_NATIVE, HIERARCHY_1_NATIVE,
            HIERARCHY_2_NATIVE, HIERARCHY_4_NATIVE, HIERARCHY_NON_INDEPTH, HIERARCHY_1_INDEPTH,
            HIERARCHY_2_INDEPTH, HIERARCHY_4_INDEPTH})
    public @interface FrontendDvbtHierarchy {}

    public static final int HIERARCHY_UNDEFINED = Constants.FrontendDvbtHierarchy.UNDEFINED;
    public static final int HIERARCHY_AUTO = Constants.FrontendDvbtHierarchy.AUTO;
    public static final int HIERARCHY_NON_NATIVE =
            Constants.FrontendDvbtHierarchy.HIERARCHY_NON_NATIVE;
    public static final int HIERARCHY_1_NATIVE = Constants.FrontendDvbtHierarchy.HIERARCHY_1_NATIVE;
    public static final int HIERARCHY_2_NATIVE = Constants.FrontendDvbtHierarchy.HIERARCHY_2_NATIVE;
    public static final int HIERARCHY_4_NATIVE = Constants.FrontendDvbtHierarchy.HIERARCHY_4_NATIVE;
    public static final int HIERARCHY_NON_INDEPTH =
            Constants.FrontendDvbtHierarchy.HIERARCHY_NON_INDEPTH;
    public static final int HIERARCHY_1_INDEPTH =
            Constants.FrontendDvbtHierarchy.HIERARCHY_1_INDEPTH;
    public static final int HIERARCHY_2_INDEPTH =
            Constants.FrontendDvbtHierarchy.HIERARCHY_2_INDEPTH;
    public static final int HIERARCHY_4_INDEPTH =
            Constants.FrontendDvbtHierarchy.HIERARCHY_4_INDEPTH;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FILTER_SETTINGS_TS, FILTER_SETTINGS_MMTP, FILTER_SETTINGS_IP, FILTER_SETTINGS_TLV,
            FILTER_SETTINGS_ALP})
    public @interface FilterSettingsType {}

    public static final int FILTER_SETTINGS_TS = Constants.DemuxFilterMainType.TS;
    public static final int FILTER_SETTINGS_MMTP = Constants.DemuxFilterMainType.MMTP;
    public static final int FILTER_SETTINGS_IP = Constants.DemuxFilterMainType.IP;
    public static final int FILTER_SETTINGS_TLV = Constants.DemuxFilterMainType.TLV;
    public static final int FILTER_SETTINGS_ALP = Constants.DemuxFilterMainType.ALP;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DVR_SETTINGS_RECORD, DVR_SETTINGS_PLAYBACK})
    public @interface DvrSettingsType {}

    public static final int DVR_SETTINGS_RECORD = Constants.DvrType.RECORD;
    public static final int DVR_SETTINGS_PLAYBACK = Constants.DvrType.PLAYBACK;

    private TunerConstants() {
    }
}
