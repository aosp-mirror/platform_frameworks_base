/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package javax.obex;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;

/**
 * This object provides an output stream to the Operation objects used in this
 * package.
 * @hide
 */
public final class PrivateOutputStream extends OutputStream {

    private BaseStream mParent;

    private ByteArrayOutputStream mArray;

    private boolean mOpen;

    private int mMaxPacketSize;

    /**
     * Creates an empty <code>PrivateOutputStream</code> to write to.
     * @param p the connection that this stream runs over
     */
    public PrivateOutputStream(BaseStream p, int maxSize) {
        mParent = p;
        mArray = new ByteArrayOutputStream();
        mMaxPacketSize = maxSize;
        mOpen = true;
    }

    /**
     * Determines how many bytes have been written to the output stream.
     * @return the number of bytes written to the output stream
     */
    public int size() {
        return mArray.size();
    }

    /**
     * Writes the specified byte to this output stream. The general contract for
     * write is that one byte is written to the output stream. The byte to be
     * written is the eight low-order bits of the argument b. The 24 high-order
     * bits of b are ignored.
     * @param b the byte to write
     * @throws IOException if an I/O error occurs
     */
    @Override
    public synchronized void write(int b) throws IOException {
        ensureOpen();
        mParent.ensureNotDone();
        mArray.write(b);
        if (mArray.size() == mMaxPacketSize) {
            mParent.continueOperation(true, false);
        }
    }

    @Override
    public void write(byte[] buffer) throws IOException {
        write(buffer, 0, buffer.length);
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int count) throws IOException {
        int offset1 = offset;
        int remainLength = count;

        if (buffer == null) {
            throw new IOException("buffer is null");
        }
        if ((offset | count) < 0 || count > buffer.length - offset) {
            throw new IndexOutOfBoundsException("index outof bound");
        }

        ensureOpen();
        mParent.ensureNotDone();
        while ((mArray.size() + remainLength) >= mMaxPacketSize) {
            int bufferLeft = mMaxPacketSize - mArray.size();
            mArray.write(buffer, offset1, bufferLeft);
            offset1 += bufferLeft;
            remainLength -= bufferLeft;
            mParent.continueOperation(true, false);
        }
        if (remainLength > 0) {
            mArray.write(buffer, offset1, remainLength);
        }
    }

    /**
     * Reads the bytes that have been written to this stream.
     * @param size the size of the array to return
     * @return the byte array that is written
     */
    public synchronized byte[] readBytes(int size) {
        if (mArray.size() > 0) {
            byte[] temp = mArray.toByteArray();
            mArray.reset();
            byte[] result = new byte[size];
            System.arraycopy(temp, 0, result, 0, size);
            if (temp.length != size) {
                mArray.write(temp, size, temp.length - size);
            }
            return result;
        } else {
            return null;
        }
    }

    /**
     * Verifies that this stream is open
     * @throws IOException if the stream is not open
     */
    private void ensureOpen() throws IOException {
        mParent.ensureOpen();
        if (!mOpen) {
            throw new IOException("Output stream is closed");
        }
    }

    /**
     * Closes the output stream. If the input stream is already closed, do
     * nothing.
     * @throws IOException this will never happen
     */
    @Override
    public void close() throws IOException {
        mOpen = false;
        mParent.streamClosed(false);
    }

    /**
     * Determines if the connection is closed
     * @return <code>true</code> if the connection is closed; <code>false</code>
     *         if the connection is open
     */
    public boolean isClosed() {
        return !mOpen;
    }
}
