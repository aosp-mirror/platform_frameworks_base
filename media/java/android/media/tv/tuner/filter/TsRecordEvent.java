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
import android.media.tv.tuner.Tuner.Filter;
import android.media.tv.tuner.TunerConstants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Filter event sent from {@link Filter} objects for TS record data.
 *
 * @hide
 */
public class TsRecordEvent extends FilterEvent {
    /**
     * @hide
     */
    @IntDef(flag = true, value = {
            TunerConstants.TS_INDEX_FIRST_PACKET,
            TunerConstants.TS_INDEX_PAYLOAD_UNIT_START_INDICATOR,
            TunerConstants.TS_INDEX_CHANGE_TO_NOT_SCRAMBLED,
            TunerConstants.TS_INDEX_CHANGE_TO_EVEN_SCRAMBLED,
            TunerConstants.TS_INDEX_CHANGE_TO_ODD_SCRAMBLED,
            TunerConstants.TS_INDEX_DISCONTINUITY_INDICATOR,
            TunerConstants.TS_INDEX_RANDOM_ACCESS_INDICATOR,
            TunerConstants.TS_INDEX_PRIORITY_INDICATOR,
            TunerConstants.TS_INDEX_PCR_FLAG,
            TunerConstants.TS_INDEX_OPCR_FLAG,
            TunerConstants.TS_INDEX_SPLICING_POINT_FLAG,
            TunerConstants.TS_INDEX_PRIVATE_DATA,
            TunerConstants.TS_INDEX_ADAPTATION_EXTENSION_FLAG,
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
    public @interface IndexMask {}

    private final int mPid;
    private final int mIndexMask;
    private final long mByteNumber;

    // This constructor is used by JNI code only
    private TsRecordEvent(int pid, int indexMask, long byteNumber) {
        mPid = pid;
        mIndexMask = indexMask;
        mByteNumber = byteNumber;
    }

    /**
     * Gets packet ID.
     */
    public int getTpid() {
        return mPid;
    }

    /**
     * Gets index mask.
     *
     * <p>The index type is one of TS, SC, and SC-HEVC, and is set when configuring the filter.
     */
    @IndexMask
    public int getIndexMask() {
        return mIndexMask;
    }

    /**
     * Gets the byte number from beginning of the filter's output.
     */
    public long getByteNumber() {
        return mByteNumber;
    }
}
