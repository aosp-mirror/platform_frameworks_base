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
import android.hardware.camera2.marshal.Marshaler;
import android.hardware.camera2.marshal.MarshalQueryable;
import android.hardware.camera2.marshal.MarshalRegistry;
import android.hardware.camera2.marshal.impl.MarshalQueryableArray;
import android.hardware.camera2.marshal.impl.MarshalQueryableBoolean;
import android.hardware.camera2.marshal.impl.MarshalQueryableColorSpaceTransform;
import android.hardware.camera2.marshal.impl.MarshalQueryableEnum;
import android.hardware.camera2.marshal.impl.MarshalQueryableMeteringRectangle;
import android.hardware.camera2.marshal.impl.MarshalQueryableNativeByteToInteger;
import android.hardware.camera2.marshal.impl.MarshalQueryableParcelable;
import android.hardware.camera2.marshal.impl.MarshalQueryablePrimitive;
import android.hardware.camera2.marshal.impl.MarshalQueryableRange;
import android.hardware.camera2.marshal.impl.MarshalQueryableRect;
import android.hardware.camera2.marshal.impl.MarshalQueryableReprocessFormatsMap;
import android.hardware.camera2.marshal.impl.MarshalQueryableRggbChannelVector;
import android.hardware.camera2.marshal.impl.MarshalQueryableSize;
import android.hardware.camera2.marshal.impl.MarshalQueryableSizeF;
import android.hardware.camera2.marshal.impl.MarshalQueryableStreamConfiguration;
import android.hardware.camera2.marshal.impl.MarshalQueryableStreamConfigurationDuration;
import android.hardware.camera2.marshal.impl.MarshalQueryableString;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

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
     * Set the global client-side vendor tag descriptor to allow use of vendor
     * tags in camera applications.
     *
     * @return int A native status_t value corresponding to one of the
     * {@link CameraBinderDecorator} integer constants.
     * @see CameraBinderDecorator#throwOnError
     *
     * @hide
     */
    public static native int nativeSetupGlobalVendorTagDescriptor();

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

    private <T> T getBase(Key<T> key) {
        int tag = key.getTag();
        byte[] values = readValues(tag);
        if (values == null) {
            return null;
        }

        Marshaler<T> marshaler = getMarshalerForKey(key);
        ByteBuffer buffer = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder());
        return marshaler.unmarshal(buffer);
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
            return (T) getFaceRectangles();
        } else if (key.equals(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)) {
            return (T) getStreamConfigurationMap();
        }

        // For other keys, get() falls back to getBase()
        return null;
    }

    private int[] getAvailableFormats() {
        int[] availableFormats = getBase(CameraCharacteristics.SCALER_AVAILABLE_FORMATS);
        if (availableFormats != null) {
            for (int i = 0; i < availableFormats.length; i++) {
                // JPEG has different value between native and managed side, need override.
                if (availableFormats[i] == NATIVE_JPEG_FORMAT) {
                    availableFormats[i] = ImageFormat.JPEG;
                }
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
    private Rect[] getFaceRectangles() {
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

    private StreamConfigurationMap getStreamConfigurationMap() {
        StreamConfiguration[] configurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_STREAM_CONFIGURATIONS);
        StreamConfigurationDuration[] minFrameDurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_MIN_FRAME_DURATIONS);
        StreamConfigurationDuration[] stallDurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_STALL_DURATIONS);

        return new StreamConfigurationMap(configurations, minFrameDurations, stallDurations);
    }

    private <T> void setBase(Key<T> key, T value) {
        int tag = key.getTag();

        if (value == null) {
            // Erase the entry
            writeValues(tag, /*src*/null);
            return;
        } // else update the entry to a new value

        Marshaler<T> marshaler = getMarshalerForKey(key);
        int size = marshaler.calculateMarshalSize(value);

        // TODO: Optimization. Cache the byte[] and reuse if the size is big enough.
        byte[] values = new byte[size];

        ByteBuffer buffer = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder());
        marshaler.marshal(value, buffer);

        writeValues(tag, values);
    }

    // Set the camera metadata override.
    private <T> boolean setOverride(Key<T> key, T value) {
        if (key.equals(CameraCharacteristics.SCALER_AVAILABLE_FORMATS)) {
            return setAvailableFormats((int[]) value);
        } else if (key.equals(CaptureResult.STATISTICS_FACE_RECTANGLES)) {
            return setFaceRectangles((Rect[]) value);
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

    /**
     * Convert Face Rectangles from managed side to native side as they have different definitions.
     * <p>
     * Managed side face rectangles are defined as: left, top, width, height.
     * Native side face rectangles are defined as: left, top, right, bottom.
     * The input face rectangle need to be converted to native side definition when set is called.
     * </p>
     *
     * @param faceRects Input face rectangles.
     * @return true if face rectangles can be set successfully. Otherwise, Let the caller
     *             (setBase) to handle it appropriately.
     */
    private boolean setFaceRectangles(Rect[] faceRects) {
        if (faceRects == null) {
            return false;
        }

        Rect[] newFaceRects = new Rect[faceRects.length];
        for (int i = 0; i < newFaceRects.length; i++) {
            newFaceRects[i] = new Rect(
                    faceRects[i].left,
                    faceRects[i].top,
                    faceRects[i].right + faceRects[i].left,
                    faceRects[i].bottom + faceRects[i].top);
        }

        setBase(CaptureResult.STATISTICS_FACE_RECTANGLES, newFaceRects);
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

    /**
     * Get the marshaler compatible with the {@code key} and type {@code T}.
     *
     * @throws UnsupportedOperationException
     *          if the native/managed type combination for {@code key} is not supported
     */
    private static <T> Marshaler<T> getMarshalerForKey(Key<T> key) {
        return MarshalRegistry.getMarshaler(key.getTypeReference(),
                getNativeType(key.getTag()));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void registerAllMarshalers() {
        if (VERBOSE) {
            Log.v(TAG, "Shall register metadata marshalers");
        }

        MarshalQueryable[] queryList = new MarshalQueryable[] {
                // marshalers for standard types
                new MarshalQueryablePrimitive(),
                new MarshalQueryableEnum(),
                new MarshalQueryableArray(),

                // pseudo standard types, that expand/narrow the native type into a managed type
                new MarshalQueryableBoolean(),
                new MarshalQueryableNativeByteToInteger(),

                // marshalers for custom types
                new MarshalQueryableRect(),
                new MarshalQueryableSize(),
                new MarshalQueryableSizeF(),
                new MarshalQueryableString(),
                new MarshalQueryableReprocessFormatsMap(),
                new MarshalQueryableRange(),
                new MarshalQueryableMeteringRectangle(),
                new MarshalQueryableColorSpaceTransform(),
                new MarshalQueryableStreamConfiguration(),
                new MarshalQueryableStreamConfigurationDuration(),
                new MarshalQueryableRggbChannelVector(),

                // generic parcelable marshaler (MUST BE LAST since it has lowest priority)
                new MarshalQueryableParcelable(),
        };

        for (MarshalQueryable query : queryList) {
            MarshalRegistry.registerMarshalQueryable(query);
        }
        if (VERBOSE) {
            Log.v(TAG, "Registered metadata marshalers");
        }
    }

    static {
        /*
         * We use a class initializer to allow the native code to cache some field offsets
         */
        nativeClassInit();
        registerAllMarshalers();
    }

}
