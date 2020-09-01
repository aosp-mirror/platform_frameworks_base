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

import static android.system.OsConstants.MAP_SHARED;
import static android.system.OsConstants.PROT_READ;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.DirectByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides utilities for dealing with HidlMemory.
 *
 * @hide
 */
public final class HidlMemoryUtil {
    static private final String TAG = "HidlMemoryUtil";

    private HidlMemoryUtil() {
    }

    /**
     * Copies a byte-array into a new Ashmem region and return it as HidlMemory.
     * The returned instance owns the underlying file descriptors, and the client should generally
     * call close on it when no longer in use (or otherwise, when the object gets destroyed it will
     * be closed).
     *
     * @param input The input byte array.
     * @return A HidlMemory instance, containing a copy of the input.
     */
    public static @NonNull
    HidlMemory byteArrayToHidlMemory(@NonNull byte[] input) {
        return byteArrayToHidlMemory(input, null);
    }

    /**
     * Copies a byte-array into a new Ashmem region and return it as HidlMemory.
     * The returned instance owns the underlying file descriptors, and the client should generally
     * call close on it when no longer in use (or otherwise, when the object gets destroyed it will
     * be closed).
     *
     * @param input The input byte array.
     * @param name  An optional name for the ashmem region.
     * @return A HidlMemory instance, containing a copy of the input.
     */
    public static @NonNull
    HidlMemory byteArrayToHidlMemory(@NonNull byte[] input, @Nullable String name) {
        Preconditions.checkNotNull(input);

        if (input.length == 0) {
            return new HidlMemory("ashmem", 0, null);
        }

        try {
            SharedMemory shmem = SharedMemory.create(name != null ? name : "", input.length);
            ByteBuffer buffer = shmem.mapReadWrite();
            buffer.put(input);
            shmem.unmap(buffer);
            return sharedMemoryToHidlMemory(shmem);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Copies a byte list into a new Ashmem region and return it as HidlMemory.
     * The returned instance owns the underlying file descriptors, and the client should generally
     * call close on it when no longer in use (or otherwise, when the object gets destroyed it will
     * be closed).
     *
     * @param input The input byte list.
     * @return A HidlMemory instance, containing a copy of the input.
     */
    public static @NonNull
    HidlMemory byteListToHidlMemory(@NonNull List<Byte> input) {
        return byteListToHidlMemory(input, null);
    }

    /**
     * Copies a byte list into a new Ashmem region and return it as HidlMemory.
     * The returned instance owns the underlying file descriptors, and the client should generally
     * call close on it when no longer in use (or otherwise, when the object gets destroyed it will
     * be closed).
     *
     * @param input The input byte list.
     * @param name  An optional name for the ashmem region.
     * @return A HidlMemory instance, containing a copy of the input.
     */
    public static @NonNull
    HidlMemory byteListToHidlMemory(@NonNull List<Byte> input, @Nullable String name) {
        Preconditions.checkNotNull(input);

        if (input.isEmpty()) {
            return new HidlMemory("ashmem", 0, null);
        }

        try {
            SharedMemory shmem = SharedMemory.create(name != null ? name : "", input.size());
            ByteBuffer buffer = shmem.mapReadWrite();
            for (Byte b : input) {
                buffer.put(b);
            }
            shmem.unmap(buffer);
            return sharedMemoryToHidlMemory(shmem);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Copies all data from a HidlMemory instance into a byte array.
     *
     * @param mem The HidlMemory instance. Must be of name "ashmem" and of size that doesn't exceed
     *            {@link Integer#MAX_VALUE}.
     * @return A byte array, containing a copy of the input.
     */
    public static @NonNull
    byte[] hidlMemoryToByteArray(@NonNull HidlMemory mem) {
        Preconditions.checkNotNull(mem);
        Preconditions.checkArgumentInRange(mem.getSize(), 0L, (long) Integer.MAX_VALUE,
                "Memory size");
        Preconditions.checkArgument(mem.getSize() == 0 || mem.getName().equals("ashmem"),
                "Unsupported memory type: %s", mem.getName());

        if (mem.getSize() == 0) {
            return new byte[0];
        }

        ByteBuffer buffer = getBuffer(mem);
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /**
     * Copies all data from a HidlMemory instance into a byte list.
     *
     * @param mem The HidlMemory instance. Must be of name "ashmem" and of size that doesn't exceed
     *            {@link Integer#MAX_VALUE}.
     * @return A byte list, containing a copy of the input.
     */
    @SuppressLint("ConcreteCollection")
    public static @NonNull
    ArrayList<Byte> hidlMemoryToByteList(@NonNull HidlMemory mem) {
        Preconditions.checkNotNull(mem);
        Preconditions.checkArgumentInRange(mem.getSize(), 0L, (long) Integer.MAX_VALUE,
                "Memory size");
        Preconditions.checkArgument(mem.getSize() == 0 || mem.getName().equals("ashmem"),
                "Unsupported memory type: %s", mem.getName());

        if (mem.getSize() == 0) {
            return new ArrayList<>();
        }

        ByteBuffer buffer = getBuffer(mem);

        ArrayList<Byte> result = new ArrayList<>(buffer.remaining());
        while (buffer.hasRemaining()) {
            result.add(buffer.get());
        }
        return result;
    }

    /**
     * Converts a SharedMemory to a HidlMemory without copying.
     *
     * @param shmem The shared memory object. Null means "empty" and will still result in a non-null
     *              return value.
     * @return The HidlMemory instance.
     */
    @NonNull public static HidlMemory sharedMemoryToHidlMemory(@Nullable SharedMemory shmem) {
        if (shmem == null) {
            return new HidlMemory("ashmem", 0, null);
        }
        return fileDescriptorToHidlMemory(shmem.getFileDescriptor(), shmem.getSize());
    }

    /**
     * Converts a FileDescriptor to a HidlMemory without copying.
     *
     * @param fd   The FileDescriptor object. Null is allowed if size is 0 and will still result in
     *             a non-null return value.
     * @param size The size of the memory buffer.
     * @return The HidlMemory instance.
     */
    @NonNull public static HidlMemory fileDescriptorToHidlMemory(@Nullable FileDescriptor fd,
            int size) {
        Preconditions.checkArgument(fd != null || size == 0);
        if (fd == null) {
            return new HidlMemory("ashmem", 0, null);
        }
        NativeHandle handle = new NativeHandle(fd, true);
        return new HidlMemory("ashmem", size, handle);
    }

    private static ByteBuffer getBuffer(@NonNull HidlMemory mem) {
        try {
            final int size = (int) mem.getSize();

            if (size == 0) {
                return ByteBuffer.wrap(new byte[0]);
            }

            NativeHandle handle = mem.getHandle();

            final long address = Os.mmap(0, size, PROT_READ, MAP_SHARED, handle.getFileDescriptor(),
                    0);
            return new DirectByteBuffer(size, address, handle.getFileDescriptor(), () -> {
                try {
                    Os.munmap(address, size);
                } catch (ErrnoException e) {
                    Log.wtf(TAG, e);
                }
            }, true);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }
}
