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

package android.hardware.camera2.impl;

import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.Face;
import android.hardware.camera2.Rational;
import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Implementation of camera metadata marshal/unmarshal across Binder to
 * the camera service
 */
public class CameraMetadataNative extends CameraMetadata implements Parcelable {

    private static final String TAG = "CameraMetadataJV";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    // this should be in sync with HAL_PIXEL_FORMAT_BLOB defined in graphics.h
    private static final int NATIVE_JPEG_FORMAT = 0x21;

    public CameraMetadataNative() {
        super();
        mMetadataPtr = nativeAllocate();
        if (mMetadataPtr == 0) {
            throw new OutOfMemoryError("Failed to allocate native CameraMetadata");
        }
    }

    /**
     * Copy constructor - clone metadata
     */
    public CameraMetadataNative(CameraMetadataNative other) {
        super();
        mMetadataPtr = nativeAllocateCopy(other);
        if (mMetadataPtr == 0) {
            throw new OutOfMemoryError("Failed to allocate native CameraMetadata");
        }
    }

    public static final Parcelable.Creator<CameraMetadataNative> CREATOR =
            new Parcelable.Creator<CameraMetadataNative>() {
        @Override
        public CameraMetadataNative createFromParcel(Parcel in) {
            CameraMetadataNative metadata = new CameraMetadataNative();
            metadata.readFromParcel(in);
            return metadata;
        }

        @Override
        public CameraMetadataNative[] newArray(int size) {
            return new CameraMetadataNative[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        nativeWriteToParcel(dest);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Key<T> key) {
        T value = getOverride(key);
        if (value != null) {
            return value;
        }

        return getBase(key);
    }

    public void readFromParcel(Parcel in) {
        nativeReadFromParcel(in);
    }

    /**
     * Set a camera metadata field to a value. The field definitions can be
     * found in {@link CameraCharacteristics}, {@link CaptureResult}, and
     * {@link CaptureRequest}.
     *
     * @param key The metadata field to write.
     * @param value The value to set the field to, which must be of a matching
     * type to the key.
     */
    public <T> void set(Key<T> key, T value) {
        if (setOverride(key, value)) {
            return;
        }

        setBase(key, value);
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

    private void close() {
        // this sets mMetadataPtr to 0
        nativeClose();
        mMetadataPtr = 0; // set it to 0 again to prevent eclipse from making this field final
    }

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

            if (VERBOSE) {
                Log.v(TAG,
                        String.format(
                            "Attempting to unpack array (count = %d, element size = %d, bytes " +
                            "remaining = %d) for type %s",
                            arraySize, elementSize, remaining, type));
            }

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

    private <T> T getBase(Key<T> key) {
        int tag = key.getTag();
        byte[] values = readValues(tag);
        if (values == null) {
            return null;
        }

        int nativeType = getNativeType(tag);

        ByteBuffer buffer = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder());
        return unpackSingle(buffer, key.getType(), nativeType);
    }

    // Need overwrite some metadata that has different definitions between native
    // and managed sides.
    @SuppressWarnings("unchecked")
    private <T> T getOverride(Key<T> key) {
        if (key.equals(CameraCharacteristics.SCALER_AVAILABLE_FORMATS)) {
            return (T) getAvailableFormats();
        } else if (key.equals(CaptureResult.STATISTICS_FACES)) {
            return (T) getFaces();
        } else if (key.equals(CaptureResult.STATISTICS_FACE_RECTANGLES)) {
            return (T) fixFaceRectangles();
        }

        // For other keys, get() falls back to getBase()
        return null;
    }

    private int[] getAvailableFormats() {
        int[] availableFormats = getBase(CameraCharacteristics.SCALER_AVAILABLE_FORMATS);
        for (int i = 0; i < availableFormats.length; i++) {
            // JPEG has different value between native and managed side, need override.
            if (availableFormats[i] == NATIVE_JPEG_FORMAT) {
                availableFormats[i] = ImageFormat.JPEG;
            }
        }
        return availableFormats;
    }

    private Face[] getFaces() {
        final int FACE_LANDMARK_SIZE = 6;

        Integer faceDetectMode = get(CaptureResult.STATISTICS_FACE_DETECT_MODE);
        if (faceDetectMode == null) {
            Log.w(TAG, "Face detect mode metadata is null, assuming the mode is SIMPLE");
            faceDetectMode = CaptureResult.STATISTICS_FACE_DETECT_MODE_SIMPLE;
        } else {
            if (faceDetectMode == CaptureResult.STATISTICS_FACE_DETECT_MODE_OFF) {
                return new Face[0];
            }
            if (faceDetectMode != CaptureResult.STATISTICS_FACE_DETECT_MODE_SIMPLE &&
                    faceDetectMode != CaptureResult.STATISTICS_FACE_DETECT_MODE_FULL) {
                Log.w(TAG, "Unknown face detect mode: " + faceDetectMode);
                return new Face[0];
            }
        }

        // Face scores and rectangles are required by SIMPLE and FULL mode.
        byte[] faceScores = get(CaptureResult.STATISTICS_FACE_SCORES);
        Rect[] faceRectangles = get(CaptureResult.STATISTICS_FACE_RECTANGLES);
        if (faceScores == null || faceRectangles == null) {
            Log.w(TAG, "Expect face scores and rectangles to be non-null");
            return new Face[0];
        } else if (faceScores.length != faceRectangles.length) {
            Log.w(TAG, String.format("Face score size(%d) doesn match face rectangle size(%d)!",
                    faceScores.length, faceRectangles.length));
        }

        // To be safe, make number of faces is the minimal of all face info metadata length.
        int numFaces = Math.min(faceScores.length, faceRectangles.length);
        // Face id and landmarks are only required by FULL mode.
        int[] faceIds = get(CaptureResult.STATISTICS_FACE_IDS);
        int[] faceLandmarks = get(CaptureResult.STATISTICS_FACE_LANDMARKS);
        if (faceDetectMode == CaptureResult.STATISTICS_FACE_DETECT_MODE_FULL) {
            if (faceIds == null || faceLandmarks == null) {
                Log.w(TAG, "Expect face ids and landmarks to be non-null for FULL mode," +
                        "fallback to SIMPLE mode");
                faceDetectMode = CaptureResult.STATISTICS_FACE_DETECT_MODE_SIMPLE;
            } else {
                if (faceIds.length != numFaces ||
                        faceLandmarks.length != numFaces * FACE_LANDMARK_SIZE) {
                    Log.w(TAG, String.format("Face id size(%d), or face landmark size(%d) don't" +
                            "match face number(%d)!",
                            faceIds.length, faceLandmarks.length * FACE_LANDMARK_SIZE, numFaces));
                }
                // To be safe, make number of faces is the minimal of all face info metadata length.
                numFaces = Math.min(numFaces, faceIds.length);
                numFaces = Math.min(numFaces, faceLandmarks.length / FACE_LANDMARK_SIZE);
            }
        }

        ArrayList<Face> faceList = new ArrayList<Face>();
        if (faceDetectMode == CaptureResult.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
            for (int i = 0; i < numFaces; i++) {
                if (faceScores[i] <= Face.SCORE_MAX &&
                        faceScores[i] >= Face.SCORE_MIN) {
                    faceList.add(new Face(faceRectangles[i], faceScores[i]));
                }
            }
        } else {
            // CaptureResult.STATISTICS_FACE_DETECT_MODE_FULL
            for (int i = 0; i < numFaces; i++) {
                if (faceScores[i] <= Face.SCORE_MAX &&
                        faceScores[i] >= Face.SCORE_MIN &&
                        faceIds[i] >= 0) {
                    Point leftEye = new Point(faceLandmarks[i*6], faceLandmarks[i*6+1]);
                    Point rightEye = new Point(faceLandmarks[i*6+2], faceLandmarks[i*6+3]);
                    Point mouth = new Point(faceLandmarks[i*6+4], faceLandmarks[i*6+5]);
                    Face face = new Face(faceRectangles[i], faceScores[i], faceIds[i],
                            leftEye, rightEye, mouth);
                    faceList.add(face);
                }
            }
        }
        Face[] faces = new Face[faceList.size()];
        faceList.toArray(faces);
        return faces;
    }

    // Face rectangles are defined as (left, top, right, bottom) instead of
    // (left, top, width, height) at the native level, so the normal Rect
    // conversion that does (l, t, w, h) -> (l, t, r, b) is unnecessary. Undo
    // that conversion here for just the faces.
    private Rect[] fixFaceRectangles() {
        Rect[] faceRectangles = getBase(CaptureResult.STATISTICS_FACE_RECTANGLES);
        if (faceRectangles == null) return null;

        Rect[] fixedFaceRectangles = new Rect[faceRectangles.length];
        for (int i = 0; i < faceRectangles.length; i++) {
            fixedFaceRectangles[i] = new Rect(
                    faceRectangles[i].left,
                    faceRectangles[i].top,
                    faceRectangles[i].right - faceRectangles[i].left,
                    faceRectangles[i].bottom - faceRectangles[i].top);
        }
        return fixedFaceRectangles;
    }

    private <T> void setBase(Key<T> key, T value) {
        int tag = key.getTag();

        if (value == null) {
            writeValues(tag, null);
            return;
        }

        int nativeType = getNativeType(tag);

        int size = packSingle(value, null, key.getType(), nativeType, /* sizeOnly */true);

        // TODO: Optimization. Cache the byte[] and reuse if the size is big enough.
        byte[] values = new byte[size];

        ByteBuffer buffer = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder());
        packSingle(value, buffer, key.getType(), nativeType, /*sizeOnly*/false);

        writeValues(tag, values);
    }

    // Set the camera metadata override.
    private <T> boolean setOverride(Key<T> key, T value) {
        if (key.equals(CameraCharacteristics.SCALER_AVAILABLE_FORMATS)) {
            return setAvailableFormats((int[]) value);
        }

        // For other keys, set() falls back to setBase().
        return false;
    }

    private boolean setAvailableFormats(int[] value) {
        int[] availableFormat = value;
        if (value == null) {
            // Let setBase() to handle the null value case.
            return false;
        }

        int[] newValues = new int[availableFormat.length];
        for (int i = 0; i < availableFormat.length; i++) {
            newValues[i] = availableFormat[i];
            if (availableFormat[i] == ImageFormat.JPEG) {
                newValues[i] = NATIVE_JPEG_FORMAT;
            }
        }

        setBase(CameraCharacteristics.SCALER_AVAILABLE_FORMATS, newValues);
        return true;
    }

    private long mMetadataPtr; // native CameraMetadata*

    private native long nativeAllocate();
    private native long nativeAllocateCopy(CameraMetadataNative other)
            throws NullPointerException;

    private native synchronized void nativeWriteToParcel(Parcel dest);
    private native synchronized void nativeReadFromParcel(Parcel source);
    private native synchronized void nativeSwap(CameraMetadataNative other)
            throws NullPointerException;
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
    public void swap(CameraMetadataNative other) {
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
        if (VERBOSE) {
            Log.v(TAG, "Registered enum values for type " + enumType + " values");
        }

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
        nativeClassInit();

        if (VERBOSE) {
            Log.v(TAG, "Shall register metadata marshalers");
        }

        // load built-in marshallers
        registerMarshaler(new MetadataMarshalRect());
        registerMarshaler(new MetadataMarshalSize());
        registerMarshaler(new MetadataMarshalString());

        if (VERBOSE) {
            Log.v(TAG, "Registered metadata marshalers");
        }
    }

}
