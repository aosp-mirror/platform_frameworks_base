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

import java.io.BufferedOutputStream;
import java.io.IOException;

/**
 * {@link BufferedOutputStream} for {@link AtomicFile}.
 * Allows user-code to write into file output stream backed by {@link AtomicFile}.
 * In order to "commit" the new content to the file, call {@link #markSuccess()} then
 * {@link #close()}. Calling{@link #markSuccess()} alone won't update the file.
 * This class does not confer any file locking semantics. Do not use this class when the file may be
 * accessed or modified concurrently by multiple threads or processes. The caller is responsible for
 * ensuring appropriate mutual exclusion invariants whenever it accesses the file.
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class AtomicFileBufferedOutputStream extends BufferedOutputStream implements AutoCloseable {
    private static final String TAG = "AtomicFileBufferedOutputStream";
    private final AtomicFileOutputStream mAtomicFileOutputStream;

    /**
     * See {@link AtomicFileOutputStream#AtomicFileOutputStream(AtomicFile)}.
     */
    public AtomicFileBufferedOutputStream(AtomicFile file) throws IOException {
        this(new AtomicFileOutputStream(file));
    }

    private AtomicFileBufferedOutputStream(AtomicFileOutputStream atomicFileOutputStream) {
        super(atomicFileOutputStream);
        mAtomicFileOutputStream = atomicFileOutputStream;
    }

    /**
     * See {@link AtomicFile#startWrite()} with specific buffer size.
     */
    public AtomicFileBufferedOutputStream(AtomicFile file, int bufferSize) throws IOException {
        this(new AtomicFileOutputStream(file), bufferSize);
    }

    private AtomicFileBufferedOutputStream(AtomicFileOutputStream atomicFileOutputStream,
            int bufferSize) {
        super(atomicFileOutputStream, bufferSize);
        mAtomicFileOutputStream = atomicFileOutputStream;
    }

    /**
     * Flushes output stream and marks the writing as finished.
     */
    public void markSuccess() throws IOException {
        flush();
        mAtomicFileOutputStream.markSuccess();
    }

    /**
     * Creates string representation of the object.
     */
    @Override
    public String toString() {
        return TAG + "[" + mAtomicFileOutputStream + "]";
    }
}
