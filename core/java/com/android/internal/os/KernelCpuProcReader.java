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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

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
    private static final int INITIAL_BUFFER_SIZE = 8 * 1024;
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
    private ByteBuffer mBuffer;

    @VisibleForTesting
    public KernelCpuProcReader(String procFile) {
        mProc = Paths.get(procFile);
        mBuffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_SIZE);
        mBuffer.clear();
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
            if (mBuffer.limit() > 0 && mBuffer.limit() < mBuffer.capacity()) {
                // mBuffer has data.
                return mBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            }
            return null;
        }
        mLastReadTime = SystemClock.elapsedRealtime();
        mBuffer.clear();
        final int oldMask = StrictMode.allowThreadDiskReadsMask();
        try (FileChannel fc = FileChannel.open(mProc, StandardOpenOption.READ)) {
            while (fc.read(mBuffer) == mBuffer.capacity()) {
                if (!resize()) {
                    mErrors++;
                    Slog.e(TAG, "Proc file is too large: " + mProc);
                    return null;
                }
                fc.position(0);
            }
        } catch (NoSuchFileException | FileNotFoundException e) {
            // Happens when the kernel does not provide this file. Not a big issue. Just log it.
            mErrors++;
            Slog.w(TAG, "File not exist: " + mProc);
            return null;
        } catch (IOException e) {
            mErrors++;
            Slog.e(TAG, "Error reading: " + mProc, e);
            return null;
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
        }
        mBuffer.flip();
        return mBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
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

    private boolean resize() {
        if (mBuffer.capacity() >= MAX_BUFFER_SIZE) {
            return false;
        }
        int newSize = Math.min(mBuffer.capacity() << 1, MAX_BUFFER_SIZE);
        // Slog.i(TAG, "Resize buffer " + mBuffer.capacity() + " => " + newSize);
        mBuffer = ByteBuffer.allocateDirect(newSize);
        return true;
    }
}
