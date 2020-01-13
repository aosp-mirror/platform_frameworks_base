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
 * Filter event sent from {@link Filter} objects with section type.
 *
 * @hide
 */
@SystemApi
public class SectionEvent extends FilterEvent {
    private final int mTableId;
    private final int mVersion;
    private final int mSectionNum;
    private final int mDataLength;

    // This constructor is used by JNI code only
    private SectionEvent(int tableId, int version, int sectionNum, int dataLength) {
        mTableId = tableId;
        mVersion = version;
        mSectionNum = sectionNum;
        mDataLength = dataLength;
    }

    /**
     * Gets table ID of filtered data.
     */
    public int getTableId() {
        return mTableId;
    }

    /**
     * Gets version number of filtered data.
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Gets section number of filtered data.
     */
    public int getSectionNumber() {
        return mSectionNum;
    }

    /**
     * Gets data size in bytes of filtered data.
     */
    public int getDataLength() {
        return mDataLength;
    }
}
