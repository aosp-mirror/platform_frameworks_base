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

package com.android.internal.os;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Helper class to get byte data through a pipe from a client app. Also {@see TransferPipe}.
 */
public class ByteTransferPipe extends TransferPipe {
    static final String TAG = "ByteTransferPipe";

    private ByteArrayOutputStream mOutputStream;

    public ByteTransferPipe() throws IOException {
        super();
    }

    public ByteTransferPipe(String bufferPrefix) throws IOException {
        super(bufferPrefix, "ByteTransferPipe");
    }

    @Override
    protected OutputStream getNewOutputStream() {
        mOutputStream = new ByteArrayOutputStream();
        return mOutputStream;
    }

    public byte[] get() throws IOException {
        go(null);
        return mOutputStream.toByteArray();
    }
}
