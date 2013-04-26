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
import java.io.OutputStream;

/**
 * BluetoothOutputStream.
 *
 * Used to read from a Bluetooth socket.
 *
 * @hide
 */
/*package*/ final class BluetoothOutputStream extends OutputStream {
    private BluetoothSocket mSocket;

    /*package*/ BluetoothOutputStream(BluetoothSocket s) {
        mSocket = s;
    }

    /**
     * Close this output stream and the socket associated with it.
     */
    public void close() throws IOException {
        mSocket.close();
    }

    /**
     * Writes a single byte to this stream. Only the least significant byte of
     * the integer {@code oneByte} is written to the stream.
     *
     * @param oneByte
     *            the byte to be written.
     * @throws IOException
     *             if an error occurs while writing to this stream.
     * @since Android 1.0
     */
    public void write(int oneByte) throws IOException {
        byte b[] = new byte[1];
        b[0] = (byte)oneByte;
        mSocket.write(b, 0, 1);
    }

    /**
     * Writes {@code count} bytes from the byte array {@code buffer} starting
     * at position {@code offset} to this stream.
     *
     * @param b
     *            the buffer to be written.
     * @param offset
     *            the start position in {@code buffer} from where to get bytes.
     * @param count
     *            the number of bytes from {@code buffer} to write to this
     *            stream.
     * @throws IOException
     *             if an error occurs while writing to this stream.
     * @throws IndexOutOfBoundsException
     *             if {@code offset < 0} or {@code count < 0}, or if
     *             {@code offset + count} is bigger than the length of
     *             {@code buffer}.
     * @since Android 1.0
     */
    public void write(byte[] b, int offset, int count) throws IOException {
        if (b == null) {
            throw new NullPointerException("buffer is null");
        }
        if ((offset | count) < 0 || count > b.length - offset) {
            throw new IndexOutOfBoundsException("invalid offset or length");
        }
        mSocket.write(b, offset, count);
    }
    /**
     * Wait until the data in sending queue is emptied. A polling version
     * for flush implementation. Use it to ensure the writing data afterwards will
     * be packed in the new RFCOMM frame.
     * @throws IOException
     *             if an i/o error occurs.
     * @since Android 4.2.3
     */
    public void flush()  throws IOException {
        mSocket.flush();
    }
}
