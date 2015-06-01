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
import android.hardware.camera2.marshal.MarshalRegistry;
import android.hardware.camera2.utils.TypeReference;
import android.util.Log;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Marshal any array {@code T}.
 *
 * <p>To marshal any {@code T} to/from a native type, the marshaler for T to/from that native type
 * also has to exist.</p>
 *
 * <p>{@code T} can be either a T2[] where T2 is an object type, or a P[] where P is a
 * built-in primitive (e.g. int[], float[], etc).</p>

 * @param <T> the type of the array (e.g. T = int[], or T = Rational[])
 */
public class MarshalQueryableArray<T> implements MarshalQueryable<T> {

    private static final String TAG = MarshalQueryableArray.class.getSimpleName();
    private static final boolean DEBUG = false;

    private class MarshalerArray extends Marshaler<T> {
        private final Class<T> mClass;
        private final Marshaler<?> mComponentMarshaler;
        private final Class<?> mComponentClass;

        @SuppressWarnings("unchecked")
        protected MarshalerArray(TypeReference<T> typeReference, int nativeType) {
            super(MarshalQueryableArray.this, typeReference, nativeType);

            mClass = (Class<T>)typeReference.getRawType();

            TypeReference<?> componentToken = typeReference.getComponentType();
            mComponentMarshaler = MarshalRegistry.getMarshaler(componentToken, mNativeType);
            mComponentClass = componentToken.getRawType();
        }

        @Override
        public void marshal(T value, ByteBuffer buffer) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; ++i) {
                marshalArrayElement(mComponentMarshaler, buffer, value, i);
            }
        }

        @Override
        public T unmarshal(ByteBuffer buffer) {
            Object array;

            int elementSize = mComponentMarshaler.getNativeSize();

            if (elementSize != Marshaler.NATIVE_SIZE_DYNAMIC) {
                int remaining = buffer.remaining();
                int arraySize = remaining / elementSize;

                if (remaining % elementSize != 0) {
                    throw new UnsupportedOperationException("Arrays for " + mTypeReference
                            + " must be packed tighly into a multiple of " + elementSize
                            + "; but there are " + (remaining % elementSize) + " left over bytes");
                }

                if (DEBUG) {
                    Log.v(TAG, String.format(
                            "Attempting to unpack array (count = %d, element size = %d, bytes "
                            + "remaining = %d) for type %s",
                            arraySize, elementSize, remaining, mClass));
                }

                array = Array.newInstance(mComponentClass, arraySize);
                for (int i = 0; i < arraySize; ++i) {
                    Object elem = mComponentMarshaler.unmarshal(buffer);
                    Array.set(array, i, elem);
                }
            } else {
                // Dynamic size, use an array list.
                ArrayList<Object> arrayList = new ArrayList<Object>();

                // Assumes array is packed tightly; no unused bytes allowed
                while (buffer.hasRemaining()) {
                    Object elem = mComponentMarshaler.unmarshal(buffer);
                    arrayList.add(elem);
                }

                int arraySize = arrayList.size();
                array = copyListToArray(arrayList, Array.newInstance(mComponentClass, arraySize));
            }

            if (buffer.remaining() != 0) {
                Log.e(TAG, "Trailing bytes (" + buffer.remaining() + ") left over after unpacking "
                        + mClass);
            }

            return mClass.cast(array);
        }

        @Override
        public int getNativeSize() {
            return NATIVE_SIZE_DYNAMIC;
        }

        @Override
        public int calculateMarshalSize(T value) {
            int elementSize = mComponentMarshaler.getNativeSize();
            int arrayLength = Array.getLength(value);

            if (elementSize != Marshaler.NATIVE_SIZE_DYNAMIC) {
                // The fast way. Every element size is uniform.
                return elementSize * arrayLength;
            } else {
                // The slow way. Accumulate size for each element.
                int size = 0;
                for (int i = 0; i < arrayLength; ++i) {
                    size += calculateElementMarshalSize(mComponentMarshaler, value, i);
                }

                return size;
            }
        }

        /*
         * Helpers to avoid compiler errors regarding types with wildcards (?)
         */

        @SuppressWarnings("unchecked")
        private <TElem> void marshalArrayElement(Marshaler<TElem> marshaler,
                ByteBuffer buffer, Object array, int index) {
            marshaler.marshal((TElem)Array.get(array, index), buffer);
        }

        @SuppressWarnings("unchecked")
        private Object copyListToArray(ArrayList<?> arrayList, Object arrayDest) {
            return arrayList.toArray((T[]) arrayDest);
        }

        @SuppressWarnings("unchecked")
        private <TElem> int calculateElementMarshalSize(Marshaler<TElem> marshaler,
                Object array, int index) {
            Object elem = Array.get(array, index);

            return marshaler.calculateMarshalSize((TElem) elem);
        }
    }

    @Override
    public Marshaler<T> createMarshaler(TypeReference<T> managedType, int nativeType) {
        return new MarshalerArray(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<T> managedType, int nativeType) {
        // support both ConcreteType[] and GenericType<ConcreteType>[]
        return managedType.getRawType().isArray();

        // TODO: Should this recurse deeper and check that there is
        // a valid marshaler for the ConcreteType as well?
    }
}
