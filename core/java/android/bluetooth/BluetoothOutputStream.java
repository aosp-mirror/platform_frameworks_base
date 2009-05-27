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
 * TODO: Implement bulk reads (instead of one byte at a time).
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
        mSocket.writeNative(oneByte);
    }
}
