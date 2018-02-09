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

import android.app.backup.BackupDataInput;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

/**
 * Shadow for {@link BackupDataInput}. Format read does NOT match implementation. To write data to
 * be read by this shadow, you should also declare shadow {@link ShadowBackupDataOutput}.
 */
@Implements(BackupDataInput.class)
public class ShadowBackupDataInput {
    private ObjectInputStream mInput;
    private int mSize;
    private String mKey;
    private boolean mHeaderReady;

    @Implementation
    public void __constructor__(FileDescriptor fd) {
        try {
            mInput = new ObjectInputStream(new FileInputStream(fd));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Implementation
    public boolean readNextHeader() throws IOException {
        mHeaderReady = false;
        try {
            mSize = mInput.readInt();
        } catch (EOFException e) {
            return false;
        }
        mKey = mInput.readUTF();
        mHeaderReady = true;
        return true;
    }

    @Implementation
    public String getKey() {
        checkHeaderReady();
        return mKey;
    }

    @Implementation
    public int getDataSize() {
        checkHeaderReady();
        return mSize;
    }

    @Implementation
    public int readEntityData(byte[] data, int offset, int size) throws IOException {
        checkHeaderReady();
        int result = mInput.read(data, offset, size);
        if (result < 0) {
            throw new IOException("result=0x" + Integer.toHexString(result));
        }
        return result;
    }

    @Implementation
    public void skipEntityData() throws IOException {
        checkHeaderReady();
        mInput.read(new byte[mSize], 0, mSize);
    }

    private void checkHeaderReady() {
        if (!mHeaderReady) {
            throw new IllegalStateException("Entity header not read");
        }
    }
}
