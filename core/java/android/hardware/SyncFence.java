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
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.Closeable;
import java.io.IOException;

/**
 * A SyncFence represents a synchronization primitive which signals when hardware buffers have
 * completed work on a particular resource.
 *
 * <p>For example, a GPU rendering to a framebuffer may generate a synchronization fence,
 * e.g., a VkFence, which signals when rendering has completed.
 *
 * Once the fence signals, then the backing storage for the framebuffer may be safely read from,
 * such as for display or for media encoding.</p>
 *
 * @see <a href="https://www.khronos.org/registry/vulkan/specs/1.3-extensions/man/html/vkCreateFence.html">
 * VkFence</a>
 */
public final class SyncFence implements Closeable, Parcelable {
    private static final String TAG = "SyncFence";

    /**
     * Wrapped {@link android.os.ParcelFileDescriptor}.
     */
    private ParcelFileDescriptor mWrapped;

    private SyncFence(@NonNull ParcelFileDescriptor wrapped) {
        mWrapped = wrapped;
    }

    /***
     * Create an empty SyncFence
     *
     * @return a SyncFence with invalid fence
     * @hide
     */
    public static @NonNull SyncFence createEmpty() {
        return new SyncFence(ParcelFileDescriptor.adoptFd(-1));
    }

    /**
     * Create a new SyncFence wrapped around another descriptor. By default, all method calls are
     * delegated to the wrapped descriptor.
     *
     * @param wrapped The descriptor to be wrapped.
     * @hide
     */
    public static @NonNull SyncFence create(@NonNull ParcelFileDescriptor wrapped) {
        return new SyncFence(wrapped);
    }

    /**
     * Return a dup'd ParcelFileDescriptor from the SyncFence ParcelFileDescriptor.
     * @hide
     */
    public @NonNull ParcelFileDescriptor getFdDup() throws IOException {
        return mWrapped.dup();
    }

    /**
     * Checks if the SyncFile object is valid.
     *
     * @return {@code true} if the file descriptor represents a valid, open file;
     *         {@code false} otherwise.
     */
    public boolean isValid() {
        return mWrapped.getFileDescriptor().valid();
    }

    /**
     * Close the SyncFence. This implementation closes the underlying OS resources allocated
     * this stream.
     *
     * @throws IOException If an error occurs attempting to close this SyncFence.
     */
    @Override
    public void close() throws IOException {
        if (mWrapped != null) {
            try {
                mWrapped.close();
            } finally {
                // success
            }
        }
    }

    @Override
    public int describeContents() {
        return mWrapped.describeContents();
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
        try {
            mWrapped.writeToParcel(out, flags);
        } finally {
            // success
        }
    }

    public static final @NonNull Parcelable.Creator<SyncFence> CREATOR =
            new Parcelable.Creator<SyncFence>() {
        @Override
        public SyncFence createFromParcel(Parcel in) {
            return new SyncFence(ParcelFileDescriptor.CREATOR.createFromParcel(in));
        }

        @Override
        public SyncFence[] newArray(int size) {
            return new SyncFence[size];
        }
    };
}
