/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Build.IS_USER;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;

/**
 * A buffer structure backed by a {@link java.util.concurrent.BlockingQueue} to store the first
 * {@code #size size} bytes of window trace elements.
 * Once the buffer is full it will no longer accepts new elements.
 */
class WindowTraceQueueBuffer extends WindowTraceBuffer {
    private Thread mWriterThread;
    private boolean mCancel;

    @VisibleForTesting
    WindowTraceQueueBuffer(int size, File traceFile, boolean startWriterThread) throws IOException {
        super(size, traceFile);
        if (startWriterThread) {
            initializeWriterThread();
        }
    }

    WindowTraceQueueBuffer(int size, File traceFile) throws IOException {
        this(size, traceFile, !IS_USER);
    }

    private void initializeWriterThread() {
        mCancel = false;
        mWriterThread = new Thread(() -> {
            try {
                loop();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to execute trace write loop thread", e);
            }
        }, "window_tracing");
        mWriterThread.start();
    }

    private void loop() throws IOException {
        while (!mCancel) {
            writeNextBufferElementToFile();
        }
    }

    private void restartWriterThread() throws InterruptedException {
        if (mWriterThread != null) {
            mCancel = true;
            mWriterThread.interrupt();
            mWriterThread.join();
            initializeWriterThread();
        }
    }

    @Override
    boolean canAdd(byte[] protoBytes) {
        long availableSpace = getAvailableSpace();
        return availableSpace >= protoBytes.length;
    }

    @Override
    void writeToDisk() throws InterruptedException {
        while (!mBuffer.isEmpty()) {
            mBufferSizeLock.wait();
            mBufferSizeLock.notify();
        }
        restartWriterThread();
    }
}
