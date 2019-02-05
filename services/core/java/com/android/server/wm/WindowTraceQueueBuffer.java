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

import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A buffer structure backed by a {@link java.util.concurrent.BlockingQueue} to store the first
 * {@code #size size} bytes of window trace elements.
 * Once the buffer is full it will no longer accepts new elements.
 */
class WindowTraceQueueBuffer extends WindowTraceBuffer {
    private static final String TAG = "WindowTracing";

    private Thread mConsumerThread;
    private boolean mCancel;

    @VisibleForTesting
    WindowTraceQueueBuffer(int size, File traceFile, boolean startConsumerThread)
            throws IOException {
        super(size, traceFile);
        if (startConsumerThread) {
            initializeConsumerThread();
        }
    }

    WindowTraceQueueBuffer(int size, File traceFile) throws IOException {
        this(size, traceFile, !IS_USER);
    }

    private void initializeConsumerThread() {
        mCancel = false;
        mConsumerThread = new Thread(() -> {
            try {
                loop();
            } catch (InterruptedException e) {
                Log.i(TAG, "Interrupting trace consumer thread");
            } catch (IOException e) {
                Log.e(TAG, "Failed to execute trace consumer thread", e);
            }
        }, "window_tracing");
        mConsumerThread.start();
    }

    private void loop() throws IOException, InterruptedException {
        while (!mCancel) {
            ProtoOutputStream proto;
            synchronized (mBufferLock) {
                mBufferLock.wait();
                proto = mBuffer.poll();
                if (proto != null) {
                    mBufferSize -= proto.getRawSize();
                }
            }
            if (proto != null) {
                try (OutputStream os = new FileOutputStream(mTraceFile, true)) {
                    byte[] protoBytes = proto.getBytes();
                    os.write(protoBytes);
                }
            }
        }
    }

    @Override
    boolean canAdd(int protoLength) {
        long availableSpace = getAvailableSpace();
        return availableSpace >= protoLength;
    }

    @Override
    void writeTraceToFile() throws InterruptedException {
        synchronized (mBufferLock) {
            mCancel = true;
            mBufferLock.notify();
        }
        if (mConsumerThread != null) {
            mConsumerThread.join();
            mConsumerThread = null;
        }
    }
}
