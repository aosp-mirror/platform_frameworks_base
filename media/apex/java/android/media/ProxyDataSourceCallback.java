/*
 * Copyright 2019 The Android Open Source Project
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

package android.media;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * A DataSourceCallback that is backed by a ParcelFileDescriptor.
 */
class ProxyDataSourceCallback extends DataSourceCallback {
    private static final String TAG = "TestDataSourceCallback";

    ParcelFileDescriptor mPFD;
    FileDescriptor mFD;

    ProxyDataSourceCallback(ParcelFileDescriptor pfd) throws IOException {
        mPFD = pfd.dup();
        mFD = mPFD.getFileDescriptor();
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size)
            throws IOException {
        try {
            Os.lseek(mFD, position, OsConstants.SEEK_SET);
            int ret = Os.read(mFD, buffer, offset, size);
            return (ret == 0) ? END_OF_STREAM : ret;
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    @Override
    public synchronized long getSize() throws IOException {
        return mPFD.getStatSize();
    }

    @Override
    public synchronized void close() {
        try {
            mPFD.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to close the PFD.", e);
        }
    }
}

