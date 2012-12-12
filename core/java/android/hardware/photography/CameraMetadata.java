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
public class CameraMetadata implements Parcelable {

    public CameraMetadata() {
        mMetadataMap = new HashMap<Key<?>, Object>();
    }

    private CameraMetadata(Parcel in) {

    }

    public static final Parcelable.Creator<CameraMetadata> CREATOR =
            new Parcelable.Creator<CameraMetadata>() {
        public CameraMetadata createFromParcel(Parcel in) {
            return new CameraMetadata(in);
        }

        public CameraMetadata[] newArray(int size) {
            return new CameraMetadata[size];
        }
    };

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
        return (T) mMetadataMap.get(key);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {

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

    private Map<Key<?>, Object> mMetadataMap;
}