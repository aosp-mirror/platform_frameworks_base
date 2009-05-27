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
 * TODO: Consider implementing SCO and L2CAP sockets.
 * TODO: Clean up javadoc grammer and formatting.
 * TODO: Remove @hide
 * @hide
 */
public final class BluetoothServerSocket implements Closeable {
    private final BluetoothSocket mSocket;

    /**
     * Construct a listening, secure RFCOMM server socket.
     * The remote device connecting to this socket will be authenticated and
     * communication on this socket will be encrypted.
     * Call #accept to retrieve connections to this socket.
     * @return An RFCOMM BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient permissions.
     */
    public static BluetoothServerSocket listenUsingRfcommOn(int port) throws IOException {
        BluetoothServerSocket socket = new BluetoothServerSocket(true, true);
        try {
            socket.mSocket.bindListenNative(port);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException e2) { }
            throw e;
        }
        return socket;
    }

    /**
     * Construct an unencrypted, unauthenticated, RFCOMM server socket.
     * Call #accept to retrieve connections to this socket.
     * @return An RFCOMM BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient permissions.
     */
    public static BluetoothServerSocket listenUsingInsecureRfcommOn(int port) throws IOException {
        BluetoothServerSocket socket = new BluetoothServerSocket(false, false);
        try {
            socket.mSocket.bindListenNative(port);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException e2) { }
            throw e;
        }
        return socket;
    }

    /**
     * Construct a socket for incoming connections.
     * @param auth    Require the remote device to be authenticated
     * @param encrypt Require the connection to be encrypted
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient priveleges
     */
    private BluetoothServerSocket(boolean auth, boolean encrypt) throws IOException {
        mSocket = new BluetoothSocket(-1, auth, encrypt, null, -1);
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
