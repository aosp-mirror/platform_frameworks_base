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

import android.media.tv.tuner.Tuner.Filter;

/**
 * Filter event sent from {@link Filter} objects with MMTP type.
 *
 * @hide
 */
public class MmtpRecordEvent extends FilterEvent {
    private final int mScHevcIndexMask;
    private final long mByteNumber;

    // This constructor is used by JNI code only
    private MmtpRecordEvent(int scHevcIndexMask, long byteNumber) {
        mScHevcIndexMask = scHevcIndexMask;
        mByteNumber = byteNumber;
    }

    /**
     * Gets indexes which can be tagged by NAL unit group in HEVC according to ISO/IEC 23008-2.
     */
    public int getScHevcIndexMask() {
        return mScHevcIndexMask;
    }

    /**
     * Gets the byte number from beginning of the filter's output.
     */
    public long getByteNumber() {
        return mByteNumber;
    }
}
