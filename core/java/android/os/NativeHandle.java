/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.system.OsConstants.F_DUPFD_CLOEXEC;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.system.ErrnoException;
import android.system.Os;

import java.io.Closeable;
import java.io.FileDescriptor;

/**
 * Collection representing a set of open file descriptors and an opaque data stream.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class NativeHandle implements Closeable {
    // whether this object owns mFds
    private boolean mOwn = false;
    private FileDescriptor[] mFds;
    private int[] mInts;

    /**
     * Constructs a {@link NativeHandle} object containing
     * zero file descriptors and an empty data stream.
     */
    public NativeHandle() {
        this(new FileDescriptor[0], new int[0], false);
    }

    /**
     * Constructs a {@link NativeHandle} object containing the given
     * {@link FileDescriptor} object and an empty data stream.
     */
    public NativeHandle(@NonNull FileDescriptor descriptor, boolean own) {
        this(new FileDescriptor[] {descriptor}, new int[0], own);
    }

    /**
     * Convenience method for creating a list of file descriptors.
     *
     * @hide
     */
    private static FileDescriptor[] createFileDescriptorArray(@NonNull int[] fds) {
        FileDescriptor[] list = new FileDescriptor[fds.length];
        for (int i = 0; i < fds.length; i++) {
            FileDescriptor descriptor = new FileDescriptor();
            descriptor.setInt$(fds[i]);
            list[i] = descriptor;
        }
        return list;
    }

    /**
     * Convenience method for instantiating a {@link NativeHandle} from JNI. It does
     * not take ownership of the int[] params. It does not dupe the FileDescriptors.
     *
     * @hide
     */
    private NativeHandle(@NonNull int[] fds, @NonNull int[] ints, boolean own) {
        this(createFileDescriptorArray(fds), ints, own);
    }

    /**
     * Instantiate an opaque {@link NativeHandle} from fds and integers.
     *
     * @param own whether the fds are owned by this object and should be closed
     */
    public NativeHandle(@NonNull FileDescriptor[] fds, @NonNull int[] ints, boolean own) {
        mFds = fds.clone();
        mInts = ints.clone();
        mOwn = own;
    }

    /**
     * Returns whether this {@link NativeHandle} object contains a single file
     * descriptor and nothing else.
     *
     * @return a boolean value
     */
    public boolean hasSingleFileDescriptor() {
        checkOpen();

        return mFds.length == 1 && mInts.length == 0;
    }

    /**
     * Explicitly duplicate NativeHandle (this dups all file descritptors).
     *
     * If this method is called, this must also be explicitly closed with
     * {@link #close()}.
     */
    public @NonNull NativeHandle dup() throws java.io.IOException {
        FileDescriptor[] fds = new FileDescriptor[mFds.length];
        try {
            for (int i = 0; i < mFds.length; i++) {
                FileDescriptor newFd = new FileDescriptor();
                int fdint = Os.fcntlInt(mFds[i], F_DUPFD_CLOEXEC, 0);
                newFd.setInt$(fdint);
                fds[i] = newFd;
            }
        } catch (ErrnoException e) {
            e.rethrowAsIOException();
        }
        return new NativeHandle(fds, mInts, true /*own*/);
    }

    private void checkOpen() {
        if (mFds == null) {
            throw new IllegalStateException("NativeHandle is invalidated after close.");
        }
    }

    /**
     * Closes the file descriptors if they are owned by this object.
     *
     * This also invalidates the object.
     */
    @Override
    public void close() throws java.io.IOException {
        checkOpen();

        if (mOwn) {
            try {
                for (FileDescriptor fd : mFds) {
                    Os.close(fd);
                }
            } catch (ErrnoException e) {
                e.rethrowAsIOException();
            }

            mOwn = false;
        }

        mFds = null;
        mInts = null;
    }

    /**
     * Returns the underlying lone file descriptor.
     *
     * @return a {@link FileDescriptor} object
     * @throws IllegalStateException if this object contains either zero or
     *         more than one file descriptor, or a non-empty data stream.
     */
    public @NonNull FileDescriptor getFileDescriptor() {
        checkOpen();

        if (!hasSingleFileDescriptor()) {
            throw new IllegalStateException(
                    "NativeHandle is not single file descriptor. Contents must"
                    + " be retreived through getFileDescriptors and getInts.");
        }

        return mFds[0];
    }

    /**
     * Convenience method for fetching this object's file descriptors from JNI.
     * @return a mutable copy of the underlying file descriptors (as an int[])
     *
     * @hide
     */
    private int[] getFdsAsIntArray() {
        checkOpen();

        int numFds = mFds.length;
        int[] fds = new int[numFds];

        for (int i = 0; i < numFds; i++) {
            fds[i] = mFds[i].getInt$();
        }

        return fds;
    }

    /**
     * Fetch file descriptors
     *
     * @return the fds.
     */
    public @NonNull FileDescriptor[] getFileDescriptors() {
        checkOpen();

        return mFds;
    }

    /**
     * Fetch opaque ints. Note: This object retains ownership of the data.
     *
     * @return the opaque data stream.
     */
    public @NonNull int[] getInts() {
        checkOpen();

        return mInts;
    }
}
