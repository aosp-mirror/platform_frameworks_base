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

package android.hardware.camera2;

import android.hardware.camera2.impl.MetadataMarshalClass;
import android.hardware.camera2.impl.MetadataMarshalRect;
import android.hardware.camera2.impl.MetadataMarshalSize;
import android.hardware.camera2.impl.MetadataMarshalString;
import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
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
     * @param key The metadata field to write.
     * @param value The value to set the field to, which must be of a matching
     * type to the key.
     */
    public <T> void set(Key<T> key, T value) {
        int tag = key.getTag();

        if (value == null) {
            writeValues(tag, null);
            return;
        }

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
     * @param key The metadata field to read.
     * @return The value of that key, or {@code null} if the field is not set.
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

        MetadataMarshalClass<T> marshaler = getMarshaler(type, nativeType);
        if (marshaler == null) {
            throw new IllegalArgumentException(String.format("Unknown Key type: %s", type));
        }

        return marshaler.marshal(value, buffer, nativeType, sizeOnly);
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

    private static <T extends Enum<T>> T unpackEnum(ByteBuffer buffer, Class<T> type,
            int nativeType) {
        int ordinal = unpackSingleNative(buffer, Integer.TYPE, nativeType);
        return getEnumFromValue(type, ordinal);
    }

    private static <T> T unpackClass(ByteBuffer buffer, Class<T> type, int nativeType) {

        MetadataMarshalClass<T> marshaler = getMarshaler(type, nativeType);
        if (marshaler == null) {
            throw new IllegalArgumentException("Unknown class type: " + type);
        }

        return marshaler.unmarshal(buffer, nativeType);
    }

    @SuppressWarnings("unchecked")
    private static <T> T unpackArray(ByteBuffer buffer, Class<T> type, int nativeType) {

        Class<?> componentType = type.getComponentType();
        Object array;

        int elementSize = getTypeSize(nativeType);

        MetadataMarshalClass<?> marshaler = getMarshaler(componentType, nativeType);
        if (marshaler != null) {
            elementSize = marshaler.getNativeSize(nativeType);
        }

        if (elementSize != MetadataMarshalClass.NATIVE_SIZE_DYNAMIC) {
            int remaining = buffer.remaining();
            int arraySize = remaining / elementSize;

            Log.v(TAG,
                    String.format(
                            "Attempting to unpack array (count = %d, element size = %d, bytes " +
                                    "remaining = %d) for type %s",
                            arraySize, elementSize, remaining, type));

            array = Array.newInstance(componentType, arraySize);
            for (int i = 0; i < arraySize; ++i) {
               Object elem = unpackSingle(buffer, componentType, nativeType);
               Array.set(array, i, elem);
            }
        } else {
            // Dynamic size, use an array list.
            ArrayList<Object> arrayList = new ArrayList<Object>();

            int primitiveSize = getTypeSize(nativeType);
            while (buffer.remaining() >= primitiveSize) {
                Object elem = unpackSingle(buffer, componentType, nativeType);
                arrayList.add(elem);
            }

            array = arrayList.toArray((T[]) Array.newInstance(componentType, 0));
        }

        if (buffer.remaining() != 0) {
            Log.e(TAG, "Trailing bytes (" + buffer.remaining() + ") left over after unpacking "
                    + type);
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
         * @return The tag numeric value corresponding to the string
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
     * @param other Metadata to swap with
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
     * @param tag An integer tag, see e.g. {@link #getTag}
     * @return An int enum for the metadata type, see e.g. {@link #TYPE_BYTE}
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
     * @param tag An integer tag, see e.g. {@link #getTag}
     * @param src An array of bytes, or null to erase the entry
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
     * @param tag An integer tag, see e.g. {@link #getTag}
     *
     * @return {@code null} if there were 0 entries for this tag, a byte[] otherwise.
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
     * @param enumType The class for an enum
     * @param values A list of values mapping to the ordinals of the enum
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

    static HashMap<Class<?>, MetadataMarshalClass<?>> sMarshalerMap = new
            HashMap<Class<?>, MetadataMarshalClass<?>>();

    private static <T> void registerMarshaler(MetadataMarshalClass<T> marshaler) {
        sMarshalerMap.put(marshaler.getMarshalingClass(), marshaler);
    }

    @SuppressWarnings("unchecked")
    private static <T> MetadataMarshalClass<T> getMarshaler(Class<T> type, int nativeType) {
        MetadataMarshalClass<T> marshaler = (MetadataMarshalClass<T>) sMarshalerMap.get(type);

        if (marshaler != null && !marshaler.isNativeTypeSupported(nativeType)) {
            throw new UnsupportedOperationException("Unsupported type " + nativeType +
                    " to be marshalled to/from a " + type);
        }

        return marshaler;
    }

    /**
     * We use a class initializer to allow the native code to cache some field offsets
     */
    static {
        System.loadLibrary("media_jni");
        nativeClassInit();

        Log.v(TAG, "Shall register metadata marshalers");

        // load built-in marshallers
        registerMarshaler(new MetadataMarshalRect());
        registerMarshaler(new MetadataMarshalSize());
        registerMarshaler(new MetadataMarshalString());

        Log.v(TAG, "Registered metadata marshalers");
    }

    /*@O~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * The enum values below this point are generated from metadata
     * definitions in /system/media/camera/docs. Do not modify by hand or
     * modify the comment blocks at the start or end.
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~*/

    //
    // Enumeration values for CameraProperties#LENS_FACING
    //

    /**
     * @see CameraProperties#LENS_FACING
     */
    public static final int LENS_FACING_FRONT = 0;

    /**
     * @see CameraProperties#LENS_FACING
     */
    public static final int LENS_FACING_BACK = 1;

    //
    // Enumeration values for CameraProperties#LED_AVAILABLE_LEDS
    //

    /**
     * <p>
     * android.led.transmit control is used
     * </p>
     * @see CameraProperties#LED_AVAILABLE_LEDS
     * @hide
     */
    public static final int LED_AVAILABLE_LEDS_TRANSMIT = 0;

    //
    // Enumeration values for CameraProperties#INFO_SUPPORTED_HARDWARE_LEVEL
    //

    /**
     * @see CameraProperties#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED = 0;

    /**
     * @see CameraProperties#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_FULL = 1;

    //
    // Enumeration values for CaptureRequest#COLOR_CORRECTION_MODE
    //

    /**
     * <p>
     * Use the android.colorCorrection.transform matrix
     * and android.colorCorrection.gains to do color conversion
     * </p>
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     */
    public static final int COLOR_CORRECTION_MODE_TRANSFORM_MATRIX = 0;

    /**
     * <p>
     * Must not slow down frame rate relative to raw
     * bayer output
     * </p>
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     */
    public static final int COLOR_CORRECTION_MODE_FAST = 1;

    /**
     * <p>
     * Frame rate may be reduced by high
     * quality
     * </p>
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     */
    public static final int COLOR_CORRECTION_MODE_HIGH_QUALITY = 2;

    //
    // Enumeration values for CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
    //

    /**
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     */
    public static final int CONTROL_AE_ANTIBANDING_MODE_OFF = 0;

    /**
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     */
    public static final int CONTROL_AE_ANTIBANDING_MODE_50HZ = 1;

    /**
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     */
    public static final int CONTROL_AE_ANTIBANDING_MODE_60HZ = 2;

    /**
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     */
    public static final int CONTROL_AE_ANTIBANDING_MODE_AUTO = 3;

    //
    // Enumeration values for CaptureRequest#CONTROL_AE_MODE
    //

    /**
     * <p>
     * Autoexposure is disabled; sensor.exposureTime,
     * sensor.sensitivity and sensor.frameDuration are used
     * </p>
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_OFF = 0;

    /**
     * <p>
     * Autoexposure is active, no flash
     * control
     * </p>
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_ON = 1;

    /**
     * <p>
     * if flash exists Autoexposure is active, auto
     * flash control; flash may be fired when precapture
     * trigger is activated, and for captures for which
     * captureIntent = STILL_CAPTURE
     * </p>
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_ON_AUTO_FLASH = 2;

    /**
     * <p>
     * if flash exists Autoexposure is active, auto
     * flash control for precapture trigger and always flash
     * when captureIntent = STILL_CAPTURE
     * </p>
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_ON_ALWAYS_FLASH = 3;

    /**
     * <p>
     * optional Automatic red eye reduction with flash.
     * If deemed necessary, red eye reduction sequence should
     * fire when precapture trigger is activated, and final
     * flash should fire when captureIntent =
     * STILL_CAPTURE
     * </p>
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE = 4;

    //
    // Enumeration values for CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
    //

    /**
     * <p>
     * The trigger is idle.
     * </p>
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     */
    public static final int CONTROL_AE_PRECAPTURE_TRIGGER_IDLE = 0;

    /**
     * <p>
     * The precapture metering sequence
     * must be started. The exact effect of the precapture
     * trigger depends on the current AE mode and
     * state.
     * </p>
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     */
    public static final int CONTROL_AE_PRECAPTURE_TRIGGER_START = 1;

    //
    // Enumeration values for CaptureRequest#CONTROL_AF_MODE
    //

    /**
     * <p>
     * The 3A routines do not control the lens;
     * android.lens.focusDistance is controlled by the
     * application
     * </p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_OFF = 0;

    /**
     * <p>
     * if lens is not fixed focus.
     * </p><p>
     * Use android.lens.minimumFocusDistance to determine if lens
     * is fixed focus In this mode, the lens does not move unless
     * the autofocus trigger action is called. When that trigger
     * is activated, AF must transition to ACTIVE_SCAN, then to
     * the outcome of the scan (FOCUSED or
     * NOT_FOCUSED).
     * </p><p>
     * Triggering cancel AF resets the lens position to default,
     * and sets the AF state to INACTIVE.
     * </p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_AUTO = 1;

    /**
     * <p>
     * In this mode, the lens does not move unless the
     * autofocus trigger action is called.
     * </p><p>
     * When that trigger is activated, AF must transition to
     * ACTIVE_SCAN, then to the outcome of the scan (FOCUSED or
     * NOT_FOCUSED).  Triggering cancel AF resets the lens
     * position to default, and sets the AF state to
     * INACTIVE.
     * </p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_MACRO = 2;

    /**
     * <p>
     * In this mode, the AF algorithm modifies the lens
     * position continually to attempt to provide a
     * constantly-in-focus image stream.
     * </p><p>
     * The focusing behavior should be suitable for good quality
     * video recording; typically this means slower focus
     * movement and no overshoots. When the AF trigger is not
     * involved, the AF algorithm should start in INACTIVE state,
     * and then transition into PASSIVE_SCAN and PASSIVE_FOCUSED
     * states as appropriate. When the AF trigger is activated,
     * the algorithm should immediately transition into
     * AF_FOCUSED or AF_NOT_FOCUSED as appropriate, and lock the
     * lens position until a cancel AF trigger is received.
     * </p><p>
     * Once cancel is received, the algorithm should transition
     * back to INACTIVE and resume passive scan. Note that this
     * behavior is not identical to CONTINUOUS_PICTURE, since an
     * ongoing PASSIVE_SCAN must immediately be
     * canceled.
     * </p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_CONTINUOUS_VIDEO = 3;

    /**
     * <p>
     * In this mode, the AF algorithm modifies the lens
     * position continually to attempt to provide a
     * constantly-in-focus image stream.
     * </p><p>
     * The focusing behavior should be suitable for still image
     * capture; typically this means focusing as fast as
     * possible. When the AF trigger is not involved, the AF
     * algorithm should start in INACTIVE state, and then
     * transition into PASSIVE_SCAN and PASSIVE_FOCUSED states as
     * appropriate as it attempts to maintain focus. When the AF
     * trigger is activated, the algorithm should finish its
     * PASSIVE_SCAN if active, and then transition into
     * AF_FOCUSED or AF_NOT_FOCUSED as appropriate, and lock the
     * lens position until a cancel AF trigger is received.
     * </p><p>
     * When the AF cancel trigger is activated, the algorithm
     * should transition back to INACTIVE and then act as if it
     * has just been started.
     * </p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_CONTINUOUS_PICTURE = 4;

    /**
     * <p>
     * Extended depth of field (digital focus). AF
     * trigger is ignored, AF state should always be
     * INACTIVE.
     * </p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_EDOF = 5;

    //
    // Enumeration values for CaptureRequest#CONTROL_AF_TRIGGER
    //

    /**
     * <p>
     * The trigger is idle.
     * </p>
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     */
    public static final int CONTROL_AF_TRIGGER_IDLE = 0;

    /**
     * <p>
     * Autofocus must trigger now.
     * </p>
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     */
    public static final int CONTROL_AF_TRIGGER_START = 1;

    /**
     * <p>
     * Autofocus must return to initial
     * state, and cancel any active trigger.
     * </p>
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     */
    public static final int CONTROL_AF_TRIGGER_CANCEL = 2;

    //
    // Enumeration values for CaptureRequest#CONTROL_AWB_MODE
    //

    /**
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_OFF = 0;

    /**
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_AUTO = 1;

    /**
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_INCANDESCENT = 2;

    /**
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_FLUORESCENT = 3;

    /**
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_WARM_FLUORESCENT = 4;

    /**
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_DAYLIGHT = 5;

    /**
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_CLOUDY_DAYLIGHT = 6;

    /**
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_TWILIGHT = 7;

    /**
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_SHADE = 8;

    //
    // Enumeration values for CaptureRequest#CONTROL_CAPTURE_INTENT
    //

    /**
     * <p>
     * This request doesn't fall into the other
     * categories. Default to preview-like
     * behavior.
     * </p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_CUSTOM = 0;

    /**
     * <p>
     * This request is for a preview-like usecase. The
     * precapture trigger may be used to start off a metering
     * w/flash sequence
     * </p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_PREVIEW = 1;

    /**
     * <p>
     * This request is for a still capture-type
     * usecase.
     * </p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_STILL_CAPTURE = 2;

    /**
     * <p>
     * This request is for a video recording
     * usecase.
     * </p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_VIDEO_RECORD = 3;

    /**
     * <p>
     * This request is for a video snapshot (still
     * image while recording video) usecase
     * </p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT = 4;

    /**
     * <p>
     * This request is for a ZSL usecase; the
     * application will stream full-resolution images and
     * reprocess one or several later for a final
     * capture
     * </p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG = 5;

    //
    // Enumeration values for CaptureRequest#CONTROL_EFFECT_MODE
    //

    /**
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_OFF = 0;

    /**
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_MONO = 1;

    /**
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_NEGATIVE = 2;

    /**
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_SOLARIZE = 3;

    /**
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_SEPIA = 4;

    /**
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_POSTERIZE = 5;

    /**
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_WHITEBOARD = 6;

    /**
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_BLACKBOARD = 7;

    /**
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_AQUA = 8;

    //
    // Enumeration values for CaptureRequest#CONTROL_MODE
    //

    /**
     * <p>
     * Full application control of pipeline. All 3A
     * routines are disabled, no other settings in
     * android.control.* have any effect
     * </p>
     * @see CaptureRequest#CONTROL_MODE
     */
    public static final int CONTROL_MODE_OFF = 0;

    /**
     * <p>
     * Use settings for each individual 3A routine.
     * Manual control of capture parameters is disabled. All
     * controls in android.control.* besides sceneMode take
     * effect
     * </p>
     * @see CaptureRequest#CONTROL_MODE
     */
    public static final int CONTROL_MODE_AUTO = 1;

    /**
     * <p>
     * Use specific scene mode. Enabling this disables
     * control.aeMode, control.awbMode and control.afMode
     * controls; the HAL must ignore those settings while
     * USE_SCENE_MODE is active (except for FACE_PRIORITY
     * scene mode). Other control entries are still active.
     * This setting can only be used if availableSceneModes !=
     * UNSUPPORTED
     * </p>
     * @see CaptureRequest#CONTROL_MODE
     */
    public static final int CONTROL_MODE_USE_SCENE_MODE = 2;

    //
    // Enumeration values for CaptureRequest#CONTROL_SCENE_MODE
    //

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_UNSUPPORTED = 0;

    /**
     * <p>
     * if face detection support exists Use face
     * detection data to drive 3A routines. If face detection
     * statistics are disabled, should still operate correctly
     * (but not return face detection statistics to the
     * framework).
     * </p><p>
     * Unlike the other scene modes, aeMode, awbMode, and afMode
     * remain active when FACE_PRIORITY is set. This is due to
     * compatibility concerns with the old camera
     * API
     * </p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_FACE_PRIORITY = 1;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_ACTION = 2;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_PORTRAIT = 3;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_LANDSCAPE = 4;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_NIGHT = 5;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_NIGHT_PORTRAIT = 6;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_THEATRE = 7;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_BEACH = 8;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_SNOW = 9;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_SUNSET = 10;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_STEADYPHOTO = 11;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_FIREWORKS = 12;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_SPORTS = 13;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_PARTY = 14;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_CANDLELIGHT = 15;

    /**
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_BARCODE = 16;

    //
    // Enumeration values for CaptureRequest#EDGE_MODE
    //

    /**
     * <p>
     * No edge enhancement is applied
     * </p>
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_OFF = 0;

    /**
     * <p>
     * Must not slow down frame rate relative to raw
     * bayer output
     * </p>
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_FAST = 1;

    /**
     * <p>
     * Frame rate may be reduced by high
     * quality
     * </p>
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_HIGH_QUALITY = 2;

    //
    // Enumeration values for CaptureRequest#FLASH_MODE
    //

    /**
     * <p>
     * Do not fire the flash for this
     * capture
     * </p>
     * @see CaptureRequest#FLASH_MODE
     */
    public static final int FLASH_MODE_OFF = 0;

    /**
     * <p>
     * if android.flash.available is true Fire flash
     * for this capture based on firingPower,
     * firingTime.
     * </p>
     * @see CaptureRequest#FLASH_MODE
     */
    public static final int FLASH_MODE_SINGLE = 1;

    /**
     * <p>
     * if android.flash.available is true Flash
     * continuously on, power set by
     * firingPower
     * </p>
     * @see CaptureRequest#FLASH_MODE
     */
    public static final int FLASH_MODE_TORCH = 2;

    //
    // Enumeration values for CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
    //

    /**
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     */
    public static final int LENS_OPTICAL_STABILIZATION_MODE_OFF = 0;

    /**
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     */
    public static final int LENS_OPTICAL_STABILIZATION_MODE_ON = 1;

    //
    // Enumeration values for CaptureRequest#NOISE_REDUCTION_MODE
    //

    /**
     * <p>
     * No noise reduction is applied
     * </p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_OFF = 0;

    /**
     * <p>
     * Must not slow down frame rate relative to raw
     * bayer output
     * </p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_FAST = 1;

    /**
     * <p>
     * May slow down frame rate to provide highest
     * quality
     * </p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_HIGH_QUALITY = 2;

    //
    // Enumeration values for CaptureRequest#STATISTICS_FACE_DETECT_MODE
    //

    /**
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     */
    public static final int STATISTICS_FACE_DETECT_MODE_OFF = 0;

    /**
     * <p>
     * Optional Return rectangle and confidence
     * only
     * </p>
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     */
    public static final int STATISTICS_FACE_DETECT_MODE_SIMPLE = 1;

    /**
     * <p>
     * Optional Return all face
     * metadata
     * </p>
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     */
    public static final int STATISTICS_FACE_DETECT_MODE_FULL = 2;

    //
    // Enumeration values for CaptureRequest#TONEMAP_MODE
    //

    /**
     * <p>
     * Use the tone mapping curve specified in
     * android.tonemap.curve
     * </p>
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_CONTRAST_CURVE = 0;

    /**
     * <p>
     * Must not slow down frame rate relative to raw
     * bayer output
     * </p>
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_FAST = 1;

    /**
     * <p>
     * Frame rate may be reduced by high
     * quality
     * </p>
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_HIGH_QUALITY = 2;

    //
    // Enumeration values for CaptureResult#CONTROL_AE_STATE
    //

    /**
     * <p>
     * AE is off.  When a camera device is opened, it starts in
     * this state.
     * </p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_INACTIVE = 0;

    /**
     * <p>
     * AE doesn't yet have a good set of control values
     * for the current scene
     * </p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_SEARCHING = 1;

    /**
     * <p>
     * AE has a good set of control values for the
     * current scene
     * </p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_CONVERGED = 2;

    /**
     * <p>
     * AE has been locked (aeMode =
     * LOCKED)
     * </p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_LOCKED = 3;

    /**
     * <p>
     * AE has a good set of control values, but flash
     * needs to be fired for good quality still
     * capture
     * </p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_FLASH_REQUIRED = 4;

    /**
     * <p>
     * AE has been asked to do a precapture sequence
     * (through the
     * trigger_action(CAMERA2_TRIGGER_PRECAPTURE_METERING)
     * call), and is currently executing it. Once PRECAPTURE
     * completes, AE will transition to CONVERGED or
     * FLASH_REQUIRED as appropriate
     * </p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_PRECAPTURE = 5;

    //
    // Enumeration values for CaptureResult#CONTROL_AF_STATE
    //

    /**
     * <p>
     * AF off or has not yet tried to scan/been asked
     * to scan.  When a camera device is opened, it starts in
     * this state.
     * </p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_INACTIVE = 0;

    /**
     * <p>
     * if CONTINUOUS_* modes are supported AF is
     * currently doing an AF scan initiated by a continuous
     * autofocus mode
     * </p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_PASSIVE_SCAN = 1;

    /**
     * <p>
     * if CONTINUOUS_* modes are supported AF currently
     * believes it is in focus, but may restart scanning at
     * any time.
     * </p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_PASSIVE_FOCUSED = 2;

    /**
     * <p>
     * if AUTO or MACRO modes are supported AF is doing
     * an AF scan because it was triggered by AF
     * trigger
     * </p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_ACTIVE_SCAN = 3;

    /**
     * <p>
     * if any AF mode besides OFF is supported AF
     * believes it is focused correctly and is
     * locked
     * </p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_FOCUSED_LOCKED = 4;

    /**
     * <p>
     * if any AF mode besides OFF is supported AF has
     * failed to focus successfully and is
     * locked
     * </p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_NOT_FOCUSED_LOCKED = 5;

    //
    // Enumeration values for CaptureResult#CONTROL_AWB_STATE
    //

    /**
     * <p>
     * AWB is not in auto mode.  When a camera device is opened, it
     * starts in this state.
     * </p>
     * @see CaptureResult#CONTROL_AWB_STATE
     */
    public static final int CONTROL_AWB_STATE_INACTIVE = 0;

    /**
     * <p>
     * AWB doesn't yet have a good set of control
     * values for the current scene
     * </p>
     * @see CaptureResult#CONTROL_AWB_STATE
     */
    public static final int CONTROL_AWB_STATE_SEARCHING = 1;

    /**
     * <p>
     * AWB has a good set of control values for the
     * current scene
     * </p>
     * @see CaptureResult#CONTROL_AWB_STATE
     */
    public static final int CONTROL_AWB_STATE_CONVERGED = 2;

    /**
     * <p>
     * AE has been locked (aeMode =
     * LOCKED)
     * </p>
     * @see CaptureResult#CONTROL_AWB_STATE
     */
    public static final int CONTROL_AWB_STATE_LOCKED = 3;

    //
    // Enumeration values for CaptureResult#FLASH_STATE
    //

    /**
     * <p>
     * No flash on camera
     * </p>
     * @see CaptureResult#FLASH_STATE
     */
    public static final int FLASH_STATE_UNAVAILABLE = 0;

    /**
     * <p>
     * if android.flash.available is true Flash is
     * charging and cannot be fired
     * </p>
     * @see CaptureResult#FLASH_STATE
     */
    public static final int FLASH_STATE_CHARGING = 1;

    /**
     * <p>
     * if android.flash.available is true Flash is
     * ready to fire
     * </p>
     * @see CaptureResult#FLASH_STATE
     */
    public static final int FLASH_STATE_READY = 2;

    /**
     * <p>
     * if android.flash.available is true Flash fired
     * for this capture
     * </p>
     * @see CaptureResult#FLASH_STATE
     */
    public static final int FLASH_STATE_FIRED = 3;

    //
    // Enumeration values for CaptureResult#LENS_STATE
    //

    /**
     * @see CaptureResult#LENS_STATE
     */
    public static final int LENS_STATE_STATIONARY = 0;

    /**
     * @see CaptureResult#LENS_STATE
     */
    public static final int LENS_STATE_MOVING = 1;

    //
    // Enumeration values for CaptureResult#STATISTICS_SCENE_FLICKER
    //

    /**
     * @see CaptureResult#STATISTICS_SCENE_FLICKER
     */
    public static final int STATISTICS_SCENE_FLICKER_NONE = 0;

    /**
     * @see CaptureResult#STATISTICS_SCENE_FLICKER
     */
    public static final int STATISTICS_SCENE_FLICKER_50HZ = 1;

    /**
     * @see CaptureResult#STATISTICS_SCENE_FLICKER
     */
    public static final int STATISTICS_SCENE_FLICKER_60HZ = 2;

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/

}
