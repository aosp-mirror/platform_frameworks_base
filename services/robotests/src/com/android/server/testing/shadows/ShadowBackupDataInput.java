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
import org.robolectric.annotation.Resetter;

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
    private static boolean sReadNextHeaderThrow = false;

    public static void throwInNextHeaderRead() {
        sReadNextHeaderThrow = true;
    }

    @Resetter
    public static void reset() {
        sReadNextHeaderThrow = false;
    }

    private FileDescriptor mFileDescriptor;
    private ObjectInputStream mInput;
    private int mSize;
    private String mKey;
    private boolean mHeaderReady;

    @Implementation
    protected void __constructor__(FileDescriptor fd) {
        mFileDescriptor = fd;
    }

    @Implementation
    protected boolean readNextHeader() throws IOException {
        if (sReadNextHeaderThrow) {
            sReadNextHeaderThrow = false;
            throw new IOException("Fake exception");
        }
        mHeaderReady = false;
        try {
            ensureInput();
            mSize = mInput.readInt();
        } catch (EOFException e) {
            return false;
        }
        mKey = mInput.readUTF();
        mHeaderReady = true;
        return true;
    }

    @Implementation
    protected String getKey() {
        checkHeaderReady();
        return mKey;
    }

    @Implementation
    protected int getDataSize() {
        checkHeaderReady();
        return mSize;
    }

    @Implementation
    protected int readEntityData(byte[] data, int offset, int size) throws IOException {
        checkHeaderReady();
        int result = mInput.read(data, offset, size);
        if (result < 0) {
            throw new IOException("result=0x" + Integer.toHexString(result));
        }
        return result;
    }

    @Implementation
    protected void skipEntityData() throws IOException {
        checkHeaderReady();
        mInput.read(new byte[mSize], 0, mSize);
    }

    private void checkHeaderReady() {
        if (!mHeaderReady) {
            throw new IllegalStateException("Entity header not read");
        }
    }

    /**
     * Lazily initializing input to avoid throwing exception when stream is completely empty in
     * constructor (Java Object IO writes/reads some header data).
     *
     * @throws EOFException When the input is empty.
     */
    private void ensureInput() throws EOFException {
        if (mInput == null) {
            try {
                mInput = new ObjectInputStream(new FileInputStream(mFileDescriptor));
            } catch (EOFException e) {
                throw e;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }
}
