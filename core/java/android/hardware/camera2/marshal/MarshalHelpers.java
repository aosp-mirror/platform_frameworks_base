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
package android.hardware.camera2.marshal;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static com.android.internal.util.Preconditions.*;

import android.hardware.camera2.impl.CameraMetadataNative;
import android.util.Rational;

/**
 * Static functions in order to help implementing various marshaler functionality.
 *
 * <p>The intention is to statically import everything from this file into another file when
 * implementing a new marshaler (or marshal queryable).</p>
 *
 * <p>The helpers are centered around providing primitive knowledge of the native types,
 * such as the native size, the managed class wrappers, and various precondition checks.</p>
 */
public final class MarshalHelpers {

    public static final int SIZEOF_BYTE = 1;
    public static final int SIZEOF_INT32 = Integer.SIZE / Byte.SIZE;
    public static final int SIZEOF_INT64 = Long.SIZE / Byte.SIZE;
    public static final int SIZEOF_FLOAT = Float.SIZE / Byte.SIZE;
    public static final int SIZEOF_DOUBLE = Double.SIZE / Byte.SIZE;
    public static final int SIZEOF_RATIONAL = SIZEOF_INT32 * 2;

    /**
     * Get the size in bytes for the native camera metadata type.
     *
     * <p>This used to determine how many bytes it would take to encode/decode a single value
     * of that {@link nativeType}.</p>
     *
     * @param nativeType the native type, e.g.
     *        {@link android.hardware.camera2.impl.CameraMetadataNative#TYPE_BYTE TYPE_BYTE}.
     * @return size in bytes >= 1
     *
     * @throws UnsupportedOperationException if nativeType was not one of the built-in types
     */
    public static int getPrimitiveTypeSize(int nativeType) {
        switch (nativeType) {
            case TYPE_BYTE:
                return SIZEOF_BYTE;
            case TYPE_INT32:
                return SIZEOF_INT32;
            case TYPE_FLOAT:
                return SIZEOF_FLOAT;
            case TYPE_INT64:
                return SIZEOF_INT64;
            case TYPE_DOUBLE:
                return SIZEOF_DOUBLE;
            case TYPE_RATIONAL:
                return SIZEOF_RATIONAL;
        }

        throw new UnsupportedOperationException("Unknown type, can't get size for "
                + nativeType);
    }


    /**
     * Ensure that the {@code klass} is one of the metadata-primitive classes.
     *
     * @param klass a non-{@code null} reference
     * @return {@code klass} instance
     *
     * @throws UnsupportedOperationException if klass was not one of the built-in classes
     * @throws NullPointerException if klass was null
     *
     * @see #isPrimitiveClass
     */
    public static <T> Class<T> checkPrimitiveClass(Class<T> klass) {
        checkNotNull(klass, "klass must not be null");

        if (isPrimitiveClass(klass)) {
            return klass;
        }

        throw new UnsupportedOperationException("Unsupported class '" + klass +
                "'; expected a metadata primitive class");
    }

    /**
     * Checks whether or not {@code klass} is one of the unboxed primitive classes.
     *
     * <p>The following types (whether boxed or unboxed) are considered primitive:
     * <ul>
     * <li>byte
     * <li>int
     * <li>float
     * <li>double
     * </ul>
     * </p>
     *
     * @param klass a {@link Class} instance; using {@code null} will return {@code false}
     * @return {@code true} if primitive, {@code false} otherwise
     */
    public static boolean isUnwrappedPrimitiveClass(Class<?> klass) {
        if (klass == null) {
            return false;
        }

        if (klass == byte.class) {
            return true;
        } else if (klass == int.class) {
            return true;
        } else if (klass == float.class) {
            return true;
        } else if (klass == long.class) {
            return true;
        } else if (klass == double.class) {
            return true;
        }
        return false;
    }

    /**
     * Checks whether or not {@code klass} is one of the metadata-primitive classes.
     *
     * <p>The following types (whether boxed or unboxed) are considered primitive:
     * <ul>
     * <li>byte
     * <li>int
     * <li>float
     * <li>double
     * <li>Rational
     * </ul>
     * </p>
     *
     * <p>This doesn't strictly follow the java understanding of primitive since
     * boxed objects are included, Rational is included, and other types such as char and
     * short are not included.</p>
     *
     * @param klass a {@link Class} instance; using {@code null} will return {@code false}
     * @return {@code true} if primitive, {@code false} otherwise
     */
    public static <T> boolean isPrimitiveClass(Class<T> klass) {
        if (klass == null) {
            return false;
        }

        if (klass == byte.class || klass == Byte.class) {
            return true;
        } else if (klass == int.class || klass == Integer.class) {
            return true;
        } else if (klass == float.class || klass == Float.class) {
            return true;
        } else if (klass == long.class || klass == Long.class) {
            return true;
        } else if (klass == double.class || klass == Double.class) {
            return true;
        } else if (klass == Rational.class) {
            return true;
        }

        return false;
    }

    /**
     * Wrap {@code klass} with its wrapper variant if it was a {@code Class} corresponding
     * to a Java primitive.
     *
     * <p>Non-primitive classes are passed through as-is.</p>
     *
     * <p>For example, for a primitive {@code int.class => Integer.class},
     * but for a non-primitive {@code Rational.class => Rational.class}.</p>
     *
     * @param klass a {@code Class} reference
     *
     * @return wrapped class object, or same class object if non-primitive
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<T> wrapClassIfPrimitive(Class<T> klass) {
        if (klass == byte.class) {
            return (Class<T>)Byte.class;
        } else if (klass == int.class) {
            return (Class<T>)Integer.class;
        } else if (klass == float.class) {
            return (Class<T>)Float.class;
        } else if (klass == long.class) {
            return (Class<T>)Long.class;
        } else if (klass == double.class) {
            return (Class<T>)Double.class;
        }

        return klass;
    }

    /**
     * Return a human-readable representation of the {@code nativeType}, e.g. "TYPE_INT32"
     *
     * <p>Out-of-range values return a string with "UNKNOWN" as the prefix.</p>
     *
     * @param nativeType the native type
     *
     * @return human readable type name
     */
    public static String toStringNativeType(int nativeType) {
        switch (nativeType) {
            case TYPE_BYTE:
                return "TYPE_BYTE";
            case TYPE_INT32:
                return "TYPE_INT32";
            case TYPE_FLOAT:
                return "TYPE_FLOAT";
            case TYPE_INT64:
                return "TYPE_INT64";
            case TYPE_DOUBLE:
                return "TYPE_DOUBLE";
            case TYPE_RATIONAL:
                return "TYPE_RATIONAL";
        }

        return "UNKNOWN(" + nativeType + ")";
    }

    /**
     * Ensure that the {@code nativeType} is one of the native types supported
     * by {@link CameraMetadataNative}.
     *
     * @param nativeType the native type
     *
     * @return the native type
     *
     * @throws UnsupportedOperationException if the native type was invalid
     */
    public static int checkNativeType(int nativeType) {
        switch (nativeType) {
            case TYPE_BYTE:
            case TYPE_INT32:
            case TYPE_FLOAT:
            case TYPE_INT64:
            case TYPE_DOUBLE:
            case TYPE_RATIONAL:
                return nativeType;
        }

        throw new UnsupportedOperationException("Unknown nativeType " + nativeType);
    }

    /**
     * Get the unboxed primitive type corresponding to nativeType
     *
     * @param nativeType the native type (RATIONAL not included)
     *
     * @return the native type class
     *
     * @throws UnsupportedOperationException if the native type was invalid
     */
    public static Class<?> getPrimitiveTypeClass(int nativeType) {
        switch (nativeType) {
            case TYPE_BYTE:
                return byte.class;
            case TYPE_INT32:
                return int.class;
            case TYPE_FLOAT:
                return float.class;
            case TYPE_INT64:
                return long.class;
            case TYPE_DOUBLE:
                return double.class;
        }

        throw new UnsupportedOperationException("Unknown nativeType " + nativeType);
    }

    /**
     * Ensure that the expected and actual native types are equal.
     *
     * @param expectedNativeType the expected native type
     * @param actualNativeType the actual native type
     * @return the actual native type
     *
     * @throws UnsupportedOperationException if the types are not equal
     */
    public static int checkNativeTypeEquals(int expectedNativeType, int actualNativeType) {
        if (expectedNativeType != actualNativeType) {
            throw new UnsupportedOperationException(
                    String.format("Expected native type %d, but got %d",
                            expectedNativeType, actualNativeType));
        }

        return actualNativeType;
    }

    private MarshalHelpers() {
        throw new AssertionError();
    }
}
