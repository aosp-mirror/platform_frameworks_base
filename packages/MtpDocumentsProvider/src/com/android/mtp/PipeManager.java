/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class PipeManager {
    /**
     * Milliseconds we wait for background thread when pausing.
     */
    private final static long AWAIT_TERMINATION_TIMEOUT = 2000;

    final ExecutorService mExecutor;
    final MtpDatabase mDatabase;

    PipeManager(MtpDatabase database) {
        this(database, Executors.newSingleThreadExecutor());
    }

    PipeManager(MtpDatabase database, ExecutorService executor) {
        this.mDatabase = database;
        this.mExecutor = executor;
    }

    ParcelFileDescriptor readDocument(MtpManager model, Identifier identifier) throws IOException {
        final Task task = new ImportFileTask(model, identifier);
        mExecutor.execute(task);
        return task.getReadingFileDescriptor();
    }

    ParcelFileDescriptor readThumbnail(MtpManager model, Identifier identifier) throws IOException {
        final Task task = new GetThumbnailTask(model, identifier);
        mExecutor.execute(task);
        return task.getReadingFileDescriptor();
    }

    private static abstract class Task implements Runnable {
        protected final MtpManager mManager;
        protected final Identifier mIdentifier;
        protected final ParcelFileDescriptor[] mDescriptors;

        Task(MtpManager manager, Identifier identifier) throws IOException {
            mManager = manager;
            mIdentifier = identifier;
            mDescriptors = ParcelFileDescriptor.createReliablePipe();
        }

        ParcelFileDescriptor getReadingFileDescriptor() {
            return mDescriptors[0];
        }
    }

    private static class ImportFileTask extends Task {
        ImportFileTask(MtpManager model, Identifier identifier) throws IOException {
            super(model, identifier);
        }

        @Override
        public void run() {
            try {
                mManager.importFile(
                        mIdentifier.mDeviceId, mIdentifier.mObjectHandle, mDescriptors[1]);
                mDescriptors[1].close();
            } catch (IOException error) {
                try {
                    mDescriptors[1].closeWithError("Failed to stream a file.");
                } catch (IOException closeError) {
                    Log.w(MtpDocumentsProvider.TAG, closeError.getMessage());
                }
            }
        }
    }

    private static class GetThumbnailTask extends Task {
        GetThumbnailTask(MtpManager model, Identifier identifier) throws IOException {
            super(model, identifier);
        }

        @Override
        public void run() {
            try {
                try (final ParcelFileDescriptor.AutoCloseOutputStream stream =
                        new ParcelFileDescriptor.AutoCloseOutputStream(mDescriptors[1])) {
                    try {
                        stream.write(mManager.getThumbnail(
                                mIdentifier.mDeviceId, mIdentifier.mObjectHandle));
                    } catch (IOException error) {
                        mDescriptors[1].closeWithError("Failed to stream a thumbnail.");
                    }
                }
            } catch (IOException closeError) {
                Log.w(MtpDocumentsProvider.TAG, closeError.getMessage());
            }
        }
    }

    boolean close() throws InterruptedException {
        mExecutor.shutdownNow();
        return mExecutor.awaitTermination(AWAIT_TERMINATION_TIMEOUT, TimeUnit.MILLISECONDS);
    }
}
