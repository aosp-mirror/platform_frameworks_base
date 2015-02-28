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

import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.marshal.Marshaler;
import android.hardware.camera2.marshal.MarshalQueryable;
import android.hardware.camera2.utils.TypeReference;
import android.util.Rational;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;
import java.nio.ByteBuffer;

/**
 * Marshal/unmarshal built-in primitive types to and from a {@link ByteBuffer}.
 *
 * <p>The following list of type marshaling is supported:
 * <ul>
 * <li>byte <-> TYPE_BYTE
 * <li>int <-> TYPE_INT32
 * <li>long <-> TYPE_INT64
 * <li>float <-> TYPE_FLOAT
 * <li>double <-> TYPE_DOUBLE
 * <li>Rational <-> TYPE_RATIONAL
 * </ul>
 * </p>
 *
 * <p>Due to the nature of generics, values are always boxed; this also means that both
 * the boxed and unboxed types are supported (i.e. both {@code int} and {@code Integer}).</p>
 *
 * <p>Each managed type <!--(other than boolean)--> must correspond 1:1 to the native type
 * (e.g. a byte will not map to a {@link CameraMetadataNative#TYPE_INT32 TYPE_INT32} or vice versa)
 * for marshaling.</p>
 */
public final class MarshalQueryablePrimitive<T> implements MarshalQueryable<T> {

    private class MarshalerPrimitive extends Marshaler<T> {
        /** Always the wrapped class variant of the primitive class for {@code T} */
        private final Class<T> mClass;

        @SuppressWarnings("unchecked")
        protected MarshalerPrimitive(TypeReference<T> typeReference, int nativeType) {
            super(MarshalQueryablePrimitive.this, typeReference, nativeType);

            // Turn primitives into wrappers, otherwise int.class.cast(Integer) will fail
            mClass = wrapClassIfPrimitive((Class<T>)typeReference.getRawType());
        }

        @Override
        public T unmarshal(ByteBuffer buffer) {
            return mClass.cast(unmarshalObject(buffer));
        }

        @Override
        public int calculateMarshalSize(T value) {
            return getPrimitiveTypeSize(mNativeType);
        }

        @Override
        public void marshal(T value, ByteBuffer buffer) {
            if (value instanceof Integer) {
                checkNativeTypeEquals(TYPE_INT32, mNativeType);
                final int val = (Integer) value;
                marshalPrimitive(val, buffer);
            } else if (value instanceof Float) {
                checkNativeTypeEquals(TYPE_FLOAT, mNativeType);
                final float val = (Float) value;
                marshalPrimitive(val, buffer);
            } else if (value instanceof Long) {
                checkNativeTypeEquals(TYPE_INT64, mNativeType);
                final long val = (Long) value;
                marshalPrimitive(val, buffer);
            } else if (value instanceof Rational) {
                checkNativeTypeEquals(TYPE_RATIONAL, mNativeType);
                marshalPrimitive((Rational) value, buffer);
            } else if (value instanceof Double) {
                checkNativeTypeEquals(TYPE_DOUBLE, mNativeType);
                final double val = (Double) value;
                marshalPrimitive(val, buffer);
            } else if (value instanceof Byte) {
                checkNativeTypeEquals(TYPE_BYTE, mNativeType);
                final byte val = (Byte) value;
                marshalPrimitive(val, buffer);
            } else {
                throw new UnsupportedOperationException(
                        "Can't marshal managed type " + mTypeReference);
            }
        }

        private void marshalPrimitive(int value, ByteBuffer buffer) {
            buffer.putInt(value);
        }

        private void marshalPrimitive(float value, ByteBuffer buffer) {
            buffer.putFloat(value);
        }

        private void marshalPrimitive(double value, ByteBuffer buffer) {
            buffer.putDouble(value);
        }

        private void marshalPrimitive(long value, ByteBuffer buffer) {
            buffer.putLong(value);
        }

        private void marshalPrimitive(Rational value, ByteBuffer buffer) {
            buffer.putInt(value.getNumerator());
            buffer.putInt(value.getDenominator());
        }

        private void marshalPrimitive(byte value, ByteBuffer buffer) {
            buffer.put(value);
        }

        private Object unmarshalObject(ByteBuffer buffer) {
            switch (mNativeType) {
                case TYPE_INT32:
                    return buffer.getInt();
                case TYPE_FLOAT:
                    return buffer.getFloat();
                case TYPE_INT64:
                    return buffer.getLong();
                case TYPE_RATIONAL:
                    int numerator = buffer.getInt();
                    int denominator = buffer.getInt();
                    return new Rational(numerator, denominator);
                case TYPE_DOUBLE:
                    return buffer.getDouble();
                case TYPE_BYTE:
                    return buffer.get(); // getByte
                default:
                    throw new UnsupportedOperationException(
                            "Can't unmarshal native type " + mNativeType);
            }
        }

        @Override
        public int getNativeSize() {
            return getPrimitiveTypeSize(mNativeType);
        }
    }

    @Override
    public Marshaler<T> createMarshaler(TypeReference<T> managedType, int nativeType) {
        return new MarshalerPrimitive(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<T> managedType, int nativeType) {
        if (managedType.getType() instanceof Class<?>) {
            Class<?> klass = (Class<?>)managedType.getType();

            if (klass == byte.class || klass == Byte.class) {
                return nativeType == TYPE_BYTE;
            } else if (klass == int.class || klass == Integer.class) {
                return nativeType == TYPE_INT32;
            } else if (klass == float.class || klass == Float.class) {
                return nativeType == TYPE_FLOAT;
            } else if (klass == long.class || klass == Long.class) {
                return nativeType == TYPE_INT64;
            } else if (klass == double.class || klass == Double.class) {
                return nativeType == TYPE_DOUBLE;
            } else if (klass == Rational.class) {
                return nativeType == TYPE_RATIONAL;
            }
        }
        return false;
    }
}
