/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.powerstats;

import android.content.Context;
import android.util.Log;

import com.android.internal.util.FileRotator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PowerStatsDataStorage implements the on-device storage cache for energy
 * data.  This data must be persisted across boot cycles so we store it
 * on-device.  Versioning of this data is handled by deleting any data that
 * does not match the current version.  The cache is implemented as a circular
 * buffer using the FileRotator class in android.util.  We maintain 48 hours
 * worth of logs in 12 files (4 hours each).
 */
public class PowerStatsDataStorage {
    private static final String TAG = PowerStatsDataStorage.class.getSimpleName();

    private static final long MILLISECONDS_PER_HOUR = 1000 * 60 * 60;
    // Rotate files every 4 hours.
    private static final long ROTATE_AGE_MILLIS = 4 * MILLISECONDS_PER_HOUR;
    // Store 48 hours worth of data.
    private static final long DELETE_AGE_MILLIS = 48 * MILLISECONDS_PER_HOUR;

    private final ReentrantLock mLock = new ReentrantLock();
    private File mDataStorageDir;
    private final FileRotator mFileRotator;

    private static class DataElement {
        private static final int LENGTH_FIELD_WIDTH = 4;
        private static final int MAX_DATA_ELEMENT_SIZE = 1000;

        private byte[] mData;

        private byte[] toByteArray() throws IOException {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(ByteBuffer.allocate(LENGTH_FIELD_WIDTH).putInt(mData.length).array());
            data.write(mData);
            return data.toByteArray();
        }

        protected byte[] getData() {
            return mData;
        }

        private DataElement(byte[] data) {
            mData = data;
        }

        private DataElement(InputStream in) throws IOException {
            byte[] lengthBytes = new byte[LENGTH_FIELD_WIDTH];
            int bytesRead = in.read(lengthBytes);
            mData = new byte[0];

            if (bytesRead == LENGTH_FIELD_WIDTH) {
                int length = ByteBuffer.wrap(lengthBytes).getInt();

                if (0 < length && length < MAX_DATA_ELEMENT_SIZE) {
                    mData = new byte[length];
                    bytesRead = in.read(mData);

                    if (bytesRead != length) {
                        throw new IOException("Invalid bytes read, expected: " + length
                            + ", actual: " + bytesRead);
                    }
                } else {
                    throw new IOException("DataElement size is invalid: " + length);
                }
            } else {
                throw new IOException("Did not read " + LENGTH_FIELD_WIDTH + " bytes (" + bytesRead
                    + ")");
            }
        }
    }

    /**
     * Used by external classes to read DataElements from on-device storage.
     * This callback is passed in to the read() function and is called for
     * each DataElement read from on-device storage.
     */
    public interface DataElementReadCallback {
        /**
         * When performing a read of the on-device storage this callback
         * must be passed in to the read function.  The function will be
         * called for each DataElement read from on-device storage.
         *
         * @param data Byte array containing a DataElement payload.
         */
        void onReadDataElement(byte[] data);
    }

    private static class DataReader implements FileRotator.Reader {
        private DataElementReadCallback mCallback;

        DataReader(DataElementReadCallback callback) {
            mCallback = callback;
        }

        @Override
        public void read(InputStream in) throws IOException {
            while (in.available() > 0) {
                try {
                    DataElement dataElement = new DataElement(in);
                    mCallback.onReadDataElement(dataElement.getData());
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read from storage. " + e.getMessage());
                }
            }
        }
    }

    private static class DataRewriter implements FileRotator.Rewriter {
        byte[] mActiveFileData;
        byte[] mNewData;

        DataRewriter(byte[] data) {
            mActiveFileData = new byte[0];
            mNewData = data;
        }

        @Override
        public void reset() {
            // ignored
        }

        @Override
        public void read(InputStream in) throws IOException {
            mActiveFileData = new byte[in.available()];
            in.read(mActiveFileData);
        }

        @Override
        public boolean shouldWrite() {
            return true;
        }

        @Override
        public void write(OutputStream out) throws IOException {
            out.write(mActiveFileData);
            out.write(mNewData);
        }
    }

    public PowerStatsDataStorage(Context context, File dataStoragePath,
            String dataStorageFilename) {
        mDataStorageDir = dataStoragePath;

        if (!mDataStorageDir.exists() && !mDataStorageDir.mkdirs()) {
            Log.wtf(TAG, "mDataStorageDir does not exist: " + mDataStorageDir.getPath());
            mFileRotator = null;
        } else {
            // Delete files written with an old version number.  The version is included in the
            // filename, so any files that don't match the current version number can be deleted.
            File[] files = mDataStorageDir.listFiles();
            for (int i = 0; i < files.length; i++) {
                if (!files[i].getName().matches(dataStorageFilename + "(.*)")) {
                    files[i].delete();
                }
            }

            mFileRotator = new FileRotator(mDataStorageDir,
                                           dataStorageFilename,
                                           ROTATE_AGE_MILLIS,
                                           DELETE_AGE_MILLIS);
        }
    }

    /**
     * Writes data stored in PowerStatsDataStorage to a file descriptor.
     *
     * @param data Byte array to write to on-device storage.  Byte array is
     *             converted to a DataElement which prefixes the payload with
     *             the data length.  The DataElement is then converted to a byte
     *             array and written to on-device storage.
     */
    public void write(byte[] data) {
        if (data.length > 0) {
            mLock.lock();

            long currentTimeMillis = System.currentTimeMillis();
            try {
                DataElement dataElement = new DataElement(data);
                mFileRotator.rewriteActive(new DataRewriter(dataElement.toByteArray()),
                        currentTimeMillis);
                mFileRotator.maybeRotate(currentTimeMillis);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write to on-device storage: " + e);
            }

            mLock.unlock();
        }
    }

    /**
     * Reads all DataElements stored in on-device storage.  For each
     * DataElement retrieved from on-device storage, callback is called.
     */
    public void read(DataElementReadCallback callback) throws IOException {
        mFileRotator.readMatching(new DataReader(callback), Long.MIN_VALUE, Long.MAX_VALUE);
    }
}
