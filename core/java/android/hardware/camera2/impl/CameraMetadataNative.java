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
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.marshal.Marshaler;
import android.hardware.camera2.marshal.MarshalQueryable;
import android.hardware.camera2.marshal.MarshalRegistry;
import android.hardware.camera2.marshal.impl.MarshalQueryableArray;
import android.hardware.camera2.marshal.impl.MarshalQueryableBoolean;
import android.hardware.camera2.marshal.impl.MarshalQueryableBlackLevelPattern;
import android.hardware.camera2.marshal.impl.MarshalQueryableColorSpaceTransform;
import android.hardware.camera2.marshal.impl.MarshalQueryableEnum;
import android.hardware.camera2.marshal.impl.MarshalQueryableHighSpeedVideoConfiguration;
import android.hardware.camera2.marshal.impl.MarshalQueryableMeteringRectangle;
import android.hardware.camera2.marshal.impl.MarshalQueryableNativeByteToInteger;
import android.hardware.camera2.marshal.impl.MarshalQueryablePair;
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
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.HighSpeedVideoConfiguration;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.ReprocessFormatsMap;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.hardware.camera2.utils.TypeReference;
import android.location.Location;
import android.location.LocationManager;
import android.os.Parcelable;
import android.os.Parcel;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Size;

import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Implementation of camera metadata marshal/unmarshal across Binder to
 * the camera service
 */
public class CameraMetadataNative implements Parcelable {

    public static class Key<T> {
        private boolean mHasTag;
        private int mTag;
        private long mVendorId = Long.MAX_VALUE;
        private final Class<T> mType;
        private final TypeReference<T> mTypeReference;
        private final String mName;
        private final int mHash;

        /**
         * @hide
         */
        public Key(String name, Class<T> type, long vendorId) {
            if (name == null) {
                throw new NullPointerException("Key needs a valid name");
            } else if (type == null) {
                throw new NullPointerException("Type needs to be non-null");
            }
            mName = name;
            mType = type;
            mVendorId = vendorId;
            mTypeReference = TypeReference.createSpecializedTypeReference(type);
            mHash = mName.hashCode() ^ mTypeReference.hashCode();
        }

        /**
         * Visible for testing only.
         *
         * <p>Use the CameraCharacteristics.Key, CaptureResult.Key, or CaptureRequest.Key
         * for application code or vendor-extended keys.</p>
         */
        public Key(String name, Class<T> type) {
            if (name == null) {
                throw new NullPointerException("Key needs a valid name");
            } else if (type == null) {
                throw new NullPointerException("Type needs to be non-null");
            }
            mName = name;
            mType = type;
            mTypeReference = TypeReference.createSpecializedTypeReference(type);
            mHash = mName.hashCode() ^ mTypeReference.hashCode();
        }

        /**
         * Visible for testing only.
         *
         * <p>Use the CameraCharacteristics.Key, CaptureResult.Key, or CaptureRequest.Key
         * for application code or vendor-extended keys.</p>
         */
        @SuppressWarnings("unchecked")
        public Key(String name, TypeReference<T> typeReference) {
            if (name == null) {
                throw new NullPointerException("Key needs a valid name");
            } else if (typeReference == null) {
                throw new NullPointerException("TypeReference needs to be non-null");
            }
            mName = name;
            mType = (Class<T>)typeReference.getRawType();
            mTypeReference = typeReference;
            mHash = mName.hashCode() ^ mTypeReference.hashCode();
        }

        /**
         * Return a camelCase, period separated name formatted like:
         * {@code "root.section[.subsections].name"}.
         *
         * <p>Built-in keys exposed by the Android SDK are always prefixed with {@code "android."};
         * keys that are device/platform-specific are prefixed with {@code "com."}.</p>
         *
         * <p>For example, {@code CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP} would
         * have a name of {@code "android.scaler.streamConfigurationMap"}; whereas a device
         * specific key might look like {@code "com.google.nexus.data.private"}.</p>
         *
         * @return String representation of the key name
         */
        public final String getName() {
            return mName;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public final int hashCode() {
            return mHash;
        }

        /**
         * Compare this key against other native keys, request keys, result keys, and
         * characteristics keys.
         *
         * <p>Two keys are considered equal if their name and type reference are equal.</p>
         *
         * <p>Note that the equality against non-native keys is one-way. A native key may be equal
         * to a result key; but that same result key will not be equal to a native key.</p>
         */
        @SuppressWarnings("rawtypes")
        @Override
        public final boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (o == null || this.hashCode() != o.hashCode()) {
                return false;
            }

            Key<?> lhs;

            if (o instanceof CaptureResult.Key) {
                lhs = ((CaptureResult.Key)o).getNativeKey();
            } else if (o instanceof CaptureRequest.Key) {
                lhs = ((CaptureRequest.Key)o).getNativeKey();
            } else if (o instanceof CameraCharacteristics.Key) {
                lhs = ((CameraCharacteristics.Key)o).getNativeKey();
            } else if ((o instanceof Key)) {
                lhs = (Key<?>)o;
            } else {
                return false;
            }

            return mName.equals(lhs.mName) && mTypeReference.equals(lhs.mTypeReference);
        }

        /**
         * <p>
         * Get the tag corresponding to this key. This enables insertion into the
         * native metadata.
         * </p>
         *
         * <p>This value is looked up the first time, and cached subsequently.</p>
         *
         * @return The tag numeric value corresponding to the string
         */
        public final int getTag() {
            if (!mHasTag) {
                mTag = CameraMetadataNative.getTag(mName, mVendorId);
                mHasTag = true;
            }
            return mTag;
        }

        /**
         * Get the raw class backing the type {@code T} for this key.
         *
         * <p>The distinction is only important if {@code T} is a generic, e.g.
         * {@code Range<Integer>} since the nested type will be erased.</p>
         */
        public final Class<T> getType() {
            // TODO: remove this; other places should use #getTypeReference() instead
            return mType;
        }

        /**
         * Get the vendor tag provider id.
         *
         * @hide
         */
        public final long getVendorId() {
            return mVendorId;
        }

        /**
         * Get the type reference backing the type {@code T} for this key.
         *
         * <p>The distinction is only important if {@code T} is a generic, e.g.
         * {@code Range<Integer>} since the nested type will be retained.</p>
         */
        public final TypeReference<T> getTypeReference() {
            return mTypeReference;
        }
    }

    private static final String TAG = "CameraMetadataJV";
    private static final boolean DEBUG = false;

    // this should be in sync with HAL_PIXEL_FORMAT_BLOB defined in graphics.h
    public static final int NATIVE_JPEG_FORMAT = 0x21;

    private static final String CELLID_PROCESS = "CELLID";
    private static final String GPS_PROCESS = "GPS";
    private static final int FACE_LANDMARK_SIZE = 6;

    private static String translateLocationProviderToProcess(final String provider) {
        if (provider == null) {
            return null;
        }
        switch(provider) {
            case LocationManager.GPS_PROVIDER:
                return GPS_PROCESS;
            case LocationManager.NETWORK_PROVIDER:
                return CELLID_PROCESS;
            default:
                return null;
        }
    }

    private static String translateProcessToLocationProvider(final String process) {
        if (process == null) {
            return null;
        }
        switch(process) {
            case GPS_PROCESS:
                return LocationManager.GPS_PROVIDER;
            case CELLID_PROCESS:
                return LocationManager.NETWORK_PROVIDER;
            default:
                return null;
        }
    }

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

    /**
     * Move the contents from {@code other} into a new camera metadata instance.</p>
     *
     * <p>After this call, {@code other} will become empty.</p>
     *
     * @param other the previous metadata instance which will get pilfered
     * @return a new metadata instance with the values from {@code other} moved into it
     */
    public static CameraMetadataNative move(CameraMetadataNative other) {
        CameraMetadataNative newObject = new CameraMetadataNative();
        newObject.swap(other);
        return newObject;
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

    /**
     * @hide
     */
    public <T> T get(CameraCharacteristics.Key<T> key) {
        return get(key.getNativeKey());
    }

    /**
     * @hide
     */
    public <T> T get(CaptureResult.Key<T> key) {
        return get(key.getNativeKey());
    }

    /**
     * @hide
     */
    public <T> T get(CaptureRequest.Key<T> key) {
        return get(key.getNativeKey());
    }

    /**
     * Look-up a metadata field value by its key.
     *
     * @param key a non-{@code null} key instance
     * @return the field corresponding to the {@code key}, or {@code null} if no value was set
     */
    public <T> T get(Key<T> key) {
        Preconditions.checkNotNull(key, "key must not be null");

        // Check if key has been overridden to use a wrapper class on the java side.
        GetCommand g = sGetCommandMap.get(key);
        if (g != null) {
            return g.getValue(this, key);
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
     * @throws ServiceSpecificException
     * @hide
     */
    public static void setupGlobalVendorTagDescriptor() throws ServiceSpecificException {
        int err = nativeSetupGlobalVendorTagDescriptor();
        if (err != 0) {
            throw new ServiceSpecificException(err, "Failure to set up global vendor tags");
        }
    }

    /**
     * Set the global client-side vendor tag descriptor to allow use of vendor
     * tags in camera applications.
     *
     * @return int An error code corresponding to one of the
     * {@link ICameraService} error constants, or 0 on success.
     */
    private static native int nativeSetupGlobalVendorTagDescriptor();

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
        SetCommand s = sSetCommandMap.get(key);
        if (s != null) {
            s.setValue(this, value);
            return;
        }

        setBase(key, value);
    }

    public <T> void set(CaptureRequest.Key<T> key, T value) {
        set(key.getNativeKey(), value);
    }

    public <T> void set(CaptureResult.Key<T> key, T value) {
        set(key.getNativeKey(), value);
    }

    public <T> void set(CameraCharacteristics.Key<T> key, T value) {
        set(key.getNativeKey(), value);
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

    private <T> T getBase(CameraCharacteristics.Key<T> key) {
        return getBase(key.getNativeKey());
    }

    private <T> T getBase(CaptureResult.Key<T> key) {
        return getBase(key.getNativeKey());
    }

    private <T> T getBase(CaptureRequest.Key<T> key) {
        return getBase(key.getNativeKey());
    }

    private <T> T getBase(Key<T> key) {
        int tag = nativeGetTagFromKeyLocal(key.getName());
        byte[] values = readValues(tag);
        if (values == null) {
            return null;
        }

        int nativeType = nativeGetTypeFromTagLocal(tag);
        Marshaler<T> marshaler = getMarshalerForKey(key, nativeType);
        ByteBuffer buffer = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder());
        return marshaler.unmarshal(buffer);
    }

    // Use Command pattern here to avoid lots of expensive if/equals checks in get for overridden
    // metadata.
    private static final HashMap<Key<?>, GetCommand> sGetCommandMap =
            new HashMap<Key<?>, GetCommand>();
    static {
        sGetCommandMap.put(
                CameraCharacteristics.SCALER_AVAILABLE_FORMATS.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getAvailableFormats();
                    }
                });
        sGetCommandMap.put(
                CaptureResult.STATISTICS_FACES.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getFaces();
                    }
                });
        sGetCommandMap.put(
                CaptureResult.STATISTICS_FACE_RECTANGLES.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getFaceRectangles();
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getStreamConfigurationMap();
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.CONTROL_MAX_REGIONS_AE.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMaxRegions(key);
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.CONTROL_MAX_REGIONS_AWB.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMaxRegions(key);
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.CONTROL_MAX_REGIONS_AF.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMaxRegions(key);
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMaxNumOutputs(key);
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMaxNumOutputs(key);
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMaxNumOutputs(key);
                    }
                });
        sGetCommandMap.put(
                CaptureRequest.TONEMAP_CURVE.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getTonemapCurve();
                    }
                });
        sGetCommandMap.put(
                CaptureResult.JPEG_GPS_LOCATION.getNativeKey(), new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getGpsLocation();
                    }
                });
        sGetCommandMap.put(
                CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getLensShadingMap();
                    }
                });
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

    private boolean setFaces(Face[] faces) {
        if (faces == null) {
            return false;
        }

        int numFaces = faces.length;

        // Detect if all faces are SIMPLE or not; count # of valid faces
        boolean fullMode = true;
        for (Face face : faces) {
            if (face == null) {
                numFaces--;
                Log.w(TAG, "setFaces - null face detected, skipping");
                continue;
            }

            if (face.getId() == Face.ID_UNSUPPORTED) {
                fullMode = false;
            }
        }

        Rect[] faceRectangles = new Rect[numFaces];
        byte[] faceScores = new byte[numFaces];
        int[] faceIds = null;
        int[] faceLandmarks = null;

        if (fullMode) {
            faceIds = new int[numFaces];
            faceLandmarks = new int[numFaces * FACE_LANDMARK_SIZE];
        }

        int i = 0;
        for (Face face : faces) {
            if (face == null) {
                continue;
            }

            faceRectangles[i] = face.getBounds();
            faceScores[i] = (byte)face.getScore();

            if (fullMode) {
                faceIds[i] = face.getId();

                int j = 0;

                faceLandmarks[i * FACE_LANDMARK_SIZE + j++] = face.getLeftEyePosition().x;
                faceLandmarks[i * FACE_LANDMARK_SIZE + j++] = face.getLeftEyePosition().y;
                faceLandmarks[i * FACE_LANDMARK_SIZE + j++] = face.getRightEyePosition().x;
                faceLandmarks[i * FACE_LANDMARK_SIZE + j++] = face.getRightEyePosition().y;
                faceLandmarks[i * FACE_LANDMARK_SIZE + j++] = face.getMouthPosition().x;
                faceLandmarks[i * FACE_LANDMARK_SIZE + j++] = face.getMouthPosition().y;
            }

            i++;
        }

        set(CaptureResult.STATISTICS_FACE_RECTANGLES, faceRectangles);
        set(CaptureResult.STATISTICS_FACE_IDS, faceIds);
        set(CaptureResult.STATISTICS_FACE_LANDMARKS, faceLandmarks);
        set(CaptureResult.STATISTICS_FACE_SCORES, faceScores);

        return true;
    }

    private Face[] getFaces() {
        Integer faceDetectMode = get(CaptureResult.STATISTICS_FACE_DETECT_MODE);
        byte[] faceScores = get(CaptureResult.STATISTICS_FACE_SCORES);
        Rect[] faceRectangles = get(CaptureResult.STATISTICS_FACE_RECTANGLES);
        int[] faceIds = get(CaptureResult.STATISTICS_FACE_IDS);
        int[] faceLandmarks = get(CaptureResult.STATISTICS_FACE_LANDMARKS);

        if (areValuesAllNull(faceDetectMode, faceScores, faceRectangles, faceIds, faceLandmarks)) {
            return null;
        }

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
                    Point leftEye = new Point(faceLandmarks[i*FACE_LANDMARK_SIZE],
                            faceLandmarks[i*FACE_LANDMARK_SIZE+1]);
                    Point rightEye = new Point(faceLandmarks[i*FACE_LANDMARK_SIZE+2],
                            faceLandmarks[i*FACE_LANDMARK_SIZE+3]);
                    Point mouth = new Point(faceLandmarks[i*FACE_LANDMARK_SIZE+4],
                            faceLandmarks[i*FACE_LANDMARK_SIZE+5]);
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

    private LensShadingMap getLensShadingMap() {
        float[] lsmArray = getBase(CaptureResult.STATISTICS_LENS_SHADING_MAP);
        Size s = get(CameraCharacteristics.LENS_INFO_SHADING_MAP_SIZE);

        // Do not warn if lsmArray is null while s is not. This is valid.
        if (lsmArray == null) {
            return null;
        }

        if (s == null) {
            Log.w(TAG, "getLensShadingMap - Lens shading map size was null.");
            return null;
        }

        LensShadingMap map = new LensShadingMap(lsmArray, s.getHeight(), s.getWidth());
        return map;
    }

    private Location getGpsLocation() {
        String processingMethod = get(CaptureResult.JPEG_GPS_PROCESSING_METHOD);
        double[] coords = get(CaptureResult.JPEG_GPS_COORDINATES);
        Long timeStamp = get(CaptureResult.JPEG_GPS_TIMESTAMP);

        if (areValuesAllNull(processingMethod, coords, timeStamp)) {
            return null;
        }

        Location l = new Location(translateProcessToLocationProvider(processingMethod));
        if (timeStamp != null) {
            // Location expects timestamp in [ms.]
            l.setTime(timeStamp * 1000);
        } else {
            Log.w(TAG, "getGpsLocation - No timestamp for GPS location.");
        }

        if (coords != null) {
            l.setLatitude(coords[0]);
            l.setLongitude(coords[1]);
            l.setAltitude(coords[2]);
        } else {
            Log.w(TAG, "getGpsLocation - No coordinates for GPS location");
        }

        return l;
    }

    private boolean setGpsLocation(Location l) {
        if (l == null) {
            return false;
        }

        double[] coords = { l.getLatitude(), l.getLongitude(), l.getAltitude() };
        String processMethod = translateLocationProviderToProcess(l.getProvider());
        //JPEG_GPS_TIMESTAMP expects sec. instead of msec.
        long timestamp = l.getTime() / 1000;

        set(CaptureRequest.JPEG_GPS_TIMESTAMP, timestamp);
        set(CaptureRequest.JPEG_GPS_COORDINATES, coords);

        if (processMethod == null) {
            Log.w(TAG, "setGpsLocation - No process method, Location is not from a GPS or NETWORK" +
                    "provider");
        } else {
            setBase(CaptureRequest.JPEG_GPS_PROCESSING_METHOD, processMethod);
        }
        return true;
    }

    private StreamConfigurationMap getStreamConfigurationMap() {
        StreamConfiguration[] configurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_STREAM_CONFIGURATIONS);
        StreamConfigurationDuration[] minFrameDurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_MIN_FRAME_DURATIONS);
        StreamConfigurationDuration[] stallDurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_STALL_DURATIONS);
        StreamConfiguration[] depthConfigurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DEPTH_STREAM_CONFIGURATIONS);
        StreamConfigurationDuration[] depthMinFrameDurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DEPTH_MIN_FRAME_DURATIONS);
        StreamConfigurationDuration[] depthStallDurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DEPTH_STALL_DURATIONS);
        HighSpeedVideoConfiguration[] highSpeedVideoConfigurations = getBase(
                CameraCharacteristics.CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS);
        ReprocessFormatsMap inputOutputFormatsMap = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP);
        int[] capabilities = getBase(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean listHighResolution = false;
        for (int capability : capabilities) {
            if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE) {
                listHighResolution = true;
                break;
            }
        }
        return new StreamConfigurationMap(
                configurations, minFrameDurations, stallDurations,
                depthConfigurations, depthMinFrameDurations, depthStallDurations,
                highSpeedVideoConfigurations, inputOutputFormatsMap,
                listHighResolution);
    }

    private <T> Integer getMaxRegions(Key<T> key) {
        final int AE = 0;
        final int AWB = 1;
        final int AF = 2;

        // The order of the elements is: (AE, AWB, AF)
        int[] maxRegions = getBase(CameraCharacteristics.CONTROL_MAX_REGIONS);

        if (maxRegions == null) {
            return null;
        }

        if (key.equals(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)) {
            return maxRegions[AE];
        } else if (key.equals(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB)) {
            return maxRegions[AWB];
        } else if (key.equals(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)) {
            return maxRegions[AF];
        } else {
            throw new AssertionError("Invalid key " + key);
        }
    }

    private <T> Integer getMaxNumOutputs(Key<T> key) {
        final int RAW = 0;
        final int PROC = 1;
        final int PROC_STALLING = 2;

        // The order of the elements is: (raw, proc+nonstalling, proc+stalling)
        int[] maxNumOutputs = getBase(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_STREAMS);

        if (maxNumOutputs == null) {
            return null;
        }

        if (key.equals(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW)) {
            return maxNumOutputs[RAW];
        } else if (key.equals(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC)) {
            return maxNumOutputs[PROC];
        } else if (key.equals(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING)) {
            return maxNumOutputs[PROC_STALLING];
        } else {
            throw new AssertionError("Invalid key " + key);
        }
    }

    private <T> TonemapCurve getTonemapCurve() {
        float[] red = getBase(CaptureRequest.TONEMAP_CURVE_RED);
        float[] green = getBase(CaptureRequest.TONEMAP_CURVE_GREEN);
        float[] blue = getBase(CaptureRequest.TONEMAP_CURVE_BLUE);

        if (areValuesAllNull(red, green, blue)) {
            return null;
        }

        if (red == null || green == null || blue == null) {
            Log.w(TAG, "getTonemapCurve - missing tone curve components");
            return null;
        }
        TonemapCurve tc = new TonemapCurve(red, green, blue);
        return tc;
    }

    private <T> void setBase(CameraCharacteristics.Key<T> key, T value) {
        setBase(key.getNativeKey(), value);
    }

    private <T> void setBase(CaptureResult.Key<T> key, T value) {
        setBase(key.getNativeKey(), value);
    }

    private <T> void setBase(CaptureRequest.Key<T> key, T value) {
        setBase(key.getNativeKey(), value);
    }

    private <T> void setBase(Key<T> key, T value) {
        int tag = nativeGetTagFromKeyLocal(key.getName());
        if (value == null) {
            // Erase the entry
            writeValues(tag, /*src*/null);
            return;
        } // else update the entry to a new value

        int nativeType = nativeGetTypeFromTagLocal(tag);
        Marshaler<T> marshaler = getMarshalerForKey(key, nativeType);
        int size = marshaler.calculateMarshalSize(value);

        // TODO: Optimization. Cache the byte[] and reuse if the size is big enough.
        byte[] values = new byte[size];

        ByteBuffer buffer = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder());
        marshaler.marshal(value, buffer);

        writeValues(tag, values);
    }

    // Use Command pattern here to avoid lots of expensive if/equals checks in get for overridden
    // metadata.
    private static final HashMap<Key<?>, SetCommand> sSetCommandMap =
            new HashMap<Key<?>, SetCommand>();
    static {
        sSetCommandMap.put(CameraCharacteristics.SCALER_AVAILABLE_FORMATS.getNativeKey(),
                new SetCommand() {
            @Override
            public <T> void setValue(CameraMetadataNative metadata, T value) {
                metadata.setAvailableFormats((int[]) value);
            }
        });
        sSetCommandMap.put(CaptureResult.STATISTICS_FACE_RECTANGLES.getNativeKey(),
                new SetCommand() {
            @Override
            public <T> void setValue(CameraMetadataNative metadata, T value) {
                metadata.setFaceRectangles((Rect[]) value);
            }
        });
        sSetCommandMap.put(CaptureResult.STATISTICS_FACES.getNativeKey(),
                new SetCommand() {
            @Override
            public <T> void setValue(CameraMetadataNative metadata, T value) {
                metadata.setFaces((Face[])value);
            }
        });
        sSetCommandMap.put(CaptureRequest.TONEMAP_CURVE.getNativeKey(), new SetCommand() {
            @Override
            public <T> void setValue(CameraMetadataNative metadata, T value) {
                metadata.setTonemapCurve((TonemapCurve) value);
            }
        });
        sSetCommandMap.put(CaptureResult.JPEG_GPS_LOCATION.getNativeKey(), new SetCommand() {
            @Override
            public <T> void setValue(CameraMetadataNative metadata, T value) {
                metadata.setGpsLocation((Location) value);
            }
        });
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

    private <T> boolean setTonemapCurve(TonemapCurve tc) {
        if (tc == null) {
            return false;
        }

        float[][] curve = new float[3][];
        for (int i = TonemapCurve.CHANNEL_RED; i <= TonemapCurve.CHANNEL_BLUE; i++) {
            int pointCount = tc.getPointCount(i);
            curve[i] = new float[pointCount * TonemapCurve.POINT_SIZE];
            tc.copyColorCurve(i, curve[i], 0);
        }
        setBase(CaptureRequest.TONEMAP_CURVE_RED, curve[0]);
        setBase(CaptureRequest.TONEMAP_CURVE_GREEN, curve[1]);
        setBase(CaptureRequest.TONEMAP_CURVE_BLUE, curve[2]);

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
    private native synchronized void nativeDump() throws IOException; // dump to ALOGD

    private native synchronized ArrayList nativeGetAllVendorKeys(Class keyClass);
    private native synchronized int nativeGetTagFromKeyLocal(String keyName)
            throws IllegalArgumentException;
    private native synchronized int nativeGetTypeFromTagLocal(int tag)
            throws IllegalArgumentException;
    private static native int nativeGetTagFromKey(String keyName, long vendorId)
            throws IllegalArgumentException;
    private static native int nativeGetTypeFromTag(int tag, long vendorId)
            throws IllegalArgumentException;

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
     * Return a list containing keys of the given key class for all defined vendor tags.
     *
     * @hide
     */
    public <K>  ArrayList<K> getAllVendorKeys(Class<K> keyClass) {
        if (keyClass == null) {
            throw new NullPointerException();
        }
        return (ArrayList<K>) nativeGetAllVendorKeys(keyClass);
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
        return nativeGetTagFromKey(key, Long.MAX_VALUE);
    }

    /**
     * Convert a key string into the equivalent native tag.
     *
     * @throws IllegalArgumentException if the key was not recognized
     * @throws NullPointerException if the key was null
     *
     * @hide
     */
    public static int getTag(String key, long vendorId) {
        return nativeGetTagFromKey(key, vendorId);
    }

    /**
     * Get the underlying native type for a tag.
     *
     * @param tag An integer tag, see e.g. {@link #getTag}
     * @param vendorId A vendor tag provider id
     * @return An int enum for the metadata type, see e.g. {@link #TYPE_BYTE}
     *
     * @hide
     */
    public static int getNativeType(int tag, long vendorId) {
        return nativeGetTypeFromTag(tag, vendorId);
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

    /**
     * Dumps the native metadata contents to logcat.
     *
     * <p>Visibility for testing/debugging only. The results will not
     * include any synthesized keys, as they are invisible to the native layer.</p>
     *
     * @hide
     */
    public void dumpToLog() {
        try {
            nativeDump();
        } catch (IOException e) {
            Log.wtf(TAG, "Dump logging failed", e);
        }
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
    private static <T> Marshaler<T> getMarshalerForKey(Key<T> key, int nativeType) {
        return MarshalRegistry.getMarshaler(key.getTypeReference(),
                nativeType);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void registerAllMarshalers() {
        if (DEBUG) {
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
                new MarshalQueryablePair(),
                new MarshalQueryableMeteringRectangle(),
                new MarshalQueryableColorSpaceTransform(),
                new MarshalQueryableStreamConfiguration(),
                new MarshalQueryableStreamConfigurationDuration(),
                new MarshalQueryableRggbChannelVector(),
                new MarshalQueryableBlackLevelPattern(),
                new MarshalQueryableHighSpeedVideoConfiguration(),

                // generic parcelable marshaler (MUST BE LAST since it has lowest priority)
                new MarshalQueryableParcelable(),
        };

        for (MarshalQueryable query : queryList) {
            MarshalRegistry.registerMarshalQueryable(query);
        }
        if (DEBUG) {
            Log.v(TAG, "Registered metadata marshalers");
        }
    }

    /** Check if input arguments are all {@code null}.
     *
     * @param objs Input arguments for null check
     * @return {@code true} if input arguments are all {@code null}, otherwise {@code false}
     */
    private static boolean areValuesAllNull(Object... objs) {
        for (Object o : objs) {
            if (o != null) return false;
        }
        return true;
    }

    static {
        registerAllMarshalers();
    }
}
