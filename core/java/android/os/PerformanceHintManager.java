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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;

import com.android.internal.util.Preconditions;

import java.io.Closeable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.Reference;


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

        /**
        * This hint indicates a sudden increase in CPU workload intensity. It means
        * that this hint session needs extra CPU resources immediately to meet the
        * target duration for the current work cycle.
        *
        * @hide
        */
        @TestApi
        public static final int CPU_LOAD_UP = 0;
        /**
        * This hint indicates a decrease in CPU workload intensity. It means that
        * this hint session can reduce CPU resources and still meet the target duration.
        *
        * @hide
        */
        @TestApi
        public static final int CPU_LOAD_DOWN = 1;
        /**
        * This hint indicates an upcoming CPU workload that is completely changed and
        * unknown. It means that the hint session should reset CPU resources to a known
        * baseline to prepare for an arbitrary load, and must wake up if inactive.
        *
        * @hide
        */
        @TestApi
        public static final int CPU_LOAD_RESET = 2;
        /**
        * This hint indicates that the most recent CPU workload is resuming after a
        * period of inactivity. It means that the hint session should allocate similar
        * CPU resources to what was used previously, and must wake up if inactive.
        *
        * @hide
        */
        @TestApi
        public static final int CPU_LOAD_RESUME = 3;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"CPU_LOAD_"}, value = {
            CPU_LOAD_UP,
            CPU_LOAD_DOWN,
            CPU_LOAD_RESET,
            CPU_LOAD_RESUME
        })
        public @interface Hint {}

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

        /**
         * Sends performance hints to inform the hint session of changes in the workload.
         *
         * @param hint The hint to send to the session.
         *
         * @hide
         */
        @TestApi
        public void sendHint(@Hint int hint) {
            Preconditions.checkArgumentNonNegative(hint, "the hint ID should be at least"
                    + " zero.");
            try {
                nativeSendHint(mNativeSessionPtr, hint);
            } finally {
                Reference.reachabilityFence(this);
            }
        }

        /**
         * Set a list of threads to the performance hint session. This operation will replace
         * the current list of threads with the given list of threads.
         * Note that this is not an oneway method.
         *
         * @param tids The list of threads to be associated with this session. They must be
         *     part of this app's thread group.
         *
         * @throws IllegalStateException if the hint session is not in the foreground.
         * @throws IllegalArgumentException if the thread id list is empty.
         * @throws SecurityException if any thread id doesn't belong to the application.
         */
        public void setThreads(@NonNull int[] tids) {
            if (mNativeSessionPtr == 0) {
                return;
            }
            if (tids.length == 0) {
                throw new IllegalArgumentException("Thread id list can't be empty.");
            }
            nativeSetThreads(mNativeSessionPtr, tids);
        }

        /**
         * Returns the list of thread ids.
         *
         * @hide
         */
        @TestApi
        public @Nullable int[] getThreadIds() {
            return nativeGetThreadIds(mNativeSessionPtr);
        }
    }

    private static native long nativeAcquireManager();
    private static native long nativeGetPreferredUpdateRateNanos(long nativeManagerPtr);
    private static native long nativeCreateSession(long nativeManagerPtr,
            int[] tids, long initialTargetWorkDurationNanos);
    private static native int[] nativeGetThreadIds(long nativeSessionPtr);
    private static native void nativeUpdateTargetWorkDuration(long nativeSessionPtr,
            long targetDurationNanos);
    private static native void nativeReportActualWorkDuration(long nativeSessionPtr,
            long actualDurationNanos);
    private static native void nativeCloseSession(long nativeSessionPtr);
    private static native void nativeSendHint(long nativeSessionPtr, int hint);
    private static native void nativeSetThreads(long nativeSessionPtr, int[] tids);
}
