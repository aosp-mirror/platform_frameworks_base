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
import java.util.ArrayList;

/** The PerformanceHintManager allows apps to send performance hint to system. */
@SystemService(Context.PERFORMANCE_HINT_SERVICE)
public final class PerformanceHintManager {
    private static final String TAG = "PerformanceHintManager";
    private final IHintManager mService;
    // HAL preferred update rate
    private final long mPreferredRate;

    /** @hide */
    public PerformanceHintManager(IHintManager service) {
        mService = service;
        try {
            mPreferredRate = mService.getHintSessionPreferredRate();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        try {
            IBinder token = new Binder();
            IHintSession session = mService.createHintSession(token, tids,
                    initialTargetWorkDurationNanos);
            if (session == null) return null;
            return new Session(session, sNanoClock, mPreferredRate,
                    initialTargetWorkDurationNanos);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get preferred update rate information for this device.
     *
     * @return the preferred update rate supported by device software.
     */
    public long getPreferredUpdateRateNanos() {
        return mPreferredRate;
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
        private final IHintSession mSession;
        private final NanoClock mElapsedRealtimeClock;
        // Target duration for choosing update rate
        private long mTargetDurationInNanos;
        // HAL preferred update rate
        private long mPreferredRate;
        // Last update timestamp
        private long mLastUpdateTimeStamp = -1L;
        // Cached samples
        private final ArrayList<Long> mActualDurationNanos;
        private final ArrayList<Long> mTimeStampNanos;

        /** @hide */
        public Session(IHintSession session, NanoClock elapsedRealtimeClock, long preferredRate,
                long durationNanos) {
            mSession = session;
            mElapsedRealtimeClock = elapsedRealtimeClock;
            mTargetDurationInNanos = durationNanos;
            mPreferredRate = preferredRate;
            mActualDurationNanos = new ArrayList<Long>();
            mTimeStampNanos = new ArrayList<Long>();
            mLastUpdateTimeStamp = mElapsedRealtimeClock.nanos();
        }

        /**
         * Updates this session's target duration for each cycle of work.
         *
         * @param targetDurationNanos the new desired duration in nanoseconds
         */
        public void updateTargetWorkDuration(long targetDurationNanos) {
            Preconditions.checkArgumentPositive(targetDurationNanos, "the hint target duration"
                    + " should be positive.");
            try {
                mSession.updateTargetWorkDuration(targetDurationNanos);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mTargetDurationInNanos = targetDurationNanos;
            /**
             * Most of the workload is target_duration dependent, so now clear the cached samples
             * as they are most likely obsolete.
             */
            mActualDurationNanos.clear();
            mTimeStampNanos.clear();
            mLastUpdateTimeStamp = mElapsedRealtimeClock.nanos();
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
            final long now = mElapsedRealtimeClock.nanos();
            mActualDurationNanos.add(actualDurationNanos);
            mTimeStampNanos.add(now);

            /**
             * Use current sample to determine the rate limit. We can pick a shorter rate limit
             * if any sample underperformed, however, it could be the lower level system is slow
             * to react. So here we explicitly choose the rate limit with the latest sample.
             */
            long rateLimit =
                    actualDurationNanos > mTargetDurationInNanos ? mPreferredRate
                            : 10 * mPreferredRate;

            if (now - mLastUpdateTimeStamp <= rateLimit) {
                return;
            }
            Preconditions.checkState(mActualDurationNanos.size() == mTimeStampNanos.size());
            final int size = mActualDurationNanos.size();
            long[] actualDurationArray = new long[size];
            long[] timeStampArray = new long[size];
            for (int i = 0; i < size; i++) {
                actualDurationArray[i] = mActualDurationNanos.get(i);
                timeStampArray[i] = mTimeStampNanos.get(i);
            }
            try {
                mSession.reportActualWorkDuration(actualDurationArray, timeStampArray);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mActualDurationNanos.clear();
            mTimeStampNanos.clear();
            mLastUpdateTimeStamp = now;
        }

        /**
         * Ends the current hint session.
         *
         * <p>Once called, you should not call anything else on this object.</p>
         */
        public void close() {
            try {
                mSession.close();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * The interface is to make the FakeClock for testing.
     * @hide
     */
    public interface NanoClock {
        /** Gets the current nanosecond instant of the clock. */
        long nanos();
    }

    private static final NanoClock sNanoClock = new NanoClock() {
        public long nanos() {
            return SystemClock.elapsedRealtimeNanos();
        }
    };
}
