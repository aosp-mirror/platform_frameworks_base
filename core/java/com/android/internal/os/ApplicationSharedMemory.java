/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.NonNull;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.time.DateTimeException;

/**
 * This class is used to create and access a shared memory region.
 *
 * <p>The intended use case is that memory is shared between system processes and application
 * processes such that it's readable to all apps and writable only to system processes.
 *
 * <p>This shared memory region can be used as an alternative to Binder IPC for driving
 * communication between system processes and application processes at a lower latency and higher
 * throughput than Binder IPC can provide, under circumstances where the additional features of
 * Binder IPC are not required.
 *
 * <p>Unlike Binder IPC, shared memory doesn't support synchronous transactions and associated
 * ordering guarantees, client identity (and therefore caller permission checking), and access
 * auditing. Therefore it's not a suitable alternative to Binder IPC for most use cases.
 *
 * <p>Additionally, because the intended use case is to make this shared memory region readable to
 * all apps, it's not suitable for sharing sensitive data.
 *
 * @see {@link ApplicationSharedMemoryTestRule} for unit testing support.
 * @hide
 */
public class ApplicationSharedMemory implements AutoCloseable {

    // LINT.IfChange(invalid_network_time)
    public static final long INVALID_NETWORK_TIME = -1;
    // LINT.ThenChange(frameworks/base/core/jni/com_android_internal_os_ApplicationSharedMemory.cpp:invalid_network_time)

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "ApplicationSharedMemory";

    @VisibleForTesting public static ApplicationSharedMemory sInstance;

    /** Get the process-global instance. */
    public static ApplicationSharedMemory getInstance() {
        ApplicationSharedMemory instance = sInstance;
        if (instance == null) {
            throw new IllegalStateException("ApplicationSharedMemory not initialized");
        }
        return instance;
    }

    /** Set the process-global instance. */
    public static void setInstance(ApplicationSharedMemory instance) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setInstance: " + instance);
        }

        if (sInstance != null) {
            throw new IllegalStateException("ApplicationSharedMemory already initialized");
        }
        sInstance = instance;
    }

    /** Allocate mutable shared memory region. */
    public static ApplicationSharedMemory create() {
        if (DEBUG) {
            Log.d(LOG_TAG, "create");
        }

        int fd = nativeCreate();
        FileDescriptor fileDescriptor = new FileDescriptor();
        fileDescriptor.setInt$(fd);

        final boolean mutable = true;
        long ptr = nativeMap(fd, mutable);
        nativeInit(ptr);

        return new ApplicationSharedMemory(fileDescriptor, mutable, ptr);
    }

    /**
     * Open shared memory region from a given {@link FileDescriptor}.
     *
     * @param fileDescriptor Handle to shared memory region.
     * @param mutable Whether the shared memory region is mutable. If true, will be mapped as
     *     read-write memory. If false, will be mapped as read-only memory. Passing true (mutable)
     *     if |pfd| is a handle to read-only memory will result in undefined behavior.
     */
    public static ApplicationSharedMemory fromFileDescriptor(
            @NonNull FileDescriptor fileDescriptor, boolean mutable) {
        if (DEBUG) {
            Log.d(LOG_TAG, "fromFileDescriptor: " + fileDescriptor + " mutable: " + mutable);
        }

        long ptr = nativeMap(fileDescriptor.getInt$(), mutable);
        return new ApplicationSharedMemory(fileDescriptor, mutable, ptr);
    }

    /**
     * Allocate read-write shared memory region.
     *
     * @return File descriptor of the shared memory region.
     */
    private static native int nativeCreate();

    /**
     * Map the shared memory region.
     *
     * @param fd File descriptor of the shared memory region.
     * @param isMutable Whether the shared memory region is mutable. If true, will be mapped as
     *     read-write memory. If false, will be mapped as read-only memory.
     * @return Pointer to the mapped shared memory region.
     */
    private static native long nativeMap(int fd, boolean isMutable);

    /**
     * Initialize read-write shared memory region.
     *
     * @param Pointer to the mapped shared memory region.
     */
    private static native void nativeInit(long ptr);

    /**
     * Unmap the shared memory region.
     *
     * @param ptr Pointer to the mapped shared memory region.
     */
    private static native void nativeUnmap(long ptr);

    /**
     * If true, this object owns the read-write instance of the shared memory region. If false, this
     * object can only read.
     */
    private final boolean mMutable;

    /**
     * Handle to the shared memory region. This can be send to other processes over Binder calls or
     * Intent extras. Recipients can use this handle to obtain read-only access to the shared memory
     * region.
     */
    private FileDescriptor mFileDescriptor;

    /** Native pointer to the mapped shared memory region. */
    private volatile long mPtr;

    ApplicationSharedMemory(@NonNull FileDescriptor fileDescriptor, boolean mutable, long ptr) {
        mFileDescriptor = fileDescriptor;
        mMutable = mutable;
        mPtr = ptr;
    }

    /**
     * Returns the file descriptor of the shared memory region.
     *
     * <p>This file descriptor retains the mutability properties of this object instance, and can be
     * sent over Binder IPC or Intent extras to another process to allow the remote process to map
     * the same shared memory region with the same access rights.
     *
     * @throws IllegalStateException if the file descriptor is closed.
     */
    public FileDescriptor getFileDescriptor() {
        checkFileOpen();
        return mFileDescriptor;
    }

    /**
     * Returns a read-only file descriptor of the shared memory region. This object can be sent over
     * Binder IPC or Intent extras to another process to allow the remote process to map the same
     * shared memory region with read-only access.
     *
     * @return a read-only handle to the shared memory region.
     * @throws IllegalStateException if the file descriptor is closed.
     */
    public FileDescriptor getReadOnlyFileDescriptor() throws IOException {
        checkFileOpen();
        FileDescriptor readOnlyFileDescriptor = new FileDescriptor();
        int readOnlyFd = nativeDupAsReadOnly(mFileDescriptor.getInt$());
        readOnlyFileDescriptor.setInt$(readOnlyFd);
        return readOnlyFileDescriptor;
    }

    /** Return a read-only duplicate of the file descriptor. */
    private static native int nativeDupAsReadOnly(int fd);

    /** Set the latest network Unix Epoch minus realtime millis. */
    public void setLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis(long offset) {
        checkMutable();
        nativeSetLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis(mPtr, offset);
    }

    /** Clear the latest network Unix Epoch minus realtime millis. */
    public void clearLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis() {
        checkMutable();
        nativeSetLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis(
                mPtr, INVALID_NETWORK_TIME);
    }

    @CriticalNative
    private static native void nativeSetLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis(
            long ptr, long offset);

    /**
     * Get the latest network Unix Epoch minus realtime millis.
     *
     * @throws DateTimeException when no network time can be provided.
     */
    public long getLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis()
            throws DateTimeException {
        checkMapped();
        long offset = nativeGetLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis(mPtr);
        if (offset == INVALID_NETWORK_TIME) {
            throw new DateTimeException("No network time available");
        }
        return offset;
    }

    @CriticalNative
    public static native long nativeGetLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis(
            long ptr);

    /**
     * Close the associated file descriptor.
     *
     * <p>This method is safe to call if you never intend to pass the file descriptor to another
     * process, whether via {@link #getFileDescriptor()} or {@link #getReadOnlyFileDescriptor()}.
     * After calling this method, subsequent calls to {@link #getFileDescriptor()} or {@link
     * #getReadOnlyFileDescriptor()} will throw an {@link IllegalStateException}.
     */
    public void closeFileDescriptor() {
        if (mFileDescriptor != null) {
            IoUtils.closeQuietly(mFileDescriptor);
            mFileDescriptor = null;
        }
    }

    public void close() {
        if (mPtr != 0) {
            nativeUnmap(mPtr);
            mPtr = 0;
        }

        if (mFileDescriptor != null) {
            IoUtils.closeQuietly(mFileDescriptor);
            mFileDescriptor = null;
        }
    }

    private void checkFileOpen() {
        if (mFileDescriptor == null) {
            throw new IllegalStateException("File descriptor is closed");
        }
    }

    /**
     * Check that the shared memory region is mapped.
     *
     * @throws IllegalStateException if the shared memory region is not mapped.
     */
    private void checkMapped() {
        if (mPtr == 0) {
            throw new IllegalStateException("Instance is closed");
        }
    }

    /**
     * Check that the shared memory region is mapped and mutable.
     *
     * @throws IllegalStateException if the shared memory region is not mapped or not mutable.
     */
    private void checkMutable() {
        checkMapped();
        if (!mMutable) {
            throw new IllegalStateException("Not mutable");
        }
    }

    /**
     * Return true if the memory has been mapped.  This never throws.
     */
    public boolean isMapped() {
        return mPtr != 0;
    }

    /**
     * Return true if the memory is mapped and mutable.  This never throws.  Note that it returns
     * false if the memory is not mapped.
     */
    public boolean isMutable() {
        return isMapped() && mMutable;
    }

    /**
     * Provide access to the nonce block needed by {@link PropertyInvalidatedCache}.  This method
     * returns 0 if the shared memory is not (yet) mapped.
     */
    public long getSystemNonceBlock() {
        return isMapped() ? nativeGetSystemNonceBlock(mPtr) : 0;
    }

    /**
     * Return a pointer to the system nonce cache in the shared memory region.  The method is
     * idempotent.
     */
    @FastNative
    private static native long nativeGetSystemNonceBlock(long ptr);
}
