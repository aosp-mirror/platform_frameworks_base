/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.photography;

import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * The base class for camera controls and information.
 *
 * This class defines the basic key/value map used for querying for camera
 * characteristics or capture results, and for setting camera request
 * parameters.
 *
 * @see CameraDevice
 * @see CameraManager
 * @see CameraProperties
 **/
public class CameraMetadata implements Parcelable, AutoCloseable {

    public CameraMetadata() {
        mMetadataMap = new HashMap<Key<?>, Object>();

        mMetadataPtr = nativeAllocate();
        if (mMetadataPtr == 0) {
            throw new OutOfMemoryError("Failed to allocate native CameraMetadata");
        }
    }

    public static final Parcelable.Creator<CameraMetadata> CREATOR =
            new Parcelable.Creator<CameraMetadata>() {
        @Override
        public CameraMetadata createFromParcel(Parcel in) {
            CameraMetadata metadata = new CameraMetadata();
            metadata.readFromParcel(in);
            return metadata;
        }

        @Override
        public CameraMetadata[] newArray(int size) {
            return new CameraMetadata[size];
        }
    };

    private static final String TAG = "CameraMetadataJV";

    /**
     * Set a camera metadata field to a value. The field definitions can be
     * found in {@link CameraProperties}, {@link CaptureResult}, and
     * {@link CaptureRequest}.
     *
     * @param key the metadata field to write.
     * @param value the value to set the field to, which must be of a matching
     * type to the key.
     */
    public <T> void set(Key<T> key, T value) {
        Log.e(TAG, "Not fully implemented yet");

        mMetadataMap.put(key, value);
    }

    /**
     * Get a camera metadata field value. The field definitions can be
     * found in {@link CameraProperties}, {@link CaptureResult}, and
     * {@link CaptureRequest}.
     *
     * @param key the metadata field to read.
     * @return the value of that key, or {@code null} if the field is not set.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        Log.e(TAG, "Not fully implemented yet");

        return (T) mMetadataMap.get(key);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        nativeWriteToParcel(dest);
    }

    /**
     * Expand this object from a Parcel.
     * @param in The Parcel from which the object should be read
     */
    public void readFromParcel(Parcel in) {
        nativeReadFromParcel(in);
    }

    public static class Key<T> {
        public Key(String name) {
            if (name == null) {
                throw new NullPointerException("Key needs a valid name");
            }
            mName = name;
        }

        public final String getName() {
            return mName;
        }

        @Override
        public final int hashCode() {
            return mName.hashCode();
        }

        @Override
        @SuppressWarnings("unchecked")
        public final boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof Key)) {
                return false;
            }

            Key lhs = (Key) o;

            return mName.equals(lhs.mName);
        }

        private final String mName;
    }

    private final Map<Key<?>, Object> mMetadataMap;

    /**
     * @hide
     */
    private long mMetadataPtr; // native CameraMetadata*

    private native long nativeAllocate();
    private native synchronized void nativeWriteToParcel(Parcel dest);
    private native synchronized void nativeReadFromParcel(Parcel source);
    private native synchronized void nativeSwap(CameraMetadata other) throws NullPointerException;
    private native synchronized void nativeClose();
    private native synchronized boolean nativeIsEmpty();
    private native synchronized int nativeGetEntryCount();
    private static native void nativeClassInit();

    /**
     * <p>Perform a 0-copy swap of the internal metadata with another object.</p>
     *
     * <p>Useful to convert a CameraMetadata into e.g. a CaptureRequest.</p>
     *
     * @param other metadata to swap with
     * @throws NullPointerException if other was null
     * @hide
     */
    public void swap(CameraMetadata other) {
        nativeSwap(other);
    }

    /**
     * @hide
     */
    public int getEntryCount() {
        return nativeGetEntryCount();
    }

    /**
     * Does this metadata contain at least 1 entry?
     *
     * @hide
     */
    public boolean isEmpty() {
        return nativeIsEmpty();
    }

    /**
     * <p>Closes this object, and releases all native resources associated with it.</p>
     *
     * <p>Calling any other public method after this will result in an IllegalStateException
     * being thrown.</p>
     */
    @Override
    public void close() throws Exception {
        // this sets mMetadataPtr to 0
        nativeClose();
        mMetadataPtr = 0; // set it to 0 again to prevent eclipse from making this field final
    }

    /**
     * Whether or not {@link #close} has already been called (at least once) on this object.
     * @hide
     */
    public boolean isClosed() {
        synchronized (this) {
            return mMetadataPtr == 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * We use a class initializer to allow the native code to cache some field offsets
     */
    static {
        System.loadLibrary("media_jni");
        nativeClassInit();
    }
}