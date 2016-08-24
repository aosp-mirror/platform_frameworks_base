/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.util;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;

/**
 * This class is an array of integers that is backed by shared memory.
 * It is useful for efficiently sharing state between processes. The
 * write and read operations are guaranteed to not result in read/
 * write memory tear, i.e. they are atomic. However, multiple read/
 * write operations are <strong>not</strong> synchronized between
 * each other.
 * <p>
 * The data structure is designed to have one owner process that can
 * read/write. There may be multiple client processes that can only read or
 * read/write depending how the data structure was configured when
 * instantiated. The owner process is the process that created the array.
 * The shared memory is pinned (not reclaimed by the system) until the
 * owning process dies or the data structure is closed. This class
 * is <strong>not</strong> thread safe. You should not interact with
 * an instance of this class once it is closed.
 * </p>
 *
 * @hide
 */
public final class MemoryIntArray implements Parcelable, Closeable {
    private static final String TAG = "MemoryIntArray";

    private static final int MAX_SIZE = 1024;

    private final int mOwnerPid;
    private final boolean mClientWritable;
    private final long mMemoryAddr;
    private ParcelFileDescriptor mFd;

    /**
     * Creates a new instance.
     *
     * @param size The size of the array in terms of integer slots. Cannot be
     *     more than {@link #getMaxSize()}.
     * @param clientWritable Whether other processes can write to the array.
     * @throws IOException If an error occurs while accessing the shared memory.
     */
    public MemoryIntArray(int size, boolean clientWritable) throws IOException {
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("Max size is " + MAX_SIZE);
        }
        mOwnerPid = Process.myPid();
        mClientWritable = clientWritable;
        final String name = UUID.randomUUID().toString();
        mFd = ParcelFileDescriptor.fromFd(nativeCreate(name, size));
        mMemoryAddr = nativeOpen(mFd.getFd(), true, clientWritable);
    }

    private MemoryIntArray(Parcel parcel) throws IOException {
        mOwnerPid = parcel.readInt();
        mClientWritable = (parcel.readInt() == 1);
        mFd = parcel.readParcelable(null);
        if (mFd == null) {
            throw new IOException("No backing file descriptor");
        }
        final long memoryAddress = parcel.readLong();
        if (isOwner()) {
            mMemoryAddr = memoryAddress;
        } else {
            mMemoryAddr = nativeOpen(mFd.getFd(), false, mClientWritable);
        }
    }

    /**
     * @return Gets whether this array is mutable.
     */
    public boolean isWritable() {
        enforceNotClosed();
        return isOwner() || mClientWritable;
    }

    /**
     * Gets the value at a given index.
     *
     * @param index The index.
     * @return The value at this index.
     * @throws IOException If an error occurs while accessing the shared memory.
     */
    public int get(int index) throws IOException {
        enforceNotClosed();
        enforceValidIndex(index);
        return nativeGet(mFd.getFd(), mMemoryAddr, index, isOwner());
    }

    /**
     * Sets the value at a given index. This method can be called only if
     * {@link #isWritable()} returns true which means your process is the
     * owner.
     *
     * @param index The index.
     * @param value The value to set.
     * @throws IOException If an error occurs while accessing the shared memory.
     */
    public void set(int index, int value) throws IOException {
        enforceNotClosed();
        enforceWritable();
        enforceValidIndex(index);
        nativeSet(mFd.getFd(), mMemoryAddr, index, value, isOwner());
    }

    /**
     * Gets the array size.
     *
     * @throws IOException If an error occurs while accessing the shared memory.
     */
    public int size() throws IOException {
        enforceNotClosed();
        return nativeSize(mFd.getFd());
    }

    /**
     * Closes the array releasing resources.
     *
     * @throws IOException If an error occurs while accessing the shared memory.
     */
    @Override
    public void close() throws IOException {
        if (!isClosed()) {
            ParcelFileDescriptor pfd = mFd;
            mFd = null;
            nativeClose(pfd.getFd(), mMemoryAddr, isOwner());
        }
    }

    /**
     * @return Whether this array is closed and shouldn't be used.
     */
    public boolean isClosed() {
        return mFd == null;
    }

    @Override
    protected void finalize() throws Throwable {
        IoUtils.closeQuietly(this);
        super.finalize();
    }

    @Override
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mOwnerPid);
        parcel.writeInt(mClientWritable ? 1 : 0);
        parcel.writeParcelable(mFd, 0);
        parcel.writeLong(mMemoryAddr);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        MemoryIntArray other = (MemoryIntArray) obj;
        if (mFd == null) {
            if (other.mFd != null) {
                return false;
            }
        } else if (mFd.getFd() != other.mFd.getFd()) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return mFd != null ? mFd.hashCode() : 1;
    }

    private boolean isOwner() {
        return mOwnerPid == Process.myPid();
    }

    private void enforceNotClosed() {
        if (isClosed()) {
            throw new IllegalStateException("cannot interact with a closed instance");
        }
    }

    private void enforceValidIndex(int index) throws IOException {
        final int size = size();
        if (index < 0 || index > size - 1) {
            throw new IndexOutOfBoundsException(
                    index + " not between 0 and " + (size - 1));
        }
    }

    private void enforceWritable() {
        if (!isWritable()) {
            throw new UnsupportedOperationException("array is not writable");
        }
    }

    private native int nativeCreate(String name, int size);
    private native long nativeOpen(int fd, boolean owner, boolean writable);
    private native void nativeClose(int fd, long memoryAddr, boolean owner);
    private native int nativeGet(int fd, long memoryAddr, int index, boolean owner);
    private native void nativeSet(int fd, long memoryAddr, int index, int value, boolean owner);
    private native int nativeSize(int fd);

    /**
     * @return The max array size.
     */
    public static int getMaxSize() {
        return MAX_SIZE;
    }

    public static final Parcelable.Creator<MemoryIntArray> CREATOR =
            new Parcelable.Creator<MemoryIntArray>() {
        @Override
        public MemoryIntArray createFromParcel(Parcel parcel) {
            try {
                return new MemoryIntArray(parcel);
            } catch (IOException ioe) {
                Log.e(TAG, "Error unparceling MemoryIntArray");
                return null;
            }
        }

        @Override
        public MemoryIntArray[] newArray(int size) {
            return new MemoryIntArray[size];
        }
    };
}
