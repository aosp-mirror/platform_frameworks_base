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
import android.util.Range;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;

/**
 * Marshal {@link Range} to/from any native type
 */
public class MarshalQueryableRange<T extends Comparable<? super T>>
        implements MarshalQueryable<Range<T>> {
    private static final int RANGE_COUNT = 2;

    private class MarshalerRange extends Marshaler<Range<T>> {
        private final Class<? super Range<T>> mClass;
        private final Constructor<Range<T>> mConstructor;
        /** Marshal the {@code T} inside of {@code Range<T>} */
        private final Marshaler<T> mNestedTypeMarshaler;

        @SuppressWarnings("unchecked")
        protected MarshalerRange(TypeReference<Range<T>> typeReference,
                int nativeType) {
            super(MarshalQueryableRange.this, typeReference, nativeType);

            mClass = typeReference.getRawType();

            /*
             * Lookup the actual type argument, e.g. Range<Integer> --> Integer
             * and then get the marshaler for that managed type.
             */
            ParameterizedType paramType;
            try {
                paramType = (ParameterizedType) typeReference.getType();
            } catch (ClassCastException e) {
                throw new AssertionError("Raw use of Range is not supported", e);
            }
            Type actualTypeArgument = paramType.getActualTypeArguments()[0];

            TypeReference<?> actualTypeArgToken =
                    TypeReference.createSpecializedTypeReference(actualTypeArgument);

            mNestedTypeMarshaler = (Marshaler<T>)MarshalRegistry.getMarshaler(
                    actualTypeArgToken, mNativeType);
            try {
                mConstructor = (Constructor<Range<T>>)mClass.getConstructor(
                        Comparable.class, Comparable.class);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void marshal(Range<T> value, ByteBuffer buffer) {
            mNestedTypeMarshaler.marshal(value.getLower(), buffer);
            mNestedTypeMarshaler.marshal(value.getUpper(), buffer);
        }

        @Override
        public Range<T> unmarshal(ByteBuffer buffer) {
            T lower = mNestedTypeMarshaler.unmarshal(buffer);
            T upper = mNestedTypeMarshaler.unmarshal(buffer);

            try {
                return mConstructor.newInstance(lower, upper);
            } catch (InstantiationException e) {
                throw new AssertionError(e);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            } catch (IllegalArgumentException e) {
                throw new AssertionError(e);
            } catch (InvocationTargetException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public int getNativeSize() {
            int nestedSize = mNestedTypeMarshaler.getNativeSize();

            if (nestedSize != NATIVE_SIZE_DYNAMIC) {
                return nestedSize * RANGE_COUNT;
            } else {
                return NATIVE_SIZE_DYNAMIC;
            }
        }

        @Override
        public int calculateMarshalSize(Range<T> value) {
            int nativeSize = getNativeSize();

            if (nativeSize != NATIVE_SIZE_DYNAMIC) {
                return nativeSize;
            } else {
                int lowerSize = mNestedTypeMarshaler.calculateMarshalSize(value.getLower());
                int upperSize = mNestedTypeMarshaler.calculateMarshalSize(value.getUpper());

                return lowerSize + upperSize;
            }
        }
    }

    @Override
    public Marshaler<Range<T>> createMarshaler(TypeReference<Range<T>> managedType,
            int nativeType) {
        return new MarshalerRange(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<Range<T>> managedType, int nativeType) {
        return (Range.class.equals(managedType.getRawType()));
    }

}
