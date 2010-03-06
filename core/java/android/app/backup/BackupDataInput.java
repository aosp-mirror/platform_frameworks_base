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

package android.app.backup;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * STOPSHIP: document!
 */
public class BackupDataInput {
    int mBackupReader;

    private EntityHeader mHeader = new EntityHeader();
    private boolean mHeaderReady;

    private static class EntityHeader {
        String key;
        int dataSize;
    }

    /** @hide */
    public BackupDataInput(FileDescriptor fd) {
        if (fd == null) throw new NullPointerException();
        mBackupReader = ctor(fd);
        if (mBackupReader == 0) {
            throw new RuntimeException("Native initialization failed with fd=" + fd);
        }
    }

    /** @hide */
    protected void finalize() throws Throwable {
        try {
            dtor(mBackupReader);
        } finally {
            super.finalize();
        }
    }

    /**
     * Consumes the next header from the restore stream.
     *
     * @return true when there is an entity ready for consumption from the restore stream,
     *    false if the restore stream has been fully consumed.
     * @throws IOException if an error occurred while reading the restore stream
     */
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

    /**
     * Report the key associated with the current record in the restore stream
     * @return the current record's key string
     * @throws IllegalStateException if the next record header has not yet been read
     */
    public String getKey() {
        if (mHeaderReady) {
            return mHeader.key;
        } else {
            throw new IllegalStateException("mHeaderReady=false");
        }
    }

    /**
     * Report the size in bytes of the data associated with the current record in the
     * restore stream.
     *
     * @return The size of the record's raw data, in bytes
     * @throws IllegalStateException if the next record header has not yet been read
     */
    public int getDataSize() {
        if (mHeaderReady) {
            return mHeader.dataSize;
        } else {
            throw new IllegalStateException("mHeaderReady=false");
        }
    }

    /**
     * Read a record's raw data from the restore stream.  The record's header must first
     * have been processed by the {@link #readNextHeader()} method.  Multiple calls to
     * this method may be made in order to process the data in chunks; not all of it
     * must be read in a single call.
     *
     * @param data An allocated byte array of at least 'size' bytes
     * @param offset Offset within the 'data' array at which the data will be placed
     *    when read from the stream.
     * @param size The number of bytes to read in this pass.
     * @return The number of bytes of data read
     * @throws IOException if an error occurred when trying to read the restore data stream
     */
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

    /**
     * Consume the current record's data without actually reading it into a buffer
     * for further processing.  This allows a {@link android.app.backup.BackupAgent} to
     * efficiently discard obsolete or otherwise uninteresting records during the
     * restore operation.
     * 
     * @throws IOException if an error occurred when trying to read the restore data stream
     */
    public void skipEntityData() throws IOException {
        if (mHeaderReady) {
            skipEntityData_native(mBackupReader);
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
