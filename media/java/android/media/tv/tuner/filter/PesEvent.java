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

import android.annotation.SystemApi;

/**
 * Filter event sent from {@link Filter} objects with PES type.
 *
 * @hide
 */
@SystemApi
public class PesEvent extends FilterEvent {
    private final int mStreamId;
    private final int mDataLength;
    private final int mMpuSequenceNumber;

    // This constructor is used by JNI code only
    private PesEvent(int streamId, int dataLength, int mpuSequenceNumber) {
        mStreamId = streamId;
        mDataLength = dataLength;
        mMpuSequenceNumber = mpuSequenceNumber;
    }

    /**
     * Gets stream ID.
     */
    public int getStreamId() {
        return mStreamId;
    }

    /**
     * Gets data size in bytes of filtered data.
     */
    public int getDataLength() {
        return mDataLength;
    }

    /**
     * Gets MPU sequence number of filtered data.
     */
    public int getMpuSequenceNumber() {
        return mMpuSequenceNumber;
    }
}
