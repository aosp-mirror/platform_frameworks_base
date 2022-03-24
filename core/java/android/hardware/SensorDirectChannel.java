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
import java.nio.channels.Channel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class representing a sensor direct channel. Use
 * {@link SensorManager#createDirectChannel(android.os.MemoryFile)} or
 * {@link SensorManager#createDirectChannel(android.hardware.HardwareBuffer)}
 * to obtain an object. The channel object can be then configured
 * (see {@link #configure(Sensor, int)})
 * to start delivery of sensor events into shared memory buffer.
 */
public final class SensorDirectChannel implements Channel {

    // shared memory types

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
            TYPE_MEMORY_FILE,
            TYPE_HARDWARE_BUFFER
    })
    public @interface MemoryType {}

    /**
     * Shared memory type ashmem, wrapped in MemoryFile object.
     *
     * @see SensorManager#createDirectChannel(MemoryFile)
     */
    public static final int TYPE_MEMORY_FILE = 1;

    /**
     * Shared memory type wrapped by HardwareBuffer object.
     *
     * @see SensorManager#createDirectChannel(HardwareBuffer)
     */
    public static final int TYPE_HARDWARE_BUFFER = 2;

    // sensor rate levels

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "RATE_" }, value = {
            RATE_STOP,
            RATE_NORMAL,
            RATE_FAST,
            RATE_VERY_FAST
    })
    public @interface RateLevel {}

    /**
     * Sensor stopped (no event output).
     *
     * @see #configure(Sensor, int)
     */
    public static final int RATE_STOP = 0;
    /**
     * Sensor operates at nominal rate of 50Hz.
     *
     * The actual rate is expected to be between 55% to 220% of nominal rate, thus between 27.5Hz to
     * 110Hz.
     *
     * @see #configure(Sensor, int)
     */
    public static final int RATE_NORMAL = 1; //50Hz
    /**
     * Sensor operates at nominal rate of 200Hz.
     *
     * The actual rate is expected to be between 55% to 220% of nominal rate, thus between 110Hz to
     * 440Hz.
     *
     * @see #configure(Sensor, int)
     */
    public static final int RATE_FAST = 2; // ~200Hz
    /**
     * Sensor operates at nominal rate of 800Hz.
     *
     * The actual rate is expected to be between 55% to 220% of nominal rate, thus between 440Hz to
     * 1760Hz.
     *
     * @see #configure(Sensor, int)
     */
    public static final int RATE_VERY_FAST = 3; // ~800Hz

    /**
     * Determine if a channel is still valid. A channel is invalidated after {@link #close()} is
     * called.
     *
     * @return <code>true</code> if channel is valid.
     */
    @Override
    public boolean isOpen() {
        return !mClosed.get();
    }

    /** @removed */
    @Deprecated
    public boolean isValid() {
        return isOpen();
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
        if (mClosed.compareAndSet(false, true)) {
            mCloseGuard.close();
            // actual close action
            mManager.destroyDirectChannel(this);
        }
    }

    /**
     * Configure sensor rate or stop sensor report.
     *
     * To start event report of a sensor, or change rate of existing report, call this function with
     * rateLevel other than {@link android.hardware.SensorDirectChannel#RATE_STOP}. Sensor events
     * will be added into a queue formed by the shared memory used in creation of direction channel.
     * Each element of the queue has size of 104 bytes and represents a sensor event. Data
     * structure of an element (all fields in little-endian):
     *
     * <pre>
     *   offset   type                    name
     * ------------------------------------------------------------------------
     *   0x0000   int32_t                 size (always 104)
     *   0x0004   int32_t                 sensor report token
     *   0x0008   int32_t                 type (see SensorType)
     *   0x000C   uint32_t                atomic counter
     *   0x0010   int64_t                 timestamp (see Event)
     *   0x0018   float[16]/int64_t[8]    data (data type depends on sensor type)
     *   0x0058   int32_t[4]              reserved (set to zero)
     * </pre>
     *
     * There are no head or tail pointers. The sequence and frontier of new sensor events is
     * determined by the atomic counter, which counts from 1 after creation of direct channel and
     * increments 1 for each new event. Atomic counter will wrap back to 1 after it reaches
     * UINT32_MAX, skipping value 0 to avoid confusion with uninitialized memory. The writer in
     * sensor system will wrap around from the start of shared memory region when it reaches the
     * end. If size of memory region is not a multiple of size of element (104 bytes), the residual
     * is not used at the end.  Function returns a positive sensor report token on success. This
     * token can be used to differentiate sensor events from multiple sensor of the same type. For
     * example, if there are two accelerometers in the system A and B, it is guaranteed different
     * report tokens will be returned when starting sensor A and B.
     *
     * To stop a sensor, call this function with rateLevel equal {@link
     * android.hardware.SensorDirectChannel#RATE_STOP}. If the sensor parameter is left to be null,
     * this will stop all active sensor report associated with the direct channel specified.
     * Function return 1 on success or 0 on failure.
     *
     * @param sensor A {@link android.hardware.Sensor} object to denote sensor to be operated.
     * @param rateLevel rate level defined in {@link android.hardware.SensorDirectChannel}.
     * @return * starting report or changing rate: positive sensor report token on success,
     *                                             0 on failure;
     *         * stopping report: 1 on success, 0 on failure.
     * @throws NullPointerException when channel is null.
     */
    public int configure(Sensor sensor, @RateLevel int rateLevel) {
        return mManager.configureDirectChannelImpl(this, sensor, rateLevel);
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
     * This function encode handle information in {@link android.os.MemoryFile} into a long array to
     * be passed down to native methods.
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
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

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
