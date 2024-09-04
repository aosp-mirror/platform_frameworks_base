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

package android.content.res;

import static android.system.OsConstants.S_ISFIFO;
import static android.system.OsConstants.S_ISSOCK;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodReplace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * File descriptor of an entry in the AssetManager.  This provides your own
 * opened FileDescriptor that can be used to read the data, as well as the
 * offset and length of that entry's data in the file.
 */
@RavenwoodKeepWholeClass
public class AssetFileDescriptor implements Parcelable, Closeable {
    /**
     * Length used with {@link #AssetFileDescriptor(ParcelFileDescriptor, long, long)}
     * and {@link #getDeclaredLength} when a length has not been declared.  This means
     * the data extends to the end of the file.
     */
    public static final long UNKNOWN_LENGTH = -1;

    @UnsupportedAppUsage
    private final ParcelFileDescriptor mFd;
    @UnsupportedAppUsage
    private final long mStartOffset;
    @UnsupportedAppUsage
    private final long mLength;
    private final Bundle mExtras;

    /**
     * Create a new AssetFileDescriptor from the given values.
     *
     * @param fd The underlying file descriptor.
     * @param startOffset The location within the file that the asset starts.
     *            This must be 0 if length is UNKNOWN_LENGTH.
     * @param length The number of bytes of the asset, or
     *            {@link #UNKNOWN_LENGTH} if it extends to the end of the file.
     */
    public AssetFileDescriptor(ParcelFileDescriptor fd, long startOffset,
            long length) {
        this(fd, startOffset, length, null);
    }

    /**
     * Create a new AssetFileDescriptor from the given values.
     *
     * @param fd The underlying file descriptor.
     * @param startOffset The location within the file that the asset starts.
     *            This must be 0 if length is UNKNOWN_LENGTH.
     * @param length The number of bytes of the asset, or
     *            {@link #UNKNOWN_LENGTH} if it extends to the end of the file.
     * @param extras additional details that can be used to interpret the
     *            underlying file descriptor. May be null.
     */
    public AssetFileDescriptor(ParcelFileDescriptor fd, long startOffset,
            long length, Bundle extras) {
        if (fd == null) {
            throw new IllegalArgumentException("fd must not be null");
        }
        if (length < 0 && startOffset != 0) {
            throw new IllegalArgumentException(
                    "startOffset must be 0 when using UNKNOWN_LENGTH");
        }
        mFd = fd;
        mStartOffset = startOffset;
        mLength = length;
        mExtras = extras;
    }

    /**
     * The AssetFileDescriptor contains its own ParcelFileDescriptor, which
     * in addition to the normal FileDescriptor object also allows you to close
     * the descriptor when you are done with it.
     */
    public ParcelFileDescriptor getParcelFileDescriptor() {
        return mFd;
    }

    /**
     * Returns the FileDescriptor that can be used to read the data in the
     * file.
     */
    public FileDescriptor getFileDescriptor() {
        return mFd.getFileDescriptor();
    }

    /**
     * Returns the byte offset where this asset entry's data starts.
     */
    public long getStartOffset() {
        return mStartOffset;
    }

    /**
     * Returns any additional details that can be used to interpret the
     * underlying file descriptor. May be null.
     */
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Returns the total number of bytes of this asset entry's data.  May be
     * {@link #UNKNOWN_LENGTH} if the asset extends to the end of the file.
     * If the AssetFileDescriptor was constructed with {@link #UNKNOWN_LENGTH},
     * this will use {@link ParcelFileDescriptor#getStatSize()
     * ParcelFileDescriptor.getStatSize()} to find the total size of the file,
     * returning that number if found or {@link #UNKNOWN_LENGTH} if it could
     * not be determined.
     *
     * @see #getDeclaredLength()
     */
    public long getLength() {
        if (mLength >= 0) {
            return mLength;
        }
        long len = mFd.getStatSize();
        return len >= 0 ? len : UNKNOWN_LENGTH;
    }

    /**
     * Return the actual number of bytes that were declared when the
     * AssetFileDescriptor was constructed.  Will be
     * {@link #UNKNOWN_LENGTH} if the length was not declared, meaning data
     * should be read to the end of the file.
     *
     * @see #getDeclaredLength()
     */
    public long getDeclaredLength() {
        return mLength;
    }

    /**
     * Convenience for calling <code>getParcelFileDescriptor().close()</code>.
     */
    @Override
    public void close() throws IOException {
        mFd.close();
    }

    /**
     * Create and return a new auto-close input stream for this asset.  This
     * will either return a full asset {@link AutoCloseInputStream}, or
     * an underlying {@link ParcelFileDescriptor.AutoCloseInputStream
     * ParcelFileDescriptor.AutoCloseInputStream} depending on whether the
     * the object represents a complete file or sub-section of a file.  You
     * should only call this once for a particular asset.
     */
    public FileInputStream createInputStream() throws IOException {
        if (mLength < 0) {
            return new ParcelFileDescriptor.AutoCloseInputStream(mFd);
        }
        return new AutoCloseInputStream(this);
    }

    /**
     * Create and return a new auto-close output stream for this asset.  This
     * will either return a full asset {@link AutoCloseOutputStream}, or
     * an underlying {@link ParcelFileDescriptor.AutoCloseOutputStream
     * ParcelFileDescriptor.AutoCloseOutputStream} depending on whether the
     * the object represents a complete file or sub-section of a file.  You
     * should only call this once for a particular asset.
     */
    public FileOutputStream createOutputStream() throws IOException {
        if (mLength < 0) {
            return new ParcelFileDescriptor.AutoCloseOutputStream(mFd);
        }
        return new AutoCloseOutputStream(this);
    }

    @Override
    public String toString() {
        return "{AssetFileDescriptor: " + mFd
                + " start=" + mStartOffset + " len=" + mLength + "}";
    }

    /**
     * An InputStream you can create on a ParcelFileDescriptor, which will
     * take care of calling {@link ParcelFileDescriptor#close
     * ParcelFileDescriptor.close()} for you when the stream is closed.
     * It has a ParcelFileDescriptor.AutoCloseInputStream member to make delegate calls
     * and during definition it will create seekable or non seekable child object
     * AssetFileDescriptor.AutoCloseInputStream depends on the type of file descriptor
     * to provide different solution.
     */
    public static class AutoCloseInputStream
            extends ParcelFileDescriptor.AutoCloseInputStream {
        private ParcelFileDescriptor.AutoCloseInputStream mDelegateInputStream;

        public AutoCloseInputStream(AssetFileDescriptor fd) throws IOException {
            super(fd.getParcelFileDescriptor());
            StructStat ss;
            try {
                ss = Os.fstat(fd.getParcelFileDescriptor().getFileDescriptor());
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
            if (S_ISSOCK(ss.st_mode) || S_ISFIFO(ss.st_mode)) {
                mDelegateInputStream = new NonSeekableAutoCloseInputStream(fd);
            } else {
                mDelegateInputStream = new SeekableAutoCloseInputStream(fd);
            }
        }

        @Override
        public int available() throws IOException {
            return mDelegateInputStream.available();
        }

        @Override
        public int read() throws IOException {
            return mDelegateInputStream.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            return mDelegateInputStream.read(buffer, offset, count);
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            return mDelegateInputStream.read(buffer);
        }

        @Override
        public long skip(long count) throws IOException {
            return mDelegateInputStream.skip(count);
        }

        @Override
        public void mark(int readlimit) {
            mDelegateInputStream.mark(readlimit);
        }

        @Override
        public boolean markSupported() {
            return mDelegateInputStream.markSupported();
        }

        @Override
        public synchronized void reset() throws IOException {
            mDelegateInputStream.reset();
        }

        @Override
        public FileChannel getChannel() {
            return mDelegateInputStream.getChannel();
        }
        @Override
        public void close() throws IOException {
            // Make the mDelegateInputStream own file descriptor and super.close()
            // is not needed here to avoid double close the file descriptor.
            mDelegateInputStream.close();
        }
    }

    /**
     * An InputStream you can create on a non seekable file descriptor,
     * like PIPE, SOCKET and FIFO, which will take care of calling
     * {@link ParcelFileDescriptor#close ParcelFileDescriptor.close()}
     * for you when the stream is closed.
     */
    private static class NonSeekableAutoCloseInputStream
            extends ParcelFileDescriptor.AutoCloseInputStream {
        private long mRemaining;

        NonSeekableAutoCloseInputStream(AssetFileDescriptor fd) throws IOException {
            super(fd.getParcelFileDescriptor());
            skipRaw(fd.getStartOffset());
            mRemaining = (int) fd.getLength();
        }

        @RavenwoodReplace
        private long skipRaw(long count) throws IOException {
            return super.skip(count);
        }

        private long skipRaw$ravenwood(long count) throws IOException {
            // OpenJDK doesn't allow skip on pipes, so just use read.
            final byte[] buf = new byte[(int) Math.min(1024, count)];
            long totalRead = 0;
            while (totalRead < count) {
                final int toRead = (int) Math.min(count - totalRead, buf.length);
                final int read = super.read(buf, 0, toRead);
                if (read == -1) {
                    break;
                }
                totalRead += read;
            }
            return totalRead;
        }

        @Override
        public int available() throws IOException {
            return mRemaining >= 0
                    ? (mRemaining < 0x7fffffff ? (int) mRemaining : 0x7fffffff)
                    : super.available();
        }

        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int result = read(buffer, 0, 1);
            return result == -1 ? -1 : buffer[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            if (mRemaining >= 0) {
                if (mRemaining == 0) return -1;
                if (count > mRemaining) count = (int) mRemaining;
                int res = super.read(buffer, offset, count);
                if (res >= 0) mRemaining -= res;
                return res;
            }

            return super.read(buffer, offset, count);
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            return read(buffer, 0, buffer.length);
        }

        @Override
        public long skip(long count) throws IOException {
            if (mRemaining >= 0) {
                if (mRemaining == 0) return -1;
                if (count > mRemaining) count = mRemaining;
                long res = skipRaw(count);
                if (res >= 0) mRemaining -= res;
                return res;
            }

            return skipRaw(count);
        }

        @Override
        public void mark(int readlimit) {
            if (mRemaining >= 0) {
                // Not supported.
                return;
            }
            super.mark(readlimit);
        }

        @Override
        public boolean markSupported() {
            if (mRemaining >= 0) {
                return false;
            }
            return super.markSupported();
        }

        @Override
        public synchronized void reset() throws IOException {
            if (mRemaining >= 0) {
                // Not supported.
                return;
            }
            super.reset();
        }
    }

    /**
     * An InputStream you can create on a seekable file descriptor, which means
     * you can use pread to read from a specific offset, this will take care of
     * calling {@link ParcelFileDescriptor#close ParcelFileDescriptor.close()}
     * for you when the stream is closed.
     */
    private static class SeekableAutoCloseInputStream
            extends ParcelFileDescriptor.AutoCloseInputStream {
        /** Size of current file. */
        private long mTotalSize;
        /** The absolute position of current file start point. */
        private final long mFileOffset;
        /** The relative position where input stream is against mFileOffset. */
        private long mOffset;
        private OffsetCorrectFileChannel mOffsetCorrectFileChannel;

        SeekableAutoCloseInputStream(AssetFileDescriptor fd) throws IOException {
            super(fd.getParcelFileDescriptor());
            mTotalSize = fd.getLength();
            mFileOffset = fd.getStartOffset();
        }

        @Override
        public int available() throws IOException {
            long available = mTotalSize - mOffset;
            return available >= 0
                    ? (available < 0x7fffffff ? (int) available : 0x7fffffff)
                    : 0;
        }

        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int result = read(buffer, 0, 1);
            return result == -1 ? -1 : buffer[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            int available = available();
            if (available <= 0) {
                return -1;
            }
            if (count == 0) {
                // Java's InputStream explicitly specifies that this returns zero.
                return 0;
            }

            if (count > available) count = available;
            try {
                int res = Os.pread(getFD(), buffer, offset, count, mFileOffset + mOffset);
                // pread returns 0 at end of file, while java's InputStream interface requires -1
                if (res == 0) res = -1;
                if (res > 0) {
                    mOffset += res;
                    updateChannelPosition(mOffset + mFileOffset);
                }
                return res;
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            return read(buffer, 0, buffer.length);
        }

        @Override
        public long skip(long count) throws IOException {
            int available = available();
            if (available <= 0) {
                return -1;
            }

            if (count > available) count = available;
            mOffset += count;
            updateChannelPosition(mOffset + mFileOffset);
            return count;
        }

        @Override
        public void mark(int readlimit) {
            // Not supported.
            return;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void reset() throws IOException {
            // Not supported.
            return;
        }

        @Override
        public FileChannel getChannel() {
            if (mOffsetCorrectFileChannel == null) {
                mOffsetCorrectFileChannel = new OffsetCorrectFileChannel(super.getChannel());
            }
            try {
                updateChannelPosition(mOffset + mFileOffset);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return mOffsetCorrectFileChannel;
        }

        /**
         * Update the position of mOffsetCorrectFileChannel only after it is constructed.
         *
         * @param newPosition The absolute position mOffsetCorrectFileChannel needs to be moved to.
         */
        private void updateChannelPosition(long newPosition) throws IOException {
            if (mOffsetCorrectFileChannel != null) {
                mOffsetCorrectFileChannel.position(newPosition);
            }
        }

        /**
         * A FileChannel wrapper that will update mOffset of the AutoCloseInputStream
         * to correct position when using FileChannel to read. All occurrence of position
         * should be using absolute solution and each override method just do Delegation
         * besides additional check. All methods related to write mode have been disabled
         * and will throw UnsupportedOperationException with customized message.
         */
        private class OffsetCorrectFileChannel extends FileChannel {
            private final FileChannel mDelegate;
            private static final String METHOD_NOT_SUPPORTED_MESSAGE =
                    "This Method is not supported in AutoCloseInputStream FileChannel.";

            OffsetCorrectFileChannel(FileChannel fc) {
                mDelegate = fc;
            }

            @Override
            public int read(ByteBuffer dst) throws IOException {
                if (available() <= 0) return -1;
                int bytesRead = mDelegate.read(dst);
                if (bytesRead != -1) mOffset += bytesRead;
                return bytesRead;
            }

            @Override
            public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
                if (available() <= 0) return -1;
                if (mOffset + length > mTotalSize) {
                    length = (int) (mTotalSize - mOffset);
                }
                long bytesRead = mDelegate.read(dsts, offset, length);
                if (bytesRead != -1) mOffset += bytesRead;
                return bytesRead;
            }

            @Override
            /**The only read method that does not move channel position*/
            public int read(ByteBuffer dst, long position) throws IOException {
                if (position - mFileOffset > mTotalSize) return -1;
                return mDelegate.read(dst, position);
            }

            @Override
            public long position() throws IOException {
                return mDelegate.position();
            }

            @Override
            public FileChannel position(long newPosition) throws IOException {
                mOffset = newPosition - mFileOffset;
                return mDelegate.position(newPosition);
            }

            @Override
            public long size() throws IOException {
                return mTotalSize;
            }

            @Override
            public long transferTo(long position, long count, WritableByteChannel target)
                    throws IOException {
                if (position - mFileOffset > mTotalSize) {
                    return 0;
                }
                if (position - mFileOffset + count > mTotalSize) {
                    count = mTotalSize - (position - mFileOffset);
                }
                return mDelegate.transferTo(position, count, target);
            }

            @Override
            public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
                if (position - mFileOffset > mTotalSize) {
                    throw new IOException(
                            "Cannot map to buffer because position exceed current file size.");
                }
                if (position - mFileOffset + size > mTotalSize) {
                    size = mTotalSize - (position - mFileOffset);
                }
                return mDelegate.map(mode, position, size);
            }

            @Override
            protected void implCloseChannel() throws IOException {
                mDelegate.close();
            }

            @Override
            public int write(ByteBuffer src) throws IOException {
                throw new UnsupportedOperationException(METHOD_NOT_SUPPORTED_MESSAGE);
            }

            @Override
            public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
                throw new UnsupportedOperationException(METHOD_NOT_SUPPORTED_MESSAGE);
            }

            @Override
            public int write(ByteBuffer src, long position) throws IOException {
                throw new UnsupportedOperationException(METHOD_NOT_SUPPORTED_MESSAGE);
            }

            @Override
            public long transferFrom(ReadableByteChannel src, long position, long count)
                    throws IOException {
                throw new UnsupportedOperationException(METHOD_NOT_SUPPORTED_MESSAGE);
            }

            @Override
            public FileChannel truncate(long size) throws IOException {
                throw new UnsupportedOperationException(METHOD_NOT_SUPPORTED_MESSAGE);
            }

            @Override
            public void force(boolean metaData) throws IOException {
                throw new UnsupportedOperationException(METHOD_NOT_SUPPORTED_MESSAGE);
            }

            @Override
            public FileLock lock(long position, long size, boolean shared) throws IOException {
                throw new UnsupportedOperationException(METHOD_NOT_SUPPORTED_MESSAGE);
            }

            @Override
            public FileLock tryLock(long position, long size, boolean shared) throws IOException {
                throw new UnsupportedOperationException(METHOD_NOT_SUPPORTED_MESSAGE);
            }
        }
    }

    /**
     * An OutputStream you can create on a ParcelFileDescriptor, which will
     * take care of calling {@link ParcelFileDescriptor#close
     * ParcelFileDescriptor.close()} for you when the stream is closed.
     */
    public static class AutoCloseOutputStream
            extends ParcelFileDescriptor.AutoCloseOutputStream {
        private long mRemaining;

        public AutoCloseOutputStream(AssetFileDescriptor fd) throws IOException {
            super(fd.getParcelFileDescriptor());
            if (fd.getParcelFileDescriptor().seekTo(fd.getStartOffset()) < 0) {
                throw new IOException("Unable to seek");
            }
            mRemaining = (int) fd.getLength();
        }

        @Override
        public void write(byte[] buffer, int offset, int count) throws IOException {
            if (mRemaining >= 0) {
                if (mRemaining == 0) return;
                if (count > mRemaining) count = (int) mRemaining;
                super.write(buffer, offset, count);
                mRemaining -= count;
                return;
            }

            super.write(buffer, offset, count);
        }

        @Override
        public void write(byte[] buffer) throws IOException {
            if (mRemaining >= 0) {
                if (mRemaining == 0) return;
                int count = buffer.length;
                if (count > mRemaining) count = (int) mRemaining;
                super.write(buffer);
                mRemaining -= count;
                return;
            }

            super.write(buffer);
        }

        @Override
        public void write(int oneByte) throws IOException {
            if (mRemaining >= 0) {
                if (mRemaining == 0) return;
                super.write(oneByte);
                mRemaining--;
                return;
            }

            super.write(oneByte);
        }
    }

    /* Parcelable interface */
    @Override
    public int describeContents() {
        return mFd.describeContents();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mFd.writeToParcel(out, flags);
        out.writeLong(mStartOffset);
        out.writeLong(mLength);
        if (mExtras != null) {
            out.writeInt(1);
            out.writeBundle(mExtras);
        } else {
            out.writeInt(0);
        }
    }

    AssetFileDescriptor(Parcel src) {
        mFd = ParcelFileDescriptor.CREATOR.createFromParcel(src);
        mStartOffset = src.readLong();
        mLength = src.readLong();
        if (src.readInt() != 0) {
            mExtras = src.readBundle();
        } else {
            mExtras = null;
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AssetFileDescriptor> CREATOR
            = new Parcelable.Creator<AssetFileDescriptor>() {
        public AssetFileDescriptor createFromParcel(Parcel in) {
            return new AssetFileDescriptor(in);
        }
        public AssetFileDescriptor[] newArray(int size) {
            return new AssetFileDescriptor[size];
        }
    };

}
