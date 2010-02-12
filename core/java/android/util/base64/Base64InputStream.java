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

package android.util.base64;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An OutputStream that does either Base64 encoding or decoding on the
 * data written to it, writing the resulting data to another
 * OutputStream.
 */
public class Base64InputStream extends FilterInputStream {
    private final boolean encode;
    private final Base64.EncoderState estate;
    private final Base64.DecoderState dstate;

    private static byte[] EMPTY = new byte[0];

    private static final int BUFFER_SIZE = 2048;
    private boolean eof;
    private byte[] inputBuffer;
    private byte[] outputBuffer;
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
     */
    public Base64InputStream(InputStream in, int flags, boolean encode) {
        super(in);
        this.encode = encode;
        eof = false;
        inputBuffer = new byte[BUFFER_SIZE];
        if (encode) {
            // len*8/5+10 is an overestimate of the most bytes the
            // encoder can produce for len bytes of input.
            outputBuffer = new byte[BUFFER_SIZE * 8/5 + 10];
            estate = new Base64.EncoderState(flags, outputBuffer);
            dstate = null;
        } else {
            // len*3/4+10 is an overestimate of the most bytes the
            // decoder can produce for len bytes of input.
            outputBuffer = new byte[BUFFER_SIZE * 3/4 + 10];
            estate = null;
            dstate = new Base64.DecoderState(flags, outputBuffer);
        }
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
            return outputBuffer[outputStart++];
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
        System.arraycopy(outputBuffer, outputStart, b, off, bytes);
        outputStart += bytes;
        return bytes;
    }

    /**
     * Read data from the input stream into inputBuffer, then
     * decode/encode it into the empty outputBuffer, and reset the
     * outputStart and outputEnd pointers.
     */
    private void refill() throws IOException {
        if (eof) return;
        int bytesRead = in.read(inputBuffer);
        if (encode) {
            if (bytesRead == -1) {
                eof = true;
                Base64.encodeInternal(EMPTY, 0, 0, estate, true);
            } else {
                Base64.encodeInternal(inputBuffer, 0, bytesRead, estate, false);
            }
            outputEnd = estate.op;
        } else {
            if (bytesRead == -1) {
                eof = true;
                Base64.decodeInternal(EMPTY, 0, 0, dstate, true);
            } else {
                Base64.decodeInternal(inputBuffer, 0, bytesRead, dstate, false);
            }
            outputEnd = dstate.op;
        }
        outputStart = 0;
    }
}
