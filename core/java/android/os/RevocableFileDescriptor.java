/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.os;

import android.content.Context;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Slog;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * Variant of {@link FileDescriptor} that allows its creator to revoke all
 * access to the underlying resource.
 * <p>
 * This is useful when the code that originally opened a file needs to strongly
 * assert that any clients are completely hands-off for security purposes.
 *
 * @hide
 */
public class RevocableFileDescriptor {
    private static final String TAG = "RevocableFileDescriptor";
    private static final boolean DEBUG = true;

    private FileDescriptor mInner;
    private ParcelFileDescriptor mOuter;

    private volatile boolean mRevoked;

    /** {@hide} */
    public RevocableFileDescriptor() {
    }

    /**
     * Create an instance that references the given {@link File}.
     */
    public RevocableFileDescriptor(Context context, File file) throws IOException {
        try {
            init(context, Os.open(file.getAbsolutePath(),
                    OsConstants.O_CREAT | OsConstants.O_RDWR, 0700));
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Create an instance that references the given {@link FileDescriptor}.
     */
    public RevocableFileDescriptor(Context context, FileDescriptor fd) throws IOException {
        init(context, fd);
    }

    /** {@hide} */
    public void init(Context context, FileDescriptor fd) throws IOException {
        mInner = fd;
        mOuter = context.getSystemService(StorageManager.class)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_WRITE, mCallback);
    }

    /**
     * Return a {@link ParcelFileDescriptor} which can safely be passed to an
     * untrusted process. After {@link #revoke()} is called, all operations will
     * fail with {@link OsConstants#EPERM}.
     */
    public ParcelFileDescriptor getRevocableFileDescriptor() {
        return mOuter;
    }

    /**
     * Revoke all future access to the {@link ParcelFileDescriptor} returned by
     * {@link #getRevocableFileDescriptor()}. From this point forward, all
     * operations will fail with {@link OsConstants#EPERM}.
     */
    public void revoke() {
        mRevoked = true;
        IoUtils.closeQuietly(mInner);
    }

    public boolean isRevoked() {
        return mRevoked;
    }

    private final ProxyFileDescriptorCallback mCallback = new ProxyFileDescriptorCallback() {
        private void checkRevoked() throws ErrnoException {
            if (mRevoked) {
                throw new ErrnoException(TAG, OsConstants.EPERM);
            }
        }

        @Override
        public long onGetSize() throws ErrnoException {
            checkRevoked();
            return Os.fstat(mInner).st_size;
        }

        @Override
        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            checkRevoked();
            int n = 0;
            while (n < size) {
                try {
                    n += Os.pread(mInner, data, n, size - n, offset + n);
                    break;
                } catch (InterruptedIOException e) {
                    n += e.bytesTransferred;
                }
            }
            return n;
        }

        @Override
        public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
            checkRevoked();
            int n = 0;
            while (n < size) {
                try {
                    n += Os.pwrite(mInner, data, n, size - n, offset + n);
                    break;
                } catch (InterruptedIOException e) {
                    n += e.bytesTransferred;
                }
            }
            return n;
        }

        @Override
        public void onFsync() throws ErrnoException {
            if (DEBUG) Slog.v(TAG, "onFsync()");
            checkRevoked();
            Os.fsync(mInner);
        }

        @Override
        public void onRelease() {
            if (DEBUG) Slog.v(TAG, "onRelease()");
            mRevoked = true;
            IoUtils.closeQuietly(mInner);
        }
    };
}
