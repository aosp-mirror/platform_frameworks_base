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

import java.io.InputStream;
import java.io.IOException;

/**
 * This object provides an input stream to the Operation objects used in this
 * package.
 * @hide
 */
public final class PrivateInputStream extends InputStream {

    private BaseStream mParent;

    private byte[] mData;

    private int mIndex;

    private boolean mOpen;

    /**
     * Creates an input stream for the <code>Operation</code> to read from
     * @param p the connection this input stream is for
     */
    public PrivateInputStream(BaseStream p) {
        mParent = p;
        mData = new byte[0];
        mIndex = 0;
        mOpen = true;
    }

    /**
     * Returns the number of bytes that can be read (or skipped over) from this
     * input stream without blocking by the next caller of a method for this
     * input stream. The next caller might be the same thread or or another
     * thread.
     * @return the number of bytes that can be read from this input stream
     *         without blocking
     * @throws IOException if an I/O error occurs
     */
    @Override
    public synchronized int available() throws IOException {
        ensureOpen();
        return mData.length - mIndex;
    }

    /**
     * Reads the next byte of data from the input stream. The value byte is
     * returned as an int in the range 0 to 255. If no byte is available because
     * the end of the stream has been reached, the value -1 is returned. This
     * method blocks until input data is available, the end of the stream is
     * detected, or an exception is thrown.
     * @return the byte read from the input stream or -1 if it reaches the end of
     *         stream
     * @throws IOException if an I/O error occurs
     */
    @Override
    public synchronized int read() throws IOException {
        ensureOpen();
        while (mData.length == mIndex) {
            if (!mParent.continueOperation(true, true)) {
                return -1;
            }
        }
        return (mData[mIndex++] & 0xFF);
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public synchronized int read(byte[] b, int offset, int length) throws IOException {

        if (b == null) {
            throw new IOException("buffer is null");
        }
        if ((offset | length) < 0 || length > b.length - offset) {
            throw new ArrayIndexOutOfBoundsException("index outof bound");
        }
        ensureOpen();

        int currentDataLength = mData.length - mIndex;
        int remainReadLength = length;
        int offset1 = offset;
        int result = 0;

        while (currentDataLength <= remainReadLength) {
            System.arraycopy(mData, mIndex, b, offset1, currentDataLength);
            mIndex += currentDataLength;
            offset1 += currentDataLength;
            result += currentDataLength;
            remainReadLength -= currentDataLength;

            if (!mParent.continueOperation(true, true)) {
                return result == 0 ? -1 : result;
            }
            currentDataLength = mData.length - mIndex;
        }
        if (remainReadLength > 0) {
            System.arraycopy(mData, mIndex, b, offset1, remainReadLength);
            mIndex += remainReadLength;
            result += remainReadLength;
        }
        return result;
    }

    /**
     * Allows the <code>OperationImpl</code> thread to add body data to the
     * input stream.
     * @param body the data to add to the stream
     * @param start the start of the body to array to copy
     */
    public synchronized void writeBytes(byte[] body, int start) {

        int length = (body.length - start) + (mData.length - mIndex);
        byte[] temp = new byte[length];

        System.arraycopy(mData, mIndex, temp, 0, mData.length - mIndex);
        System.arraycopy(body, start, temp, mData.length - mIndex, body.length - start);

        mData = temp;
        mIndex = 0;
        notifyAll();
    }

    /**
     * Verifies that this stream is open
     * @throws IOException if the stream is not open
     */
    private void ensureOpen() throws IOException {
        mParent.ensureOpen();
        if (!mOpen) {
            throw new IOException("Input stream is closed");
        }
    }

    /**
     * Closes the input stream. If the input stream is already closed, do
     * nothing.
     * @throws IOException this will never happen
     */
    @Override
    public void close() throws IOException {
        mOpen = false;
        mParent.streamClosed(true);
    }
}
