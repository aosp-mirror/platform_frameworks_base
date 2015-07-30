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

class PipeManager {
    final ExecutorService mExecutor;

    PipeManager() {
        this(Executors.newCachedThreadPool());
    }

    PipeManager(ExecutorService executor) {
        this.mExecutor = executor;
    }

    ParcelFileDescriptor readDocument(
            final MtpManager model,
            final Identifier identifier,
            final int expectedSize) throws IOException {
        final Task task = new Task() {
            @Override
            byte[] getBytes() throws IOException {
                // TODO: Use importFile to ParcelFileDescripter after implementing this.
                return model.getObject(
                        identifier.mDeviceId, identifier.mObjectHandle, expectedSize);
            }
        };
        mExecutor.execute(task);
        return task.getReadingFileDescriptor();
    }

    private static abstract class Task implements Runnable {
        private final ParcelFileDescriptor[] mDescriptors;

        Task() throws IOException {
            mDescriptors = ParcelFileDescriptor.createReliablePipe();
        }

        abstract byte[] getBytes() throws IOException;

        @Override
        public void run() {
            try (final ParcelFileDescriptor.AutoCloseOutputStream stream =
                    new ParcelFileDescriptor.AutoCloseOutputStream(mDescriptors[1])) {
                try {
                    final byte[] bytes = getBytes();
                    stream.write(bytes);
                } catch (IOException error) {
                    mDescriptors[1].closeWithError("Failed to load bytes.");
                    return;
                }
            } catch (IOException closeError) {
                Log.d(MtpDocumentsProvider.TAG, closeError.getMessage());
            }
        }

        ParcelFileDescriptor getReadingFileDescriptor() {
            return mDescriptors[0];
        }
    }

    void close() {
        mExecutor.shutdown();
    }
}
