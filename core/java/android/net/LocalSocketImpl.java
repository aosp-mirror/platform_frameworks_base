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

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructLinger;
import android.system.StructTimeval;
import android.util.MutableInt;

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
            FileDescriptor myFd = fd;
            if (myFd == null) throw new IOException("socket closed");

            MutableInt avail = new MutableInt(0);
            try {
                Os.ioctlInt(myFd, OsConstants.FIONREAD, avail);
            } catch (ErrnoException e) {
                throw e.rethrowAsIOException();
            }
            return avail.value;
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

            // Loop until the output buffer is empty.
            MutableInt pending = new MutableInt(0);
            while (true) {
                try {
                    // See linux/net/unix/af_unix.c
                    Os.ioctlInt(myFd, OsConstants.TIOCOUTQ, pending);
                } catch (ErrnoException e) {
                    throw e.rethrowAsIOException();
                }

                if (pending.value <= 0) {
                    // The output buffer is empty.
                    break;
                }

                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

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
    private native Credentials getPeerCredentials_native(
            FileDescriptor fd) throws IOException;

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
    public void create(int sockType) throws IOException {
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
                fd = Os.socket(OsConstants.AF_UNIX, osType, 0);
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
                Os.close(fd);
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
        try {
            Os.listen(fd, backlog);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Accepts a new connection to the socket. Blocks until a new
     * connection arrives.
     *
     * @param s a socket that will be used to represent the new connection.
     * @throws IOException
     */
    protected void accept(LocalSocketImpl s) throws IOException {
        if (fd == null) {
            throw new IOException("socket not created");
        }

        try {
            s.fd = Os.accept(fd, null /* address */);
            s.mFdCreatedInternally = true;
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
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

        try {
            Os.shutdown(fd, OsConstants.SHUT_RD);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
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

        try {
            Os.shutdown(fd, OsConstants.SHUT_WR);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
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

        try {
            Object toReturn;
            switch (optID) {
                case SocketOptions.SO_TIMEOUT:
                    StructTimeval timeval = Os.getsockoptTimeval(fd, OsConstants.SOL_SOCKET,
                            OsConstants.SO_SNDTIMEO);
                    toReturn = (int) timeval.toMillis();
                    break;
                case SocketOptions.SO_RCVBUF:
                case SocketOptions.SO_SNDBUF:
                case SocketOptions.SO_REUSEADDR:
                    int osOpt = javaSoToOsOpt(optID);
                    toReturn = Os.getsockoptInt(fd, OsConstants.SOL_SOCKET, osOpt);
                    break;
                case SocketOptions.SO_LINGER:
                    StructLinger linger=
                            Os.getsockoptLinger(fd, OsConstants.SOL_SOCKET, OsConstants.SO_LINGER);
                    if (!linger.isOn()) {
                        toReturn = -1;
                    } else {
                        toReturn = linger.l_linger;
                    }
                    break;
                case SocketOptions.TCP_NODELAY:
                    toReturn = Os.getsockoptInt(fd, OsConstants.IPPROTO_TCP,
                            OsConstants.TCP_NODELAY);
                    break;
                default:
                    throw new IOException("Unknown option: " + optID);
            }
            return toReturn;
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    public void setOption(int optID, Object value)
            throws IOException {

        if (fd == null) {
            throw new IOException("socket not created");
        }

        /*
         * Boolean.FALSE is used to disable some options, so it
         * is important to distinguish between FALSE and unset.
         * We define it here that -1 is unset, 0 is FALSE, and 1
         * is TRUE.
         */
        int boolValue = -1;
        int intValue = 0;
        if (value instanceof Integer) {
            intValue = (Integer)value;
        } else if (value instanceof Boolean) {
            boolValue = ((Boolean) value)? 1 : 0;
        } else {
            throw new IOException("bad value: " + value);
        }

        try {
            switch (optID) {
                case SocketOptions.SO_LINGER:
                    StructLinger linger = new StructLinger(boolValue, intValue);
                    Os.setsockoptLinger(fd, OsConstants.SOL_SOCKET, OsConstants.SO_LINGER, linger);
                    break;
                case SocketOptions.SO_TIMEOUT:
                    // The option must set both send and receive timeouts.
                    // Note: The incoming timeout value is in milliseconds.
                    StructTimeval timeval = StructTimeval.fromMillis(intValue);
                    Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                            timeval);
                    Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
                            timeval);
                    break;
                case SocketOptions.SO_RCVBUF:
                case SocketOptions.SO_SNDBUF:
                case SocketOptions.SO_REUSEADDR:
                    int osOpt = javaSoToOsOpt(optID);
                    Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, osOpt, intValue);
                    break;
                case SocketOptions.TCP_NODELAY:
                    Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY,
                            intValue);
                    break;
                default:
                    throw new IOException("Unknown option: " + optID);
            }
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
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
    public Credentials getPeerCredentials() throws IOException {
        return getPeerCredentials_native(fd);
    }

    /**
     * Retrieves the socket name from the OS.
     *
     * @return non-null; socket name
     * @throws IOException on failure
     */
    public LocalSocketAddress getSockAddress() throws IOException {
        // This method has never been implemented.
        return null;
    }

    @Override
    protected void finalize() throws IOException {
        close();
    }

    private static int javaSoToOsOpt(int optID) {
        switch (optID) {
            case SocketOptions.SO_SNDBUF:
                return OsConstants.SO_SNDBUF;
            case SocketOptions.SO_RCVBUF:
                return OsConstants.SO_RCVBUF;
            case SocketOptions.SO_REUSEADDR:
                return OsConstants.SO_REUSEADDR;
            default:
                throw new UnsupportedOperationException("Unknown option: " + optID);
        }
    }
}
