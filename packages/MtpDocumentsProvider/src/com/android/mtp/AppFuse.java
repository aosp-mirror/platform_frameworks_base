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

import android.annotation.WorkerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.mtp.annotations.UsedByNative;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class AppFuse {
    static {
        System.loadLibrary("appfuse_jni");
    }

    private static final boolean DEBUG = false;

    /**
     * Max read amount specified at the FUSE kernel implementation.
     * The value is copied from sdcard.c.
     */
    @UsedByNative("com_android_mtp_AppFuse.cpp")
    static final int MAX_READ = 128 * 1024;

    @UsedByNative("com_android_mtp_AppFuse.cpp")
    static final int MAX_WRITE = 256 * 1024;

    private final String mName;
    private final Callback mCallback;

    /**
     * Buffer for read bytes request.
     * Don't use the buffer from the out of AppFuseMessageThread.
     */
    private byte[] mBuffer = new byte[Math.max(MAX_READ, MAX_WRITE)];

    private Thread mMessageThread;
    private ParcelFileDescriptor mDeviceFd;

    AppFuse(String name, Callback callback) {
        mName = name;
        mCallback = callback;
    }

    void mount(StorageManager storageManager) throws IOException {
        Preconditions.checkState(mDeviceFd == null);
        mDeviceFd = storageManager.mountAppFuse(mName);
        mMessageThread = new AppFuseMessageThread(mDeviceFd.dup().detachFd());
        mMessageThread.start();
    }

    @VisibleForTesting
    void close() {
        try {
            // Remote side of ParcelFileDescriptor is tracking the close of mDeviceFd, and unmount
            // the corresponding fuse file system. The mMessageThread will receive ENODEV, and
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
     * Opens a file on app fuse and returns ParcelFileDescriptor.
     *
     * @param i ID for opened file.
     * @param mode Mode for opening file.
     * @see ParcelFileDescriptor#MODE_READ_ONLY
     * @see ParcelFileDescriptor#MODE_WRITE_ONLY
     */
    public ParcelFileDescriptor openFile(int i, int mode) throws FileNotFoundException {
        Preconditions.checkArgument(
                mode == ParcelFileDescriptor.MODE_READ_ONLY ||
                mode == (ParcelFileDescriptor.MODE_WRITE_ONLY |
                         ParcelFileDescriptor.MODE_TRUNCATE));
        return ParcelFileDescriptor.open(new File(
                getMountPoint(),
                Integer.toString(i)),
                mode);
    }

    File getMountPoint() {
        return new File("/mnt/appfuse/" + Process.myUid() + "_" + mName);
    }

    static interface Callback {
        /**
         * Returns file size for the given inode.
         * @param inode
         * @return File size. Must not be negative.
         * @throws FileNotFoundException
         */
        long getFileSize(int inode) throws FileNotFoundException;

        /**
         * Returns file bytes for the give inode.
         * @param inode
         * @param offset Offset for file bytes.
         * @param size Size for file bytes.
         * @param bytes Buffer to store file bytes.
         * @return Number of read bytes. Must not be negative.
         * @throws IOException
         */
        long readObjectBytes(int inode, long offset, long size, byte[] bytes) throws IOException;

        /**
         * Handles writing bytes for the give inode.
         * @param fileHandle
         * @param inode
         * @param offset Offset for file bytes.
         * @param size Size for file bytes.
         * @param bytes Buffer to store file bytes.
         * @return Number of read bytes. Must not be negative.
         * @throws IOException
         */
        int writeObjectBytes(long fileHandle, int inode, long offset, int size, byte[] bytes)
                throws IOException, ErrnoException;

        /**
         * Flushes bytes for file handle.
         * @param fileHandle
         * @throws IOException
         * @throws ErrnoException
         */
        void flushFileHandle(long fileHandle) throws IOException, ErrnoException;

        /**
         * Closes file handle.
         * @param fileHandle
         * @throws IOException
         */
        void closeFileHandle(long fileHandle) throws IOException, ErrnoException;
    }

    @UsedByNative("com_android_mtp_AppFuse.cpp")
    @WorkerThread
    private long getFileSize(int inode) {
        try {
            return mCallback.getFileSize(inode);
        } catch (Exception error) {
            return -getErrnoFromException(error);
        }
    }

    @UsedByNative("com_android_mtp_AppFuse.cpp")
    @WorkerThread
    private long readObjectBytes(int inode, long offset, long size) {
        if (offset < 0 || size < 0 || size > MAX_READ) {
            return -OsConstants.EINVAL;
        }
        try {
            // It's OK to share the same mBuffer among requests because the requests are processed
            // by AppFuseMessageThread sequentially.
            return mCallback.readObjectBytes(inode, offset, size, mBuffer);
        } catch (Exception error) {
            return -getErrnoFromException(error);
        }
    }

    @UsedByNative("com_android_mtp_AppFuse.cpp")
    @WorkerThread
    private /* unsgined */ int writeObjectBytes(long fileHandler,
                                                int inode,
                                                /* unsigned */ long offset,
                                                /* unsigned */ int size,
                                                byte[] bytes) {
        try {
            return mCallback.writeObjectBytes(fileHandler, inode, offset, size, bytes);
        } catch (Exception error) {
            return -getErrnoFromException(error);
        }
    }

    @UsedByNative("com_android_mtp_AppFuse.cpp")
    @WorkerThread
    private int flushFileHandle(long fileHandle) {
        try {
            mCallback.flushFileHandle(fileHandle);
            return 0;
        } catch (Exception error) {
            return -getErrnoFromException(error);
        }
    }

    @UsedByNative("com_android_mtp_AppFuse.cpp")
    @WorkerThread
    private int closeFileHandle(long fileHandle) {
        try {
            mCallback.closeFileHandle(fileHandle);
            return 0;
        } catch (Exception error) {
            return -getErrnoFromException(error);
        }
    }

    private static int getErrnoFromException(Exception error) {
        if (DEBUG) {
            Log.e(MtpDocumentsProvider.TAG, "AppFuse callbacks", error);
        }
        if (error instanceof FileNotFoundException) {
            return OsConstants.ENOENT;
        } else if (error instanceof IOException) {
            return OsConstants.EIO;
        } else if (error instanceof UnsupportedOperationException) {
            return OsConstants.ENOTSUP;
        } else if (error instanceof IllegalArgumentException) {
            return OsConstants.EINVAL;
        } else {
            return OsConstants.EIO;
        }
    }

    private native void native_start_app_fuse_loop(int fd);

    private class AppFuseMessageThread extends Thread {
        /**
         * File descriptor used by native loop.
         * It's owned by native loop and does not need to close here.
         */
        private final int mRawFd;

        AppFuseMessageThread(int fd) {
            super("AppFuseMessageThread");
            mRawFd = fd;
        }

        @Override
        public void run() {
            native_start_app_fuse_loop(mRawFd);
        }
    }
}
