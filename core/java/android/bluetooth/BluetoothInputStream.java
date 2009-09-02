/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.bluetooth;

import java.io.IOException;
import java.io.InputStream;

/**
 * BluetoothInputStream.
 *
 * Used to write to a Bluetooth socket.
 *
 * @hide
 */
/*package*/ final class BluetoothInputStream extends InputStream {
    private BluetoothSocket mSocket;

    /*package*/ BluetoothInputStream(BluetoothSocket s) {
        mSocket = s;
    }

    /**
     * Return number of bytes available before this stream will block.
     */
    public int available() throws IOException {
        return mSocket.available();
    }

    public void close() throws IOException {
        mSocket.close();
    }

    /**
     * Reads a single byte from this stream and returns it as an integer in the
     * range from 0 to 255. Returns -1 if the end of the stream has been
     * reached. Blocks until one byte has been read, the end of the source
     * stream is detected or an exception is thrown.
     *
     * @return the byte read or -1 if the end of stream has been reached.
     * @throws IOException
     *             if the stream is closed or another IOException occurs.
     * @since Android 1.5
     */
    public int read() throws IOException {
        byte b[] = new byte[1];
        int ret = mSocket.read(b, 0, 1);
        if (ret == 1) {
            return (int)b[0] & 0xff;
        } else {
            return -1;
        }
    }

    /**
     * Reads at most {@code length} bytes from this stream and stores them in
     * the byte array {@code b} starting at {@code offset}.
     *
     * @param b
     *            the byte array in which to store the bytes read.
     * @param offset
     *            the initial position in {@code buffer} to store the bytes
     *            read from this stream.
     * @param length
     *            the maximum number of bytes to store in {@code b}.
     * @return the number of bytes actually read or -1 if the end of the stream
     *         has been reached.
     * @throws IndexOutOfBoundsException
     *             if {@code offset < 0} or {@code length < 0}, or if
     *             {@code offset + length} is greater than the length of
     *             {@code b}.
     * @throws IOException
     *             if the stream is closed or another IOException occurs.
     * @since Android 1.5
     */
    public int read(byte[] b, int offset, int length) throws IOException {
        if (b == null) {
            throw new NullPointerException("byte array is null");
        }
        if ((offset | length) < 0 || length > b.length - offset) {
            throw new ArrayIndexOutOfBoundsException("invalid offset or length");
        }
        return mSocket.read(b, offset, length);
    }
}
