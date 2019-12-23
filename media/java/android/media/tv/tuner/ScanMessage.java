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

import android.media.tv.tuner.TunerConstants.ScanMessageType;

/**
 * Message from frontend during scan operations.
 *
 * @hide
 */
public class ScanMessage {
    private final int mType;
    private final Object mValue;

    private ScanMessage(int type, Object value) {
        mType = type;
        mValue = value;
    }

    /** Gets scan message type. */
    @ScanMessageType
    public int getMessageType() {
        return mType;
    }
    /** Message indicates whether frontend is locked or not. */
    public boolean getIsLocked() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_LOCKED) {
            throw new IllegalStateException();
        }
        return (Boolean) mValue;
    }
    /** Message indicates whether the scan has reached the end or not. */
    public boolean getIsEnd() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_END) {
            throw new IllegalStateException();
        }
        return (Boolean) mValue;
    }
    /** Progress message in percent. */
    public int getProgressPercent() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_PROGRESS_PERCENT) {
            throw new IllegalStateException();
        }
        return (Integer) mValue;
    }
    /** Gets frequency. */
    public int getFrequency() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_FREQUENCY) {
            throw new IllegalStateException();
        }
        return (Integer) mValue;
    }
    /** Gets symbol rate. */
    public int getSymbolRate() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_SYMBOL_RATE) {
            throw new IllegalStateException();
        }
        return (Integer) mValue;
    }
    /** Gets PLP IDs. */
    public int[] getPlpIds() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_PLP_IDS) {
            throw new IllegalStateException();
        }
        return (int[]) mValue;
    }
    /** Gets group IDs. */
    public int[] getGroupIds() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_GROUP_IDS) {
            throw new IllegalStateException();
        }
        return (int[]) mValue;
    }
    /** Gets Input stream IDs. */
    public int[] getInputStreamIds() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_INPUT_STREAM_IDS) {
            throw new IllegalStateException();
        }
        return (int[]) mValue;
    }
    /** Gets the DVB-T or DVB-S standard. */
    public int getStandard() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_STANDARD) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }

    /** Gets PLP information for ATSC3. */
    public Atsc3PlpInfo[] getAtsc3PlpInfos() {
        if (mType != TunerConstants.SCAN_MESSAGE_TYPE_ATSC3_PLP_INFO) {
            throw new IllegalStateException();
        }
        return (Atsc3PlpInfo[]) mValue;
    }

    /** PLP information for ATSC3. */
    public static class Atsc3PlpInfo {
        private final int mPlpId;
        private final boolean mLlsFlag;

        private Atsc3PlpInfo(int plpId, boolean llsFlag) {
            mPlpId = plpId;
            mLlsFlag = llsFlag;
        }

        /** Gets PLP IDs. */
        public int getPlpId() {
            return mPlpId;
        }
        /** Gets LLS flag. */
        public boolean getLlsFlag() {
            return mLlsFlag;
        }
    }
}
