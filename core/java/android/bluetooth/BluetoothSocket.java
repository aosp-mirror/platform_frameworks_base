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
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A connected or connecting Bluetooth socket.
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
 */
public final class BluetoothSocket implements Closeable {
    /** Keep TYPE_ fields in sync with BluetoothSocket.cpp */
    /*package*/ static final int TYPE_RFCOMM = 1;
    /*package*/ static final int TYPE_SCO = 2;
    /*package*/ static final int TYPE_L2CAP = 3;

    private final int mType;  /* one of TYPE_RFCOMM etc */
    private final int mPort;  /* RFCOMM channel or L2CAP psm */
    private final BluetoothDevice mDevice;    /* remote device */
    private final String mAddress;    /* remote address */
    private final boolean mAuth;
    private final boolean mEncrypt;
    private final BluetoothInputStream mInputStream;
    private final BluetoothOutputStream mOutputStream;

    private int mSocketData;    /* used by native code only */

    /**
     * Construct a BluetoothSocket.
     * @param type    type of socket
     * @param fd      fd to use for connected socket, or -1 for a new socket
     * @param auth    require the remote device to be authenticated
     * @param encrypt require the connection to be encrypted
     * @param device  remote device that this socket can connect to
     * @param port    remote port
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient priveleges
     */
    /*package*/ BluetoothSocket(int type, int fd, boolean auth, boolean encrypt,
            BluetoothDevice device, int port) throws IOException {
        mType = type;
        mAuth = auth;
        mEncrypt = encrypt;
        mDevice = device;
        if (device == null) {
            mAddress = null;
        } else {
            mAddress = device.getAddress();
        }
        mPort = port;
        if (fd == -1) {
            initSocketNative();
        } else {
            initSocketFromFdNative(fd);
        }
        mInputStream = new BluetoothInputStream(this);
        mOutputStream = new BluetoothOutputStream(this);
    }

    /**
     * Construct a BluetoothSocket from address.
     * @param type    type of socket
     * @param fd      fd to use for connected socket, or -1 for a new socket
     * @param auth    require the remote device to be authenticated
     * @param encrypt require the connection to be encrypted
     * @param address remote device that this socket can connect to
     * @param port    remote port
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient priveleges
     */
    private BluetoothSocket(int type, int fd, boolean auth, boolean encrypt, String address,
            int port) throws IOException {
        this(type, fd, auth, encrypt, new BluetoothDevice(address), port);
    }

    /** @hide */
    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Attempt to connect to a remote device.
     * <p>This method will block until a connection is made or the connection
     * fails. If this method returns without an exception then this socket
     * is now connected.
     * <p>{@link #close} can be used to abort this call from another thread.
     * @throws IOException on error, for example connection failure
     */
    public void connect() throws IOException {
        connectNative();
    }

    /**
     * Immediately close this socket, and release all associated resources.
     * <p>Causes blocked calls on this socket in other threads to immediately
     * throw an IOException.
     */
    public void close() throws IOException {
        closeNative();
    }

    /**
     * Get the remote device this socket is connecting, or connected, to.
     * @return remote device
     */
    public BluetoothDevice getRemoteDevice() {
        return mDevice;
    }

    /**
     * Get the input stream associated with this socket.
     * <p>The input stream will be returned even if the socket is not yet
     * connected, but operations on that stream will throw IOException until
     * the associated socket is connected.
     * @return InputStream
     */
    public InputStream getInputStream() throws IOException {
        return mInputStream;
    }

    /**
     * Get the output stream associated with this socket.
     * <p>The output stream will be returned even if the socket is not yet
     * connected, but operations on that stream will throw IOException until
     * the associated socket is connected.
     * @return OutputStream
     */
    public OutputStream getOutputStream() throws IOException {
        return mOutputStream;
    }

    private native void initSocketNative() throws IOException;
    private native void initSocketFromFdNative(int fd) throws IOException;
    private native void connectNative() throws IOException;
    /*package*/ native void bindListenNative() throws IOException;
    /*package*/ native BluetoothSocket acceptNative(int timeout) throws IOException;
    /*package*/ native int availableNative() throws IOException;
    /*package*/ native int readNative(byte[] b, int offset, int length) throws IOException;
    /*package*/ native int writeNative(byte[] b, int offset, int length) throws IOException;
    /*package*/ native void closeNative() throws IOException;
    private native void destroyNative() throws IOException;
}
