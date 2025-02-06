/*
 * Copyright 2024 The Android Open Source Project
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

package android.util;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * {@link FileOutputStream} for {@link AtomicFile}.
 * Allows user-code to write into file output stream backed by {@link AtomicFile}.
 * In order to "commit" the new content to the file, call {@link #markSuccess()} then
 * {@link #close()}. Calling{@link #markSuccess()} alone won't update the file.
 * This class does not confer any file locking semantics. Do not use this class when the file may be
 * accessed or modified concurrently by multiple threads or processes. The caller is responsible for
 * ensuring appropriate mutual exclusion invariants whenever it accesses the file.
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class AtomicFileOutputStream extends FileOutputStream implements AutoCloseable {
    private static final String TAG = "AtomicFileOutputStream";
    private final AtomicFile mFile;
    private final FileOutputStream mOutStream;
    private boolean mWritingSuccessful;
    private boolean mClosed;

    /**
     * See {@link AtomicFile#startWrite()}.
     */
    public AtomicFileOutputStream(AtomicFile file) throws IOException {
        this(file, file.startWrite());
    }

    private AtomicFileOutputStream(AtomicFile file, FileOutputStream oStream) throws IOException {
        super(oStream.getFD());
        mFile = file;
        mOutStream = oStream;
    }

    /**
     * Marks the writing as successful.
     */
    public void markSuccess() {
        if (mWritingSuccessful) {
            throw new IllegalStateException(TAG + " success is already marked");
        }
        mWritingSuccessful = true;
    }

    /**
     * Finishes writing to {@link #mFile}, see {@link AtomicFile#finishWrite(FileOutputStream)}
     * and {@link AtomicFile#failWrite(FileOutputStream)}. Closes {@link #mOutStream} which
     * is the owner of the file descriptor.
     */
    @Override
    public void close() throws IOException {
        super.close();
        synchronized (mOutStream) {
            if (mClosed) {
                // FileOutputStream#finalize() may call this #close() method.
                // We don't want to throw exceptions in this case.
                // CloseGuard#warnIfOpen() also called there, so no need to log warnings in
                // AtomicFileOutputStream#finalize().
                return;
            }
            mClosed = true;
        }

        if (mWritingSuccessful) {
            mFile.finishWrite(mOutStream);
        } else {
            mFile.failWrite(mOutStream);
        }
    }

    /**
     * Creates string representation of the object.
     */
    @Override
    public String toString() {
        return TAG + "["
                + "mFile=" + mFile
                + ", mWritingSuccessful=" + mWritingSuccessful
                + ", mClosed=" + mClosed
                + "]";
    }
}
