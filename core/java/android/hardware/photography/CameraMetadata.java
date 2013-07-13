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

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
        int tag = key.getTag();

        int nativeType = getNativeType(tag);

        int size = packSingle(value, null, key.mType, nativeType, /* sizeOnly */true);

        // TODO: Optimization. Cache the byte[] and reuse if the size is big enough.
        byte[] values = new byte[size];

        ByteBuffer buffer = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder());
        packSingle(value, buffer, key.mType, nativeType, /*sizeOnly*/false);

        writeValues(tag, values);
    }

    /**
     * Get a camera metadata field value. The field definitions can be
     * found in {@link CameraProperties}, {@link CaptureResult}, and
     * {@link CaptureRequest}.
     *
     * @throws IllegalArgumentException if the key was not valid
     *
     * @param key the metadata field to read.
     * @return the value of that key, or {@code null} if the field is not set.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        int tag = key.getTag();
        byte[] values = readValues(tag);
        if (values == null) {
            return null;
        }

        int nativeType = getNativeType(tag);

        ByteBuffer buffer = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder());
        return unpackSingle(buffer, key.mType, nativeType);
    }

    // Keep up-to-date with camera_metadata.h
    /**
     * @hide
     */
    public static final int TYPE_BYTE = 0;
    /**
     * @hide
     */
    public static final int TYPE_INT32 = 1;
    /**
     * @hide
     */
    public static final int TYPE_FLOAT = 2;
    /**
     * @hide
     */
    public static final int TYPE_INT64 = 3;
    /**
     * @hide
     */
    public static final int TYPE_DOUBLE = 4;
    /**
     * @hide
     */
    public static final int TYPE_RATIONAL = 5;
    /**
     * @hide
     */
    public static final int NUM_TYPES = 6;

    private static int getTypeSize(int nativeType) {
        switch(nativeType) {
            case TYPE_BYTE:
                return 1;
            case TYPE_INT32:
            case TYPE_FLOAT:
                return 4;
            case TYPE_INT64:
            case TYPE_DOUBLE:
            case TYPE_RATIONAL:
                return 8;
        }

        throw new UnsupportedOperationException("Unknown type, can't get size "
                + nativeType);
    }

    private static Class<?> getExpectedType(int nativeType) {
        switch(nativeType) {
            case TYPE_BYTE:
                return Byte.TYPE;
            case TYPE_INT32:
                return Integer.TYPE;
            case TYPE_FLOAT:
                return Float.TYPE;
            case TYPE_INT64:
                return Long.TYPE;
            case TYPE_DOUBLE:
                return Double.TYPE;
            case TYPE_RATIONAL:
                return Rational.class;
        }

        throw new UnsupportedOperationException("Unknown type, can't map to Java type "
                + nativeType);
    }

    @SuppressWarnings("unchecked")
    private static <T> int packSingleNative(T value, ByteBuffer buffer, Class<T> type,
            int nativeType, boolean sizeOnly) {

        if (!sizeOnly) {
            /**
             * Rewrite types when the native type doesn't match the managed type
             *  - Boolean -> Byte
             *  - Integer -> Byte
             */

            if (nativeType == TYPE_BYTE && type == Boolean.TYPE) {
                // Since a boolean can't be cast to byte, and we don't want to use putBoolean
                boolean asBool = (Boolean) value;
                byte asByte = (byte) (asBool ? 1 : 0);
                value = (T) (Byte) asByte;
            } else if (nativeType == TYPE_BYTE && type == Integer.TYPE) {
                int asInt = (Integer) value;
                byte asByte = (byte) asInt;
                value = (T) (Byte) asByte;
            } else if (type != getExpectedType(nativeType)) {
                throw new UnsupportedOperationException("Tried to pack a type of " + type +
                        " but we expected the type to be " + getExpectedType(nativeType));
            }

            if (nativeType == TYPE_BYTE) {
                buffer.put((Byte) value);
            } else if (nativeType == TYPE_INT32) {
                buffer.putInt((Integer) value);
            } else if (nativeType == TYPE_FLOAT) {
                buffer.putFloat((Float) value);
            } else if (nativeType == TYPE_INT64) {
                buffer.putLong((Long) value);
            } else if (nativeType == TYPE_DOUBLE) {
                buffer.putDouble((Double) value);
            } else if (nativeType == TYPE_RATIONAL) {
                Rational r = (Rational) value;
                buffer.putInt(r.getNumerator());
                buffer.putInt(r.getDenominator());
            }

        }

        return getTypeSize(nativeType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> int packSingle(T value, ByteBuffer buffer, Class<T> type, int nativeType,
            boolean sizeOnly) {

        int size = 0;

        if (type.isPrimitive() || type == Rational.class) {
            size = packSingleNative(value, buffer, type, nativeType, sizeOnly);
        } else if (type.isEnum()) {
            size = packEnum((Enum)value, buffer, (Class<Enum>)type, nativeType, sizeOnly);
        } else if (type.isArray()) {
            size = packArray(value, buffer, type, nativeType, sizeOnly);
        } else {
            size = packClass(value, buffer, type, nativeType, sizeOnly);
        }

        return size;
    }

    private static <T extends Enum<T>> int packEnum(T value, ByteBuffer buffer, Class<T> type,
            int nativeType, boolean sizeOnly) {

        // TODO: add support for enums with their own values.
        return packSingleNative(getEnumValue(value), buffer, Integer.TYPE, nativeType, sizeOnly);
    }

    @SuppressWarnings("unchecked")
    private static <T> int packClass(T value, ByteBuffer buffer, Class<T> type, int nativeType,
            boolean sizeOnly) {

        /**
         * FIXME: This doesn't actually work because getFields() returns fields in an unordered
         * manner. Although we could sort and get the data to come out correctly on the *java* side,
         * it would not be data-compatible with our strict XML definitions.
         *
         * Rewrite this code to use Parcelables instead, they are most likely compatible with
         * what we are trying to do in general.
         */
        List<Field> instanceFields = findInstanceFields(type);
        if (instanceFields.size() == 0) {
            throw new UnsupportedOperationException("Class has no instance fields: " + type);
        }

        int fieldCount = instanceFields.size();
        int bufferSize = 0;

        HashSet<Class<?>> fieldTypes = new HashSet<Class<?>>();
        for (Field f : instanceFields) {
            fieldTypes.add(f.getType());
        }

        /**
         * Pack arguments one field at a time. If we can't access field, look for its accessor
         * method instead.
         */
        for (int i = 0; i < fieldCount; ++i) {
            Object arg;

            Field f = instanceFields.get(i);

            if ((f.getModifiers() & Modifier.PUBLIC) != 0) {
                try {
                    arg = f.get(value);
                } catch (IllegalAccessException e) {
                    throw new UnsupportedOperationException(
                            "Failed to access field " + f + " of type " + type, e);
                } catch (IllegalArgumentException e) {
                    throw new UnsupportedOperationException(
                            "Illegal arguments when accessing field " + f + " of type " + type, e);
                }
            } else {
                Method accessor = null;
                // try to find a public accessor method
                for(Method m : type.getMethods()) {
                    Log.v(TAG, String.format("Looking for getter in method %s for field %s", m, f));

                    // Must have 0 arguments
                    if (m.getParameterTypes().length != 0) {
                        continue;
                    }

                    // Return type must be same as field type
                    if (m.getReturnType() != f.getType()) {
                        continue;
                    }

                    // Strip 'm' from variable prefix if the next letter is capitalized
                    String fieldName = f.getName();
                    char[] nameChars = f.getName().toCharArray();
                    if (nameChars.length >= 2 && nameChars[0] == 'm'
                            && Character.isUpperCase(nameChars[1])) {
                        fieldName = String.valueOf(nameChars, /*start*/1, nameChars.length - 1);
                    }

                    Log.v(TAG, String.format("Normalized field name: %s", fieldName));

                    // #getFoo() , getfoo(), foo(), all match.
                    if (m.getName().toLowerCase().equals(fieldName.toLowerCase()) ||
                            m.getName().toLowerCase().equals("get" + fieldName.toLowerCase())) {
                        accessor = m;
                        break;
                    }
                }

                if (accessor == null) {
                    throw new UnsupportedOperationException(
                            "Failed to find getter method for field " + f + " in type " + type);
                }

                try {
                    arg = accessor.invoke(value);
                } catch (IllegalAccessException e) {
                    // Impossible
                    throw new UnsupportedOperationException("Failed to access method + " + accessor
                            + " in type " + type, e);
                } catch (IllegalArgumentException e) {
                    // Impossible
                    throw new UnsupportedOperationException("Bad arguments for method + " + accessor
                            + " in type " + type, e);
                } catch (InvocationTargetException e) {
                    // Possibly but extremely unlikely
                    throw new UnsupportedOperationException("Failed to invoke method + " + accessor
                            + " in type " + type, e);
                }
            }

            bufferSize += packSingle(arg, buffer, (Class<Object>)f.getType(), nativeType, sizeOnly);
        }

        return bufferSize;
    }

    private static <T> int packArray(T value, ByteBuffer buffer, Class<T> type, int nativeType,
            boolean sizeOnly) {

        int size = 0;
        int arrayLength = Array.getLength(value);

        @SuppressWarnings("unchecked")
        Class<Object> componentType = (Class<Object>)type.getComponentType();

        for (int i = 0; i < arrayLength; ++i) {
            size += packSingle(Array.get(value, i), buffer, componentType, nativeType, sizeOnly);
        }

        return size;
    }

    @SuppressWarnings("unchecked")
    private static <T> T unpackSingleNative(ByteBuffer buffer, Class<T> type, int nativeType) {

        T val;

        if (nativeType == TYPE_BYTE) {
            val = (T) (Byte) buffer.get();
        } else if (nativeType == TYPE_INT32) {
            val = (T) (Integer) buffer.getInt();
        } else if (nativeType == TYPE_FLOAT) {
            val = (T) (Float) buffer.getFloat();
        } else if (nativeType == TYPE_INT64) {
            val = (T) (Long) buffer.getLong();
        } else if (nativeType == TYPE_DOUBLE) {
            val = (T) (Double) buffer.getDouble();
        } else if (nativeType == TYPE_RATIONAL) {
            val = (T) new Rational(buffer.getInt(), buffer.getInt());
        } else {
            throw new UnsupportedOperationException("Unknown type, can't unpack a native type "
                + nativeType);
        }

        /**
         * Rewrite types when the native type doesn't match the managed type
         *  - Byte -> Boolean
         *  - Byte -> Integer
         */

        if (nativeType == TYPE_BYTE && type == Boolean.TYPE) {
            // Since a boolean can't be cast to byte, and we don't want to use getBoolean
            byte asByte = (Byte) val;
            boolean asBool = asByte != 0;
            val = (T) (Boolean) asBool;
        } else if (nativeType == TYPE_BYTE && type == Integer.TYPE) {
            byte asByte = (Byte) val;
            int asInt = asByte;
            val = (T) (Integer) asInt;
        } else if (type != getExpectedType(nativeType)) {
            throw new UnsupportedOperationException("Tried to unpack a type of " + type +
                    " but we expected the type to be " + getExpectedType(nativeType));
        }

        return val;
    }

    private static <T> List<Field> findInstanceFields(Class<T> type) {
        List<Field> fields = new ArrayList<Field>();

        for (Field f : type.getDeclaredFields()) {
            if (f.isSynthetic()) {
                throw new UnsupportedOperationException(
                        "Marshalling synthetic fields not supported in type " + type);
            }

            // Skip static fields
            int modifiers = f.getModifiers();
            if ((modifiers & Modifier.STATIC) == 0) {
                fields.add(f);
            }

            Log.v(TAG, String.format("Field %s has modifiers %d", f, modifiers));
        }

        if (type.getDeclaredFields().length == 0) {
            Log.w(TAG, String.format("Type %s had 0 fields of any kind", type));
        }
        return fields;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T unpackSingle(ByteBuffer buffer, Class<T> type, int nativeType) {

        if (type.isPrimitive() || type == Rational.class) {
            return unpackSingleNative(buffer, type, nativeType);
        }

        if (type.isEnum()) {
            return (T) unpackEnum(buffer, (Class<Enum>)type, nativeType);
        }

        if (type.isArray()) {
            return unpackArray(buffer, type, nativeType);
        }

        T instance = unpackClass(buffer, type, nativeType);

        return instance;
    }

    private static <T> Constructor<T> findApplicableConstructor(Class<T> type) {

        List<Field> instanceFields = findInstanceFields(type);
        if (instanceFields.size() == 0) {
            throw new UnsupportedOperationException("Class has no instance fields: " + type);
        }

        Constructor<T> constructor = null;
        int fieldCount = instanceFields.size();

        HashSet<Class<?>> fieldTypes = new HashSet<Class<?>>();
        for (Field f : instanceFields) {
            fieldTypes.add(f.getType());
        }

        /**
         * Find which constructor to use:
         * - must be public
         * - same amount of arguments as there are instance fields
         * - each argument is same type as each field (in any order)
         */
        @SuppressWarnings("unchecked")
        Constructor<T>[] constructors = (Constructor<T>[]) type.getConstructors();
        for (Constructor<T> ctor : constructors) {
            Log.v(TAG, String.format("Inspecting constructor '%s'", ctor));

            Class<?>[] parameterTypes = ctor.getParameterTypes();
            if (parameterTypes.length == fieldCount) {
                boolean match = true;

                HashSet<Class<?>> argTypes = new HashSet<Class<?>>();
                for (Class<?> t : parameterTypes) {
                    argTypes.add(t);
                }

                // Order does not matter
                match = argTypes.equals(fieldTypes);

                /*
                // check if the types are the same
                for (int i = 0; i < fieldCount; ++i) {
                    if (parameterTypes[i] != instanceFields.get(i).getType()) {

                        Log.v(TAG, String.format(
                                "Constructor arg (%d) type %s did not match field type %s", i,
                                parameterTypes[i], instanceFields.get(i).getType()));

                        match = false;
                        break;
                    }
                }
                */

                if (match) {
                    constructor = ctor;
                    break;
                } else {
                    Log.w(TAG, String.format("Constructor args did not have matching types"));
                }
            } else {
                Log.v(TAG, String.format(
                        "Constructor did not have expected amount of fields (had %d, expected %d)",
                        parameterTypes.length, fieldCount));
            }
        }

        if (constructors.length == 0) {
            Log.w(TAG, String.format("Type %s had no public constructors", type));
        }

        if (constructor == null) {
            throw new UnsupportedOperationException(
                    "Failed to find any applicable constructors for type " + type);
        }

        return constructor;
    }

    private static <T extends Enum<T>> T unpackEnum(ByteBuffer buffer, Class<T> type,
            int nativeType) {
        int ordinal = unpackSingleNative(buffer, Integer.TYPE, nativeType);
        return getEnumFromValue(type, ordinal);
    }

    private static <T> T unpackClass(ByteBuffer buffer, Class<T> type, int nativeType) {

        /**
         * FIXME: This doesn't actually work because getFields() returns fields in an unordered
         * manner. Although we could sort and get the data to come out correctly on the *java* side,
         * it would not be data-compatible with our strict XML definitions.
         *
         * Rewrite this code to use Parcelables instead, they are most likely compatible with
         * what we are trying to do in general.
         */

        List<Field> instanceFields = findInstanceFields(type);
        if (instanceFields.size() == 0) {
            throw new UnsupportedOperationException("Class has no instance fields: " + type);
        }
        int fieldCount = instanceFields.size();

        Constructor<T> constructor = findApplicableConstructor(type);

        /**
         * Build the arguments by unpacking one field at a time
         * (note that while the field type might be different, the native type is the same)
         */
        Object[] arguments = new Object[fieldCount];
        for (int i = 0; i < fieldCount; ++i) {
            Object o = unpackSingle(buffer, instanceFields.get(i).getType(), nativeType);
            arguments[i] = o;
        }

        T instance;
        try {
            instance = constructor.newInstance(arguments);
        } catch (InstantiationException e) {
            // type is abstract class, interface, etc...
            throw new UnsupportedOperationException("Failed to instantiate type " + type, e);
        } catch (IllegalAccessException e) {
            // This could happen if we have to access a private.
            throw new UnsupportedOperationException("Failed to access type " + type, e);
        } catch (IllegalArgumentException e) {
            throw new UnsupportedOperationException("Illegal arguments for constructor of type "
                    + type, e);
        } catch (InvocationTargetException e) {
            throw new UnsupportedOperationException(
                    "Underlying constructor threw exception for type " + type, e);
        }

        return instance;
    }

    @SuppressWarnings("unchecked")
    private static <T> T unpackArray(ByteBuffer buffer, Class<T> type, int nativeType) {

        Class<?> componentType = type.getComponentType();
        Object array;

        int remaining = buffer.remaining();
        // FIXME: Assumes that the rest of the ByteBuffer is part of the array.
        int arraySize = remaining / getTypeSize(nativeType);

        array = Array.newInstance(componentType, arraySize);
        for (int i = 0; i < arraySize; ++i) {
           Object elem = unpackSingle(buffer, componentType, nativeType);
           Array.set(array, i, elem);
        }

        return (T) array;
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

        private boolean mHasTag;
        private int mTag;
        private final Class<T> mType;

        /*
         * @hide
         */
        public Key(String name, Class<T> type) {
            if (name == null) {
                throw new NullPointerException("Key needs a valid name");
            } else if (type == null) {
                throw new NullPointerException("Type needs to be non-null");
            }
            mName = name;
            mType = type;
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

        /**
         * <p>
         * Get the tag corresponding to this key. This enables insertion into the
         * native metadata.
         * </p>
         *
         * <p>This value is looked up the first time, and cached subsequently.</p>
         *
         * @return the tag numeric value corresponding to the string
         *
         * @hide
         */
        public final int getTag() {
            if (!mHasTag) {
                mTag = CameraMetadata.getTag(mName);
                mHasTag = true;
            }
            return mTag;
        }
    }

    private final Map<Key<?>, Object> mMetadataMap;

    private long mMetadataPtr; // native CameraMetadata*

    private native long nativeAllocate();
    private native synchronized void nativeWriteToParcel(Parcel dest);
    private native synchronized void nativeReadFromParcel(Parcel source);
    private native synchronized void nativeSwap(CameraMetadata other) throws NullPointerException;
    private native synchronized void nativeClose();
    private native synchronized boolean nativeIsEmpty();
    private native synchronized int nativeGetEntryCount();

    private native synchronized byte[] nativeReadValues(int tag);
    private native synchronized void nativeWriteValues(int tag, byte[] src);

    private static native int nativeGetTagFromKey(String keyName)
            throws IllegalArgumentException;
    private static native int nativeGetTypeFromTag(int tag)
            throws IllegalArgumentException;
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

    /**
     * Convert a key string into the equivalent native tag.
     *
     * @throws IllegalArgumentException if the key was not recognized
     * @throws NullPointerException if the key was null
     *
     * @hide
     */
    public static int getTag(String key) {
        return nativeGetTagFromKey(key);
    }

    /**
     * Get the underlying native type for a tag.
     *
     * @param tag an integer tag, see e.g. {@link #getTag}
     * @return an int enum for the metadata type, see e.g. {@link #TYPE_BYTE}
     *
     * @hide
     */
    public static int getNativeType(int tag) {
        return nativeGetTypeFromTag(tag);
    }

    /**
     * <p>Updates the existing entry for tag with the new bytes pointed by src, erasing
     * the entry if src was null.</p>
     *
     * <p>An empty array can be passed in to update the entry to 0 elements.</p>
     *
     * @param tag an integer tag, see e.g. {@link #getTag}
     * @param src an array of bytes, or null to erase the entry
     *
     * @hide
     */
    public void writeValues(int tag, byte[] src) {
        nativeWriteValues(tag, src);
    }

    /**
     * <p>Returns a byte[] of data corresponding to this tag. Use a wrapped bytebuffer to unserialize
     * the data properly.</p>
     *
     * <p>An empty array can be returned to denote an existing entry with 0 elements.</p>
     *
     * @param tag an integer tag, see e.g. {@link #getTag}
     *
     * @return null if there were 0 entries for this tag, a byte[] otherwise.
     * @hide
     */
    public byte[] readValues(int tag) {
     // TODO: Optimization. Native code returns a ByteBuffer instead.
        return nativeReadValues(tag);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private static final HashMap<Class<? extends Enum>, int[]> sEnumValues =
            new HashMap<Class<? extends Enum>, int[]>();
    /**
     * Register a non-sequential set of values to be used with the pack/unpack functions.
     * This enables get/set to correctly marshal the enum into a value that is C-compatible.
     *
     * @param enumType the class for an enum
     * @param values a list of values mapping to the ordinals of the enum
     *
     * @hide
     */
    public static <T extends Enum<T>> void registerEnumValues(Class<T> enumType, int[] values) {
        if (enumType.getEnumConstants().length != values.length) {
            throw new IllegalArgumentException(
                    "Expected values array to be the same size as the enumTypes values "
                            + values.length + " for type " + enumType);
        }

        Log.v(TAG, "Registered enum values for type " + enumType + " values");

        sEnumValues.put(enumType, values);
    }

    /**
     * Get the numeric value from an enum. This is usually the same as the ordinal value for
     * enums that have fully sequential values, although for C-style enums the range of values
     * may not map 1:1.
     *
     * @param enumValue enum instance
     * @return int guaranteed to be ABI-compatible with the C enum equivalent
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
     * @param enumType class of the enum we want to find
     * @param value the numeric value of the enum
     * @return an instance of the enum
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

    /**
     * We use a class initializer to allow the native code to cache some field offsets
     */
    static {
        System.loadLibrary("media_jni");
        nativeClassInit();
    }
}
