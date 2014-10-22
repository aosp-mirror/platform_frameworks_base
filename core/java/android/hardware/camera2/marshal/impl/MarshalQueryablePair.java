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
import android.util.Pair;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;

/**
 * Marshal {@link Pair} to/from any native type
 */
public class MarshalQueryablePair<T1, T2>
        implements MarshalQueryable<Pair<T1, T2>> {

    private class MarshalerPair extends Marshaler<Pair<T1, T2>> {
        private final Class<? super Pair<T1, T2>> mClass;
        private final Constructor<Pair<T1, T2>> mConstructor;
        /** Marshal the {@code T1} inside of {@code Pair<T1, T2>} */
        private final Marshaler<T1> mNestedTypeMarshalerFirst;
        /** Marshal the {@code T1} inside of {@code Pair<T1, T2>} */
        private final Marshaler<T2> mNestedTypeMarshalerSecond;

        @SuppressWarnings("unchecked")
        protected MarshalerPair(TypeReference<Pair<T1, T2>> typeReference,
                int nativeType) {
            super(MarshalQueryablePair.this, typeReference, nativeType);

            mClass = typeReference.getRawType();

            /*
             * Lookup the actual type arguments, e.g. Pair<Integer, Float> --> [Integer, Float]
             * and then get the marshalers for that managed type.
             */
            ParameterizedType paramType;
            try {
                paramType = (ParameterizedType) typeReference.getType();
            } catch (ClassCastException e) {
                throw new AssertionError("Raw use of Pair is not supported", e);
            }

            // Get type marshaler for T1
            {
                Type actualTypeArgument = paramType.getActualTypeArguments()[0];

                TypeReference<?> actualTypeArgToken =
                        TypeReference.createSpecializedTypeReference(actualTypeArgument);

                mNestedTypeMarshalerFirst = (Marshaler<T1>)MarshalRegistry.getMarshaler(
                        actualTypeArgToken, mNativeType);
            }
            // Get type marshaler for T2
            {
                Type actualTypeArgument = paramType.getActualTypeArguments()[1];

                TypeReference<?> actualTypeArgToken =
                        TypeReference.createSpecializedTypeReference(actualTypeArgument);

                mNestedTypeMarshalerSecond = (Marshaler<T2>)MarshalRegistry.getMarshaler(
                        actualTypeArgToken, mNativeType);
            }
            try {
                mConstructor = (Constructor<Pair<T1, T2>>)mClass.getConstructor(
                        Object.class, Object.class);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void marshal(Pair<T1, T2> value, ByteBuffer buffer) {
            if (value.first == null) {
                throw new UnsupportedOperationException("Pair#first must not be null");
            } else if (value.second == null) {
                throw new UnsupportedOperationException("Pair#second must not be null");
            }

            mNestedTypeMarshalerFirst.marshal(value.first, buffer);
            mNestedTypeMarshalerSecond.marshal(value.second, buffer);
        }

        @Override
        public Pair<T1, T2> unmarshal(ByteBuffer buffer) {
            T1 first = mNestedTypeMarshalerFirst.unmarshal(buffer);
            T2 second = mNestedTypeMarshalerSecond.unmarshal(buffer);

            try {
                return mConstructor.newInstance(first, second);
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
            int firstSize = mNestedTypeMarshalerFirst.getNativeSize();
            int secondSize = mNestedTypeMarshalerSecond.getNativeSize();

            if (firstSize != NATIVE_SIZE_DYNAMIC && secondSize != NATIVE_SIZE_DYNAMIC) {
                return firstSize + secondSize;
            } else {
                return NATIVE_SIZE_DYNAMIC;
            }
        }

        @Override
        public int calculateMarshalSize(Pair<T1, T2> value) {
            int nativeSize = getNativeSize();

            if (nativeSize != NATIVE_SIZE_DYNAMIC) {
                return nativeSize;
            } else {
                int firstSize = mNestedTypeMarshalerFirst.calculateMarshalSize(value.first);
                int secondSize = mNestedTypeMarshalerSecond.calculateMarshalSize(value.second);

                return firstSize + secondSize;
            }
        }
    }

    @Override
    public Marshaler<Pair<T1, T2>> createMarshaler(TypeReference<Pair<T1, T2>> managedType,
            int nativeType) {
        return new MarshalerPair(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<Pair<T1, T2>> managedType, int nativeType) {
        return (Pair.class.equals(managedType.getRawType()));
    }

}
