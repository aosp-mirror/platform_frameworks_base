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
import android.annotation.SystemApi;


/**
 * Filter event sent from {@link Filter} objects for TS record data.
 *
 * @hide
 */
@SystemApi
public class TsRecordEvent extends FilterEvent {

    private final int mPid;
    private final int mTsIndexMask;
    private final int mScIndexMask;
    private final long mDataLength;

    // This constructor is used by JNI code only
    private TsRecordEvent(int pid, int tsIndexMask, int scIndexMask, long dataLength) {
        mPid = pid;
        mTsIndexMask = tsIndexMask;
        mScIndexMask = scIndexMask;
        mDataLength = dataLength;
    }

    /**
     * Gets packet ID.
     */
    public int getPacketId() {
        return mPid;
    }

    /**
     * Gets TS (transport stream) index mask.
     */
    @RecordSettings.TsIndexMask
    public int getTsIndexMask() {
        return mTsIndexMask;
    }
    /**
     * Gets SC (Start Code) index mask.
     *
     * <p>The index type is SC or SC-HEVC, and is set when configuring the filter.
     */
    @RecordSettings.ScIndexMask
    public int getScIndexMask() {
        return mScIndexMask;
    }

    /**
     * Gets data size in bytes of filtered data.
     */
    @BytesLong
    public long getDataLength() {
        return mDataLength;
    }
}
