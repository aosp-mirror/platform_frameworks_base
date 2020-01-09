/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.hardware.tv.tuner.V1_0.Constants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Message from frontend during scan operations.
 *
 * @hide
 */
public class ScanMessage {

    /** @hide */
    @IntDef({
        LOCKED,
        END,
        PROGRESS_PERCENT,
        FREQUENCY,
        SYMBOL_RATE,
        PLP_IDS,
        GROUP_IDS,
        INPUT_STREAM_IDS,
        STANDARD,
        ATSC3_PLP_INFO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}
    /** @hide */
    public static final int LOCKED = Constants.FrontendScanMessageType.LOCKED;
    /** @hide */
    public static final int END = Constants.FrontendScanMessageType.END;
    /** @hide */
    public static final int PROGRESS_PERCENT = Constants.FrontendScanMessageType.PROGRESS_PERCENT;
    /** @hide */
    public static final int FREQUENCY = Constants.FrontendScanMessageType.FREQUENCY;
    /** @hide */
    public static final int SYMBOL_RATE = Constants.FrontendScanMessageType.SYMBOL_RATE;
    /** @hide */
    public static final int PLP_IDS = Constants.FrontendScanMessageType.PLP_IDS;
    /** @hide */
    public static final int GROUP_IDS = Constants.FrontendScanMessageType.GROUP_IDS;
    /** @hide */
    public static final int INPUT_STREAM_IDS = Constants.FrontendScanMessageType.INPUT_STREAM_IDS;
    /** @hide */
    public static final int STANDARD = Constants.FrontendScanMessageType.STANDARD;
    /** @hide */
    public static final int ATSC3_PLP_INFO = Constants.FrontendScanMessageType.ATSC3_PLP_INFO;

    private final int mType;
    private final Object mValue;

    private ScanMessage(int type, Object value) {
        mType = type;
        mValue = value;
    }

    /** Gets scan message type. */
    @Type
    public int getMessageType() {
        return mType;
    }
    /** Message indicates whether frontend is locked or not. */
    public boolean getIsLocked() {
        if (mType != LOCKED) {
            throw new IllegalStateException();
        }
        return (Boolean) mValue;
    }
    /** Message indicates whether the scan has reached the end or not. */
    public boolean getIsEnd() {
        if (mType != END) {
            throw new IllegalStateException();
        }
        return (Boolean) mValue;
    }
    /** Progress message in percent. */
    public int getProgressPercent() {
        if (mType != PROGRESS_PERCENT) {
            throw new IllegalStateException();
        }
        return (Integer) mValue;
    }
    /** Gets frequency. */
    public int getFrequency() {
        if (mType != FREQUENCY) {
            throw new IllegalStateException();
        }
        return (Integer) mValue;
    }
    /** Gets symbol rate. */
    public int getSymbolRate() {
        if (mType != SYMBOL_RATE) {
            throw new IllegalStateException();
        }
        return (Integer) mValue;
    }
    /** Gets PLP IDs. */
    public int[] getPlpIds() {
        if (mType != PLP_IDS) {
            throw new IllegalStateException();
        }
        return (int[]) mValue;
    }
    /** Gets group IDs. */
    public int[] getGroupIds() {
        if (mType != GROUP_IDS) {
            throw new IllegalStateException();
        }
        return (int[]) mValue;
    }
    /** Gets Input stream IDs. */
    public int[] getInputStreamIds() {
        if (mType != INPUT_STREAM_IDS) {
            throw new IllegalStateException();
        }
        return (int[]) mValue;
    }
    /** Gets the DVB-T or DVB-S standard. */
    public int getStandard() {
        if (mType != STANDARD) {
            throw new IllegalStateException();
        }
        return (int) mValue;
    }

    /** Gets PLP information for ATSC3. */
    public Atsc3PlpInfo[] getAtsc3PlpInfos() {
        if (mType != ATSC3_PLP_INFO) {
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
