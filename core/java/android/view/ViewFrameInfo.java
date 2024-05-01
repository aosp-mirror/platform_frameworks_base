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
import android.os.IInputConstants;

/**
 * The timing information of events taking place in ViewRootImpl
 * @hide
 */
public class ViewFrameInfo {
    public long drawStart;


    // Various flags set to provide extra metadata about the current frame. See flag definitions
    // inside FrameInfo.
    // @see android.graphics.FrameInfo.FLAG_WINDOW_LAYOUT_CHANGED
    public long flags;

    private int mInputEventId;

    private int mViewsMeasuredCounts;

    /**
     * Populate the missing fields using the data from ViewFrameInfo
     * @param frameInfo : the structure FrameInfo object to populate
     */
    public void populateFrameInfo(FrameInfo frameInfo) {
        frameInfo.frameInfo[FrameInfo.FLAGS] |= flags;
        frameInfo.frameInfo[FrameInfo.DRAW_START] = drawStart;
        frameInfo.frameInfo[FrameInfo.INPUT_EVENT_ID] = mInputEventId;
    }

    /**
     * Reset this data. Should typically be invoked after calling "populateFrameInfo".
     */
    public void reset() {
        drawStart = 0;
        mInputEventId = IInputConstants.INVALID_INPUT_EVENT_ID;
        flags = 0;
        mViewsMeasuredCounts = 0;
    }

    /**
     * Record the current time, and store it in 'drawStart'
     */
    public void markDrawStart() {
        drawStart = System.nanoTime();
    }

    /**
     * Record the number of view being measured for the current frame.
     */
    public int getAndIncreaseViewMeasuredCount() {
        return ++mViewsMeasuredCounts;
    }

    /**
     * Assign the value for input event id
     * @param eventId the id of the input event
     */
    public void setInputEvent(int eventId) {
        mInputEventId = eventId;
    }
}
