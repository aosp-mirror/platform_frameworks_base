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

import android.annotation.BytesLong;
import android.annotation.IntRange;
import android.annotation.SystemApi;
import android.media.tv.tuner.filter.RecordSettings.ScHevcIndex;

/**
 * Filter event sent from {@link Filter} objects with MPEG media Transport Protocol(MMTP) type.
 *
 * @hide
 */
@SystemApi
public class MmtpRecordEvent extends FilterEvent {
    private final int mScHevcIndexMask;
    private final long mDataLength;
    private final int mMpuSequenceNumber;
    private final long mPts;
    private final int mFirstMbInSlice;
    private final int mTsIndexMask;

    // This constructor is used by JNI code only
    private MmtpRecordEvent(int scHevcIndexMask, long dataLength, int mpuSequenceNumber, long pts,
            int firstMbInSlice, int tsIndexMask) {
        mScHevcIndexMask = scHevcIndexMask;
        mDataLength = dataLength;
        mMpuSequenceNumber = mpuSequenceNumber;
        mPts = pts;
        mFirstMbInSlice = firstMbInSlice;
        mTsIndexMask = tsIndexMask;
    }

    /**
     * Gets indexes which can be tagged by NAL unit group in HEVC according to ISO/IEC 23008-2.
     */
    @ScHevcIndex
    public int getScHevcIndexMask() {
        return mScHevcIndexMask;
    }

    /**
     * Gets the record data offset from the beginning of the record buffer.
     */
    @BytesLong
    public long getDataLength() {
        return mDataLength;
    }

    /**
     * Get the MPU sequence number of the filtered data.
     *
     * <p>This field is only supported in Tuner 1.1 or higher version. Unsupported version will
     * return {@link android.media.tv.tuner.Tuner#INVALID_MMTP_RECORD_EVENT_MPT_SEQUENCE_NUM}. Use
     * {@link android.media.tv.tuner.TunerVersionChecker#getTunerVersion()} to get the version
     * information.
     */
    @IntRange(from = 0)
    public int getMpuSequenceNumber() {
        return mMpuSequenceNumber;
    }

    /**
     * Get the Presentation Time Stamp(PTS) for the audio or video frame. It is based on 90KHz
     * and has the same format as the PTS in ISO/IEC 13818-1.
     *
     * <p>This field is only supported in Tuner 1.1 or higher version. Unsupported version will
     * return {@link android.media.tv.tuner.Tuner#INVALID_TIMESTAMP}. Use
     * {@link android.media.tv.tuner.TunerVersionChecker#getTunerVersion()} to get the version
     * information.
     */
    public long getPts() {
        return mPts;
    }

    /**
     * Get the address of the first macroblock in the slice defined in ITU-T Rec. H.264.
     *
     * <p>This field is only supported in Tuner 1.1 or higher version. Unsupported version will
     * return {@link android.media.tv.tuner.Tuner#INVALID_FIRST_MACROBLOCK_IN_SLICE}. Use
     * {@link android.media.tv.tuner.TunerVersionChecker#getTunerVersion()} to get the version
     * information.
     */
    public int getFirstMacroblockInSlice() {
        return mFirstMbInSlice;
    }

    /**
     * Get the offset of the recorded keyframe from MMT Packet Table.
     *
     * <p>This field is only supported in Tuner 1.1 or higher version. Unsupported version will
     * return {@link RecordSettings#TS_INDEX_INVALID}. Use
     * {@link android.media.tv.tuner.TunerVersionChecker#getTunerVersion()} to get the
     * version information.
     */
    @RecordSettings.TsIndexMask
    public int getTsIndexMask() {
        return mTsIndexMask;
    }
}
