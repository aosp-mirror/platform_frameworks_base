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

import static android.graphics.FrameInfo.FLAG_WINDOW_VISIBILITY_CHANGED;

import android.annotation.IntDef;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

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

    /**
     * Metric identifier for the timestamp of the intended vsync for this frame.
     * <p>
     * The intended start point for the frame. If this value is different from
     * {@link #VSYNC_TIMESTAMP}, there was work occurring on the UI thread that
     * prevented it from responding to the vsync signal in a timely fashion.
     * </p>
     */
    public static final int INTENDED_VSYNC_TIMESTAMP = 10;

    /**
     * Metric identifier for the timestamp of the actual vsync for this frame.
     * <p>
     * The time value that was used in all the vsync listeners and drawing for
     * the frame (Choreographer frame callbacks, animations,
     * {@link View#getDrawingTime()}, etc.)
     * </p>
     */
    public static final int VSYNC_TIMESTAMP = 11;

    /**
     * Metric identifier for GPU duration.
     * <p>
     * Represents the total time in nanoseconds this frame took to complete on the GPU.
     * </p>
     **/
    public static final int GPU_DURATION = 12;

    /**
     * Metric identifier for the total duration that was available to the app to produce a frame.
     * <p>
     * Represents the total time in nanoseconds the system allocated for the app to produce its
     * frame. If FrameMetrics.TOTAL_DURATION < FrameMetrics.DEADLINE, the app hit its intended
     * deadline and there was no jank visible to the user.
     * </p>
     **/
    public static final int DEADLINE = 13;

    /**
     * Identifiers for metrics available for each frame.
     *
     * {@see #getMetric(int)}
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
            INTENDED_VSYNC_TIMESTAMP,
            VSYNC_TIMESTAMP,
            GPU_DURATION,
            DEADLINE,
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
            Index.FRAME_TIMELINE_VSYNC_ID,
            Index.INTENDED_VSYNC,
            Index.VSYNC,
            Index.INPUT_EVENT_ID,
            Index.HANDLE_INPUT_START,
            Index.ANIMATION_START,
            Index.PERFORM_TRAVERSALS_START,
            Index.DRAW_START,
            Index.FRAME_DEADLINE,
            Index.SYNC_QUEUED,
            Index.SYNC_START,
            Index.ISSUE_DRAW_COMMANDS_START,
            Index.SWAP_BUFFERS,
            Index.FRAME_COMPLETED,
            Index.DEQUEUE_BUFFER_DURATION,
            Index.QUEUE_BUFFER_DURATION,
            Index.GPU_COMPLETED,
            Index.SWAP_BUFFERS_COMPLETED,
            Index.DISPLAY_PRESENT_TIME,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Index {
        int FLAGS = 0;
        int FRAME_TIMELINE_VSYNC_ID = 1;
        int INTENDED_VSYNC = 2;
        int VSYNC = 3;
        int INPUT_EVENT_ID = 4;
        int HANDLE_INPUT_START = 5;
        int ANIMATION_START = 6;
        int PERFORM_TRAVERSALS_START = 7;
        int DRAW_START = 8;
        int FRAME_DEADLINE = 9;
        int FRAME_START_TIME = 10;
        int FRAME_INTERVAL = 11;
        int SYNC_QUEUED = 12;
        int SYNC_START = 13;
        int ISSUE_DRAW_COMMANDS_START = 14;
        int SWAP_BUFFERS = 15;
        int FRAME_COMPLETED = 16;
        int DEQUEUE_BUFFER_DURATION = 17;
        int QUEUE_BUFFER_DURATION = 18;
        int GPU_COMPLETED = 19;
        int SWAP_BUFFERS_COMPLETED = 20;
        int DISPLAY_PRESENT_TIME = 21;
        int COMMAND_SUBMISSION_COMPLETED = 22;

        int FRAME_STATS_COUNT = 23; // must always be last and in sync with
                                    // FrameInfoIndex::NumIndexes in libs/hwui/FrameInfo.h
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
        Index.SWAP_BUFFERS, Index.SWAP_BUFFERS_COMPLETED,
        // TOTAL_DURATION
        Index.INTENDED_VSYNC, Index.FRAME_COMPLETED,
        // RESERVED for FIRST_DRAW_FRAME
        0, 0,
        // RESERVED forINTENDED_VSYNC_TIMESTAMP
        0, 0,
        // RESERVED VSYNC_TIMESTAMP
        0, 0,
        // GPU_DURATION
        Index.COMMAND_SUBMISSION_COMPLETED, Index.GPU_COMPLETED,
        // DEADLINE
        Index.INTENDED_VSYNC, Index.FRAME_DEADLINE,
    };

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final long[] mTimingData;

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
    public FrameMetrics() {
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
        if (id < UNKNOWN_DELAY_DURATION || id > DEADLINE) {
            return -1;
        }

        if (mTimingData == null) {
            return -1;
        }

        if (id == FIRST_DRAW_FRAME) {
            return (mTimingData[Index.FLAGS] & FLAG_WINDOW_VISIBILITY_CHANGED) != 0 ? 1 : 0;
        } else if (id == INTENDED_VSYNC_TIMESTAMP) {
            return mTimingData[Index.INTENDED_VSYNC];
        } else if (id == VSYNC_TIMESTAMP) {
            return mTimingData[Index.VSYNC];
        }

        int durationsIdx = 2 * id;
        return mTimingData[DURATIONS[durationsIdx + 1]]
                - mTimingData[DURATIONS[durationsIdx]];
    }
}

