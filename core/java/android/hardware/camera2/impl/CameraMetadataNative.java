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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.marshal.MarshalQueryable;
import android.hardware.camera2.marshal.MarshalRegistry;
import android.hardware.camera2.marshal.Marshaler;
import android.hardware.camera2.marshal.impl.MarshalQueryableArray;
import android.hardware.camera2.marshal.impl.MarshalQueryableBlackLevelPattern;
import android.hardware.camera2.marshal.impl.MarshalQueryableBoolean;
import android.hardware.camera2.marshal.impl.MarshalQueryableColorSpaceTransform;
import android.hardware.camera2.marshal.impl.MarshalQueryableEnum;
import android.hardware.camera2.marshal.impl.MarshalQueryableHighSpeedVideoConfiguration;
import android.hardware.camera2.marshal.impl.MarshalQueryableMeteringRectangle;
import android.hardware.camera2.marshal.impl.MarshalQueryableNativeByteToInteger;
import android.hardware.camera2.marshal.impl.MarshalQueryablePair;
import android.hardware.camera2.marshal.impl.MarshalQueryableParcelable;
import android.hardware.camera2.marshal.impl.MarshalQueryablePrimitive;
import android.hardware.camera2.marshal.impl.MarshalQueryableRange;
import android.hardware.camera2.marshal.impl.MarshalQueryableRecommendedStreamConfiguration;
import android.hardware.camera2.marshal.impl.MarshalQueryableRect;
import android.hardware.camera2.marshal.impl.MarshalQueryableReprocessFormatsMap;
import android.hardware.camera2.marshal.impl.MarshalQueryableRggbChannelVector;
import android.hardware.camera2.marshal.impl.MarshalQueryableSize;
import android.hardware.camera2.marshal.impl.MarshalQueryableSizeF;
import android.hardware.camera2.marshal.impl.MarshalQueryableStreamConfiguration;
import android.hardware.camera2.marshal.impl.MarshalQueryableStreamConfigurationDuration;
import android.hardware.camera2.marshal.impl.MarshalQueryableString;
import android.hardware.camera2.params.Capability;
import android.hardware.camera2.params.ColorSpaceProfiles;
import android.hardware.camera2.params.DeviceStateSensorOrientationMap;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.HighSpeedVideoConfiguration;
import android.hardware.camera2.params.LensIntrinsicsSample;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.MandatoryStreamCombination;
import android.hardware.camera2.params.MultiResolutionStreamConfigurationMap;
import android.hardware.camera2.params.OisSample;
import android.hardware.camera2.params.RecommendedStreamConfiguration;
import android.hardware.camera2.params.RecommendedStreamConfigurationMap;
import android.hardware.camera2.params.ReprocessFormatsMap;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.hardware.camera2.utils.ArrayUtils;
import android.hardware.camera2.utils.TypeReference;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import dalvik.annotation.optimization.FastNative;
import dalvik.system.VMRuntime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        private final String mFallbackName;
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
            mFallbackName = null;
            mType = type;
            mVendorId = vendorId;
            mTypeReference = TypeReference.createSpecializedTypeReference(type);
            mHash = mName.hashCode() ^ mTypeReference.hashCode();
        }

        /**
         * @hide
         */
        public Key(String name, String fallbackName, Class<T> type) {
            if (name == null) {
                throw new NullPointerException("Key needs a valid name");
            } else if (type == null) {
                throw new NullPointerException("Type needs to be non-null");
            }
            mName = name;
            mFallbackName = fallbackName;
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
        public Key(String name, Class<T> type) {
            if (name == null) {
                throw new NullPointerException("Key needs a valid name");
            } else if (type == null) {
                throw new NullPointerException("Type needs to be non-null");
            }
            mName = name;
            mFallbackName = null;
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
            mFallbackName = null;
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
         * <p>This function may be called without cacheTag() if this is not a vendor key.
         * If this is a vendor key, cacheTag() must be called first before getTag() can
         * be called. Otherwise, mVendorId could be default (Long.MAX_VALUE) and vendor
         * tag lookup could fail.</p>
         *
         * @return The tag numeric value corresponding to the string
         */
        @UnsupportedAppUsage
        public final int getTag() {
            if (!mHasTag) {
                mTag = CameraMetadataNative.getTag(mName, mVendorId);
                mHasTag = true;
            }
            return mTag;
        }

        /**
         * Whether this key's tag is cached.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public final boolean hasTag() {
            return mHasTag;
        }

        /**
         * Cache this key's tag.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public final void cacheTag(int tag) {
            mHasTag = true;
            mTag = tag;
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

    private static final int MANDATORY_STREAM_CONFIGURATIONS_DEFAULT = 0;
    private static final int MANDATORY_STREAM_CONFIGURATIONS_MAX_RESOLUTION = 1;
    private static final int MANDATORY_STREAM_CONFIGURATIONS_CONCURRENT = 2;
    private static final int MANDATORY_STREAM_CONFIGURATIONS_10BIT = 3;
    private static final int MANDATORY_STREAM_CONFIGURATIONS_USE_CASE = 4;
    private static final int MANDATORY_STREAM_CONFIGURATIONS_PREVIEW_STABILIZATION = 5;

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
        updateNativeAllocation();
    }

    /**
     * Copy constructor - clone metadata
     */
    public CameraMetadataNative(CameraMetadataNative other) {
        super();
        mMetadataPtr = nativeAllocateCopy(other.mMetadataPtr);
        if (mMetadataPtr == 0) {
            throw new OutOfMemoryError("Failed to allocate native CameraMetadata");
        }
        updateNativeAllocation();
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

    /**
     * Set all metadata values in the destination argument by using the corresponding
     * values from the source. Metadata tags present in the destination and absent
     * from the source will remain unmodified.
     *
     * @param dst Destination metadata
     * @param src Source metadata
     * @hide
     */
    public static void update(CameraMetadataNative dst, CameraMetadataNative src) {
        nativeUpdate(dst.mMetadataPtr, src.mMetadataPtr);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CameraMetadataNative> CREATOR =
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
        nativeWriteToParcel(dest, mMetadataPtr);
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
        Objects.requireNonNull(key, "key must not be null");

        // Check if key has been overridden to use a wrapper class on the java side.
        GetCommand g = sGetCommandMap.get(key);
        if (g != null) {
            return g.getValue(this, key);
        }
        return getBase(key);
    }

    public void readFromParcel(Parcel in) {
        nativeReadFromParcel(in, mMetadataPtr);
        updateNativeAllocation();
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
        // Delete native pointer, but does not clear it
        nativeClose(mMetadataPtr);
        mMetadataPtr = 0;

        if (mBufferSize > 0) {
            VMRuntime.getRuntime().registerNativeFree(mBufferSize);
        }
        mBufferSize = 0;
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
        int tag;
        if (key.hasTag()) {
            tag = key.getTag();
        } else {
            tag = nativeGetTagFromKeyLocal(mMetadataPtr, key.getName());
            key.cacheTag(tag);
        }
        byte[] values = readValues(tag);
        if (values == null) {
            // If the key returns null, use the fallback key if exists.
            // This is to support old key names for the newly published keys.
            if (key.mFallbackName == null) {
                return null;
            }
            tag = nativeGetTagFromKeyLocal(mMetadataPtr, key.mFallbackName);
            values = readValues(tag);
            if (values == null) {
                return null;
            }
        }

        int nativeType = nativeGetTypeFromTagLocal(mMetadataPtr, tag);
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
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getStreamConfigurationMapMaximumResolution();
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.SCALER_MANDATORY_STREAM_COMBINATIONS.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMandatoryStreamCombinations();
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMandatoryConcurrentStreamCombinations();
                    }
                });

        sGetCommandMap.put(
                CameraCharacteristics.SCALER_MANDATORY_TEN_BIT_OUTPUT_STREAM_COMBINATIONS.getNativeKey(),
                new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMandatory10BitStreamCombinations();
                    }
                });

        sGetCommandMap.put(
                CameraCharacteristics.SCALER_MANDATORY_MAXIMUM_RESOLUTION_STREAM_COMBINATIONS.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMandatoryMaximumResolutionStreamCombinations();
                    }
                });

        sGetCommandMap.put(
                CameraCharacteristics.SCALER_MANDATORY_USE_CASE_STREAM_COMBINATIONS.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMandatoryUseCaseStreamCombinations();
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.SCALER_MANDATORY_PREVIEW_STABILIZATION_OUTPUT_STREAM_COMBINATIONS.getNativeKey(),
                new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMandatoryPreviewStabilizationStreamCombinations();
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
        sGetCommandMap.put(
                CameraCharacteristics.INFO_DEVICE_STATE_SENSOR_ORIENTATION_MAP.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getDeviceStateOrientationMap();
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getDynamicRangeProfiles();
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getColorSpaceProfiles();
                    }
                });
        sGetCommandMap.put(
                CaptureResult.STATISTICS_OIS_SAMPLES.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getOisSamples();
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getExtendedSceneModeCapabilities();
                    }
                });
        sGetCommandMap.put(
                CameraCharacteristics.SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP.getNativeKey(),
                        new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getMultiResolutionStreamConfigurationMap();
                    }
                });
        sGetCommandMap.put(
                CaptureResult.STATISTICS_LENS_INTRINSICS_SAMPLES.getNativeKey(),
                new GetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getValue(CameraMetadataNative metadata, Key<T> key) {
                        return (T) metadata.getLensIntrinsicSamples();
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
        } else if (faceDetectMode > CaptureResult.STATISTICS_FACE_DETECT_MODE_FULL) {
            // Face detect mode is larger than FULL, assuming the mode is FULL
            faceDetectMode = CaptureResult.STATISTICS_FACE_DETECT_MODE_FULL;
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

    private boolean setLensShadingMap(LensShadingMap lensShadingMap) {
        if (lensShadingMap == null) {
            return false;
        }
        float[] lsmArray = new float[lensShadingMap.getGainFactorCount()];
        lensShadingMap.copyGainFactors(lsmArray, 0);
        setBase(CaptureResult.STATISTICS_LENS_SHADING_MAP, lsmArray);

        Size s = new Size(lensShadingMap.getRowCount(), lensShadingMap.getColumnCount());
        setBase(CameraCharacteristics.LENS_INFO_SHADING_MAP_SIZE, s);
        return true;
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

    private DeviceStateSensorOrientationMap getDeviceStateOrientationMap() {
        long[] mapArray = getBase(CameraCharacteristics.INFO_DEVICE_STATE_ORIENTATIONS);

        // Do not warn if map is null while s is not. This is valid.
        if (mapArray == null) {
            return null;
        }

        DeviceStateSensorOrientationMap map = new DeviceStateSensorOrientationMap(mapArray);
        return map;
    }

    private DynamicRangeProfiles getDynamicRangeProfiles() {
        long[] profileArray = getBase(
                CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP);

        if (profileArray == null) {
            return null;
        }

        return new DynamicRangeProfiles(profileArray);
    }

    private ColorSpaceProfiles getColorSpaceProfiles() {
        long[] profileArray = getBase(
                CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP);

        if (profileArray == null) {
            return null;
        }

        return new ColorSpaceProfiles(profileArray);
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
            // If Location value being set is null, remove corresponding keys.
            // This is safe because api1/client2/CameraParameters.cpp already erases
            // the keys for JPEG_GPS_LOCATION for certain cases.
            setBase(CaptureRequest.JPEG_GPS_TIMESTAMP, null);
            setBase(CaptureRequest.JPEG_GPS_COORDINATES, null);
            setBase(CaptureRequest.JPEG_GPS_PROCESSING_METHOD, null);
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

    private void parseRecommendedConfigurations(RecommendedStreamConfiguration[] configurations,
            StreamConfigurationMap fullMap, boolean isDepth,
            ArrayList<ArrayList<StreamConfiguration>> /*out*/streamConfigList,
            ArrayList<ArrayList<StreamConfigurationDuration>> /*out*/streamDurationList,
            ArrayList<ArrayList<StreamConfigurationDuration>> /*out*/streamStallList,
            boolean[] /*out*/supportsPrivate) {

        streamConfigList.ensureCapacity(RecommendedStreamConfigurationMap.MAX_USECASE_COUNT);
        streamDurationList.ensureCapacity(RecommendedStreamConfigurationMap.MAX_USECASE_COUNT);
        streamStallList.ensureCapacity(RecommendedStreamConfigurationMap.MAX_USECASE_COUNT);
        for (int i = 0; i < RecommendedStreamConfigurationMap.MAX_USECASE_COUNT; i++) {
            streamConfigList.add(new ArrayList<StreamConfiguration> ());
            streamDurationList.add(new ArrayList<StreamConfigurationDuration> ());
            streamStallList.add(new ArrayList<StreamConfigurationDuration> ());
        }

        for (RecommendedStreamConfiguration c : configurations) {
            int width = c.getWidth();
            int height = c.getHeight();
            int internalFormat = c.getFormat();
            int publicFormat =
                (isDepth) ? StreamConfigurationMap.depthFormatToPublic(internalFormat) :
                StreamConfigurationMap.imageFormatToPublic(internalFormat);
            Size sz = new Size(width, height);
            int usecaseBitmap = c.getUsecaseBitmap();

            if (!c.isInput()) {
                StreamConfigurationDuration minDurationConfiguration = null;
                StreamConfigurationDuration stallDurationConfiguration = null;

                StreamConfiguration streamConfiguration = new StreamConfiguration(internalFormat,
                        width, height, /*input*/ false);

                long minFrameDuration = fullMap.getOutputMinFrameDuration(publicFormat, sz);
                if (minFrameDuration > 0) {
                    minDurationConfiguration = new StreamConfigurationDuration(internalFormat,
                            width, height, minFrameDuration);
                }

                long stallDuration = fullMap.getOutputStallDuration(publicFormat, sz);
                if (stallDuration > 0) {
                    stallDurationConfiguration = new StreamConfigurationDuration(internalFormat,
                            width, height, stallDuration);
                }

                for (int i = 0; i < RecommendedStreamConfigurationMap.MAX_USECASE_COUNT; i++) {
                    if ((usecaseBitmap & (1 << i)) != 0) {
                        ArrayList<StreamConfiguration> sc = streamConfigList.get(i);
                        sc.add(streamConfiguration);

                        if (minFrameDuration > 0) {
                            ArrayList<StreamConfigurationDuration> scd = streamDurationList.get(i);
                            scd.add(minDurationConfiguration);
                        }

                        if (stallDuration > 0) {
                            ArrayList<StreamConfigurationDuration> scs = streamStallList.get(i);
                            scs.add(stallDurationConfiguration);
                        }

                        if ((supportsPrivate != null) && !supportsPrivate[i] &&
                                (publicFormat == ImageFormat.PRIVATE)) {
                            supportsPrivate[i] = true;
                        }
                    }
                }
            } else {
                if (usecaseBitmap != (1 << RecommendedStreamConfigurationMap.USECASE_ZSL)) {
                    throw new IllegalArgumentException("Recommended input stream configurations " +
                            "should only be advertised in the ZSL use case!");
                }

                ArrayList<StreamConfiguration> sc = streamConfigList.get(
                        RecommendedStreamConfigurationMap.USECASE_ZSL);
                sc.add(new StreamConfiguration(internalFormat,
                        width, height, /*input*/ true));
            }
        }
    }

    private class StreamConfigurationData {
        StreamConfiguration [] streamConfigurationArray = null;
        StreamConfigurationDuration [] minDurationArray = null;
        StreamConfigurationDuration [] stallDurationArray = null;
    }

    public void initializeStreamConfigurationData(ArrayList<StreamConfiguration> sc,
            ArrayList<StreamConfigurationDuration> scd, ArrayList<StreamConfigurationDuration> scs,
            StreamConfigurationData /*out*/scData) {
        if ((scData == null) || (sc == null)) {
            return;
        }

        scData.streamConfigurationArray = new StreamConfiguration[sc.size()];
        scData.streamConfigurationArray = sc.toArray(scData.streamConfigurationArray);

        if ((scd != null) && !scd.isEmpty()) {
            scData.minDurationArray = new StreamConfigurationDuration[scd.size()];
            scData.minDurationArray = scd.toArray(scData.minDurationArray);
        } else {
            scData.minDurationArray = new StreamConfigurationDuration[0];
        }

        if ((scs != null) && !scs.isEmpty()) {
            scData.stallDurationArray = new StreamConfigurationDuration[scs.size()];
            scData.stallDurationArray = scs.toArray(scData.stallDurationArray);
        } else {
            scData.stallDurationArray = new StreamConfigurationDuration[0];
        }
    }

    /**
     * Retrieve the list of recommended stream configurations.
     *
     * @return A list of recommended stream configuration maps for each common use case or null
     *         in case the recommended stream configurations are invalid or incomplete.
     * @hide
     */
    public ArrayList<RecommendedStreamConfigurationMap> getRecommendedStreamConfigurations() {
        RecommendedStreamConfiguration[] configurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_RECOMMENDED_STREAM_CONFIGURATIONS);
        RecommendedStreamConfiguration[] depthConfigurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_RECOMMENDED_DEPTH_STREAM_CONFIGURATIONS);
        if ((configurations == null) && (depthConfigurations == null)) {
            return null;
        }

        StreamConfigurationMap fullMap = getStreamConfigurationMap();
        ArrayList<RecommendedStreamConfigurationMap> recommendedConfigurations =
            new ArrayList<RecommendedStreamConfigurationMap> ();

        ArrayList<ArrayList<StreamConfiguration>> streamConfigList =
            new ArrayList<ArrayList<StreamConfiguration>>();
        ArrayList<ArrayList<StreamConfigurationDuration>> streamDurationList =
            new ArrayList<ArrayList<StreamConfigurationDuration>>();
        ArrayList<ArrayList<StreamConfigurationDuration>> streamStallList =
            new ArrayList<ArrayList<StreamConfigurationDuration>>();
        boolean[] supportsPrivate =
                new boolean[RecommendedStreamConfigurationMap.MAX_USECASE_COUNT];
        try {
            if (configurations != null) {
                parseRecommendedConfigurations(configurations, fullMap, /*isDepth*/ false,
                        streamConfigList, streamDurationList, streamStallList, supportsPrivate);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed parsing the recommended stream configurations!");
            return null;
        }

        ArrayList<ArrayList<StreamConfiguration>> depthStreamConfigList =
            new ArrayList<ArrayList<StreamConfiguration>>();
        ArrayList<ArrayList<StreamConfigurationDuration>> depthStreamDurationList =
            new ArrayList<ArrayList<StreamConfigurationDuration>>();
        ArrayList<ArrayList<StreamConfigurationDuration>> depthStreamStallList =
            new ArrayList<ArrayList<StreamConfigurationDuration>>();
        if (depthConfigurations != null) {
            try {
                parseRecommendedConfigurations(depthConfigurations, fullMap, /*isDepth*/ true,
                        depthStreamConfigList, depthStreamDurationList, depthStreamStallList,
                        /*supportsPrivate*/ null);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed parsing the recommended depth stream configurations!");
                return null;
            }
        }

        ReprocessFormatsMap inputOutputFormatsMap = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_RECOMMENDED_INPUT_OUTPUT_FORMATS_MAP);
        HighSpeedVideoConfiguration[] highSpeedVideoConfigurations = getBase(
                CameraCharacteristics.CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS);
        boolean listHighResolution = isBurstSupported();
        recommendedConfigurations.ensureCapacity(
                RecommendedStreamConfigurationMap.MAX_USECASE_COUNT);
        for (int i = 0; i < RecommendedStreamConfigurationMap.MAX_USECASE_COUNT; i++) {
            StreamConfigurationData scData = new StreamConfigurationData();
            if (configurations != null) {
                initializeStreamConfigurationData(streamConfigList.get(i),
                        streamDurationList.get(i), streamStallList.get(i), scData);
            }

            StreamConfigurationData depthScData = new StreamConfigurationData();
            if (depthConfigurations != null) {
                initializeStreamConfigurationData(depthStreamConfigList.get(i),
                        depthStreamDurationList.get(i), depthStreamStallList.get(i), depthScData);
            }

            if ((scData.streamConfigurationArray == null ||
                    scData.streamConfigurationArray.length == 0) &&
                    (depthScData.streamConfigurationArray == null ||
                     depthScData.streamConfigurationArray.length == 0)) {
                recommendedConfigurations.add(null);
                continue;
            }

            // Dynamic depth streams involve alot of SW processing and currently cannot be
            // recommended.
            StreamConfigurationMap map = null;
            switch (i) {
                case RecommendedStreamConfigurationMap.USECASE_PREVIEW:
                case RecommendedStreamConfigurationMap.USECASE_RAW:
                case RecommendedStreamConfigurationMap.USECASE_LOW_LATENCY_SNAPSHOT:
                case RecommendedStreamConfigurationMap.USECASE_VIDEO_SNAPSHOT:
                    map = new StreamConfigurationMap(scData.streamConfigurationArray,
                            scData.minDurationArray, scData.stallDurationArray,
                            /*depthconfiguration*/ null, /*depthminduration*/ null,
                            /*depthstallduration*/ null,
                            /*dynamicDepthConfigurations*/ null,
                            /*dynamicDepthMinFrameDurations*/ null,
                            /*dynamicDepthStallDurations*/ null,
                            /*heicconfiguration*/ null,
                            /*heicminduration*/ null,
                            /*heicstallduration*/ null,
                            /*jpegRconfiguration*/ null,
                            /*jpegRminduration*/ null,
                            /*jpegRstallduration*/ null,
                            /*highspeedvideoconfigurations*/ null,
                            /*inputoutputformatsmap*/ null, listHighResolution, supportsPrivate[i]);
                    break;
                case RecommendedStreamConfigurationMap.USECASE_RECORD:
                    map = new StreamConfigurationMap(scData.streamConfigurationArray,
                            scData.minDurationArray, scData.stallDurationArray,
                            /*depthconfiguration*/ null, /*depthminduration*/ null,
                            /*depthstallduration*/ null,
                            /*dynamicDepthConfigurations*/ null,
                            /*dynamicDepthMinFrameDurations*/ null,
                            /*dynamicDepthStallDurations*/ null,
                            /*heicconfiguration*/ null,
                            /*heicminduration*/ null,
                            /*heicstallduration*/ null,
                            /*jpegRconfiguration*/ null,
                            /*jpegRminduration*/ null,
                            /*jpegRstallduration*/ null,
                            highSpeedVideoConfigurations,
                            /*inputoutputformatsmap*/ null, listHighResolution, supportsPrivate[i]);
                    break;
                case RecommendedStreamConfigurationMap.USECASE_ZSL:
                    map = new StreamConfigurationMap(scData.streamConfigurationArray,
                            scData.minDurationArray, scData.stallDurationArray,
                            depthScData.streamConfigurationArray, depthScData.minDurationArray,
                            depthScData.stallDurationArray,
                            /*dynamicDepthConfigurations*/ null,
                            /*dynamicDepthMinFrameDurations*/ null,
                            /*dynamicDepthStallDurations*/ null,
                            /*heicconfiguration*/ null,
                            /*heicminduration*/ null,
                            /*heicstallduration*/ null,
                            /*jpegRconfiguration*/ null,
                            /*jpegRminduration*/ null,
                            /*jpegRstallduration*/ null,
                            /*highSpeedVideoConfigurations*/ null,
                            inputOutputFormatsMap, listHighResolution, supportsPrivate[i]);
                    break;
                default:
                    map = new StreamConfigurationMap(scData.streamConfigurationArray,
                            scData.minDurationArray, scData.stallDurationArray,
                            depthScData.streamConfigurationArray, depthScData.minDurationArray,
                            depthScData.stallDurationArray,
                            /*dynamicDepthConfigurations*/ null,
                            /*dynamicDepthMinFrameDurations*/ null,
                            /*dynamicDepthStallDurations*/ null,
                            /*heicconfiguration*/ null,
                            /*heicminduration*/ null,
                            /*heicstallduration*/ null,
                            /*jpegRconfiguration*/ null,
                            /*jpegRminduration*/ null,
                            /*jpegRstallduration*/ null,
                            /*highSpeedVideoConfigurations*/ null,
                            /*inputOutputFormatsMap*/ null, listHighResolution, supportsPrivate[i]);
            }

            recommendedConfigurations.add(new RecommendedStreamConfigurationMap(map, /*usecase*/i,
                        supportsPrivate[i]));
        }

        return recommendedConfigurations;
    }

    private boolean isCapabilitySupported(int capabilityRequested) {
        boolean ret = false;

        int[] capabilities = getBase(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        for (int capability : capabilities) {
            if (capabilityRequested == capability) {
                ret = true;
                break;
            }
        }

        return ret;
    }

    /**
     * @hide
     */
    public boolean isUltraHighResolutionSensor() {
        return isCapabilitySupported(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR);

    }
    private boolean isBurstSupported() {
        return isCapabilitySupported(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE);
    }

    private boolean isPreviewStabilizationSupported() {
        boolean ret = false;

        int[] videoStabilizationModes =
                getBase(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (videoStabilizationModes == null) {
            return false;
        }
        for (int mode : videoStabilizationModes) {
            if (mode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) {
                ret = true;
                break;
            }
        }

        return ret;
    }

    private boolean isCroppedRawSupported() {
        boolean ret = false;

        long[] streamUseCases =
                getBase(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES);
        if (streamUseCases == null) {
            return false;
        }
        for (long useCase : streamUseCases) {
            if (useCase == CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW) {
                return true;
            }
        }

        return ret;
    }

    private MandatoryStreamCombination[] getMandatoryStreamCombinationsHelper(
            int mandatoryStreamsType) {
        int[] capabilities = getBase(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        ArrayList<Integer> caps = new ArrayList<Integer>();
        caps.ensureCapacity(capabilities.length);
        for (int c : capabilities) {
            caps.add(new Integer(c));
        }
        int hwLevel = getBase(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        MandatoryStreamCombination.Builder build = new MandatoryStreamCombination.Builder(
                mCameraId, hwLevel, mDisplaySize, caps, getStreamConfigurationMap(),
                getStreamConfigurationMapMaximumResolution(), isPreviewStabilizationSupported(),
                isCroppedRawSupported());

        List<MandatoryStreamCombination> combs = null;
        switch (mandatoryStreamsType) {
            case MANDATORY_STREAM_CONFIGURATIONS_CONCURRENT:
                combs = build.getAvailableMandatoryConcurrentStreamCombinations();
                break;
            case MANDATORY_STREAM_CONFIGURATIONS_MAX_RESOLUTION:
                combs = build.getAvailableMandatoryMaximumResolutionStreamCombinations();
                break;
            case MANDATORY_STREAM_CONFIGURATIONS_10BIT:
                combs = build.getAvailableMandatory10BitStreamCombinations();
                break;
            case MANDATORY_STREAM_CONFIGURATIONS_USE_CASE:
                combs = build.getAvailableMandatoryStreamUseCaseCombinations();
                break;
            case MANDATORY_STREAM_CONFIGURATIONS_PREVIEW_STABILIZATION:
                combs = build.getAvailableMandatoryPreviewStabilizedStreamCombinations();
                break;
            default:
                combs = build.getAvailableMandatoryStreamCombinations();
        }
        if ((combs != null) && (!combs.isEmpty())) {
            MandatoryStreamCombination[] combArray = new MandatoryStreamCombination[combs.size()];
            combArray = combs.toArray(combArray);
            return combArray;
        }
        return null;
    }

    private MandatoryStreamCombination[] getMandatory10BitStreamCombinations() {
        return getMandatoryStreamCombinationsHelper(MANDATORY_STREAM_CONFIGURATIONS_10BIT);
    }

    private MandatoryStreamCombination[] getMandatoryConcurrentStreamCombinations() {
        if (!mHasMandatoryConcurrentStreams) {
            return null;
        }
        return getMandatoryStreamCombinationsHelper(MANDATORY_STREAM_CONFIGURATIONS_CONCURRENT);
    }

    private MandatoryStreamCombination[] getMandatoryMaximumResolutionStreamCombinations() {
        if (!isUltraHighResolutionSensor()) {
            return null;
        }
        return getMandatoryStreamCombinationsHelper(MANDATORY_STREAM_CONFIGURATIONS_MAX_RESOLUTION);
    }

    private MandatoryStreamCombination[] getMandatoryStreamCombinations() {
        return getMandatoryStreamCombinationsHelper(MANDATORY_STREAM_CONFIGURATIONS_DEFAULT);
    }

    private MandatoryStreamCombination[] getMandatoryUseCaseStreamCombinations() {
        return getMandatoryStreamCombinationsHelper(MANDATORY_STREAM_CONFIGURATIONS_USE_CASE);
    }

    private MandatoryStreamCombination[] getMandatoryPreviewStabilizationStreamCombinations() {
        return getMandatoryStreamCombinationsHelper(
                MANDATORY_STREAM_CONFIGURATIONS_PREVIEW_STABILIZATION);
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
        StreamConfiguration[] dynamicDepthConfigurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DYNAMIC_DEPTH_STREAM_CONFIGURATIONS);
        StreamConfigurationDuration[] dynamicDepthMinFrameDurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DYNAMIC_DEPTH_MIN_FRAME_DURATIONS);
        StreamConfigurationDuration[] dynamicDepthStallDurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DYNAMIC_DEPTH_STALL_DURATIONS);
        StreamConfiguration[] heicConfigurations = getBase(
                CameraCharacteristics.HEIC_AVAILABLE_HEIC_STREAM_CONFIGURATIONS);
        StreamConfigurationDuration[] heicMinFrameDurations = getBase(
                CameraCharacteristics.HEIC_AVAILABLE_HEIC_MIN_FRAME_DURATIONS);
        StreamConfigurationDuration[] heicStallDurations = getBase(
                CameraCharacteristics.HEIC_AVAILABLE_HEIC_STALL_DURATIONS);
        StreamConfiguration[] jpegRConfigurations = getBase(
                CameraCharacteristics.JPEGR_AVAILABLE_JPEG_R_STREAM_CONFIGURATIONS);
        StreamConfigurationDuration[] jpegRMinFrameDurations = getBase(
                CameraCharacteristics.JPEGR_AVAILABLE_JPEG_R_MIN_FRAME_DURATIONS);
        StreamConfigurationDuration[] jpegRStallDurations = getBase(
                CameraCharacteristics.JPEGR_AVAILABLE_JPEG_R_STALL_DURATIONS);
        HighSpeedVideoConfiguration[] highSpeedVideoConfigurations = getBase(
                CameraCharacteristics.CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS);
        ReprocessFormatsMap inputOutputFormatsMap = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP);
        boolean listHighResolution = isBurstSupported();
        return new StreamConfigurationMap(
                configurations, minFrameDurations, stallDurations,
                depthConfigurations, depthMinFrameDurations, depthStallDurations,
                dynamicDepthConfigurations, dynamicDepthMinFrameDurations,
                dynamicDepthStallDurations, heicConfigurations,
                heicMinFrameDurations, heicStallDurations,
                jpegRConfigurations, jpegRMinFrameDurations, jpegRStallDurations,
                highSpeedVideoConfigurations, inputOutputFormatsMap,
                listHighResolution);
    }

    private StreamConfigurationMap getStreamConfigurationMapMaximumResolution() {
        StreamConfiguration[] configurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] minFrameDurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] stallDurations = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_STALL_DURATIONS_MAXIMUM_RESOLUTION);
        // If the at least these keys haven't been advertised, there cannot be a meaningful max
        // resolution StreamConfigurationMap
        if (configurations == null ||
                minFrameDurations == null ||
                stallDurations == null) {
            return null;
        }

        StreamConfiguration[] depthConfigurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DEPTH_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] depthMinFrameDurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DEPTH_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] depthStallDurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DEPTH_STALL_DURATIONS_MAXIMUM_RESOLUTION);
        StreamConfiguration[] dynamicDepthConfigurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DYNAMIC_DEPTH_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] dynamicDepthMinFrameDurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DYNAMIC_DEPTH_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] dynamicDepthStallDurations = getBase(
                CameraCharacteristics.DEPTH_AVAILABLE_DYNAMIC_DEPTH_STALL_DURATIONS_MAXIMUM_RESOLUTION);
        StreamConfiguration[] heicConfigurations = getBase(
                CameraCharacteristics.HEIC_AVAILABLE_HEIC_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] heicMinFrameDurations = getBase(
                CameraCharacteristics.HEIC_AVAILABLE_HEIC_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] heicStallDurations = getBase(
                CameraCharacteristics.HEIC_AVAILABLE_HEIC_STALL_DURATIONS_MAXIMUM_RESOLUTION);
        StreamConfiguration[] jpegRConfigurations = getBase(
                CameraCharacteristics.JPEGR_AVAILABLE_JPEG_R_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] jpegRMinFrameDurations = getBase(
                CameraCharacteristics.JPEGR_AVAILABLE_JPEG_R_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION);
        StreamConfigurationDuration[] jpegRStallDurations = getBase(
                CameraCharacteristics.JPEGR_AVAILABLE_JPEG_R_STALL_DURATIONS_MAXIMUM_RESOLUTION);
        HighSpeedVideoConfiguration[] highSpeedVideoConfigurations = getBase(
                CameraCharacteristics.CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS_MAXIMUM_RESOLUTION);
        ReprocessFormatsMap inputOutputFormatsMap = getBase(
                CameraCharacteristics.SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP_MAXIMUM_RESOLUTION);
        // TODO: Is this correct, burst capability shouldn't necessarily correspond to max res mode
        boolean listHighResolution = isBurstSupported();
        return new StreamConfigurationMap(
                configurations, minFrameDurations, stallDurations,
                depthConfigurations, depthMinFrameDurations, depthStallDurations,
                dynamicDepthConfigurations, dynamicDepthMinFrameDurations,
                dynamicDepthStallDurations, heicConfigurations,
                heicMinFrameDurations, heicStallDurations,
                jpegRConfigurations, jpegRMinFrameDurations, jpegRStallDurations,
                highSpeedVideoConfigurations, inputOutputFormatsMap,
                listHighResolution, false);
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

    private OisSample[] getOisSamples() {
        long[] timestamps = getBase(CaptureResult.STATISTICS_OIS_TIMESTAMPS);
        float[] xShifts = getBase(CaptureResult.STATISTICS_OIS_X_SHIFTS);
        float[] yShifts = getBase(CaptureResult.STATISTICS_OIS_Y_SHIFTS);

        if (timestamps == null) {
            if (xShifts != null) {
                throw new AssertionError("timestamps is null but xShifts is not");
            }

            if (yShifts != null) {
                throw new AssertionError("timestamps is null but yShifts is not");
            }

            return null;
        }

        if (xShifts == null) {
            throw new AssertionError("timestamps is not null but xShifts is");
        }

        if (yShifts == null) {
            throw new AssertionError("timestamps is not null but yShifts is");
        }

        if (xShifts.length != timestamps.length) {
            throw new AssertionError(String.format(
                    "timestamps has %d entries but xShifts has %d", timestamps.length,
                    xShifts.length));
        }

        if (yShifts.length != timestamps.length) {
            throw new AssertionError(String.format(
                    "timestamps has %d entries but yShifts has %d", timestamps.length,
                    yShifts.length));
        }

        OisSample[] samples = new OisSample[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            samples[i] = new OisSample(timestamps[i], xShifts[i], yShifts[i]);
        }
        return samples;
    }

    private boolean setLensIntrinsicsSamples(LensIntrinsicsSample[] samples) {
        if (samples == null) {
            return false;
        }

        long[] tsArray = new long[samples.length];
        float[] intrinsicsArray = new float[samples.length * 5];
        for (int i = 0; i < samples.length; i++) {
            tsArray[i] = samples[i].getTimestamp();
            System.arraycopy(samples[i].getLensIntrinsics(), 0, intrinsicsArray, 5*i, 5);

        }
        setBase(CaptureResult.STATISTICS_LENS_INTRINSIC_SAMPLES, intrinsicsArray);
        setBase(CaptureResult.STATISTICS_LENS_INTRINSIC_TIMESTAMPS, tsArray);

        return true;
    }

    private LensIntrinsicsSample[] getLensIntrinsicSamples() {
        long[] timestamps = getBase(CaptureResult.STATISTICS_LENS_INTRINSIC_TIMESTAMPS);
        float[] intrinsics = getBase(CaptureResult.STATISTICS_LENS_INTRINSIC_SAMPLES);

        if (timestamps == null) {
            if (intrinsics != null) {
                throw new AssertionError("timestamps is null but intrinsics is not");
            }

            return null;
        }

        if (intrinsics == null) {
            throw new AssertionError("timestamps is not null but intrinsics is");
        } else if((intrinsics.length % 5) != 0) {
            throw new AssertionError("intrinsics are not multiple of 5");
        }

        if ((intrinsics.length / 5) != timestamps.length) {
            throw new AssertionError(String.format(
                    "timestamps has %d entries but intrinsics has %d", timestamps.length,
                    intrinsics.length / 5));
        }

        LensIntrinsicsSample[] samples = new LensIntrinsicsSample[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            float[] currentIntrinsic = Arrays.copyOfRange(intrinsics, 5*i, 5*i + 5);
            samples[i] = new LensIntrinsicsSample(timestamps[i], currentIntrinsic);
        }
        return samples;
    }

    private Capability[] getExtendedSceneModeCapabilities() {
        int[] maxSizes =
                getBase(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_MAX_SIZES);
        float[] zoomRanges = getBase(
                CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_ZOOM_RATIO_RANGES);
        Range<Float> zoomRange = getBase(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        float maxDigitalZoom = getBase(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);

        if (maxSizes == null) {
            return null;
        }
        if (maxSizes.length % 3 != 0) {
            throw new AssertionError("availableExtendedSceneModeMaxSizes must be tuples of "
                    + "[mode, width, height]");
        }
        int numExtendedSceneModes = maxSizes.length / 3;
        int numExtendedSceneModeZoomRanges = 0;
        if (zoomRanges != null) {
            if (zoomRanges.length % 2 != 0) {
                throw new AssertionError("availableExtendedSceneModeZoomRanges must be tuples of "
                        + "[minZoom, maxZoom]");
            }
            numExtendedSceneModeZoomRanges = zoomRanges.length / 2;
            if (numExtendedSceneModes - numExtendedSceneModeZoomRanges != 1) {
                throw new AssertionError("Number of extended scene mode zoom ranges must be 1 "
                        + "less than number of supported modes");
            }
        }

        float modeOffMinZoomRatio = 1.0f;
        float modeOffMaxZoomRatio = maxDigitalZoom;
        if (zoomRange != null) {
            modeOffMinZoomRatio = zoomRange.getLower();
            modeOffMaxZoomRatio = zoomRange.getUpper();
        }

        Capability[] capabilities = new Capability[numExtendedSceneModes];
        for (int i = 0, j = 0; i < numExtendedSceneModes; i++) {
            int mode = maxSizes[3 * i];
            int width = maxSizes[3 * i + 1];
            int height = maxSizes[3 * i + 2];
            if (mode != CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_DISABLED
                    && j < numExtendedSceneModeZoomRanges) {
                capabilities[i] = new Capability(mode, new Size(width, height),
                        new Range<Float>(zoomRanges[2 * j], zoomRanges[2 * j + 1]));
                j++;
            } else {
                capabilities[i] = new Capability(mode, new Size(width, height),
                        new Range<Float>(modeOffMinZoomRatio, modeOffMaxZoomRatio));
            }
        }

        return capabilities;
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
        int tag;
        if (key.hasTag()) {
            tag = key.getTag();
        } else {
            tag = nativeGetTagFromKeyLocal(mMetadataPtr, key.getName());
            key.cacheTag(tag);
        }
        if (value == null) {
            // Erase the entry
            writeValues(tag, /*src*/null);
            return;
        } // else update the entry to a new value

        int nativeType = nativeGetTypeFromTagLocal(mMetadataPtr, tag);
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
        sSetCommandMap.put(CaptureRequest.SCALER_CROP_REGION.getNativeKey(),
                new SetCommand() {
            @Override
            public <T> void setValue(CameraMetadataNative metadata, T value) {
                metadata.setScalerCropRegion((Rect) value);
            }
        });
        sSetCommandMap.put(CaptureRequest.CONTROL_AWB_REGIONS.getNativeKey(),
                new SetCommand() {
            @Override
            public <T> void setValue(CameraMetadataNative metadata, T value) {
                metadata.setAWBRegions(value);
            }
        });
        sSetCommandMap.put(CaptureRequest.CONTROL_AF_REGIONS.getNativeKey(),
                new SetCommand() {
            @Override
            public <T> void setValue(CameraMetadataNative metadata, T value) {
                metadata.setAFRegions(value);
            }
        });
        sSetCommandMap.put(CaptureRequest.CONTROL_AE_REGIONS.getNativeKey(),
                new SetCommand() {
            @Override
            public <T> void setValue(CameraMetadataNative metadata, T value) {
                metadata.setAERegions(value);
            }
        });
        sSetCommandMap.put(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP.getNativeKey(),
                new SetCommand() {
                    @Override
                    public <T> void setValue(CameraMetadataNative metadata, T value) {
                        metadata.setLensShadingMap((LensShadingMap) value);
                    }
                });
        sSetCommandMap.put(
                CaptureResult.STATISTICS_LENS_INTRINSICS_SAMPLES.getNativeKey(),
                new SetCommand() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> void setValue(CameraMetadataNative metadata, T value) {
                        metadata.setLensIntrinsicsSamples((LensIntrinsicsSample []) value);
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

    private <T> boolean setScalerCropRegion(Rect cropRegion) {
        if (cropRegion == null) {
            return false;
        }
        setBase(CaptureRequest.SCALER_CROP_REGION_SET, true);
        setBase(CaptureRequest.SCALER_CROP_REGION, cropRegion);
        return true;
    }

    private <T> boolean setAFRegions(T afRegions) {
        if (afRegions == null) {
            return false;
        }
        setBase(CaptureRequest.CONTROL_AF_REGIONS_SET, true);
        // The cast to CaptureRequest.Key is needed since java does not support template
        // specialization and we need to route this method to
        // setBase(CaptureRequest.Key<T> key, T value)
        setBase((CaptureRequest.Key)CaptureRequest.CONTROL_AF_REGIONS, afRegions);
        return true;
    }

    private <T> boolean setAERegions(T aeRegions) {
        if (aeRegions == null) {
            return false;
        }
        setBase(CaptureRequest.CONTROL_AE_REGIONS_SET, true);
        setBase((CaptureRequest.Key)CaptureRequest.CONTROL_AE_REGIONS, aeRegions);
        return true;
    }

    private <T> boolean setAWBRegions(T awbRegions) {
        if (awbRegions == null) {
            return false;
        }
        setBase(CaptureRequest.CONTROL_AWB_REGIONS_SET, true);
        setBase((CaptureRequest.Key)CaptureRequest.CONTROL_AWB_REGIONS, awbRegions);
        return true;
    }

    private void updateNativeAllocation() {
        long currentBufferSize = nativeGetBufferSize(mMetadataPtr);

        if (currentBufferSize != mBufferSize) {
            if (mBufferSize > 0) {
                VMRuntime.getRuntime().registerNativeFree(mBufferSize);
            }

            mBufferSize = currentBufferSize;

            if (mBufferSize > 0) {
                VMRuntime.getRuntime().registerNativeAllocation(mBufferSize);
            }
        }
    }

    private int mCameraId = -1;
    private boolean mHasMandatoryConcurrentStreams = false;
    private Size mDisplaySize = new Size(0, 0);
    private long mBufferSize = 0;
    private MultiResolutionStreamConfigurationMap mMultiResolutionStreamConfigurationMap = null;

    /**
     * Set the current camera Id.
     *
     * @param cameraId Current camera id.
     *
     * @hide
     */
    public void setCameraId(int cameraId) {
        mCameraId = cameraId;
    }

    /**
     * Set the current camera Id.
     *
     * @param hasMandatoryConcurrentStreams whether the metadata advertises mandatory concurrent
     *        streams.
     *
     * @hide
     */
    public void setHasMandatoryConcurrentStreams(boolean hasMandatoryConcurrentStreams) {
        mHasMandatoryConcurrentStreams = hasMandatoryConcurrentStreams;
    }

    /**
     * Set the current display size.
     *
     * @param displaySize The current display size.
     *
     * @hide
     */
    public void setDisplaySize(Size displaySize) {
        mDisplaySize = displaySize;
    }

    /**
     * Set the multi-resolution stream configuration map.
     *
     * @param multiResolutionMap The multi-resolution stream configuration map.
     *
     * @hide
     */
    public void setMultiResolutionStreamConfigurationMap(
            @NonNull Map<String, StreamConfiguration[]> multiResolutionMap) {
        mMultiResolutionStreamConfigurationMap =
                new MultiResolutionStreamConfigurationMap(multiResolutionMap);
    }

    /**
     * Get the multi-resolution stream configuration map.
     *
     * @return The multi-resolution stream configuration map.
     *
     * @hide
     */
    public MultiResolutionStreamConfigurationMap getMultiResolutionStreamConfigurationMap() {
        return mMultiResolutionStreamConfigurationMap;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private long mMetadataPtr; // native std::shared_ptr<CameraMetadata>*

    @FastNative
    private static native long nativeAllocate();
    @FastNative
    private static native long nativeAllocateCopy(long ptr)
            throws NullPointerException;


    @FastNative
    private static native void nativeUpdate(long dst, long src);
    private static synchronized native void nativeWriteToParcel(Parcel dest, long ptr);
    private static synchronized native void nativeReadFromParcel(Parcel source, long ptr);
    private static synchronized native void nativeSwap(long ptr, long otherPtr)
            throws NullPointerException;
    @FastNative
    private static native void nativeSetVendorId(long ptr, long vendorId);
    private static synchronized native void nativeClose(long ptr);
    private static synchronized native boolean nativeIsEmpty(long ptr);
    private static synchronized native int nativeGetEntryCount(long ptr);
    private static synchronized native long nativeGetBufferSize(long ptr);

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static synchronized native byte[] nativeReadValues(int tag, long ptr);
    private static synchronized native void nativeWriteValues(int tag, byte[] src, long ptr);
    private static synchronized native void nativeDump(long ptr) throws IOException; // dump to LOGD

    private static synchronized native ArrayList nativeGetAllVendorKeys(long ptr, Class keyClass);
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static synchronized native int nativeGetTagFromKeyLocal(long ptr, String keyName)
            throws IllegalArgumentException;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static synchronized native int nativeGetTypeFromTagLocal(long ptr, int tag)
            throws IllegalArgumentException;
    @FastNative
    private static native int nativeGetTagFromKey(String keyName, long vendorId)
            throws IllegalArgumentException;
    @FastNative
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
        nativeSwap(mMetadataPtr, other.mMetadataPtr);
        mCameraId = other.mCameraId;
        mHasMandatoryConcurrentStreams = other.mHasMandatoryConcurrentStreams;
        mDisplaySize = other.mDisplaySize;
        mMultiResolutionStreamConfigurationMap = other.mMultiResolutionStreamConfigurationMap;
        updateNativeAllocation();
        other.updateNativeAllocation();
    }

    /**
     * Set the native metadata vendor id.
     *
     * @hide
     */
    public void setVendorId(long vendorId) {
        nativeSetVendorId(mMetadataPtr, vendorId);
    }

    /**
     * @hide
     */
    public int getEntryCount() {
        return nativeGetEntryCount(mMetadataPtr);
    }

    /**
     * Does this metadata contain at least 1 entry?
     *
     * @hide
     */
    public boolean isEmpty() {
        return nativeIsEmpty(mMetadataPtr);
    }


    /**
     * Retrieves the pointer to the native shared_ptr<CameraMetadata> as a Java long.
     *
     * @hide
     */
    public long getMetadataPtr() {
        return mMetadataPtr;
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
        return (ArrayList<K>) nativeGetAllVendorKeys(mMetadataPtr, keyClass);
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
        nativeWriteValues(tag, src, mMetadataPtr);
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
        return nativeReadValues(tag, mMetadataPtr);
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
            nativeDump(mMetadataPtr);
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
                new MarshalQueryableRecommendedStreamConfiguration(),

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

    /**
     * Return the set of physical camera ids that this logical {@link CameraDevice} is made
     * up of.
     *
     * If the camera device isn't a logical camera, return an empty set.
     *
     * @hide
     */
    public Set<String> getPhysicalCameraIds() {
        int[] availableCapabilities = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (availableCapabilities == null) {
            throw new AssertionError("android.request.availableCapabilities must be non-null "
                        + "in the characteristics");
        }

        if (!ArrayUtils.contains(availableCapabilities,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return Collections.emptySet();
        }
        byte[] physicalCamIds = get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_PHYSICAL_IDS);

        String physicalCamIdString = null;
        try {
            physicalCamIdString = new String(physicalCamIds, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError("android.logicalCam.physicalIds must be UTF-8 string");
        }
        String[] physicalCameraIdArray = physicalCamIdString.split("\0");

        return Collections.unmodifiableSet(
                new HashSet<String>(Arrays.asList(physicalCameraIdArray)));
    }

    static {
        registerAllMarshalers();
    }
}
