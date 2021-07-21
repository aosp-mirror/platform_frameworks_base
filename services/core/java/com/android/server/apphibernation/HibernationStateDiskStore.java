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

package com.android.server.apphibernation;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.text.format.DateUtils;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Disk store utility class for hibernation states.
 *
 * @param <T> the type of hibernation state data
 */
class HibernationStateDiskStore<T> {
    private static final String TAG = "HibernationStateDiskStore";

    // Time to wait before actually writing. Saves extra writes if data changes come in batches.
    private static final long DISK_WRITE_DELAY = 1L * DateUtils.MINUTE_IN_MILLIS;
    private static final String STATES_FILE_NAME = "states";

    private final File mHibernationFile;
    private final ScheduledExecutorService mExecutorService;
    private final ProtoReadWriter<List<T>> mProtoReadWriter;
    private List<T> mScheduledStatesToWrite = new ArrayList<>();
    private ScheduledFuture<?> mFuture;

    /**
     * Initialize a disk store for hibernation states in the given directory.
     *
     * @param hibernationDir directory to write/read states file
     * @param readWriter writer/reader of states proto
     * @param executorService scheduled executor for writing data
     */
    HibernationStateDiskStore(@NonNull File hibernationDir,
            @NonNull ProtoReadWriter<List<T>> readWriter,
            @NonNull ScheduledExecutorService executorService) {
        this(hibernationDir, readWriter, executorService, STATES_FILE_NAME);
    }

    @VisibleForTesting
    HibernationStateDiskStore(@NonNull File hibernationDir,
            @NonNull ProtoReadWriter<List<T>> readWriter,
            @NonNull ScheduledExecutorService executorService,
            @NonNull String fileName) {
        mHibernationFile = new File(hibernationDir, fileName);
        mExecutorService = executorService;
        mProtoReadWriter = readWriter;
    }

    /**
     * Schedule a full write of all the hibernation states to the file on disk. Does not run
     * immediately and subsequent writes override previous ones.
     *
     * @param hibernationStates list of hibernation states to write to disk
     */
    void scheduleWriteHibernationStates(@NonNull List<T> hibernationStates) {
        synchronized (this) {
            mScheduledStatesToWrite = hibernationStates;
            if (mExecutorService.isShutdown()) {
                Slog.e(TAG, "Scheduled executor service is shut down.");
                return;
            }

            // Already have write scheduled
            if (mFuture != null) {
                Slog.i(TAG, "Write already scheduled. Skipping schedule.");
                return;
            }

            mFuture = mExecutorService.schedule(this::writeHibernationStates, DISK_WRITE_DELAY,
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Read hibernation states from disk.
     *
     * @return the parsed list of hibernation states, null if file does not exist
     */
    @Nullable
    @WorkerThread
    List<T> readHibernationStates() {
        synchronized (this) {
            if (!mHibernationFile.exists()) {
                Slog.i(TAG, "No hibernation file on disk for file " + mHibernationFile.getPath());
                return null;
            }
            AtomicFile atomicFile = new AtomicFile(mHibernationFile);

            try {
                FileInputStream inputStream = atomicFile.openRead();
                ProtoInputStream protoInputStream = new ProtoInputStream(inputStream);
                return mProtoReadWriter.readFromProto(protoInputStream);
            } catch (IOException e) {
                Slog.e(TAG, "Failed to read states protobuf.", e);
                return null;
            }
        }
    }

    @WorkerThread
    private void writeHibernationStates() {
        synchronized (this) {
            writeStateProto(mScheduledStatesToWrite);
            mScheduledStatesToWrite.clear();
            mFuture = null;
        }
    }

    @WorkerThread
    private void writeStateProto(List<T> states) {
        AtomicFile atomicFile = new AtomicFile(mHibernationFile);

        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = atomicFile.startWrite();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to start write to states protobuf.", e);
            return;
        }

        try {
            ProtoOutputStream protoOutputStream = new ProtoOutputStream(fileOutputStream);
            mProtoReadWriter.writeToProto(protoOutputStream, states);
            protoOutputStream.flush();
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to finish write to states protobuf.", e);
            atomicFile.failWrite(fileOutputStream);
        }
    }
}
