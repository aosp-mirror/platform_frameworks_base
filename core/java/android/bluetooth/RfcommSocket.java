/*
 * Copyright (C) 2007 The Android Open Source Project
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
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.FileDescriptor;

/**
 * The Android Bluetooth API is not finalized, and *will* change. Use at your
 * own risk.
 * 
 * This class implements an API to the Bluetooth RFCOMM layer. An RFCOMM socket
 * is similar to a normal socket in that it takes an address and a port number.
 * The difference is of course that the address is a Bluetooth-device address,
 * and the port number is an RFCOMM channel. The API allows for the
 * establishment of listening sockets via methods
 * {@link #bind(String, int) bind}, {@link #listen(int) listen}, and
 * {@link #accept(RfcommSocket, int) accept}, as well as for the making of
 * outgoing connections with {@link #connect(String, int) connect},
 * {@link #connectAsync(String, int) connectAsync}, and
 * {@link #waitForAsyncConnect(int) waitForAsyncConnect}.
 * 
 * After constructing a socket, you need to {@link #create() create} it and then
 * {@link #destroy() destroy} it when you are done using it. Both
 * {@link #create() create} and {@link #accept(RfcommSocket, int) accept} return
 * a {@link java.io.FileDescriptor FileDescriptor} for the actual data.
 * Alternatively, you may call {@link #getInputStream() getInputStream} and
 * {@link #getOutputStream() getOutputStream} to retrieve the respective streams
 * without going through the FileDescriptor.
 *
 * @hide
 */
public class RfcommSocket {

    /**
     * Used by the native implementation of the class.
     */
    private int mNativeData;

    /**
     * Used by the native implementation of the class.
     */
    private int mPort;

    /**
     * Used by the native implementation of the class.
     */
    private String mAddress;

    /**
     * We save the return value of {@link #create() create} and
     * {@link #accept(RfcommSocket,int) accept} in this variable, and use it to
     * retrieve the I/O streams.
     */
    private FileDescriptor mFd;

    /**
     * After a call to {@link #waitForAsyncConnect(int) waitForAsyncConnect},
     * if the return value is zero, then, the the remaining time left to wait is
     * written into this variable (by the native implementation). It is possible
     * that {@link #waitForAsyncConnect(int) waitForAsyncConnect} returns before
     * the user-specified timeout expires, which is why we save the remaining
     * time in this member variable for the user to retrieve by calling method
     * {@link #getRemainingAsyncConnectWaitingTimeMs() getRemainingAsyncConnectWaitingTimeMs}.
     */
    private int mTimeoutRemainingMs;

    /**
     * Set to true when an asynchronous (nonblocking) connect is in progress.
     * {@see #connectAsync(String,int)}.
     */
    private boolean mIsConnecting;

    /**
     * Set to true after a successful call to {@link #bind(String,int) bind} and
     * used for error checking in {@link #listen(int) listen}. Reset to false
     * on {@link #destroy() destroy}.
     */
    private boolean mIsBound = false;

    /**
     * Set to true after a successful call to {@link #listen(int) listen} and
     * used for error checking in {@link #accept(RfcommSocket,int) accept}.
     * Reset to false on {@link #destroy() destroy}.
     */
    private boolean mIsListening = false;

    /**
     * Used to store the remaining time after an accept with a non-negative
     * timeout returns unsuccessfully. It is possible that a blocking
     * {@link #accept(int) accept} may wait for less than the time specified by
     * the user, which is why we store the remainder in this member variable for
     * it to be retrieved with method
     * {@link #getRemainingAcceptWaitingTimeMs() getRemainingAcceptWaitingTimeMs}.
     */
    private int mAcceptTimeoutRemainingMs;

    /**
     * Maintained by {@link #getInputStream() getInputStream}.
     */
    protected FileInputStream mInputStream;

    /**
     * Maintained by {@link #getOutputStream() getOutputStream}.
     */
    protected FileOutputStream mOutputStream;

    private native void initializeNativeDataNative();

    /**
     * Constructor.
     */
    public RfcommSocket() {
        initializeNativeDataNative();
    }

    private native void cleanupNativeDataNative();

    /**
     * Called by the GC to clean up the native data that we set up when we
     * construct the object.
     */
    protected void finalize() throws Throwable {
        try {
            cleanupNativeDataNative();
        } finally {
            super.finalize();
        }
    }

    private native static void classInitNative();

    static {
        classInitNative();
    }

    /**
     * Creates a socket. You need to call this method before performing any
     * other operation on a socket.
     * 
     * @return FileDescriptor for the data stream.
     * @throws IOException
     * @see #destroy()
     */
    public FileDescriptor create() throws IOException {
        if (mFd == null) {
            mFd = createNative();
        }
        if (mFd == null) {
            throw new IOException("socket not created");
        }
        return mFd;
    }

    private native FileDescriptor createNative();

    /**
     * Destroys a socket created by {@link #create() create}. Call this
     * function when you no longer use the socket in order to release the
     * underlying OS resources.
     * 
     * @see #create()
     */
    public void destroy() {
        synchronized (this) {
            destroyNative();
            mFd = null;
            mIsBound = false;
            mIsListening = false;
        }
    }

    private native void destroyNative();

    /**
     * Returns the {@link java.io.FileDescriptor FileDescriptor} of the socket.
     * 
     * @return the FileDescriptor
     * @throws IOException
     *             when the socket has not been {@link #create() created}.
     */
    public FileDescriptor getFileDescriptor() throws IOException {
        if (mFd == null) {
            throw new IOException("socket not created");
        }
        return mFd;
    }

    /**
     * Retrieves the input stream from the socket. Alternatively, you can do
     * that from the FileDescriptor returned by {@link #create() create} or
     * {@link #accept(RfcommSocket, int) accept}.
     * 
     * @return InputStream
     * @throws IOException
     *             if you have not called {@link #create() create} on the
     *             socket.
     */
    public InputStream getInputStream() throws IOException {
        if (mFd == null) {
            throw new IOException("socket not created");
        }

        synchronized (this) {
            if (mInputStream == null) {
                mInputStream = new FileInputStream(mFd);
            }

            return mInputStream;
        }
    }

    /**
     * Retrieves the output stream from the socket. Alternatively, you can do
     * that from the FileDescriptor returned by {@link #create() create} or
     * {@link #accept(RfcommSocket, int) accept}.
     * 
     * @return OutputStream
     * @throws IOException
     *             if you have not called {@link #create() create} on the
     *             socket.
     */
    public OutputStream getOutputStream() throws IOException {
        if (mFd == null) {
            throw new IOException("socket not created");
        }

        synchronized (this) {
            if (mOutputStream == null) {
                mOutputStream = new FileOutputStream(mFd);
            }

            return mOutputStream;
        }
    }

    /**
     * Starts a blocking connect to a remote RFCOMM socket. It takes the address
     * of a device and the RFCOMM channel (port) to which to connect.
     * 
     * @param address
     *            is the Bluetooth address of the remote device.
     * @param port
     *            is the RFCOMM channel
     * @return true on success, false on failure
     * @throws IOException
     *             if {@link #create() create} has not been called.
     * @see #connectAsync(String, int)
     */
    public boolean connect(String address, int port) throws IOException {
        synchronized (this) {
            if (mFd == null) {
                throw new IOException("socket not created");
            }
            return connectNative(address, port);
        }
    }

    private native boolean connectNative(String address, int port);

    /**
     * Starts an asynchronous (nonblocking) connect to a remote RFCOMM socket.
     * It takes the address of the device to connect to, as well as the RFCOMM
     * channel (port). On successful return (return value is true), you need to
     * call method {@link #waitForAsyncConnect(int) waitForAsyncConnect} to
     * block for up to a specified number of milliseconds while waiting for the
     * asyncronous connect to complete.
     * 
     * @param address
     *            of remote device
     * @param port
     *            the RFCOMM channel
     * @return true when the asynchronous connect has successfully started,
     *         false if there was an error.
     * @throws IOException
     *             is you have not called {@link #create() create}
     * @see #waitForAsyncConnect(int)
     * @see #getRemainingAsyncConnectWaitingTimeMs()
     * @see #connect(String, int)
     */
    public boolean connectAsync(String address, int port) throws IOException {
        synchronized (this) {
            if (mFd == null) {
                throw new IOException("socket not created");
            }
            mIsConnecting = connectAsyncNative(address, port);
            return mIsConnecting;
        }
    }

    private native boolean connectAsyncNative(String address, int port);

    /**
     * Interrupts an asynchronous connect in progress. This method does nothing
     * when there is no asynchronous connect in progress.
     * 
     * @throws IOException
     *             if you have not called {@link #create() create}.
     * @see #connectAsync(String, int)
     */
    public void interruptAsyncConnect() throws IOException {
        synchronized (this) {
            if (mFd == null) {
                throw new IOException("socket not created");
            }
            if (mIsConnecting) {
                mIsConnecting = !interruptAsyncConnectNative();
            }
        }
    }

    private native boolean interruptAsyncConnectNative();

    /**
     * Tells you whether there is an asynchronous connect in progress. This
     * method returns an undefined value when there is a synchronous connect in
     * progress.
     * 
     * @return true if there is an asyc connect in progress, false otherwise
     * @see #connectAsync(String, int)
     */
    public boolean isConnecting() {
        return mIsConnecting;
    }

    /**
     * Blocks for a specified amount of milliseconds while waiting for an
     * asynchronous connect to complete. Returns an integer value to indicate
     * one of the following: the connect succeeded, the connect is still in
     * progress, or the connect failed. It is possible for this method to block
     * for less than the time specified by the user, and still return zero
     * (i.e., async connect is still in progress.) For this reason, if the
     * return value is zero, you need to call method
     * {@link #getRemainingAsyncConnectWaitingTimeMs() getRemainingAsyncConnectWaitingTimeMs}
     * to retrieve the remaining time.
     * 
     * @param timeoutMs
     *            the time to block while waiting for the async connect to
     *            complete.
     * @return a positive value if the connect succeeds; zero, if the connect is
     *         still in progress, and a negative value if the connect failed.
     * 
     * @throws IOException
     * @see #getRemainingAsyncConnectWaitingTimeMs()
     * @see #connectAsync(String, int)
     */
    public int waitForAsyncConnect(int timeoutMs) throws IOException {
        synchronized (this) {
            if (mFd == null) {
                throw new IOException("socket not created");
            }
            int ret = waitForAsyncConnectNative(timeoutMs);
            if (ret != 0) {
                mIsConnecting = false;
            }
            return ret;
        }
    }

    private native int waitForAsyncConnectNative(int timeoutMs);

    /**
     * Returns the number of milliseconds left to wait after the last call to
     * {@link #waitForAsyncConnect(int) waitForAsyncConnect}.
     * 
     * It is possible that waitForAsyncConnect() waits for less than the time
     * specified by the user, and still returns zero (i.e., async connect is
     * still in progress.) For this reason, if the return value is zero, you
     * need to call this method to retrieve the remaining time before you call
     * waitForAsyncConnect again.
     * 
     * @return the remaining timeout in milliseconds.
     * @see #waitForAsyncConnect(int)
     * @see #connectAsync(String, int)
     */
    public int getRemainingAsyncConnectWaitingTimeMs() {
        return mTimeoutRemainingMs;
    }

    /**
     * Shuts down both directions on a socket.
     * 
     * @return true on success, false on failure; if the return value is false,
     *         the socket might be left in a patially shut-down state (i.e. one
     *         direction is shut down, but the other is still open.) In this
     *         case, you should {@link #destroy() destroy} and then
     *         {@link #create() create} the socket again.
     * @throws IOException
     *             is you have not caled {@link #create() create}.
     * @see #shutdownInput()
     * @see #shutdownOutput()
     */
    public boolean shutdown() throws IOException {
        synchronized (this) {
            if (mFd == null) {
                throw new IOException("socket not created");
            }
            if (shutdownNative(true)) {
                return shutdownNative(false);
            }

            return false;
        }
    }

    /**
     * Shuts down the input stream of the socket, but leaves the output stream
     * in its current state.
     * 
     * @return true on success, false on failure
     * @throws IOException
     *             is you have not called {@link #create() create}
     * @see #shutdown()
     * @see #shutdownOutput()
     */
    public boolean shutdownInput() throws IOException {
        synchronized (this) {
            if (mFd == null) {
                throw new IOException("socket not created");
            }
            return shutdownNative(true);
        }
    }

    /**
     * Shut down the output stream of the socket, but leaves the input stream in
     * its current state.
     * 
     * @return true on success, false on failure
     * @throws IOException
     *             is you have not called {@link #create() create}
     * @see #shutdown()
     * @see #shutdownInput()
     */
    public boolean shutdownOutput() throws IOException {
        synchronized (this) {
            if (mFd == null) {
                throw new IOException("socket not created");
            }
            return shutdownNative(false);
        }
    }

    private native boolean shutdownNative(boolean shutdownInput);

    /**
     * Tells you whether a socket is connected to another socket. This could be
     * for input or output or both.
     * 
     * @return true if connected, false otherwise.
     * @see #isInputConnected()
     * @see #isOutputConnected()
     */
    public boolean isConnected() {
        return isConnectedNative() > 0;
    }

    /**
     * Determines whether input is connected (i.e., whether you can receive data
     * on this socket.)
     * 
     * @return true if input is connected, false otherwise.
     * @see #isConnected()
     * @see #isOutputConnected()
     */
    public boolean isInputConnected() {
        return (isConnectedNative() & 1) != 0;
    }

    /**
     * Determines whether output is connected (i.e., whether you can send data
     * on this socket.)
     * 
     * @return true if output is connected, false otherwise.
     * @see #isConnected()
     * @see #isInputConnected()
     */
    public boolean isOutputConnected() {
        return (isConnectedNative() & 2) != 0;
    }

    private native int isConnectedNative();

    /**
     * Binds a listening socket to the local device, or a non-listening socket
     * to a remote device. The port is automatically selected as the first
     * available port in the range 12 to 30.
     *
     * NOTE: Currently we ignore the device parameter and always bind the socket
     * to the local device, assuming that it is a listening socket.
     *
     * TODO: Use bind(0) in native code to have the kernel select an unused
     * port.
     *
     * @param device
     *            Bluetooth address of device to bind to (currently ignored).
     * @return true on success, false on failure
     * @throws IOException
     *             if you have not called {@link #create() create}
     * @see #listen(int)
     * @see #accept(RfcommSocket,int)
     */
    public boolean bind(String device) throws IOException {
        if (mFd == null) {
            throw new IOException("socket not created");
        }
        for (int port = 12; port <= 30; port++) {
            if (bindNative(device, port)) {
                mIsBound = true;
                return true;
            }
        }
        mIsBound = false;
        return false;
    }

    /**
     * Binds a listening socket to the local device, or a non-listening socket
     * to a remote device.
     *
     * NOTE: Currently we ignore the device parameter and always bind the socket
     * to the local device, assuming that it is a listening socket.
     *
     * @param device
     *            Bluetooth address of device to bind to (currently ignored).
     * @param port
     *            RFCOMM channel to bind socket to.
     * @return true on success, false on failure
     * @throws IOException
     *             if you have not called {@link #create() create}
     * @see #listen(int)
     * @see #accept(RfcommSocket,int)
     */
    public boolean bind(String device, int port) throws IOException {
        if (mFd == null) {
            throw new IOException("socket not created");
        }
        mIsBound = bindNative(device, port);
        return mIsBound;
    }

    private native boolean bindNative(String device, int port);

    /**
     * Starts listening for incoming connections on this socket, after it has
     * been bound to an address and RFCOMM channel with
     * {@link #bind(String,int) bind}.
     * 
     * @param backlog
     *            the number of pending incoming connections to queue for
     *            {@link #accept(RfcommSocket, int) accept}.
     * @return true on success, false on failure
     * @throws IOException
     *             if you have not called {@link #create() create} or if the
     *             socket has not been bound to a device and RFCOMM channel.
     */
    public boolean listen(int backlog) throws IOException {
        if (mFd == null) {
            throw new IOException("socket not created");
        }
        if (!mIsBound) {
            throw new IOException("socket not bound");
        }
        mIsListening = listenNative(backlog);
        return mIsListening;
    }

    private native boolean listenNative(int backlog);

    /**
     * Accepts incoming-connection requests for a listening socket bound to an
     * RFCOMM channel. The user may provide a time to wait for an incoming
     * connection.
     * 
     * Note that this method may return null (i.e., no incoming connection)
     * before the user-specified timeout expires. For this reason, on a null
     * return value, you need to call
     * {@link #getRemainingAcceptWaitingTimeMs() getRemainingAcceptWaitingTimeMs}
     * in order to see how much time is left to wait, before you call this
     * method again.
     * 
     * @param newSock
     *            is set to the new socket that is created as a result of a
     *            successful accept.
     * @param timeoutMs
     *            time (in milliseconds) to block while waiting to an
     *            incoming-connection request. A negative value is an infinite
     *            wait.
     * @return FileDescriptor of newSock on success, null on failure. Failure
     *         occurs if the timeout expires without a successful connect.
     * @throws IOException
     *             if the socket has not been {@link #create() create}ed, is
     *             not bound, or is not a listening socket.
     * @see #bind(String, int)
     * @see #listen(int)
     * @see #getRemainingAcceptWaitingTimeMs()
     */
    public FileDescriptor accept(RfcommSocket newSock, int timeoutMs)
            throws IOException {
        synchronized (newSock) {
            if (mFd == null) {
                throw new IOException("socket not created");
            }
            if (mIsListening == false) {
                throw new IOException("not listening on socket");
            }
            newSock.mFd = acceptNative(newSock, timeoutMs);
            return newSock.mFd;
        }
    }

    /**
     * Returns the number of milliseconds left to wait after the last call to
     * {@link #accept(RfcommSocket, int) accept}.
     * 
     * Since accept() may return null (i.e., no incoming connection) before the
     * user-specified timeout expires, you need to call this method in order to
     * see how much time is left to wait, and wait for that amount of time
     * before you call accept again.
     * 
     * @return the remaining time, in milliseconds.
     */
    public int getRemainingAcceptWaitingTimeMs() {
        return mAcceptTimeoutRemainingMs;
    }

    private native FileDescriptor acceptNative(RfcommSocket newSock,
            int timeoutMs);

    /**
     * Get the port (rfcomm channel) associated with this socket.
     *
     * This is only valid if the port has been set via a successful call to
     * {@link #bind(String, int)}, {@link #connect(String, int)}
     * or {@link #connectAsync(String, int)}. This can be checked
     * with {@link #isListening()} and {@link #isConnected()}.
     * @return Port (rfcomm channel)
     */
    public int getPort() throws IOException {
        if (mFd == null) {
            throw new IOException("socket not created");
        }
        if (!mIsListening && !isConnected()) {
            throw new IOException("not listening or connected on socket");
        }
        return mPort;
    }

    /**
     * Return true if this socket is listening ({@link #listen(int)}
     * has been called successfully).
     */
    public boolean isListening() {
        return mIsListening;
    }
}
