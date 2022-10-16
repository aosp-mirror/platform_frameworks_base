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
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.HashMap;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

/**
 * Marshal any simple enum (0-arg constructors only) into/from either
 * {@code TYPE_BYTE} or {@code TYPE_INT32}.
 *
 * <p>Default values of the enum are mapped to its ordinal; this can be overridden
 * by providing a manual value with {@link #registerEnumValues}.</p>

 * @param <T> the type of {@code Enum}
 */
public class MarshalQueryableEnum<T extends Enum<T>> implements MarshalQueryable<T> {

    private static final String TAG = MarshalQueryableEnum.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int UINT8_MIN = 0x0;
    private static final int UINT8_MAX = (1 << Byte.SIZE) - 1;
    private static final int UINT8_MASK = UINT8_MAX;

    private class MarshalerEnum extends Marshaler<T> {

        private final Class<T> mClass;

        @SuppressWarnings("unchecked")
        protected MarshalerEnum(TypeReference<T> typeReference, int nativeType) {
            super(MarshalQueryableEnum.this, typeReference, nativeType);

            mClass = (Class<T>)typeReference.getRawType();
        }

        @Override
        public void marshal(T value, ByteBuffer buffer) {
            int enumValue = getEnumValue(value);

            if (mNativeType == TYPE_INT32) {
                buffer.putInt(enumValue);
            } else if (mNativeType == TYPE_BYTE) {
                if (enumValue < UINT8_MIN || enumValue > UINT8_MAX) {
                    throw new UnsupportedOperationException(String.format(
                            "Enum value %x too large to fit into unsigned byte", enumValue));
                }
                buffer.put((byte)enumValue);
            } else {
                throw new AssertionError();
            }
        }

        @Override
        public T unmarshal(ByteBuffer buffer) {
            int enumValue;

            switch (mNativeType) {
                case TYPE_INT32:
                    enumValue = buffer.getInt();
                    break;
                case TYPE_BYTE:
                    // get the unsigned byte value; avoid sign extension
                    enumValue = buffer.get() & UINT8_MASK;
                    break;
                default:
                    throw new AssertionError(
                            "Unexpected native type; impossible since its not supported");
            }

            return getEnumFromValue(mClass, enumValue);
        }

        @Override
        public int getNativeSize() {
            return getPrimitiveTypeSize(mNativeType);
        }
    }

    @Override
    public Marshaler<T> createMarshaler(TypeReference<T> managedType, int nativeType) {
        return new MarshalerEnum(managedType, nativeType);
    }

    @SuppressWarnings("ReturnValueIgnored")
    @Override
    public boolean isTypeMappingSupported(TypeReference<T> managedType, int nativeType) {
        if (nativeType == TYPE_INT32 || nativeType == TYPE_BYTE) {
            if (managedType.getType() instanceof Class<?>) {
                Class<?> typeClass = (Class<?>)managedType.getType();

                if (typeClass.isEnum()) {
                    if (DEBUG) {
                        Log.v(TAG, "possible enum detected for " + typeClass);
                    }

                    // The enum must not take extra arguments
                    try {
                        // match a class like: "public enum Fruits { Apple, Orange; }"
                        typeClass.getDeclaredConstructor(String.class, int.class);
                        return true;
                    } catch (NoSuchMethodException e) {
                        // Skip: custom enum with a special constructor e.g. Foo(T), but need Foo()
                        Log.e(TAG, "Can't marshal class " + typeClass + "; no default constructor");
                    } catch (SecurityException e) {
                        // Skip: wouldn't be able to touch the enum anyway
                        Log.e(TAG, "Can't marshal class " + typeClass + "; not accessible");
                    }
                }
            }
        }

        return false;
    }

    @SuppressWarnings("rawtypes")
    private static final HashMap<Class<? extends Enum>, int[]> sEnumValues =
            new HashMap<Class<? extends Enum>, int[]>();

    /**
     * Register a non-sequential set of values to be used with the marshal/unmarshal functions.
     *
     * <p>This enables get/set to correctly marshal the enum into a value that is C-compatible.</p>
     *
     * @param enumType The class for an enum
     * @param values A list of values mapping to the ordinals of the enum
     */
    public static <T extends Enum<T>> void registerEnumValues(Class<T> enumType, int[] values) {
        if (enumType.getEnumConstants().length != values.length) {
            throw new IllegalArgumentException(
                    "Expected values array to be the same size as the enumTypes values "
                            + values.length + " for type " + enumType);
        }
        if (DEBUG) {
            Log.v(TAG, "Registered enum values for type " + enumType + " values");
        }

        sEnumValues.put(enumType, values);
    }

    /**
     * Get the numeric value from an enum.
     *
     * <p>This is usually the same as the ordinal value for
     * enums that have fully sequential values, although for C-style enums the range of values
     * may not map 1:1.</p>
     *
     * @param enumValue Enum instance
     * @return Int guaranteed to be ABI-compatible with the C enum equivalent
     */
    private static <T extends Enum<T>> int getEnumValue(T enumValue) {
        int[] values;
        values = sEnumValues.get(enumValue.getClass());

        int ordinal = enumValue.ordinal();
        if (values != null) {
            return values[ordinal];
        }

        return ordinal;
    }

    /**
     * Finds the enum corresponding to it's numeric value. Opposite of {@link #getEnumValue} method.
     *
     * @param enumType Class of the enum we want to find
     * @param value The numeric value of the enum
     * @return An instance of the enum
     */
    private static <T extends Enum<T>> T getEnumFromValue(Class<T> enumType, int value) {
        int ordinal;

        int[] registeredValues = sEnumValues.get(enumType);
        if (registeredValues != null) {
            ordinal = -1;

            for (int i = 0; i < registeredValues.length; ++i) {
                if (registeredValues[i] == value) {
                    ordinal = i;
                    break;
                }
            }
        } else {
            ordinal = value;
        }

        T[] values = enumType.getEnumConstants();

        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException(
                    String.format(
                            "Argument 'value' (%d) was not a valid enum value for type %s "
                                    + "(registered? %b)",
                            value,
                            enumType, (registeredValues != null)));
        }

        return values[ordinal];
    }
}
