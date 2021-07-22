/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;

import com.android.internal.util.Preconditions;

import java.io.Closeable;

/** The PerformanceHintManager allows apps to send performance hint to system. */
@SystemService(Context.PERFORMANCE_HINT_SERVICE)
public final class PerformanceHintManager {
    private final long mNativeManagerPtr;

    /** @hide */
    public static PerformanceHintManager create() throws ServiceManager.ServiceNotFoundException {
        long nativeManagerPtr = nativeAcquireManager();
        if (nativeManagerPtr == 0) {
            throw new ServiceManager.ServiceNotFoundException(Context.PERFORMANCE_HINT_SERVICE);
        }
        return new PerformanceHintManager(nativeManagerPtr);
    }

    private PerformanceHintManager(long nativeManagerPtr) {
        mNativeManagerPtr = nativeManagerPtr;
    }

    /**
     * Creates a {@link Session} for the given set of threads and sets their initial target work
     * duration.
     *
     * @param tids The list of threads to be associated with this session. They must be part of
     *     this process' thread group.
     * @param initialTargetWorkDurationNanos The desired duration in nanoseconds for the new
     *     session.
     * @return the new session if it is supported on this device, null if hint session is not
     *     supported on this device.
     */
    @Nullable
    public Session createHintSession(@NonNull int[] tids, long initialTargetWorkDurationNanos) {
        Preconditions.checkNotNull(tids, "tids cannot be null");
        Preconditions.checkArgumentPositive(initialTargetWorkDurationNanos,
                "the hint target duration should be positive.");
        long nativeSessionPtr = nativeCreateSession(mNativeManagerPtr, tids,
                initialTargetWorkDurationNanos);
        if (nativeSessionPtr == 0) return null;
        return new Session(nativeSessionPtr);
    }

    /**
     * Get preferred update rate information for this device.
     *
     * @return the preferred update rate supported by device software.
     */
    public long getPreferredUpdateRateNanos() {
        return nativeGetPreferredUpdateRateNanos(mNativeManagerPtr);
    }

    /**
     * A Session represents a group of threads with an inter-related workload such that hints for
     * their performance should be considered as a unit. The threads in a given session should be
     * long-life and not created or destroyed dynamically.
     *
     * <p>Each session is expected to have a periodic workload with a target duration for each
     * cycle. The cycle duration is likely greater than the target work duration to allow other
     * parts of the pipeline to run within the available budget. For example, a renderer thread may
     * work at 60hz in order to produce frames at the display's frame but have a target work
     * duration of only 6ms.</p>
     *
     * <p>Any call in this class will change its internal data, so you must do your own thread
     * safety to protect from racing.</p>
     *
     * <p>Note that the target work duration can be {@link #updateTargetWorkDuration(long) updated}
     * if workloads change.</p>
     *
     * <p>After each cycle of work, the client is expected to
     * {@link #reportActualWorkDuration(long) report} the actual time taken to complete.</p>
     *
     * <p>All timings should be in {@link SystemClock#elapsedRealtimeNanos()}.</p>
     */
    public static class Session implements Closeable {
        private long mNativeSessionPtr;

        /** @hide */
        public Session(long nativeSessionPtr) {
            mNativeSessionPtr = nativeSessionPtr;
        }

        /** @hide */
        @Override
        protected void finalize() throws Throwable {
            try {
                close();
            } finally {
                super.finalize();
            }
        }

        /**
         * Updates this session's target duration for each cycle of work.
         *
         * @param targetDurationNanos the new desired duration in nanoseconds
         */
        public void updateTargetWorkDuration(long targetDurationNanos) {
            Preconditions.checkArgumentPositive(targetDurationNanos, "the hint target duration"
                    + " should be positive.");
            nativeUpdateTargetWorkDuration(mNativeSessionPtr, targetDurationNanos);
        }

        /**
         * Reports the actual duration for the last cycle of work.
         *
         * <p>The system will attempt to adjust the core placement of the threads within the thread
         * group and/or the frequency of the core on which they are run to bring the actual duration
         * close to the target duration.</p>
         *
         * @param actualDurationNanos how long the thread group took to complete its last task in
         *     nanoseconds
         */
        public void reportActualWorkDuration(long actualDurationNanos) {
            Preconditions.checkArgumentPositive(actualDurationNanos, "the actual duration should"
                    + " be positive.");
            nativeReportActualWorkDuration(mNativeSessionPtr, actualDurationNanos);
        }

        /**
         * Ends the current hint session.
         *
         * <p>Once called, you should not call anything else on this object.</p>
         */
        public void close() {
            if (mNativeSessionPtr != 0) {
                nativeCloseSession(mNativeSessionPtr);
                mNativeSessionPtr = 0;
            }
        }
    }

    private static native long nativeAcquireManager();
    private static native long nativeGetPreferredUpdateRateNanos(long nativeManagerPtr);
    private static native long nativeCreateSession(long nativeManagerPtr,
            int[] tids, long initialTargetWorkDurationNanos);
    private static native void nativeUpdateTargetWorkDuration(long nativeSessionPtr,
            long targetDurationNanos);
    private static native void nativeReportActualWorkDuration(long nativeSessionPtr,
            long actualDurationNanos);
    private static native void nativeCloseSession(long nativeSessionPtr);
}
