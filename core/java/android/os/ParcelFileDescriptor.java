/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import static libcore.io.OsConstants.AF_UNIX;
import static libcore.io.OsConstants.SEEK_SET;
import static libcore.io.OsConstants.SOCK_STREAM;
import static libcore.io.OsConstants.S_ISLNK;
import static libcore.io.OsConstants.S_ISREG;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.util.Log;

import dalvik.system.CloseGuard;

import libcore.io.ErrnoException;
import libcore.io.IoUtils;
import libcore.io.Libcore;
import libcore.io.Memory;
import libcore.io.OsConstants;
import libcore.io.StructStat;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.ByteOrder;

/**
 * The FileDescriptor returned by {@link Parcel#readFileDescriptor}, allowing
 * you to close it when done with it.
 */
public class ParcelFileDescriptor implements Parcelable, Closeable {
    private static final String TAG = "ParcelFileDescriptor";

    private final FileDescriptor mFd;

    /**
     * Optional socket used to communicate close events, status at close, and
     * detect remote process crashes.
     */
    private FileDescriptor mCommFd;

    /**
     * Wrapped {@link ParcelFileDescriptor}, if any. Used to avoid
     * double-closing {@link #mFd}.
     */
    private final ParcelFileDescriptor mWrapped;

    /**
     * Maximum {@link #mStatusBuf} size; longer status messages will be
     * truncated.
     */
    private static final int MAX_STATUS = 1024;

    /**
     * Temporary buffer used by {@link #readCommStatus(FileDescriptor, byte[])},
     * allocated on-demand.
     */
    private byte[] mStatusBuf;

    /**
     * Status read by {@link #checkError()}, or null if not read yet.
     */
    private Status mStatus;

    private volatile boolean mClosed;

    private final CloseGuard mGuard = CloseGuard.get();

    /**
     * For use with {@link #open}: if {@link #MODE_CREATE} has been supplied and
     * this file doesn't already exist, then create the file with permissions
     * such that any application can read it.
     *
     * @deprecated Creating world-readable files is very dangerous, and likely
     *             to cause security holes in applications. It is strongly
     *             discouraged; instead, applications should use more formal
     *             mechanism for interactions such as {@link ContentProvider},
     *             {@link BroadcastReceiver}, and {@link android.app.Service}.
     *             There are no guarantees that this access mode will remain on
     *             a file, such as when it goes through a backup and restore.
     */
    @Deprecated
    public static final int MODE_WORLD_READABLE = 0x00000001;

    /**
     * For use with {@link #open}: if {@link #MODE_CREATE} has been supplied and
     * this file doesn't already exist, then create the file with permissions
     * such that any application can write it.
     *
     * @deprecated Creating world-writable files is very dangerous, and likely
     *             to cause security holes in applications. It is strongly
     *             discouraged; instead, applications should use more formal
     *             mechanism for interactions such as {@link ContentProvider},
     *             {@link BroadcastReceiver}, and {@link android.app.Service}.
     *             There are no guarantees that this access mode will remain on
     *             a file, such as when it goes through a backup and restore.
     */
    @Deprecated
    public static final int MODE_WORLD_WRITEABLE = 0x00000002;

    /**
     * For use with {@link #open}: open the file with read-only access.
     */
    public static final int MODE_READ_ONLY = 0x10000000;

    /**
     * For use with {@link #open}: open the file with write-only access.
     */
    public static final int MODE_WRITE_ONLY = 0x20000000;

    /**
     * For use with {@link #open}: open the file with read and write access.
     */
    public static final int MODE_READ_WRITE = 0x30000000;

    /**
     * For use with {@link #open}: create the file if it doesn't already exist.
     */
    public static final int MODE_CREATE = 0x08000000;

    /**
     * For use with {@link #open}: erase contents of file when opening.
     */
    public static final int MODE_TRUNCATE = 0x04000000;

    /**
     * For use with {@link #open}: append to end of file while writing.
     */
    public static final int MODE_APPEND = 0x02000000;

    /**
     * Create a new ParcelFileDescriptor wrapped around another descriptor. By
     * default all method calls are delegated to the wrapped descriptor.
     */
    public ParcelFileDescriptor(ParcelFileDescriptor wrapped) {
        // We keep a strong reference to the wrapped PFD, and rely on its
        // finalizer to trigger CloseGuard. All calls are delegated to wrapper.
        mWrapped = wrapped;
        mFd = null;
        mCommFd = null;
        mClosed = true;
    }

    /** {@hide} */
    public ParcelFileDescriptor(FileDescriptor fd) {
        this(fd, null);
    }

    /** {@hide} */
    public ParcelFileDescriptor(FileDescriptor fd, FileDescriptor commChannel) {
        if (fd == null) {
            throw new NullPointerException("FileDescriptor must not be null");
        }
        mWrapped = null;
        mFd = fd;
        mCommFd = commChannel;
        mGuard.open("close");
    }

    /**
     * Create a new ParcelFileDescriptor accessing a given file.
     *
     * @param file The file to be opened.
     * @param mode The desired access mode, must be one of
     *            {@link #MODE_READ_ONLY}, {@link #MODE_WRITE_ONLY}, or
     *            {@link #MODE_READ_WRITE}; may also be any combination of
     *            {@link #MODE_CREATE}, {@link #MODE_TRUNCATE},
     *            {@link #MODE_WORLD_READABLE}, and
     *            {@link #MODE_WORLD_WRITEABLE}.
     * @return a new ParcelFileDescriptor pointing to the given file.
     * @throws FileNotFoundException if the given file does not exist or can not
     *             be opened with the requested mode.
     * @see #parseMode(String)
     */
    public static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
        final FileDescriptor fd = openInternal(file, mode);
        if (fd == null) return null;

        return new ParcelFileDescriptor(fd);
    }

    /**
     * Create a new ParcelFileDescriptor accessing a given file.
     *
     * @param file The file to be opened.
     * @param mode The desired access mode, must be one of
     *            {@link #MODE_READ_ONLY}, {@link #MODE_WRITE_ONLY}, or
     *            {@link #MODE_READ_WRITE}; may also be any combination of
     *            {@link #MODE_CREATE}, {@link #MODE_TRUNCATE},
     *            {@link #MODE_WORLD_READABLE}, and
     *            {@link #MODE_WORLD_WRITEABLE}.
     * @param handler to call listener from; must not be null.
     * @param listener to be invoked when the returned descriptor has been
     *            closed; must not be null.
     * @return a new ParcelFileDescriptor pointing to the given file.
     * @throws FileNotFoundException if the given file does not exist or can not
     *             be opened with the requested mode.
     * @see #parseMode(String)
     */
    public static ParcelFileDescriptor open(
            File file, int mode, Handler handler, OnCloseListener listener) throws IOException {
        if (handler == null) {
            throw new IllegalArgumentException("Handler must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }

        final FileDescriptor fd = openInternal(file, mode);
        if (fd == null) return null;

        final FileDescriptor[] comm = createCommSocketPair();
        final ParcelFileDescriptor pfd = new ParcelFileDescriptor(fd, comm[0]);

        // Kick off thread to watch for status updates
        IoUtils.setBlocking(comm[1], true);
        final ListenerBridge bridge = new ListenerBridge(comm[1], handler.getLooper(), listener);
        bridge.start();

        return pfd;
    }

    private static FileDescriptor openInternal(File file, int mode) throws FileNotFoundException {
        if ((mode & MODE_READ_WRITE) == 0) {
            throw new IllegalArgumentException(
                    "Must specify MODE_READ_ONLY, MODE_WRITE_ONLY, or MODE_READ_WRITE");
        }

        final String path = file.getPath();
        return Parcel.openFileDescriptor(path, mode);
    }

    /**
     * Create a new ParcelFileDescriptor that is a dup of an existing
     * FileDescriptor.  This obeys standard POSIX semantics, where the
     * new file descriptor shared state such as file position with the
     * original file descriptor.
     */
    public static ParcelFileDescriptor dup(FileDescriptor orig) throws IOException {
        try {
            final FileDescriptor fd = Libcore.os.dup(orig);
            return new ParcelFileDescriptor(fd);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Create a new ParcelFileDescriptor that is a dup of the existing
     * FileDescriptor.  This obeys standard POSIX semantics, where the
     * new file descriptor shared state such as file position with the
     * original file descriptor.
     */
    public ParcelFileDescriptor dup() throws IOException {
        if (mWrapped != null) {
            return mWrapped.dup();
        } else {
            return dup(getFileDescriptor());
        }
    }

    /**
     * Create a new ParcelFileDescriptor from a raw native fd.  The new
     * ParcelFileDescriptor holds a dup of the original fd passed in here,
     * so you must still close that fd as well as the new ParcelFileDescriptor.
     *
     * @param fd The native fd that the ParcelFileDescriptor should dup.
     *
     * @return Returns a new ParcelFileDescriptor holding a FileDescriptor
     * for a dup of the given fd.
     */
    public static ParcelFileDescriptor fromFd(int fd) throws IOException {
        final FileDescriptor original = new FileDescriptor();
        original.setInt$(fd);

        try {
            final FileDescriptor dup = Libcore.os.dup(original);
            return new ParcelFileDescriptor(dup);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Take ownership of a raw native fd in to a new ParcelFileDescriptor.
     * The returned ParcelFileDescriptor now owns the given fd, and will be
     * responsible for closing it.  You must not close the fd yourself.
     *
     * @param fd The native fd that the ParcelFileDescriptor should adopt.
     *
     * @return Returns a new ParcelFileDescriptor holding a FileDescriptor
     * for the given fd.
     */
    public static ParcelFileDescriptor adoptFd(int fd) {
        final FileDescriptor fdesc = new FileDescriptor();
        fdesc.setInt$(fd);

        return new ParcelFileDescriptor(fdesc);
    }

    /**
     * Create a new ParcelFileDescriptor from the specified Socket.  The new
     * ParcelFileDescriptor holds a dup of the original FileDescriptor in
     * the Socket, so you must still close the Socket as well as the new
     * ParcelFileDescriptor.
     *
     * @param socket The Socket whose FileDescriptor is used to create
     *               a new ParcelFileDescriptor.
     *
     * @return A new ParcelFileDescriptor with the FileDescriptor of the
     *         specified Socket.
     */
    public static ParcelFileDescriptor fromSocket(Socket socket) {
        FileDescriptor fd = socket.getFileDescriptor$();
        return fd != null ? new ParcelFileDescriptor(fd) : null;
    }

    /**
     * Create a new ParcelFileDescriptor from the specified DatagramSocket.
     *
     * @param datagramSocket The DatagramSocket whose FileDescriptor is used
     *               to create a new ParcelFileDescriptor.
     *
     * @return A new ParcelFileDescriptor with the FileDescriptor of the
     *         specified DatagramSocket.
     */
    public static ParcelFileDescriptor fromDatagramSocket(DatagramSocket datagramSocket) {
        FileDescriptor fd = datagramSocket.getFileDescriptor$();
        return fd != null ? new ParcelFileDescriptor(fd) : null;
    }

    /**
     * Create two ParcelFileDescriptors structured as a data pipe.  The first
     * ParcelFileDescriptor in the returned array is the read side; the second
     * is the write side.
     */
    public static ParcelFileDescriptor[] createPipe() throws IOException {
        try {
            final FileDescriptor[] fds = Libcore.os.pipe();
            return new ParcelFileDescriptor[] {
                    new ParcelFileDescriptor(fds[0]),
                    new ParcelFileDescriptor(fds[1]) };
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Create two ParcelFileDescriptors structured as a data pipe. The first
     * ParcelFileDescriptor in the returned array is the read side; the second
     * is the write side.
     * <p>
     * The write end has the ability to deliver an error message through
     * {@link #closeWithError(String)} which can be handled by the read end
     * calling {@link #checkError()}, usually after detecting an EOF.
     * This can also be used to detect remote crashes.
     */
    public static ParcelFileDescriptor[] createReliablePipe() throws IOException {
        try {
            final FileDescriptor[] comm = createCommSocketPair();
            final FileDescriptor[] fds = Libcore.os.pipe();
            return new ParcelFileDescriptor[] {
                    new ParcelFileDescriptor(fds[0], comm[0]),
                    new ParcelFileDescriptor(fds[1], comm[1]) };
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Create two ParcelFileDescriptors structured as a pair of sockets
     * connected to each other. The two sockets are indistinguishable.
     */
    public static ParcelFileDescriptor[] createSocketPair() throws IOException {
        try {
            final FileDescriptor fd0 = new FileDescriptor();
            final FileDescriptor fd1 = new FileDescriptor();
            Libcore.os.socketpair(AF_UNIX, SOCK_STREAM, 0, fd0, fd1);
            return new ParcelFileDescriptor[] {
                    new ParcelFileDescriptor(fd0),
                    new ParcelFileDescriptor(fd1) };
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Create two ParcelFileDescriptors structured as a pair of sockets
     * connected to each other. The two sockets are indistinguishable.
     * <p>
     * Both ends have the ability to deliver an error message through
     * {@link #closeWithError(String)} which can be detected by the other end
     * calling {@link #checkError()}, usually after detecting an EOF.
     * This can also be used to detect remote crashes.
     */
    public static ParcelFileDescriptor[] createReliableSocketPair() throws IOException {
        try {
            final FileDescriptor[] comm = createCommSocketPair();
            final FileDescriptor fd0 = new FileDescriptor();
            final FileDescriptor fd1 = new FileDescriptor();
            Libcore.os.socketpair(AF_UNIX, SOCK_STREAM, 0, fd0, fd1);
            return new ParcelFileDescriptor[] {
                    new ParcelFileDescriptor(fd0, comm[0]),
                    new ParcelFileDescriptor(fd1, comm[1]) };
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    private static FileDescriptor[] createCommSocketPair() throws IOException {
        try {
            final FileDescriptor comm1 = new FileDescriptor();
            final FileDescriptor comm2 = new FileDescriptor();
            Libcore.os.socketpair(AF_UNIX, SOCK_STREAM, 0, comm1, comm2);
            IoUtils.setBlocking(comm1, false);
            IoUtils.setBlocking(comm2, false);
            return new FileDescriptor[] { comm1, comm2 };
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * @hide Please use createPipe() or ContentProvider.openPipeHelper().
     * Gets a file descriptor for a read-only copy of the given data.
     *
     * @param data Data to copy.
     * @param name Name for the shared memory area that may back the file descriptor.
     *        This is purely informative and may be {@code null}.
     * @return A ParcelFileDescriptor.
     * @throws IOException if there is an error while creating the shared memory area.
     */
    @Deprecated
    public static ParcelFileDescriptor fromData(byte[] data, String name) throws IOException {
        if (data == null) return null;
        MemoryFile file = new MemoryFile(name, data.length);
        if (data.length > 0) {
            file.writeBytes(data, 0, 0, data.length);
        }
        file.deactivate();
        FileDescriptor fd = file.getFileDescriptor();
        return fd != null ? new ParcelFileDescriptor(fd) : null;
    }

    /**
     * Converts a string representing a file mode, such as "rw", into a bitmask suitable for use
     * with {@link #open}.
     * <p>
     * @param mode The string representation of the file mode.
     * @return A bitmask representing the given file mode.
     * @throws IllegalArgumentException if the given string does not match a known file mode.
     */
    public static int parseMode(String mode) {
        final int modeBits;
        if ("r".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_READ_ONLY;
        } else if ("w".equals(mode) || "wt".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if ("wa".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_APPEND;
        } else if ("rw".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE;
        } else if ("rwt".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else {
            throw new IllegalArgumentException("Bad mode '" + mode + "'");
        }
        return modeBits;
    }

    /**
     * Retrieve the actual FileDescriptor associated with this object.
     *
     * @return Returns the FileDescriptor associated with this object.
     */
    public FileDescriptor getFileDescriptor() {
        if (mWrapped != null) {
            return mWrapped.getFileDescriptor();
        } else {
            return mFd;
        }
    }

    /**
     * Return the total size of the file representing this fd, as determined by
     * {@code stat()}. Returns -1 if the fd is not a file.
     */
    public long getStatSize() {
        if (mWrapped != null) {
            return mWrapped.getStatSize();
        } else {
            try {
                final StructStat st = Libcore.os.fstat(mFd);
                if (S_ISREG(st.st_mode) || S_ISLNK(st.st_mode)) {
                    return st.st_size;
                } else {
                    return -1;
                }
            } catch (ErrnoException e) {
                Log.w(TAG, "fstat() failed: " + e);
                return -1;
            }
        }
    }

    /**
     * This is needed for implementing AssetFileDescriptor.AutoCloseOutputStream,
     * and I really don't think we want it to be public.
     * @hide
     */
    public long seekTo(long pos) throws IOException {
        if (mWrapped != null) {
            return mWrapped.seekTo(pos);
        } else {
            try {
                return Libcore.os.lseek(mFd, pos, SEEK_SET);
            } catch (ErrnoException e) {
                throw e.rethrowAsIOException();
            }
        }
    }

    /**
     * Return the native fd int for this ParcelFileDescriptor.  The
     * ParcelFileDescriptor still owns the fd, and it still must be closed
     * through this API.
     */
    public int getFd() {
        if (mWrapped != null) {
            return mWrapped.getFd();
        } else {
            if (mClosed) {
                throw new IllegalStateException("Already closed");
            }
            return mFd.getInt$();
        }
    }

    /**
     * Return the native fd int for this ParcelFileDescriptor and detach it from
     * the object here. You are now responsible for closing the fd in native
     * code.
     * <p>
     * You should not detach when the original creator of the descriptor is
     * expecting a reliable signal through {@link #close()} or
     * {@link #closeWithError(String)}.
     *
     * @see #canDetectErrors()
     */
    public int detachFd() {
        if (mWrapped != null) {
            return mWrapped.detachFd();
        } else {
            if (mClosed) {
                throw new IllegalStateException("Already closed");
            }
            final int fd = getFd();
            Parcel.clearFileDescriptor(mFd);
            writeCommStatusAndClose(Status.DETACHED, null);
            return fd;
        }
    }

    /**
     * Close the ParcelFileDescriptor. This implementation closes the underlying
     * OS resources allocated to represent this stream.
     *
     * @throws IOException
     *             If an error occurs attempting to close this ParcelFileDescriptor.
     */
    @Override
    public void close() throws IOException {
        if (mWrapped != null) {
            try {
                mWrapped.close();
            } finally {
                releaseResources();
            }
        } else {
            closeWithStatus(Status.OK, null);
        }
    }

    /**
     * Close the ParcelFileDescriptor, informing any peer that an error occurred
     * while processing. If the creator of this descriptor is not observing
     * errors, it will close normally.
     *
     * @param msg describing the error; must not be null.
     */
    public void closeWithError(String msg) throws IOException {
        if (mWrapped != null) {
            try {
                mWrapped.closeWithError(msg);
            } finally {
                releaseResources();
            }
        } else {
            if (msg == null) {
                throw new IllegalArgumentException("Message must not be null");
            }
            closeWithStatus(Status.ERROR, msg);
        }
    }

    private void closeWithStatus(int status, String msg) {
        if (mClosed) return;
        mClosed = true;
        mGuard.close();
        // Status MUST be sent before closing actual descriptor
        writeCommStatusAndClose(status, msg);
        IoUtils.closeQuietly(mFd);
        releaseResources();
    }

    /**
     * Called when the fd is being closed, for subclasses to release any other resources
     * associated with it, such as acquired providers.
     * @hide
     */
    public void releaseResources() {
    }

    private byte[] getOrCreateStatusBuffer() {
        if (mStatusBuf == null) {
            mStatusBuf = new byte[MAX_STATUS];
        }
        return mStatusBuf;
    }

    private void writeCommStatusAndClose(int status, String msg) {
        if (mCommFd == null) {
            // Not reliable, or someone already sent status
            if (msg != null) {
                Log.w(TAG, "Unable to inform peer: " + msg);
            }
            return;
        }

        if (status == Status.DETACHED) {
            Log.w(TAG, "Peer expected signal when closed; unable to deliver after detach");
        }

        try {
            if (status == Status.SILENCE) return;

            // Since we're about to close, read off any remote status. It's
            // okay to remember missing here.
            mStatus = readCommStatus(mCommFd, getOrCreateStatusBuffer());

            // Skip writing status when other end has already gone away.
            if (mStatus != null) return;

            try {
                final byte[] buf = getOrCreateStatusBuffer();
                int writePtr = 0;

                Memory.pokeInt(buf, writePtr, status, ByteOrder.BIG_ENDIAN);
                writePtr += 4;

                if (msg != null) {
                    final byte[] rawMsg = msg.getBytes();
                    final int len = Math.min(rawMsg.length, buf.length - writePtr);
                    System.arraycopy(rawMsg, 0, buf, writePtr, len);
                    writePtr += len;
                }

                Libcore.os.write(mCommFd, buf, 0, writePtr);
            } catch (ErrnoException e) {
                // Reporting status is best-effort
                Log.w(TAG, "Failed to report status: " + e);
            }

        } finally {
            IoUtils.closeQuietly(mCommFd);
            mCommFd = null;
        }
    }

    private static Status readCommStatus(FileDescriptor comm, byte[] buf) {
        try {
            final int n = Libcore.os.read(comm, buf, 0, buf.length);
            if (n == 0) {
                // EOF means they're dead
                return new Status(Status.DEAD);
            } else {
                final int status = Memory.peekInt(buf, 0, ByteOrder.BIG_ENDIAN);
                if (status == Status.ERROR) {
                    final String msg = new String(buf, 4, n - 4);
                    return new Status(status, msg);
                }
                return new Status(status);
            }
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EAGAIN) {
                // Remote is still alive, but no status written yet
                return null;
            } else {
                Log.d(TAG, "Failed to read status; assuming dead: " + e);
                return new Status(Status.DEAD);
            }
        }
    }

    /**
     * Indicates if this ParcelFileDescriptor can communicate and detect remote
     * errors/crashes.
     *
     * @see #checkError()
     */
    public boolean canDetectErrors() {
        if (mWrapped != null) {
            return mWrapped.canDetectErrors();
        } else {
            return mCommFd != null;
        }
    }

    /**
     * Detect and throw if the other end of a pipe or socket pair encountered an
     * error or crashed. This allows a reader to distinguish between a valid EOF
     * and an error/crash.
     * <p>
     * If this ParcelFileDescriptor is unable to detect remote errors, it will
     * return silently.
     *
     * @throws IOException for normal errors.
     * @throws FileDescriptorDetachedException
     *            if the remote side called {@link #detachFd()}. Once detached, the remote
     *            side is unable to communicate any errors through
     *            {@link #closeWithError(String)}.
     * @see #canDetectErrors()
     */
    public void checkError() throws IOException {
        if (mWrapped != null) {
            mWrapped.checkError();
        } else {
            if (mStatus == null) {
                if (mCommFd == null) {
                    Log.w(TAG, "Peer didn't provide a comm channel; unable to check for errors");
                    return;
                }

                // Try reading status; it might be null if nothing written yet.
                // Either way, we keep comm open to write our status later.
                mStatus = readCommStatus(mCommFd, getOrCreateStatusBuffer());
            }

            if (mStatus == null || mStatus.status == Status.OK) {
                // No status yet, or everything is peachy!
                return;
            } else {
                throw mStatus.asIOException();
            }
        }
    }

    /**
     * An InputStream you can create on a ParcelFileDescriptor, which will
     * take care of calling {@link ParcelFileDescriptor#close
     * ParcelFileDescriptor.close()} for you when the stream is closed.
     */
    public static class AutoCloseInputStream extends FileInputStream {
        private final ParcelFileDescriptor mPfd;

        public AutoCloseInputStream(ParcelFileDescriptor pfd) {
            super(pfd.getFileDescriptor());
            mPfd = pfd;
        }

        @Override
        public void close() throws IOException {
            try {
                mPfd.close();
            } finally {
                super.close();
            }
        }
    }

    /**
     * An OutputStream you can create on a ParcelFileDescriptor, which will
     * take care of calling {@link ParcelFileDescriptor#close
     * ParcelFileDescriptor.close()} for you when the stream is closed.
     */
    public static class AutoCloseOutputStream extends FileOutputStream {
        private final ParcelFileDescriptor mPfd;

        public AutoCloseOutputStream(ParcelFileDescriptor pfd) {
            super(pfd.getFileDescriptor());
            mPfd = pfd;
        }

        @Override
        public void close() throws IOException {
            try {
                mPfd.close();
            } finally {
                super.close();
            }
        }
    }

    @Override
    public String toString() {
        if (mWrapped != null) {
            return mWrapped.toString();
        } else {
            return "{ParcelFileDescriptor: " + mFd + "}";
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (mWrapped != null) {
            releaseResources();
        }
        if (mGuard != null) {
            mGuard.warnIfOpen();
        }
        try {
            if (!mClosed) {
                closeWithStatus(Status.LEAKED, null);
            }
        } finally {
            super.finalize();
        }
    }

    @Override
    public int describeContents() {
        if (mWrapped != null) {
            return mWrapped.describeContents();
        } else {
            return Parcelable.CONTENTS_FILE_DESCRIPTOR;
        }
    }

    /**
     * {@inheritDoc}
     * If {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE} is set in flags,
     * the file descriptor will be closed after a copy is written to the Parcel.
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (mWrapped != null) {
            try {
                mWrapped.writeToParcel(out, flags);
            } finally {
                releaseResources();
            }
        } else {
            out.writeFileDescriptor(mFd);
            if (mCommFd != null) {
                out.writeInt(1);
                out.writeFileDescriptor(mCommFd);
            } else {
                out.writeInt(0);
            }
            if ((flags & PARCELABLE_WRITE_RETURN_VALUE) != 0 && !mClosed) {
                // Not a real close, so emit no status
                closeWithStatus(Status.SILENCE, null);
            }
        }
    }

    public static final Parcelable.Creator<ParcelFileDescriptor> CREATOR
            = new Parcelable.Creator<ParcelFileDescriptor>() {
        @Override
        public ParcelFileDescriptor createFromParcel(Parcel in) {
            final FileDescriptor fd = in.readRawFileDescriptor();
            FileDescriptor commChannel = null;
            if (in.readInt() != 0) {
                commChannel = in.readRawFileDescriptor();
            }
            return new ParcelFileDescriptor(fd, commChannel);
        }

        @Override
        public ParcelFileDescriptor[] newArray(int size) {
            return new ParcelFileDescriptor[size];
        }
    };

    /**
     * Callback indicating that a ParcelFileDescriptor has been closed.
     */
    public interface OnCloseListener {
        /**
         * Event indicating the ParcelFileDescriptor to which this listener was
         * attached has been closed.
         *
         * @param e error state, or {@code null} if closed cleanly.
         *        If the close event was the result of
         *        {@link ParcelFileDescriptor#detachFd()}, this will be a
         *        {@link FileDescriptorDetachedException}. After detach the
         *        remote side may continue reading/writing to the underlying
         *        {@link FileDescriptor}, but they can no longer deliver
         *        reliable close/error events.
         */
        public void onClose(IOException e);
    }

    /**
     * Exception that indicates that the file descriptor was detached.
     */
    public static class FileDescriptorDetachedException extends IOException {

        private static final long serialVersionUID = 0xDe7ac4edFdL;

        public FileDescriptorDetachedException() {
            super("Remote side is detached");
        }
    }

    /**
     * Internal class representing a remote status read by
     * {@link ParcelFileDescriptor#readCommStatus(FileDescriptor, byte[])}.
     */
    private static class Status {
        /** Special value indicating remote side died. */
        public static final int DEAD = -2;
        /** Special value indicating no status should be written. */
        public static final int SILENCE = -1;

        /** Remote reported that everything went better than expected. */
        public static final int OK = 0;
        /** Remote reported error; length and message follow. */
        public static final int ERROR = 1;
        /** Remote reported {@link #detachFd()} and went rogue. */
        public static final int DETACHED = 2;
        /** Remote reported their object was finalized. */
        public static final int LEAKED = 3;

        public final int status;
        public final String msg;

        public Status(int status) {
            this(status, null);
        }

        public Status(int status, String msg) {
            this.status = status;
            this.msg = msg;
        }

        public IOException asIOException() {
            switch (status) {
                case DEAD:
                    return new IOException("Remote side is dead");
                case OK:
                    return null;
                case ERROR:
                    return new IOException("Remote error: " + msg);
                case DETACHED:
                    return new FileDescriptorDetachedException();
                case LEAKED:
                    return new IOException("Remote side was leaked");
                default:
                    return new IOException("Unknown status: " + status);
            }
        }
    }

    /**
     * Bridge to watch for remote status, and deliver to listener. Currently
     * requires that communication socket is <em>blocking</em>.
     */
    private static final class ListenerBridge extends Thread {
        // TODO: switch to using Looper to avoid burning a thread

        private FileDescriptor mCommFd;
        private final Handler mHandler;

        public ListenerBridge(FileDescriptor comm, Looper looper, final OnCloseListener listener) {
            mCommFd = comm;
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    final Status s = (Status) msg.obj;
                    listener.onClose(s != null ? s.asIOException() : null);
                }
            };
        }

        @Override
        public void run() {
            try {
                final byte[] buf = new byte[MAX_STATUS];
                final Status status = readCommStatus(mCommFd, buf);
                mHandler.obtainMessage(0, status).sendToTarget();
            } finally {
                IoUtils.closeQuietly(mCommFd);
                mCommFd = null;
            }
        }
    }
}
