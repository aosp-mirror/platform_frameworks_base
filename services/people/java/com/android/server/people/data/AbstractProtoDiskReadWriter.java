/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.data;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Base class for reading and writing protobufs on disk from a root directory. Callers should
 * ensure that the root directory is unlocked before doing I/O operations using this class.
 *
 * @param <T> is the data class representation of a protobuf.
 */
abstract class AbstractProtoDiskReadWriter<T> {

    private static final String TAG = AbstractProtoDiskReadWriter.class.getSimpleName();

    // Common disk write delay that will be appropriate for most scenarios.
    private static final long DEFAULT_DISK_WRITE_DELAY = 2L * DateUtils.MINUTE_IN_MILLIS;
    private static final long SHUTDOWN_DISK_WRITE_TIMEOUT = 5L * DateUtils.SECOND_IN_MILLIS;

    private final File mRootDir;
    private final ScheduledExecutorService mScheduledExecutorService;

    @GuardedBy("this")
    private ScheduledFuture<?> mScheduledFuture;

    // File name -> data class
    @GuardedBy("this")
    private Map<String, T> mScheduledFileDataMap = new ArrayMap<>();

    /**
     * Child class shall provide a {@link ProtoStreamWriter} to facilitate the writing of data as a
     * protobuf on disk.
     */
    abstract ProtoStreamWriter<T> protoStreamWriter();

    /**
     * Child class shall provide a {@link ProtoStreamReader} to facilitate the reading of protobuf
     * data on disk.
     */
    abstract ProtoStreamReader<T> protoStreamReader();

    AbstractProtoDiskReadWriter(@NonNull File rootDir,
            @NonNull ScheduledExecutorService scheduledExecutorService) {
        mRootDir = rootDir;
        mScheduledExecutorService = scheduledExecutorService;
    }

    @WorkerThread
    void delete(@NonNull String fileName) {
        synchronized (this) {
            mScheduledFileDataMap.remove(fileName);
        }
        final File file = getFile(fileName);
        if (!file.exists()) {
            return;
        }
        if (!file.delete()) {
            Slog.e(TAG, "Failed to delete file: " + file.getPath());
        }
    }

    @WorkerThread
    void writeTo(@NonNull String fileName, @NonNull T data) {
        final File file = getFile(fileName);
        final AtomicFile atomicFile = new AtomicFile(file);

        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = atomicFile.startWrite();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write to protobuf file.", e);
            return;
        }

        try {
            final ProtoOutputStream protoOutputStream = new ProtoOutputStream(fileOutputStream);
            protoStreamWriter().write(protoOutputStream, data);
            protoOutputStream.flush();
            atomicFile.finishWrite(fileOutputStream);
            fileOutputStream = null;
        } finally {
            // When fileInputStream is null (successful write), this will no-op.
            atomicFile.failWrite(fileOutputStream);
        }
    }

    @WorkerThread
    @Nullable
    T read(@NonNull String fileName) {
        File[] files = mRootDir.listFiles(
                pathname -> pathname.isFile() && pathname.getName().equals(fileName));
        if (files == null || files.length == 0) {
            return null;
        } else if (files.length > 1) {
            // This can't possibly happen, but validity check.
            Slog.w(TAG, "Found multiple files with the same name: " + Arrays.toString(files));
        }
        return parseFile(files[0]);
    }

    /**
     * Schedules the specified data to be flushed to a file in the future. Subsequent
     * calls for the same file before the flush occurs will replace the previous data but will not
     * reset when the flush will occur. All unique files will be flushed at the same time.
     */
    @MainThread
    synchronized void scheduleSave(@NonNull String fileName, @NonNull T data) {
        mScheduledFileDataMap.put(fileName, data);

        if (mScheduledExecutorService.isShutdown()) {
            Slog.e(TAG, "Worker is shutdown, failed to schedule data saving.");
            return;
        }

        // Skip scheduling another flush when one is pending.
        if (mScheduledFuture != null) {
            return;
        }

        mScheduledFuture = mScheduledExecutorService.schedule(this::flushScheduledData,
                DEFAULT_DISK_WRITE_DELAY, TimeUnit.MILLISECONDS);
    }

    /**
     * Saves specified data immediately on a background thread, and blocks until its completed. This
     * is useful for when device is powering off.
     */
    @MainThread
    void saveImmediately(@NonNull String fileName, @NonNull T data) {
        synchronized (this) {
            mScheduledFileDataMap.put(fileName, data);
        }
        triggerScheduledFlushEarly();
    }

    @MainThread
    private void triggerScheduledFlushEarly() {
        synchronized (this) {
            if (mScheduledFileDataMap.isEmpty() || mScheduledExecutorService.isShutdown()) {
                return;
            }
            // Cancel existing future.
            if (mScheduledFuture != null) {
                mScheduledFuture.cancel(true);
            }
        }

        // Submit flush and blocks until it completes. Blocking will prevent the device from
        // shutting down before flushing completes.
        Future<?> future = mScheduledExecutorService.submit(this::flushScheduledData);
        try {
            future.get(SHUTDOWN_DISK_WRITE_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Slog.e(TAG, "Failed to save data immediately.", e);
        }
    }

    @WorkerThread
    private synchronized void flushScheduledData() {
        if (mScheduledFileDataMap.isEmpty()) {
            mScheduledFuture = null;
            return;
        }
        for (String fileName : mScheduledFileDataMap.keySet()) {
            T data = mScheduledFileDataMap.get(fileName);
            writeTo(fileName, data);
        }
        mScheduledFileDataMap.clear();
        mScheduledFuture = null;
    }

    @WorkerThread
    @Nullable
    private T parseFile(@NonNull File file) {
        final AtomicFile atomicFile = new AtomicFile(file);
        try (FileInputStream fileInputStream = atomicFile.openRead()) {
            final ProtoInputStream protoInputStream = new ProtoInputStream(fileInputStream);
            return protoStreamReader().read(protoInputStream);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to parse protobuf file.", e);
        }
        return null;
    }

    @NonNull
    private File getFile(String fileName) {
        return new File(mRootDir, fileName);
    }

    /**
     * {@code ProtoStreamWriter} writes {@code T} fields to {@link ProtoOutputStream}.
     *
     * @param <T> is the data class representation of a protobuf.
     */
    interface ProtoStreamWriter<T> {

        /**
         * Writes {@code T} to {@link ProtoOutputStream}.
         */
        void write(@NonNull ProtoOutputStream protoOutputStream, @NonNull T data);
    }

    /**
     * {@code ProtoStreamReader} reads {@link ProtoInputStream} and translate it to {@code T}.
     *
     * @param <T> is the data class representation of a protobuf.
     */
    interface ProtoStreamReader<T> {
        /**
         * Reads {@link ProtoInputStream} and translates it to {@code T}.
         */
        @Nullable
        T read(@NonNull ProtoInputStream protoInputStream);
    }
}
