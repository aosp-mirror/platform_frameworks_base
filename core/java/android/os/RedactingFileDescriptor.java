/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * Variant of {@link FileDescriptor} that allows its creator to specify regions
 * that should be redacted (appearing as zeros to the reader).
 *
 * @hide
 */
public class RedactingFileDescriptor {
    private static final String TAG = "RedactingFileDescriptor";
    private static final boolean DEBUG = true;

    private final long[] mRedactRanges;

    private FileDescriptor mInner = null;
    private ParcelFileDescriptor mOuter = null;

    private RedactingFileDescriptor(Context context, File file, long[] redactRanges)
            throws IOException {
        mRedactRanges = checkRangesArgument(redactRanges);

        try {
            try {
                mInner = Os.open(file.getAbsolutePath(), OsConstants.O_RDONLY, 0);
                mOuter = context.getSystemService(StorageManager.class)
                        .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, mCallback);
            } catch (ErrnoException e) {
                throw e.rethrowAsIOException();
            }
        } catch (IOException e) {
            IoUtils.closeQuietly(mInner);
            IoUtils.closeQuietly(mOuter);
            throw e;
        }
    }

    private static long[] checkRangesArgument(long[] ranges) {
        if (ranges.length % 2 != 0) {
            throw new IllegalArgumentException();
        }
        for (int i = 0; i < ranges.length - 1; i += 2) {
            if (ranges[i] > ranges[i + 1]) {
                throw new IllegalArgumentException();
            }
        }
        return ranges;
    }

    /**
     * Open the given {@link File} and returns a {@link ParcelFileDescriptor}
     * that offers a redacted, read-only view of the underlying data.
     *
     * @param file The underlying file to open.
     * @param redactRanges List of file offsets that should be redacted, stored
     *            as {@code [start1, end1, start2, end2, ...]}. Start values are
     *            inclusive and end values are exclusive.
     */
    public static ParcelFileDescriptor open(Context context, File file, long[] redactRanges)
            throws IOException {
        return new RedactingFileDescriptor(context, file, redactRanges).mOuter;
    }

    private final ProxyFileDescriptorCallback mCallback = new ProxyFileDescriptorCallback() {
        @Override
        public long onGetSize() throws ErrnoException {
            return Os.fstat(mInner).st_size;
        }

        @Override
        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            int n = 0;
            while (n < size) {
                try {
                    final int res = Os.pread(mInner, data, n, size - n, offset + n);
                    if (res == 0) {
                        break;
                    } else {
                        n += res;
                    }
                } catch (InterruptedIOException e) {
                    n += e.bytesTransferred;
                }
            }

            // Redact any relevant ranges before returning
            final long[] ranges = mRedactRanges;
            for (int i = 0; i < ranges.length; i += 2) {
                final long start = Math.max(offset, ranges[i]);
                final long end = Math.min(offset + size, ranges[i + 1]);
                for (long j = start; j < end; j++) {
                    data[(int) (j - offset)] = 0;
                }
            }
            return n;
        }

        @Override
        public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
            throw new ErrnoException(TAG, OsConstants.EBADF);
        }

        @Override
        public void onFsync() throws ErrnoException {
            Os.fsync(mInner);
        }

        @Override
        public void onRelease() {
            if (DEBUG) Slog.v(TAG, "onRelease()");
            IoUtils.closeQuietly(mInner);
        }
    };
}
