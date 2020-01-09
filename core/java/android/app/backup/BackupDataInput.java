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

import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Provides the structured interface through which a {@link BackupAgent} reads
 * information from the backup data set, via its
 * {@link BackupAgent#onRestore(BackupDataInput, int, android.os.ParcelFileDescriptor) onRestore()}
 * method.  The data is presented as a set of "entities," each
 * representing one named record as previously stored by the agent's
 * {@link BackupAgent#onBackup(ParcelFileDescriptor,BackupDataOutput,ParcelFileDescriptor)
 * onBackup()} implementation.  An entity is composed of a descriptive header plus a
 * byte array that holds the raw data saved in the remote backup.
 * <p>
 * The agent must consume every entity in the data stream, otherwise the
 * restored state of the application will be incomplete.
 * <h3>Example</h3>
 * <p>
 * A typical
 * {@link BackupAgent#onRestore(BackupDataInput,int,ParcelFileDescriptor)
 * onRestore()} implementation might be structured something like this:
 * <pre>
 * public void onRestore(BackupDataInput data, int appVersionCode,
 *                       ParcelFileDescriptor newState) {
 *     while (data.readNextHeader()) {
 *         String key = data.getKey();
 *         int dataSize = data.getDataSize();
 *
 *         if (key.equals(MY_BACKUP_KEY_ONE)) {
 *             // process this kind of record here
 *             byte[] buffer = new byte[dataSize];
 *             data.readEntityData(buffer, 0, dataSize); // reads the entire entity at once
 *
 *             // now 'buffer' holds the raw data and can be processed however
 *             // the agent wishes
 *             processBackupKeyOne(buffer);
 *         } else if (key.equals(MY_BACKUP_KEY_TO_IGNORE) {
 *             // a key we recognize but wish to discard
 *             data.skipEntityData();
 *         } // ... etc.
 *    }
 * }</pre>
 */
public class BackupDataInput {
    long mBackupReader;

    private EntityHeader mHeader = new EntityHeader();
    private boolean mHeaderReady;

    private static class EntityHeader {
        @UnsupportedAppUsage
        String key;
        @UnsupportedAppUsage
        int dataSize;
    }

    /** @hide */
    @SystemApi
    public BackupDataInput(FileDescriptor fd) {
        if (fd == null) throw new NullPointerException();
        mBackupReader = ctor(fd);
        if (mBackupReader == 0) {
            throw new RuntimeException("Native initialization failed with fd=" + fd);
        }
    }

    /** @hide */
    @Override
    protected void finalize() throws Throwable {
        try {
            dtor(mBackupReader);
        } finally {
            super.finalize();
        }
    }

    /**
     * Extract the next entity header from the restore stream.  After this method
     * return success, the {@link #getKey()} and {@link #getDataSize()} methods can
     * be used to inspect the entity that is now available for processing.
     *
     * @return <code>true</code> when there is an entity ready for consumption from the
     *    restore stream, <code>false</code> if the restore stream has been fully consumed.
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
            throw new IOException("failed: 0x" + Integer.toHexString(result));
        }
    }

    /**
     * Report the key associated with the current entity in the restore stream
     * @return the current entity's key string
     * @throws IllegalStateException if the next record header has not yet been read
     */
    public String getKey() {
        if (mHeaderReady) {
            return mHeader.key;
        } else {
            throw new IllegalStateException("Entity header not read");
        }
    }

    /**
     * Report the size in bytes of the data associated with the current entity in the
     * restore stream.
     *
     * @return The size of the record's raw data, in bytes
     * @throws IllegalStateException if the next record header has not yet been read
     */
    public int getDataSize() {
        if (mHeaderReady) {
            return mHeader.dataSize;
        } else {
            throw new IllegalStateException("Entity header not read");
        }
    }

    /**
     * Read a record's raw data from the restore stream.  The record's header must first
     * have been processed by the {@link #readNextHeader()} method.  Multiple calls to
     * this method may be made in order to process the data in chunks; not all of it
     * must be read in a single call.  Once all of the raw data for the current entity
     * has been read, further calls to this method will simply return zero.
     *
     * @param data An allocated byte array of at least 'size' bytes
     * @param offset Offset within the 'data' array at which the data will be placed
     *    when read from the stream
     * @param size The number of bytes to read in this pass
     * @return The number of bytes of data read.  Once all of the data for this entity
     *    has been read, further calls to this method will return zero.
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
            throw new IllegalStateException("Entity header not read");
        }
    }

    /**
     * Consume the current entity's data without extracting it into a buffer
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
            throw new IllegalStateException("Entity header not read");
        }
    }

    private native static long ctor(FileDescriptor fd);
    private native static void dtor(long mBackupReader);

    private native int readNextHeader_native(long mBackupReader, EntityHeader entity);
    private native int readEntityData_native(long mBackupReader, byte[] data, int offset, int size);
    private native int skipEntityData_native(long mBackupReader);
}
