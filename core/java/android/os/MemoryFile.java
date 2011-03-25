/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


/**
 * MemoryFile is a wrapper for the Linux ashmem driver.
 * MemoryFiles are backed by shared memory, which can be optionally
 * set to be purgeable.
 * Purgeable files may have their contents reclaimed by the kernel
 * in low memory conditions (only if allowPurging is set to true).
 * After a file is purged, attempts to read or write the file will
 * cause an IOException to be thrown.
 */
public class MemoryFile
{
    private static String TAG = "MemoryFile";

    // mmap(2) protection flags from <sys/mman.h>
    private static final int PROT_READ = 0x1;
    private static final int PROT_WRITE = 0x2;

    private static native FileDescriptor native_open(String name, int length) throws IOException;
    // returns memory address for ashmem region
    private static native int native_mmap(FileDescriptor fd, int length, int mode)
            throws IOException;
    private static native void native_munmap(int addr, int length) throws IOException;
    private static native void native_close(FileDescriptor fd);
    private static native int native_read(FileDescriptor fd, int address, byte[] buffer,
            int srcOffset, int destOffset, int count, boolean isUnpinned) throws IOException;
    private static native void native_write(FileDescriptor fd, int address, byte[] buffer,
            int srcOffset, int destOffset, int count, boolean isUnpinned) throws IOException;
    private static native void native_pin(FileDescriptor fd, boolean pin) throws IOException;
    private static native int native_get_size(FileDescriptor fd) throws IOException;

    private FileDescriptor mFD;        // ashmem file descriptor
    private int mAddress;   // address of ashmem memory
    private int mLength;    // total length of our ashmem region
    private boolean mAllowPurging = false;  // true if our ashmem region is unpinned

    /**
     * Allocates a new ashmem region. The region is initially not purgable.
     *
     * @param name optional name for the file (can be null).
     * @param length of the memory file in bytes.
     * @throws IOException if the memory file could not be created.
     */
    public MemoryFile(String name, int length) throws IOException {
        mLength = length;
        mFD = native_open(name, length);
        if (length > 0) {
            mAddress = native_mmap(mFD, length, PROT_READ | PROT_WRITE);
        } else {
            mAddress = 0;
        }
    }

    /**
     * Closes the memory file. If there are no other open references to the memory
     * file, it will be deleted.
     */
    public void close() {
        deactivate();
        if (!isClosed()) {
            native_close(mFD);
        }
    }

    /**
     * Unmaps the memory file from the process's memory space, but does not close it.
     * After this method has been called, read and write operations through this object
     * will fail, but {@link #getFileDescriptor()} will still return a valid file descriptor.
     *
     * @hide
     */
    void deactivate() {
        if (!isDeactivated()) {
            try {
                native_munmap(mAddress, mLength);
                mAddress = 0;
            } catch (IOException ex) {
                Log.e(TAG, ex.toString());
            }
        }
    }

    /**
     * Checks whether the memory file has been deactivated.
     */
    private boolean isDeactivated() {
        return mAddress == 0;
    }

    /**
     * Checks whether the memory file has been closed.
     */
    private boolean isClosed() {
        return !mFD.valid();
    }

    @Override
    protected void finalize() {
        if (!isClosed()) {
            Log.e(TAG, "MemoryFile.finalize() called while ashmem still open");
            close();
        }
    }

    /**
     * Returns the length of the memory file.
     *
     * @return file length.
     */
    public int length() {
        return mLength;
    }

    /**
     * Is memory file purging enabled?
     *
     * @return true if the file may be purged.
     */
    public boolean isPurgingAllowed() {
        return mAllowPurging;
    }

    /**
     * Enables or disables purging of the memory file.
     *
     * @param allowPurging true if the operating system can purge the contents
     * of the file in low memory situations
     * @return previous value of allowPurging
     */
    synchronized public boolean allowPurging(boolean allowPurging) throws IOException {
        boolean oldValue = mAllowPurging;
        if (oldValue != allowPurging) {
            native_pin(mFD, !allowPurging);
            mAllowPurging = allowPurging;
        }
        return oldValue;
    }

    /**
     * Creates a new InputStream for reading from the memory file.
     *
     @return InputStream
     */
    public InputStream getInputStream() {
        return new MemoryInputStream();
    }

    /**
     * Creates a new OutputStream for writing to the memory file.
     *
     @return OutputStream
     */
     public OutputStream getOutputStream() {
        return new MemoryOutputStream();
    }

    /**
     * Reads bytes from the memory file.
     * Will throw an IOException if the file has been purged.
     *
     * @param buffer byte array to read bytes into.
     * @param srcOffset offset into the memory file to read from.
     * @param destOffset offset into the byte array buffer to read into.
     * @param count number of bytes to read.
     * @return number of bytes read.
     * @throws IOException if the memory file has been purged or deactivated.
     */
    public int readBytes(byte[] buffer, int srcOffset, int destOffset, int count)
            throws IOException {
        if (isDeactivated()) {
            throw new IOException("Can't read from deactivated memory file.");
        }
        if (destOffset < 0 || destOffset > buffer.length || count < 0
                || count > buffer.length - destOffset
                || srcOffset < 0 || srcOffset > mLength
                || count > mLength - srcOffset) {
            throw new IndexOutOfBoundsException();
        }
        return native_read(mFD, mAddress, buffer, srcOffset, destOffset, count, mAllowPurging);
    }

    /**
     * Write bytes to the memory file.
     * Will throw an IOException if the file has been purged.
     *
     * @param buffer byte array to write bytes from.
     * @param srcOffset offset into the byte array buffer to write from.
     * @param destOffset offset  into the memory file to write to.
     * @param count number of bytes to write.
     * @throws IOException if the memory file has been purged or deactivated.
     */
    public void writeBytes(byte[] buffer, int srcOffset, int destOffset, int count)
            throws IOException {
        if (isDeactivated()) {
            throw new IOException("Can't write to deactivated memory file.");
        }
        if (srcOffset < 0 || srcOffset > buffer.length || count < 0
                || count > buffer.length - srcOffset
                || destOffset < 0 || destOffset > mLength
                || count > mLength - destOffset) {
            throw new IndexOutOfBoundsException();
        }
        native_write(mFD, mAddress, buffer, srcOffset, destOffset, count, mAllowPurging);
    }

    /**
     * Gets a FileDescriptor for the memory file.
     *
     * The returned file descriptor is not duplicated.
     *
     * @throws IOException If the memory file has been closed.
     *
     * @hide
     */
    public FileDescriptor getFileDescriptor() throws IOException {
        return mFD;
    }

    /**
     * Returns the size of the memory file that the file descriptor refers to,
     * or -1 if the file descriptor does not refer to a memory file.
     *
     * @throws IOException If <code>fd</code> is not a valid file descriptor.
     *
     * @hide
     */
    public static int getSize(FileDescriptor fd) throws IOException {
        return native_get_size(fd);
    }

    private class MemoryInputStream extends InputStream {

        private int mMark = 0;
        private int mOffset = 0;
        private byte[] mSingleByte;

        @Override
        public int available() throws IOException {
            if (mOffset >= mLength) {
                return 0;
            }
            return mLength - mOffset;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int readlimit) {
            mMark = mOffset;
        }

        @Override
        public void reset() throws IOException {
            mOffset = mMark;
        }

        @Override
        public int read() throws IOException {
            if (mSingleByte == null) {
                mSingleByte = new byte[1];
            }
            int result = read(mSingleByte, 0, 1);
            if (result != 1) {
                return -1;
            }
            return mSingleByte[0];
        }

        @Override
        public int read(byte buffer[], int offset, int count) throws IOException {
            if (offset < 0 || count < 0 || offset + count > buffer.length) {
                // readBytes() also does this check, but we need to do it before
                // changing count.
                throw new IndexOutOfBoundsException();
            }
            count = Math.min(count, available());
            if (count < 1) {
                return -1;
            }
            int result = readBytes(buffer, mOffset, offset, count);
            if (result > 0) {
                mOffset += result;
            }
            return result;
        }

        @Override
        public long skip(long n) throws IOException {
            if (mOffset + n > mLength) {
                n = mLength - mOffset;
            }
            mOffset += n;
            return n;
        }
    }

    private class MemoryOutputStream extends OutputStream {

        private int mOffset = 0;
        private byte[] mSingleByte;

        @Override
        public void write(byte buffer[], int offset, int count) throws IOException {
            writeBytes(buffer, offset, mOffset, count);
            mOffset += count;
        }

        @Override
        public void write(int oneByte) throws IOException {
            if (mSingleByte == null) {
                mSingleByte = new byte[1];
            }
            mSingleByte[0] = (byte)oneByte;
            write(mSingleByte, 0, 1);
        }
    }
}
