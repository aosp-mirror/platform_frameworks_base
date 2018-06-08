/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.testing.shadows;

import android.app.backup.BackupDataOutput;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * Shadow for {@link BackupDataOutput}. Format written does NOT match implementation. To read data
 * written with this shadow you should also declare shadow {@link ShadowBackupDataInput}.
 */
@Implements(BackupDataOutput.class)
public class ShadowBackupDataOutput {
    private long mQuota;
    private int mTransportFlags;
    private ObjectOutputStream mOutput;

    @Implementation
    public void __constructor__(FileDescriptor fd, long quota, int transportFlags) {
        try {
            mOutput = new ObjectOutputStream(new FileOutputStream(fd));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        mQuota = quota;
        mTransportFlags = transportFlags;
    }

    @Implementation
    public long getQuota() {
        return mQuota;
    }

    @Implementation
    public int getTransportFlags() {
        return mTransportFlags;
    }

    @Implementation
    public int writeEntityHeader(String key, int dataSize) throws IOException {
        final int size;
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream()) {
            writeEntityHeader(new ObjectOutputStream(byteStream), key, dataSize);
            size = byteStream.size();
        }
        writeEntityHeader(mOutput, key, dataSize);
        return size;
    }

    private void writeEntityHeader(ObjectOutputStream stream, String key, int dataSize)
            throws IOException {
        // Write the int first because readInt() throws EOFException, to know when stream ends
        stream.writeInt(dataSize);
        stream.writeUTF(key);
        stream.flush();
    }

    @Implementation
    public int writeEntityData(byte[] data, int size) throws IOException {
        mOutput.write(data, 0, size);
        mOutput.flush();
        return size;
    }
}
