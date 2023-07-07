/**
 * Copyright (C) 2022 The Android Open Source Project
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

package android.hardware;

import android.annotation.NonNull;
import android.media.Image;
import android.media.ImageWriter;
import android.opengl.EGLDisplay;
import android.opengl.EGLSync;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.SystemClock;

import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.io.IOException;
import java.time.Duration;

/**
 * A SyncFence represents a synchronization primitive which signals when hardware units have
 * completed work on a particular resource. They initially start in an unsignaled state and make
 * a one-time transition to either a signaled or error state. SyncFences are created by various
 * device APIs in response to submitting tasks to the device. They cannot be created nor signaled
 * by userspace. As a result, this means that a SyncFence will make always make forward progress.
 *
 * <p>SyncFence's generally come in one of two varieties. "Presentation fences" refer to
 *  a SyncFence when the writing to a buffer has finished. "Release fences" then refer
 *  to when the reading from a buffer has finished.</p>
 *
 * <p>For example, a GPU rendering to a framebuffer may generate a synchronization fence,
 * e.g., an EGLSync or VkFence, which signals when rendering has completed. Once the fence signals,
 * then the backing storage for the framebuffer may be safely read from, such as for display or
 * for media encoding. This would be referred to as a "presentation fence."</p>
 *
 * <p>Similarly when using an {@link android.media.ImageWriter} it is possible that an
 * {@link android.media.Image} returned by {@link ImageWriter#dequeueInputImage()} may already
 * have a {@link Image#getFence() fence} set on it. This would be what is referred to as either
 * a "release fence" or an "acqurie fence" and indicates the fence that the writer must wait
 * on before writing to the underlying buffer. In the case of ImageWriter this is done
 * automatically when eg {@link Image#getPlanes()} is called, however when using
 * {@link Image#getHardwareBuffer()} it is the caller's responsibility to ensure the
 * release fence has signaled before writing to the buffer.</p>
 *
 * @see android.opengl.EGLExt#eglDupNativeFenceFDANDROID(EGLDisplay, EGLSync)
 * @see android.media.Image#getFence()
 */
public final class SyncFence implements AutoCloseable, Parcelable {

    /**
     * An invalid signal time. Represents either the signal time for a SyncFence that isn't valid
     * (that is, {@link #isValid()} is false), or if an error occurred while attempting to retrieve
     * the signal time.
     */
    public static final long SIGNAL_TIME_INVALID = -1;

    /**
     * A pending signal time. This is equivalent to the max value of a long, representing an
     * infinitely far point in the future.
     */
    public static final long SIGNAL_TIME_PENDING = Long.MAX_VALUE;

    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createNonmalloced(SyncFence.class.getClassLoader(),
                    nGetDestructor(), 4);

    private long mNativePtr;

    // The destructor for this object
    // This is also used as our internal lock object. Although SyncFence doesn't claim to be
    // thread-safe, the cost of doing so to avoid issues around double-close or similar issues
    // is well worth making.
    private final Runnable mCloser;

    private SyncFence(int fileDescriptor) {
        mNativePtr = nCreate(fileDescriptor);
        mCloser = sRegistry.registerNativeAllocation(this, mNativePtr);
    }

    private SyncFence(@NonNull Parcel parcel) {
        boolean valid = parcel.readBoolean();
        FileDescriptor fileDescriptor = null;
        if (valid) {
            fileDescriptor = parcel.readRawFileDescriptor();
        }
        if (fileDescriptor != null) {
            mNativePtr = nCreate(fileDescriptor.getInt$());
            mCloser = sRegistry.registerNativeAllocation(this, mNativePtr);
        } else {
            mCloser = () -> {};
        }
    }

    /**
     * Creates a SyncFence from a libui Fence*
     * DOES NOT TAKE AN ADDITIONAL REFERENCE, the caller must incref if it intends to retain
     * ownership (eg, when using sp<Fence>)
     * @hide
     */
    public SyncFence(long nativeFencePtr) {
        mNativePtr = nativeFencePtr;
        if (nativeFencePtr != 0) {
            mCloser = sRegistry.registerNativeAllocation(this, mNativePtr);
        } else {
            mCloser = () -> {};
        }
    }

    private SyncFence() {
        mCloser = () -> {};
    }

    /***
     * Create an empty SyncFence
     *
     * @return a SyncFence with invalid fence
     * @hide
     */
    public static @NonNull SyncFence createEmpty() {
        return new SyncFence();
    }

    /**
     * Create a new SyncFence wrapped around another {@link ParcelFileDescriptor}. By default, all
     * method calls are delegated to the wrapped descriptor. This takes ownership of the
     * {@link ParcelFileDescriptor}.
     *
     * @param wrapped The descriptor to be wrapped.
     * @hide
     */
    public static @NonNull SyncFence create(@NonNull ParcelFileDescriptor wrapped) {
        return new SyncFence(wrapped.detachFd());
    }

    /**
     * Create a new SyncFence wrapped around another descriptor. The returned {@link SyncFence}
     * instance takes ownership of the file descriptor.
     *
     * @param fileDescriptor The descriptor to be wrapped.
     * @hide
     */
    public static @NonNull SyncFence adopt(int fileDescriptor) {
        return new SyncFence(fileDescriptor);
    }

    /**
     * Return a dup'd ParcelFileDescriptor from the SyncFence ParcelFileDescriptor.
     * @hide
     */
    public @NonNull ParcelFileDescriptor getFdDup() throws IOException {
        synchronized (mCloser) {
            final int fd = mNativePtr != 0 ? nGetFd(mNativePtr) : -1;
            if (fd == -1) {
                throw new IllegalStateException("Cannot dup the FD of an invalid SyncFence");
            }
            return ParcelFileDescriptor.fromFd(fd);
        }
    }

    /**
     * Checks if the SyncFile object is valid.
     *
     * @return {@code true} if the file descriptor represents a valid, open file;
     *         {@code false} otherwise.
     */
    public boolean isValid() {
        synchronized (mCloser) {
            return mNativePtr != 0 && nIsValid(mNativePtr);
        }
    }

    /**
     * Waits for a SyncFence to signal for up to the timeout duration.
     *
     * An invalid SyncFence, that is if {@link #isValid()} is false, is treated equivalently
     * to a SyncFence that has already signaled. That is, wait() will immediately return true.
     *
     * @param timeout The timeout duration. If the duration is negative, then this waits forever.
     * @return true if the fence signaled or isn't valid, false otherwise.
     */
    public boolean await(@NonNull Duration timeout) {
        final long timeoutNanos;
        if (timeout.isNegative()) {
            timeoutNanos = -1;
        } else {
            timeoutNanos = timeout.toNanos();
        }
        return await(timeoutNanos);
    }

    /**
     * Waits forever for a SyncFence to signal.
     *
     * An invalid SyncFence, that is if {@link #isValid()} is false, is treated equivalently
     * to a SyncFence that has already signaled. That is, wait() will immediately return true.
     *
     * @return true if the fence signaled or isn't valid, false otherwise.
     */
    public boolean awaitForever() {
        return await(-1);
    }

    private boolean await(long timeoutNanos) {
        synchronized (mCloser) {
            return mNativePtr != 0 && nWait(mNativePtr, timeoutNanos);
        }
    }

    /**
     * Returns the time in nanoseconds that the fence signaled in the CLOCK_MONOTONIC time domain.
     * This corresponds to {@link System#nanoTime()} but may also be compared to
     * {@link SystemClock#uptimeMillis()} after adjusting for milliseconds vs. nanoseconds.
     *
     * If the fence isn't valid, that is if {@link #isValid()} is false, then this returns
     * {@link #SIGNAL_TIME_INVALID}. Similarly, if an error occurs while trying to access the
     * signal time, then {@link #SIGNAL_TIME_INVALID} is also returned.
     *
     * If the fence hasn't yet signaled, then {@link #SIGNAL_TIME_PENDING} is returned.
     *
     * @return The time the fence signaled, {@link #SIGNAL_TIME_INVALID} if there's an error,
     *         or {@link #SIGNAL_TIME_PENDING} if the fence hasn't signaled yet.
     */
    public long getSignalTime() {
        synchronized (mCloser) {
            return mNativePtr != 0 ? nGetSignalTime(mNativePtr) : SIGNAL_TIME_INVALID;
        }
    }

    /**
     * Close the SyncFence. This implementation closes the underlying OS resources allocated
     * this stream.
     */
    @Override
    public void close() {
        synchronized (mCloser) {
            if (mNativePtr == 0) {
                return;
            }
            mNativePtr = 0;
            mCloser.run();
        }
    }

    @Override
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }

    /** @hide */
    public Object getLock() {
        return mCloser;
    }

    /** @hide */
    public long getNativeFence() {
        return mNativePtr;
    }

    /**
     * Flatten this object into a Parcel.
     *
     * @param out The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be {@code 0} or {@link #PARCELABLE_WRITE_RETURN_VALUE}
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        synchronized (mCloser) {
            final int fd = mNativePtr != 0 ? nGetFd(mNativePtr) : -1;
            if (fd == -1) {
                out.writeBoolean(false);
            } else {
                out.writeBoolean(true);
                FileDescriptor temp = new FileDescriptor();
                temp.setInt$(fd);
                out.writeFileDescriptor(temp);
            }
        }
    }

    public static final @NonNull Parcelable.Creator<SyncFence> CREATOR =
            new Parcelable.Creator<SyncFence>() {
                @Override
                public SyncFence createFromParcel(Parcel in) {
                    return new SyncFence(in);
                }

                @Override
                public SyncFence[] newArray(int size) {
                    return new SyncFence[size];
                }
            };

    private static native long nGetDestructor();
    private static native long nCreate(int fd);
    private static native boolean nIsValid(long nPtr);
    private static native int nGetFd(long nPtr);
    private static native boolean nWait(long nPtr, long timeout);
    private static native long nGetSignalTime(long nPtr);
}
