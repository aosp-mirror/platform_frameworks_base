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

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * File descriptor of an entry in the AssetManager.  This provides your own
 * opened FileDescriptor that can be used to read the data, as well as the
 * offset and length of that entry's data in the file.
 */
public class AssetFileDescriptor implements Parcelable {
    /**
     * Length used with {@link #AssetFileDescriptor(ParcelFileDescriptor, long, long)}
     * and {@link #getDeclaredLength} when a length has not been declared.  This means
     * the data extends to the end of the file.
     */
    public static final long UNKNOWN_LENGTH = -1;
    
    private final ParcelFileDescriptor mFd;
    private final long mStartOffset;
    private final long mLength;
    
    /**
     * Create a new AssetFileDescriptor from the given values.
     * @param fd The underlying file descriptor.
     * @param startOffset The location within the file that the asset starts.
     * This must be 0 if length is UNKNOWN_LENGTH.
     * @param length The number of bytes of the asset, or
     * {@link #UNKNOWN_LENGTH} if it extends to the end of the file.
     */
    public AssetFileDescriptor(ParcelFileDescriptor fd, long startOffset,
            long length) {
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
     * ParcelFileDescritor.close()} for you when the stream is closed.
     */
    public static class AutoCloseInputStream
            extends ParcelFileDescriptor.AutoCloseInputStream {
        private long mRemaining;
        
        public AutoCloseInputStream(AssetFileDescriptor fd) throws IOException {
            super(fd.getParcelFileDescriptor());
            super.skip(fd.getStartOffset());
            mRemaining = (int)fd.getLength();
        }

        @Override
        public int available() throws IOException {
            return mRemaining >= 0
                    ? (mRemaining < 0x7fffffff ? (int)mRemaining : 0x7fffffff)
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
                if (count > mRemaining) count = (int)mRemaining;
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
                long res = super.skip(count);
                if (res >= 0) mRemaining -= res;
                return res;
            }
            
            return super.skip(count);
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
     * An OutputStream you can create on a ParcelFileDescriptor, which will
     * take care of calling {@link ParcelFileDescriptor#close
     * ParcelFileDescritor.close()} for you when the stream is closed.
     */
    public static class AutoCloseOutputStream
            extends ParcelFileDescriptor.AutoCloseOutputStream {
        private long mRemaining;
        
        public AutoCloseOutputStream(AssetFileDescriptor fd) throws IOException {
            super(fd.getParcelFileDescriptor());
            if (fd.getParcelFileDescriptor().seekTo(fd.getStartOffset()) < 0) {
                throw new IOException("Unable to seek");
            }
            mRemaining = (int)fd.getLength();
        }

        @Override
        public void write(byte[] buffer, int offset, int count) throws IOException {
            if (mRemaining >= 0) {
                if (mRemaining == 0) return;
                if (count > mRemaining) count = (int)mRemaining;
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
                if (count > mRemaining) count = (int)mRemaining;
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
    public int describeContents() {
        return mFd.describeContents();
    }

    public void writeToParcel(Parcel out, int flags) {
        mFd.writeToParcel(out, flags);
        out.writeLong(mStartOffset);
        out.writeLong(mLength);
    }

    AssetFileDescriptor(Parcel src) {
        mFd = ParcelFileDescriptor.CREATOR.createFromParcel(src);
        mStartOffset = src.readLong();
        mLength = src.readLong();
    }
    
    public static final Parcelable.Creator<AssetFileDescriptor> CREATOR
            = new Parcelable.Creator<AssetFileDescriptor>() {
        public AssetFileDescriptor createFromParcel(Parcel in) {
            return new AssetFileDescriptor(in);
        }
        public AssetFileDescriptor[] newArray(int size) {
            return new AssetFileDescriptor[size];
        }
    };

}
