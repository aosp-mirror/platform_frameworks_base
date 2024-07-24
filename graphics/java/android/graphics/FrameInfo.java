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

package android.graphics;

import android.annotation.LongDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class that contains all the timing information for the current frame. This
 * is used in conjunction with the hardware renderer to provide
 * continuous-monitoring jank events
 *
 * All times in nanoseconds from CLOCK_MONOTONIC/System.nanoTime()
 *
 * To minimize overhead from System.nanoTime() calls we infer durations of
 * things by knowing the ordering of the events. For example, to know how
 * long layout & measure took it's displayListRecordStart - performTraversalsStart.
 *
 * These constants must be kept in sync with FrameInfo.h in libhwui and are
 * used for indexing into AttachInfo's frameInfo long[], which is intended
 * to be quick to pass down to native via JNI, hence a pre-packed format
 *
 * @hide
 */
public final class FrameInfo {

    public long[] frameInfo = new long[FRAME_INFO_SIZE];

    // Various flags set to provide extra metadata about the current frame
    public static final int FLAGS = 0;

    // Is this the first-draw following a window layout?
    public static final long FLAG_WINDOW_VISIBILITY_CHANGED = 1;

    // A renderer associated with just a Surface, not with a ViewRootImpl instance.
    public static final long FLAG_SURFACE_CANVAS = 1 << 2;

    // An invalid vsync id to be used when FRAME_TIMELINE_VSYNC_ID is unknown
    // Needs to be in sync with android::ISurfaceComposer::INVALID_VSYNC_ID in native code
    public static final long INVALID_VSYNC_ID = -1;

    @LongDef(flag = true, value = {
            FLAG_WINDOW_VISIBILITY_CHANGED, FLAG_SURFACE_CANVAS })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrameInfoFlags {}

    public static final int FRAME_TIMELINE_VSYNC_ID = 1;

    // The intended vsync time, unadjusted by jitter
    public static final int INTENDED_VSYNC = 2;

    // Jitter-adjusted vsync time, this is what was used as input into the
    // animation & drawing system
    public static final int VSYNC = 3;

    // The id of the input event that caused the current frame
    public static final int INPUT_EVENT_ID = 4;

    // When input event handling started
    public static final int HANDLE_INPUT_START = 5;

    // When animation evaluations started
    public static final int ANIMATION_START = 6;

    // When ViewRootImpl#performTraversals() started
    public static final int PERFORM_TRAVERSALS_START = 7;

    // When View:draw() started
    public static final int DRAW_START = 8;

    // When the frame needs to be ready by
    public static final int FRAME_DEADLINE = 9;

    // When frame actually started.
    public static final int FRAME_START_TIME = 10;

    // Interval between two consecutive frames
    public static final int FRAME_INTERVAL = 11;

    // Must be the last one
    // This value must be in sync with `UI_THREAD_FRAME_INFO_SIZE` in FrameInfo.h
    private static final int FRAME_INFO_SIZE = FRAME_INTERVAL + 1;

    /** checkstyle */
    public void setVsync(long intendedVsync, long usedVsync, long frameTimelineVsyncId,
            long frameDeadline, long frameStartTime, long frameInterval) {
        frameInfo[FRAME_TIMELINE_VSYNC_ID] = frameTimelineVsyncId;
        frameInfo[INTENDED_VSYNC] = intendedVsync;
        frameInfo[VSYNC] = usedVsync;
        frameInfo[FLAGS] = 0;
        frameInfo[FRAME_DEADLINE] = frameDeadline;
        frameInfo[FRAME_START_TIME] = frameStartTime;
        frameInfo[FRAME_INTERVAL] = frameInterval;
    }

    /** checkstyle */
    public void markInputHandlingStart() {
        frameInfo[HANDLE_INPUT_START] = System.nanoTime();
    }

    /** checkstyle */
    public void markAnimationsStart() {
        frameInfo[ANIMATION_START] = System.nanoTime();
    }

    /** checkstyle */
    public void markPerformTraversalsStart() {
        frameInfo[PERFORM_TRAVERSALS_START] = System.nanoTime();
    }

    /** checkstyle */
    public void addFlags(@FrameInfoFlags long flags) {
        frameInfo[FLAGS] |= flags;
    }
}
