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
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * The FileDescriptor returned by {@link Parcel#readFileDescriptor}, allowing
 * you to close it when done with it.
 */
public class ParcelFileDescriptor implements Parcelable {
    private final FileDescriptor mFileDescriptor;
    private boolean mClosed;
    //this field is to create wrapper for ParcelFileDescriptor using another
    //PartialFileDescriptor but avoid invoking close twice
    //consider ParcelFileDescriptor A(fileDescriptor fd),  ParcelFileDescriptor B(A)
    //in this particular case fd.close might be invoked twice.
    private final ParcelFileDescriptor mParcelDescriptor;
    
    /**
     * For use with {@link #open}: if {@link #MODE_CREATE} has been supplied
     * and this file doesn't already exist, then create the file with
     * permissions such that any application can read it.
     */
    public static final int MODE_WORLD_READABLE = 0x00000001;
    
    /**
     * For use with {@link #open}: if {@link #MODE_CREATE} has been supplied
     * and this file doesn't already exist, then create the file with
     * permissions such that any application can write it.
     */
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
     * Create a new ParcelFileDescriptor accessing a given file.
     * 
     * @param file The file to be opened.
     * @param mode The desired access mode, must be one of
     * {@link #MODE_READ_ONLY}, {@link #MODE_WRITE_ONLY}, or
     * {@link #MODE_READ_WRITE}; may also be any combination of
     * {@link #MODE_CREATE}, {@link #MODE_TRUNCATE},
     * {@link #MODE_WORLD_READABLE}, and {@link #MODE_WORLD_WRITEABLE}.
     * 
     * @return Returns a new ParcelFileDescriptor pointing to the given
     * file.
     * 
     * @throws FileNotFoundException Throws FileNotFoundException if the given
     * file does not exist or can not be opened with the requested mode.
     */
    public static ParcelFileDescriptor open(File file, int mode)
            throws FileNotFoundException {
        String path = file.getPath();
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkRead(path);
            if ((mode&MODE_WRITE_ONLY) != 0) {
                security.checkWrite(path);
            }
        }
        
        if ((mode&MODE_READ_WRITE) == 0) {
            throw new IllegalArgumentException(
                    "Must specify MODE_READ_ONLY, MODE_WRITE_ONLY, or MODE_READ_WRITE");
        }
        
        FileDescriptor fd = Parcel.openFileDescriptor(path, mode);
        return fd != null ? new ParcelFileDescriptor(fd) : null;
    }

    /**
     * Create a new ParcelFileDescriptor from the specified Socket.
     *
     * @param socket The Socket whose FileDescriptor is used to create
     *               a new ParcelFileDescriptor.
     *
     * @return A new ParcelFileDescriptor with the FileDescriptor of the
     *         specified Socket.
     */
    public static ParcelFileDescriptor fromSocket(Socket socket) {
        FileDescriptor fd = getFileDescriptorFromSocket(socket);
        return fd != null ? new ParcelFileDescriptor(fd) : null;
    }

    // Extracts the file descriptor from the specified socket and returns it untouched
    private static native FileDescriptor getFileDescriptorFromSocket(Socket socket);

    /**
     * Retrieve the actual FileDescriptor associated with this object.
     * 
     * @return Returns the FileDescriptor associated with this object.
     */
    public FileDescriptor getFileDescriptor() {
        return mFileDescriptor;
    }
    
    /**
     * Return the total size of the file representing this fd, as determined
     * by stat().  Returns -1 if the fd is not a file.
     */
    public native long getStatSize();
    
    /**
     * This is needed for implementing AssetFileDescriptor.AutoCloseOutputStream,
     * and I really don't think we want it to be public.
     * @hide
     */
    public native long seekTo(long pos);
    
    /**
     * Close the ParcelFileDescriptor. This implementation closes the underlying
     * OS resources allocated to represent this stream.
     * 
     * @throws IOException
     *             If an error occurs attempting to close this ParcelFileDescriptor.
     */
    public void close() throws IOException {
        synchronized (this) {
            if (mClosed) return;
            mClosed = true;
        }
        if (mParcelDescriptor != null) {
            // If this is a proxy to another file descriptor, just call through to its
            // close method.
            mParcelDescriptor.close();
        } else {
            Parcel.closeFileDescriptor(mFileDescriptor);
        }
    }
    
    /**
     * An InputStream you can create on a ParcelFileDescriptor, which will
     * take care of calling {@link ParcelFileDescriptor#close
     * ParcelFileDescritor.close()} for you when the stream is closed.
     */
    public static class AutoCloseInputStream extends FileInputStream {
        private final ParcelFileDescriptor mFd;
        
        public AutoCloseInputStream(ParcelFileDescriptor fd) {
            super(fd.getFileDescriptor());
            mFd = fd;
        }

        @Override
        public void close() throws IOException {
            mFd.close();
        }
    }
    
    /**
     * An OutputStream you can create on a ParcelFileDescriptor, which will
     * take care of calling {@link ParcelFileDescriptor#close
     * ParcelFileDescritor.close()} for you when the stream is closed.
     */
    public static class AutoCloseOutputStream extends FileOutputStream {
        private final ParcelFileDescriptor mFd;
        
        public AutoCloseOutputStream(ParcelFileDescriptor fd) {
            super(fd.getFileDescriptor());
            mFd = fd;
        }

        @Override
        public void close() throws IOException {
            mFd.close();
        }
    }
    
    @Override
    public String toString() {
        return "{ParcelFileDescriptor: " + mFileDescriptor + "}";
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            if (!mClosed) {
                close();
            }
        } finally {
            super.finalize();
        }
    }
    
    public ParcelFileDescriptor(ParcelFileDescriptor descriptor) {
        super();
        mParcelDescriptor = descriptor;
        mFileDescriptor = mParcelDescriptor.mFileDescriptor;
    }
    
    /*package */ParcelFileDescriptor(FileDescriptor descriptor) {
        super();
        if (descriptor == null) {
            throw new NullPointerException("descriptor must not be null");
        }
        mFileDescriptor = descriptor;
        mParcelDescriptor = null;
    }
    
    /* Parcelable interface */
    public int describeContents() {
        return Parcelable.CONTENTS_FILE_DESCRIPTOR;
    }

    /**
     * {@inheritDoc}
     * If {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE} is set in flags,
     * the file descriptor will be closed after a copy is written to the Parcel.
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeFileDescriptor(mFileDescriptor);
        if ((flags&PARCELABLE_WRITE_RETURN_VALUE) != 0 && !mClosed) {
            try {
                close();
            } catch (IOException e) {
                // Empty
            }
        }
    }

    public static final Parcelable.Creator<ParcelFileDescriptor> CREATOR
            = new Parcelable.Creator<ParcelFileDescriptor>() {
        public ParcelFileDescriptor createFromParcel(Parcel in) {
            return in.readFileDescriptor();
        }
        public ParcelFileDescriptor[] newArray(int size) {
            return new ParcelFileDescriptor[size];
        }
    };

}
