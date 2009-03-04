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
 
    // returns fd
    private native int native_open(String name, int length);
    // returns memory address for ashmem region
    private native int native_mmap(int fd, int length);
    private native void native_close(int fd);
    private native int native_read(int fd, int address, byte[] buffer, 
            int srcOffset, int destOffset, int count, boolean isUnpinned);
    private native void native_write(int fd, int address, byte[] buffer, 
            int srcOffset, int destOffset, int count, boolean isUnpinned);
    private native void native_pin(int fd, boolean pin);

    private int mFD;        // ashmem file descriptor
    private int mAddress;   // address of ashmem memory
    private int mLength;    // total length of our ashmem region
    private boolean mAllowPurging = false;  // true if our ashmem region is unpinned

    /**
     * MemoryFile constructor.
     *
     * @param name optional name for the file (can be null).
     * @param length of the memory file in bytes.
     */
    public MemoryFile(String name, int length) {
        mLength = length;
        mFD = native_open(name, length);
        mAddress = native_mmap(mFD, length);
    }

    /**
     * Closes and releases all resources for the memory file.
     */
    public void close() {
        if (mFD > 0) {
            native_close(mFD);
            mFD = 0;
        }
    }

    @Override
    protected void finalize() {
        if (mFD > 0) {
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
     */
    public int readBytes(byte[] buffer, int srcOffset, int destOffset, int count) 
            throws IOException {
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
     */
    public void writeBytes(byte[] buffer, int srcOffset, int destOffset, int count)
            throws IOException {
        if (srcOffset < 0 || srcOffset > buffer.length || count < 0
                || count > buffer.length - srcOffset
                || destOffset < 0 || destOffset > mLength
                || count > mLength - destOffset) {
            throw new IndexOutOfBoundsException();
        }
        native_write(mFD, mAddress, buffer, srcOffset, destOffset, count, mAllowPurging);
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
                throw new IOException("read() failed");
            }
            return mSingleByte[0];
        }

        @Override
        public int read(byte buffer[], int offset, int count) throws IOException {
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
