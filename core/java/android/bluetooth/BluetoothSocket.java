/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import android.net.LocalSocket;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
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
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /** @hide */
    public static final int MAX_RFCOMM_CHANNEL = 30;

    /** Keep TYPE_ fields in sync with BluetoothSocket.cpp */
    /*package*/ static final int TYPE_RFCOMM = 1;
    /*package*/ static final int TYPE_SCO = 2;
    /*package*/ static final int TYPE_L2CAP = 3;

    /*package*/ static final int EBADFD = 77;
    /*package*/ static final int EADDRINUSE = 98;

    /*package*/ static final int SEC_FLAG_ENCRYPT = 1;
    /*package*/ static final int SEC_FLAG_AUTH = 1 << 1;

    private final int mType;  /* one of TYPE_RFCOMM etc */
    private BluetoothDevice mDevice;    /* remote device */
    private String mAddress;    /* remote address */
    private final boolean mAuth;
    private final boolean mEncrypt;
    private final BluetoothInputStream mInputStream;
    private final BluetoothOutputStream mOutputStream;
    private final ParcelUuid mUuid;
    private ParcelFileDescriptor mPfd;
    private LocalSocket mSocket;
    private InputStream mSocketIS;
    private OutputStream mSocketOS;
    private int mPort;  /* RFCOMM channel or L2CAP psm */
    private int mFd;
    private String mServiceName;
    private static int PROXY_CONNECTION_TIMEOUT = 5000;

    private static int SOCK_SIGNAL_SIZE = 16;

    private enum SocketState {
        INIT,
        CONNECTED,
        LISTENING,
        CLOSED,
    }

    /** prevents all native calls after destroyNative() */
    private volatile SocketState mSocketState;

    /** protects mSocketState */
    //private final ReentrantReadWriteLock mLock;

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
        if(uuid != null)
            mUuid = uuid;
        else mUuid = new ParcelUuid(new UUID(0, 0));
        mType = type;
        mAuth = auth;
        mEncrypt = encrypt;
        mDevice = device;
        mPort = port;
        mFd = fd;

        mSocketState = SocketState.INIT;

        if (device == null) {
            // Server socket
            mAddress = BluetoothAdapter.getDefaultAdapter().getAddress();
        } else {
            // Remote socket
            mAddress = device.getAddress();
        }
        mInputStream = new BluetoothInputStream(this);
        mOutputStream = new BluetoothOutputStream(this);
    }
    private BluetoothSocket(BluetoothSocket s) {
        mUuid = s.mUuid;
        mType = s.mType;
        mAuth = s.mAuth;
        mEncrypt = s.mEncrypt;
        mPort = s.mPort;
        mInputStream = new BluetoothInputStream(this);
        mOutputStream = new BluetoothOutputStream(this);
        mServiceName = s.mServiceName;
    }
    private BluetoothSocket acceptSocket(String RemoteAddr) throws IOException {
        BluetoothSocket as = new BluetoothSocket(this);
        as.mSocketState = SocketState.CONNECTED;
        FileDescriptor[] fds = mSocket.getAncillaryFileDescriptors();
        if (VDBG) Log.d(TAG, "socket fd passed by stack  fds: " + fds);
        if(fds == null || fds.length != 1) {
            Log.e(TAG, "socket fd passed from stack failed, fds: " + fds);
            as.close();
            throw new IOException("bt socket acept failed");
        }
        as.mSocket = new LocalSocket(fds[0]);
        as.mSocketIS = as.mSocket.getInputStream();
        as.mSocketOS = as.mSocket.getOutputStream();
        as.mAddress = RemoteAddr;
        as.mDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(RemoteAddr);
        as.mPort = mPort;
        return as;
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
    private int getSecurityFlags() {
        int flags = 0;
        if(mAuth)
            flags |= SEC_FLAG_AUTH;
        if(mEncrypt)
            flags |= SEC_FLAG_ENCRYPT;
        return flags;
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
        return mSocketState == SocketState.CONNECTED;
    }

    /*package*/ void setServiceName(String name) {
        mServiceName = name;
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
        if (mDevice == null) throw new IOException("Connect is called on null device");

        try {
            if (mSocketState == SocketState.CLOSED) throw new IOException("socket closed");
            IBluetooth bluetoothProxy = BluetoothAdapter.getDefaultAdapter().getBluetoothService(null);
            if (bluetoothProxy == null) throw new IOException("Bluetooth is off");
            mPfd = bluetoothProxy.connectSocket(mDevice, mType,
                    mUuid, mPort, getSecurityFlags());
            synchronized(this)
            {
                if (DBG) Log.d(TAG, "connect(), SocketState: " + mSocketState + ", mPfd: " + mPfd);
                if (mSocketState == SocketState.CLOSED) throw new IOException("socket closed");
                if (mPfd == null) throw new IOException("bt socket connect failed");
                FileDescriptor fd = mPfd.getFileDescriptor();
                mSocket = new LocalSocket(fd);
                mSocketIS = mSocket.getInputStream();
                mSocketOS = mSocket.getOutputStream();
            }
            int channel = readInt(mSocketIS);
            if (channel <= 0)
                throw new IOException("bt socket connect failed");
            mPort = channel;
            waitSocketSignal(mSocketIS);
            synchronized(this)
            {
                if (mSocketState == SocketState.CLOSED)
                    throw new IOException("bt socket closed");
                mSocketState = SocketState.CONNECTED;
            }
        } catch (RemoteException e) {
            Log.e(TAG, Log.getStackTraceString(new Throwable()));
        }
    }

    /**
     * Currently returns unix errno instead of throwing IOException,
     * so that BluetoothAdapter can check the error code for EADDRINUSE
     */
    /*package*/ int bindListen() {
        int ret;
        if (mSocketState == SocketState.CLOSED) return EBADFD;
        IBluetooth bluetoothProxy = BluetoothAdapter.getDefaultAdapter().getBluetoothService(null);
        if (bluetoothProxy == null) {
            Log.e(TAG, "bindListen fail, reason: bluetooth is off");
            return -1;
        }
        try {
            mPfd = bluetoothProxy.createSocketChannel(mType, mServiceName,
                    mUuid, mPort, getSecurityFlags());
        } catch (RemoteException e) {
            Log.e(TAG, Log.getStackTraceString(new Throwable()));
            return -1;
        }

        // read out port number
        try {
            synchronized(this) {
                if (VDBG) Log.d(TAG, "bindListen(), SocketState: " + mSocketState + ", mPfd: " +
                                mPfd);
                if(mSocketState != SocketState.INIT) return EBADFD;
                if(mPfd == null) return -1;
                FileDescriptor fd = mPfd.getFileDescriptor();
                if (VDBG) Log.d(TAG, "bindListen(), new LocalSocket ");
                mSocket = new LocalSocket(fd);
                if (VDBG) Log.d(TAG, "bindListen(), new LocalSocket.getInputStream() ");
                mSocketIS = mSocket.getInputStream();
                mSocketOS = mSocket.getOutputStream();
            }
            if (VDBG) Log.d(TAG, "bindListen(), readInt mSocketIS: " + mSocketIS);
            int channel = readInt(mSocketIS);
            synchronized(this) {
                if(mSocketState == SocketState.INIT)
                    mSocketState = SocketState.LISTENING;
            }
            if (VDBG) Log.d(TAG, "channel: " + channel);
            if (mPort == -1) {
                mPort = channel;
            } // else ASSERT(mPort == channel)
            ret = 0;
        } catch (IOException e) {
            Log.e(TAG, "bindListen, fail to get port number, exception: " + e);
            return -1;
        }
        return ret;
    }

    /*package*/ BluetoothSocket accept(int timeout) throws IOException {
        BluetoothSocket acceptedSocket;
        if (mSocketState != SocketState.LISTENING) throw new IOException("bt socket is not in listen state");
        if(timeout > 0) {
            Log.d(TAG, "accept() set timeout (ms):" + timeout);
           mSocket.setSoTimeout(timeout);
        }
        String RemoteAddr = waitSocketSignal(mSocketIS);
        if(timeout > 0)
            mSocket.setSoTimeout(0);
        synchronized(this)
        {
            if (mSocketState != SocketState.LISTENING)
                throw new IOException("bt socket is not in listen state");
            acceptedSocket = acceptSocket(RemoteAddr);
            //quick drop the reference of the file handle
        }
        return acceptedSocket;
    }

    /**
     * setSocketOpt for the Buetooth Socket.
     *
     * @param optionName socket option name
     * @param optionVal  socket option value
     * @param optionLen  socket option length
     * @return -1 on immediate error,
     *               0 otherwise
     * @hide
     */
    public int setSocketOpt(int optionName, byte [] optionVal, int optionLen) throws IOException {
        int ret = 0;
        if (mSocketState == SocketState.CLOSED) throw new IOException("socket closed");
        IBluetooth bluetoothProxy = BluetoothAdapter.getDefaultAdapter().getBluetoothService(null);
        if (bluetoothProxy == null) {
            Log.e(TAG, "setSocketOpt fail, reason: bluetooth is off");
            return -1;
        }
        try {
            if(VDBG) Log.d(TAG, "setSocketOpt(), mType: " + mType + " mPort: " + mPort);
            ret = bluetoothProxy.setSocketOpt(mType, mPort, optionName, optionVal, optionLen);
        } catch (RemoteException e) {
            Log.e(TAG, Log.getStackTraceString(new Throwable()));
            return -1;
        }
        return ret;
    }

    /**
     * getSocketOpt for the Buetooth Socket.
     *
     * @param optionName socket option name
     * @param optionVal  socket option value
     * @return -1 on immediate error,
     *               length of returned socket option otherwise
     * @hide
     */
    public int getSocketOpt(int optionName, byte [] optionVal) throws IOException {
        int ret = 0;
        if (mSocketState == SocketState.CLOSED) throw new IOException("socket closed");
        IBluetooth bluetoothProxy = BluetoothAdapter.getDefaultAdapter().getBluetoothService(null);
        if (bluetoothProxy == null) {
            Log.e(TAG, "getSocketOpt fail, reason: bluetooth is off");
            return -1;
        }
        try {
            if(VDBG) Log.d(TAG, "getSocketOpt(), mType: " + mType + " mPort: " + mPort);
            ret = bluetoothProxy.getSocketOpt(mType, mPort, optionName, optionVal);
        } catch (RemoteException e) {
            Log.e(TAG, Log.getStackTraceString(new Throwable()));
            return -1;
        }
        return ret;
    }

    /*package*/ int available() throws IOException {
        if (VDBG) Log.d(TAG, "available: " + mSocketIS);
        return mSocketIS.available();
    }
    /**
     * Wait until the data in sending queue is emptied. A polling version
     * for flush implementation. Used to ensure the writing data afterwards will
     * be packed in new RFCOMM frame.
     * @throws IOException
     *             if an i/o error occurs.
     */
    /*package*/ void flush() throws IOException {
        if (VDBG) Log.d(TAG, "flush: " + mSocketOS);
        mSocketOS.flush();
    }

    /*package*/ int read(byte[] b, int offset, int length) throws IOException {

            if (VDBG) Log.d(TAG, "read in:  " + mSocketIS + " len: " + length);
            int ret = mSocketIS.read(b, offset, length);
            if(ret < 0)
                throw new IOException("bt socket closed, read return: " + ret);
            if (VDBG) Log.d(TAG, "read out:  " + mSocketIS + " ret: " + ret);
            return ret;
    }

    /*package*/ int write(byte[] b, int offset, int length) throws IOException {

            if (VDBG) Log.d(TAG, "write: " + mSocketOS + " length: " + length);
            mSocketOS.write(b, offset, length);
            // There is no good way to confirm since the entire process is asynchronous anyway
            if (VDBG) Log.d(TAG, "write out: " + mSocketOS + " length: " + length);
            return length;
    }

    @Override
    public void close() throws IOException {
        if (VDBG) Log.d(TAG, "close() in, this: " + this + ", channel: " + mPort + ", state: " + mSocketState);
        if(mSocketState == SocketState.CLOSED)
            return;
        else
        {
            synchronized(this)
            {
                 if(mSocketState == SocketState.CLOSED)
                    return;
                 mSocketState = SocketState.CLOSED;
                 if (VDBG) Log.d(TAG, "close() this: " + this + ", channel: " + mPort + ", mSocketIS: " + mSocketIS +
                        ", mSocketOS: " + mSocketOS + "mSocket: " + mSocket);
                 if(mSocket != null) {
                    if (VDBG) Log.d(TAG, "Closing mSocket: " + mSocket);
                    mSocket.shutdownInput();
                    mSocket.shutdownOutput();
                    mSocket.close();
                    mSocket = null;
                }
                if(mPfd != null) {
                    mPfd.detachFd();
                    mPfd.close();
                    mPfd = null;
                }
           }
        }
    }

    /*package */ void removeChannel() {
    }

    /*package */ int getPort() {
        return mPort;
    }
    private String convertAddr(final byte[] addr)  {
        return String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                addr[0] , addr[1], addr[2], addr[3] , addr[4], addr[5]);
    }
    private String waitSocketSignal(InputStream is) throws IOException {
        byte [] sig = new byte[SOCK_SIGNAL_SIZE];
        int ret = readAll(is, sig);
        if (VDBG) Log.d(TAG, "waitSocketSignal read 16 bytes signal ret: " + ret);
        ByteBuffer bb = ByteBuffer.wrap(sig);
        bb.order(ByteOrder.nativeOrder());
        int size = bb.getShort();
        if(size != SOCK_SIGNAL_SIZE)
            throw new IOException("Connection failure, wrong signal size: " + size);
        byte [] addr = new byte[6];
        bb.get(addr);
        int channel = bb.getInt();
        int status = bb.getInt();
        String RemoteAddr = convertAddr(addr);
        if (VDBG) Log.d(TAG, "waitSocketSignal: sig size: " + size + ", remote addr: "
                + RemoteAddr + ", channel: " + channel + ", status: " + status);
        if(status != 0)
            throw new IOException("Connection failure, status: " + status);
        return RemoteAddr;
    }
    private int readAll(InputStream is, byte[] b) throws IOException {
        int left = b.length;
        while(left > 0) {
            int ret = is.read(b, b.length - left, left);
            if(ret <= 0)
                 throw new IOException("read failed, socket might closed or timeout, read ret: " + ret);
            left -= ret;
            if(left != 0)
                Log.w(TAG, "readAll() looping, read partial size: " + (b.length - left) +
                            ", expect size: " + b.length);
        }
        return b.length;
    }

    private int readInt(InputStream is) throws IOException {
        byte[] ibytes = new byte[4];
        int ret = readAll(is, ibytes);
        if (VDBG) Log.d(TAG, "inputStream.read ret: " + ret);
        ByteBuffer bb = ByteBuffer.wrap(ibytes);
        bb.order(ByteOrder.nativeOrder());
        return bb.getInt();
    }
}
