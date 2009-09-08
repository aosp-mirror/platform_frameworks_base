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
 * A listening Bluetooth socket.
 *
 * <p>The interface for Bluetooth Sockets is similar to that of TCP sockets:
 * {@link java.net.Socket} and {@link java.net.ServerSocket}. On the server
 * side, use a {@link BluetoothServerSocket} to create a listening server
 * socket. It will return a new, connected {@link BluetoothSocket} on an
 * accepted connection. On the client side, use the same
 * {@link BluetoothSocket} object to both intiate the outgoing connection,
 * and to manage the connected socket.
 *
 * <p>The most common type of Bluetooth Socket is RFCOMM. RFCOMM is a
 * connection orientated, streaming transport over Bluetooth. It is also known
 * as the Serial Port Profile (SPP).
 *
 * <p>Use {@link BluetoothDevice#createRfcommSocket} to create a new {@link
 * BluetoothSocket} ready for an outgoing connection to a remote
 * {@link BluetoothDevice}.
 *
 * <p>Use {@link BluetoothAdapter#listenUsingRfcommOn} to create a listening
 * {@link BluetoothServerSocket} ready for incoming connections to the local
 * {@link BluetoothAdapter}.
 *
 * <p>{@link BluetoothSocket} and {@link BluetoothServerSocket} are thread
 * safe. In particular, {@link #close} will always immediately abort ongoing
 * operations and close the socket.
 *
 * <p>All methods on a {@link BluetoothServerSocket} require
 * {@link android.Manifest.permission#BLUETOOTH}
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
     * <p>Returns a connected {@link BluetoothSocket} on successful connection.
     * <p>Once this call returns, it can be called again to accept subsequent
     * incoming connections.
     * <p>{@link #close} can be used to abort this call from another thread.
     * @return a connected {@link BluetoothSocket}
     * @throws IOException on error, for example this call was aborted, or
     *                     timeout
     */
    public BluetoothSocket accept() throws IOException {
        return accept(-1);
    }

    /**
     * Block until a connection is established, with timeout.
     * <p>Returns a connected {@link BluetoothSocket} on successful connection.
     * <p>Once this call returns, it can be called again to accept subsequent
     * incoming connections.
     * <p>{@link #close} can be used to abort this call from another thread.
     * @return a connected {@link BluetoothSocket}
     * @throws IOException on error, for example this call was aborted, or
     *                     timeout
     */
    public BluetoothSocket accept(int timeout) throws IOException {
        return mSocket.accept(timeout);
    }

    /**
     * Immediately close this socket, and release all associated resources.
     * <p>Causes blocked calls on this socket in other threads to immediately
     * throw an IOException.
     */
    public void close() throws IOException {
        mSocket.close();
    }
}
