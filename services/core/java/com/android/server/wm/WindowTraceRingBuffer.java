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

import android.util.proto.ProtoOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A ring buffer to store the {@code #size size} bytes of window trace data.
 * The buffer operates on a trace entry level, that is, if the new trace data is larger than the
 * available buffer space, the buffer will discard as many full trace entries as necessary to fit
 * the new trace.
 */
class WindowTraceRingBuffer extends WindowTraceBuffer {
    WindowTraceRingBuffer(int size, File traceFile) throws IOException {
        super(size, traceFile);
    }

    @Override
    boolean canAdd(int protoLength) {
        long availableSpace = getAvailableSpace();

        while (availableSpace < protoLength) {
            discardOldest();
            availableSpace = getAvailableSpace();
        }

        return true;
    }

    @Override
    void writeTraceToFile() throws IOException {
        synchronized (mBufferLock) {
            try (OutputStream os = new FileOutputStream(mTraceFile, true)) {
                while (!mBuffer.isEmpty()) {
                    ProtoOutputStream proto = mBuffer.poll();
                    mBufferSize -= proto.getRawSize();
                    byte[] protoBytes = proto.getBytes();
                    os.write(protoBytes);
                }
            }
        }
    }

    private void discardOldest() {
        ProtoOutputStream item = mBuffer.poll();
        if (item == null) {
            throw new IllegalStateException("No element to discard from buffer");
        }
        mBufferSize -= item.getRawSize();
    }
}
