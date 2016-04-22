/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.IntDef;
import android.view.Window;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class containing timing data for various milestones in a frame
 * lifecycle reported by the rendering subsystem.
 * <p>
 * Supported metrics can be queried via their corresponding identifier.
 * </p>
 */
public final class FrameMetrics {

    /**
     * Metric identifier for unknown delay.
     * <p>
     * Represents the number of nanoseconds elapsed waiting for the
     * UI thread to become responsive and process the frame. This
     * should be 0 most of the time.
     * </p>
     */
    public static final int UNKNOWN_DELAY_DURATION = 0;

    /**
     * Metric identifier for input handling duration.
     * <p>
     * Represents the number of nanoseconds elapsed issuing
     * input handling callbacks.
     * </p>
     */
    public static final int INPUT_HANDLING_DURATION = 1;

    /**
     * Metric identifier for animation callback duration.
     * <p>
     * Represents the number of nanoseconds elapsed issuing
     * animation callbacks.
     * </p>
     */
    public static final int ANIMATION_DURATION = 2;

    /**
     * Metric identifier for layout/measure duration.
     * <p>
     * Represents the number of nanoseconds elapsed measuring
     * and laying out the invalidated pieces of the view hierarchy.
     * </p>
     */
    public static final int LAYOUT_MEASURE_DURATION = 3;
    /**
     * Metric identifier for draw duration.
     * <p>
     * Represents the number of nanoseconds elapsed computing
     * DisplayLists for transformations applied to the view
     * hierarchy.
     * </p>
     */
    public static final int DRAW_DURATION = 4;

    /**
     * Metric identifier for sync duration.
     * <p>
     * Represents the number of nanoseconds elapsed
     * synchronizing the computed display lists with the render
     * thread.
     * </p>
     */
    public static final int SYNC_DURATION = 5;

    /**
     * Metric identifier for command issue duration.
     * <p>
     * Represents the number of nanoseconds elapsed
     * issuing draw commands to the GPU.
     * </p>
     */
    public static final int COMMAND_ISSUE_DURATION = 6;

    /**
     * Metric identifier for swap buffers duration.
     * <p>
     * Represents the number of nanoseconds elapsed issuing
     * the frame buffer for this frame to the display
     * subsystem.
     * </p>
     */
    public static final int SWAP_BUFFERS_DURATION = 7;

    /**
     * Metric identifier for total frame duration.
     * <p>
     * Represents the total time in nanoseconds this frame took to render
     * and be issued to the display subsystem.
     * </p>
     * <p>
     * Equal to the sum of the values of all other time-valued metric
     * identifiers.
     * </p>
     */
    public static final int TOTAL_DURATION = 8;

    /**
     * Metric identifier for a boolean value determining whether this frame was
     * the first to draw in a new Window layout.
     * <p>
     * {@link #getMetric(int)} will return 0 for false, 1 for true.
     * </p>
     * <p>
     * First draw frames are expected to be slow and should usually be exempt
     * from display jank calculations as they do not cause skips in animations
     * and are usually hidden by window animations or other tricks.
     * </p>
     */
    public static final int FIRST_DRAW_FRAME = 9;

    private static final int FRAME_INFO_FLAG_FIRST_DRAW = 1 << 0;

    /**
     * Identifiers for metrics available for each frame.
     *
     * {@see {@link #getMetric(int)}}
     * @hide
     */
    @IntDef({
            UNKNOWN_DELAY_DURATION,
            INPUT_HANDLING_DURATION,
            ANIMATION_DURATION,
            LAYOUT_MEASURE_DURATION,
            DRAW_DURATION,
            SYNC_DURATION,
            COMMAND_ISSUE_DURATION,
            SWAP_BUFFERS_DURATION,
            TOTAL_DURATION,
            FIRST_DRAW_FRAME,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Metric {}

    /**
     * Timestamp indices for frame milestones.
     *
     * May change from release to release.
     *
     * Must be kept in sync with frameworks/base/libs/hwui/FrameInfo.h.
     *
     * @hide
     */
    @IntDef ({
            Index.FLAGS,
            Index.INTENDED_VSYNC,
            Index.VSYNC,
            Index.OLDEST_INPUT_EVENT,
            Index.NEWEST_INPUT_EVENT,
            Index.HANDLE_INPUT_START,
            Index.ANIMATION_START,
            Index.PERFORM_TRAVERSALS_START,
            Index.DRAW_START,
            Index.SYNC_QUEUED,
            Index.SYNC_START,
            Index.ISSUE_DRAW_COMMANDS_START,
            Index.SWAP_BUFFERS,
            Index.FRAME_COMPLETED,
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface Index {
        int FLAGS = 0;
        int INTENDED_VSYNC = 1;
        int VSYNC = 2;
        int OLDEST_INPUT_EVENT = 3;
        int NEWEST_INPUT_EVENT = 4;
        int HANDLE_INPUT_START = 5;
        int ANIMATION_START = 6;
        int PERFORM_TRAVERSALS_START = 7;
        int DRAW_START = 8;
        int SYNC_QUEUED = 9;
        int SYNC_START = 10;
        int ISSUE_DRAW_COMMANDS_START = 11;
        int SWAP_BUFFERS = 12;
        int FRAME_COMPLETED = 13;

        int FRAME_STATS_COUNT = 14; // must always be last
    }

    /*
     * Bucket endpoints for each Metric defined above.
     *
     * Each defined metric *must* have a corresponding entry
     * in this list.
     */
    private static final int[] DURATIONS = new int[] {
        // UNKNOWN_DELAY
        Index.INTENDED_VSYNC, Index.HANDLE_INPUT_START,
        // INPUT_HANDLING
        Index.HANDLE_INPUT_START, Index.ANIMATION_START,
        // ANIMATION
        Index.ANIMATION_START, Index.PERFORM_TRAVERSALS_START,
        // LAYOUT_MEASURE
        Index.PERFORM_TRAVERSALS_START, Index.DRAW_START,
        // DRAW
        Index.DRAW_START, Index.SYNC_QUEUED,
        // SYNC
        Index.SYNC_START, Index.ISSUE_DRAW_COMMANDS_START,
        // COMMAND_ISSUE
        Index.ISSUE_DRAW_COMMANDS_START, Index.SWAP_BUFFERS,
        // SWAP_BUFFERS
        Index.SWAP_BUFFERS, Index.FRAME_COMPLETED,
        // TOTAL_DURATION
        Index.INTENDED_VSYNC, Index.FRAME_COMPLETED,
    };

    /* package */ final long[] mTimingData;

    /**
     * Constructs a FrameMetrics object as a copy.
     * <p>
     * Use this method to copy out metrics reported by
     * {@link Window.OnFrameMetricsAvailableListener#onFrameMetricsAvailable(
     * Window, FrameMetrics, int)}
     * </p>
     * @param other the FrameMetrics object to copy.
     */
    public FrameMetrics(FrameMetrics other) {
        mTimingData = new long[Index.FRAME_STATS_COUNT];
        System.arraycopy(other.mTimingData, 0, mTimingData, 0, mTimingData.length);
    }

    /**
     * @hide
     */
    FrameMetrics() {
        mTimingData = new long[Index.FRAME_STATS_COUNT];
    }

    /**
     * Retrieves the value associated with Metric identifier {@code id}
     * for this frame.
     * <p>
     * Boolean metrics are represented in [0,1], with 0 corresponding to
     * false, and 1 corresponding to true.
     * </p>
     * @param id the metric to retrieve
     * @return the value of the metric or -1 if it is not available.
     */
    public long getMetric(@Metric int id) {
        if (id < UNKNOWN_DELAY_DURATION || id > FIRST_DRAW_FRAME) {
            return -1;
        }

        if (mTimingData == null) {
            return -1;
        }

        if (id == FIRST_DRAW_FRAME) {
            return (mTimingData[Index.FLAGS] & FRAME_INFO_FLAG_FIRST_DRAW) != 0 ? 1 : 0;
        }

        int durationsIdx = 2 * id;
        return mTimingData[DURATIONS[durationsIdx + 1]]
                - mTimingData[DURATIONS[durationsIdx]];
    }
}

