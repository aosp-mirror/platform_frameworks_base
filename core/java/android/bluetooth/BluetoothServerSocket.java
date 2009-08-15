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

import java.io.Closeable;
import java.io.IOException;

/**
 * Server (listening) Bluetooth Socket.
 *
 * Currently only supports RFCOMM sockets.
 *
 * RFCOMM is a connection orientated, streaming transport over Bluetooth. It is
 * also known as the Serial Port Profile (SPP).
 *
 * TODO: Consider exposing L2CAP sockets.
 * TODO: Clean up javadoc grammer and formatting.
 * TODO: Remove @hide
 * @hide
 */
public final class BluetoothServerSocket implements Closeable {
    /*package*/ final BluetoothSocket mSocket;

    /**
     * Construct a socket for incoming connections.
     * @param type    type of socket
     * @param auth    require the remote device to be authenticated
     * @param encrypt require the connection to be encrypted
     * @param port    remote port
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient priveleges
     */
    /*package*/ BluetoothServerSocket(int type, boolean auth, boolean encrypt, int port)
            throws IOException {
        mSocket = new BluetoothSocket(type, -1, auth, encrypt, null, port);
    }

    /**
     * Block until a connection is established.
     * Returns a connected #BluetoothSocket. This server socket can be reused
     * for subsequent incoming connections by calling #accept repeatedly.
     * #close can be used to abort this call from another thread.
     * @return A connected #BluetoothSocket
     * @throws IOException On error, for example this call was aborted
     */
    public BluetoothSocket accept() throws IOException {
        return accept(-1);
    }

    /**
     * Block until a connection is established, with timeout.
     * Returns a connected #BluetoothSocket. This server socket can be reused
     * for subsequent incoming connections by calling #accept repeatedly.
     * #close can be used to abort this call from another thread.
     * @return A connected #BluetoothSocket
     * @throws IOException On error, for example this call was aborted, or
     *                     timeout
     */
    public BluetoothSocket accept(int timeout) throws IOException {
        return mSocket.acceptNative(timeout);
    }

    /**
     * Closes this socket.
     * This will cause other blocking calls on this socket to immediately
     * throw an IOException.
     */
    public void close() throws IOException {
        mSocket.closeNative();
    }
}
