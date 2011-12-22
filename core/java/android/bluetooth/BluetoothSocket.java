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

import android.bluetooth.IBluetoothCallback;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A connected or connecting Bluetooth socket.
 *
 * <p>The interface for Bluetooth Sockets is similar to that of TCP sockets:
 * {@link java.net.Socket} and {@link java.net.ServerSocket}. On the server
 * side, use a {@link BluetoothServerSocket} to create a listening server
 * socket. When a connection is accepted by the {@link BluetoothServerSocket},
 * it will return a new {@link BluetoothSocket} to manage the connection.
 * On the client side, use a single {@link BluetoothSocket} to both initiate
 * an outgoing connection and to manage the connection.
 *
 * <p>The most common type of Bluetooth socket is RFCOMM, which is the type
 * supported by the Android APIs. RFCOMM is a connection-oriented, streaming
 * transport over Bluetooth. It is also known as the Serial Port Profile (SPP).
 *
 * <p>To create a {@link BluetoothSocket} for connecting to a known device, use
 * {@link BluetoothDevice#createRfcommSocketToServiceRecord
 * BluetoothDevice.createRfcommSocketToServiceRecord()}.
 * Then call {@link #connect()} to attempt a connection to the remote device.
 * This call will block until a connection is established or the connection
 * fails.
 *
 * <p>To create a {@link BluetoothSocket} as a server (or "host"), see the
 * {@link BluetoothServerSocket} documentation.
 *
 * <p>Once the socket is connected, whether initiated as a client or accepted
 * as a server, open the IO streams by calling {@link #getInputStream} and
 * {@link #getOutputStream} in order to retrieve {@link java.io.InputStream}
 * and {@link java.io.OutputStream} objects, respectively, which are
 * automatically connected to the socket.
 *
 * <p>{@link BluetoothSocket} is thread
 * safe. In particular, {@link #close} will always immediately abort ongoing
 * operations and close the socket.
 *
 * <p class="note"><strong>Note:</strong>
 * Requires the {@link android.Manifest.permission#BLUETOOTH} permission.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using Bluetooth, read the
 * <a href="{@docRoot}guide/topics/wireless/bluetooth.html">Bluetooth</a> developer guide.</p>
 * </div>
 *
 * {@see BluetoothServerSocket}
 * {@see java.io.InputStream}
 * {@see java.io.OutputStream}
 */
public final class BluetoothSocket implements Closeable {
    private static final String TAG = "BluetoothSocket";

    /** @hide */
    public static final int MAX_RFCOMM_CHANNEL = 30;

    /** Keep TYPE_ fields in sync with BluetoothSocket.cpp */
    /*package*/ static final int TYPE_RFCOMM = 1;
    /*package*/ static final int TYPE_SCO = 2;
    /*package*/ static final int TYPE_L2CAP = 3;

    /*package*/ static final int EBADFD = 77;
    /*package*/ static final int EADDRINUSE = 98;

    private final int mType;  /* one of TYPE_RFCOMM etc */
    private final BluetoothDevice mDevice;    /* remote device */
    private final String mAddress;    /* remote address */
    private final boolean mAuth;
    private final boolean mEncrypt;
    private final BluetoothInputStream mInputStream;
    private final BluetoothOutputStream mOutputStream;
    private final SdpHelper mSdp;

    private int mPort;  /* RFCOMM channel or L2CAP psm */

    private enum SocketState {
        INIT,
        CONNECTED,
        CLOSED
    }

    /** prevents all native calls after destroyNative() */
    private SocketState mSocketState;

    /** protects mSocketState */
    private final ReentrantReadWriteLock mLock;

    /** used by native code only */
    private int mSocketData;

    /**
     * Construct a BluetoothSocket.
     * @param type    type of socket
     * @param fd      fd to use for connected socket, or -1 for a new socket
     * @param auth    require the remote device to be authenticated
     * @param encrypt require the connection to be encrypted
     * @param device  remote device that this socket can connect to
     * @param port    remote port
     * @param uuid    SDP uuid
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient privileges
     */
    /*package*/ BluetoothSocket(int type, int fd, boolean auth, boolean encrypt,
            BluetoothDevice device, int port, ParcelUuid uuid) throws IOException {
        if (type == BluetoothSocket.TYPE_RFCOMM && uuid == null && fd == -1) {
            if (port < 1 || port > MAX_RFCOMM_CHANNEL) {
                throw new IOException("Invalid RFCOMM channel: " + port);
            }
        }
        if (uuid == null) {
            mPort = port;
            mSdp = null;
        } else {
            mSdp = new SdpHelper(device, uuid);
            mPort = -1;
        }
        mType = type;
        mAuth = auth;
        mEncrypt = encrypt;
        mDevice = device;
        if (device == null) {
            mAddress = null;
        } else {
            mAddress = device.getAddress();
        }
        if (fd == -1) {
            initSocketNative();
        } else {
            initSocketFromFdNative(fd);
        }
        mInputStream = new BluetoothInputStream(this);
        mOutputStream = new BluetoothOutputStream(this);
        mSocketState = SocketState.INIT;
        mLock = new ReentrantReadWriteLock();
    }

    /**
     * Construct a BluetoothSocket from address. Used by native code.
     * @param type    type of socket
     * @param fd      fd to use for connected socket, or -1 for a new socket
     * @param auth    require the remote device to be authenticated
     * @param encrypt require the connection to be encrypted
     * @param address remote device that this socket can connect to
     * @param port    remote port
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient privileges
     */
    private BluetoothSocket(int type, int fd, boolean auth, boolean encrypt, String address,
            int port) throws IOException {
        this(type, fd, auth, encrypt, new BluetoothDevice(address), port, null);
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
     * <p>Creating new connections to
     * remote Bluetooth devices should not be attempted while device discovery
     * is in progress. Device discovery is a heavyweight procedure on the
     * Bluetooth adapter and will significantly slow a device connection.
     * Use {@link BluetoothAdapter#cancelDiscovery()} to cancel an ongoing
     * discovery. Discovery is not managed by the Activity,
     * but is run as a system service, so an application should always call
     * {@link BluetoothAdapter#cancelDiscovery()} even if it
     * did not directly request a discovery, just to be sure.
     * <p>{@link #close} can be used to abort this call from another thread.
     * @throws IOException on error, for example connection failure
     */
    public void connect() throws IOException {
        mLock.readLock().lock();
        try {
            if (mSocketState == SocketState.CLOSED) throw new IOException("socket closed");

            if (mSdp != null) {
                mPort = mSdp.doSdp();  // blocks
            }

            connectNative();  // blocks
            mSocketState = SocketState.CONNECTED;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /**
     * Immediately close this socket, and release all associated resources.
     * <p>Causes blocked calls on this socket in other threads to immediately
     * throw an IOException.
     */
    public void close() throws IOException {
        // abort blocking operations on the socket
        mLock.readLock().lock();
        try {
            if (mSocketState == SocketState.CLOSED) return;
            if (mSdp != null) {
                mSdp.cancel();
            }
            abortNative();
        } finally {
            mLock.readLock().unlock();
        }

        // all native calls are guaranteed to immediately return after
        // abortNative(), so this lock should immediately acquire
        mLock.writeLock().lock();
        try {
            mSocketState = SocketState.CLOSED;
            destroyNative();
        } finally {
            mLock.writeLock().unlock();
        }
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

    /**
     * Get the connection status of this socket, ie, whether there is an active connection with
     * remote device.
     * @return true if connected
     *         false if not connected
     */
    public boolean isConnected() {
        return (mSocketState == SocketState.CONNECTED);
    }

    /**
     * Currently returns unix errno instead of throwing IOException,
     * so that BluetoothAdapter can check the error code for EADDRINUSE
     */
    /*package*/ int bindListen() {
        mLock.readLock().lock();
        try {
            if (mSocketState == SocketState.CLOSED) return EBADFD;
            return bindListenNative();
        } finally {
            mLock.readLock().unlock();
        }
    }

    /*package*/ BluetoothSocket accept(int timeout) throws IOException {
        mLock.readLock().lock();
        try {
            if (mSocketState == SocketState.CLOSED) throw new IOException("socket closed");

            BluetoothSocket acceptedSocket = acceptNative(timeout);
            mSocketState = SocketState.CONNECTED;
            return acceptedSocket;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /*package*/ int available() throws IOException {
        mLock.readLock().lock();
        try {
            if (mSocketState == SocketState.CLOSED) throw new IOException("socket closed");
            return availableNative();
        } finally {
            mLock.readLock().unlock();
        }
    }

    /*package*/ int read(byte[] b, int offset, int length) throws IOException {
        mLock.readLock().lock();
        try {
            if (mSocketState == SocketState.CLOSED) throw new IOException("socket closed");
            return readNative(b, offset, length);
        } finally {
            mLock.readLock().unlock();
        }
    }

    /*package*/ int write(byte[] b, int offset, int length) throws IOException {
        mLock.readLock().lock();
        try {
            if (mSocketState == SocketState.CLOSED) throw new IOException("socket closed");
            return writeNative(b, offset, length);
        } finally {
            mLock.readLock().unlock();
        }
    }

    private native void initSocketNative() throws IOException;
    private native void initSocketFromFdNative(int fd) throws IOException;
    private native void connectNative() throws IOException;
    private native int bindListenNative();
    private native BluetoothSocket acceptNative(int timeout) throws IOException;
    private native int availableNative() throws IOException;
    private native int readNative(byte[] b, int offset, int length) throws IOException;
    private native int writeNative(byte[] b, int offset, int length) throws IOException;
    private native void abortNative() throws IOException;
    private native void destroyNative() throws IOException;
    /**
     * Throws an IOException for given posix errno. Done natively so we can
     * use strerr to convert to string error.
     */
    /*package*/ native void throwErrnoNative(int errno) throws IOException;

    /**
     * Helper to perform blocking SDP lookup.
     */
    private static class SdpHelper extends IBluetoothCallback.Stub {
        private final IBluetooth service;
        private final ParcelUuid uuid;
        private final BluetoothDevice device;
        private int channel;
        private boolean canceled;
        public SdpHelper(BluetoothDevice device, ParcelUuid uuid) {
            service = BluetoothDevice.getService();
            this.device = device;
            this.uuid = uuid;
            canceled = false;
        }
        /**
         * Returns the RFCOMM channel for the UUID, or throws IOException
         * on failure.
         */
        public synchronized int doSdp() throws IOException {
            if (canceled) throw new IOException("Service discovery canceled");
            channel = -1;

            boolean inProgress = false;
            try {
                inProgress = service.fetchRemoteUuids(device.getAddress(), uuid, this);
            } catch (RemoteException e) {Log.e(TAG, "", e);}

            if (!inProgress) throw new IOException("Unable to start Service Discovery");

            try {
                /* 12 second timeout as a precaution - onRfcommChannelFound
                 * should always occur before the timeout */
                wait(12000);   // block

            } catch (InterruptedException e) {}

            if (canceled) throw new IOException("Service discovery canceled");
            if (channel < 1) throw new IOException("Service discovery failed");

            return channel;
        }
        /** Object cannot be re-used after calling cancel() */
        public synchronized void cancel() {
            if (!canceled) {
                canceled = true;
                channel = -1;
                notifyAll();  // unblock
            }
        }
        public synchronized void onRfcommChannelFound(int channel) {
            if (!canceled) {
                this.channel = channel;
                notifyAll();  // unblock
            }
        }
    }
}
