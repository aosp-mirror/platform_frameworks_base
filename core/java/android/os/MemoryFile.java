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

import android.compat.annotation.UnsupportedAppUsage;
import android.system.ErrnoException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;


/**
 * MemoryFile is a wrapper for {@link SharedMemory} which can optionally be set to purgeable.
 *
 * Applications should generally prefer to use {@link SharedMemory} which offers more flexible
 * access & control over the shared memory region than MemoryFile does.
 *
 * Purgeable files may have their contents reclaimed by the kernel
 * in low memory conditions (only if allowPurging is set to true).
 * After a file is purged, attempts to read or write the file will
 * cause an IOException to be thrown.
 */
public class MemoryFile {
    private static String TAG = "MemoryFile";

    // Returns 'true' if purged, 'false' otherwise
    @UnsupportedAppUsage
    private static native boolean native_pin(FileDescriptor fd, boolean pin) throws IOException;
    @UnsupportedAppUsage
    private static native int native_get_size(FileDescriptor fd) throws IOException;

    private SharedMemory mSharedMemory;
    private ByteBuffer mMapping;
    private boolean mAllowPurging = false;  // true if our ashmem region is unpinned

    /**
     * Allocates a new ashmem region. The region is initially not purgable.
     *
     * @param name optional name for the file (can be null).
     * @param length of the memory file in bytes, must be positive.
     * @throws IOException if the memory file could not be created.
     */
    public MemoryFile(String name, int length) throws IOException {
        try {
            mSharedMemory = SharedMemory.create(name, length);
            mMapping = mSharedMemory.mapReadWrite();
        } catch (ErrnoException ex) {
            ex.rethrowAsIOException();
        }
    }

    /**
     * Closes the memory file. If there are no other open references to the memory
     * file, it will be deleted.
     */
    public void close() {
        deactivate();
        mSharedMemory.close();
    }

    /**
     * Unmaps the memory file from the process's memory space, but does not close it.
     * After this method has been called, read and write operations through this object
     * will fail, but {@link #getFileDescriptor()} will still return a valid file descriptor.
     *
     * @hide
     */
    @UnsupportedAppUsage
    void deactivate() {
        if (mMapping != null) {
            SharedMemory.unmap(mMapping);
            mMapping = null;
        }
    }

    private void checkActive() throws IOException {
        if (mMapping == null) {
            throw new IOException("MemoryFile has been deactivated");
        }
    }

    private void beginAccess() throws IOException {
        checkActive();
        if (mAllowPurging) {
            if (native_pin(mSharedMemory.getFileDescriptor(), true)) {
                throw new IOException("MemoryFile has been purged");
            }
        }
    }

    private void endAccess() throws IOException {
        if (mAllowPurging) {
            native_pin(mSharedMemory.getFileDescriptor(), false);
        }
    }

    /**
     * Returns the length of the memory file.
     *
     * @return file length.
     */
    public int length() {
        return mSharedMemory.getSize();
    }

    /**
     * Is memory file purging enabled?
     *
     * @return true if the file may be purged.
     *
     * @deprecated Purgable is considered generally fragile and hard to use safely. Applications
     * are recommend to instead use {@link android.content.ComponentCallbacks2#onTrimMemory(int)}
     * to react to memory events and release shared memory regions as appropriate.
     */
    @Deprecated
    public boolean isPurgingAllowed() {
        return mAllowPurging;
    }

    /**
     * Enables or disables purging of the memory file.
     *
     * @param allowPurging true if the operating system can purge the contents
     * of the file in low memory situations
     * @return previous value of allowPurging
     *
     * @deprecated Purgable is considered generally fragile and hard to use safely. Applications
     * are recommend to instead use {@link android.content.ComponentCallbacks2#onTrimMemory(int)}
     * to react to memory events and release shared memory regions as appropriate.
     */
    @Deprecated
    synchronized public boolean allowPurging(boolean allowPurging) throws IOException {
        boolean oldValue = mAllowPurging;
        if (oldValue != allowPurging) {
            native_pin(mSharedMemory.getFileDescriptor(), !allowPurging);
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
        beginAccess();
        try {
            mMapping.position(srcOffset);
            mMapping.get(buffer, destOffset, count);
        } finally {
            endAccess();
        }
        return count;
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
        beginAccess();
        try {
            mMapping.position(destOffset);
            mMapping.put(buffer, srcOffset, count);
        } finally {
            endAccess();
        }
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
    @UnsupportedAppUsage
    public FileDescriptor getFileDescriptor() throws IOException {
        return mSharedMemory.getFileDescriptor();
    }

    /**
     * Returns the size of the memory file that the file descriptor refers to,
     * or -1 if the file descriptor does not refer to a memory file.
     *
     * @throws IOException If <code>fd</code> is not a valid file descriptor.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static int getSize(FileDescriptor fd) throws IOException {
        return native_get_size(fd);
    }

    private class MemoryInputStream extends InputStream {

        private int mMark = 0;
        private int mOffset = 0;
        private byte[] mSingleByte;

        @Override
        public int available() throws IOException {
            if (mOffset >= mSharedMemory.getSize()) {
                return 0;
            }
            return mSharedMemory.getSize() - mOffset;
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
            if (mOffset + n > mSharedMemory.getSize()) {
                n = mSharedMemory.getSize() - mOffset;
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
