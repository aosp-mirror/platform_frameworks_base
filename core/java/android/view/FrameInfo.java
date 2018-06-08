/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.LongDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class that contains all the timing information for the current frame. This
 * is used in conjunction with the hardware renderer to provide
 * continous-monitoring jank events
 *
 * All times in nanoseconds from CLOCK_MONOTONIC/System.nanoTime()
 *
 * To minimize overhead from System.nanoTime() calls we infer durations of
 * things by knowing the ordering of the events. For example, to know how
 * long layout & measure took it's displayListRecordStart - performTraversalsStart.
 *
 * These constants must be kept in sync with FrameInfo.h in libhwui and are
 * used for indexing into AttachInfo's mFrameInfo long[], which is intended
 * to be quick to pass down to native via JNI, hence a pre-packed format
 *
 * @hide
 */
final class FrameInfo {

    long[] mFrameInfo = new long[9];

    // Various flags set to provide extra metadata about the current frame
    private static final int FLAGS = 0;

    // Is this the first-draw following a window layout?
    public static final long FLAG_WINDOW_LAYOUT_CHANGED = 1;

    @LongDef(flag = true, value = {
            FLAG_WINDOW_LAYOUT_CHANGED })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrameInfoFlags {}

    // The intended vsync time, unadjusted by jitter
    private static final int INTENDED_VSYNC = 1;

    // Jitter-adjusted vsync time, this is what was used as input into the
    // animation & drawing system
    private static final int VSYNC = 2;

    // The time of the oldest input event
    private static final int OLDEST_INPUT_EVENT = 3;

    // The time of the newest input event
    private static final int NEWEST_INPUT_EVENT = 4;

    // When input event handling started
    private static final int HANDLE_INPUT_START = 5;

    // When animation evaluations started
    private static final int ANIMATION_START = 6;

    // When ViewRootImpl#performTraversals() started
    private static final int PERFORM_TRAVERSALS_START = 7;

    // When View:draw() started
    private static final int DRAW_START = 8;

    public void setVsync(long intendedVsync, long usedVsync) {
        mFrameInfo[INTENDED_VSYNC] = intendedVsync;
        mFrameInfo[VSYNC] = usedVsync;
        mFrameInfo[OLDEST_INPUT_EVENT] = Long.MAX_VALUE;
        mFrameInfo[NEWEST_INPUT_EVENT] = 0;
        mFrameInfo[FLAGS] = 0;
    }

    public void updateInputEventTime(long inputEventTime, long inputEventOldestTime) {
        if (inputEventOldestTime < mFrameInfo[OLDEST_INPUT_EVENT]) {
            mFrameInfo[OLDEST_INPUT_EVENT] = inputEventOldestTime;
        }
        if (inputEventTime > mFrameInfo[NEWEST_INPUT_EVENT]) {
            mFrameInfo[NEWEST_INPUT_EVENT] = inputEventTime;
        }
    }

    public void markInputHandlingStart() {
        mFrameInfo[HANDLE_INPUT_START] = System.nanoTime();
    }

    public void markAnimationsStart() {
        mFrameInfo[ANIMATION_START] = System.nanoTime();
    }

    public void markPerformTraversalsStart() {
        mFrameInfo[PERFORM_TRAVERSALS_START] = System.nanoTime();
    }

    public void markDrawStart() {
        mFrameInfo[DRAW_START] = System.nanoTime();
    }

    public void addFlags(@FrameInfoFlags long flags) {
        mFrameInfo[FLAGS] |= flags;
    }

}
