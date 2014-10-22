/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.hardware.camera2.marshal.impl;

import android.hardware.camera2.marshal.Marshaler;
import android.hardware.camera2.marshal.MarshalQueryable;
import android.hardware.camera2.utils.TypeReference;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

/**
 * Marshal any {@code T extends Parcelable} to/from any native type
 *
 * <p>Use with extreme caution! File descriptors and binders will not be marshaled across.</p>
 */
public class MarshalQueryableParcelable<T extends Parcelable>
        implements MarshalQueryable<T> {

    private static final String TAG = "MarshalParcelable";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private static final String FIELD_CREATOR = "CREATOR";

    private class MarshalerParcelable extends Marshaler<T> {

        private final Class<T> mClass;
        private final Parcelable.Creator<T> mCreator;

        @SuppressWarnings("unchecked")
        protected MarshalerParcelable(TypeReference<T> typeReference,
                int nativeType) {
            super(MarshalQueryableParcelable.this, typeReference, nativeType);

            mClass = (Class<T>)typeReference.getRawType();
            Field creatorField;
            try {
                creatorField = mClass.getDeclaredField(FIELD_CREATOR);
            } catch (NoSuchFieldException e) {
                // Impossible. All Parcelable implementations must have a 'CREATOR' static field
                throw new AssertionError(e);
            }

            try {
                mCreator = (Parcelable.Creator<T>)creatorField.get(null);
            } catch (IllegalAccessException e) {
                // Impossible: All 'CREATOR' static fields must be public
                throw new AssertionError(e);
            } catch (IllegalArgumentException e) {
                // Impossible: This is a static field, so null must be ok
                throw new AssertionError(e);
            }
        }

        @Override
        public void marshal(T value, ByteBuffer buffer) {
            if (VERBOSE) {
                Log.v(TAG, "marshal " + value);
            }

            Parcel parcel = Parcel.obtain();
            byte[] parcelContents;

            try {
                value.writeToParcel(parcel, /*flags*/0);

                if (parcel.hasFileDescriptors()) {
                    throw new UnsupportedOperationException(
                            "Parcelable " + value + " must not have file descriptors");
                }

                parcelContents = parcel.marshall();
            }
            finally {
                parcel.recycle();
            }

            if (parcelContents.length == 0) {
                throw new AssertionError("No data marshaled for " + value);
            }

            buffer.put(parcelContents);
        }

        @Override
        public T unmarshal(ByteBuffer buffer) {
            if (VERBOSE) {
                Log.v(TAG, "unmarshal, buffer remaining " + buffer.remaining());
            }

            /*
             * Quadratically slow when marshaling an array of parcelables.
             *
             * Read out the entire byte buffer as an array, then copy it into the parcel.
             *
             * Once we unparcel the entire object, advance the byte buffer by only how many
             * bytes the parcel actually used up.
             *
             * Future: If we ever do need to use parcelable arrays, we can do this a little smarter
             * by reading out a chunk like 4,8,16,24 each time, but not sure how to detect
             * parcels being too short in this case.
             *
             * Future: Alternatively use Parcel#obtain(long) directly into the native
             * pointer of a ByteBuffer, which would not copy if the ByteBuffer was direct.
             */
            buffer.mark();

            Parcel parcel = Parcel.obtain();
            try {
                int maxLength = buffer.remaining();

                byte[] remaining = new byte[maxLength];
                buffer.get(remaining);

                parcel.unmarshall(remaining, /*offset*/0, maxLength);
                parcel.setDataPosition(/*pos*/0);

                T value = mCreator.createFromParcel(parcel);
                int actualLength = parcel.dataPosition();

                if (actualLength == 0) {
                    throw new AssertionError("No data marshaled for " + value);
                }

                // set the position past the bytes the parcelable actually used
                buffer.reset();
                buffer.position(buffer.position() + actualLength);

                if (VERBOSE) {
                    Log.v(TAG, "unmarshal, parcel length was " + actualLength);
                    Log.v(TAG, "unmarshal, value is " + value);
                }

                return mClass.cast(value);
            } finally {
                parcel.recycle();
            }
        }

        @Override
        public int getNativeSize() {
            return NATIVE_SIZE_DYNAMIC;
        }

        @Override
        public int calculateMarshalSize(T value) {
            Parcel parcel = Parcel.obtain();
            try {
                value.writeToParcel(parcel, /*flags*/0);
                int length = parcel.marshall().length;

                if (VERBOSE) {
                    Log.v(TAG, "calculateMarshalSize, length when parceling "
                            + value + " is " + length);
                }

                return length;
            } finally {
                parcel.recycle();
            }
        }
    }

    @Override
    public Marshaler<T> createMarshaler(TypeReference<T> managedType, int nativeType) {
        return new MarshalerParcelable(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<T> managedType, int nativeType) {
        return Parcelable.class.isAssignableFrom(managedType.getRawType());
    }

}
