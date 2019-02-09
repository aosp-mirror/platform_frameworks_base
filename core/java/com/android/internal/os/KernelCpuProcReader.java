/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.os;

import android.os.StrictMode;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Reads cpu time proc files with throttling (adjustable interval).
 *
 * KernelCpuProcReader is implemented as singletons for built-in kernel proc files. Get___Instance()
 * method will return corresponding reader instance. In order to prevent frequent GC,
 * KernelCpuProcReader reuses a {@link ByteBuffer} to store data read from proc files.
 *
 * A KernelCpuProcReader instance keeps an error counter. When the number of read errors within that
 * instance accumulates to 5, this instance will reject all further read requests.
 *
 * Each KernelCpuProcReader instance also has a throttler. Throttle interval can be adjusted via
 * {@link #setThrottleInterval(long)} method. Default throttle interval is 3000ms. If current
 * timestamp based on {@link SystemClock#elapsedRealtime()} is less than throttle interval from
 * the last read timestamp, {@link #readBytes()} will return previous result.
 *
 * A KernelCpuProcReader instance is thread-unsafe. Caller needs to hold a lock on this object while
 * accessing its instance methods or digesting the return values.
 */
public class KernelCpuProcReader {
    private static final String TAG = "KernelCpuProcReader";
    private static final int ERROR_THRESHOLD = 5;
    // Throttle interval in milliseconds
    private static final long DEFAULT_THROTTLE_INTERVAL = 3000L;
    private static final int MAX_BUFFER_SIZE = 1024 * 1024;
    private static final String PROC_UID_FREQ_TIME = "/proc/uid_cpupower/time_in_state";
    private static final String PROC_UID_ACTIVE_TIME = "/proc/uid_cpupower/concurrent_active_time";
    private static final String PROC_UID_CLUSTER_TIME = "/proc/uid_cpupower/concurrent_policy_time";

    private static final KernelCpuProcReader mFreqTimeReader = new KernelCpuProcReader(
            PROC_UID_FREQ_TIME);
    private static final KernelCpuProcReader mActiveTimeReader = new KernelCpuProcReader(
            PROC_UID_ACTIVE_TIME);
    private static final KernelCpuProcReader mClusterTimeReader = new KernelCpuProcReader(
            PROC_UID_CLUSTER_TIME);

    public static KernelCpuProcReader getFreqTimeReaderInstance() {
        return mFreqTimeReader;
    }

    public static KernelCpuProcReader getActiveTimeReaderInstance() {
        return mActiveTimeReader;
    }

    public static KernelCpuProcReader getClusterTimeReaderInstance() {
        return mClusterTimeReader;
    }

    private int mErrors;
    private long mThrottleInterval = DEFAULT_THROTTLE_INTERVAL;
    private long mLastReadTime = Long.MIN_VALUE;
    private final Path mProc;
    private byte[] mBuffer = new byte[8 * 1024];
    private int mContentSize;

    @VisibleForTesting
    public KernelCpuProcReader(String procFile) {
        mProc = Paths.get(procFile);
    }

    /**
     * Reads all bytes from the corresponding proc file.
     *
     * If elapsed time since last call to this method is less than the throttle interval, it will
     * return previous result. When IOException accumulates to 5, it will always return null. This
     * method is thread-unsafe, so is the return value. Caller needs to hold a lock on this
     * object while calling this method and digesting its return value.
     *
     * @return a {@link ByteBuffer} containing all bytes from the proc file.
     */
    public ByteBuffer readBytes() {
        if (mErrors >= ERROR_THRESHOLD) {
            return null;
        }
        if (SystemClock.elapsedRealtime() < mLastReadTime + mThrottleInterval) {
            if (mContentSize > 0) {
                return ByteBuffer.wrap(mBuffer, 0, mContentSize).asReadOnlyBuffer()
                        .order(ByteOrder.nativeOrder());
            }
            return null;
        }
        mLastReadTime = SystemClock.elapsedRealtime();
        mContentSize = 0;
        final int oldMask = StrictMode.allowThreadDiskReadsMask();
        try (InputStream in = Files.newInputStream(mProc)) {
            int numBytes = 0;
            int curr;
            while ((curr = in.read(mBuffer, numBytes, mBuffer.length - numBytes)) >= 0) {
                numBytes += curr;
                if (numBytes == mBuffer.length) {
                    // Hit the limit. Resize mBuffer.
                    if (mBuffer.length == MAX_BUFFER_SIZE) {
                        mErrors++;
                        Slog.e(TAG, "Proc file is too large: " + mProc);
                        return null;
                    }
                    mBuffer = Arrays.copyOf(mBuffer,
                            Math.min(mBuffer.length << 1, MAX_BUFFER_SIZE));
                }
            }
            mContentSize = numBytes;
            return ByteBuffer.wrap(mBuffer, 0, mContentSize).asReadOnlyBuffer()
                    .order(ByteOrder.nativeOrder());
        } catch (NoSuchFileException | FileNotFoundException e) {
            // Happens when the kernel does not provide this file. Not a big issue. Just log it.
            mErrors++;
            Slog.w(TAG, "File not exist: " + mProc);
        } catch (IOException e) {
            mErrors++;
            Slog.e(TAG, "Error reading: " + mProc, e);
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
        }
        return null;
    }

    /**
     * Sets the throttle interval. Set to 0 will disable throttling. Thread-unsafe, holding a lock
     * on this object is recommended.
     *
     * @param throttleInterval throttle interval in milliseconds
     */
    public void setThrottleInterval(long throttleInterval) {
        if (throttleInterval >= 0) {
            mThrottleInterval = throttleInterval;
        }
    }
}
