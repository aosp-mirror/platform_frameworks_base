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
public class BackupDataOutput {
    int mBackupWriter;

    public static final int OP_UPDATE = 1;
    public static final int OP_DELETE = 2;

    public BackupDataOutput(FileDescriptor fd) {
        if (fd == null) throw new NullPointerException();
        mBackupWriter = ctor(fd);
        if (mBackupWriter == 0) {
            throw new RuntimeException("Native initialization failed with fd=" + fd);
        }
    }

    // A dataSize of -1 indicates that the record under this key should be deleted
    public int writeEntityHeader(String key, int dataSize) throws IOException {
        int result = writeEntityHeader_native(mBackupWriter, key, dataSize);
        if (result >= 0) {
            return result;
        } else {
            throw new IOException("result=0x" + Integer.toHexString(result));
        }
    }

    public int writeEntityData(byte[] data, int size) throws IOException {
        int result = writeEntityData_native(mBackupWriter, data, size);
        if (result >= 0) {
            return result;
        } else {
            throw new IOException("result=0x" + Integer.toHexString(result));
        }
    }

    public void setKeyPrefix(String keyPrefix) {
        setKeyPrefix_native(mBackupWriter, keyPrefix);
    }

    protected void finalize() throws Throwable {
        try {
            dtor(mBackupWriter);
        } finally {
            super.finalize();
        }
    }

    private native static int ctor(FileDescriptor fd);
    private native static void dtor(int mBackupWriter);

    private native static int writeEntityHeader_native(int mBackupWriter, String key, int dataSize);
    private native static int writeEntityData_native(int mBackupWriter, byte[] data, int size);
    private native static void setKeyPrefix_native(int mBackupWriter, String keyPrefix);
}

