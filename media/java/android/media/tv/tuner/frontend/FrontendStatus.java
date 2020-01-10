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

import android.media.tv.tuner.Lnb;
import android.media.tv.tuner.TunerConstants;
import android.media.tv.tuner.TunerConstants.FrontendDvbcSpectralInversion;
import android.media.tv.tuner.TunerConstants.FrontendDvbtHierarchy;
import android.media.tv.tuner.TunerConstants.FrontendInnerFec;
import android.media.tv.tuner.TunerConstants.FrontendModulation;
import android.media.tv.tuner.TunerConstants.FrontendStatusType;

/**
 * Frontend status
 *
 * @hide
 */
public class FrontendStatus {

    private final int mType;
    private final Object mValue;

    private FrontendStatus(int type, Object value) {
        mType = type;
        mValue = value;
    }

    /** Gets frontend status type. */
    @FrontendStatusType
    public int getStatusType() {
        return mType;
    }
    /** Lock status for Demod in True/False. */
    public boolean getIsDemodLocked() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_DEMOD_LOCK) {
            throw new IllegalStateException();
        }
        return (Boolean) mValue;
    }
    /** SNR value measured by 0.001 dB. */
    public int getSnr() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_SNR) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** The number of error bit per 1 billion bits. */
    public int getBer() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_BER) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** The number of error package per 1 billion packages. */
    public int getPer() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_PER) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** The number of error bit per 1 billion bits before FEC. */
    public int getPerBer() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_PRE_BER) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** Signal Quality in percent. */
    public int getSignalQuality() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_SIGNAL_QUALITY) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** Signal Strength measured by 0.001 dBm. */
    public int getSignalStrength() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_SIGNAL_STRENGTH) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /**  Symbols per second. */
    public int getSymbolRate() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_SYMBOL_RATE) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /**
     *  Inner Forward Error Correction type as specified in ETSI EN 300 468 V1.15.1
     *  and ETSI EN 302 307-2 V1.1.1.
     */
    @FrontendInnerFec
    public long getFec() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_FEC) {
            throw new IllegalStateException();
        }
        return (long) mValue;
    }
    /** Modulation */
    @FrontendModulation
    public int getModulation() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_MODULATION) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** Spectral Inversion for DVBC. */
    @FrontendDvbcSpectralInversion
    public int getSpectralInversion() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_SPECTRAL) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** Power Voltage Type for LNB. */
    @Lnb.Voltage
    public int getLnbVoltage() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_LNB_VOLTAGE) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** PLP ID */
    public byte getPlpId() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_PLP_ID) {
            throw new IllegalStateException();
        }
        return (byte) mValue;
    }
    /** Emergency Warning Broadcasting System */
    public boolean getIsEwbs() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_EWBS) {
            throw new IllegalStateException();
        }
        return (Boolean) mValue;
    }
    /** AGC value is normalized from 0 to 255. */
    public byte getAgc() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_AGC) {
            throw new IllegalStateException();
        }
        return (byte) mValue;
    }
    /** LNA(Low Noise Amplifier) is on or not. */
    public boolean getLnaOn() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_LNA) {
            throw new IllegalStateException();
        }
        return (Boolean) mValue;
    }
    /** Error status by layer. */
    public boolean[] getIsLayerError() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_LAYER_ERROR) {
            throw new IllegalStateException();
        }
        return (boolean[]) mValue;
    }
    /** CN value by VBER measured by 0.001 dB. */
    public int getVberCn() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_VBER_CN) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** CN value by LBER measured by 0.001 dB. */
    public int getLberCn() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_LBER_CN) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** CN value by XER measured by 0.001 dB. */
    public int getXerCn() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_XER_CN) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** MER value measured by 0.001 dB. */
    public int getMer() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_MER) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** Frequency difference in Hertz. */
    public int getFreqOffset() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_FREQ_OFFSET) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** Hierarchy Type for DVBT. */
    @FrontendDvbtHierarchy
    public int getHierarchy() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_HIERARCHY) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }
    /** Lock status for RF. */
    public boolean getIsRfLock() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_RF_LOCK) {
            throw new IllegalStateException();
        }
        return (Boolean) mValue;
    }
    /** A list of PLP status for tuned PLPs for ATSC3 frontend. */
    public Atsc3PlpInfo[] getAtsc3PlpInfo() {
        if (mType != TunerConstants.FRONTEND_STATUS_TYPE_ATSC3_PLP_INFO) {
            throw new IllegalStateException();
        }
        return (Atsc3PlpInfo[]) mValue;
    }

    /** Status for each tuning PLPs. */
    public static class Atsc3PlpInfo {
        private final int mPlpId;
        private final boolean mIsLock;
        private final int mUec;

        private Atsc3PlpInfo(int plpId, boolean isLock, int uec) {
            mPlpId = plpId;
            mIsLock = isLock;
            mUec = uec;
        }

        /** Gets PLP IDs. */
        public int getPlpId() {
            return mPlpId;
        }
        /** Gets Demod Lock/Unlock status of this particular PLP. */
        public boolean getIsLock() {
            return mIsLock;
        }
        /**
         * Gets Uncorrectable Error Counts (UEC) of this particular PLP since last tune
         * operation.
         */
        public int getUec() {
            return mUec;
        }
    }
}
