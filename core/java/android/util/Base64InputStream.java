/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream that does Base64 decoding on the data read through
 * it.
 */
public class Base64InputStream extends FilterInputStream {
    private final Base64.Coder coder;

    private static byte[] EMPTY = new byte[0];

    private static final int BUFFER_SIZE = 2048;
    private boolean eof;
    private byte[] inputBuffer;
    private int outputStart;
    private int outputEnd;

    /**
     * An InputStream that performs Base64 decoding on the data read
     * from the wrapped stream.
     *
     * @param in the InputStream to read the source data from
     * @param flags bit flags for controlling the decoder; see the
     *        constants in {@link Base64}
     */
    public Base64InputStream(InputStream in, int flags) {
        this(in, flags, false);
    }

    /**
     * Performs Base64 encoding or decoding on the data read from the
     * wrapped InputStream.
     *
     * @param in the InputStream to read the source data from
     * @param flags bit flags for controlling the decoder; see the
     *        constants in {@link Base64}
     * @param encode true to encode, false to decode
     *
     * @hide
     */
    public Base64InputStream(InputStream in, int flags, boolean encode) {
        super(in);
        eof = false;
        inputBuffer = new byte[BUFFER_SIZE];
        if (encode) {
            coder = new Base64.Encoder(flags, null);
        } else {
            coder = new Base64.Decoder(flags, null);
        }
        coder.output = new byte[coder.maxOutputSize(BUFFER_SIZE)];
        outputStart = 0;
        outputEnd = 0;
    }

    public boolean markSupported() {
        return false;
    }

    public void mark(int readlimit) {
        throw new UnsupportedOperationException();
    }

    public void reset() {
        throw new UnsupportedOperationException();
    }

    public void close() throws IOException {
        in.close();
        inputBuffer = null;
    }

    public int available() {
        return outputEnd - outputStart;
    }

    public long skip(long n) throws IOException {
        if (outputStart >= outputEnd) {
            refill();
        }
        if (outputStart >= outputEnd) {
            return 0;
        }
        long bytes = Math.min(n, outputEnd-outputStart);
        outputStart += bytes;
        return bytes;
    }

    public int read() throws IOException {
        if (outputStart >= outputEnd) {
            refill();
        }
        if (outputStart >= outputEnd) {
            return -1;
        } else {
            return coder.output[outputStart++];
        }
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (outputStart >= outputEnd) {
            refill();
        }
        if (outputStart >= outputEnd) {
            return -1;
        }
        int bytes = Math.min(len, outputEnd-outputStart);
        System.arraycopy(coder.output, outputStart, b, off, bytes);
        outputStart += bytes;
        return bytes;
    }

    /**
     * Read data from the input stream into inputBuffer, then
     * decode/encode it into the empty coder.output, and reset the
     * outputStart and outputEnd pointers.
     */
    private void refill() throws IOException {
        if (eof) return;
        int bytesRead = in.read(inputBuffer);
        boolean success;
        if (bytesRead == -1) {
            eof = true;
            success = coder.process(EMPTY, 0, 0, true);
        } else {
            success = coder.process(inputBuffer, 0, bytesRead, false);
        }
        if (!success) {
            throw new IOException("bad base-64");
        }
        outputEnd = coder.op;
        outputStart = 0;
    }
}
