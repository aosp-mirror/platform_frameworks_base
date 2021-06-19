/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import java.io.Closeable;
import java.io.IOException;

/**
 * An abstract representation of a memory block, as representing by the HIDL system.
 *
 * The block is defined by a {name, size, handle} tuple, where the name is used to determine how to
 * interpret the handle. The underlying handle is assumed to be owned by this instance and will be
 * closed as soon as {@link #close()} is called on this instance, or this instance has been
 * finalized (the latter supports using it in a shared manner without having to worry about who owns
 * this instance, the former is more efficient resource-wise and is recommended for most use-cases).
 * Note, however, that ownership of the handle does not necessarily imply ownership of the
 * underlying file descriptors - the underlying handle may or may not own them. If you want the
 * underlying handle to outlive this instance, call {@link #releaseHandle()} to obtain the handle
 * and detach the ownership relationship.
 *
 * @hide
 */
@SystemApi
public class HidlMemory implements Closeable {
    private final @NonNull String mName;
    private final long mSize;
    private @Nullable NativeHandle mHandle;
    private long mNativeContext;  // For use of native code.

    /**
     * Constructor.
     *
     * @param name      The name of the IMapper service used to resolve the handle (e.g. "ashmem").
     * @param size      The (non-negative) size in bytes of the memory block.
     * @param handle    The handle. May be null. This instance will own the handle and will close it
     *                  as soon as {@link #close()} is called or the object is destroyed. This, this
     *                  handle instance should generally not be shared with other clients.
     */
    public HidlMemory(@NonNull String name, @IntRange(from = 0) long size,
            @Nullable NativeHandle handle) {
        mName = name;
        mSize = size;
        mHandle = handle;
    }

    /**
     * Create a copy of this instance, where the underlying handle (and its file descriptors) have
     * been duplicated.
     */
    @NonNull
    public HidlMemory dup() throws IOException {
        return new HidlMemory(mName, mSize, mHandle != null ? mHandle.dup() : null);
    }

    /**
     * Close the underlying native handle. No-op if handle is null or has been released using {@link
     * #releaseHandle()}.
     */
    @Override
    public void close() throws IOException {
        if (mHandle != null) {
            mHandle.close();
            mHandle = null;
        }
    }

    /**
     * Disowns the underlying handle and returns it. The underlying handle becomes null.
     *
     * @return The underlying handle.
     */
    @Nullable
    public NativeHandle releaseHandle() {
        NativeHandle handle = mHandle;
        mHandle = null;
        return handle;
    }

    /**
     * Gets the name, which represents how the handle is to be interpreted.
     *
     * @return The name.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Gets the size of the block, in bytes.
     *
     * @return The size.
     */
    public long getSize() {
        return mSize;
    }

    /**
     * Gets a native handle. The actual interpretation depends on the name and is implementation
     * defined.
     *
     * @return The native handle.
     */
    @Nullable
    public NativeHandle getHandle() {
        return mHandle;
    }

    @Override
    protected void finalize() {
        try {
            close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            nativeFinalize();
        }
    }

    private native void nativeFinalize();
}
