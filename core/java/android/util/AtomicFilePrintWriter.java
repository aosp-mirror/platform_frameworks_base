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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;


/**
 * {@link PrintWriter} for {@link AtomicFile}
 * In order to "commit" the new content to the file, call {@link #markSuccess()} then
 * {@link #close()}. Calling{@link #markSuccess()} alone won't update the file.
 * This class does not confer any file locking semantics. Do not use this class when the file may be
 * accessed or modified concurrently by multiple threads or processes. The caller is responsible for
 * ensuring appropriate mutual exclusion invariants whenever it accesses the file.
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class AtomicFilePrintWriter extends PrintWriter {
    private final AtomicFileOutputStream mAtomicFileOutStream;

    /**
     * Construct from {@link AtomicFile} with {@link BufferedWriter} default buffer size.
     */
    public AtomicFilePrintWriter(AtomicFile atomicFile, Charset charset)
            throws IOException {
        this(new AtomicFileOutputStream(atomicFile), charset);
    }

    /**
     * Create from {@link AtomicFileOutputStream}.
     */
    public AtomicFilePrintWriter(AtomicFileOutputStream outStream, Charset charset) {
        super(new OutputStreamWriter(outStream, charset));
        mAtomicFileOutStream = outStream;
    }

    /**
     * When write is successful this needs to be called to flush the buffer and mark the writing as
     * successful.
     */
    public void markSuccess() throws IOException {
        flush();
        mAtomicFileOutStream.markSuccess();
    }

    /**
     * Creates string representation of the object.
     */
    @Override
    public String toString() {
        return "AtomicFilePrintWriter[" + mAtomicFileOutStream + "]";
    }
}
