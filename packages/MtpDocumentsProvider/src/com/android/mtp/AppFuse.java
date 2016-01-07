/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.storage.StorageManager;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * TODO: Remove VisibleForTesting class.
 */
@VisibleForTesting
public class AppFuse {
    static {
        System.loadLibrary("appfuse_jni");
    }

    private final String mName;
    private final Callback mCallback;
    private final Thread mMessageThread;
    private ParcelFileDescriptor mDeviceFd;

    @VisibleForTesting
    AppFuse(String name, Callback callback) {
        mName = name;
        mCallback = callback;
        mMessageThread = new Thread(new Runnable() {
            @Override
            public void run() {
                native_start_app_fuse_loop(mDeviceFd.getFd());
            }
        });
    }

    @VisibleForTesting
    void mount(StorageManager storageManager) {
        mDeviceFd = storageManager.mountAppFuse(mName);
        mMessageThread.start();
    }

    @VisibleForTesting
    void close() {
        try {
            // Remote side of ParcelFileDescriptor is tracking the close of mDeviceFd, and unmount
            // the corresponding fuse file system. The mMessageThread will receive FUSE_FORGET, and
            // then terminate itself.
            mDeviceFd.close();
            mMessageThread.join();
        } catch (IOException exp) {
            Log.e(MtpDocumentsProvider.TAG, "Failed to close device FD.", exp);
        } catch (InterruptedException exp) {
            Log.e(MtpDocumentsProvider.TAG, "Failed to terminate message thread.", exp);
        }
    }

    /**
     * @param i
     * @throws FileNotFoundException
     */
    @VisibleForTesting
    public ParcelFileDescriptor openFile(int i) throws FileNotFoundException {
        return ParcelFileDescriptor.open(new File(
                getMountPoint(),
                Integer.toString(i)),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @VisibleForTesting
    File getMountPoint() {
        return new File("/mnt/appfuse/" + Process.myUid() + "_" + mName);
    }

    static interface Callback {
        long getFileSize(int inode) throws FileNotFoundException;
        byte[] getObjectBytes(int inode, long offset, int size) throws IOException;
    }

    @VisibleForTesting
    private long getFileSize(int inode) {
        try {
            return mCallback.getFileSize(inode);
        } catch (IOException e) {
            return -1;
        }
    }

    @VisibleForTesting
    private byte[] getObjectBytes(int inode, long offset, int size) {
        try {
            return mCallback.getObjectBytes(inode, offset, size);
        } catch (IOException e) {
            return null;
        }
    }

    private native boolean native_start_app_fuse_loop(int fd);
}
