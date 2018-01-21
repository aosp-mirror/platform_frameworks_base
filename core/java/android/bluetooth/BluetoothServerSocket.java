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

import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;

/**
 * A listening Bluetooth socket.
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
 * <p>To create a listening {@link BluetoothServerSocket} that's ready for
 * incoming connections, use
 * {@link BluetoothAdapter#listenUsingRfcommWithServiceRecord
 * BluetoothAdapter.listenUsingRfcommWithServiceRecord()}. Then call
 * {@link #accept()} to listen for incoming connection requests. This call
 * will block until a connection is established, at which point, it will return
 * a {@link BluetoothSocket} to manage the connection. Once the {@link
 * BluetoothSocket} is acquired, it's a good idea to call {@link #close()} on
 * the {@link BluetoothServerSocket} when it's no longer needed for accepting
 * connections. Closing the {@link BluetoothServerSocket} will <em>not</em>
 * close the returned {@link BluetoothSocket}.
 *
 * <p>{@link BluetoothServerSocket} is thread
 * safe. In particular, {@link #close} will always immediately abort ongoing
 * operations and close the server socket.
 *
 * <p class="note"><strong>Note:</strong>
 * Requires the {@link android.Manifest.permission#BLUETOOTH} permission.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using Bluetooth, read the
 * <a href="{@docRoot}guide/topics/connectivity/bluetooth.html">Bluetooth</a> developer guide.</p>
 * </div>
 *
 * {@see BluetoothSocket}
 */
public final class BluetoothServerSocket implements Closeable {

    private static final String TAG = "BluetoothServerSocket";
    private static final boolean DBG = false;
    /*package*/ final BluetoothSocket mSocket;
    private Handler mHandler;
    private int mMessage;
    private int mChannel;

    /**
     * Construct a socket for incoming connections.
     *
     * @param type type of socket
     * @param auth require the remote device to be authenticated
     * @param encrypt require the connection to be encrypted
     * @param port remote port
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * privileges
     */
    /*package*/ BluetoothServerSocket(int type, boolean auth, boolean encrypt, int port)
            throws IOException {
        mChannel = port;
        mSocket = new BluetoothSocket(type, -1, auth, encrypt, null, port, null);
        if (port == BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP) {
            mSocket.setExcludeSdp(true);
        }
    }

    /**
     * Construct a socket for incoming connections.
     *
     * @param type type of socket
     * @param auth require the remote device to be authenticated
     * @param encrypt require the connection to be encrypted
     * @param port remote port
     * @param mitm enforce man-in-the-middle protection for authentication.
     * @param min16DigitPin enforce a minimum length of 16 digits for a sec mode 2 connection
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * privileges
     */
    /*package*/ BluetoothServerSocket(int type, boolean auth, boolean encrypt, int port,
            boolean mitm, boolean min16DigitPin)
            throws IOException {
        mChannel = port;
        mSocket = new BluetoothSocket(type, -1, auth, encrypt, null, port, null, mitm,
                min16DigitPin);
        if (port == BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP) {
            mSocket.setExcludeSdp(true);
        }
    }

    /**
     * Construct a socket for incoming connections.
     *
     * @param type type of socket
     * @param auth require the remote device to be authenticated
     * @param encrypt require the connection to be encrypted
     * @param uuid uuid
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * privileges
     */
    /*package*/ BluetoothServerSocket(int type, boolean auth, boolean encrypt, ParcelUuid uuid)
            throws IOException {
        mSocket = new BluetoothSocket(type, -1, auth, encrypt, null, -1, uuid);
        // TODO: This is the same as mChannel = -1 - is this intentional?
        mChannel = mSocket.getPort();
    }


    /**
     * Block until a connection is established.
     * <p>Returns a connected {@link BluetoothSocket} on successful connection.
     * <p>Once this call returns, it can be called again to accept subsequent
     * incoming connections.
     * <p>{@link #close} can be used to abort this call from another thread.
     *
     * @return a connected {@link BluetoothSocket}
     * @throws IOException on error, for example this call was aborted, or timeout
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
     *
     * @return a connected {@link BluetoothSocket}
     * @throws IOException on error, for example this call was aborted, or timeout
     */
    public BluetoothSocket accept(int timeout) throws IOException {
        return mSocket.accept(timeout);
    }

    /**
     * Immediately close this socket, and release all associated resources.
     * <p>Causes blocked calls on this socket in other threads to immediately
     * throw an IOException.
     * <p>Closing the {@link BluetoothServerSocket} will <em>not</em>
     * close any {@link BluetoothSocket} received from {@link #accept()}.
     */
    public void close() throws IOException {
        if (DBG) Log.d(TAG, "BluetoothServerSocket:close() called. mChannel=" + mChannel);
        synchronized (this) {
            if (mHandler != null) {
                mHandler.obtainMessage(mMessage).sendToTarget();
            }
        }
        mSocket.close();
    }

    /*package*/
    synchronized void setCloseHandler(Handler handler, int message) {
        mHandler = handler;
        mMessage = message;
    }

    /*package*/ void setServiceName(String serviceName) {
        mSocket.setServiceName(serviceName);
    }

    /**
     * Returns the channel on which this socket is bound.
     *
     * @hide
     */
    public int getChannel() {
        return mChannel;
    }

    /**
     * Returns the assigned dynamic protocol/service multiplexer (PSM) value for the listening L2CAP
     * Connection-oriented Channel (CoC) server socket. This server socket must be returned by the
     * {#link BluetoothAdapter.listenUsingL2capCoc(int)} or {#link
     * BluetoothAdapter.listenUsingInsecureL2capCoc(int)}. The returned value is undefined if this
     * method is called on non-L2CAP server sockets.
     *
     * @return the assigned PSM or LE_PSM value depending on transport
     * @hide
     */
    public int getPsm() {
        return mChannel;
    }

    /**
     * Sets the channel on which future sockets are bound.
     * Currently used only when a channel is auto generated.
     */
    /*package*/ void setChannel(int newChannel) {
        /* TODO: From a design/architecture perspective this is wrong.
         *       The bind operation should be conducted through this class
         *       and the resulting port should be kept in mChannel, and
         *       not set from BluetoothAdapter. */
        if (mSocket != null) {
            if (mSocket.getPort() != newChannel) {
                Log.w(TAG, "The port set is different that the underlying port. mSocket.getPort(): "
                        + mSocket.getPort() + " requested newChannel: " + newChannel);
            }
        }
        mChannel = newChannel;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ServerSocket: Type: ");
        switch (mSocket.getConnectionType()) {
            case BluetoothSocket.TYPE_RFCOMM: {
                sb.append("TYPE_RFCOMM");
                break;
            }
            case BluetoothSocket.TYPE_L2CAP: {
                sb.append("TYPE_L2CAP");
                break;
            }
            case BluetoothSocket.TYPE_L2CAP_LE: {
                sb.append("TYPE_L2CAP_LE");
                break;
            }
            case BluetoothSocket.TYPE_SCO: {
                sb.append("TYPE_SCO");
                break;
            }
        }
        sb.append(" Channel: ").append(mChannel);
        return sb.toString();
    }
}
