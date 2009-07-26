/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.backup;

import android.content.Context;

import java.io.FileDescriptor;
import java.io.IOException;

/** @hide */
public class BackupDataInput {
    int mBackupReader;

    private EntityHeader mHeader = new EntityHeader();
    private boolean mHeaderReady;

    private static class EntityHeader {
        String key;
        int dataSize;
    }

    public BackupDataInput(FileDescriptor fd) {
        if (fd == null) throw new NullPointerException();
        mBackupReader = ctor(fd);
        if (mBackupReader == 0) {
            throw new RuntimeException("Native initialization failed with fd=" + fd);
        }
    }

    protected void finalize() throws Throwable {
        try {
            dtor(mBackupReader);
        } finally {
            super.finalize();
        }
    }

    public boolean readNextHeader() throws IOException {
        int result = readNextHeader_native(mBackupReader, mHeader);
        if (result == 0) {
            // read successfully
            mHeaderReady = true;
            return true;
        } else if (result > 0) {
            // done
            mHeaderReady = false;
            return false;
        } else {
            // error
            mHeaderReady = false;
            throw new IOException("result=0x" + Integer.toHexString(result));
        }
    }

    public String getKey() {
        if (mHeaderReady) {
            return mHeader.key;
        } else {
            throw new IllegalStateException("mHeaderReady=false");
        }
    }

    public int getDataSize() {
        if (mHeaderReady) {
            return mHeader.dataSize;
        } else {
            throw new IllegalStateException("mHeaderReady=false");
        }
    }

    public int readEntityData(byte[] data, int offset, int size) throws IOException {
        if (mHeaderReady) {
            int result = readEntityData_native(mBackupReader, data, offset, size);
            if (result >= 0) {
                return result;
            } else {
                throw new IOException("result=0x" + Integer.toHexString(result));
            }
        } else {
            throw new IllegalStateException("mHeaderReady=false");
        }
    }

    public void skipEntityData() throws IOException {
        if (mHeaderReady) {
            int result = skipEntityData_native(mBackupReader);
            if (result >= 0) {
                return;
            } else {
                throw new IOException("result=0x" + Integer.toHexString(result));
            }
        } else {
            throw new IllegalStateException("mHeaderReady=false");
        }
    }

    private native static int ctor(FileDescriptor fd);
    private native static void dtor(int mBackupReader);

    private native int readNextHeader_native(int mBackupReader, EntityHeader entity);
    private native int readEntityData_native(int mBackupReader, byte[] data, int offset, int size);
    private native int skipEntityData_native(int mBackupReader);
}
