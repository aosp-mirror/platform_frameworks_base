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

import android.annotation.Nullable;
import android.media.tv.tuner.TunerConstants.Result;

/**
 *  A timer filter is used to filter data based on timestamps.
 *
 *  <p> If the timestamp is set, data is discarded if its timestamp is smaller than the
 *  timestamp in this time filter.
 *
 *  <p> The format of the timestamps is the same as PTS defined in ISO/IEC 13818-1:2019. The
 *  timestamps may or may not be related to PTS or DTS.
 *
 * @hide
 */
public class TimeFilter implements AutoCloseable {
    private native int nativeSetTimestamp(long timestamp);
    private native int nativeClearTimestamp();
    private native Long nativeGetTimestamp();
    private native Long nativeGetSourceTime();
    private native int nativeClose();

    private boolean mEnable = false;

    /**
     * Set timestamp for time based filter.
     *
     * It is used to set initial timestamp and enable time filtering. Once set, the time will be
     * increased automatically like a clock. Contents are discarded if their timestamps are
     * older than the time in the time filter.
     *
     * This method can be called more than once to reset the initial timestamp.
     *
     * @param timestamp initial timestamp for the time filter before it's increased. It's
     * based on the 90KHz counter, and it's the same format as PTS (Presentation Time Stamp)
     * defined in ISO/IEC 13818-1:2019. The timestamps may or may not be related to PTS or DTS.
     * @return result status of the operation.
     */
    @Result
    public int setCurrentTimestamp(long timestamp) {
        int res = nativeSetTimestamp(timestamp);
        // TODO: use a constant for SUCCESS
        if (res == 0) {
            mEnable = true;
        }
        return res;
    }

    /**
     * Clear the timestamp in the time filter.
     *
     * It is used to clear the time value of the time filter. Time filtering is disabled then.
     *
     * @return result status of the operation.
     */
    @Result
    public int clearTimestamp() {
        int res = nativeClearTimestamp();
        if (res == 0) {
            mEnable = false;
        }
        return res;
    }

    /**
     * Get the current time in the time filter.
     *
     * It is used to inquiry current time in the time filter.
     *
     * @return current timestamp in the time filter. It's based on the 90KHz counter, and it's
     * the same format as PTS (Presentation Time Stamp) defined in ISO/IEC 13818-1:2019. The
     * timestamps may or may not be related to PTS or DTS. {@code null} if the timestamp is
     * never set.
     */
    @Nullable
    public Long getTimeStamp() {
        if (!mEnable) {
            return null;
        }
        return nativeGetTimestamp();
    }

    /**
     * Get the timestamp from the beginning of incoming data stream.
     *
     * It is used to inquiry the timestamp from the beginning of incoming data stream.
     *
     * @return first timestamp of incoming data stream. It's based on the 90KHz counter, and
     * it's the same format as PTS (Presentation Time Stamp) defined in ISO/IEC 13818-1:2019.
     * The timestamps may or may not be related to PTS or DTS.
     */
    @Nullable
    public Long getSourceTime() {
        if (!mEnable) {
            return null;
        }
        return nativeGetSourceTime();
    }

    /**
     * Close the Time Filter instance
     *
     * It is to release the TimeFilter instance. Resources are reclaimed so the instance must
     * not be accessed after this method is called.
     */
    @Override
    public void close() {
        nativeClose();
    }
}
