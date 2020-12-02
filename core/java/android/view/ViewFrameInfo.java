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

package android.view;

import android.graphics.FrameInfo;

/**
 * The timing information of events taking place in ViewRootImpl
 * @hide
 */
public class ViewFrameInfo {
    public long drawStart;
    public long oldestInputEventTime; // the time of the oldest input event consumed for this frame
    public long newestInputEventTime; // the time of the newest input event consumed for this frame
    // Various flags set to provide extra metadata about the current frame. See flag definitions
    // inside FrameInfo.
    // @see android.graphics.FrameInfo.FLAG_WINDOW_LAYOUT_CHANGED
    public long flags;

    /**
     * Update the oldest event time.
     * @param eventTime the time of the input event
     */
    public void updateOldestInputEvent(long eventTime) {
        if (oldestInputEventTime == 0 || eventTime < oldestInputEventTime) {
            oldestInputEventTime = eventTime;
        }
    }

    /**
     * Update the newest event time.
     * @param eventTime the time of the input event
     */
    public void updateNewestInputEvent(long eventTime) {
        if (newestInputEventTime == 0 || eventTime > newestInputEventTime) {
            newestInputEventTime = eventTime;
        }
    }

    /**
     * Populate the missing fields using the data from ViewFrameInfo
     * @param frameInfo : the structure FrameInfo object to populate
     */
    public void populateFrameInfo(FrameInfo frameInfo) {
        frameInfo.frameInfo[FrameInfo.FLAGS] |= flags;
        frameInfo.frameInfo[FrameInfo.DRAW_START] = drawStart;
        frameInfo.frameInfo[FrameInfo.OLDEST_INPUT_EVENT] = oldestInputEventTime;
        frameInfo.frameInfo[FrameInfo.NEWEST_INPUT_EVENT] = newestInputEventTime;
    }

    /**
     * Reset this data. Should typically be invoked after calling "populateFrameInfo".
     */
    public void reset() {
        drawStart = 0;
        oldestInputEventTime = 0;
        newestInputEventTime = 0;
        flags = 0;
    }

    /**
     * Record the current time, and store it in 'drawStart'
     */
    public void markDrawStart() {
        drawStart = System.nanoTime();
    }
}
