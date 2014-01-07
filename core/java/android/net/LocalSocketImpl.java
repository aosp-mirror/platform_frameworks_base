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

package android.net;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.FileDescriptor;
import java.net.SocketOptions;

import libcore.io.ErrnoException;
import libcore.io.Libcore;
import libcore.io.OsConstants;

/**
 * Socket implementation used for android.net.LocalSocket and
 * android.net.LocalServerSocket. Supports only AF_LOCAL sockets.
 */
class LocalSocketImpl
{
    private SocketInputStream fis;
    private SocketOutputStream fos;
    private Object readMonitor = new Object();
    private Object writeMonitor = new Object();

    /** null if closed or not yet created */
    private FileDescriptor fd;
    /** whether fd is created internally */
    private boolean mFdCreatedInternally;

    // These fields are accessed by native code;
    /** file descriptor array received during a previous read */
    FileDescriptor[] inboundFileDescriptors;
    /** file descriptor array that should be written during next write */
    FileDescriptor[] outboundFileDescriptors;

    /**
     * An input stream for local sockets. Needed because we may
     * need to read ancillary data.
     */
    class SocketInputStream extends InputStream {
        /** {@inheritDoc} */
        @Override
        public int available() throws IOException {
            return available_native(fd);
        }

        /** {@inheritDoc} */
        @Override
        public void close() throws IOException {
            LocalSocketImpl.this.close();
        }

        /** {@inheritDoc} */
        @Override
        public int read() throws IOException {
            int ret;
            synchronized (readMonitor) {
                FileDescriptor myFd = fd;
                if (myFd == null) throw new IOException("socket closed");

                ret = read_native(myFd);
                return ret;
            }
        }

        /** {@inheritDoc} */
        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        /** {@inheritDoc} */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            synchronized (readMonitor) {
                FileDescriptor myFd = fd;
                if (myFd == null) throw new IOException("socket closed");

                if (off < 0 || len < 0 || (off + len) > b.length ) {
                    throw new ArrayIndexOutOfBoundsException();
                }

                int ret = readba_native(b, off, len, myFd);

                return ret;
            }
        }
    }

    /**
     * An output stream for local sockets. Needed because we may
     * need to read ancillary data.
     */
    class SocketOutputStream extends OutputStream {
        /** {@inheritDoc} */
        @Override
        public void close() throws IOException {
            LocalSocketImpl.this.close();
        }

        /** {@inheritDoc} */
        @Override
        public void write (byte[] b) throws IOException {
            write(b, 0, b.length);
        }
        
        /** {@inheritDoc} */
        @Override
        public void write (byte[] b, int off, int len) throws IOException {
            synchronized (writeMonitor) {
                FileDescriptor myFd = fd;
                if (myFd == null) throw new IOException("socket closed");

                if (off < 0 || len < 0 || (off + len) > b.length ) {
                    throw new ArrayIndexOutOfBoundsException();
                }
                writeba_native(b, off, len, myFd);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void write (int b) throws IOException {
            synchronized (writeMonitor) {
                FileDescriptor myFd = fd;
                if (myFd == null) throw new IOException("socket closed");
                write_native(b, myFd);
            }
        }

        /**
         * Wait until the data in sending queue is emptied. A polling version
         * for flush implementation.
         * @throws IOException
         *             if an i/o error occurs.
         */
        @Override
        public void flush() throws IOException {
            FileDescriptor myFd = fd;
            if (myFd == null) throw new IOException("socket closed");
            while(pending_native(myFd) > 0) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private native int pending_native(FileDescriptor fd) throws IOException;
    private native int available_native(FileDescriptor fd) throws IOException;
    private native int read_native(FileDescriptor fd) throws IOException;
    private native int readba_native(byte[] b, int off, int len,
            FileDescriptor fd) throws IOException;
    private native void writeba_native(byte[] b, int off, int len,
            FileDescriptor fd) throws IOException;
    private native void write_native(int b, FileDescriptor fd)
            throws IOException;
    private native void connectLocal(FileDescriptor fd, String name,
            int namespace) throws IOException;
    private native void bindLocal(FileDescriptor fd, String name, int namespace)
            throws IOException;
    private native void listen_native(FileDescriptor fd, int backlog)
            throws IOException;
    private native void shutdown(FileDescriptor fd, boolean shutdownInput);
    private native Credentials getPeerCredentials_native(
            FileDescriptor fd) throws IOException;
    private native int getOption_native(FileDescriptor fd, int optID)
            throws IOException;
    private native void setOption_native(FileDescriptor fd, int optID,
            int b, int value) throws IOException;

//    private native LocalSocketAddress getSockName_native
//            (FileDescriptor fd) throws IOException;

    /**
     * Accepts a connection on a server socket.
     *
     * @param fd file descriptor of server socket
     * @param s socket implementation that will become the new socket
     * @return file descriptor of new socket
     */
    private native FileDescriptor accept
            (FileDescriptor fd, LocalSocketImpl s) throws IOException;

    /**
     * Create a new instance.
     */
    /*package*/ LocalSocketImpl()
    {
    }

    /**
     * Create a new instance from a file descriptor representing
     * a bound socket. The state of the file descriptor is not checked here
     *  but the caller can verify socket state by calling listen().
     *
     * @param fd non-null; bound file descriptor
     */
    /*package*/ LocalSocketImpl(FileDescriptor fd) throws IOException
    {
        this.fd = fd;
    }

    public String toString() {
        return super.toString() + " fd:" + fd;
    }

    /**
     * Creates a socket in the underlying OS.
     *
     * @param sockType either {@link LocalSocket#SOCKET_DGRAM}, {@link LocalSocket#SOCKET_STREAM}
     * or {@link LocalSocket#SOCKET_SEQPACKET}
     * @throws IOException
     */
    public void create (int sockType) throws IOException {
        // no error if socket already created
        // need this for LocalServerSocket.accept()
        if (fd == null) {
            int osType;
            switch (sockType) {
                case LocalSocket.SOCKET_DGRAM:
                    osType = OsConstants.SOCK_DGRAM;
                    break;
                case LocalSocket.SOCKET_STREAM:
                    osType = OsConstants.SOCK_STREAM;
                    break;
                case LocalSocket.SOCKET_SEQPACKET:
                    osType = OsConstants.SOCK_SEQPACKET;
                    break;
                default:
                    throw new IllegalStateException("unknown sockType");
            }
            try {
                fd = Libcore.os.socket(OsConstants.AF_UNIX, osType, 0);
                mFdCreatedInternally = true;
            } catch (ErrnoException e) {
                e.rethrowAsIOException();
            }
        }
    }

    /**
     * Closes the socket.
     *
     * @throws IOException
     */
    public void close() throws IOException {
        synchronized (LocalSocketImpl.this) {
            if ((fd == null) || (mFdCreatedInternally == false)) {
                fd = null;
                return;
            }
            try {
                Libcore.os.close(fd);
            } catch (ErrnoException e) {
                e.rethrowAsIOException();
            }
            fd = null;
        }
    }

    /** note timeout presently ignored */
    protected void connect(LocalSocketAddress address, int timeout)
                        throws IOException
    {        
        if (fd == null) {
            throw new IOException("socket not created");
        }

        connectLocal(fd, address.getName(), address.getNamespace().getId());
    }

    /**
     * Binds this socket to an endpoint name. May only be called on an instance
     * that has not yet been bound.
     *
     * @param endpoint endpoint address
     * @throws IOException
     */
    public void bind(LocalSocketAddress endpoint) throws IOException
    {
        if (fd == null) {
            throw new IOException("socket not created");
        }

        bindLocal(fd, endpoint.getName(), endpoint.getNamespace().getId());
    }

    protected void listen(int backlog) throws IOException
    {
        if (fd == null) {
            throw new IOException("socket not created");
        }

        listen_native(fd, backlog);
    }

    /**
     * Accepts a new connection to the socket. Blocks until a new
     * connection arrives.
     *
     * @param s a socket that will be used to represent the new connection.
     * @throws IOException
     */
    protected void accept(LocalSocketImpl s) throws IOException
    {
        if (fd == null) {
            throw new IOException("socket not created");
        }

        s.fd = accept(fd, s);
        s.mFdCreatedInternally = true;
    }

    /**
     * Retrieves the input stream for this instance.
     *
     * @return input stream
     * @throws IOException if socket has been closed or cannot be created.
     */
    protected InputStream getInputStream() throws IOException
    {
        if (fd == null) {
            throw new IOException("socket not created");
        }

        synchronized (this) {
            if (fis == null) {
                fis = new SocketInputStream();
            }

            return fis;
        }
    }

    /**
     * Retrieves the output stream for this instance.
     *
     * @return output stream
     * @throws IOException if socket has been closed or cannot be created.
     */
    protected OutputStream getOutputStream() throws IOException
    { 
        if (fd == null) {
            throw new IOException("socket not created");
        }

        synchronized (this) {
            if (fos == null) {
                fos = new SocketOutputStream();
            }

            return fos;
        }
    }

    /**
     * Returns the number of bytes available for reading without blocking.
     *
     * @return >= 0 count bytes available
     * @throws IOException
     */
    protected int available() throws IOException
    {
        return getInputStream().available();
    }

    /**
     * Shuts down the input side of the socket.
     *
     * @throws IOException
     */
    protected void shutdownInput() throws IOException
    {
        if (fd == null) {
            throw new IOException("socket not created");
        }

        shutdown(fd, true);
    }

    /**
     * Shuts down the output side of the socket.
     *
     * @throws IOException
     */
    protected void shutdownOutput() throws IOException
    {
        if (fd == null) {
            throw new IOException("socket not created");
        }

        shutdown(fd, false);
    }

    protected FileDescriptor getFileDescriptor()
    {
        return fd;
    }

    protected boolean supportsUrgentData()
    {
        return false;
    }

    protected void sendUrgentData(int data) throws IOException
    {
        throw new RuntimeException ("not impled");
    }

    public Object getOption(int optID) throws IOException
    {
        if (fd == null) {
            throw new IOException("socket not created");
        }

        if (optID == SocketOptions.SO_TIMEOUT) {
            return 0;
        }
        
        int value = getOption_native(fd, optID);
        switch (optID)
        {
            case SocketOptions.SO_RCVBUF:
            case SocketOptions.SO_SNDBUF:
                return value;
            case SocketOptions.SO_REUSEADDR:
            default:
                return value;
        }
    }

    public void setOption(int optID, Object value)
            throws IOException {
        /*
         * Boolean.FALSE is used to disable some options, so it
         * is important to distinguish between FALSE and unset.
         * We define it here that -1 is unset, 0 is FALSE, and 1
         * is TRUE.
         */
        int boolValue = -1;
        int intValue = 0;

        if (fd == null) {
            throw new IOException("socket not created");
        }

        if (value instanceof Integer) {
            intValue = (Integer)value;
        } else if (value instanceof Boolean) {
            boolValue = ((Boolean) value)? 1 : 0;
        } else {
            throw new IOException("bad value: " + value);
        }

        setOption_native(fd, optID, boolValue, intValue);
    }

    /**
     * Enqueues a set of file descriptors to send to the peer. The queue
     * is one deep. The file descriptors will be sent with the next write
     * of normal data, and will be delivered in a single ancillary message.
     * See "man 7 unix" SCM_RIGHTS on a desktop Linux machine.
     *
     * @param fds non-null; file descriptors to send.
     * @throws IOException
     */
    public void setFileDescriptorsForSend(FileDescriptor[] fds) {
        synchronized(writeMonitor) {
            outboundFileDescriptors = fds;
        }
    }

    /**
     * Retrieves a set of file descriptors that a peer has sent through
     * an ancillary message. This method retrieves the most recent set sent,
     * and then returns null until a new set arrives.
     * File descriptors may only be passed along with regular data, so this
     * method can only return a non-null after a read operation.
     *
     * @return null or file descriptor array
     * @throws IOException
     */
    public FileDescriptor[] getAncillaryFileDescriptors() throws IOException {
        synchronized(readMonitor) {
            FileDescriptor[] result = inboundFileDescriptors;

            inboundFileDescriptors = null;
            return result;
        }
    }

    /**
     * Retrieves the credentials of this socket's peer. Only valid on
     * connected sockets.
     *
     * @return non-null; peer credentials
     * @throws IOException
     */
    public Credentials getPeerCredentials() throws IOException
    {
        return getPeerCredentials_native(fd);
    }

    /**
     * Retrieves the socket name from the OS.
     *
     * @return non-null; socket name
     * @throws IOException on failure
     */
    public LocalSocketAddress getSockAddress() throws IOException
    {
        return null;
        //TODO implement this
        //return getSockName_native(fd);
    }

    @Override
    protected void finalize() throws IOException {
        close();
    }
}
