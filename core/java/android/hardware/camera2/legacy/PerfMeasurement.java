/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.legacy;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

/**
 * GPU and CPU performance measurement for the legacy implementation.
 *
 * <p>Measures CPU and GPU processing duration for a set of operations, and dumps
 * the results into a file.</p>
 *
 * <p>Rough usage:
 * <pre>
 * {@code
 *   <set up workload>
 *   <start long-running workload>
 *   mPerfMeasurement.startTimer();
 *   ...render a frame...
 *   mPerfMeasurement.stopTimer();
 *   <end workload>
 *   mPerfMeasurement.dumpPerformanceData("/sdcard/my_data.txt");
 * }
 * </pre>
 * </p>
 *
 * <p>All calls to this object must be made within the same thread, and the same GL context.
 * PerfMeasurement cannot be used outside of a GL context.  The only exception is
 * dumpPerformanceData, which can be called outside of a valid GL context.</p>
 */
class PerfMeasurement {
    private static final String TAG = "PerfMeasurement";

    public static final int DEFAULT_MAX_QUERIES = 3;

    private final long mNativeContext;

    private int mCompletedQueryCount = 0;

    /**
     * Values for completed measurements
     */
    private ArrayList<Long> mCollectedGpuDurations = new ArrayList<>();
    private ArrayList<Long> mCollectedCpuDurations = new ArrayList<>();
    private ArrayList<Long> mCollectedTimestamps = new ArrayList<>();

    /**
     * Values for in-progress measurements (waiting for async GPU results)
     */
    private Queue<Long> mTimestampQueue = new LinkedList<>();
    private Queue<Long> mCpuDurationsQueue = new LinkedList<>();

    private long mStartTimeNs;

    /**
     * The value returned by {@link #nativeGetNextGlDuration} if no new timing
     * measurement is available since the last call.
     */
    private static final long NO_DURATION_YET = -1l;

    /**
     * The value returned by {@link #nativeGetNextGlDuration} if timing failed for
     * the next timing interval
     */
    private static final long FAILED_TIMING = -2l;

    /**
     * Create a performance measurement object with a maximum of {@value #DEFAULT_MAX_QUERIES}
     * in-progess queries.
     */
    public PerfMeasurement() {
        mNativeContext = nativeCreateContext(DEFAULT_MAX_QUERIES);
    }

    /**
     * Create a performance measurement object with maxQueries as the maximum number of
     * in-progress queries.
     *
     * @param maxQueries maximum in-progress queries, must be larger than 0.
     * @throws IllegalArgumentException if maxQueries is less than 1.
     */
    public PerfMeasurement(int maxQueries) {
        if (maxQueries < 1) throw new IllegalArgumentException("maxQueries is less than 1");
        mNativeContext = nativeCreateContext(maxQueries);
    }

    /**
     * Returns true if the Gl timing methods will work, false otherwise.
     *
     * <p>Must be called within a valid GL context.</p>
     */
    public static boolean isGlTimingSupported() {
        return nativeQuerySupport();
    }

    /**
     * Dump collected data to file, and clear the stored data.
     *
     * <p>
     * Format is a simple csv-like text file with a header,
     * followed by a 3-column list of values in nanoseconds:
     * <pre>
     *   timestamp gpu_duration cpu_duration
     *   <long> <long> <long>
     *   <long> <long> <long>
     *   <long> <long> <long>
     *   ....
     * </pre>
     * </p>
     */
    public void dumpPerformanceData(String path) {
        try (BufferedWriter dump = new BufferedWriter(new FileWriter(path))) {
            dump.write("timestamp gpu_duration cpu_duration\n");
            for (int i = 0; i < mCollectedGpuDurations.size(); i++) {
                dump.write(String.format("%d %d %d\n",
                                mCollectedTimestamps.get(i),
                                mCollectedGpuDurations.get(i),
                                mCollectedCpuDurations.get(i)));
            }
            mCollectedTimestamps.clear();
            mCollectedGpuDurations.clear();
            mCollectedCpuDurations.clear();
        } catch (IOException e) {
            Log.e(TAG, "Error writing data dump to " + path + ":" + e);
        }
    }

    /**
     * Start a GPU/CPU timing measurement.
     *
     * <p>Call before starting a rendering pass. Only one timing measurement can be active at once,
     * so {@link #stopTimer} must be called before the next call to this method.</p>
     *
     * @throws IllegalStateException if the maximum number of queries are in progress already,
     *                               or the method is called multiple times in a row, or there is
     *                               a GPU error.
     */
    public void startTimer() {
        nativeStartGlTimer(mNativeContext);
        mStartTimeNs = SystemClock.elapsedRealtimeNanos();
    }

    /**
     * Finish a GPU/CPU timing measurement.
     *
     * <p>Call after finishing all the drawing for a rendering pass. Only one timing measurement can
     * be active at once, so {@link #startTimer} must be called before the next call to this
     * method.</p>
     *
     * @throws IllegalStateException if no GL timer is currently started, or there is a GPU
     *                               error.
     */
    public void stopTimer() {
        // Complete CPU timing
        long endTimeNs = SystemClock.elapsedRealtimeNanos();
        mCpuDurationsQueue.add(endTimeNs - mStartTimeNs);
        // Complete GL timing
        nativeStopGlTimer(mNativeContext);

        // Poll to see if GL timing results have arrived; if so
        // store the results for a frame
        long duration = getNextGlDuration();
        if (duration > 0) {
            mCollectedGpuDurations.add(duration);
            mCollectedTimestamps.add(mTimestampQueue.isEmpty() ?
                    NO_DURATION_YET : mTimestampQueue.poll());
            mCollectedCpuDurations.add(mCpuDurationsQueue.isEmpty() ?
                    NO_DURATION_YET : mCpuDurationsQueue.poll());
        }
        if (duration == FAILED_TIMING) {
            // Discard timestamp and CPU measurement since GPU measurement failed
            if (!mTimestampQueue.isEmpty()) {
                mTimestampQueue.poll();
            }
            if (!mCpuDurationsQueue.isEmpty()) {
                mCpuDurationsQueue.poll();
            }
        }
    }

    /**
     * Add a timestamp to a timing measurement. These are queued up and matched to completed
     * workload measurements as they become available.
     */
    public void addTimestamp(long timestamp) {
        mTimestampQueue.add(timestamp);
    }

    /**
     * Get the next available GPU timing measurement.
     *
     * <p>Since the GPU works asynchronously, the results of a single start/stopGlTimer measurement
     * will only be available some time after the {@link #stopTimer} call is made. Poll this method
     * until the result becomes available. If multiple start/endTimer measurements are made in a
     * row, the results will be available in FIFO order.</p>
     *
     * @return The measured duration of the GPU workload for the next pending query, or
     *         {@link #NO_DURATION_YET} if no queries are pending or the next pending query has not
     *         yet finished, or {@link #FAILED_TIMING} if the GPU was unable to complete the
     *         measurement.
     *
     * @throws IllegalStateException If there is a GPU error.
     *
     */
    private long getNextGlDuration() {
        long duration = nativeGetNextGlDuration(mNativeContext);
        if (duration > 0) {
            mCompletedQueryCount++;
        }
        return duration;
    }

    /**
     * Returns the number of measurements so far that returned a valid duration
     * measurement.
     */
    public int getCompletedQueryCount() {
        return mCompletedQueryCount;
    }

    @Override
    protected void finalize() {
        nativeDeleteContext(mNativeContext);
    }

    /**
     * Create a native performance measurement context.
     *
     * @param maxQueryCount maximum in-progress queries; must be >= 1.
     */
    private static native long nativeCreateContext(int maxQueryCount);

    /**
     * Delete the native context.
     *
     * <p>Not safe to call more than once.</p>
     */
    private static native void nativeDeleteContext(long contextHandle);

    /**
     * Query whether the relevant Gl extensions are available for Gl timing
     */
    private static native boolean nativeQuerySupport();

    /**
     * Start a GL timing section.
     *
     * <p>All GL commands between this method and the next {@link #nativeEndGlTimer} will be
     * included in the timing.</p>
     *
     * <p>Must be called from the same thread as calls to {@link #nativeEndGlTimer} and
     * {@link #nativeGetNextGlDuration}.</p>
     *
     * @throws IllegalStateException if a GL error occurs or start is called repeatedly.
     */
    protected static native void nativeStartGlTimer(long contextHandle);

    /**
     * Finish a GL timing section.
     *
     * <p>Some time after this call returns, the time the GPU took to
     * execute all work submitted between the latest {@link #nativeStartGlTimer} and
     * this call, will become available from calling {@link #nativeGetNextGlDuration}.</p>
     *
     * <p>Must be called from the same thread as calls to {@link #nativeStartGlTimer} and
     * {@link #nativeGetNextGlDuration}.</p>
     *
     * @throws IllegalStateException if a GL error occurs or stop is called before start
     */
    protected static native void nativeStopGlTimer(long contextHandle);

    /**
     * Get the next available GL duration measurement, in nanoseconds.
     *
     * <p>Must be called from the same thread as calls to {@link #nativeStartGlTimer} and
     * {@link #nativeEndGlTimer}.</p>
     *
     * @return the next GL duration measurement, or {@link #NO_DURATION_YET} if
     *         no new measurement is available, or {@link #FAILED_TIMING} if timing
     *         failed for the next duration measurement.
     * @throws IllegalStateException if a GL error occurs
     */
    protected static native long nativeGetNextGlDuration(long contextHandle);


}
