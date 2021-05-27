/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import dalvik.system.VMRuntime;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.DirectByteBuffer;
import java.nio.NioUtils;

import sun.misc.Cleaner;

/**
 * SharedMemory enables the creation, mapping, and protection control over anonymous shared memory.
 */
public final class SharedMemory implements Parcelable, Closeable {

    private final FileDescriptor mFileDescriptor;
    private final int mSize;
    private final MemoryRegistration mMemoryRegistration;
    private Cleaner mCleaner;

    private SharedMemory(FileDescriptor fd) {
        // This constructor is only used internally so it should be impossible to hit any of the
        // exceptions unless something goes horribly wrong.
        if (fd == null) {
            throw new IllegalArgumentException(
                    "Unable to create SharedMemory from a null FileDescriptor");
        }
        if (!fd.valid()) {
            throw new IllegalArgumentException(
                    "Unable to create SharedMemory from closed FileDescriptor");
        }
        mFileDescriptor = fd;
        mSize = nGetSize(mFileDescriptor);
        if (mSize <= 0) {
            throw new IllegalArgumentException("FileDescriptor is not a valid ashmem fd");
        }

        mMemoryRegistration = new MemoryRegistration(mSize);
        mCleaner = Cleaner.create(mFileDescriptor,
                new Closer(mFileDescriptor, mMemoryRegistration));
    }

    /**
     * Creates an anonymous SharedMemory instance with the provided debug name and size. The name
     * is only used for debugging purposes and can help identify what the shared memory is used
     * for when inspecting memory maps for the processes that have mapped this SharedMemory
     * instance.
     *
     * @param name The debug name to use for this SharedMemory instance. This can be null, however
     *             a debug name is recommended to help identify memory usage when using tools
     *             such as lsof or examining /proc/[pid]/maps
     * @param size The size of the shared memory to create. Must be greater than 0.
     * @return A SharedMemory instance of the requested size
     * @throws ErrnoException if the requested allocation fails.
     */
    public static @NonNull SharedMemory create(@Nullable String name, int size)
            throws ErrnoException {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be greater than zero");
        }
        return new SharedMemory(nCreate(name, size));
    }

    private void checkOpen() {
        if (!mFileDescriptor.valid()) {
            throw new IllegalStateException("SharedMemory is closed");
        }
    }

    /**
     * Creates from existing shared memory passed as {@link ParcelFileDesciptor}.
     *
     * @param fd File descriptor of shared memory passed as {@link #ParcelFileDescriptor}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static @NonNull SharedMemory create(@NonNull ParcelFileDescriptor fd) {
        return new SharedMemory(fd.getFileDescriptor());
    }

    private static final int PROT_MASK = OsConstants.PROT_READ | OsConstants.PROT_WRITE
            | OsConstants.PROT_EXEC | OsConstants.PROT_NONE;

    private static void validateProt(int prot) {
        if ((prot & ~PROT_MASK) != 0) {
            throw new IllegalArgumentException("Invalid prot value");
        }
    }

    /**
     * Sets the protection on the shared memory to the combination specified in prot, which
     * is either a bitwise-or'd combination of {@link android.system.OsConstants#PROT_READ},
     * {@link android.system.OsConstants#PROT_WRITE}, {@link android.system.OsConstants#PROT_EXEC}
     * from {@link android.system.OsConstants}, or {@link android.system.OsConstants#PROT_NONE},
     * to remove all further access.
     *
     * Note that protection can only ever be removed, not added. By default shared memory
     * is created with protection set to PROT_READ | PROT_WRITE | PROT_EXEC. The protection
     * passed here also only applies to any mappings created after calling this method. Existing
     * mmaps of the shared memory retain whatever protection they had when they were created.
     *
     * A common usage of this is to share a read-only copy of the data with something else. To do
     * that first create the read/write mapping with PROT_READ | PROT_WRITE,
     * then call setProtect(PROT_READ) to remove write capability, then send the SharedMemory
     * to another process. That process will only be able to mmap with PROT_READ.
     *
     * @param prot Any bitwise-or'ed combination of
     *                  {@link android.system.OsConstants#PROT_READ},
     *                  {@link android.system.OsConstants#PROT_WRITE}, and
     *                  {@link android.system.OsConstants#PROT_EXEC}; or
     *                  {@link android.system.OsConstants#PROT_NONE}
     * @return Whether or not the requested protection was applied. Returns true on success,
     * false if the requested protection was broader than the existing protection.
     */
    public boolean setProtect(int prot) {
        checkOpen();
        validateProt(prot);
        int errno = nSetProt(mFileDescriptor, prot);
        return errno == 0;
    }

    /**
     * Returns the backing {@link FileDescriptor} for this SharedMemory object. The SharedMemory
     * instance retains ownership of the FileDescriptor.
     *
     * This FileDescriptor is interoperable with the ASharedMemory NDK APIs.
     *
     * @return Returns the FileDescriptor associated with this object.
     *
     * @hide Exists only for MemoryFile interop
     */
    public @NonNull FileDescriptor getFileDescriptor() {
        return mFileDescriptor;
    }

    /**
     * Returns the backing native fd int for this SharedMemory object. The SharedMemory
     * instance retains ownership of the fd.
     *
     * This fd is interoperable with the ASharedMemory NDK APIs.
     *
     * @return Returns the native fd associated with this object, or -1 if it is already closed.
     *
     * @hide Exposed for native ASharedMemory_dupFromJava()
     */
    @UnsupportedAppUsage(trackingBug = 171971817)
    public int getFd() {
        return mFileDescriptor.getInt$();
    }

    /**
     * @return The size of the SharedMemory region.
     */
    public int getSize() {
        checkOpen();
        return mSize;
    }

    /**
     * Creates a read/write mapping of the entire shared memory region. This requires the the
     * protection level of the shared memory is at least PROT_READ|PROT_WRITE or the map will fail.
     *
     * Use {@link #map(int, int, int)} to have more control over the mapping if desired.
     * This is equivalent to map(OsConstants.PROT_READ | OsConstants.PROT_WRITE, 0, getSize())
     *
     * @return A ByteBuffer mapping
     * @throws ErrnoException if the mmap call failed.
     */
    public @NonNull ByteBuffer mapReadWrite() throws ErrnoException {
        return map(OsConstants.PROT_READ | OsConstants.PROT_WRITE, 0, mSize);
    }

    /**
     * Creates a read-only mapping of the entire shared memory region. This requires the the
     * protection level of the shared memory is at least PROT_READ or the map will fail.
     *
     * Use {@link #map(int, int, int)} to have more control over the mapping if desired.
     * This is equivalent to map(OsConstants.PROT_READ, 0, getSize())
     *
     * @return A ByteBuffer mapping
     * @throws ErrnoException if the mmap call failed.
     */
    public @NonNull ByteBuffer mapReadOnly() throws ErrnoException {
        return map(OsConstants.PROT_READ, 0, mSize);
    }

    /**
     * Creates an mmap of the SharedMemory with the specified prot, offset, and length. This will
     * always produce a new ByteBuffer window to the backing shared memory region. Every call
     * to map() may be paired with a call to {@link #unmap(ByteBuffer)} when the ByteBuffer
     * returned by map() is no longer needed.
     *
     * @param prot A bitwise-or'd combination of PROT_READ, PROT_WRITE, PROT_EXEC, or PROT_NONE.
     * @param offset The offset into the shared memory to begin mapping. Must be >= 0 and less than
     *         getSize().
     * @param length The length of the region to map. Must be > 0 and offset + length must not
     *         exceed getSize().
     * @return A ByteBuffer mapping.
     * @throws ErrnoException if the mmap call failed.
     */
    public @NonNull ByteBuffer map(int prot, int offset, int length) throws ErrnoException {
        checkOpen();
        validateProt(prot);
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be >= 0");
        }
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be > 0");
        }
        if (offset + length > mSize) {
            throw new IllegalArgumentException("offset + length must not exceed getSize()");
        }
        long address = Os.mmap(0, length, prot, OsConstants.MAP_SHARED, mFileDescriptor, offset);
        boolean readOnly = (prot & OsConstants.PROT_WRITE) == 0;
        Runnable unmapper = new Unmapper(address, length, mMemoryRegistration.acquire());
        return new DirectByteBuffer(length, address, mFileDescriptor, unmapper, readOnly);
    }

    /**
     * Unmaps a buffer previously returned by {@link #map(int, int, int)}. This will immediately
     * release the backing memory of the ByteBuffer, invalidating all references to it. Only
     * call this method if there are no duplicates of the ByteBuffer in use and don't
     * access the ByteBuffer after calling this method.
     *
     * @param buffer The buffer to unmap
     */
    public static void unmap(@NonNull ByteBuffer buffer) {
        if (buffer instanceof DirectByteBuffer) {
            NioUtils.freeDirectBuffer(buffer);
        } else {
            throw new IllegalArgumentException(
                    "ByteBuffer wasn't created by #map(int, int, int); can't unmap");
        }
    }

    /**
     * Close the backing {@link FileDescriptor} of this SharedMemory instance. Note that all
     * open mappings of the shared memory will remain valid and may continue to be used. The
     * shared memory will not be freed until all file descriptor handles are closed and all
     * memory mappings are unmapped.
     */
    @Override
    public void close() {
        if (mCleaner != null) {
            mCleaner.clean();
            mCleaner = null;
        }
    }

    @Override
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        checkOpen();
        dest.writeFileDescriptor(mFileDescriptor);
    }

    /**
     * Returns a dup'd ParcelFileDescriptor from the SharedMemory FileDescriptor.
     * This obeys standard POSIX semantics, where the
     * new file descriptor shared state such as file position with the
     * original file descriptor.
     * TODO: propose this method as a public or system API for next release to achieve parity with
     *  NDK ASharedMemory_dupFromJava.
     *
     * @hide
     */
    public ParcelFileDescriptor getFdDup() throws IOException {
        return ParcelFileDescriptor.dup(mFileDescriptor);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<SharedMemory> CREATOR =
            new Parcelable.Creator<SharedMemory>() {
        @Override
        public SharedMemory createFromParcel(Parcel source) {
            FileDescriptor descriptor = source.readRawFileDescriptor();
            return new SharedMemory(descriptor);
        }

        @Override
        public SharedMemory[] newArray(int size) {
            return new SharedMemory[size];
        }
    };

    /**
     * Cleaner that closes the FD
     */
    private static final class Closer implements Runnable {
        private FileDescriptor mFd;
        private MemoryRegistration mMemoryReference;

        private Closer(FileDescriptor fd, MemoryRegistration memoryReference) {
            mFd = fd;
            mMemoryReference = memoryReference;
        }

        @Override
        public void run() {
            try {
                Os.close(mFd);
            } catch (ErrnoException e) { /* swallow error */ }
            mMemoryReference.release();
            mMemoryReference = null;
        }
    }

    /**
     * Cleaner that munmap regions
     */
    private static final class Unmapper implements Runnable {
        private long mAddress;
        private int mSize;
        private MemoryRegistration mMemoryReference;

        private Unmapper(long address, int size, MemoryRegistration memoryReference) {
            mAddress = address;
            mSize = size;
            mMemoryReference = memoryReference;
        }

        @Override
        public void run() {
            try {
                Os.munmap(mAddress, mSize);
            } catch (ErrnoException e) { /* swallow exception */ }
            mMemoryReference.release();
            mMemoryReference = null;
        }
    }

    /**
     * Helper class that ensures that the native allocation pressure against the VM heap stays
     * active until the FD is closed as well as all mappings from that FD are closed.
     */
    private static final class MemoryRegistration {
        private int mSize;
        private int mReferenceCount;

        private MemoryRegistration(int size) {
            mSize = size;
            mReferenceCount = 1;
            VMRuntime.getRuntime().registerNativeAllocation(mSize);
        }

        public synchronized MemoryRegistration acquire() {
            mReferenceCount++;
            return this;
        }

        public synchronized void release() {
            mReferenceCount--;
            if (mReferenceCount == 0) {
                VMRuntime.getRuntime().registerNativeFree(mSize);
            }
        }
    }

    private static native FileDescriptor nCreate(String name, int size) throws ErrnoException;
    private static native int nGetSize(FileDescriptor fd);
    private static native int nSetProt(FileDescriptor fd, int prot);
}
