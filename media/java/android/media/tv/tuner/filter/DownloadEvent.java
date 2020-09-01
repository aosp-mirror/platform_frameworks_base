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
 * Filter event sent from {@link Filter} objects with download type.
 *
 * @hide
 */
@SystemApi
public class DownloadEvent extends FilterEvent {
    private final int mItemId;
    private final int mMpuSequenceNumber;
    private final int mItemFragmentIndex;
    private final int mLastItemFragmentIndex;
    private final int mDataLength;

    // This constructor is used by JNI code only
    private DownloadEvent(int itemId, int mpuSequenceNumber, int itemFragmentIndex,
            int lastItemFragmentIndex, int dataLength) {
        mItemId = itemId;
        mMpuSequenceNumber = mpuSequenceNumber;
        mItemFragmentIndex = itemFragmentIndex;
        mLastItemFragmentIndex = lastItemFragmentIndex;
        mDataLength = dataLength;
    }

    /**
     * Gets item ID.
     */
    public int getItemId() {
        return mItemId;
    }

    /**
     * Gets MPU sequence number of filtered data.
     */
    public int getMpuSequenceNumber() {
        return mMpuSequenceNumber;
    }

    /**
     * Gets current index of the current item.
     *
     * An item can be stored in different fragments.
     */
    public int getItemFragmentIndex() {
        return mItemFragmentIndex;
    }

    /**
     * Gets last index of the current item.
     */
    public int getLastItemFragmentIndex() {
        return mLastItemFragmentIndex;
    }

    /**
     * Gets data size in bytes of filtered data.
     */
    public int getDataLength() {
        return mDataLength;
    }
}

