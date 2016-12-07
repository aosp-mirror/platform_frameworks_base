/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.hardware;

import android.annotation.IntDef;
import android.os.MemoryFile;

import dalvik.system.CloseGuard;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class representing a sensor direct channel. Use {@link
 * SensorManager#createDirectChannel(android.os.MemoryFile)} to obtain object.
 */
public final class SensorDirectChannel implements AutoCloseable {

    // shared memory types

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {TYPE_ASHMEM, TYPE_HARDWARE_BUFFER})
    public @interface MemoryType {};
    /**
     * Shared memory type ashmem, wrapped in MemoryFile object.
     *
     * @see SensorManager#createDirectChannel(MemoryFile)
     */
    public static final int TYPE_ASHMEM = 1;

    /**
     * Shared memory type wrapped by HardwareBuffer object.
     *
     * @see SensorManager#createDirectChannel(HardwareBuffer)
     */
    public static final int TYPE_HARDWARE_BUFFER = 2;

    // sensor rate levels

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {RATE_STOP, RATE_NORMAL, RATE_FAST, RATE_VERY_FAST})
    public @interface RateLevel {};

    /**
     * Sensor stopped (no event output).
     *
     * @see SensorManager#configureDirectChannel(SensorDirectChannel, Sensor, int)
     */
    public static final int RATE_STOP = 0;
    /**
     * Sensor operates at nominal rate of 50Hz.
     *
     * The actual rate is expected to be between 55% to 220% of nominal rate, thus between 27.5Hz to
     * 110Hz.
     *
     * @see SensorManager#configureDirectChannel(SensorDirectChannel, Sensor, int)
     */
    public static final int RATE_NORMAL = 1; //50Hz
    /**
     * Sensor operates at nominal rate of 200Hz.
     *
     * The actual rate is expected to be between 55% to 220% of nominal rate, thus between 110Hz to
     * 440Hz.
     *
     * @see SensorManager#configureDirectChannel(SensorDirectChannel, Sensor, int)
     */
    public static final int RATE_FAST = 2; // ~200Hz
    /**
     * Sensor operates at nominal rate of 800Hz.
     *
     * The actual rate is expected to be between 55% to 220% of nominal rate, thus between 440Hz to
     * 1760Hz.
     *
     * @see SensorManager#configureDirectChannel(SensorDirectChannel, Sensor, int)
     */
    public static final int RATE_VERY_FAST = 3; // ~800Hz

    /**
     * Determine if a channel is still valid. A channel is invalidated after {@link #close()} is
     * called.
     *
     * @return <code>true</code> if channel is valid.
     */
    public boolean isValid() {
        return !mClosed.get();
    }

    /**
     * Close sensor direct channel.
     *
     * Stop all active sensor in the channel and free sensor system resource related to channel.
     * Shared memory used for creating the direct channel need to be closed or freed separately.
     *
     * @see SensorManager#createDirectChannel(MemoryFile)
     * @see SensorManager#createDirectChannel(HardwareBuffer)
     */
    @Override
    public void close() {
        mCloseGuard.close();
        if (mClosed.compareAndSet(false, true)) {
            // actual close action
            mManager.destroyDirectChannel(this);
        }
    }

    /** @hide */
    SensorDirectChannel(SensorManager manager, int id, int type, long size) {
        mManager = manager;
        mNativeHandle = id;
        mType = type;
        mSize = size;
        mCloseGuard.open("SensorDirectChannel");
    }

    /** @hide */
    int getNativeHandle() {
        return mNativeHandle;
    }

    /**
     * This function encode handle information in {@link android.os.Memory} into a long array to be
     * passed down to native methods.
     *
     * @hide */
    static long[] encodeData(MemoryFile ashmem) {
        int fd;
        try {
            fd = ashmem.getFileDescriptor().getInt$();
        } catch (IOException e) {
            fd = -1;
        }
        return new long[] { 1 /*numFds*/, 0 /*numInts*/, fd };
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mCloseGuard.warnIfOpen();
            close();
        } finally {
            super.finalize();
        }
    }

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();
    private final SensorManager mManager;
    private final int mNativeHandle;
    private final long mSize;
    private final int mType;
}
