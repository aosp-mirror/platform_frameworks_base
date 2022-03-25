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

package android.media.tv.tuner.frontend;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.media.tv.tuner.Lnb;
import android.media.tv.tuner.TunerVersionChecker;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A Frontend Status class that contains the metrics of the active frontend.
 *
 * @hide
 */
@SystemApi
public class FrontendStatus {

    /** @hide */
    @IntDef({FRONTEND_STATUS_TYPE_DEMOD_LOCK, FRONTEND_STATUS_TYPE_SNR, FRONTEND_STATUS_TYPE_BER,
            FRONTEND_STATUS_TYPE_PER, FRONTEND_STATUS_TYPE_PRE_BER,
            FRONTEND_STATUS_TYPE_SIGNAL_QUALITY, FRONTEND_STATUS_TYPE_SIGNAL_STRENGTH,
            FRONTEND_STATUS_TYPE_SYMBOL_RATE, FRONTEND_STATUS_TYPE_FEC,
            FRONTEND_STATUS_TYPE_MODULATION, FRONTEND_STATUS_TYPE_SPECTRAL,
            FRONTEND_STATUS_TYPE_LNB_VOLTAGE, FRONTEND_STATUS_TYPE_PLP_ID,
            FRONTEND_STATUS_TYPE_EWBS, FRONTEND_STATUS_TYPE_AGC, FRONTEND_STATUS_TYPE_LNA,
            FRONTEND_STATUS_TYPE_LAYER_ERROR, FRONTEND_STATUS_TYPE_MER,
            FRONTEND_STATUS_TYPE_FREQ_OFFSET, FRONTEND_STATUS_TYPE_HIERARCHY,
            FRONTEND_STATUS_TYPE_RF_LOCK, FRONTEND_STATUS_TYPE_ATSC3_PLP_INFO,
            FRONTEND_STATUS_TYPE_BERS, FRONTEND_STATUS_TYPE_CODERATES,
            FRONTEND_STATUS_TYPE_BANDWIDTH, FRONTEND_STATUS_TYPE_GUARD_INTERVAL,
            FRONTEND_STATUS_TYPE_TRANSMISSION_MODE, FRONTEND_STATUS_TYPE_UEC,
            FRONTEND_STATUS_TYPE_T2_SYSTEM_ID, FRONTEND_STATUS_TYPE_INTERLEAVINGS,
            FRONTEND_STATUS_TYPE_ISDBT_SEGMENTS, FRONTEND_STATUS_TYPE_TS_DATA_RATES,
            FRONTEND_STATUS_TYPE_MODULATIONS_EXT, FRONTEND_STATUS_TYPE_ROLL_OFF,
            FRONTEND_STATUS_TYPE_IS_MISO_ENABLED, FRONTEND_STATUS_TYPE_IS_LINEAR,
            FRONTEND_STATUS_TYPE_IS_SHORT_FRAMES_ENABLED, FRONTEND_STATUS_TYPE_ISDBT_MODE,
            FRONTEND_STATUS_TYPE_ISDBT_PARTIAL_RECEPTION_FLAG, FRONTEND_STATUS_TYPE_STREAM_IDS,
            FRONTEND_STATUS_TYPE_DVBT_CELL_IDS, FRONTEND_STATUS_TYPE_ATSC3_ALL_PLP_INFO})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendStatusType {}

    /**
     * Lock status for Demod.
     */
    public static final int FRONTEND_STATUS_TYPE_DEMOD_LOCK =
            android.hardware.tv.tuner.FrontendStatusType.DEMOD_LOCK;
    /**
     * Signal to Noise Ratio.
     */
    public static final int FRONTEND_STATUS_TYPE_SNR =
            android.hardware.tv.tuner.FrontendStatusType.SNR;
    /**
     * Bit Error Ratio.
     */
    public static final int FRONTEND_STATUS_TYPE_BER =
            android.hardware.tv.tuner.FrontendStatusType.BER;
    /**
     * Packages Error Ratio.
     */
    public static final int FRONTEND_STATUS_TYPE_PER =
            android.hardware.tv.tuner.FrontendStatusType.PER;
    /**
     * Bit Error Ratio before FEC.
     */
    public static final int FRONTEND_STATUS_TYPE_PRE_BER =
            android.hardware.tv.tuner.FrontendStatusType.PRE_BER;
    /**
     * Signal Quality (0..100). Good data over total data in percent can be
     * used as a way to present Signal Quality.
     */
    public static final int FRONTEND_STATUS_TYPE_SIGNAL_QUALITY =
            android.hardware.tv.tuner.FrontendStatusType.SIGNAL_QUALITY;
    /**
     * Signal Strength.
     */
    public static final int FRONTEND_STATUS_TYPE_SIGNAL_STRENGTH =
            android.hardware.tv.tuner.FrontendStatusType.SIGNAL_STRENGTH;
    /**
     * Symbol Rate in symbols per second.
     */
    public static final int FRONTEND_STATUS_TYPE_SYMBOL_RATE =
            android.hardware.tv.tuner.FrontendStatusType.SYMBOL_RATE;
    /**
     * Forward Error Correction Type.
     */
    public static final int FRONTEND_STATUS_TYPE_FEC =
            android.hardware.tv.tuner.FrontendStatusType.FEC;
    /**
     * Modulation Type.
     */
    public static final int FRONTEND_STATUS_TYPE_MODULATION =
            android.hardware.tv.tuner.FrontendStatusType.MODULATION;
    /**
     * Spectral Inversion Type.
     */
    public static final int FRONTEND_STATUS_TYPE_SPECTRAL =
            android.hardware.tv.tuner.FrontendStatusType.SPECTRAL;
    /**
     * LNB Voltage.
     */
    public static final int FRONTEND_STATUS_TYPE_LNB_VOLTAGE =
            android.hardware.tv.tuner.FrontendStatusType.LNB_VOLTAGE;
    /**
     * Physical Layer Pipe ID.
     */
    public static final int FRONTEND_STATUS_TYPE_PLP_ID =
            android.hardware.tv.tuner.FrontendStatusType.PLP_ID;
    /**
     * Status for Emergency Warning Broadcasting System.
     */
    public static final int FRONTEND_STATUS_TYPE_EWBS =
            android.hardware.tv.tuner.FrontendStatusType.EWBS;
    /**
     * Automatic Gain Control.
     */
    public static final int FRONTEND_STATUS_TYPE_AGC =
            android.hardware.tv.tuner.FrontendStatusType.AGC;
    /**
     * Low Noise Amplifier.
     */
    public static final int FRONTEND_STATUS_TYPE_LNA =
            android.hardware.tv.tuner.FrontendStatusType.LNA;
    /**
     * Error status by layer.
     */
    public static final int FRONTEND_STATUS_TYPE_LAYER_ERROR =
            android.hardware.tv.tuner.FrontendStatusType.LAYER_ERROR;
    /**
     * Modulation Error Ratio.
     */
    public static final int FRONTEND_STATUS_TYPE_MER =
            android.hardware.tv.tuner.FrontendStatusType.MER;
    /**
     * Difference between tuning frequency and actual locked frequency.
     */
    public static final int FRONTEND_STATUS_TYPE_FREQ_OFFSET =
            android.hardware.tv.tuner.FrontendStatusType.FREQ_OFFSET;
    /**
     * Hierarchy for DVBT.
     */
    public static final int FRONTEND_STATUS_TYPE_HIERARCHY =
            android.hardware.tv.tuner.FrontendStatusType.HIERARCHY;
    /**
     * Lock status for RF.
     */
    public static final int FRONTEND_STATUS_TYPE_RF_LOCK =
            android.hardware.tv.tuner.FrontendStatusType.RF_LOCK;
    /**
     * Current tuned PLP information in a frequency band for ATSC-3.0 frontend.
     */
    public static final int FRONTEND_STATUS_TYPE_ATSC3_PLP_INFO =
            android.hardware.tv.tuner.FrontendStatusType.ATSC3_PLP_INFO;
    /**
     * BERS Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_BERS =
            android.hardware.tv.tuner.FrontendStatusType.BERS;
    /**
     * Coderate Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_CODERATES =
            android.hardware.tv.tuner.FrontendStatusType.CODERATES;
    /**
     * Bandwidth Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_BANDWIDTH =
            android.hardware.tv.tuner.FrontendStatusType.BANDWIDTH;
    /**
     * Guard Interval Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_GUARD_INTERVAL =
            android.hardware.tv.tuner.FrontendStatusType.GUARD_INTERVAL;
    /**
     * Transmission Mode Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_TRANSMISSION_MODE =
            android.hardware.tv.tuner.FrontendStatusType.TRANSMISSION_MODE;
    /**
     * UEC Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_UEC =
            android.hardware.tv.tuner.FrontendStatusType.UEC;
    /**
     * T2 System Id Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_T2_SYSTEM_ID =
            android.hardware.tv.tuner.FrontendStatusType.T2_SYSTEM_ID;
    /**
     * Interleavings Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_INTERLEAVINGS =
            android.hardware.tv.tuner.FrontendStatusType.INTERLEAVINGS;
    /**
     * ISDBT Segments Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_ISDBT_SEGMENTS =
            android.hardware.tv.tuner.FrontendStatusType.ISDBT_SEGMENTS;
    /**
     * TS Data Rates Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_TS_DATA_RATES =
            android.hardware.tv.tuner.FrontendStatusType.TS_DATA_RATES;
    /**
     * Extended Modulations Type. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_MODULATIONS_EXT =
            android.hardware.tv.tuner.FrontendStatusType.MODULATIONS;
    /**
     * Roll Off Type status of the frontend. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_ROLL_OFF =
            android.hardware.tv.tuner.FrontendStatusType.ROLL_OFF;
    /**
     * If the frontend currently supports MISO or not. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_IS_MISO_ENABLED =
            android.hardware.tv.tuner.FrontendStatusType.IS_MISO;
    /**
     * If the frontend code rate is linear or not. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_IS_LINEAR =
            android.hardware.tv.tuner.FrontendStatusType.IS_LINEAR;
    /**
     * If short frames is enabled or not. Only supported in Tuner HAL 1.1 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_IS_SHORT_FRAMES_ENABLED =
            android.hardware.tv.tuner.FrontendStatusType.IS_SHORT_FRAMES;
    /**
     * ISDB-T mode. Only supported in Tuner HAL 2.0 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_ISDBT_MODE =
            android.hardware.tv.tuner.FrontendStatusType.ISDBT_MODE;
    /**
     * ISDB-T partial reception flag. Only supported in Tuner HAL 2.0 or higher.
     */
    public static final int FRONTEND_STATUS_TYPE_ISDBT_PARTIAL_RECEPTION_FLAG =
            android.hardware.tv.tuner.FrontendStatusType.ISDBT_PARTIAL_RECEPTION_FLAG;

    /**
     * Stream IDs included in a transponder.
     */
    public static final int FRONTEND_STATUS_TYPE_STREAM_IDS =
            android.hardware.tv.tuner.FrontendStatusType.STREAM_ID_LIST;

    /**
     * DVB-T Cell IDs.
     */
    public static final int FRONTEND_STATUS_TYPE_DVBT_CELL_IDS =
            android.hardware.tv.tuner.FrontendStatusType.DVBT_CELL_IDS;

    /**
     * All PLP information in a frequency band for ATSC-3.0 frontend, which includes both tuned and
     * not tuned PLPs for currently watching service.
     */
    public static final int FRONTEND_STATUS_TYPE_ATSC3_ALL_PLP_INFO =
            android.hardware.tv.tuner.FrontendStatusType.ATSC3_ALL_PLP_INFO;

    /** @hide */
    @IntDef(value = {
            AtscFrontendSettings.MODULATION_UNDEFINED,
            AtscFrontendSettings.MODULATION_AUTO,
            AtscFrontendSettings.MODULATION_MOD_8VSB,
            AtscFrontendSettings.MODULATION_MOD_16VSB,
            Atsc3FrontendSettings.MODULATION_UNDEFINED,
            Atsc3FrontendSettings.MODULATION_AUTO,
            Atsc3FrontendSettings.MODULATION_MOD_QPSK,
            Atsc3FrontendSettings.MODULATION_MOD_16QAM,
            Atsc3FrontendSettings.MODULATION_MOD_64QAM,
            Atsc3FrontendSettings.MODULATION_MOD_256QAM,
            Atsc3FrontendSettings.MODULATION_MOD_1024QAM,
            Atsc3FrontendSettings.MODULATION_MOD_4096QAM,
            DtmbFrontendSettings.MODULATION_CONSTELLATION_UNDEFINED,
            DtmbFrontendSettings.MODULATION_CONSTELLATION_AUTO,
            DtmbFrontendSettings.MODULATION_CONSTELLATION_4QAM,
            DtmbFrontendSettings.MODULATION_CONSTELLATION_4QAM_NR,
            DtmbFrontendSettings.MODULATION_CONSTELLATION_16QAM,
            DtmbFrontendSettings.MODULATION_CONSTELLATION_32QAM,
            DtmbFrontendSettings.MODULATION_CONSTELLATION_64QAM,
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
            DvbtFrontendSettings.CONSTELLATION_UNDEFINED,
            DvbtFrontendSettings.CONSTELLATION_AUTO,
            DvbtFrontendSettings.CONSTELLATION_QPSK,
            DvbtFrontendSettings.CONSTELLATION_16QAM,
            DvbtFrontendSettings.CONSTELLATION_64QAM,
            DvbtFrontendSettings.CONSTELLATION_256QAM,
            DvbtFrontendSettings.CONSTELLATION_QPSK_R,
            DvbtFrontendSettings.CONSTELLATION_16QAM_R,
            DvbtFrontendSettings.CONSTELLATION_64QAM_R,
            DvbtFrontendSettings.CONSTELLATION_256QAM_R,
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
    @IntDef(value = {
            Atsc3FrontendSettings.TIME_INTERLEAVE_MODE_UNDEFINED,
            Atsc3FrontendSettings.TIME_INTERLEAVE_MODE_AUTO,
            Atsc3FrontendSettings.TIME_INTERLEAVE_MODE_CTI,
            Atsc3FrontendSettings.TIME_INTERLEAVE_MODE_HTI,
            DtmbFrontendSettings.TIME_INTERLEAVE_MODE_UNDEFINED,
            DtmbFrontendSettings.TIME_INTERLEAVE_MODE_AUTO,
            DtmbFrontendSettings.TIME_INTERLEAVE_MODE_TIMER_INT_240,
            DtmbFrontendSettings.TIME_INTERLEAVE_MODE_TIMER_INT_720,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_UNDEFINED,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_AUTO,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_128_1_0,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_128_1_1,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_64_2,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_32_4,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_16_8,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_8_16,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_128_2,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_128_3,
            DvbcFrontendSettings.TIME_INTERLEAVE_MODE_128_4,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_UNDEFINED,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_AUTO,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_1_0,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_1_4,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_1_8,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_1_16,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_2_0,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_2_2,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_2_4,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_2_8,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_3_0,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_3_1,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_3_2,
            IsdbtFrontendSettings.TIME_INTERLEAVE_MODE_3_4})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendInterleaveMode {}

    /** @hide */
    @IntDef(value = {
            Atsc3FrontendSettings.BANDWIDTH_UNDEFINED,
            Atsc3FrontendSettings.BANDWIDTH_AUTO,
            Atsc3FrontendSettings.BANDWIDTH_BANDWIDTH_6MHZ,
            Atsc3FrontendSettings.BANDWIDTH_BANDWIDTH_7MHZ,
            Atsc3FrontendSettings.BANDWIDTH_BANDWIDTH_8MHZ,
            DtmbFrontendSettings.BANDWIDTH_UNDEFINED,
            DtmbFrontendSettings.BANDWIDTH_AUTO,
            DtmbFrontendSettings.BANDWIDTH_6MHZ,
            DtmbFrontendSettings.BANDWIDTH_8MHZ,
            DvbtFrontendSettings.BANDWIDTH_UNDEFINED,
            DvbtFrontendSettings.BANDWIDTH_AUTO,
            DvbtFrontendSettings.BANDWIDTH_8MHZ,
            DvbtFrontendSettings.BANDWIDTH_7MHZ,
            DvbtFrontendSettings.BANDWIDTH_6MHZ,
            DvbtFrontendSettings.BANDWIDTH_5MHZ,
            DvbtFrontendSettings.BANDWIDTH_1_7MHZ,
            DvbtFrontendSettings.BANDWIDTH_10MHZ,
            IsdbtFrontendSettings.BANDWIDTH_UNDEFINED,
            IsdbtFrontendSettings.BANDWIDTH_AUTO,
            IsdbtFrontendSettings.BANDWIDTH_8MHZ,
            IsdbtFrontendSettings.BANDWIDTH_7MHZ,
            IsdbtFrontendSettings.BANDWIDTH_6MHZ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendBandwidth {}

    /** @hide */
    @IntDef(value = {
            DtmbFrontendSettings.TRANSMISSION_MODE_UNDEFINED,
            DtmbFrontendSettings.TRANSMISSION_MODE_AUTO,
            DtmbFrontendSettings.TRANSMISSION_MODE_C1,
            DtmbFrontendSettings.TRANSMISSION_MODE_C3780,
            DvbtFrontendSettings.TRANSMISSION_MODE_UNDEFINED,
            DvbtFrontendSettings.TRANSMISSION_MODE_AUTO,
            DvbtFrontendSettings.TRANSMISSION_MODE_2K,
            DvbtFrontendSettings.TRANSMISSION_MODE_8K,
            DvbtFrontendSettings.TRANSMISSION_MODE_4K,
            DvbtFrontendSettings.TRANSMISSION_MODE_1K,
            DvbtFrontendSettings.TRANSMISSION_MODE_16K,
            DvbtFrontendSettings.TRANSMISSION_MODE_32K,
            IsdbtFrontendSettings.MODE_UNDEFINED,
            IsdbtFrontendSettings.MODE_AUTO,
            IsdbtFrontendSettings.MODE_1,
            IsdbtFrontendSettings.MODE_2,
            IsdbtFrontendSettings.MODE_3})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendTransmissionMode {}

    /** @hide */
    @IntDef(value = {
            DtmbFrontendSettings.GUARD_INTERVAL_UNDEFINED,
            DtmbFrontendSettings.GUARD_INTERVAL_AUTO,
            DtmbFrontendSettings.GUARD_INTERVAL_PN_420_VARIOUS,
            DtmbFrontendSettings.GUARD_INTERVAL_PN_595_CONST,
            DtmbFrontendSettings.GUARD_INTERVAL_PN_945_VARIOUS,
            DtmbFrontendSettings.GUARD_INTERVAL_PN_420_CONST,
            DtmbFrontendSettings.GUARD_INTERVAL_PN_945_CONST,
            DtmbFrontendSettings.GUARD_INTERVAL_PN_RESERVED,
            DvbtFrontendSettings.GUARD_INTERVAL_UNDEFINED,
            DvbtFrontendSettings.GUARD_INTERVAL_AUTO,
            DvbtFrontendSettings.GUARD_INTERVAL_1_32,
            DvbtFrontendSettings.GUARD_INTERVAL_1_16,
            DvbtFrontendSettings.GUARD_INTERVAL_1_8,
            DvbtFrontendSettings.GUARD_INTERVAL_1_4,
            DvbtFrontendSettings.GUARD_INTERVAL_1_128,
            DvbtFrontendSettings.GUARD_INTERVAL_19_128,
            DvbtFrontendSettings.GUARD_INTERVAL_19_256,
            DvbtFrontendSettings.GUARD_INTERVAL_19_128})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendGuardInterval {}

    /** @hide */
    @IntDef(value = {
            DvbsFrontendSettings.ROLLOFF_UNDEFINED,
            DvbsFrontendSettings.ROLLOFF_0_35,
            DvbsFrontendSettings.ROLLOFF_0_25,
            DvbsFrontendSettings.ROLLOFF_0_20,
            DvbsFrontendSettings.ROLLOFF_0_15,
            DvbsFrontendSettings.ROLLOFF_0_10,
            DvbsFrontendSettings.ROLLOFF_0_5,
            Isdbs3FrontendSettings.ROLLOFF_UNDEFINED,
            Isdbs3FrontendSettings.ROLLOFF_0_03,
            IsdbsFrontendSettings.ROLLOFF_UNDEFINED,
            IsdbsFrontendSettings.ROLLOFF_0_35})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendRollOff {}

    private Boolean mIsDemodLocked;
    private Integer mSnr;
    private Integer mBer;
    private Integer mPer;
    private Integer mPerBer;
    private Integer mSignalQuality;
    private Integer mSignalStrength;
    private Integer mSymbolRate;
    private Long mInnerFec;
    private Integer mModulation;
    private Integer mInversion;
    private Integer mLnbVoltage;
    private Integer mPlpId;
    private Boolean mIsEwbs;
    private Integer mAgc;
    private Boolean mIsLnaOn;
    private boolean[] mIsLayerErrors;
    private Integer mMer;
    private Long mFreqOffset;
    private Integer mHierarchy;
    private Boolean mIsRfLocked;
    private Atsc3PlpTuningInfo[] mPlpInfo;
    private int[] mBers;
    private int[] mCodeRates;
    private Integer mBandwidth;
    private Integer mGuardInterval;
    private Integer mTransmissionMode;
    private Integer mUec;
    private Integer mSystemId;
    private int[] mInterleaving;
    private int[] mTsDataRate;
    private int[] mIsdbtSegment;
    private int[] mModulationsExt;
    private Integer mRollOff;
    private Boolean mIsMisoEnabled;
    private Boolean mIsLinear;
    private Boolean mIsShortFrames;
    private Integer mIsdbtMode;
    private Integer mIsdbtPartialReceptionFlag;
    private int[] mStreamIds;
    private int[] mDvbtCellIds;
    private Atsc3PlpInfo[] mAllPlpInfo;

    // Constructed and fields set by JNI code.
    private FrontendStatus() {
    }

    /**
     * Gets if the demod is currently locked or not.
     */
    public boolean isDemodLocked() {
        if (mIsDemodLocked == null) {
            throw new IllegalStateException("DemodLocked status is empty");
        }
        return mIsDemodLocked;
    }
    /**
     * Gets the current Signal to Noise Ratio in thousandths of a deciBel (0.001dB).
     */
    public int getSnr() {
        if (mSnr == null) {
            throw new IllegalStateException("Snr status is empty");
        }
        return mSnr;
    }
    /**
     * Gets the current Bit Error Ratio.
     *
     * <p>The number of error bit per 1 billion bits.
     */
    public int getBer() {
        if (mBer == null) {
            throw new IllegalStateException("Ber status is empty");
        }
        return mBer;
    }

    /**
     * Gets the current Packages Error Ratio.
     *
     * <p>The number of error package per 1 billion packages.
     */
    public int getPer() {
        if (mPer == null) {
            throw new IllegalStateException("Per status is empty");
        }
        return mPer;
    }
    /**
     * Gets the current Bit Error Ratio before Forward Error Correction (FEC).
     *
     * <p>The number of error bit per 1 billion bits before FEC.
     */
    public int getPerBer() {
        if (mPerBer == null) {
            throw new IllegalStateException("PerBer status is empty");
        }
        return mPerBer;
    }
    /**
     * Gets the current Signal Quality in percent.
     */
    public int getSignalQuality() {
        if (mSignalQuality == null) {
            throw new IllegalStateException("SignalQuality status is empty");
        }
        return mSignalQuality;
    }
    /**
     * Gets the current Signal Strength in thousandths of a dBm (0.001dBm).
     */
    public int getSignalStrength() {
        if (mSignalStrength == null) {
            throw new IllegalStateException("SignalStrength status is empty");
        }
        return mSignalStrength;
    }
    /**
     * Gets the current symbol rate in symbols per second.
     */
    public int getSymbolRate() {
        if (mSymbolRate == null) {
            throw new IllegalStateException("SymbolRate status is empty");
        }
        return mSymbolRate;
    }
    /**
     *  Gets the current Inner Forward Error Correction type as specified in ETSI EN 300 468 V1.15.1
     *  and ETSI EN 302 307-2 V1.1.1.
     */
    @FrontendSettings.InnerFec
    public long getInnerFec() {
        if (mInnerFec == null) {
            throw new IllegalStateException("InnerFec status is empty");
        }
        return mInnerFec;
    }
    /**
     * Gets the currently modulation information.
     */
    @FrontendModulation
    public int getModulation() {
        if (mModulation == null) {
            throw new IllegalStateException("Modulation status is empty");
        }
        return mModulation;
    }
    /**
     * Gets the currently Spectral Inversion information for DVBC.
     */
    @FrontendSettings.FrontendSpectralInversion
    public int getSpectralInversion() {
        if (mInversion == null) {
            throw new IllegalStateException("SpectralInversion status is empty");
        }
        return mInversion;
    }
    /**
     * Gets the current Power Voltage Type for LNB.
     */
    @Lnb.Voltage
    public int getLnbVoltage() {
        if (mLnbVoltage == null) {
            throw new IllegalStateException("LnbVoltage status is empty");
        }
        return mLnbVoltage;
    }
    /**
     * Gets the current Physical Layer Pipe ID.
     */
    public int getPlpId() {
        if (mPlpId == null) {
            throw new IllegalStateException("PlpId status is empty");
        }
        return mPlpId;
    }
    /**
     * Checks whether it's Emergency Warning Broadcasting System
     */
    public boolean isEwbs() {
        if (mIsEwbs == null) {
            throw new IllegalStateException("Ewbs status is empty");
        }
        return mIsEwbs;
    }
    /**
     * Gets the current Automatic Gain Control value which is normalized from 0 to 255.
     *
     * Larger AGC values indicate it is applying more gain.
     */
    public int getAgc() {
        if (mAgc == null) {
            throw new IllegalStateException("Agc status is empty");
        }
        return mAgc;
    }
    /**
     * Checks LNA (Low Noise Amplifier) is on or not.
     */
    public boolean isLnaOn() {
        if (mIsLnaOn == null) {
            throw new IllegalStateException("LnaOn status is empty");
        }
        return mIsLnaOn;
    }
    /**
     * Gets the current Error information by layer.
     *
     * The order of the vectors is in ascending order of the required CNR (Contrast-to-noise ratio).
     * The most robust layer is the first. For example, in ISDB-T, vec[0] is the information of
     * layer A. vec[1] is the information of layer B.
     */
    @NonNull
    public boolean[] getLayerErrors() {
        if (mIsLayerErrors == null) {
            throw new IllegalStateException("LayerErrors status is empty");
        }
        return mIsLayerErrors;
    }
    /**
     * Gets the current Modulation Error Ratio in thousandths of a deciBel (0.001dB).
     */
    public int getMer() {
        if (mMer == null) {
            throw new IllegalStateException("Mer status is empty");
        }
        return mMer;
    }
    /**
     * Gets the current frequency difference in Hz.
     *
     * <p>Difference between tuning frequency and actual locked frequency.
     * @deprecated Use {@link #getFreqOffsetLong()}
     */
    @Deprecated
    public int getFreqOffset() {
        return (int) getFreqOffsetLong();
    }
    /**
     * Gets the current frequency difference in Hz.
     *
     * <p>Difference between tuning frequency and actual locked frequency.
     */
    public long getFreqOffsetLong() {
        if (mFreqOffset == null) {
            throw new IllegalStateException("FreqOffset status is empty");
        }
        return mFreqOffset;
    }
    /**
     * Gets the current hierarchy Type for DVBT.
     */
    @DvbtFrontendSettings.Hierarchy
    public int getHierarchy() {
        if (mHierarchy == null) {
            throw new IllegalStateException("Hierarchy status is empty");
        }
        return mHierarchy;
    }
    /**
     * Gets if the RF is locked or not.
     */
    public boolean isRfLocked() {
        if (mIsRfLocked == null) {
            throw new IllegalStateException("isRfLocked status is empty");
        }
        return mIsRfLocked;
    }
    /**
     * Gets an array of the current tuned PLPs information of ATSC3 frontend.
     */
    @NonNull
    public Atsc3PlpTuningInfo[] getAtsc3PlpTuningInfo() {
        if (mPlpInfo == null) {
            throw new IllegalStateException("Atsc3PlpTuningInfo status is empty");
        }
        return mPlpInfo;
    }

    /**
     * Gets an array of the current extended bit error ratio.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * The order of the vectors is in ascending order of the required CNR (Contrast-to-noise ratio).
     * The most robust layer is the first. For example, in ISDB-T, vec[0] is the information of
     * layer A. vec[1] is the information of layer B.
     */
    @NonNull
    public int[] getBers() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getBers status");
        if (mBers == null) {
            throw new IllegalStateException("Bers status is empty");
        }
        return mBers;
    }

    /**
     * Gets an array of the current code rates.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * The order of the vectors is in ascending order of the required CNR (Contrast-to-noise ratio).
     * The most robust layer is the first. For example, in ISDB-T, vec[0] is the information of
     * layer A. vec[1] is the information of layer B.
     */
    @NonNull
    @FrontendSettings.InnerFec
    public int[] getCodeRates() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getCodeRates status");
        if (mCodeRates == null) {
            throw new IllegalStateException("CodeRates status is empty");
        }
        return mCodeRates;
    }

    /**
     * Gets the current bandwidth information.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @FrontendBandwidth
    public int getBandwidth() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getBandwidth status");
        if (mBandwidth == null) {
            throw new IllegalStateException("Bandwidth status is empty");
        }
        return mBandwidth;
    }

    /**
     * Gets the current guard interval information.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @FrontendGuardInterval
    public int getGuardInterval() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getGuardInterval status");
        if (mGuardInterval == null) {
            throw new IllegalStateException("GuardInterval status is empty");
        }
        return mGuardInterval;
    }

    /**
     * Gets the current transmission mode information.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @FrontendTransmissionMode
    public int getTransmissionMode() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getTransmissionMode status");
        if (mTransmissionMode == null) {
            throw new IllegalStateException("TransmissionMode status is empty");
        }
        return mTransmissionMode;
    }

    /**
     * Gets the current Uncorrectable Error Counts of the frontend's Physical Layer Pipe (PLP)
     * since the last tune operation.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    public int getUec() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getUec status");
        if (mUec == null) {
            throw new IllegalStateException("Uec status is empty");
        }
        return mUec;
    }

    /**
     * Gets the current DVB-T2 system id.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @IntRange(from = 0, to = 0xffff)
    public int getSystemId() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getSystemId status");
        if (mSystemId == null) {
            throw new IllegalStateException("SystemId status is empty");
        }
        return mSystemId;
    }

    /**
     * Gets an array of the current interleaving mode information.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * The order of the vectors is in ascending order of the required CNR (Contrast-to-noise ratio).
     * The most robust layer is the first. For example, in ISDB-T, vec[0] is the information of
     * layer A. vec[1] is the information of layer B.
     */
    @NonNull
    @FrontendInterleaveMode
    public int[] getInterleaving() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getInterleaving status");
        if (mInterleaving == null) {
            throw new IllegalStateException("Interleaving status is empty");
        }
        return mInterleaving;
    }

    /**
     * Gets an array of the current segments information in ISDB-T Specification of all the
     * channels.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * The order of the vectors is in ascending order of the required CNR (Contrast-to-noise ratio).
     * The most robust layer is the first. For example, in ISDB-T, vec[0] is the information of
     * layer A. vec[1] is the information of layer B.
     */
    @NonNull
    @IntRange(from = 0, to = 0xff)
    public int[] getIsdbtSegment() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getIsdbtSegment status");
        if (mIsdbtSegment == null) {
            throw new IllegalStateException("IsdbtSegment status is empty");
        }
        return mIsdbtSegment;
    }

    /**
     * Gets an array of the Transport Stream Data Rate in BPS of the current channel.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @NonNull
    public int[] getTsDataRate() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getTsDataRate status");
        if (mTsDataRate == null) {
            throw new IllegalStateException("TsDataRate status is empty");
        }
        return mTsDataRate;
    }

    /**
     * Gets an array of the current extended modulations information.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     *
     * The order of the vectors is in ascending order of the required CNR (Contrast-to-noise ratio).
     * The most robust layer is the first. For example, in ISDB-T, vec[0] is the information of
     * layer A. vec[1] is the information of layer B.
     */
    @NonNull
    @FrontendModulation
    public int[] getExtendedModulations() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getExtendedModulations status");
        if (mModulationsExt == null) {
            throw new IllegalStateException("ExtendedModulations status is empty");
        }
        return mModulationsExt;
    }

    /**
     * Gets the current roll off information.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @FrontendRollOff
    public int getRollOff() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "getRollOff status");
        if (mRollOff == null) {
            throw new IllegalStateException("RollOff status is empty");
        }
        return mRollOff;
    }

    /**
     * Gets is MISO enabled or not.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    public boolean isMisoEnabled() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "isMisoEnabled status");
        if (mIsMisoEnabled == null) {
            throw new IllegalStateException("isMisoEnabled status is empty");
        }
        return mIsMisoEnabled;
    }

    /**
     * Gets is the Code Rate of the frontend is linear or not.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    public boolean isLinear() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "isLinear status");
        if (mIsLinear == null) {
            throw new IllegalStateException("isLinear status is empty");
        }
        return mIsLinear;
    }

    /**
     * Gets is the Short Frames enabled or not.
     *
     * <p>This query is only supported by Tuner HAL 1.1 or higher. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    public boolean isShortFramesEnabled() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_1_1, "isShortFramesEnabled status");
        if (mIsShortFrames == null) {
            throw new IllegalStateException("isShortFramesEnabled status is empty");
        }
        return mIsShortFrames;
    }

    /**
     * Gets ISDB-T mode.
     *
     * <p>This query is only supported by Tuner HAL 2.0 or higher. Unsupported version or if HAL
     * doesn't return ISDB-T mode status will throw IllegalStateException. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @IsdbtFrontendSettings.Mode
    public int getIsdbtMode() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_2_0, "IsdbtMode status");
        if (mIsdbtMode == null) {
            throw new IllegalStateException("IsdbtMode status is empty");
        }
        return mIsdbtMode;
    }

    /**
     * Gets ISDB-T partial reception flag.
     *
     * <p>This query is only supported by Tuner HAL 2.0 or higher. Unsupported version or if HAL
     * doesn't return partial reception flag status will throw IllegalStateException. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @IsdbtFrontendSettings.PartialReceptionFlag
    public int getIsdbtPartialReceptionFlag() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_2_0, "IsdbtPartialReceptionFlag status");
        if (mIsdbtPartialReceptionFlag == null) {
            throw new IllegalStateException("IsdbtPartialReceptionFlag status is empty");
        }
        return mIsdbtPartialReceptionFlag;
    }

    /**
     * Gets stream ids included in a transponder.
     *
     * <p>This query is only supported by Tuner HAL 2.0 or higher. Unsupported version or if HAL
     * doesn't return stream ids will throw IllegalStateException. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @SuppressLint("ArrayReturn")
    @NonNull
    public int[] getStreamIds() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_2_0, "stream ids status");
        if (mStreamIds == null) {
            throw new IllegalStateException("stream ids are empty");
        }
        return mStreamIds;
    }

    /**
     * Gets DVB-T cell ids.
     *
     * <p>This query is only supported by Tuner HAL 2.0 or higher. Unsupported version or if HAL
     * doesn't return cell ids will throw IllegalStateException. Use
     * {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @SuppressLint("ArrayReturn")
    @NonNull
    public int[] getDvbtCellIds() {
        TunerVersionChecker.checkHigherOrEqualVersionTo(
                TunerVersionChecker.TUNER_VERSION_2_0, "dvbt cell ids status");
        if (mDvbtCellIds == null) {
            throw new IllegalStateException("dvbt cell ids are empty");
        }
        return mDvbtCellIds;
    }

    /**
     * Gets a list of all PLPs information of ATSC3 frontend, which includes both tuned and not
     * tuned PLPs for currently watching service.
     *
     * <p>This query is only supported by Tuner HAL 2.0 or higher. Unsupported version will throw
     * UnsupportedOperationException. Use {@link TunerVersionChecker#getTunerVersion()} to check
     * the version.
     *
     * @return a list of all PLPs information. It is empty if HAL doesn't return all PLPs
     *         information status.
     */
    @NonNull
    public List<Atsc3PlpInfo> getAllAtsc3PlpInfo() {
        if (!TunerVersionChecker.checkHigherOrEqualVersionTo(
                    TunerVersionChecker.TUNER_VERSION_2_0, "Atsc3PlpInfo all status")) {
            throw new UnsupportedOperationException("Atsc3PlpInfo all status is empty");
        }
        if (mAllPlpInfo == null) {
            return Collections.EMPTY_LIST;
        }
        return Arrays.asList(mAllPlpInfo);
    }

    /**
     * Information of each tuning Physical Layer Pipes.
     */
    public static class Atsc3PlpTuningInfo {
        private final int mPlpId;
        private final boolean mIsLocked;
        private final int mUec;

        private Atsc3PlpTuningInfo(int plpId, boolean isLocked, int uec) {
            mPlpId = plpId;
            mIsLocked = isLocked;
            mUec = uec;
        }

        /**
         * Gets Physical Layer Pipe ID.
         */
        public int getPlpId() {
            return mPlpId;
        }
        /**
         * Gets Demod Lock/Unlock status of this particular PLP.
         */
        public boolean isLocked() {
            return mIsLocked;
        }
        /**
         * Gets Uncorrectable Error Counts (UEC) of this particular PLP since last tune operation.
         */
        public int getUec() {
            return mUec;
        }
    }
}
