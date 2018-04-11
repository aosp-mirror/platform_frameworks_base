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
import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Provides the structured interface through which a {@link BackupAgent} commits
 * information to the backup data set, via its {@link
 * BackupAgent#onBackup(ParcelFileDescriptor,BackupDataOutput,ParcelFileDescriptor)
 * onBackup()} method.  Data written for backup is presented
 * as a set of "entities," key/value pairs in which each binary data record "value" is
 * named with a string "key."
 * <p>
 * To commit a data record to the backup transport, the agent's
 * {@link BackupAgent#onBackup(ParcelFileDescriptor,BackupDataOutput,ParcelFileDescriptor)
 * onBackup()} method first writes an "entity header" that supplies the key string for the record
 * and the total size of the binary value for the record.  After the header has been
 * written, the agent then writes the binary entity value itself.  The entity value can
 * be written in multiple chunks if desired, as long as the total count of bytes written
 * matches what was supplied to {@link #writeEntityHeader(String, int) writeEntityHeader()}.
 * <p>
 * Entity key strings are considered to be unique within a given application's backup
 * data set. If a backup agent writes a new entity under an existing key string, its value will
 * replace any previous value in the transport's remote data store.  You can remove a record
 * entirely from the remote data set by writing a new entity header using the
 * existing record's key, but supplying a negative <code>dataSize</code> parameter.
 * When you do so, the agent does not need to call {@link #writeEntityData(byte[], int)}.
 * <h3>Example</h3>
 * <p>
 * Here is an example illustrating a way to back up the value of a String variable
 * called <code>mStringToBackUp</code>:
 * <pre>
 * static final String MY_STRING_KEY = "storedstring";
 *
 * public void {@link BackupAgent#onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor) onBackup(ParcelFileDescriptor oldState, BackupDataOutput data, ParcelFileDescriptor newState)}
 *         throws IOException {
 *     ...
 *     byte[] stringBytes = mStringToBackUp.getBytes();
 *     data.writeEntityHeader(MY_STRING_KEY, stringBytes.length);
 *     data.writeEntityData(stringBytes, stringBytes.length);
 *     ...
 * }</pre>
 *
 * @see BackupAgent
 */
public class BackupDataOutput {

    private final long mQuota;
    private final int mTransportFlags;

    long mBackupWriter;

    /**
     * Construct a BackupDataOutput purely for data-stream manipulation.  This instance will
     * not report usable quota information.
     * @hide */
    @SystemApi
    public BackupDataOutput(FileDescriptor fd) {
        this(fd, /*quota=*/ -1, /*transportFlags=*/ 0);
    }

    /** @hide */
    @SystemApi
    public BackupDataOutput(FileDescriptor fd, long quota) {
        this(fd, quota, /*transportFlags=*/ 0);
    }

    /** @hide */
    public BackupDataOutput(FileDescriptor fd, long quota, int transportFlags) {
        if (fd == null) throw new NullPointerException();
        mQuota = quota;
        mTransportFlags = transportFlags;
        mBackupWriter = ctor(fd);
        if (mBackupWriter == 0) {
            throw new RuntimeException("Native initialization failed with fd=" + fd);
        }
    }

    /**
     * Returns the quota in bytes for the application's current backup operation.  The
     * value can vary for each operation.
     *
     * @see FullBackupDataOutput#getQuota()
     */
    public long getQuota() {
        return mQuota;
    }

    /**
     * Returns flags with additional information about the backup transport. For supported flags see
     * {@link android.app.backup.BackupAgent}.
     *
     * <p>Returns the same flags that {@link BackupTransport#getTransportFlags()} returns.
     *
     * @see BackupAgent#FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED
     * @see BackupAgent#FLAG_DEVICE_TO_DEVICE_TRANSFER
     * @see FullBackupDataOutput#getTransportFlags()
     */
    public int getTransportFlags() {
        return mTransportFlags;
    }

    /**
     * Mark the beginning of one record in the backup data stream. This must be called before
     * {@link #writeEntityData}.
     * @param key A string key that uniquely identifies the data record within the application.
     *    Keys whose first character is \uFF00 or higher are not valid.
     * @param dataSize The size in bytes of this record's data.  Passing a dataSize
     *    of -1 indicates that the record under this key should be deleted.
     * @return The number of bytes written to the backup stream
     * @throws IOException if the write failed
     */
    public int writeEntityHeader(String key, int dataSize) throws IOException {
        int result = writeEntityHeader_native(mBackupWriter, key, dataSize);
        if (result >= 0) {
            return result;
        } else {
            throw new IOException("result=0x" + Integer.toHexString(result));
        }
    }

    /**
     * Write a chunk of data under the current entity to the backup transport.
     * @param data A raw data buffer to send
     * @param size The number of bytes to be sent in this chunk
     * @return the number of bytes written
     * @throws IOException if the write failed
     */
    public int writeEntityData(byte[] data, int size) throws IOException {
        int result = writeEntityData_native(mBackupWriter, data, size);
        if (result >= 0) {
            return result;
        } else {
            throw new IOException("result=0x" + Integer.toHexString(result));
        }
    }

    /** @hide */
    public void setKeyPrefix(String keyPrefix) {
        setKeyPrefix_native(mBackupWriter, keyPrefix);
    }

    /** @hide */
    @Override
    protected void finalize() throws Throwable {
        try {
            dtor(mBackupWriter);
        } finally {
            super.finalize();
        }
    }

    private native static long ctor(FileDescriptor fd);
    private native static void dtor(long mBackupWriter);

    private native static int writeEntityHeader_native(long mBackupWriter, String key, int dataSize);
    private native static int writeEntityData_native(long mBackupWriter, byte[] data, int size);
    private native static void setKeyPrefix_native(long mBackupWriter, String keyPrefix);
}

