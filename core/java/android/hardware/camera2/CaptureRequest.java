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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.ExtensionKey;
import android.hardware.camera2.impl.PublicKey;
import android.hardware.camera2.impl.SyntheticKey;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.hardware.camera2.utils.SurfaceUtils;
import android.hardware.camera2.utils.TypeReference;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;

import com.android.internal.camera.flags.Flags;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <p>An immutable package of settings and outputs needed to capture a single
 * image from the camera device.</p>
 *
 * <p>Contains the configuration for the capture hardware (sensor, lens, flash),
 * the processing pipeline, the control algorithms, and the output buffers. Also
 * contains the list of target Surfaces to send image data to for this
 * capture.</p>
 *
 * <p>CaptureRequests can be created by using a {@link Builder} instance,
 * obtained by calling {@link CameraDevice#createCaptureRequest} or {@link
 * CameraManager#createCaptureRequest}</p>
 *
 * <p>CaptureRequests are given to {@link CameraCaptureSession#capture} or
 * {@link CameraCaptureSession#setRepeatingRequest} to capture images from a camera.</p>
 *
 * <p>Each request can specify a different subset of target Surfaces for the
 * camera to send the captured data to. All the surfaces used in a request must
 * be part of the surface list given to the last call to
 * {@link CameraDevice#createCaptureSession}, when the request is submitted to the
 * session.</p>
 *
 * <p>For example, a request meant for repeating preview might only include the
 * Surface for the preview SurfaceView or SurfaceTexture, while a
 * high-resolution still capture would also include a Surface from a ImageReader
 * configured for high-resolution JPEG images.</p>
 *
 * <p>A reprocess capture request allows a previously-captured image from the camera device to be
 * sent back to the device for further processing. It can be created with
 * {@link CameraDevice#createReprocessCaptureRequest}, and used with a reprocessable capture session
 * created with {@link CameraDevice#createReprocessableCaptureSession}.</p>
 *
 * @see CameraCaptureSession#capture
 * @see CameraCaptureSession#setRepeatingRequest
 * @see CameraCaptureSession#captureBurst
 * @see CameraCaptureSession#setRepeatingBurst
 * @see CameraDevice#createCaptureRequest
 * @see CameraDevice#createReprocessCaptureRequest
 * @see CameraManager#createCaptureRequest
 */
public final class CaptureRequest extends CameraMetadata<CaptureRequest.Key<?>>
        implements Parcelable {

    /**
     * A {@code Key} is used to do capture request field lookups with
     * {@link CaptureRequest#get} or to set fields with
     * {@link CaptureRequest.Builder#set(Key, Object)}.
     *
     * <p>For example, to set the crop rectangle for the next capture:
     * <code><pre>
     * Rect cropRectangle = new Rect(0, 0, 640, 480);
     * captureRequestBuilder.set(SCALER_CROP_REGION, cropRectangle);
     * </pre></code>
     * </p>
     *
     * <p>To enumerate over all possible keys for {@link CaptureRequest}, see
     * {@link CameraCharacteristics#getAvailableCaptureRequestKeys}.</p>
     *
     * @see CaptureRequest#get
     * @see CameraCharacteristics#getAvailableCaptureRequestKeys
     */
    public final static class Key<T> {
        private final CameraMetadataNative.Key<T> mKey;

        /**
         * Visible for testing and vendor extensions only.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public Key(String name, Class<T> type, long vendorId) {
            mKey = new CameraMetadataNative.Key<T>(name, type, vendorId);
        }

        /**
         * Construct a new Key with a given name and type.
         *
         * <p>Normally, applications should use the existing Key definitions in
         * {@link CaptureRequest}, and not need to construct their own Key objects. However, they
         * may be useful for testing purposes and for defining custom capture request fields.</p>
         */
        public Key(@NonNull String name, @NonNull Class<T> type) {
            mKey = new CameraMetadataNative.Key<T>(name, type);
        }

        /**
         * Visible for testing and vendor extensions only.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public Key(String name, TypeReference<T> typeReference) {
            mKey = new CameraMetadataNative.Key<T>(name, typeReference);
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
        @NonNull
        public String getName() {
            return mKey.getName();
        }

        /**
         * Return vendor tag id.
         *
         * @hide
         */
        public long getVendorId() {
            return mKey.getVendorId();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public final int hashCode() {
            return mKey.hashCode();
        }

        /**
         * {@inheritDoc}
         */
        @SuppressWarnings("unchecked")
        @Override
        public final boolean equals(Object o) {
            return o instanceof Key && ((Key<T>)o).mKey.equals(mKey);
        }

        /**
         * Return this {@link Key} as a string representation.
         *
         * <p>{@code "CaptureRequest.Key(%s)"}, where {@code %s} represents
         * the name of this key as returned by {@link #getName}.</p>
         *
         * @return string representation of {@link Key}
         */
        @NonNull
        @Override
        public String toString() {
            return String.format("CaptureRequest.Key(%s)", mKey.getName());
        }

        /**
         * Visible for CameraMetadataNative implementation only; do not use.
         *
         * TODO: Make this private or remove it altogether.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public CameraMetadataNative.Key<T> getNativeKey() {
            return mKey;
        }

        @SuppressWarnings({ "unchecked" })
        /*package*/ Key(CameraMetadataNative.Key<?> nativeKey) {
            mKey = (CameraMetadataNative.Key<T>) nativeKey;
        }
    }

    private final String            TAG = "CaptureRequest-JV";

    private final ArraySet<Surface> mSurfaceSet = new ArraySet<Surface>();

    // Speed up sending CaptureRequest across IPC:
    // mSurfaceConverted should only be set to true during capture request
    // submission by {@link #convertSurfaceToStreamId}. The method will convert
    // surfaces to stream/surface indexes based on passed in stream configuration at that time.
    // This will save significant unparcel time for remote camera device.
    // Once the request is submitted, camera device will call {@link #recoverStreamIdToSurface}
    // to reset the capture request back to its original state.
    private final Object           mSurfacesLock = new Object();
    private boolean                mSurfaceConverted = false;
    private int[]                  mStreamIdxArray;
    private int[]                  mSurfaceIdxArray;

    private static final ArraySet<Surface> mEmptySurfaceSet = new ArraySet<Surface>();

    private String mLogicalCameraId;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private CameraMetadataNative mLogicalCameraSettings;
    private final HashMap<String, CameraMetadataNative> mPhysicalCameraSettings =
            new HashMap<String, CameraMetadataNative>();

    private boolean mIsReprocess;

    //
    // Enumeration values for types of CaptureRequest
    //

    /**
     * @hide
     */
    public static final int REQUEST_TYPE_REGULAR = 0;

    /**
     * @hide
     */
    public static final int REQUEST_TYPE_REPROCESS = 1;

    /**
     * @hide
     */
    public static final int REQUEST_TYPE_ZSL_STILL = 2;

    /**
     * Note: To add another request type, the FrameNumberTracker in CameraDeviceImpl must be
     * adjusted accordingly.
     * @hide
     */
    public static final int REQUEST_TYPE_COUNT = 3;


    private int mRequestType = -1;

    private static final String SET_TAG_STRING_PREFIX =
            "android.hardware.camera2.CaptureRequest.setTag.";
    /**
     * Get the type of the capture request
     *
     * Return one of REGULAR, ZSL_STILL, or REPROCESS.
     * @hide
     */
    public int getRequestType() {
        if (mRequestType == -1) {
            if (mIsReprocess) {
                mRequestType = REQUEST_TYPE_REPROCESS;
            } else {
                Boolean enableZsl = mLogicalCameraSettings.get(CaptureRequest.CONTROL_ENABLE_ZSL);
                boolean isZslStill = false;
                if (enableZsl != null && enableZsl) {
                    int captureIntent = mLogicalCameraSettings.get(
                            CaptureRequest.CONTROL_CAPTURE_INTENT);
                    if (captureIntent == CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                        isZslStill = true;
                    }
                }
                mRequestType = isZslStill ? REQUEST_TYPE_ZSL_STILL : REQUEST_TYPE_REGULAR;
            }
        }
        return mRequestType;
    }

    // If this request is part of constrained high speed request list that was created by
    // {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList}
    private boolean mIsPartOfCHSRequestList = false;
    // Each reprocess request must be tied to a reprocessable session ID.
    // Valid only for reprocess requests (mIsReprocess == true).
    private int mReprocessableSessionId;

    private Object mUserTag;

    private boolean mReleaseSurfaces = false;

    /**
     * Construct empty request.
     *
     * Used by Binder to unparcel this object only.
     */
    private CaptureRequest() {
        mIsReprocess = false;
        mReprocessableSessionId = CameraCaptureSession.SESSION_ID_NONE;
    }

    /**
     * Clone from source capture request.
     *
     * Used by the Builder to create an immutable copy.
     */
    @SuppressWarnings("unchecked")
    private CaptureRequest(CaptureRequest source) {
        mLogicalCameraId = new String(source.mLogicalCameraId);
        for (Map.Entry<String, CameraMetadataNative> entry :
                source.mPhysicalCameraSettings.entrySet()) {
            mPhysicalCameraSettings.put(new String(entry.getKey()),
                    new CameraMetadataNative(entry.getValue()));
        }
        mLogicalCameraSettings = mPhysicalCameraSettings.get(mLogicalCameraId);
        setNativeInstance(mLogicalCameraSettings);
        mSurfaceSet.addAll(source.mSurfaceSet);
        mIsReprocess = source.mIsReprocess;
        mIsPartOfCHSRequestList = source.mIsPartOfCHSRequestList;
        mReprocessableSessionId = source.mReprocessableSessionId;
        mUserTag = source.mUserTag;
    }

    /**
     * Take ownership of passed-in settings.
     *
     * Used by the Builder to create a mutable CaptureRequest.
     *
     * @param settings Settings for this capture request.
     * @param isReprocess Indicates whether to create a reprocess capture request. {@code true}
     *                    to create a reprocess capture request. {@code false} to create a regular
     *                    capture request.
     * @param reprocessableSessionId The ID of the camera capture session this capture is created
     *                               for. This is used to validate if the application submits a
     *                               reprocess capture request to the same session where
     *                               the {@link TotalCaptureResult}, used to create the reprocess
     *                               capture, came from.
     * @param logicalCameraId Camera Id of the actively open camera that instantiates the
     *                        Builder.
     *
     * @param physicalCameraIdSet A set of physical camera ids that can be used to customize
     *                            the request for a specific physical camera.
     *
     * @throws IllegalArgumentException If creating a reprocess capture request with an invalid
     *                                  reprocessableSessionId, or multiple physical cameras.
     *
     * @see CameraDevice#createReprocessCaptureRequest
     */
    private CaptureRequest(CameraMetadataNative settings, boolean isReprocess,
            int reprocessableSessionId, String logicalCameraId, Set<String> physicalCameraIdSet) {
        if ((physicalCameraIdSet != null) && isReprocess) {
            throw new IllegalArgumentException("Create a reprocess capture request with " +
                    "with more than one physical camera is not supported!");
        }

        mLogicalCameraId = logicalCameraId;
        mLogicalCameraSettings = CameraMetadataNative.move(settings);
        mPhysicalCameraSettings.put(mLogicalCameraId, mLogicalCameraSettings);
        if (physicalCameraIdSet != null) {
            for (String physicalId : physicalCameraIdSet) {
                mPhysicalCameraSettings.put(physicalId, new CameraMetadataNative(
                            mLogicalCameraSettings));
            }
        }

        setNativeInstance(mLogicalCameraSettings);
        mIsReprocess = isReprocess;
        if (isReprocess) {
            if (reprocessableSessionId == CameraCaptureSession.SESSION_ID_NONE) {
                throw new IllegalArgumentException("Create a reprocess capture request with an " +
                        "invalid session ID: " + reprocessableSessionId);
            }
            mReprocessableSessionId = reprocessableSessionId;
        } else {
            mReprocessableSessionId = CameraCaptureSession.SESSION_ID_NONE;
        }
    }

    /**
     * Get a capture request field value.
     *
     * <p>The field definitions can be found in {@link CaptureRequest}.</p>
     *
     * <p>Querying the value for the same key more than once will return a value
     * which is equal to the previous queried value.</p>
     *
     * @throws IllegalArgumentException if the key was not valid
     *
     * @param key The result field to read.
     * @return The value of that key, or {@code null} if the field is not set.
     */
    @Nullable
    public <T> T get(Key<T> key) {
        return mLogicalCameraSettings.get(key);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SuppressWarnings("unchecked")
    @Override
    protected <T> T getProtected(Key<?> key) {
        return (T) mLogicalCameraSettings.get(key);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SuppressWarnings("unchecked")
    @Override
    protected Class<Key<?>> getKeyClass() {
        Object thisClass = Key.class;
        return (Class<Key<?>>)thisClass;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Key<?>> getKeys() {
        // Force the javadoc for this function to show up on the CaptureRequest page
        return super.getKeys();
    }

    /**
     * Retrieve the tag for this request, if any.
     *
     * <p>This tag is not used for anything by the camera device, but can be
     * used by an application to easily identify a CaptureRequest when it is
     * returned by
     * {@link CameraCaptureSession.CaptureCallback#onCaptureCompleted CaptureCallback.onCaptureCompleted}
     * </p>
     *
     * @return the last tag Object set on this request, or {@code null} if
     *     no tag has been set.
     * @see Builder#setTag
     */
    @Nullable
    public Object getTag() {
        return mUserTag;
    }

    /**
     * Determine if this is a reprocess capture request.
     *
     * <p>A reprocess capture request produces output images from an input buffer from the
     * {@link CameraCaptureSession}'s input {@link Surface}. A reprocess capture request can be
     * created by {@link CameraDevice#createReprocessCaptureRequest}.</p>
     *
     * @return {@code true} if this is a reprocess capture request. {@code false} if this is not a
     * reprocess capture request.
     *
     * @see CameraDevice#createReprocessCaptureRequest
     */
    public boolean isReprocess() {
        return mIsReprocess;
    }

    /**
     * <p>Determine if this request is part of a constrained high speed request list that was
     * created by
     * {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList}.
     * A constrained high speed request list contains some constrained high speed capture requests
     * with certain interleaved pattern that is suitable for high speed preview/video streaming. An
     * active constrained high speed capture session only accepts constrained high speed request
     * lists.  This method can be used to do the correctness check when a constrained high speed
     * capture session receives a request list via {@link CameraCaptureSession#setRepeatingBurst} or
     * {@link CameraCaptureSession#captureBurst}.  </p>
     *
     *
     * @return {@code true} if this request is part of a constrained high speed request list,
     * {@code false} otherwise.
     *
     * @hide
     */
    public boolean isPartOfCRequestList() {
        return mIsPartOfCHSRequestList;
    }

    /**
     * Returns a copy of the underlying {@link CameraMetadataNative}.
     * @hide
     */
    public CameraMetadataNative getNativeCopy() {
        return new CameraMetadataNative(mLogicalCameraSettings);
    }

    /**
     * Get the reprocessable session ID this reprocess capture request is associated with.
     *
     * @return the reprocessable session ID this reprocess capture request is associated with
     *
     * @throws IllegalStateException if this capture request is not a reprocess capture request.
     * @hide
     */
    public int getReprocessableSessionId() {
        if (mIsReprocess == false ||
                mReprocessableSessionId == CameraCaptureSession.SESSION_ID_NONE) {
            throw new IllegalStateException("Getting the reprocessable session ID for a "+
                    "non-reprocess capture request is illegal.");
        }
        return mReprocessableSessionId;
    }

    /**
     * Determine whether this CaptureRequest is equal to another CaptureRequest.
     *
     * <p>A request is considered equal to another is if it's set of key/values is equal, it's
     * list of output surfaces is equal, the user tag is equal, and the return values of
     * isReprocess() are equal.</p>
     *
     * @param other Another instance of CaptureRequest.
     *
     * @return True if the requests are the same, false otherwise.
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof CaptureRequest
                && equals((CaptureRequest)other);
    }

    private boolean equals(CaptureRequest other) {
        return other != null
                && Objects.equals(mUserTag, other.mUserTag)
                && mSurfaceSet.equals(other.mSurfaceSet)
                && mPhysicalCameraSettings.equals(other.mPhysicalCameraSettings)
                && mLogicalCameraId.equals(other.mLogicalCameraId)
                && mLogicalCameraSettings.equals(other.mLogicalCameraSettings)
                && mIsReprocess == other.mIsReprocess
                && mReprocessableSessionId == other.mReprocessableSessionId;
    }

    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCodeGeneric(mPhysicalCameraSettings, mSurfaceSet, mUserTag);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CaptureRequest> CREATOR =
            new Parcelable.Creator<CaptureRequest>() {
        @Override
        public CaptureRequest createFromParcel(Parcel in) {
            CaptureRequest request = new CaptureRequest();
            request.readFromParcel(in);

            return request;
        }

        @Override
        public CaptureRequest[] newArray(int size) {
            return new CaptureRequest[size];
        }
    };

    /**
     * Expand this object from a Parcel.
     * Hidden since this breaks the immutability of CaptureRequest, but is
     * needed to receive CaptureRequests with aidl.
     *
     * @param in The parcel from which the object should be read
     * @hide
     */
    private void readFromParcel(Parcel in) {
        int physicalCameraCount = in.readInt();
        if (physicalCameraCount <= 0) {
            throw new RuntimeException("Physical camera count" + physicalCameraCount +
                    " should always be positive");
        }

        //Always start with the logical camera id
        mLogicalCameraId = in.readString();
        mLogicalCameraSettings = new CameraMetadataNative();
        mLogicalCameraSettings.readFromParcel(in);
        setNativeInstance(mLogicalCameraSettings);
        mPhysicalCameraSettings.put(mLogicalCameraId, mLogicalCameraSettings);
        for (int i = 1; i < physicalCameraCount; i++) {
            String physicalId = in.readString();
            CameraMetadataNative physicalCameraSettings = new CameraMetadataNative();
            physicalCameraSettings.readFromParcel(in);
            mPhysicalCameraSettings.put(physicalId, physicalCameraSettings);
        }

        mIsReprocess = (in.readInt() == 0) ? false : true;
        mReprocessableSessionId = CameraCaptureSession.SESSION_ID_NONE;

        synchronized (mSurfacesLock) {
            mSurfaceSet.clear();
            Parcelable[] parcelableArray = in.readParcelableArray(Surface.class.getClassLoader(),
                    Surface.class);
            if (parcelableArray != null) {
                if (Flags.surfaceLeakFix()) {
                    mReleaseSurfaces = true;
                }
                for (Parcelable p : parcelableArray) {
                    Surface s = (Surface) p;
                    mSurfaceSet.add(s);
                }
            }
            // Intentionally disallow java side readFromParcel to receive streamIdx/surfaceIdx
            // Since there is no good way to convert indexes back to Surface
            int streamSurfaceSize = in.readInt();
            if (streamSurfaceSize != 0) {
                throw new RuntimeException("Reading cached CaptureRequest is not supported");
            }
        }

        boolean hasUserTagStr = (in.readInt() == 1) ? true : false;
        if (hasUserTagStr) {
            mUserTag = in.readString();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (!mPhysicalCameraSettings.containsKey(mLogicalCameraId)) {
            throw new IllegalStateException("Physical camera settings map must contain a key for "
                    + "the logical camera id.");
        }

        int physicalCameraCount = mPhysicalCameraSettings.size();
        dest.writeInt(physicalCameraCount);
        //Logical camera id and settings always come first.
        dest.writeString(mLogicalCameraId);
        mLogicalCameraSettings.writeToParcel(dest, flags);
        for (Map.Entry<String, CameraMetadataNative> entry : mPhysicalCameraSettings.entrySet()) {
            if (entry.getKey().equals(mLogicalCameraId)) {
                continue;
            }
            dest.writeString(entry.getKey());
            entry.getValue().writeToParcel(dest, flags);
        }

        dest.writeInt(mIsReprocess ? 1 : 0);

        synchronized (mSurfacesLock) {
            final ArraySet<Surface> surfaces = mSurfaceConverted ? mEmptySurfaceSet : mSurfaceSet;
            dest.writeParcelableArray(surfaces.toArray(new Surface[surfaces.size()]), flags);
            if (mSurfaceConverted) {
                dest.writeInt(mStreamIdxArray.length);
                for (int i = 0; i < mStreamIdxArray.length; i++) {
                    dest.writeInt(mStreamIdxArray[i]);
                    dest.writeInt(mSurfaceIdxArray[i]);
                }
            } else {
                dest.writeInt(0);
            }
        }

        // Write string for user tag if set to something in the same namespace
        if (mUserTag != null) {
            String userTagStr = mUserTag.toString();
            if (userTagStr != null && userTagStr.startsWith(SET_TAG_STRING_PREFIX)) {
                dest.writeInt(1);
                dest.writeString(userTagStr.substring(SET_TAG_STRING_PREFIX.length()));
            } else {
                dest.writeInt(0);
            }
        } else {
            dest.writeInt(0);
        }
    }

    /**
     * @hide
     */
    public boolean containsTarget(Surface surface) {
        return mSurfaceSet.contains(surface);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public Collection<Surface> getTargets() {
        return Collections.unmodifiableCollection(mSurfaceSet);
    }

    /**
     * Retrieves the logical camera id.
     * @hide
     */
    public String getLogicalCameraId() {
        return mLogicalCameraId;
    }

    /**
     * @hide
     */
    public void convertSurfaceToStreamId(
            final SparseArray<OutputConfiguration> configuredOutputs) {
        synchronized (mSurfacesLock) {
            if (mSurfaceConverted) {
                Log.v(TAG, "Cannot convert already converted surfaces!");
                return;
            }

            mStreamIdxArray = new int[mSurfaceSet.size()];
            mSurfaceIdxArray = new int[mSurfaceSet.size()];
            int i = 0;
            for (Surface s : mSurfaceSet) {
                boolean streamFound = false;
                for (int j = 0; j < configuredOutputs.size(); ++j) {
                    int streamId = configuredOutputs.keyAt(j);
                    OutputConfiguration outConfig = configuredOutputs.valueAt(j);
                    int surfaceId = 0;
                    for (Surface outSurface : outConfig.getSurfaces()) {
                        if (s == outSurface) {
                            streamFound = true;
                            mStreamIdxArray[i] = streamId;
                            mSurfaceIdxArray[i] = surfaceId;
                            i++;
                            break;
                        }
                        surfaceId++;
                    }
                    if (streamFound) {
                        break;
                    }
                }

                if (!streamFound) {
                    // Check if we can match s by native object ID
                    long reqSurfaceId = SurfaceUtils.getSurfaceId(s);
                    for (int j = 0; j < configuredOutputs.size(); ++j) {
                        int streamId = configuredOutputs.keyAt(j);
                        OutputConfiguration outConfig = configuredOutputs.valueAt(j);
                        int surfaceId = 0;
                        for (Surface outSurface : outConfig.getSurfaces()) {
                            if (reqSurfaceId == SurfaceUtils.getSurfaceId(outSurface)) {
                                streamFound = true;
                                mStreamIdxArray[i] = streamId;
                                mSurfaceIdxArray[i] = surfaceId;
                                i++;
                                break;
                            }
                            surfaceId++;
                        }
                        if (streamFound) {
                            break;
                        }
                    }
                }

                if (!streamFound) {
                    mStreamIdxArray = null;
                    mSurfaceIdxArray = null;
                    throw new IllegalArgumentException(
                            "CaptureRequest contains unconfigured Input/Output Surface!");
                }
            }
            mSurfaceConverted = true;
        }
    }

    /**
     * @hide
     */
    public void recoverStreamIdToSurface() {
        synchronized (mSurfacesLock) {
            if (!mSurfaceConverted) {
                Log.v(TAG, "Cannot convert already converted surfaces!");
                return;
            }

            mStreamIdxArray = null;
            mSurfaceIdxArray = null;
            mSurfaceConverted = false;
        }
    }

    @SuppressWarnings("Finalize")
    @FlaggedApi(Flags.FLAG_SURFACE_LEAK_FIX)
    @Override
    protected void finalize() {
        if (mReleaseSurfaces) {
            for (Surface s : mSurfaceSet) {
                s.release();
            }
        }
    }

    /**
     * A builder for capture requests.
     *
     * <p>To obtain a builder instance, use the
     * {@link CameraDevice#createCaptureRequest} or {@link CameraManager#createCaptureRequest}
     * method, which initializes the request fields to one of the templates defined in
     * {@link CameraDevice}.
     *
     * @see CameraDevice#createCaptureRequest
     * @see CameraDevice#TEMPLATE_PREVIEW
     * @see CameraDevice#TEMPLATE_RECORD
     * @see CameraDevice#TEMPLATE_STILL_CAPTURE
     * @see CameraDevice#TEMPLATE_VIDEO_SNAPSHOT
     * @see CameraDevice#TEMPLATE_MANUAL
     * @see CameraManager#createCaptureRequest
     */
    public final static class Builder {

        private final CaptureRequest mRequest;

        /**
         * Initialize the builder using the template; the request takes
         * ownership of the template.
         *
         * @param template Template settings for this capture request.
         * @param reprocess Indicates whether to create a reprocess capture request. {@code true}
         *                  to create a reprocess capture request. {@code false} to create a regular
         *                  capture request.
         * @param reprocessableSessionId The ID of the camera capture session this capture is
         *                               created for. This is used to validate if the application
         *                               submits a reprocess capture request to the same session
         *                               where the {@link TotalCaptureResult}, used to create the
         *                               reprocess capture, came from.
         * @param logicalCameraId Camera Id of the actively open camera that instantiates the
         *                        Builder.
         * @param physicalCameraIdSet A set of physical camera ids that can be used to customize
         *                            the request for a specific physical camera.
         *
         * @throws IllegalArgumentException If creating a reprocess capture request with an invalid
         *                                  reprocessableSessionId.
         * @hide
         */
        public Builder(CameraMetadataNative template, boolean reprocess,
                int reprocessableSessionId, String logicalCameraId,
                Set<String> physicalCameraIdSet) {
            mRequest = new CaptureRequest(template, reprocess, reprocessableSessionId,
                    logicalCameraId, physicalCameraIdSet);
        }

        /**
         * <p>Add a surface to the list of targets for this request</p>
         *
         * <p>The Surface added must be one of the surfaces included in the most
         * recent call to {@link CameraDevice#createCaptureSession}, when the
         * request is given to the camera device.</p>
         *
         * <p>Adding a target more than once has no effect.</p>
         *
         * @param outputTarget Surface to use as an output target for this request
         */
        public void addTarget(@NonNull Surface outputTarget) {
            mRequest.mSurfaceSet.add(outputTarget);
        }

        /**
         * <p>Remove a surface from the list of targets for this request.</p>
         *
         * <p>Removing a target that is not currently added has no effect.</p>
         *
         * @param outputTarget Surface to use as an output target for this request
         */
        public void removeTarget(@NonNull Surface outputTarget) {
            mRequest.mSurfaceSet.remove(outputTarget);
        }

        /**
         * Set a capture request field to a value. The field definitions can be
         * found in {@link CaptureRequest}.
         *
         * <p>Setting a field to {@code null} will remove that field from the capture request.
         * Unless the field is optional, removing it will likely produce an error from the camera
         * device when the request is submitted.</p>
         *
         * @param key The metadata field to write.
         * @param value The value to set the field to, which must be of a matching
         * type to the key.
         */
        public <T> void set(@NonNull Key<T> key, T value) {
            mRequest.mLogicalCameraSettings.set(key, value);
        }

        /**
         * Get a capture request field value. The field definitions can be
         * found in {@link CaptureRequest}.
         *
         * @throws IllegalArgumentException if the key was not valid
         *
         * @param key The metadata field to read.
         * @return The value of that key, or {@code null} if the field is not set.
         */
        @Nullable
        public <T> T get(Key<T> key) {
            return mRequest.mLogicalCameraSettings.get(key);
        }

        /**
         * Set a capture request field to a value. The field definitions can be
         * found in {@link CaptureRequest}.
         *
         * <p>Setting a field to {@code null} will remove that field from the capture request.
         * Unless the field is optional, removing it will likely produce an error from the camera
         * device when the request is submitted.</p>
         *
         *<p>This method can be called for logical camera devices, which are devices that have
         * REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA capability and calls to
         * {@link CameraCharacteristics#getPhysicalCameraIds} return a non-empty set of
         * physical devices that are backing the logical camera. The camera Id included in the
         * 'physicalCameraId' argument selects an individual physical device that will receive
         * the customized capture request field.</p>
         *
         * @throws IllegalArgumentException if the physical camera id is not valid
         *
         * @param key The metadata field to write.
         * @param value The value to set the field to, which must be of a matching type to the key.
         * @param physicalCameraId A valid physical camera Id. The valid camera Ids can be obtained
         *                         via calls to {@link CameraCharacteristics#getPhysicalCameraIds}.
         * @return The builder object.
         */
        public <T> Builder setPhysicalCameraKey(@NonNull Key<T> key, T value,
                @NonNull String physicalCameraId) {
            if (!mRequest.mPhysicalCameraSettings.containsKey(physicalCameraId)) {
                throw new IllegalArgumentException("Physical camera id: " + physicalCameraId +
                        " is not valid!");
            }

            mRequest.mPhysicalCameraSettings.get(physicalCameraId).set(key, value);

            return this;
        }

        /**
         * Get a capture request field value for a specific physical camera Id. The field
         * definitions can be found in {@link CaptureRequest}.
         *
         *<p>This method can be called for logical camera devices, which are devices that have
         * REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA capability and calls to
         * {@link CameraCharacteristics#getPhysicalCameraIds} return a non-empty list of
         * physical devices that are backing the logical camera. The camera Id included in the
         * 'physicalCameraId' argument selects an individual physical device and returns
         * its specific capture request field.</p>
         *
         * @throws IllegalArgumentException if the key or physical camera id were not valid
         *
         * @param key The metadata field to read.
         * @param physicalCameraId A valid physical camera Id. The valid camera Ids can be obtained
         *                         via calls to {@link CameraCharacteristics#getPhysicalCameraIds}.
         * @return The value of that key, or {@code null} if the field is not set.
         */
        @Nullable
        public <T> T getPhysicalCameraKey(Key<T> key,@NonNull String physicalCameraId) {
            if (!mRequest.mPhysicalCameraSettings.containsKey(physicalCameraId)) {
                throw new IllegalArgumentException("Physical camera id: " + physicalCameraId +
                        " is not valid!");
            }

            return mRequest.mPhysicalCameraSettings.get(physicalCameraId).get(key);
        }

        /**
         * Set a tag for this request.
         *
         * <p>This tag is not used for anything by the camera device, but can be
         * used by an application to easily identify a CaptureRequest when it is
         * returned by
         * {@link CameraCaptureSession.CaptureCallback#onCaptureCompleted CaptureCallback.onCaptureCompleted}.</p>
         *
         * <p>If the application overrides the tag object's {@link Object#toString} function, the
         * returned string must not contain personal identifiable information.</p>
         *
         * @param tag an arbitrary Object to store with this request
         * @see CaptureRequest#getTag
         */
        public void setTag(@Nullable Object tag) {
            mRequest.mUserTag = tag;
        }

        /**
         * <p>Mark this request as part of a constrained high speed request list created by
         * {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList}.
         * A constrained high speed request list contains some constrained high speed capture
         * requests with certain interleaved pattern that is suitable for high speed preview/video
         * streaming.</p>
         *
         * @hide
         */
        @UnsupportedAppUsage
        public void setPartOfCHSRequestList(boolean partOfCHSList) {
            mRequest.mIsPartOfCHSRequestList = partOfCHSList;
        }

        /**
         * Build a request using the current target Surfaces and settings.
         * <p>Note that, although it is possible to create a {@code CaptureRequest} with no target
         * {@link Surface}s, passing such a request into {@link CameraCaptureSession#capture},
         * {@link CameraCaptureSession#captureBurst},
         * {@link CameraCaptureSession#setRepeatingBurst}, or
         * {@link CameraCaptureSession#setRepeatingRequest} will cause that method to throw an
         * {@link IllegalArgumentException}.</p>
         *
         * @return A new capture request instance, ready for submission to the
         * camera device.
         */
        @NonNull
        public CaptureRequest build() {
            return new CaptureRequest(mRequest);
        }

        /**
         * @hide
         */
        public boolean isEmpty() {
            return mRequest.mLogicalCameraSettings.isEmpty();
        }

    }

    /*@O~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * The key entries below this point are generated from metadata
     * definitions in /system/media/camera/docs. Do not modify by hand or
     * modify the comment blocks at the start or end.
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~*/

    /**
     * <p>The mode control selects how the image data is converted from the
     * sensor's native color into linear sRGB color.</p>
     * <p>When auto-white balance (AWB) is enabled with {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode}, this
     * control is overridden by the AWB routine. When AWB is disabled, the
     * application controls how the color mapping is performed.</p>
     * <p>We define the expected processing pipeline below. For consistency
     * across devices, this is always the case with TRANSFORM_MATRIX.</p>
     * <p>When either FAST or HIGH_QUALITY is used, the camera device may
     * do additional processing but {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} and
     * {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform} will still be provided by the
     * camera device (in the results) and be roughly correct.</p>
     * <p>Switching to TRANSFORM_MATRIX and using the data provided from
     * FAST or HIGH_QUALITY will yield a picture with the same white point
     * as what was produced by the camera device in the earlier frame.</p>
     * <p>The expected processing pipeline is as follows:</p>
     * <p><img alt="White balance processing pipeline" src="/reference/images/camera2/metadata/android.colorCorrection.mode/processing_pipeline.png" /></p>
     * <p>The white balance is encoded by two values, a 4-channel white-balance
     * gain vector (applied in the Bayer domain), and a 3x3 color transform
     * matrix (applied after demosaic).</p>
     * <p>The 4-channel white-balance gains are defined as:</p>
     * <pre><code>{@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} = [ R G_even G_odd B ]
     * </code></pre>
     * <p>where <code>G_even</code> is the gain for green pixels on even rows of the
     * output, and <code>G_odd</code> is the gain for green pixels on the odd rows.
     * These may be identical for a given camera device implementation; if
     * the camera device does not support a separate gain for even/odd green
     * channels, it will use the <code>G_even</code> value, and write <code>G_odd</code> equal to
     * <code>G_even</code> in the output result metadata.</p>
     * <p>The matrices for color transforms are defined as a 9-entry vector:</p>
     * <pre><code>{@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform} = [ I0 I1 I2 I3 I4 I5 I6 I7 I8 ]
     * </code></pre>
     * <p>which define a transform from input sensor colors, <code>P_in = [ r g b ]</code>,
     * to output linear sRGB, <code>P_out = [ r' g' b' ]</code>,</p>
     * <p>with colors as follows:</p>
     * <pre><code>r' = I0r + I1g + I2b
     * g' = I3r + I4g + I5b
     * b' = I6r + I7g + I8b
     * </code></pre>
     * <p>Both the input and output value ranges must match. Overflow/underflow
     * values are clipped to fit within the range.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #COLOR_CORRECTION_MODE_TRANSFORM_MATRIX TRANSFORM_MATRIX}</li>
     *   <li>{@link #COLOR_CORRECTION_MODE_FAST FAST}</li>
     *   <li>{@link #COLOR_CORRECTION_MODE_HIGH_QUALITY HIGH_QUALITY}</li>
     * </ul>
     *
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see #COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
     * @see #COLOR_CORRECTION_MODE_FAST
     * @see #COLOR_CORRECTION_MODE_HIGH_QUALITY
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> COLOR_CORRECTION_MODE =
            new Key<Integer>("android.colorCorrection.mode", int.class);

    /**
     * <p>A color transform matrix to use to transform
     * from sensor RGB color space to output linear sRGB color space.</p>
     * <p>This matrix is either set by the camera device when the request
     * {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} is not TRANSFORM_MATRIX, or
     * directly by the application in the request when the
     * {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} is TRANSFORM_MATRIX.</p>
     * <p>In the latter case, the camera device may round the matrix to account
     * for precision issues; the final rounded matrix should be reported back
     * in this matrix result metadata. The transform should keep the magnitude
     * of the output color values within <code>[0, 1.0]</code> (assuming input color
     * values is within the normalized range <code>[0, 1.0]</code>), or clipping may occur.</p>
     * <p>The valid range of each matrix element varies on different devices, but
     * values within [-1.5, 3.0] are guaranteed not to be clipped.</p>
     * <p><b>Units</b>: Unitless scale factors</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    @PublicKey
    @NonNull
    public static final Key<android.hardware.camera2.params.ColorSpaceTransform> COLOR_CORRECTION_TRANSFORM =
            new Key<android.hardware.camera2.params.ColorSpaceTransform>("android.colorCorrection.transform", android.hardware.camera2.params.ColorSpaceTransform.class);

    /**
     * <p>Gains applying to Bayer raw color channels for
     * white-balance.</p>
     * <p>These per-channel gains are either set by the camera device
     * when the request {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} is not
     * TRANSFORM_MATRIX, or directly by the application in the
     * request when the {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} is
     * TRANSFORM_MATRIX.</p>
     * <p>The gains in the result metadata are the gains actually
     * applied by the camera device to the current frame.</p>
     * <p>The valid range of gains varies on different devices, but gains
     * between [1.0, 3.0] are guaranteed not to be clipped. Even if a given
     * device allows gains below 1.0, this is usually not recommended because
     * this can create color artifacts.</p>
     * <p><b>Units</b>: Unitless gain factors</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    @PublicKey
    @NonNull
    public static final Key<android.hardware.camera2.params.RggbChannelVector> COLOR_CORRECTION_GAINS =
            new Key<android.hardware.camera2.params.RggbChannelVector>("android.colorCorrection.gains", android.hardware.camera2.params.RggbChannelVector.class);

    /**
     * <p>Mode of operation for the chromatic aberration correction algorithm.</p>
     * <p>Chromatic (color) aberration is caused by the fact that different wavelengths of light
     * can not focus on the same point after exiting from the lens. This metadata defines
     * the high level control of chromatic aberration correction algorithm, which aims to
     * minimize the chromatic artifacts that may occur along the object boundaries in an
     * image.</p>
     * <p>FAST/HIGH_QUALITY both mean that camera device determined aberration
     * correction will be applied. HIGH_QUALITY mode indicates that the camera device will
     * use the highest-quality aberration correction algorithms, even if it slows down
     * capture rate. FAST means the camera device will not slow down capture rate when
     * applying aberration correction.</p>
     * <p>LEGACY devices will always be in FAST mode.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #COLOR_CORRECTION_ABERRATION_MODE_OFF OFF}</li>
     *   <li>{@link #COLOR_CORRECTION_ABERRATION_MODE_FAST FAST}</li>
     *   <li>{@link #COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY HIGH_QUALITY}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES android.colorCorrection.availableAberrationModes}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES
     * @see #COLOR_CORRECTION_ABERRATION_MODE_OFF
     * @see #COLOR_CORRECTION_ABERRATION_MODE_FAST
     * @see #COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> COLOR_CORRECTION_ABERRATION_MODE =
            new Key<Integer>("android.colorCorrection.aberrationMode", int.class);

    /**
     * <p>The desired setting for the camera device's auto-exposure
     * algorithm's antibanding compensation.</p>
     * <p>Some kinds of lighting fixtures, such as some fluorescent
     * lights, flicker at the rate of the power supply frequency
     * (60Hz or 50Hz, depending on country). While this is
     * typically not noticeable to a person, it can be visible to
     * a camera device. If a camera sets its exposure time to the
     * wrong value, the flicker may become visible in the
     * viewfinder as flicker or in a final captured image, as a
     * set of variable-brightness bands across the image.</p>
     * <p>Therefore, the auto-exposure routines of camera devices
     * include antibanding routines that ensure that the chosen
     * exposure value will not cause such banding. The choice of
     * exposure time depends on the rate of flicker, which the
     * camera device can detect automatically, or the expected
     * rate can be selected by the application using this
     * control.</p>
     * <p>A given camera device may not support all of the possible
     * options for the antibanding mode. The
     * {@link CameraCharacteristics#CONTROL_AE_AVAILABLE_ANTIBANDING_MODES android.control.aeAvailableAntibandingModes} key contains
     * the available modes for a given camera device.</p>
     * <p>AUTO mode is the default if it is available on given
     * camera device. When AUTO mode is not available, the
     * default will be either 50HZ or 60HZ, and both 50HZ
     * and 60HZ will be available.</p>
     * <p>If manual exposure control is enabled (by setting
     * {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} or {@link CaptureRequest#CONTROL_MODE android.control.mode} to OFF),
     * then this setting has no effect, and the application must
     * ensure it selects exposure times that do not cause banding
     * issues. The {@link CaptureResult#STATISTICS_SCENE_FLICKER android.statistics.sceneFlicker} key can assist
     * the application in this.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_AE_ANTIBANDING_MODE_OFF OFF}</li>
     *   <li>{@link #CONTROL_AE_ANTIBANDING_MODE_50HZ 50HZ}</li>
     *   <li>{@link #CONTROL_AE_ANTIBANDING_MODE_60HZ 60HZ}</li>
     *   <li>{@link #CONTROL_AE_ANTIBANDING_MODE_AUTO AUTO}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br></p>
     * <p>{@link CameraCharacteristics#CONTROL_AE_AVAILABLE_ANTIBANDING_MODES android.control.aeAvailableAntibandingModes}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_AVAILABLE_ANTIBANDING_MODES
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see CaptureResult#STATISTICS_SCENE_FLICKER
     * @see #CONTROL_AE_ANTIBANDING_MODE_OFF
     * @see #CONTROL_AE_ANTIBANDING_MODE_50HZ
     * @see #CONTROL_AE_ANTIBANDING_MODE_60HZ
     * @see #CONTROL_AE_ANTIBANDING_MODE_AUTO
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_AE_ANTIBANDING_MODE =
            new Key<Integer>("android.control.aeAntibandingMode", int.class);

    /**
     * <p>Adjustment to auto-exposure (AE) target image
     * brightness.</p>
     * <p>The adjustment is measured as a count of steps, with the
     * step size defined by {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP android.control.aeCompensationStep} and the
     * allowed range by {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_RANGE android.control.aeCompensationRange}.</p>
     * <p>For example, if the exposure value (EV) step is 0.333, '6'
     * will mean an exposure compensation of +2 EV; -3 will mean an
     * exposure compensation of -1 EV. One EV represents a doubling
     * of image brightness. Note that this control will only be
     * effective if {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} <code>!=</code> OFF. This control
     * will take effect even when {@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} <code>== true</code>.</p>
     * <p>In the event of exposure compensation value being changed, camera device
     * may take several frames to reach the newly requested exposure target.
     * During that time, {@link CaptureResult#CONTROL_AE_STATE android.control.aeState} field will be in the SEARCHING
     * state. Once the new exposure target is reached, {@link CaptureResult#CONTROL_AE_STATE android.control.aeState} will
     * change from SEARCHING to either CONVERGED, LOCKED (if AE lock is enabled), or
     * FLASH_REQUIRED (if the scene is too dark for still capture).</p>
     * <p><b>Units</b>: Compensation steps</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_RANGE android.control.aeCompensationRange}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_COMPENSATION_RANGE
     * @see CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP
     * @see CaptureRequest#CONTROL_AE_LOCK
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureResult#CONTROL_AE_STATE
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_AE_EXPOSURE_COMPENSATION =
            new Key<Integer>("android.control.aeExposureCompensation", int.class);

    /**
     * <p>Whether auto-exposure (AE) is currently locked to its latest
     * calculated values.</p>
     * <p>When set to <code>true</code> (ON), the AE algorithm is locked to its latest parameters,
     * and will not change exposure settings until the lock is set to <code>false</code> (OFF).</p>
     * <p>Note that even when AE is locked, the flash may be fired if
     * the {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is ON_AUTO_FLASH /
     * ON_ALWAYS_FLASH / ON_AUTO_FLASH_REDEYE.</p>
     * <p>When {@link CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION android.control.aeExposureCompensation} is changed, even if the AE lock
     * is ON, the camera device will still adjust its exposure value.</p>
     * <p>If AE precapture is triggered (see {@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger})
     * when AE is already locked, the camera device will not change the exposure time
     * ({@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime}) and sensitivity ({@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity})
     * parameters. The flash may be fired if the {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode}
     * is ON_AUTO_FLASH/ON_AUTO_FLASH_REDEYE and the scene is too dark. If the
     * {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is ON_ALWAYS_FLASH, the scene may become overexposed.
     * Similarly, AE precapture trigger CANCEL has no effect when AE is already locked.</p>
     * <p>When an AE precapture sequence is triggered, AE unlock will not be able to unlock
     * the AE if AE is locked by the camera device internally during precapture metering
     * sequence In other words, submitting requests with AE unlock has no effect for an
     * ongoing precapture metering sequence. Otherwise, the precapture metering sequence
     * will never succeed in a sequence of preview requests where AE lock is always set
     * to <code>false</code>.</p>
     * <p>Since the camera device has a pipeline of in-flight requests, the settings that
     * get locked do not necessarily correspond to the settings that were present in the
     * latest capture result received from the camera device, since additional captures
     * and AE updates may have occurred even before the result was sent out. If an
     * application is switching between automatic and manual control and wishes to eliminate
     * any flicker during the switch, the following procedure is recommended:</p>
     * <ol>
     * <li>Starting in auto-AE mode:</li>
     * <li>Lock AE</li>
     * <li>Wait for the first result to be output that has the AE locked</li>
     * <li>Copy exposure settings from that result into a request, set the request to manual AE</li>
     * <li>Submit the capture request, proceed to run manual AE as desired.</li>
     * </ol>
     * <p>See {@link CaptureResult#CONTROL_AE_STATE android.control.aeState} for AE lock related state transition details.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CaptureResult#CONTROL_AE_STATE
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_SENSITIVITY
     */
    @PublicKey
    @NonNull
    public static final Key<Boolean> CONTROL_AE_LOCK =
            new Key<Boolean>("android.control.aeLock", boolean.class);

    /**
     * <p>The desired mode for the camera device's
     * auto-exposure routine.</p>
     * <p>This control is only effective if {@link CaptureRequest#CONTROL_MODE android.control.mode} is
     * AUTO.</p>
     * <p>When set to any of the ON modes, the camera device's
     * auto-exposure routine is enabled, overriding the
     * application's selected exposure time, sensor sensitivity,
     * and frame duration ({@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime},
     * {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}, and
     * {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration}). If one of the FLASH modes
     * is selected, the camera device's flash unit controls are
     * also overridden.</p>
     * <p>The FLASH modes are only available if the camera device
     * has a flash unit ({@link CameraCharacteristics#FLASH_INFO_AVAILABLE android.flash.info.available} is <code>true</code>).</p>
     * <p>If flash TORCH mode is desired, this field must be set to
     * ON or OFF, and {@link CaptureRequest#FLASH_MODE android.flash.mode} set to TORCH.</p>
     * <p>When set to any of the ON modes, the values chosen by the
     * camera device auto-exposure routine for the overridden
     * fields for a given capture will be available in its
     * CaptureResult.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_AE_MODE_OFF OFF}</li>
     *   <li>{@link #CONTROL_AE_MODE_ON ON}</li>
     *   <li>{@link #CONTROL_AE_MODE_ON_AUTO_FLASH ON_AUTO_FLASH}</li>
     *   <li>{@link #CONTROL_AE_MODE_ON_ALWAYS_FLASH ON_ALWAYS_FLASH}</li>
     *   <li>{@link #CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE ON_AUTO_FLASH_REDEYE}</li>
     *   <li>{@link #CONTROL_AE_MODE_ON_EXTERNAL_FLASH ON_EXTERNAL_FLASH}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#CONTROL_AE_AVAILABLE_MODES android.control.aeAvailableModes}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_AVAILABLE_MODES
     * @see CaptureRequest#CONTROL_MODE
     * @see CameraCharacteristics#FLASH_INFO_AVAILABLE
     * @see CaptureRequest#FLASH_MODE
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see CaptureRequest#SENSOR_SENSITIVITY
     * @see #CONTROL_AE_MODE_OFF
     * @see #CONTROL_AE_MODE_ON
     * @see #CONTROL_AE_MODE_ON_AUTO_FLASH
     * @see #CONTROL_AE_MODE_ON_ALWAYS_FLASH
     * @see #CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE
     * @see #CONTROL_AE_MODE_ON_EXTERNAL_FLASH
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_AE_MODE =
            new Key<Integer>("android.control.aeMode", int.class);

    /**
     * <p>List of metering areas to use for auto-exposure adjustment.</p>
     * <p>Not available if {@link CameraCharacteristics#CONTROL_MAX_REGIONS_AE android.control.maxRegionsAe} is 0.
     * Otherwise will always be present.</p>
     * <p>The maximum number of regions supported by the device is determined by the value
     * of {@link CameraCharacteristics#CONTROL_MAX_REGIONS_AE android.control.maxRegionsAe}.</p>
     * <p>For devices not supporting {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the coordinate
     * system always follows that of {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, with (0,0) being
     * the top-left pixel in the active pixel array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.height - 1) being the bottom-right pixel in the
     * active pixel array.</p>
     * <p>For devices supporting {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the coordinate
     * system depends on the mode being set.
     * When the distortion correction mode is OFF, the coordinate system follows
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}, with
     * <code>(0, 0)</code> being the top-left pixel of the pre-correction active array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}.height - 1) being the bottom-right
     * pixel in the pre-correction active pixel array.
     * When the distortion correction mode is not OFF, the coordinate system follows
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, with
     * <code>(0, 0)</code> being the top-left pixel of the active array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.height - 1) being the bottom-right pixel in the
     * active pixel array.</p>
     * <p>The weight must be within <code>[0, 1000]</code>, and represents a weight
     * for every pixel in the area. This means that a large metering area
     * with the same weight as a smaller area will have more effect in
     * the metering result. Metering areas can partially overlap and the
     * camera device will add the weights in the overlap region.</p>
     * <p>The weights are relative to weights of other exposure metering regions, so if only one
     * region is used, all non-zero weights will have the same effect. A region with 0
     * weight is ignored.</p>
     * <p>If all regions have 0 weight, then no specific metering area needs to be used by the
     * camera device.</p>
     * <p>If the metering region is outside the used {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} returned in
     * capture result metadata, the camera device will ignore the sections outside the crop
     * region and output only the intersection rectangle as the metering region in the result
     * metadata.  If the region is entirely outside the crop region, it will be ignored and
     * not reported in the result metadata.</p>
     * <p>When setting the AE metering regions, the application must consider the additional
     * crop resulted from the aspect ratio differences between the preview stream and
     * {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}. For example, if the {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} is the full
     * active array size with 4:3 aspect ratio, and the preview stream is 16:9,
     * the boundary of AE regions will be [0, y_crop] and
     * [active_width, active_height - 2 * y_crop] rather than [0, 0] and
     * [active_width, active_height], where y_crop is the additional crop due to aspect ratio
     * mismatch.</p>
     * <p>Starting from API level 30, the coordinate system of activeArraySize or
     * preCorrectionActiveArraySize is used to represent post-zoomRatio field of view, not
     * pre-zoom field of view. This means that the same aeRegions values at different
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} represent different parts of the scene. The aeRegions
     * coordinates are relative to the activeArray/preCorrectionActiveArray representing the
     * zoomed field of view. If {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} is set to 1.0 (default), the same
     * aeRegions at different {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} still represent the same parts of the
     * scene as they do before. See {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} for details. Whether to use
     * activeArraySize or preCorrectionActiveArraySize still depends on distortion correction
     * mode.</p>
     * <p>For camera devices with the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR }
     * capability or devices where
     * {@link CameraCharacteristics#getAvailableCaptureRequestKeys }
     * lists {@link CaptureRequest#SENSOR_PIXEL_MODE {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode}}
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.activeArraySizeMaximumResolution} /
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.preCorrectionActiveArraySizeMaximumResolution} must be used as the
     * coordinate system for requests where {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode} is set to
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION }.</p>
     * <p><b>Units</b>: Pixel coordinates within {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} or
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize} depending on
     * distortion correction capability and mode</p>
     * <p><b>Range of valid values:</b><br>
     * Coordinates must be between <code>[(0,0), (width, height))</code> of
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} or {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}
     * depending on distortion correction capability and mode</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_MAX_REGIONS_AE
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CaptureRequest#DISTORTION_CORRECTION_MODE
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     */
    @PublicKey
    @NonNull
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AE_REGIONS =
            new Key<android.hardware.camera2.params.MeteringRectangle[]>("android.control.aeRegions", android.hardware.camera2.params.MeteringRectangle[].class);

    /**
     * <p>Range over which the auto-exposure routine can
     * adjust the capture frame rate to maintain good
     * exposure.</p>
     * <p>Only constrains auto-exposure (AE) algorithm, not
     * manual control of {@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime} and
     * {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration}.</p>
     * <p>Note that the actual achievable max framerate also depends on the minimum frame
     * duration of the output streams. The max frame rate will be
     * <code>min(aeTargetFpsRange.maxFps, 1 / max(individual stream min durations)</code>. For example,
     * if the application sets this key to <code>{60, 60}</code>, but the maximum minFrameDuration among
     * all configured streams is 33ms, the maximum framerate won't be 60fps, but will be
     * 30fps.</p>
     * <p>To start a CaptureSession with a target FPS range different from the
     * capture request template's default value, the application
     * is strongly recommended to call
     * {@link android.hardware.camera2.params.SessionConfiguration#setSessionParameters }
     * with the target fps range before creating the capture session. The aeTargetFpsRange is
     * typically a session parameter. Specifying it at session creation time helps avoid
     * session reconfiguration delays in cases like 60fps or high speed recording.</p>
     * <p><b>Units</b>: Frames per second (FPS)</p>
     * <p><b>Range of valid values:</b><br>
     * Any of the entries in {@link CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES android.control.aeAvailableTargetFpsRanges}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     */
    @PublicKey
    @NonNull
    public static final Key<android.util.Range<Integer>> CONTROL_AE_TARGET_FPS_RANGE =
            new Key<android.util.Range<Integer>>("android.control.aeTargetFpsRange", new TypeReference<android.util.Range<Integer>>() {{ }});

    /**
     * <p>Whether the camera device will trigger a precapture
     * metering sequence when it processes this request.</p>
     * <p>This entry is normally set to IDLE, or is not
     * included at all in the request settings. When included and
     * set to START, the camera device will trigger the auto-exposure (AE)
     * precapture metering sequence.</p>
     * <p>When set to CANCEL, the camera device will cancel any active
     * precapture metering trigger, and return to its initial AE state.
     * If a precapture metering sequence is already completed, and the camera
     * device has implicitly locked the AE for subsequent still capture, the
     * CANCEL trigger will unlock the AE and return to its initial AE state.</p>
     * <p>The precapture sequence should be triggered before starting a
     * high-quality still capture for final metering decisions to
     * be made, and for firing pre-capture flash pulses to estimate
     * scene brightness and required final capture flash power, when
     * the flash is enabled.</p>
     * <p>Normally, this entry should be set to START for only a
     * single request, and the application should wait until the
     * sequence completes before starting a new one.</p>
     * <p>When a precapture metering sequence is finished, the camera device
     * may lock the auto-exposure routine internally to be able to accurately expose the
     * subsequent still capture image (<code>{@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent} == STILL_CAPTURE</code>).
     * For this case, the AE may not resume normal scan if no subsequent still capture is
     * submitted. To ensure that the AE routine restarts normal scan, the application should
     * submit a request with <code>{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} == true</code>, followed by a request
     * with <code>{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} == false</code>, if the application decides not to submit a
     * still capture request after the precapture sequence completes. Alternatively, for
     * API level 23 or newer devices, the CANCEL can be used to unlock the camera device
     * internally locked AE if the application doesn't submit a still capture request after
     * the AE precapture trigger. Note that, the CANCEL was added in API level 23, and must not
     * be used in devices that have earlier API levels.</p>
     * <p>The exact effect of auto-exposure (AE) precapture trigger
     * depends on the current AE mode and state; see
     * {@link CaptureResult#CONTROL_AE_STATE android.control.aeState} for AE precapture state transition
     * details.</p>
     * <p>On LEGACY-level devices, the precapture trigger is not supported;
     * capturing a high-resolution JPEG image will automatically trigger a
     * precapture sequence before the high-resolution capture, including
     * potentially firing a pre-capture flash.</p>
     * <p>Using the precapture trigger and the auto-focus trigger {@link CaptureRequest#CONTROL_AF_TRIGGER android.control.afTrigger}
     * simultaneously is allowed. However, since these triggers often require cooperation between
     * the auto-focus and auto-exposure routines (for example, the may need to be enabled for a
     * focus sweep), the camera device may delay acting on a later trigger until the previous
     * trigger has been fully handled. This may lead to longer intervals between the trigger and
     * changes to {@link CaptureResult#CONTROL_AE_STATE android.control.aeState} indicating the start of the precapture sequence, for
     * example.</p>
     * <p>If both the precapture and the auto-focus trigger are activated on the same request, then
     * the camera device will complete them in the optimal order for that device.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_AE_PRECAPTURE_TRIGGER_IDLE IDLE}</li>
     *   <li>{@link #CONTROL_AE_PRECAPTURE_TRIGGER_START START}</li>
     *   <li>{@link #CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL CANCEL}</li>
     * </ul>
     *
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#CONTROL_AE_LOCK
     * @see CaptureResult#CONTROL_AE_STATE
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see #CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
     * @see #CONTROL_AE_PRECAPTURE_TRIGGER_START
     * @see #CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_AE_PRECAPTURE_TRIGGER =
            new Key<Integer>("android.control.aePrecaptureTrigger", int.class);

    /**
     * <p>Whether auto-focus (AF) is currently enabled, and what
     * mode it is set to.</p>
     * <p>Only effective if {@link CaptureRequest#CONTROL_MODE android.control.mode} = AUTO and the lens is not fixed focus
     * (i.e. <code>{@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance} &gt; 0</code>). Also note that
     * when {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is OFF, the behavior of AF is device
     * dependent. It is recommended to lock AF by using {@link CaptureRequest#CONTROL_AF_TRIGGER android.control.afTrigger} before
     * setting {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} to OFF, or set AF mode to OFF when AE is OFF.</p>
     * <p>If the lens is controlled by the camera device auto-focus algorithm,
     * the camera device will report the current AF status in {@link CaptureResult#CONTROL_AF_STATE android.control.afState}
     * in result metadata.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_AF_MODE_OFF OFF}</li>
     *   <li>{@link #CONTROL_AF_MODE_AUTO AUTO}</li>
     *   <li>{@link #CONTROL_AF_MODE_MACRO MACRO}</li>
     *   <li>{@link #CONTROL_AF_MODE_CONTINUOUS_VIDEO CONTINUOUS_VIDEO}</li>
     *   <li>{@link #CONTROL_AF_MODE_CONTINUOUS_PICTURE CONTINUOUS_PICTURE}</li>
     *   <li>{@link #CONTROL_AF_MODE_EDOF EDOF}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#CONTROL_AF_AVAILABLE_MODES android.control.afAvailableModes}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CameraCharacteristics#CONTROL_AF_AVAILABLE_MODES
     * @see CaptureResult#CONTROL_AF_STATE
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     * @see CaptureRequest#CONTROL_MODE
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     * @see #CONTROL_AF_MODE_OFF
     * @see #CONTROL_AF_MODE_AUTO
     * @see #CONTROL_AF_MODE_MACRO
     * @see #CONTROL_AF_MODE_CONTINUOUS_VIDEO
     * @see #CONTROL_AF_MODE_CONTINUOUS_PICTURE
     * @see #CONTROL_AF_MODE_EDOF
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_AF_MODE =
            new Key<Integer>("android.control.afMode", int.class);

    /**
     * <p>List of metering areas to use for auto-focus.</p>
     * <p>Not available if {@link CameraCharacteristics#CONTROL_MAX_REGIONS_AF android.control.maxRegionsAf} is 0.
     * Otherwise will always be present.</p>
     * <p>The maximum number of focus areas supported by the device is determined by the value
     * of {@link CameraCharacteristics#CONTROL_MAX_REGIONS_AF android.control.maxRegionsAf}.</p>
     * <p>For devices not supporting {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the coordinate
     * system always follows that of {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, with (0,0) being
     * the top-left pixel in the active pixel array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.height - 1) being the bottom-right pixel in the
     * active pixel array.</p>
     * <p>For devices supporting {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the coordinate
     * system depends on the mode being set.
     * When the distortion correction mode is OFF, the coordinate system follows
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}, with
     * <code>(0, 0)</code> being the top-left pixel of the pre-correction active array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}.height - 1) being the bottom-right
     * pixel in the pre-correction active pixel array.
     * When the distortion correction mode is not OFF, the coordinate system follows
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, with
     * <code>(0, 0)</code> being the top-left pixel of the active array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.height - 1) being the bottom-right pixel in the
     * active pixel array.</p>
     * <p>The weight must be within <code>[0, 1000]</code>, and represents a weight
     * for every pixel in the area. This means that a large metering area
     * with the same weight as a smaller area will have more effect in
     * the metering result. Metering areas can partially overlap and the
     * camera device will add the weights in the overlap region.</p>
     * <p>The weights are relative to weights of other metering regions, so if only one region
     * is used, all non-zero weights will have the same effect. A region with 0 weight is
     * ignored.</p>
     * <p>If all regions have 0 weight, then no specific metering area needs to be used by the
     * camera device. The capture result will either be a zero weight region as well, or
     * the region selected by the camera device as the focus area of interest.</p>
     * <p>If the metering region is outside the used {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} returned in
     * capture result metadata, the camera device will ignore the sections outside the crop
     * region and output only the intersection rectangle as the metering region in the result
     * metadata. If the region is entirely outside the crop region, it will be ignored and
     * not reported in the result metadata.</p>
     * <p>When setting the AF metering regions, the application must consider the additional
     * crop resulted from the aspect ratio differences between the preview stream and
     * {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}. For example, if the {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} is the full
     * active array size with 4:3 aspect ratio, and the preview stream is 16:9,
     * the boundary of AF regions will be [0, y_crop] and
     * [active_width, active_height - 2 * y_crop] rather than [0, 0] and
     * [active_width, active_height], where y_crop is the additional crop due to aspect ratio
     * mismatch.</p>
     * <p>Starting from API level 30, the coordinate system of activeArraySize or
     * preCorrectionActiveArraySize is used to represent post-zoomRatio field of view, not
     * pre-zoom field of view. This means that the same afRegions values at different
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} represent different parts of the scene. The afRegions
     * coordinates are relative to the activeArray/preCorrectionActiveArray representing the
     * zoomed field of view. If {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} is set to 1.0 (default), the same
     * afRegions at different {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} still represent the same parts of the
     * scene as they do before. See {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} for details. Whether to use
     * activeArraySize or preCorrectionActiveArraySize still depends on distortion correction
     * mode.</p>
     * <p>For camera devices with the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR }
     * capability or devices where
     * {@link CameraCharacteristics#getAvailableCaptureRequestKeys }
     * lists {@link CaptureRequest#SENSOR_PIXEL_MODE {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode}},
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.activeArraySizeMaximumResolution} /
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.preCorrectionActiveArraySizeMaximumResolution} must be used as the
     * coordinate system for requests where {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode} is set to
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION }.</p>
     * <p><b>Units</b>: Pixel coordinates within {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} or
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize} depending on
     * distortion correction capability and mode</p>
     * <p><b>Range of valid values:</b><br>
     * Coordinates must be between <code>[(0,0), (width, height))</code> of
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} or {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}
     * depending on distortion correction capability and mode</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_MAX_REGIONS_AF
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CaptureRequest#DISTORTION_CORRECTION_MODE
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     */
    @PublicKey
    @NonNull
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AF_REGIONS =
            new Key<android.hardware.camera2.params.MeteringRectangle[]>("android.control.afRegions", android.hardware.camera2.params.MeteringRectangle[].class);

    /**
     * <p>Whether the camera device will trigger autofocus for this request.</p>
     * <p>This entry is normally set to IDLE, or is not
     * included at all in the request settings.</p>
     * <p>When included and set to START, the camera device will trigger the
     * autofocus algorithm. If autofocus is disabled, this trigger has no effect.</p>
     * <p>When set to CANCEL, the camera device will cancel any active trigger,
     * and return to its initial AF state.</p>
     * <p>Generally, applications should set this entry to START or CANCEL for only a
     * single capture, and then return it to IDLE (or not set at all). Specifying
     * START for multiple captures in a row means restarting the AF operation over
     * and over again.</p>
     * <p>See {@link CaptureResult#CONTROL_AF_STATE android.control.afState} for what the trigger means for each AF mode.</p>
     * <p>Using the autofocus trigger and the precapture trigger {@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger}
     * simultaneously is allowed. However, since these triggers often require cooperation between
     * the auto-focus and auto-exposure routines (for example, the may need to be enabled for a
     * focus sweep), the camera device may delay acting on a later trigger until the previous
     * trigger has been fully handled. This may lead to longer intervals between the trigger and
     * changes to {@link CaptureResult#CONTROL_AF_STATE android.control.afState}, for example.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_AF_TRIGGER_IDLE IDLE}</li>
     *   <li>{@link #CONTROL_AF_TRIGGER_START START}</li>
     *   <li>{@link #CONTROL_AF_TRIGGER_CANCEL CANCEL}</li>
     * </ul>
     *
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CaptureResult#CONTROL_AF_STATE
     * @see #CONTROL_AF_TRIGGER_IDLE
     * @see #CONTROL_AF_TRIGGER_START
     * @see #CONTROL_AF_TRIGGER_CANCEL
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_AF_TRIGGER =
            new Key<Integer>("android.control.afTrigger", int.class);

    /**
     * <p>Whether auto-white balance (AWB) is currently locked to its
     * latest calculated values.</p>
     * <p>When set to <code>true</code> (ON), the AWB algorithm is locked to its latest parameters,
     * and will not change color balance settings until the lock is set to <code>false</code> (OFF).</p>
     * <p>Since the camera device has a pipeline of in-flight requests, the settings that
     * get locked do not necessarily correspond to the settings that were present in the
     * latest capture result received from the camera device, since additional captures
     * and AWB updates may have occurred even before the result was sent out. If an
     * application is switching between automatic and manual control and wishes to eliminate
     * any flicker during the switch, the following procedure is recommended:</p>
     * <ol>
     * <li>Starting in auto-AWB mode:</li>
     * <li>Lock AWB</li>
     * <li>Wait for the first result to be output that has the AWB locked</li>
     * <li>Copy AWB settings from that result into a request, set the request to manual AWB</li>
     * <li>Submit the capture request, proceed to run manual AWB as desired.</li>
     * </ol>
     * <p>Note that AWB lock is only meaningful when
     * {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} is in the AUTO mode; in other modes,
     * AWB is already fixed to a specific setting.</p>
     * <p>Some LEGACY devices may not support ON; the value is then overridden to OFF.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    @PublicKey
    @NonNull
    public static final Key<Boolean> CONTROL_AWB_LOCK =
            new Key<Boolean>("android.control.awbLock", boolean.class);

    /**
     * <p>Whether auto-white balance (AWB) is currently setting the color
     * transform fields, and what its illumination target
     * is.</p>
     * <p>This control is only effective if {@link CaptureRequest#CONTROL_MODE android.control.mode} is AUTO.</p>
     * <p>When set to the AUTO mode, the camera device's auto-white balance
     * routine is enabled, overriding the application's selected
     * {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}, {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} and
     * {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode}. Note that when {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode}
     * is OFF, the behavior of AWB is device dependent. It is recommended to
     * also set AWB mode to OFF or lock AWB by using {@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock} before
     * setting AE mode to OFF.</p>
     * <p>When set to the OFF mode, the camera device's auto-white balance
     * routine is disabled. The application manually controls the white
     * balance by {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}, {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains}
     * and {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode}.</p>
     * <p>When set to any other modes, the camera device's auto-white
     * balance routine is disabled. The camera device uses each
     * particular illumination target for white balance
     * adjustment. The application's values for
     * {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform},
     * {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} and
     * {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} are ignored.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_AWB_MODE_OFF OFF}</li>
     *   <li>{@link #CONTROL_AWB_MODE_AUTO AUTO}</li>
     *   <li>{@link #CONTROL_AWB_MODE_INCANDESCENT INCANDESCENT}</li>
     *   <li>{@link #CONTROL_AWB_MODE_FLUORESCENT FLUORESCENT}</li>
     *   <li>{@link #CONTROL_AWB_MODE_WARM_FLUORESCENT WARM_FLUORESCENT}</li>
     *   <li>{@link #CONTROL_AWB_MODE_DAYLIGHT DAYLIGHT}</li>
     *   <li>{@link #CONTROL_AWB_MODE_CLOUDY_DAYLIGHT CLOUDY_DAYLIGHT}</li>
     *   <li>{@link #CONTROL_AWB_MODE_TWILIGHT TWILIGHT}</li>
     *   <li>{@link #CONTROL_AWB_MODE_SHADE SHADE}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#CONTROL_AWB_AVAILABLE_MODES android.control.awbAvailableModes}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CameraCharacteristics#CONTROL_AWB_AVAILABLE_MODES
     * @see CaptureRequest#CONTROL_AWB_LOCK
     * @see CaptureRequest#CONTROL_MODE
     * @see #CONTROL_AWB_MODE_OFF
     * @see #CONTROL_AWB_MODE_AUTO
     * @see #CONTROL_AWB_MODE_INCANDESCENT
     * @see #CONTROL_AWB_MODE_FLUORESCENT
     * @see #CONTROL_AWB_MODE_WARM_FLUORESCENT
     * @see #CONTROL_AWB_MODE_DAYLIGHT
     * @see #CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
     * @see #CONTROL_AWB_MODE_TWILIGHT
     * @see #CONTROL_AWB_MODE_SHADE
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_AWB_MODE =
            new Key<Integer>("android.control.awbMode", int.class);

    /**
     * <p>List of metering areas to use for auto-white-balance illuminant
     * estimation.</p>
     * <p>Not available if {@link CameraCharacteristics#CONTROL_MAX_REGIONS_AWB android.control.maxRegionsAwb} is 0.
     * Otherwise will always be present.</p>
     * <p>The maximum number of regions supported by the device is determined by the value
     * of {@link CameraCharacteristics#CONTROL_MAX_REGIONS_AWB android.control.maxRegionsAwb}.</p>
     * <p>For devices not supporting {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the coordinate
     * system always follows that of {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, with (0,0) being
     * the top-left pixel in the active pixel array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.height - 1) being the bottom-right pixel in the
     * active pixel array.</p>
     * <p>For devices supporting {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the coordinate
     * system depends on the mode being set.
     * When the distortion correction mode is OFF, the coordinate system follows
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}, with
     * <code>(0, 0)</code> being the top-left pixel of the pre-correction active array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}.height - 1) being the bottom-right
     * pixel in the pre-correction active pixel array.
     * When the distortion correction mode is not OFF, the coordinate system follows
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, with
     * <code>(0, 0)</code> being the top-left pixel of the active array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.height - 1) being the bottom-right pixel in the
     * active pixel array.</p>
     * <p>The weight must range from 0 to 1000, and represents a weight
     * for every pixel in the area. This means that a large metering area
     * with the same weight as a smaller area will have more effect in
     * the metering result. Metering areas can partially overlap and the
     * camera device will add the weights in the overlap region.</p>
     * <p>The weights are relative to weights of other white balance metering regions, so if
     * only one region is used, all non-zero weights will have the same effect. A region with
     * 0 weight is ignored.</p>
     * <p>If all regions have 0 weight, then no specific metering area needs to be used by the
     * camera device.</p>
     * <p>If the metering region is outside the used {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} returned in
     * capture result metadata, the camera device will ignore the sections outside the crop
     * region and output only the intersection rectangle as the metering region in the result
     * metadata.  If the region is entirely outside the crop region, it will be ignored and
     * not reported in the result metadata.</p>
     * <p>When setting the AWB metering regions, the application must consider the additional
     * crop resulted from the aspect ratio differences between the preview stream and
     * {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}. For example, if the {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} is the full
     * active array size with 4:3 aspect ratio, and the preview stream is 16:9,
     * the boundary of AWB regions will be [0, y_crop] and
     * [active_width, active_height - 2 * y_crop] rather than [0, 0] and
     * [active_width, active_height], where y_crop is the additional crop due to aspect ratio
     * mismatch.</p>
     * <p>Starting from API level 30, the coordinate system of activeArraySize or
     * preCorrectionActiveArraySize is used to represent post-zoomRatio field of view, not
     * pre-zoom field of view. This means that the same awbRegions values at different
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} represent different parts of the scene. The awbRegions
     * coordinates are relative to the activeArray/preCorrectionActiveArray representing the
     * zoomed field of view. If {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} is set to 1.0 (default), the same
     * awbRegions at different {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} still represent the same parts of
     * the scene as they do before. See {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} for details. Whether to use
     * activeArraySize or preCorrectionActiveArraySize still depends on distortion correction
     * mode.</p>
     * <p>For camera devices with the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR }
     * capability or devices where
     * {@link CameraCharacteristics#getAvailableCaptureRequestKeys }
     * lists {@link CaptureRequest#SENSOR_PIXEL_MODE {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode}},
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.activeArraySizeMaximumResolution} /
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.preCorrectionActiveArraySizeMaximumResolution} must be used as the
     * coordinate system for requests where {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode} is set to
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION }.</p>
     * <p><b>Units</b>: Pixel coordinates within {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} or
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize} depending on
     * distortion correction capability and mode</p>
     * <p><b>Range of valid values:</b><br>
     * Coordinates must be between <code>[(0,0), (width, height))</code> of
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} or {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}
     * depending on distortion correction capability and mode</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_MAX_REGIONS_AWB
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CaptureRequest#DISTORTION_CORRECTION_MODE
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     */
    @PublicKey
    @NonNull
    public static final Key<android.hardware.camera2.params.MeteringRectangle[]> CONTROL_AWB_REGIONS =
            new Key<android.hardware.camera2.params.MeteringRectangle[]>("android.control.awbRegions", android.hardware.camera2.params.MeteringRectangle[].class);

    /**
     * <p>Information to the camera device 3A (auto-exposure,
     * auto-focus, auto-white balance) routines about the purpose
     * of this capture, to help the camera device to decide optimal 3A
     * strategy.</p>
     * <p>This control (except for MANUAL) is only effective if
     * <code>{@link CaptureRequest#CONTROL_MODE android.control.mode} != OFF</code> and any 3A routine is active.</p>
     * <p>All intents are supported by all devices, except that:</p>
     * <ul>
     * <li>ZERO_SHUTTER_LAG will be supported if {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains
     * PRIVATE_REPROCESSING or YUV_REPROCESSING.</li>
     * <li>MANUAL will be supported if {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains
     * MANUAL_SENSOR.</li>
     * <li>MOTION_TRACKING will be supported if {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains
     * MOTION_TRACKING.</li>
     * </ul>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_CAPTURE_INTENT_CUSTOM CUSTOM}</li>
     *   <li>{@link #CONTROL_CAPTURE_INTENT_PREVIEW PREVIEW}</li>
     *   <li>{@link #CONTROL_CAPTURE_INTENT_STILL_CAPTURE STILL_CAPTURE}</li>
     *   <li>{@link #CONTROL_CAPTURE_INTENT_VIDEO_RECORD VIDEO_RECORD}</li>
     *   <li>{@link #CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT VIDEO_SNAPSHOT}</li>
     *   <li>{@link #CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG ZERO_SHUTTER_LAG}</li>
     *   <li>{@link #CONTROL_CAPTURE_INTENT_MANUAL MANUAL}</li>
     *   <li>{@link #CONTROL_CAPTURE_INTENT_MOTION_TRACKING MOTION_TRACKING}</li>
     * </ul>
     *
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_MODE
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see #CONTROL_CAPTURE_INTENT_CUSTOM
     * @see #CONTROL_CAPTURE_INTENT_PREVIEW
     * @see #CONTROL_CAPTURE_INTENT_STILL_CAPTURE
     * @see #CONTROL_CAPTURE_INTENT_VIDEO_RECORD
     * @see #CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT
     * @see #CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG
     * @see #CONTROL_CAPTURE_INTENT_MANUAL
     * @see #CONTROL_CAPTURE_INTENT_MOTION_TRACKING
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_CAPTURE_INTENT =
            new Key<Integer>("android.control.captureIntent", int.class);

    /**
     * <p>A special color effect to apply.</p>
     * <p>When this mode is set, a color effect will be applied
     * to images produced by the camera device. The interpretation
     * and implementation of these color effects is left to the
     * implementor of the camera device, and should not be
     * depended on to be consistent (or present) across all
     * devices.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_EFFECT_MODE_OFF OFF}</li>
     *   <li>{@link #CONTROL_EFFECT_MODE_MONO MONO}</li>
     *   <li>{@link #CONTROL_EFFECT_MODE_NEGATIVE NEGATIVE}</li>
     *   <li>{@link #CONTROL_EFFECT_MODE_SOLARIZE SOLARIZE}</li>
     *   <li>{@link #CONTROL_EFFECT_MODE_SEPIA SEPIA}</li>
     *   <li>{@link #CONTROL_EFFECT_MODE_POSTERIZE POSTERIZE}</li>
     *   <li>{@link #CONTROL_EFFECT_MODE_WHITEBOARD WHITEBOARD}</li>
     *   <li>{@link #CONTROL_EFFECT_MODE_BLACKBOARD BLACKBOARD}</li>
     *   <li>{@link #CONTROL_EFFECT_MODE_AQUA AQUA}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#CONTROL_AVAILABLE_EFFECTS android.control.availableEffects}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_AVAILABLE_EFFECTS
     * @see #CONTROL_EFFECT_MODE_OFF
     * @see #CONTROL_EFFECT_MODE_MONO
     * @see #CONTROL_EFFECT_MODE_NEGATIVE
     * @see #CONTROL_EFFECT_MODE_SOLARIZE
     * @see #CONTROL_EFFECT_MODE_SEPIA
     * @see #CONTROL_EFFECT_MODE_POSTERIZE
     * @see #CONTROL_EFFECT_MODE_WHITEBOARD
     * @see #CONTROL_EFFECT_MODE_BLACKBOARD
     * @see #CONTROL_EFFECT_MODE_AQUA
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_EFFECT_MODE =
            new Key<Integer>("android.control.effectMode", int.class);

    /**
     * <p>Overall mode of 3A (auto-exposure, auto-white-balance, auto-focus) control
     * routines.</p>
     * <p>This is a top-level 3A control switch. When set to OFF, all 3A control
     * by the camera device is disabled. The application must set the fields for
     * capture parameters itself.</p>
     * <p>When set to AUTO, the individual algorithm controls in
     * android.control.* are in effect, such as {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}.</p>
     * <p>When set to USE_SCENE_MODE or USE_EXTENDED_SCENE_MODE, the individual controls in
     * android.control.* are mostly disabled, and the camera device
     * implements one of the scene mode or extended scene mode settings (such as ACTION,
     * SUNSET, PARTY, or BOKEH) as it wishes. The camera device scene mode
     * 3A settings are provided by {@link android.hardware.camera2.CaptureResult capture results}.</p>
     * <p>When set to OFF_KEEP_STATE, it is similar to OFF mode, the only difference
     * is that this frame will not be used by camera device background 3A statistics
     * update, as if this frame is never captured. This mode can be used in the scenario
     * where the application doesn't want a 3A manual control capture to affect
     * the subsequent auto 3A capture results.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_MODE_OFF OFF}</li>
     *   <li>{@link #CONTROL_MODE_AUTO AUTO}</li>
     *   <li>{@link #CONTROL_MODE_USE_SCENE_MODE USE_SCENE_MODE}</li>
     *   <li>{@link #CONTROL_MODE_OFF_KEEP_STATE OFF_KEEP_STATE}</li>
     *   <li>{@link #CONTROL_MODE_USE_EXTENDED_SCENE_MODE USE_EXTENDED_SCENE_MODE}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#CONTROL_AVAILABLE_MODES android.control.availableModes}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CameraCharacteristics#CONTROL_AVAILABLE_MODES
     * @see #CONTROL_MODE_OFF
     * @see #CONTROL_MODE_AUTO
     * @see #CONTROL_MODE_USE_SCENE_MODE
     * @see #CONTROL_MODE_OFF_KEEP_STATE
     * @see #CONTROL_MODE_USE_EXTENDED_SCENE_MODE
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_MODE =
            new Key<Integer>("android.control.mode", int.class);

    /**
     * <p>Control for which scene mode is currently active.</p>
     * <p>Scene modes are custom camera modes optimized for a certain set of conditions and
     * capture settings.</p>
     * <p>This is the mode that that is active when
     * <code>{@link CaptureRequest#CONTROL_MODE android.control.mode} == USE_SCENE_MODE</code>. Aside from FACE_PRIORITY, these modes will
     * disable {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode}, {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode}, and {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}
     * while in use.</p>
     * <p>The interpretation and implementation of these scene modes is left
     * to the implementor of the camera device. Their behavior will not be
     * consistent across all devices, and any given device may only implement
     * a subset of these modes.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_SCENE_MODE_DISABLED DISABLED}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_FACE_PRIORITY FACE_PRIORITY}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_ACTION ACTION}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_PORTRAIT PORTRAIT}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_LANDSCAPE LANDSCAPE}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_NIGHT NIGHT}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_NIGHT_PORTRAIT NIGHT_PORTRAIT}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_THEATRE THEATRE}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_BEACH BEACH}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_SNOW SNOW}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_SUNSET SUNSET}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_STEADYPHOTO STEADYPHOTO}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_FIREWORKS FIREWORKS}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_SPORTS SPORTS}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_PARTY PARTY}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_CANDLELIGHT CANDLELIGHT}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_BARCODE BARCODE}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO HIGH_SPEED_VIDEO}</li>
     *   <li>{@link #CONTROL_SCENE_MODE_HDR HDR}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#CONTROL_AVAILABLE_SCENE_MODES android.control.availableSceneModes}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CameraCharacteristics#CONTROL_AVAILABLE_SCENE_MODES
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see #CONTROL_SCENE_MODE_DISABLED
     * @see #CONTROL_SCENE_MODE_FACE_PRIORITY
     * @see #CONTROL_SCENE_MODE_ACTION
     * @see #CONTROL_SCENE_MODE_PORTRAIT
     * @see #CONTROL_SCENE_MODE_LANDSCAPE
     * @see #CONTROL_SCENE_MODE_NIGHT
     * @see #CONTROL_SCENE_MODE_NIGHT_PORTRAIT
     * @see #CONTROL_SCENE_MODE_THEATRE
     * @see #CONTROL_SCENE_MODE_BEACH
     * @see #CONTROL_SCENE_MODE_SNOW
     * @see #CONTROL_SCENE_MODE_SUNSET
     * @see #CONTROL_SCENE_MODE_STEADYPHOTO
     * @see #CONTROL_SCENE_MODE_FIREWORKS
     * @see #CONTROL_SCENE_MODE_SPORTS
     * @see #CONTROL_SCENE_MODE_PARTY
     * @see #CONTROL_SCENE_MODE_CANDLELIGHT
     * @see #CONTROL_SCENE_MODE_BARCODE
     * @see #CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO
     * @see #CONTROL_SCENE_MODE_HDR
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_SCENE_MODE =
            new Key<Integer>("android.control.sceneMode", int.class);

    /**
     * <p>Whether video stabilization is
     * active.</p>
     * <p>Video stabilization automatically warps images from
     * the camera in order to stabilize motion between consecutive frames.</p>
     * <p>If enabled, video stabilization can modify the
     * {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} to keep the video stream stabilized.</p>
     * <p>Switching between different video stabilization modes may take several
     * frames to initialize, the camera device will report the current mode
     * in capture result metadata. For example, When "ON" mode is requested,
     * the video stabilization modes in the first several capture results may
     * still be "OFF", and it will become "ON" when the initialization is
     * done.</p>
     * <p>In addition, not all recording sizes or frame rates may be supported for
     * stabilization by a device that reports stabilization support. It is guaranteed
     * that an output targeting a MediaRecorder or MediaCodec will be stabilized if
     * the recording resolution is less than or equal to 1920 x 1080 (width less than
     * or equal to 1920, height less than or equal to 1080), and the recording
     * frame rate is less than or equal to 30fps.  At other sizes, the CaptureResult
     * {@link CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE android.control.videoStabilizationMode} field will return
     * OFF if the recording output is not stabilized, or if there are no output
     * Surface types that can be stabilized.</p>
     * <p>The application is strongly recommended to call
     * {@link android.hardware.camera2.params.SessionConfiguration#setSessionParameters }
     * with the desired video stabilization mode before creating the capture session.
     * Video stabilization mode is a session parameter on many devices. Specifying
     * it at session creation time helps avoid reconfiguration delay caused by difference
     * between the default value and the first CaptureRequest.</p>
     * <p>If a camera device supports both this mode and OIS
     * ({@link CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE android.lens.opticalStabilizationMode}), turning both modes on may
     * produce undesirable interaction, so it is recommended not to enable
     * both at the same time.</p>
     * <p>If video stabilization is set to "PREVIEW_STABILIZATION",
     * {@link CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE android.lens.opticalStabilizationMode} is overridden. The camera sub-system may choose
     * to turn on hardware based image stabilization in addition to software based stabilization
     * if it deems that appropriate.
     * This key may be a part of the available session keys, which camera clients may
     * query via
     * {@link android.hardware.camera2.CameraCharacteristics#getAvailableSessionKeys }.
     * If this is the case, changing this key over the life-time of a capture session may
     * cause delays / glitches.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_VIDEO_STABILIZATION_MODE_OFF OFF}</li>
     *   <li>{@link #CONTROL_VIDEO_STABILIZATION_MODE_ON ON}</li>
     *   <li>{@link #CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION PREVIEW_STABILIZATION}</li>
     * </ul>
     *
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see #CONTROL_VIDEO_STABILIZATION_MODE_OFF
     * @see #CONTROL_VIDEO_STABILIZATION_MODE_ON
     * @see #CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_VIDEO_STABILIZATION_MODE =
            new Key<Integer>("android.control.videoStabilizationMode", int.class);

    /**
     * <p>The amount of additional sensitivity boost applied to output images
     * after RAW sensor data is captured.</p>
     * <p>Some camera devices support additional digital sensitivity boosting in the
     * camera processing pipeline after sensor RAW image is captured.
     * Such a boost will be applied to YUV/JPEG format output images but will not
     * have effect on RAW output formats like RAW_SENSOR, RAW10, RAW12 or RAW_OPAQUE.</p>
     * <p>This key will be <code>null</code> for devices that do not support any RAW format
     * outputs. For devices that do support RAW format outputs, this key will always
     * present, and if a device does not support post RAW sensitivity boost, it will
     * list <code>100</code> in this key.</p>
     * <p>If the camera device cannot apply the exact boost requested, it will reduce the
     * boost to the nearest supported value.
     * The final boost value used will be available in the output capture result.</p>
     * <p>For devices that support post RAW sensitivity boost, the YUV/JPEG output images
     * of such device will have the total sensitivity of
     * <code>{@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity} * {@link CaptureRequest#CONTROL_POST_RAW_SENSITIVITY_BOOST android.control.postRawSensitivityBoost} / 100</code>
     * The sensitivity of RAW format images will always be <code>{@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}</code></p>
     * <p>This control is only effective if {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} or {@link CaptureRequest#CONTROL_MODE android.control.mode} is set to
     * OFF; otherwise the auto-exposure algorithm will override this value.</p>
     * <p><b>Units</b>: ISO arithmetic units, the same as {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE android.control.postRawSensitivityBoostRange}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see CaptureRequest#CONTROL_POST_RAW_SENSITIVITY_BOOST
     * @see CameraCharacteristics#CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE
     * @see CaptureRequest#SENSOR_SENSITIVITY
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_POST_RAW_SENSITIVITY_BOOST =
            new Key<Integer>("android.control.postRawSensitivityBoost", int.class);

    /**
     * <p>Allow camera device to enable zero-shutter-lag mode for requests with
     * {@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent} == STILL_CAPTURE.</p>
     * <p>If enableZsl is <code>true</code>, the camera device may enable zero-shutter-lag mode for requests with
     * STILL_CAPTURE capture intent. The camera device may use images captured in the past to
     * produce output images for a zero-shutter-lag request. The result metadata including the
     * {@link CaptureResult#SENSOR_TIMESTAMP android.sensor.timestamp} reflects the source frames used to produce output images.
     * Therefore, the contents of the output images and the result metadata may be out of order
     * compared to previous regular requests. enableZsl does not affect requests with other
     * capture intents.</p>
     * <p>For example, when requests are submitted in the following order:
     *   Request A: enableZsl is ON, {@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent} is PREVIEW
     *   Request B: enableZsl is ON, {@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent} is STILL_CAPTURE</p>
     * <p>The output images for request B may have contents captured before the output images for
     * request A, and the result metadata for request B may be older than the result metadata for
     * request A.</p>
     * <p>Note that when enableZsl is <code>true</code>, it is not guaranteed to get output images captured in
     * the past for requests with STILL_CAPTURE capture intent.</p>
     * <p>For applications targeting SDK versions O and newer, the value of enableZsl in
     * TEMPLATE_STILL_CAPTURE template may be <code>true</code>. The value in other templates is always
     * <code>false</code> if present.</p>
     * <p>For applications targeting SDK versions older than O, the value of enableZsl in all
     * capture templates is always <code>false</code> if present.</p>
     * <p>For application-operated ZSL, use CAMERA3_TEMPLATE_ZERO_SHUTTER_LAG template.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     * @see CaptureResult#SENSOR_TIMESTAMP
     */
    @PublicKey
    @NonNull
    public static final Key<Boolean> CONTROL_ENABLE_ZSL =
            new Key<Boolean>("android.control.enableZsl", boolean.class);

    /**
     * <p>Whether extended scene mode is enabled for a particular capture request.</p>
     * <p>With bokeh mode, the camera device may blur out the parts of scene that are not in
     * focus, creating a bokeh (or shallow depth of field) effect for people or objects.</p>
     * <p>When set to BOKEH_STILL_CAPTURE mode with STILL_CAPTURE capture intent, due to the extra
     * processing needed for high quality bokeh effect, the stall may be longer than when
     * capture intent is not STILL_CAPTURE.</p>
     * <p>When set to BOKEH_STILL_CAPTURE mode with PREVIEW capture intent,</p>
     * <ul>
     * <li>If the camera device has BURST_CAPTURE capability, the frame rate requirement of
     * BURST_CAPTURE must still be met.</li>
     * <li>All streams not larger than the maximum streaming dimension for BOKEH_STILL_CAPTURE mode
     * (queried via {@link android.hardware.camera2.CameraCharacteristics#CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES })
     * will have preview bokeh effect applied.</li>
     * </ul>
     * <p>When set to BOKEH_CONTINUOUS mode, configured streams dimension should not exceed this mode's
     * maximum streaming dimension in order to have bokeh effect applied. Bokeh effect may not
     * be available for streams larger than the maximum streaming dimension.</p>
     * <p>Switching between different extended scene modes may involve reconfiguration of the camera
     * pipeline, resulting in long latency. The application should check this key against the
     * available session keys queried via
     * {@link android.hardware.camera2.CameraCharacteristics#getAvailableSessionKeys }.</p>
     * <p>For a logical multi-camera, bokeh may be implemented by stereo vision from sub-cameras
     * with different field of view. As a result, when bokeh mode is enabled, the camera device
     * may override {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} or {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}, and the field of
     * view may be smaller than when bokeh mode is off.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_EXTENDED_SCENE_MODE_DISABLED DISABLED}</li>
     *   <li>{@link #CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE BOKEH_STILL_CAPTURE}</li>
     *   <li>{@link #CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS BOKEH_CONTINUOUS}</li>
     * </ul>
     *
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see #CONTROL_EXTENDED_SCENE_MODE_DISABLED
     * @see #CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE
     * @see #CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_EXTENDED_SCENE_MODE =
            new Key<Integer>("android.control.extendedSceneMode", int.class);

    /**
     * <p>The desired zoom ratio</p>
     * <p>Instead of using {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} for zoom, the application can now choose to
     * use this tag to specify the desired zoom level.</p>
     * <p>By using this control, the application gains a simpler way to control zoom, which can
     * be a combination of optical and digital zoom. For example, a multi-camera system may
     * contain more than one lens with different focal lengths, and the user can use optical
     * zoom by switching between lenses. Using zoomRatio has benefits in the scenarios below:</p>
     * <ul>
     * <li>Zooming in from a wide-angle lens to a telephoto lens: A floating-point ratio provides
     *   better precision compared to an integer value of {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}.</li>
     * <li>Zooming out from a wide lens to an ultrawide lens: zoomRatio supports zoom-out whereas
     *   {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} doesn't.</li>
     * </ul>
     * <p>To illustrate, here are several scenarios of different zoom ratios, crop regions,
     * and output streams, for a hypothetical camera device with an active array of size
     * <code>(2000,1500)</code>.</p>
     * <ul>
     * <li>Camera Configuration:<ul>
     * <li>Active array size: <code>2000x1500</code> (3 MP, 4:3 aspect ratio)</li>
     * <li>Output stream #1: <code>640x480</code> (VGA, 4:3 aspect ratio)</li>
     * <li>Output stream #2: <code>1280x720</code> (720p, 16:9 aspect ratio)</li>
     * </ul>
     * </li>
     * <li>Case #1: 4:3 crop region with 2.0x zoom ratio<ul>
     * <li>Zoomed field of view: 1/4 of original field of view</li>
     * <li>Crop region: <code>Rect(0, 0, 2000, 1500) // (left, top, right, bottom)</code> (post zoom)</li>
     * </ul>
     * </li>
     * <li><img alt="4:3 aspect ratio crop diagram" src="/reference/images/camera2/metadata/android.control.zoomRatio/zoom-ratio-2-crop-43.png" /><ul>
     * <li><code>640x480</code> stream source area: <code>(0, 0, 2000, 1500)</code> (equal to crop region)</li>
     * <li><code>1280x720</code> stream source area: <code>(0, 187, 2000, 1312)</code> (letterboxed)</li>
     * </ul>
     * </li>
     * <li>Case #2: 16:9 crop region with 2.0x zoom.<ul>
     * <li>Zoomed field of view: 1/4 of original field of view</li>
     * <li>Crop region: <code>Rect(0, 187, 2000, 1312)</code></li>
     * <li><img alt="16:9 aspect ratio crop diagram" src="/reference/images/camera2/metadata/android.control.zoomRatio/zoom-ratio-2-crop-169.png" /></li>
     * <li><code>640x480</code> stream source area: <code>(250, 187, 1750, 1312)</code> (pillarboxed)</li>
     * <li><code>1280x720</code> stream source area: <code>(0, 187, 2000, 1312)</code> (equal to crop region)</li>
     * </ul>
     * </li>
     * <li>Case #3: 1:1 crop region with 0.5x zoom out to ultrawide lens.<ul>
     * <li>Zoomed field of view: 4x of original field of view (switched from wide lens to ultrawide lens)</li>
     * <li>Crop region: <code>Rect(250, 0, 1750, 1500)</code></li>
     * <li><img alt="1:1 aspect ratio crop diagram" src="/reference/images/camera2/metadata/android.control.zoomRatio/zoom-ratio-0.5-crop-11.png" /></li>
     * <li><code>640x480</code> stream source area: <code>(250, 187, 1750, 1312)</code> (letterboxed)</li>
     * <li><code>1280x720</code> stream source area: <code>(250, 328, 1750, 1172)</code> (letterboxed)</li>
     * </ul>
     * </li>
     * </ul>
     * <p>As seen from the graphs above, the coordinate system of cropRegion now changes to the
     * effective after-zoom field-of-view, and is represented by the rectangle of (0, 0,
     * activeArrayWith, activeArrayHeight). The same applies to AE/AWB/AF regions, and faces.
     * This coordinate system change isn't applicable to RAW capture and its related
     * metadata such as intrinsicCalibration and lensShadingMap.</p>
     * <p>Using the same hypothetical example above, and assuming output stream #1 (640x480) is
     * the viewfinder stream, the application can achieve 2.0x zoom in one of two ways:</p>
     * <ul>
     * <li>zoomRatio = 2.0, scaler.cropRegion = (0, 0, 2000, 1500)</li>
     * <li>zoomRatio = 1.0 (default), scaler.cropRegion = (500, 375, 1500, 1125)</li>
     * </ul>
     * <p>If the application intends to set aeRegions to be top-left quarter of the viewfinder
     * field-of-view, the {@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions} should be set to (0, 0, 1000, 750) with
     * zoomRatio set to 2.0. Alternatively, the application can set aeRegions to the equivalent
     * region of (500, 375, 1000, 750) for zoomRatio of 1.0. If the application doesn't
     * explicitly set {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}, its value defaults to 1.0.</p>
     * <p>One limitation of controlling zoom using zoomRatio is that the {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}
     * must only be used for letterboxing or pillarboxing of the sensor active array, and no
     * FREEFORM cropping can be used with {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} other than 1.0. If
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} is not 1.0, and {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} is set to be
     * windowboxing, the camera framework will override the {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} to be
     * the active array.</p>
     * <p>In the capture request, if the application sets {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} to a
     * value != 1.0, the {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} tag in the capture result reflects the
     * effective zoom ratio achieved by the camera device, and the {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}
     * adjusts for additional crops that are not zoom related. Otherwise, if the application
     * sets {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} to 1.0, or does not set it at all, the
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} tag in the result metadata will also be 1.0.</p>
     * <p>When the application requests a physical stream for a logical multi-camera, the
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} in the physical camera result metadata will be 1.0, and
     * the {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} tag reflects the amount of zoom and crop done by the
     * physical camera device.</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#CONTROL_ZOOM_RATIO_RANGE android.control.zoomRatioRange}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#CONTROL_AE_REGIONS
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CameraCharacteristics#CONTROL_ZOOM_RATIO_RANGE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#SCALER_CROP_REGION
     */
    @PublicKey
    @NonNull
    public static final Key<Float> CONTROL_ZOOM_RATIO =
            new Key<Float>("android.control.zoomRatio", float.class);

    /**
     * <p>Framework-only private key which informs camera fwk that the AF regions has been set
     * by the client and those regions need not be corrected when {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode} is
     * set to MAXIMUM_RESOLUTION.</p>
     * <p>This must be set to TRUE by the camera2 java fwk when the camera client sets
     * {@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions}.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_AF_REGIONS
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     * @hide
     */
    public static final Key<Boolean> CONTROL_AF_REGIONS_SET =
            new Key<Boolean>("android.control.afRegionsSet", boolean.class);

    /**
     * <p>Framework-only private key which informs camera fwk that the AE regions has been set
     * by the client and those regions need not be corrected when {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode} is
     * set to MAXIMUM_RESOLUTION.</p>
     * <p>This must be set to TRUE by the camera2 java fwk when the camera client sets
     * {@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_REGIONS
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     * @hide
     */
    public static final Key<Boolean> CONTROL_AE_REGIONS_SET =
            new Key<Boolean>("android.control.aeRegionsSet", boolean.class);

    /**
     * <p>Framework-only private key which informs camera fwk that the AF regions has been set
     * by the client and those regions need not be corrected when {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode} is
     * set to MAXIMUM_RESOLUTION.</p>
     * <p>This must be set to TRUE by the camera2 java fwk when the camera client sets
     * {@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions}.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     * @hide
     */
    public static final Key<Boolean> CONTROL_AWB_REGIONS_SET =
            new Key<Boolean>("android.control.awbRegionsSet", boolean.class);

    /**
     * <p>The desired CaptureRequest settings override with which certain keys are
     * applied earlier so that they can take effect sooner.</p>
     * <p>There are some CaptureRequest keys which can be applied earlier than others
     * when controls within a CaptureRequest aren't required to take effect at the same time.
     * One such example is zoom. Zoom can be applied at a later stage of the camera pipeline.
     * As soon as the camera device receives the CaptureRequest, it can apply the requested
     * zoom value onto an earlier request that's already in the pipeline, thus improves zoom
     * latency.</p>
     * <p>This key's value in the capture result reflects whether the controls for this capture
     * are overridden "by" a newer request. This means that if a capture request turns on
     * settings override, the capture result of an earlier request will contain the key value
     * of ZOOM. On the other hand, if a capture request has settings override turned on,
     * but all newer requests have it turned off, the key's value in the capture result will
     * be OFF because this capture isn't overridden by a newer capture. In the two examples
     * below, the capture results columns illustrate the settingsOverride values in different
     * scenarios.</p>
     * <p>Assuming the zoom settings override can speed up by 1 frame, below example illustrates
     * the speed-up at the start of capture session:</p>
     * <pre><code>Camera session created
     * Request 1 (zoom=1.0x, override=ZOOM) -&gt;
     * Request 2 (zoom=1.2x, override=ZOOM) -&gt;
     * Request 3 (zoom=1.4x, override=ZOOM) -&gt;  Result 1 (zoom=1.2x, override=ZOOM)
     * Request 4 (zoom=1.6x, override=ZOOM) -&gt;  Result 2 (zoom=1.4x, override=ZOOM)
     * Request 5 (zoom=1.8x, override=ZOOM) -&gt;  Result 3 (zoom=1.6x, override=ZOOM)
     *                                      -&gt;  Result 4 (zoom=1.8x, override=ZOOM)
     *                                      -&gt;  Result 5 (zoom=1.8x, override=OFF)
     * </code></pre>
     * <p>The application can turn on settings override and use zoom as normal. The example
     * shows that the later zoom values (1.2x, 1.4x, 1.6x, and 1.8x) overwrite the zoom
     * values (1.0x, 1.2x, 1.4x, and 1.8x) of earlier requests (#1, #2, #3, and #4).</p>
     * <p>The application must make sure the settings override doesn't interfere with user
     * journeys requiring simultaneous application of all controls in CaptureRequest on the
     * requested output targets. For example, if the application takes a still capture using
     * CameraCaptureSession#capture, and the repeating request immediately sets a different
     * zoom value using override, the inflight still capture could have its zoom value
     * overwritten unexpectedly.</p>
     * <p>So the application is strongly recommended to turn off settingsOverride when taking
     * still/burst captures, and turn it back on when there is only repeating viewfinder
     * request and no inflight still/burst captures.</p>
     * <p>Below is the example demonstrating the transitions in and out of the
     * settings override:</p>
     * <pre><code>Request 1 (zoom=1.0x, override=OFF)
     * Request 2 (zoom=1.2x, override=OFF)
     * Request 3 (zoom=1.4x, override=ZOOM)  -&gt; Result 1 (zoom=1.0x, override=OFF)
     * Request 4 (zoom=1.6x, override=ZOOM)  -&gt; Result 2 (zoom=1.4x, override=ZOOM)
     * Request 5 (zoom=1.8x, override=OFF)   -&gt; Result 3 (zoom=1.6x, override=ZOOM)
     *                                       -&gt; Result 4 (zoom=1.6x, override=OFF)
     *                                       -&gt; Result 5 (zoom=1.8x, override=OFF)
     * </code></pre>
     * <p>This example shows that:</p>
     * <ul>
     * <li>The application "ramps in" settings override by setting the control to ZOOM.
     * In the example, request #3 enables zoom settings override. Because the camera device
     * can speed up applying zoom by 1 frame, the outputs of request #2 has 1.4x zoom, the
     * value specified in request #3.</li>
     * <li>The application "ramps out" of settings override by setting the control to OFF. In
     * the example, request #5 changes the override to OFF. Because request #4's zoom
     * takes effect in result #3, result #4's zoom remains the same until new value takes
     * effect in result #5.</li>
     * </ul>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_SETTINGS_OVERRIDE_OFF OFF}</li>
     *   <li>{@link #CONTROL_SETTINGS_OVERRIDE_ZOOM ZOOM}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#CONTROL_AVAILABLE_SETTINGS_OVERRIDES android.control.availableSettingsOverrides}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_AVAILABLE_SETTINGS_OVERRIDES
     * @see #CONTROL_SETTINGS_OVERRIDE_OFF
     * @see #CONTROL_SETTINGS_OVERRIDE_ZOOM
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_SETTINGS_OVERRIDE =
            new Key<Integer>("android.control.settingsOverride", int.class);

    /**
     * <p>Automatic crop, pan and zoom to keep objects in the center of the frame.</p>
     * <p>Auto-framing is a special mode provided by the camera device to dynamically crop, zoom
     * or pan the camera feed to try to ensure that the people in a scene occupy a reasonable
     * portion of the viewport. It is primarily designed to support video calling in
     * situations where the user isn't directly in front of the device, especially for
     * wide-angle cameras.
     * {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} and {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} in CaptureResult will be used
     * to denote the coordinates of the auto-framed region.
     * Zoom and video stabilization controls are disabled when auto-framing is enabled. The 3A
     * regions must map the screen coordinates into the scaler crop returned from the capture
     * result instead of using the active array sensor.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #CONTROL_AUTOFRAMING_OFF OFF}</li>
     *   <li>{@link #CONTROL_AUTOFRAMING_ON ON}</li>
     * </ul>
     *
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see #CONTROL_AUTOFRAMING_OFF
     * @see #CONTROL_AUTOFRAMING_ON
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> CONTROL_AUTOFRAMING =
            new Key<Integer>("android.control.autoframing", int.class);

    /**
     * <p>Operation mode for edge
     * enhancement.</p>
     * <p>Edge enhancement improves sharpness and details in the captured image. OFF means
     * no enhancement will be applied by the camera device.</p>
     * <p>FAST/HIGH_QUALITY both mean camera device determined enhancement
     * will be applied. HIGH_QUALITY mode indicates that the
     * camera device will use the highest-quality enhancement algorithms,
     * even if it slows down capture rate. FAST means the camera device will
     * not slow down capture rate when applying edge enhancement. FAST may be the same as OFF if
     * edge enhancement will slow down capture rate. Every output stream will have a similar
     * amount of enhancement applied.</p>
     * <p>ZERO_SHUTTER_LAG is meant to be used by applications that maintain a continuous circular
     * buffer of high-resolution images during preview and reprocess image(s) from that buffer
     * into a final capture when triggered by the user. In this mode, the camera device applies
     * edge enhancement to low-resolution streams (below maximum recording resolution) to
     * maximize preview quality, but does not apply edge enhancement to high-resolution streams,
     * since those will be reprocessed later if necessary.</p>
     * <p>For YUV_REPROCESSING, these FAST/HIGH_QUALITY modes both mean that the camera
     * device will apply FAST/HIGH_QUALITY YUV-domain edge enhancement, respectively.
     * The camera device may adjust its internal edge enhancement parameters for best
     * image quality based on the {@link CaptureRequest#REPROCESS_EFFECTIVE_EXPOSURE_FACTOR android.reprocess.effectiveExposureFactor}, if it is set.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #EDGE_MODE_OFF OFF}</li>
     *   <li>{@link #EDGE_MODE_FAST FAST}</li>
     *   <li>{@link #EDGE_MODE_HIGH_QUALITY HIGH_QUALITY}</li>
     *   <li>{@link #EDGE_MODE_ZERO_SHUTTER_LAG ZERO_SHUTTER_LAG}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#EDGE_AVAILABLE_EDGE_MODES android.edge.availableEdgeModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#EDGE_AVAILABLE_EDGE_MODES
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#REPROCESS_EFFECTIVE_EXPOSURE_FACTOR
     * @see #EDGE_MODE_OFF
     * @see #EDGE_MODE_FAST
     * @see #EDGE_MODE_HIGH_QUALITY
     * @see #EDGE_MODE_ZERO_SHUTTER_LAG
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> EDGE_MODE =
            new Key<Integer>("android.edge.mode", int.class);

    /**
     * <p>The desired mode for for the camera device's flash control.</p>
     * <p>This control is only effective when flash unit is available
     * (<code>{@link CameraCharacteristics#FLASH_INFO_AVAILABLE android.flash.info.available} == true</code>).</p>
     * <p>When this control is used, the {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} must be set to ON or OFF.
     * Otherwise, the camera device auto-exposure related flash control (ON_AUTO_FLASH,
     * ON_ALWAYS_FLASH, or ON_AUTO_FLASH_REDEYE) will override this control.</p>
     * <p>When set to OFF, the camera device will not fire flash for this capture.</p>
     * <p>When set to SINGLE, the camera device will fire flash regardless of the camera
     * device's auto-exposure routine's result. When used in still capture case, this
     * control should be used along with auto-exposure (AE) precapture metering sequence
     * ({@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger}), otherwise, the image may be incorrectly exposed.</p>
     * <p>When set to TORCH, the flash will be on continuously. This mode can be used
     * for use cases such as preview, auto-focus assist, still capture, or video recording.</p>
     * <p>The flash status will be reported by {@link CaptureResult#FLASH_STATE android.flash.state} in the capture result metadata.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #FLASH_MODE_OFF OFF}</li>
     *   <li>{@link #FLASH_MODE_SINGLE SINGLE}</li>
     *   <li>{@link #FLASH_MODE_TORCH TORCH}</li>
     * </ul>
     *
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CameraCharacteristics#FLASH_INFO_AVAILABLE
     * @see CaptureResult#FLASH_STATE
     * @see #FLASH_MODE_OFF
     * @see #FLASH_MODE_SINGLE
     * @see #FLASH_MODE_TORCH
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> FLASH_MODE =
            new Key<Integer>("android.flash.mode", int.class);

    /**
     * <p>Flash strength level to be used when manual flash control is active.</p>
     * <p>Flash strength level to use in capture mode i.e. when the applications control
     * flash with either SINGLE or TORCH mode.</p>
     * <p>Use android.flash.info.singleStrengthMaxLevel and
     * android.flash.info.torchStrengthMaxLevel to check whether the device supports
     * flash strength control or not.
     * If the values of android.flash.info.singleStrengthMaxLevel and
     * android.flash.info.torchStrengthMaxLevel are greater than 1,
     * then the device supports manual flash strength control.</p>
     * <p>If the {@link CaptureRequest#FLASH_MODE android.flash.mode} <code>==</code> TORCH the value must be &gt;= 1
     * and &lt;= android.flash.info.torchStrengthMaxLevel.
     * If the application doesn't set the key and
     * android.flash.info.torchStrengthMaxLevel &gt; 1,
     * then the flash will be fired at the default level set by HAL in
     * android.flash.info.torchStrengthDefaultLevel.
     * If the {@link CaptureRequest#FLASH_MODE android.flash.mode} <code>==</code> SINGLE, then the value must be &gt;= 1
     * and &lt;= android.flash.info.singleStrengthMaxLevel.
     * If the application does not set this key and
     * android.flash.info.singleStrengthMaxLevel &gt; 1,
     * then the flash will be fired at the default level set by HAL
     * in android.flash.info.singleStrengthDefaultLevel.
     * If {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is set to any of ON_AUTO_FLASH, ON_ALWAYS_FLASH,
     * ON_AUTO_FLASH_REDEYE, ON_EXTERNAL_FLASH values, then the strengthLevel will be ignored.</p>
     * <p><b>Range of valid values:</b><br>
     * <code>[1-android.flash.info.torchStrengthMaxLevel]</code> when the {@link CaptureRequest#FLASH_MODE android.flash.mode} is
     * set to TORCH;
     * <code>[1-android.flash.info.singleStrengthMaxLevel]</code> when the {@link CaptureRequest#FLASH_MODE android.flash.mode} is
     * set to SINGLE</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#FLASH_MODE
     */
    @PublicKey
    @NonNull
    @FlaggedApi(Flags.FLAG_CAMERA_MANUAL_FLASH_STRENGTH_CONTROL)
    public static final Key<Integer> FLASH_STRENGTH_LEVEL =
            new Key<Integer>("android.flash.strengthLevel", int.class);

    /**
     * <p>Operational mode for hot pixel correction.</p>
     * <p>Hotpixel correction interpolates out, or otherwise removes, pixels
     * that do not accurately measure the incoming light (i.e. pixels that
     * are stuck at an arbitrary value or are oversensitive).</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #HOT_PIXEL_MODE_OFF OFF}</li>
     *   <li>{@link #HOT_PIXEL_MODE_FAST FAST}</li>
     *   <li>{@link #HOT_PIXEL_MODE_HIGH_QUALITY HIGH_QUALITY}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES android.hotPixel.availableHotPixelModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES
     * @see #HOT_PIXEL_MODE_OFF
     * @see #HOT_PIXEL_MODE_FAST
     * @see #HOT_PIXEL_MODE_HIGH_QUALITY
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> HOT_PIXEL_MODE =
            new Key<Integer>("android.hotPixel.mode", int.class);

    /**
     * <p>A location object to use when generating image GPS metadata.</p>
     * <p>Setting a location object in a request will include the GPS coordinates of the location
     * into any JPEG images captured based on the request. These coordinates can then be
     * viewed by anyone who receives the JPEG image.</p>
     * <p>This tag is also used for HEIC image capture.</p>
     * <p>This key is available on all devices.</p>
     */
    @PublicKey
    @NonNull
    @SyntheticKey
    public static final Key<android.location.Location> JPEG_GPS_LOCATION =
            new Key<android.location.Location>("android.jpeg.gpsLocation", android.location.Location.class);

    /**
     * <p>GPS coordinates to include in output JPEG
     * EXIF.</p>
     * <p>This tag is also used for HEIC image capture.</p>
     * <p><b>Range of valid values:</b><br>
     * (-180 - 180], [-90,90], [-inf, inf]</p>
     * <p>This key is available on all devices.</p>
     * @hide
     */
    public static final Key<double[]> JPEG_GPS_COORDINATES =
            new Key<double[]>("android.jpeg.gpsCoordinates", double[].class);

    /**
     * <p>32 characters describing GPS algorithm to
     * include in EXIF.</p>
     * <p>This tag is also used for HEIC image capture.</p>
     * <p>This key is available on all devices.</p>
     * @hide
     */
    public static final Key<String> JPEG_GPS_PROCESSING_METHOD =
            new Key<String>("android.jpeg.gpsProcessingMethod", String.class);

    /**
     * <p>Time GPS fix was made to include in
     * EXIF.</p>
     * <p>This tag is also used for HEIC image capture.</p>
     * <p><b>Units</b>: UTC in seconds since January 1, 1970</p>
     * <p>This key is available on all devices.</p>
     * @hide
     */
    public static final Key<Long> JPEG_GPS_TIMESTAMP =
            new Key<Long>("android.jpeg.gpsTimestamp", long.class);

    /**
     * <p>The orientation for a JPEG image.</p>
     * <p>The clockwise rotation angle in degrees, relative to the orientation
     * to the camera, that the JPEG picture needs to be rotated by, to be viewed
     * upright.</p>
     * <p>Camera devices may either encode this value into the JPEG EXIF header, or
     * rotate the image data to match this orientation. When the image data is rotated,
     * the thumbnail data will also be rotated.</p>
     * <p>Note that this orientation is relative to the orientation of the camera sensor, given
     * by {@link CameraCharacteristics#SENSOR_ORIENTATION android.sensor.orientation}.</p>
     * <p>To translate from the device orientation given by the Android sensor APIs for camera
     * sensors which are not EXTERNAL, the following sample code may be used:</p>
     * <pre><code>private int getJpegOrientation(CameraCharacteristics c, int deviceOrientation) {
     *     if (deviceOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return 0;
     *     int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
     *
     *     // Round device orientation to a multiple of 90
     *     deviceOrientation = (deviceOrientation + 45) / 90 * 90;
     *
     *     // Reverse device orientation for front-facing cameras
     *     boolean facingFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
     *     if (facingFront) deviceOrientation = -deviceOrientation;
     *
     *     // Calculate desired JPEG orientation relative to camera orientation to make
     *     // the image upright relative to the device orientation
     *     int jpegOrientation = (sensorOrientation + deviceOrientation + 360) % 360;
     *
     *     return jpegOrientation;
     * }
     * </code></pre>
     * <p>For EXTERNAL cameras the sensor orientation will always be set to 0 and the facing will
     * also be set to EXTERNAL. The above code is not relevant in such case.</p>
     * <p>This tag is also used to describe the orientation of the HEIC image capture, in which
     * case the rotation is reflected by
     * {@link android.media.ExifInterface#TAG_ORIENTATION EXIF orientation flag}, and not by
     * rotating the image data itself.</p>
     * <p><b>Units</b>: Degrees in multiples of 90</p>
     * <p><b>Range of valid values:</b><br>
     * 0, 90, 180, 270</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_ORIENTATION
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> JPEG_ORIENTATION =
            new Key<Integer>("android.jpeg.orientation", int.class);

    /**
     * <p>Compression quality of the final JPEG
     * image.</p>
     * <p>85-95 is typical usage range. This tag is also used to describe the quality
     * of the HEIC image capture.</p>
     * <p><b>Range of valid values:</b><br>
     * 1-100; larger is higher quality</p>
     * <p>This key is available on all devices.</p>
     */
    @PublicKey
    @NonNull
    public static final Key<Byte> JPEG_QUALITY =
            new Key<Byte>("android.jpeg.quality", byte.class);

    /**
     * <p>Compression quality of JPEG
     * thumbnail.</p>
     * <p>This tag is also used to describe the quality of the HEIC image capture.</p>
     * <p><b>Range of valid values:</b><br>
     * 1-100; larger is higher quality</p>
     * <p>This key is available on all devices.</p>
     */
    @PublicKey
    @NonNull
    public static final Key<Byte> JPEG_THUMBNAIL_QUALITY =
            new Key<Byte>("android.jpeg.thumbnailQuality", byte.class);

    /**
     * <p>Resolution of embedded JPEG thumbnail.</p>
     * <p>When set to (0, 0) value, the JPEG EXIF will not contain thumbnail,
     * but the captured JPEG will still be a valid image.</p>
     * <p>For best results, when issuing a request for a JPEG image, the thumbnail size selected
     * should have the same aspect ratio as the main JPEG output.</p>
     * <p>If the thumbnail image aspect ratio differs from the JPEG primary image aspect
     * ratio, the camera device creates the thumbnail by cropping it from the primary image.
     * For example, if the primary image has 4:3 aspect ratio, the thumbnail image has
     * 16:9 aspect ratio, the primary image will be cropped vertically (letterbox) to
     * generate the thumbnail image. The thumbnail image will always have a smaller Field
     * Of View (FOV) than the primary image when aspect ratios differ.</p>
     * <p>When an {@link CaptureRequest#JPEG_ORIENTATION android.jpeg.orientation} of non-zero degree is requested,
     * the camera device will handle thumbnail rotation in one of the following ways:</p>
     * <ul>
     * <li>Set the {@link android.media.ExifInterface#TAG_ORIENTATION EXIF orientation flag}
     *   and keep jpeg and thumbnail image data unrotated.</li>
     * <li>Rotate the jpeg and thumbnail image data and not set
     *   {@link android.media.ExifInterface#TAG_ORIENTATION EXIF orientation flag}. In this
     *   case, LIMITED or FULL hardware level devices will report rotated thumbnail size in
     *   capture result, so the width and height will be interchanged if 90 or 270 degree
     *   orientation is requested. LEGACY device will always report unrotated thumbnail
     *   size.</li>
     * </ul>
     * <p>The tag is also used as thumbnail size for HEIC image format capture, in which case the
     * the thumbnail rotation is reflected by
     * {@link android.media.ExifInterface#TAG_ORIENTATION EXIF orientation flag}, and not by
     * rotating the thumbnail data itself.</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#JPEG_AVAILABLE_THUMBNAIL_SIZES android.jpeg.availableThumbnailSizes}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#JPEG_AVAILABLE_THUMBNAIL_SIZES
     * @see CaptureRequest#JPEG_ORIENTATION
     */
    @PublicKey
    @NonNull
    public static final Key<android.util.Size> JPEG_THUMBNAIL_SIZE =
            new Key<android.util.Size>("android.jpeg.thumbnailSize", android.util.Size.class);

    /**
     * <p>The desired lens aperture size, as a ratio of lens focal length to the
     * effective aperture diameter.</p>
     * <p>Setting this value is only supported on the camera devices that have a variable
     * aperture lens.</p>
     * <p>When this is supported and {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is OFF,
     * this can be set along with {@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime},
     * {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}, and {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration}
     * to achieve manual exposure control.</p>
     * <p>The requested aperture value may take several frames to reach the
     * requested value; the camera device will report the current (intermediate)
     * aperture size in capture result metadata while the aperture is changing.
     * While the aperture is still changing, {@link CaptureResult#LENS_STATE android.lens.state} will be set to MOVING.</p>
     * <p>When this is supported and {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is one of
     * the ON modes, this will be overridden by the camera device
     * auto-exposure algorithm, the overridden values are then provided
     * back to the user in the corresponding result.</p>
     * <p><b>Units</b>: The f-number (f/N)</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#LENS_INFO_AVAILABLE_APERTURES android.lens.info.availableApertures}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_APERTURES
     * @see CaptureResult#LENS_STATE
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see CaptureRequest#SENSOR_SENSITIVITY
     */
    @PublicKey
    @NonNull
    public static final Key<Float> LENS_APERTURE =
            new Key<Float>("android.lens.aperture", float.class);

    /**
     * <p>The desired setting for the lens neutral density filter(s).</p>
     * <p>This control will not be supported on most camera devices.</p>
     * <p>Lens filters are typically used to lower the amount of light the
     * sensor is exposed to (measured in steps of EV). As used here, an EV
     * step is the standard logarithmic representation, which are
     * non-negative, and inversely proportional to the amount of light
     * hitting the sensor.  For example, setting this to 0 would result
     * in no reduction of the incoming light, and setting this to 2 would
     * mean that the filter is set to reduce incoming light by two stops
     * (allowing 1/4 of the prior amount of light to the sensor).</p>
     * <p>It may take several frames before the lens filter density changes
     * to the requested value. While the filter density is still changing,
     * {@link CaptureResult#LENS_STATE android.lens.state} will be set to MOVING.</p>
     * <p><b>Units</b>: Exposure Value (EV)</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#LENS_INFO_AVAILABLE_FILTER_DENSITIES android.lens.info.availableFilterDensities}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_FILTER_DENSITIES
     * @see CaptureResult#LENS_STATE
     */
    @PublicKey
    @NonNull
    public static final Key<Float> LENS_FILTER_DENSITY =
            new Key<Float>("android.lens.filterDensity", float.class);

    /**
     * <p>The desired lens focal length; used for optical zoom.</p>
     * <p>This setting controls the physical focal length of the camera
     * device's lens. Changing the focal length changes the field of
     * view of the camera device, and is usually used for optical zoom.</p>
     * <p>Like {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance} and {@link CaptureRequest#LENS_APERTURE android.lens.aperture}, this
     * setting won't be applied instantaneously, and it may take several
     * frames before the lens can change to the requested focal length.
     * While the focal length is still changing, {@link CaptureResult#LENS_STATE android.lens.state} will
     * be set to MOVING.</p>
     * <p>Optical zoom via this control will not be supported on most devices. Starting from API
     * level 30, the camera device may combine optical and digital zoom through the
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} control.</p>
     * <p><b>Units</b>: Millimeters</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#LENS_INFO_AVAILABLE_FOCAL_LENGTHS android.lens.info.availableFocalLengths}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CaptureRequest#LENS_APERTURE
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_FOCAL_LENGTHS
     * @see CaptureResult#LENS_STATE
     */
    @PublicKey
    @NonNull
    public static final Key<Float> LENS_FOCAL_LENGTH =
            new Key<Float>("android.lens.focalLength", float.class);

    /**
     * <p>Desired distance to plane of sharpest focus,
     * measured from frontmost surface of the lens.</p>
     * <p>This control can be used for setting manual focus, on devices that support
     * the MANUAL_SENSOR capability and have a variable-focus lens (see
     * {@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance}).</p>
     * <p>A value of <code>0.0f</code> means infinity focus. The value set will be clamped to
     * <code>[0.0f, {@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance}]</code>.</p>
     * <p>Like {@link CaptureRequest#LENS_FOCAL_LENGTH android.lens.focalLength}, this setting won't be applied
     * instantaneously, and it may take several frames before the lens
     * can move to the requested focus distance. While the lens is still moving,
     * {@link CaptureResult#LENS_STATE android.lens.state} will be set to MOVING.</p>
     * <p>LEGACY devices support at most setting this to <code>0.0f</code>
     * for infinity focus.</p>
     * <p><b>Units</b>: See {@link CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION android.lens.info.focusDistanceCalibration} for details</p>
     * <p><b>Range of valid values:</b><br>
     * &gt;= 0</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#LENS_FOCAL_LENGTH
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     * @see CaptureResult#LENS_STATE
     */
    @PublicKey
    @NonNull
    public static final Key<Float> LENS_FOCUS_DISTANCE =
            new Key<Float>("android.lens.focusDistance", float.class);

    /**
     * <p>Sets whether the camera device uses optical image stabilization (OIS)
     * when capturing images.</p>
     * <p>OIS is used to compensate for motion blur due to small
     * movements of the camera during capture. Unlike digital image
     * stabilization ({@link CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE android.control.videoStabilizationMode}), OIS
     * makes use of mechanical elements to stabilize the camera
     * sensor, and thus allows for longer exposure times before
     * camera shake becomes apparent.</p>
     * <p>Switching between different optical stabilization modes may take several
     * frames to initialize, the camera device will report the current mode in
     * capture result metadata. For example, When "ON" mode is requested, the
     * optical stabilization modes in the first several capture results may still
     * be "OFF", and it will become "ON" when the initialization is done.</p>
     * <p>If a camera device supports both OIS and digital image stabilization
     * ({@link CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE android.control.videoStabilizationMode}), turning both modes on may produce undesirable
     * interaction, so it is recommended not to enable both at the same time.</p>
     * <p>If {@link CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE android.control.videoStabilizationMode} is set to "PREVIEW_STABILIZATION",
     * {@link CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE android.lens.opticalStabilizationMode} is overridden. The camera sub-system may choose
     * to turn on hardware based image stabilization in addition to software based stabilization
     * if it deems that appropriate. This key's value in the capture result will reflect which
     * OIS mode was chosen.</p>
     * <p>Not all devices will support OIS; see
     * {@link CameraCharacteristics#LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION android.lens.info.availableOpticalStabilization} for
     * available controls.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #LENS_OPTICAL_STABILIZATION_MODE_OFF OFF}</li>
     *   <li>{@link #LENS_OPTICAL_STABILIZATION_MODE_ON ON}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION android.lens.info.availableOpticalStabilization}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     * @see #LENS_OPTICAL_STABILIZATION_MODE_OFF
     * @see #LENS_OPTICAL_STABILIZATION_MODE_ON
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> LENS_OPTICAL_STABILIZATION_MODE =
            new Key<Integer>("android.lens.opticalStabilizationMode", int.class);

    /**
     * <p>Mode of operation for the noise reduction algorithm.</p>
     * <p>The noise reduction algorithm attempts to improve image quality by removing
     * excessive noise added by the capture process, especially in dark conditions.</p>
     * <p>OFF means no noise reduction will be applied by the camera device, for both raw and
     * YUV domain.</p>
     * <p>MINIMAL means that only sensor raw domain basic noise reduction is enabled ,to remove
     * demosaicing or other processing artifacts. For YUV_REPROCESSING, MINIMAL is same as OFF.
     * This mode is optional, may not be support by all devices. The application should check
     * {@link CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES android.noiseReduction.availableNoiseReductionModes} before using it.</p>
     * <p>FAST/HIGH_QUALITY both mean camera device determined noise filtering
     * will be applied. HIGH_QUALITY mode indicates that the camera device
     * will use the highest-quality noise filtering algorithms,
     * even if it slows down capture rate. FAST means the camera device will not
     * slow down capture rate when applying noise filtering. FAST may be the same as MINIMAL if
     * MINIMAL is listed, or the same as OFF if any noise filtering will slow down capture rate.
     * Every output stream will have a similar amount of enhancement applied.</p>
     * <p>ZERO_SHUTTER_LAG is meant to be used by applications that maintain a continuous circular
     * buffer of high-resolution images during preview and reprocess image(s) from that buffer
     * into a final capture when triggered by the user. In this mode, the camera device applies
     * noise reduction to low-resolution streams (below maximum recording resolution) to maximize
     * preview quality, but does not apply noise reduction to high-resolution streams, since
     * those will be reprocessed later if necessary.</p>
     * <p>For YUV_REPROCESSING, these FAST/HIGH_QUALITY modes both mean that the camera device
     * will apply FAST/HIGH_QUALITY YUV domain noise reduction, respectively. The camera device
     * may adjust the noise reduction parameters for best image quality based on the
     * {@link CaptureRequest#REPROCESS_EFFECTIVE_EXPOSURE_FACTOR android.reprocess.effectiveExposureFactor} if it is set.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #NOISE_REDUCTION_MODE_OFF OFF}</li>
     *   <li>{@link #NOISE_REDUCTION_MODE_FAST FAST}</li>
     *   <li>{@link #NOISE_REDUCTION_MODE_HIGH_QUALITY HIGH_QUALITY}</li>
     *   <li>{@link #NOISE_REDUCTION_MODE_MINIMAL MINIMAL}</li>
     *   <li>{@link #NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG ZERO_SHUTTER_LAG}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES android.noiseReduction.availableNoiseReductionModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
     * @see CaptureRequest#REPROCESS_EFFECTIVE_EXPOSURE_FACTOR
     * @see #NOISE_REDUCTION_MODE_OFF
     * @see #NOISE_REDUCTION_MODE_FAST
     * @see #NOISE_REDUCTION_MODE_HIGH_QUALITY
     * @see #NOISE_REDUCTION_MODE_MINIMAL
     * @see #NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> NOISE_REDUCTION_MODE =
            new Key<Integer>("android.noiseReduction.mode", int.class);

    /**
     * <p>An application-specified ID for the current
     * request. Must be maintained unchanged in output
     * frame</p>
     * <p><b>Units</b>: arbitrary integer assigned by application</p>
     * <p><b>Range of valid values:</b><br>
     * Any int</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * @hide
     */
    public static final Key<Integer> REQUEST_ID =
            new Key<Integer>("android.request.id", int.class);

    /**
     * <p>The desired region of the sensor to read out for this capture.</p>
     * <p>This control can be used to implement digital zoom.</p>
     * <p>For devices not supporting {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the coordinate
     * system always follows that of {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, with <code>(0, 0)</code> being
     * the top-left pixel of the active array.</p>
     * <p>For devices supporting {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the coordinate system
     * depends on the mode being set.  When the distortion correction mode is OFF, the
     * coordinate system follows {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}, with <code>(0,
     * 0)</code> being the top-left pixel of the pre-correction active array.  When the distortion
     * correction mode is not OFF, the coordinate system follows
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, with <code>(0, 0)</code> being the top-left pixel of the
     * active array.</p>
     * <p>Output streams use this rectangle to produce their output, cropping to a smaller region
     * if necessary to maintain the stream's aspect ratio, then scaling the sensor input to
     * match the output's configured resolution.</p>
     * <p>The crop region is usually applied after the RAW to other color space (e.g. YUV)
     * conversion. As a result RAW streams are not croppable unless supported by the
     * camera device. See {@link CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES android.scaler.availableStreamUseCases}#CROPPED_RAW for details.</p>
     * <p>For non-raw streams, any additional per-stream cropping will be done to maximize the
     * final pixel area of the stream.</p>
     * <p>For example, if the crop region is set to a 4:3 aspect ratio, then 4:3 streams will use
     * the exact crop region. 16:9 streams will further crop vertically (letterbox).</p>
     * <p>Conversely, if the crop region is set to a 16:9, then 4:3 outputs will crop horizontally
     * (pillarbox), and 16:9 streams will match exactly. These additional crops will be
     * centered within the crop region.</p>
     * <p>To illustrate, here are several scenarios of different crop regions and output streams,
     * for a hypothetical camera device with an active array of size <code>(2000,1500)</code>.  Note that
     * several of these examples use non-centered crop regions for ease of illustration; such
     * regions are only supported on devices with FREEFORM capability
     * ({@link CameraCharacteristics#SCALER_CROPPING_TYPE android.scaler.croppingType} <code>== FREEFORM</code>), but this does not affect the way the crop
     * rules work otherwise.</p>
     * <ul>
     * <li>Camera Configuration:<ul>
     * <li>Active array size: <code>2000x1500</code> (3 MP, 4:3 aspect ratio)</li>
     * <li>Output stream #1: <code>640x480</code> (VGA, 4:3 aspect ratio)</li>
     * <li>Output stream #2: <code>1280x720</code> (720p, 16:9 aspect ratio)</li>
     * </ul>
     * </li>
     * <li>Case #1: 4:3 crop region with 2x digital zoom<ul>
     * <li>Crop region: <code>Rect(500, 375, 1500, 1125) // (left, top, right, bottom)</code></li>
     * <li><img alt="4:3 aspect ratio crop diagram" src="/reference/images/camera2/metadata/android.scaler.cropRegion/crop-region-43-ratio.png" /></li>
     * <li><code>640x480</code> stream source area: <code>(500, 375, 1500, 1125)</code> (equal to crop region)</li>
     * <li><code>1280x720</code> stream source area: <code>(500, 469, 1500, 1031)</code> (letterboxed)</li>
     * </ul>
     * </li>
     * <li>Case #2: 16:9 crop region with ~1.5x digital zoom.<ul>
     * <li>Crop region: <code>Rect(500, 375, 1833, 1125)</code></li>
     * <li><img alt="16:9 aspect ratio crop diagram" src="/reference/images/camera2/metadata/android.scaler.cropRegion/crop-region-169-ratio.png" /></li>
     * <li><code>640x480</code> stream source area: <code>(666, 375, 1666, 1125)</code> (pillarboxed)</li>
     * <li><code>1280x720</code> stream source area: <code>(500, 375, 1833, 1125)</code> (equal to crop region)</li>
     * </ul>
     * </li>
     * <li>Case #3: 1:1 crop region with ~2.6x digital zoom.<ul>
     * <li>Crop region: <code>Rect(500, 375, 1250, 1125)</code></li>
     * <li><img alt="1:1 aspect ratio crop diagram" src="/reference/images/camera2/metadata/android.scaler.cropRegion/crop-region-11-ratio.png" /></li>
     * <li><code>640x480</code> stream source area: <code>(500, 469, 1250, 1031)</code> (letterboxed)</li>
     * <li><code>1280x720</code> stream source area: <code>(500, 543, 1250, 957)</code> (letterboxed)</li>
     * </ul>
     * </li>
     * <li>Case #4: Replace <code>640x480</code> stream with <code>1024x1024</code> stream, with 4:3 crop region:<ul>
     * <li>Crop region: <code>Rect(500, 375, 1500, 1125)</code></li>
     * <li><img alt="Square output, 4:3 aspect ratio crop diagram" src="/reference/images/camera2/metadata/android.scaler.cropRegion/crop-region-43-square-ratio.png" /></li>
     * <li><code>1024x1024</code> stream source area: <code>(625, 375, 1375, 1125)</code> (pillarboxed)</li>
     * <li><code>1280x720</code> stream source area: <code>(500, 469, 1500, 1031)</code> (letterboxed)</li>
     * <li>Note that in this case, neither of the two outputs is a subset of the other, with
     *   each containing image data the other doesn't have.</li>
     * </ul>
     * </li>
     * </ul>
     * <p>If the coordinate system is {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, the width and height
     * of the crop region cannot be set to be smaller than
     * <code>floor( activeArraySize.width / {@link CameraCharacteristics#SCALER_AVAILABLE_MAX_DIGITAL_ZOOM android.scaler.availableMaxDigitalZoom} )</code> and
     * <code>floor( activeArraySize.height / {@link CameraCharacteristics#SCALER_AVAILABLE_MAX_DIGITAL_ZOOM android.scaler.availableMaxDigitalZoom} )</code>, respectively.</p>
     * <p>If the coordinate system is {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}, the width
     * and height of the crop region cannot be set to be smaller than
     * <code>floor( preCorrectionActiveArraySize.width / {@link CameraCharacteristics#SCALER_AVAILABLE_MAX_DIGITAL_ZOOM android.scaler.availableMaxDigitalZoom} )</code>
     * and
     * <code>floor( preCorrectionActiveArraySize.height / {@link CameraCharacteristics#SCALER_AVAILABLE_MAX_DIGITAL_ZOOM android.scaler.availableMaxDigitalZoom} )</code>,
     * respectively.</p>
     * <p>The camera device may adjust the crop region to account for rounding and other hardware
     * requirements; the final crop region used will be included in the output capture result.</p>
     * <p>The camera sensor output aspect ratio depends on factors such as output stream
     * combination and {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE android.control.aeTargetFpsRange}, and shouldn't be adjusted by using
     * this control. And the camera device will treat different camera sensor output sizes
     * (potentially with in-sensor crop) as the same crop of
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}. As a result, the application shouldn't assume the
     * maximum crop region always maps to the same aspect ratio or field of view for the
     * sensor output.</p>
     * <p>Starting from API level 30, it's strongly recommended to use {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}
     * to take advantage of better support for zoom with logical multi-camera. The benefits
     * include better precision with optical-digital zoom combination, and ability to do
     * zoom-out from 1.0x. When using {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} for zoom, the crop region in
     * the capture request should be left as the default activeArray size. The
     * coordinate system is post-zoom, meaning that the activeArraySize or
     * preCorrectionActiveArraySize covers the camera device's field of view "after" zoom.  See
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} for details.</p>
     * <p>For camera devices with the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR }
     * capability or devices where {@link CameraCharacteristics#getAvailableCaptureRequestKeys }
     * lists {@link CaptureRequest#SENSOR_PIXEL_MODE {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode}}</p>
     * <p>{@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.activeArraySizeMaximumResolution} /
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.preCorrectionActiveArraySizeMaximumResolution} must be used as the
     * coordinate system for requests where {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode} is set to
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION }.</p>
     * <p><b>Units</b>: Pixel coordinates relative to
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} or
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize} depending on distortion correction
     * capability and mode</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CaptureRequest#DISTORTION_CORRECTION_MODE
     * @see CameraCharacteristics#SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
     * @see CameraCharacteristics#SCALER_CROPPING_TYPE
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     */
    @PublicKey
    @NonNull
    public static final Key<android.graphics.Rect> SCALER_CROP_REGION =
            new Key<android.graphics.Rect>("android.scaler.cropRegion", android.graphics.Rect.class);

    /**
     * <p>Whether a rotation-and-crop operation is applied to processed
     * outputs from the camera.</p>
     * <p>This control is primarily intended to help camera applications with no support for
     * multi-window modes to work correctly on devices where multi-window scenarios are
     * unavoidable, such as foldables or other devices with variable display geometry or more
     * free-form window placement (such as laptops, which often place portrait-orientation apps
     * in landscape with pillarboxing).</p>
     * <p>If supported, the default value is <code>ROTATE_AND_CROP_AUTO</code>, which allows the camera API
     * to enable backwards-compatibility support for applications that do not support resizing
     * / multi-window modes, when the device is in fact in a multi-window mode (such as inset
     * portrait on laptops, or on a foldable device in some fold states).  In addition,
     * <code>ROTATE_AND_CROP_NONE</code> and <code>ROTATE_AND_CROP_90</code> will always be available if this control
     * is supported by the device.  If not supported, devices API level 30 or higher will always
     * list only <code>ROTATE_AND_CROP_NONE</code>.</p>
     * <p>When <code>CROP_AUTO</code> is in use, and the camera API activates backward-compatibility mode,
     * several metadata fields will also be parsed differently to ensure that coordinates are
     * correctly handled for features like drawing face detection boxes or passing in
     * tap-to-focus coordinates.  The camera API will convert positions in the active array
     * coordinate system to/from the cropped-and-rotated coordinate system to make the
     * operation transparent for applications.  The following controls are affected:</p>
     * <ul>
     * <li>{@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions}</li>
     * <li>{@link CaptureResult#STATISTICS_FACES android.statistics.faces}</li>
     * </ul>
     * <p>Capture results will contain the actual value selected by the API;
     * <code>ROTATE_AND_CROP_AUTO</code> will never be seen in a capture result.</p>
     * <p>Applications can also select their preferred cropping mode, either to opt out of the
     * backwards-compatibility treatment, or to use the cropping feature themselves as needed.
     * In this case, no coordinate translation will be done automatically, and all controls
     * will continue to use the normal active array coordinates.</p>
     * <p>Cropping and rotating is done after the application of digital zoom (via either
     * {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} or {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}), but before each individual
     * output is further cropped and scaled. It only affects processed outputs such as
     * YUV, PRIVATE, and JPEG.  It has no effect on RAW outputs.</p>
     * <p>When <code>CROP_90</code> or <code>CROP_270</code> are selected, there is a significant loss to the field of
     * view. For example, with a 4:3 aspect ratio output of 1600x1200, <code>CROP_90</code> will still
     * produce 1600x1200 output, but these buffers are cropped from a vertical 3:4 slice at the
     * center of the 4:3 area, then rotated to be 4:3, and then upscaled to 1600x1200.  Only
     * 56.25% of the original FOV is still visible.  In general, for an aspect ratio of <code>w:h</code>,
     * the crop and rotate operation leaves <code>(h/w)^2</code> of the field of view visible. For 16:9,
     * this is ~31.6%.</p>
     * <p>As a visual example, the figure below shows the effect of <code>ROTATE_AND_CROP_90</code> on the
     * outputs for the following parameters:</p>
     * <ul>
     * <li>Sensor active array: <code>2000x1500</code></li>
     * <li>Crop region: top-left: <code>(500, 375)</code>, size: <code>(1000, 750)</code> (4:3 aspect ratio)</li>
     * <li>Output streams: YUV <code>640x480</code> and YUV <code>1280x720</code></li>
     * <li><code>ROTATE_AND_CROP_90</code></li>
     * </ul>
     * <p><img alt="Effect of ROTATE_AND_CROP_90" src="/reference/images/camera2/metadata/android.scaler.rotateAndCrop/crop-region-rotate-90-43-ratio.png" /></p>
     * <p>With these settings, the regions of the active array covered by the output streams are:</p>
     * <ul>
     * <li>640x480 stream crop: top-left: <code>(219, 375)</code>, size: <code>(562, 750)</code></li>
     * <li>1280x720 stream crop: top-left: <code>(289, 375)</code>, size: <code>(422, 750)</code></li>
     * </ul>
     * <p>Since the buffers are rotated, the buffers as seen by the application are:</p>
     * <ul>
     * <li>640x480 stream: top-left: <code>(781, 375)</code> on active array, size: <code>(640, 480)</code>, downscaled 1.17x from sensor pixels</li>
     * <li>1280x720 stream: top-left: <code>(711, 375)</code> on active array, size: <code>(1280, 720)</code>, upscaled 1.71x from sensor pixels</li>
     * </ul>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #SCALER_ROTATE_AND_CROP_NONE NONE}</li>
     *   <li>{@link #SCALER_ROTATE_AND_CROP_90 90}</li>
     *   <li>{@link #SCALER_ROTATE_AND_CROP_180 180}</li>
     *   <li>{@link #SCALER_ROTATE_AND_CROP_270 270}</li>
     *   <li>{@link #SCALER_ROTATE_AND_CROP_AUTO AUTO}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#SCALER_AVAILABLE_ROTATE_AND_CROP_MODES android.scaler.availableRotateAndCropModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_REGIONS
     * @see CaptureRequest#CONTROL_AF_REGIONS
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CameraCharacteristics#SCALER_AVAILABLE_ROTATE_AND_CROP_MODES
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CaptureResult#STATISTICS_FACES
     * @see #SCALER_ROTATE_AND_CROP_NONE
     * @see #SCALER_ROTATE_AND_CROP_90
     * @see #SCALER_ROTATE_AND_CROP_180
     * @see #SCALER_ROTATE_AND_CROP_270
     * @see #SCALER_ROTATE_AND_CROP_AUTO
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> SCALER_ROTATE_AND_CROP =
            new Key<Integer>("android.scaler.rotateAndCrop", int.class);

    /**
     * <p>Framework-only private key which informs camera fwk that the scaler crop region
     * ({@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}) has been set by the client and it need
     * not be corrected when {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode} is set to MAXIMUM_RESOLUTION.</p>
     * <p>This must be set to TRUE by the camera2 java fwk when the camera client sets
     * {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     * @hide
     */
    public static final Key<Boolean> SCALER_CROP_REGION_SET =
            new Key<Boolean>("android.scaler.cropRegionSet", boolean.class);

    /**
     * <p>Duration each pixel is exposed to
     * light.</p>
     * <p>If the sensor can't expose this exact duration, it will shorten the
     * duration exposed to the nearest possible value (rather than expose longer).
     * The final exposure time used will be available in the output capture result.</p>
     * <p>This control is only effective if {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} or {@link CaptureRequest#CONTROL_MODE android.control.mode} is set to
     * OFF; otherwise the auto-exposure algorithm will override this value.</p>
     * <p><b>Units</b>: Nanoseconds</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE android.sensor.info.exposureTimeRange}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE
     */
    @PublicKey
    @NonNull
    public static final Key<Long> SENSOR_EXPOSURE_TIME =
            new Key<Long>("android.sensor.exposureTime", long.class);

    /**
     * <p>Duration from start of frame readout to
     * start of next frame readout.</p>
     * <p>The maximum frame rate that can be supported by a camera subsystem is
     * a function of many factors:</p>
     * <ul>
     * <li>Requested resolutions of output image streams</li>
     * <li>Availability of binning / skipping modes on the imager</li>
     * <li>The bandwidth of the imager interface</li>
     * <li>The bandwidth of the various ISP processing blocks</li>
     * </ul>
     * <p>Since these factors can vary greatly between different ISPs and
     * sensors, the camera abstraction tries to represent the bandwidth
     * restrictions with as simple a model as possible.</p>
     * <p>The model presented has the following characteristics:</p>
     * <ul>
     * <li>The image sensor is always configured to output the smallest
     * resolution possible given the application's requested output stream
     * sizes.  The smallest resolution is defined as being at least as large
     * as the largest requested output stream size; the camera pipeline must
     * never digitally upsample sensor data when the crop region covers the
     * whole sensor. In general, this means that if only small output stream
     * resolutions are configured, the sensor can provide a higher frame
     * rate.</li>
     * <li>Since any request may use any or all the currently configured
     * output streams, the sensor and ISP must be configured to support
     * scaling a single capture to all the streams at the same time.  This
     * means the camera pipeline must be ready to produce the largest
     * requested output size without any delay.  Therefore, the overall
     * frame rate of a given configured stream set is governed only by the
     * largest requested stream resolution.</li>
     * <li>Using more than one output stream in a request does not affect the
     * frame duration.</li>
     * <li>Certain format-streams may need to do additional background processing
     * before data is consumed/produced by that stream. These processors
     * can run concurrently to the rest of the camera pipeline, but
     * cannot process more than 1 capture at a time.</li>
     * </ul>
     * <p>The necessary information for the application, given the model above, is provided via
     * {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputMinFrameDuration }.
     * These are used to determine the maximum frame rate / minimum frame duration that is
     * possible for a given stream configuration.</p>
     * <p>Specifically, the application can use the following rules to
     * determine the minimum frame duration it can request from the camera
     * device:</p>
     * <ol>
     * <li>Let the set of currently configured input/output streams be called <code>S</code>.</li>
     * <li>Find the minimum frame durations for each stream in <code>S</code>, by looking it up in {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputMinFrameDuration }
     * (with its respective size/format). Let this set of frame durations be called <code>F</code>.</li>
     * <li>For any given request <code>R</code>, the minimum frame duration allowed for <code>R</code> is the maximum
     * out of all values in <code>F</code>. Let the streams used in <code>R</code> be called <code>S_r</code>.</li>
     * </ol>
     * <p>If none of the streams in <code>S_r</code> have a stall time (listed in {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputStallDuration }
     * using its respective size/format), then the frame duration in <code>F</code> determines the steady
     * state frame rate that the application will get if it uses <code>R</code> as a repeating request. Let
     * this special kind of request be called <code>Rsimple</code>.</p>
     * <p>A repeating request <code>Rsimple</code> can be <em>occasionally</em> interleaved by a single capture of a
     * new request <code>Rstall</code> (which has at least one in-use stream with a non-0 stall time) and if
     * <code>Rstall</code> has the same minimum frame duration this will not cause a frame rate loss if all
     * buffers from the previous <code>Rstall</code> have already been delivered.</p>
     * <p>For more details about stalling, see {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputStallDuration }.</p>
     * <p>This control is only effective if {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} or {@link CaptureRequest#CONTROL_MODE android.control.mode} is set to
     * OFF; otherwise the auto-exposure algorithm will override this value.</p>
     * <p><em>Note:</em> Prior to Android 13, this field was described as measuring the duration from
     * start of frame exposure to start of next frame exposure, which doesn't reflect the
     * definition from sensor manufacturer. A mobile sensor defines the frame duration as
     * intervals between sensor readouts.</p>
     * <p><b>Units</b>: Nanoseconds</p>
     * <p><b>Range of valid values:</b><br>
     * See {@link CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION android.sensor.info.maxFrameDuration}, {@link android.hardware.camera2.params.StreamConfigurationMap }.
     * The duration is capped to <code>max(duration, exposureTime + overhead)</code>.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION
     */
    @PublicKey
    @NonNull
    public static final Key<Long> SENSOR_FRAME_DURATION =
            new Key<Long>("android.sensor.frameDuration", long.class);

    /**
     * <p>The amount of gain applied to sensor data
     * before processing.</p>
     * <p>The sensitivity is the standard ISO sensitivity value,
     * as defined in ISO 12232:2006.</p>
     * <p>The sensitivity must be within {@link CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE android.sensor.info.sensitivityRange}, and
     * if if it less than {@link CameraCharacteristics#SENSOR_MAX_ANALOG_SENSITIVITY android.sensor.maxAnalogSensitivity}, the camera device
     * is guaranteed to use only analog amplification for applying the gain.</p>
     * <p>If the camera device cannot apply the exact sensitivity
     * requested, it will reduce the gain to the nearest supported
     * value. The final sensitivity used will be available in the
     * output capture result.</p>
     * <p>This control is only effective if {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} or {@link CaptureRequest#CONTROL_MODE android.control.mode} is set to
     * OFF; otherwise the auto-exposure algorithm will override this value.</p>
     * <p>Note that for devices supporting postRawSensitivityBoost, the total sensitivity applied
     * to the final processed image is the combination of {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity} and
     * {@link CaptureRequest#CONTROL_POST_RAW_SENSITIVITY_BOOST android.control.postRawSensitivityBoost}. In case the application uses the sensor
     * sensitivity from last capture result of an auto request for a manual request, in order
     * to achieve the same brightness in the output image, the application should also
     * set postRawSensitivityBoost.</p>
     * <p><b>Units</b>: ISO arithmetic units</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE android.sensor.info.sensitivityRange}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see CaptureRequest#CONTROL_POST_RAW_SENSITIVITY_BOOST
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE
     * @see CameraCharacteristics#SENSOR_MAX_ANALOG_SENSITIVITY
     * @see CaptureRequest#SENSOR_SENSITIVITY
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> SENSOR_SENSITIVITY =
            new Key<Integer>("android.sensor.sensitivity", int.class);

    /**
     * <p>A pixel <code>[R, G_even, G_odd, B]</code> that supplies the test pattern
     * when {@link CaptureRequest#SENSOR_TEST_PATTERN_MODE android.sensor.testPatternMode} is SOLID_COLOR.</p>
     * <p>Each color channel is treated as an unsigned 32-bit integer.
     * The camera device then uses the most significant X bits
     * that correspond to how many bits are in its Bayer raw sensor
     * output.</p>
     * <p>For example, a sensor with RAW10 Bayer output would use the
     * 10 most significant bits from each color channel.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    @PublicKey
    @NonNull
    public static final Key<int[]> SENSOR_TEST_PATTERN_DATA =
            new Key<int[]>("android.sensor.testPatternData", int[].class);

    /**
     * <p>When enabled, the sensor sends a test pattern instead of
     * doing a real exposure from the camera.</p>
     * <p>When a test pattern is enabled, all manual sensor controls specified
     * by android.sensor.* will be ignored. All other controls should
     * work as normal.</p>
     * <p>For example, if manual flash is enabled, flash firing should still
     * occur (and that the test pattern remain unmodified, since the flash
     * would not actually affect it).</p>
     * <p>Defaults to OFF.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #SENSOR_TEST_PATTERN_MODE_OFF OFF}</li>
     *   <li>{@link #SENSOR_TEST_PATTERN_MODE_SOLID_COLOR SOLID_COLOR}</li>
     *   <li>{@link #SENSOR_TEST_PATTERN_MODE_COLOR_BARS COLOR_BARS}</li>
     *   <li>{@link #SENSOR_TEST_PATTERN_MODE_COLOR_BARS_FADE_TO_GRAY COLOR_BARS_FADE_TO_GRAY}</li>
     *   <li>{@link #SENSOR_TEST_PATTERN_MODE_PN9 PN9}</li>
     *   <li>{@link #SENSOR_TEST_PATTERN_MODE_CUSTOM1 CUSTOM1}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#SENSOR_AVAILABLE_TEST_PATTERN_MODES android.sensor.availableTestPatternModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_AVAILABLE_TEST_PATTERN_MODES
     * @see #SENSOR_TEST_PATTERN_MODE_OFF
     * @see #SENSOR_TEST_PATTERN_MODE_SOLID_COLOR
     * @see #SENSOR_TEST_PATTERN_MODE_COLOR_BARS
     * @see #SENSOR_TEST_PATTERN_MODE_COLOR_BARS_FADE_TO_GRAY
     * @see #SENSOR_TEST_PATTERN_MODE_PN9
     * @see #SENSOR_TEST_PATTERN_MODE_CUSTOM1
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> SENSOR_TEST_PATTERN_MODE =
            new Key<Integer>("android.sensor.testPatternMode", int.class);

    /**
     * <p>Switches sensor pixel mode between maximum resolution mode and default mode.</p>
     * <p>This key controls whether the camera sensor operates in
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION }
     * mode or not. By default, all camera devices operate in
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_DEFAULT } mode.
     * When operating in
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_DEFAULT } mode, sensors
     * would typically perform pixel binning in order to improve low light
     * performance, noise reduction etc. However, in
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION }
     * mode, sensors typically operate in unbinned mode allowing for a larger image size.
     * The stream configurations supported in
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION }
     * mode are also different from those of
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_DEFAULT } mode.
     * They can be queried through
     * {@link android.hardware.camera2.CameraCharacteristics#get } with
     * {@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION }.
     * Unless reported by both
     * {@link android.hardware.camera2.params.StreamConfigurationMap }s, the outputs from
     * <code>{@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION android.scaler.streamConfigurationMapMaximumResolution}</code> and
     * <code>{@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP android.scaler.streamConfigurationMap}</code>
     * must not be mixed in the same CaptureRequest. In other words, these outputs are
     * exclusive to each other.
     * This key does not need to be set for reprocess requests.
     * This key will be be present on devices supporting the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR }
     * capability. It may also be present on devices which do not support the aforementioned
     * capability. In that case:</p>
     * <ul>
     * <li>
     * <p>The mandatory stream combinations listed in
     *   {@link CameraCharacteristics#SCALER_MANDATORY_MAXIMUM_RESOLUTION_STREAM_COMBINATIONS android.scaler.mandatoryMaximumResolutionStreamCombinations}  would not apply.</p>
     * </li>
     * <li>
     * <p>The bayer pattern of {@code RAW} streams when
     *   {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION }
     *   is selected will be the one listed in {@link CameraCharacteristics#SENSOR_INFO_BINNING_FACTOR android.sensor.info.binningFactor}.</p>
     * </li>
     * <li>
     * <p>The following keys will always be present:</p>
     * <ul>
     * <li>{@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION android.scaler.streamConfigurationMapMaximumResolution}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.activeArraySizeMaximumResolution}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.pixelArraySizeMaximumResolution}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION android.sensor.info.preCorrectionActiveArraySizeMaximumResolution}</li>
     * </ul>
     * </li>
     * </ul>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #SENSOR_PIXEL_MODE_DEFAULT DEFAULT}</li>
     *   <li>{@link #SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION MAXIMUM_RESOLUTION}</li>
     * </ul>
     *
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SCALER_MANDATORY_MAXIMUM_RESOLUTION_STREAM_COMBINATIONS
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CameraCharacteristics#SENSOR_INFO_BINNING_FACTOR
     * @see CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
     * @see #SENSOR_PIXEL_MODE_DEFAULT
     * @see #SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> SENSOR_PIXEL_MODE =
            new Key<Integer>("android.sensor.pixelMode", int.class);

    /**
     * <p>Quality of lens shading correction applied
     * to the image data.</p>
     * <p>When set to OFF mode, no lens shading correction will be applied by the
     * camera device, and an identity lens shading map data will be provided
     * if <code>{@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode} == ON</code>. For example, for lens
     * shading map with size of <code>[ 4, 3 ]</code>,
     * the output {@link CaptureResult#STATISTICS_LENS_SHADING_CORRECTION_MAP android.statistics.lensShadingCorrectionMap} for this case will be an identity
     * map shown below:</p>
     * <pre><code>[ 1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0,
     *  1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0,
     *  1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0,
     *  1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0,
     *  1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0,
     *  1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0 ]
     * </code></pre>
     * <p>When set to other modes, lens shading correction will be applied by the camera
     * device. Applications can request lens shading map data by setting
     * {@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode} to ON, and then the camera device will provide lens
     * shading map data in {@link CaptureResult#STATISTICS_LENS_SHADING_CORRECTION_MAP android.statistics.lensShadingCorrectionMap}; the returned shading map
     * data will be the one applied by the camera device for this capture request.</p>
     * <p>The shading map data may depend on the auto-exposure (AE) and AWB statistics, therefore
     * the reliability of the map data may be affected by the AE and AWB algorithms. When AE and
     * AWB are in AUTO modes({@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} <code>!=</code> OFF and {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} <code>!=</code>
     * OFF), to get best results, it is recommended that the applications wait for the AE and AWB
     * to be converged before using the returned shading map data.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #SHADING_MODE_OFF OFF}</li>
     *   <li>{@link #SHADING_MODE_FAST FAST}</li>
     *   <li>{@link #SHADING_MODE_HIGH_QUALITY HIGH_QUALITY}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#SHADING_AVAILABLE_MODES android.shading.availableModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#SHADING_AVAILABLE_MODES
     * @see CaptureResult#STATISTICS_LENS_SHADING_CORRECTION_MAP
     * @see CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE
     * @see #SHADING_MODE_OFF
     * @see #SHADING_MODE_FAST
     * @see #SHADING_MODE_HIGH_QUALITY
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> SHADING_MODE =
            new Key<Integer>("android.shading.mode", int.class);

    /**
     * <p>Operating mode for the face detector
     * unit.</p>
     * <p>Whether face detection is enabled, and whether it
     * should output just the basic fields or the full set of
     * fields.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #STATISTICS_FACE_DETECT_MODE_OFF OFF}</li>
     *   <li>{@link #STATISTICS_FACE_DETECT_MODE_SIMPLE SIMPLE}</li>
     *   <li>{@link #STATISTICS_FACE_DETECT_MODE_FULL FULL}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES android.statistics.info.availableFaceDetectModes}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES
     * @see #STATISTICS_FACE_DETECT_MODE_OFF
     * @see #STATISTICS_FACE_DETECT_MODE_SIMPLE
     * @see #STATISTICS_FACE_DETECT_MODE_FULL
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> STATISTICS_FACE_DETECT_MODE =
            new Key<Integer>("android.statistics.faceDetectMode", int.class);

    /**
     * <p>Operating mode for hot pixel map generation.</p>
     * <p>If set to <code>true</code>, a hot pixel map is returned in {@link CaptureResult#STATISTICS_HOT_PIXEL_MAP android.statistics.hotPixelMap}.
     * If set to <code>false</code>, no hot pixel map will be returned.</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES android.statistics.info.availableHotPixelMapModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureResult#STATISTICS_HOT_PIXEL_MAP
     * @see CameraCharacteristics#STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES
     */
    @PublicKey
    @NonNull
    public static final Key<Boolean> STATISTICS_HOT_PIXEL_MAP_MODE =
            new Key<Boolean>("android.statistics.hotPixelMapMode", boolean.class);

    /**
     * <p>Whether the camera device will output the lens
     * shading map in output result metadata.</p>
     * <p>When set to ON,
     * android.statistics.lensShadingMap will be provided in
     * the output result metadata.</p>
     * <p>ON is always supported on devices with the RAW capability.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #STATISTICS_LENS_SHADING_MAP_MODE_OFF OFF}</li>
     *   <li>{@link #STATISTICS_LENS_SHADING_MAP_MODE_ON ON}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES android.statistics.info.availableLensShadingMapModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES
     * @see #STATISTICS_LENS_SHADING_MAP_MODE_OFF
     * @see #STATISTICS_LENS_SHADING_MAP_MODE_ON
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> STATISTICS_LENS_SHADING_MAP_MODE =
            new Key<Integer>("android.statistics.lensShadingMapMode", int.class);

    /**
     * <p>A control for selecting whether optical stabilization (OIS) position
     * information is included in output result metadata.</p>
     * <p>Since optical image stabilization generally involves motion much faster than the duration
     * of individual image exposure, multiple OIS samples can be included for a single capture
     * result. For example, if the OIS reporting operates at 200 Hz, a typical camera operating
     * at 30fps may have 6-7 OIS samples per capture result. This information can be combined
     * with the rolling shutter skew to account for lens motion during image exposure in
     * post-processing algorithms.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #STATISTICS_OIS_DATA_MODE_OFF OFF}</li>
     *   <li>{@link #STATISTICS_OIS_DATA_MODE_ON ON}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES android.statistics.info.availableOisDataModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES
     * @see #STATISTICS_OIS_DATA_MODE_OFF
     * @see #STATISTICS_OIS_DATA_MODE_ON
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> STATISTICS_OIS_DATA_MODE =
            new Key<Integer>("android.statistics.oisDataMode", int.class);

    /**
     * <p>Tonemapping / contrast / gamma curve for the blue
     * channel, to use when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * CONTRAST_CURVE.</p>
     * <p>See android.tonemap.curveRed for more details.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#TONEMAP_MODE
     * @hide
     */
    public static final Key<float[]> TONEMAP_CURVE_BLUE =
            new Key<float[]>("android.tonemap.curveBlue", float[].class);

    /**
     * <p>Tonemapping / contrast / gamma curve for the green
     * channel, to use when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * CONTRAST_CURVE.</p>
     * <p>See android.tonemap.curveRed for more details.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#TONEMAP_MODE
     * @hide
     */
    public static final Key<float[]> TONEMAP_CURVE_GREEN =
            new Key<float[]>("android.tonemap.curveGreen", float[].class);

    /**
     * <p>Tonemapping / contrast / gamma curve for the red
     * channel, to use when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * CONTRAST_CURVE.</p>
     * <p>Each channel's curve is defined by an array of control points:</p>
     * <pre><code>android.tonemap.curveRed =
     *   [ P0in, P0out, P1in, P1out, P2in, P2out, P3in, P3out, ..., PNin, PNout ]
     * 2 &lt;= N &lt;= {@link CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS android.tonemap.maxCurvePoints}</code></pre>
     * <p>These are sorted in order of increasing <code>Pin</code>; it is
     * required that input values 0.0 and 1.0 are included in the list to
     * define a complete mapping. For input values between control points,
     * the camera device must linearly interpolate between the control
     * points.</p>
     * <p>Each curve can have an independent number of points, and the number
     * of points can be less than max (that is, the request doesn't have to
     * always provide a curve with number of points equivalent to
     * {@link CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS android.tonemap.maxCurvePoints}).</p>
     * <p>For devices with MONOCHROME capability, all three channels must have the same set of
     * control points.</p>
     * <p>A few examples, and their corresponding graphical mappings; these
     * only specify the red channel and the precision is limited to 4
     * digits, for conciseness.</p>
     * <p>Linear mapping:</p>
     * <pre><code>android.tonemap.curveRed = [ 0, 0, 1.0, 1.0 ]
     * </code></pre>
     * <p><img alt="Linear mapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/linear_tonemap.png" /></p>
     * <p>Invert mapping:</p>
     * <pre><code>android.tonemap.curveRed = [ 0, 1.0, 1.0, 0 ]
     * </code></pre>
     * <p><img alt="Inverting mapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/inverse_tonemap.png" /></p>
     * <p>Gamma 1/2.2 mapping, with 16 control points:</p>
     * <pre><code>android.tonemap.curveRed = [
     *   0.0000, 0.0000, 0.0667, 0.2920, 0.1333, 0.4002, 0.2000, 0.4812,
     *   0.2667, 0.5484, 0.3333, 0.6069, 0.4000, 0.6594, 0.4667, 0.7072,
     *   0.5333, 0.7515, 0.6000, 0.7928, 0.6667, 0.8317, 0.7333, 0.8685,
     *   0.8000, 0.9035, 0.8667, 0.9370, 0.9333, 0.9691, 1.0000, 1.0000 ]
     * </code></pre>
     * <p><img alt="Gamma = 1/2.2 tonemapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/gamma_tonemap.png" /></p>
     * <p>Standard sRGB gamma mapping, per IEC 61966-2-1:1999, with 16 control points:</p>
     * <pre><code>android.tonemap.curveRed = [
     *   0.0000, 0.0000, 0.0667, 0.2864, 0.1333, 0.4007, 0.2000, 0.4845,
     *   0.2667, 0.5532, 0.3333, 0.6125, 0.4000, 0.6652, 0.4667, 0.7130,
     *   0.5333, 0.7569, 0.6000, 0.7977, 0.6667, 0.8360, 0.7333, 0.8721,
     *   0.8000, 0.9063, 0.8667, 0.9389, 0.9333, 0.9701, 1.0000, 1.0000 ]
     * </code></pre>
     * <p><img alt="sRGB tonemapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/srgb_tonemap.png" /></p>
     * <p><b>Range of valid values:</b><br>
     * 0-1 on both input and output coordinates, normalized
     * as a floating-point value such that 0 == black and 1 == white.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS
     * @see CaptureRequest#TONEMAP_MODE
     * @hide
     */
    public static final Key<float[]> TONEMAP_CURVE_RED =
            new Key<float[]>("android.tonemap.curveRed", float[].class);

    /**
     * <p>Tonemapping / contrast / gamma curve to use when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode}
     * is CONTRAST_CURVE.</p>
     * <p>The tonemapCurve consist of three curves for each of red, green, and blue
     * channels respectively. The following example uses the red channel as an
     * example. The same logic applies to green and blue channel.
     * Each channel's curve is defined by an array of control points:</p>
     * <pre><code>curveRed =
     *   [ P0(in, out), P1(in, out), P2(in, out), P3(in, out), ..., PN(in, out) ]
     * 2 &lt;= N &lt;= {@link CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS android.tonemap.maxCurvePoints}</code></pre>
     * <p>These are sorted in order of increasing <code>Pin</code>; it is always
     * guaranteed that input values 0.0 and 1.0 are included in the list to
     * define a complete mapping. For input values between control points,
     * the camera device must linearly interpolate between the control
     * points.</p>
     * <p>Each curve can have an independent number of points, and the number
     * of points can be less than max (that is, the request doesn't have to
     * always provide a curve with number of points equivalent to
     * {@link CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS android.tonemap.maxCurvePoints}).</p>
     * <p>For devices with MONOCHROME capability, all three channels must have the same set of
     * control points.</p>
     * <p>A few examples, and their corresponding graphical mappings; these
     * only specify the red channel and the precision is limited to 4
     * digits, for conciseness.</p>
     * <p>Linear mapping:</p>
     * <pre><code>curveRed = [ (0, 0), (1.0, 1.0) ]
     * </code></pre>
     * <p><img alt="Linear mapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/linear_tonemap.png" /></p>
     * <p>Invert mapping:</p>
     * <pre><code>curveRed = [ (0, 1.0), (1.0, 0) ]
     * </code></pre>
     * <p><img alt="Inverting mapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/inverse_tonemap.png" /></p>
     * <p>Gamma 1/2.2 mapping, with 16 control points:</p>
     * <pre><code>curveRed = [
     *   (0.0000, 0.0000), (0.0667, 0.2920), (0.1333, 0.4002), (0.2000, 0.4812),
     *   (0.2667, 0.5484), (0.3333, 0.6069), (0.4000, 0.6594), (0.4667, 0.7072),
     *   (0.5333, 0.7515), (0.6000, 0.7928), (0.6667, 0.8317), (0.7333, 0.8685),
     *   (0.8000, 0.9035), (0.8667, 0.9370), (0.9333, 0.9691), (1.0000, 1.0000) ]
     * </code></pre>
     * <p><img alt="Gamma = 1/2.2 tonemapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/gamma_tonemap.png" /></p>
     * <p>Standard sRGB gamma mapping, per IEC 61966-2-1:1999, with 16 control points:</p>
     * <pre><code>curveRed = [
     *   (0.0000, 0.0000), (0.0667, 0.2864), (0.1333, 0.4007), (0.2000, 0.4845),
     *   (0.2667, 0.5532), (0.3333, 0.6125), (0.4000, 0.6652), (0.4667, 0.7130),
     *   (0.5333, 0.7569), (0.6000, 0.7977), (0.6667, 0.8360), (0.7333, 0.8721),
     *   (0.8000, 0.9063), (0.8667, 0.9389), (0.9333, 0.9701), (1.0000, 1.0000) ]
     * </code></pre>
     * <p><img alt="sRGB tonemapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/srgb_tonemap.png" /></p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS
     * @see CaptureRequest#TONEMAP_MODE
     */
    @PublicKey
    @NonNull
    @SyntheticKey
    public static final Key<android.hardware.camera2.params.TonemapCurve> TONEMAP_CURVE =
            new Key<android.hardware.camera2.params.TonemapCurve>("android.tonemap.curve", android.hardware.camera2.params.TonemapCurve.class);

    /**
     * <p>High-level global contrast/gamma/tonemapping control.</p>
     * <p>When switching to an application-defined contrast curve by setting
     * {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} to CONTRAST_CURVE, the curve is defined
     * per-channel with a set of <code>(in, out)</code> points that specify the
     * mapping from input high-bit-depth pixel value to the output
     * low-bit-depth value.  Since the actual pixel ranges of both input
     * and output may change depending on the camera pipeline, the values
     * are specified by normalized floating-point numbers.</p>
     * <p>More-complex color mapping operations such as 3D color look-up
     * tables, selective chroma enhancement, or other non-linear color
     * transforms will be disabled when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * CONTRAST_CURVE.</p>
     * <p>When using either FAST or HIGH_QUALITY, the camera device will
     * emit its own tonemap curve in {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}.
     * These values are always available, and as close as possible to the
     * actually used nonlinear/nonglobal transforms.</p>
     * <p>If a request is sent with CONTRAST_CURVE with the camera device's
     * provided curve in FAST or HIGH_QUALITY, the image's tonemap will be
     * roughly the same.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #TONEMAP_MODE_CONTRAST_CURVE CONTRAST_CURVE}</li>
     *   <li>{@link #TONEMAP_MODE_FAST FAST}</li>
     *   <li>{@link #TONEMAP_MODE_HIGH_QUALITY HIGH_QUALITY}</li>
     *   <li>{@link #TONEMAP_MODE_GAMMA_VALUE GAMMA_VALUE}</li>
     *   <li>{@link #TONEMAP_MODE_PRESET_CURVE PRESET_CURVE}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#TONEMAP_AVAILABLE_TONE_MAP_MODES android.tonemap.availableToneMapModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#TONEMAP_AVAILABLE_TONE_MAP_MODES
     * @see CaptureRequest#TONEMAP_CURVE
     * @see CaptureRequest#TONEMAP_MODE
     * @see #TONEMAP_MODE_CONTRAST_CURVE
     * @see #TONEMAP_MODE_FAST
     * @see #TONEMAP_MODE_HIGH_QUALITY
     * @see #TONEMAP_MODE_GAMMA_VALUE
     * @see #TONEMAP_MODE_PRESET_CURVE
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> TONEMAP_MODE =
            new Key<Integer>("android.tonemap.mode", int.class);

    /**
     * <p>Tonemapping curve to use when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * GAMMA_VALUE</p>
     * <p>The tonemap curve will be defined the following formula:</p>
     * <ul>
     * <li>OUT = pow(IN, 1.0 / gamma)</li>
     * </ul>
     * <p>where IN and OUT is the input pixel value scaled to range [0.0, 1.0],
     * pow is the power function and gamma is the gamma value specified by this
     * key.</p>
     * <p>The same curve will be applied to all color channels. The camera device
     * may clip the input gamma value to its supported range. The actual applied
     * value will be returned in capture result.</p>
     * <p>The valid range of gamma value varies on different devices, but values
     * within [1.0, 5.0] are guaranteed not to be clipped.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#TONEMAP_MODE
     */
    @PublicKey
    @NonNull
    public static final Key<Float> TONEMAP_GAMMA =
            new Key<Float>("android.tonemap.gamma", float.class);

    /**
     * <p>Tonemapping curve to use when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * PRESET_CURVE</p>
     * <p>The tonemap curve will be defined by specified standard.</p>
     * <p>sRGB (approximated by 16 control points):</p>
     * <p><img alt="sRGB tonemapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/srgb_tonemap.png" /></p>
     * <p>Rec. 709 (approximated by 16 control points):</p>
     * <p><img alt="Rec. 709 tonemapping curve" src="/reference/images/camera2/metadata/android.tonemap.curveRed/rec709_tonemap.png" /></p>
     * <p>Note that above figures show a 16 control points approximation of preset
     * curves. Camera devices may apply a different approximation to the curve.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #TONEMAP_PRESET_CURVE_SRGB SRGB}</li>
     *   <li>{@link #TONEMAP_PRESET_CURVE_REC709 REC709}</li>
     * </ul>
     *
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#TONEMAP_MODE
     * @see #TONEMAP_PRESET_CURVE_SRGB
     * @see #TONEMAP_PRESET_CURVE_REC709
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> TONEMAP_PRESET_CURVE =
            new Key<Integer>("android.tonemap.presetCurve", int.class);

    /**
     * <p>This LED is nominally used to indicate to the user
     * that the camera is powered on and may be streaming images back to the
     * Application Processor. In certain rare circumstances, the OS may
     * disable this when video is processed locally and not transmitted to
     * any untrusted applications.</p>
     * <p>In particular, the LED <em>must</em> always be on when the data could be
     * transmitted off the device. The LED <em>should</em> always be on whenever
     * data is stored locally on the device.</p>
     * <p>The LED <em>may</em> be off if a trusted application is using the data that
     * doesn't violate the above rules.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * @hide
     */
    public static final Key<Boolean> LED_TRANSMIT =
            new Key<Boolean>("android.led.transmit", boolean.class);

    /**
     * <p>Whether black-level compensation is locked
     * to its current values, or is free to vary.</p>
     * <p>When set to <code>true</code> (ON), the values used for black-level
     * compensation will not change until the lock is set to
     * <code>false</code> (OFF).</p>
     * <p>Since changes to certain capture parameters (such as
     * exposure time) may require resetting of black level
     * compensation, the camera device must report whether setting
     * the black level lock was successful in the output result
     * metadata.</p>
     * <p>For example, if a sequence of requests is as follows:</p>
     * <ul>
     * <li>Request 1: Exposure = 10ms, Black level lock = OFF</li>
     * <li>Request 2: Exposure = 10ms, Black level lock = ON</li>
     * <li>Request 3: Exposure = 10ms, Black level lock = ON</li>
     * <li>Request 4: Exposure = 20ms, Black level lock = ON</li>
     * <li>Request 5: Exposure = 20ms, Black level lock = ON</li>
     * <li>Request 6: Exposure = 20ms, Black level lock = ON</li>
     * </ul>
     * <p>And the exposure change in Request 4 requires the camera
     * device to reset the black level offsets, then the output
     * result metadata is expected to be:</p>
     * <ul>
     * <li>Result 1: Exposure = 10ms, Black level lock = OFF</li>
     * <li>Result 2: Exposure = 10ms, Black level lock = ON</li>
     * <li>Result 3: Exposure = 10ms, Black level lock = ON</li>
     * <li>Result 4: Exposure = 20ms, Black level lock = OFF</li>
     * <li>Result 5: Exposure = 20ms, Black level lock = ON</li>
     * <li>Result 6: Exposure = 20ms, Black level lock = ON</li>
     * </ul>
     * <p>This indicates to the application that on frame 4, black
     * levels were reset due to exposure value changes, and pixel
     * values may not be consistent across captures.</p>
     * <p>The camera device will maintain the lock to the extent
     * possible, only overriding the lock to OFF when changes to
     * other request parameters require a black level recalculation
     * or reset.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    @PublicKey
    @NonNull
    public static final Key<Boolean> BLACK_LEVEL_LOCK =
            new Key<Boolean>("android.blackLevel.lock", boolean.class);

    /**
     * <p>The amount of exposure time increase factor applied to the original output
     * frame by the application processing before sending for reprocessing.</p>
     * <p>This is optional, and will be supported if the camera device supports YUV_REPROCESSING
     * capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains YUV_REPROCESSING).</p>
     * <p>For some YUV reprocessing use cases, the application may choose to filter the original
     * output frames to effectively reduce the noise to the same level as a frame that was
     * captured with longer exposure time. To be more specific, assuming the original captured
     * images were captured with a sensitivity of S and an exposure time of T, the model in
     * the camera device is that the amount of noise in the image would be approximately what
     * would be expected if the original capture parameters had been a sensitivity of
     * S/effectiveExposureFactor and an exposure time of T*effectiveExposureFactor, rather
     * than S and T respectively. If the captured images were processed by the application
     * before being sent for reprocessing, then the application may have used image processing
     * algorithms and/or multi-frame image fusion to reduce the noise in the
     * application-processed images (input images). By using the effectiveExposureFactor
     * control, the application can communicate to the camera device the actual noise level
     * improvement in the application-processed image. With this information, the camera
     * device can select appropriate noise reduction and edge enhancement parameters to avoid
     * excessive noise reduction ({@link CaptureRequest#NOISE_REDUCTION_MODE android.noiseReduction.mode}) and insufficient edge
     * enhancement ({@link CaptureRequest#EDGE_MODE android.edge.mode}) being applied to the reprocessed frames.</p>
     * <p>For example, for multi-frame image fusion use case, the application may fuse
     * multiple output frames together to a final frame for reprocessing. When N image are
     * fused into 1 image for reprocessing, the exposure time increase factor could be up to
     * square root of N (based on a simple photon shot noise model). The camera device will
     * adjust the reprocessing noise reduction and edge enhancement parameters accordingly to
     * produce the best quality images.</p>
     * <p>This is relative factor, 1.0 indicates the application hasn't processed the input
     * buffer in a way that affects its effective exposure time.</p>
     * <p>This control is only effective for YUV reprocessing capture request. For noise
     * reduction reprocessing, it is only effective when <code>{@link CaptureRequest#NOISE_REDUCTION_MODE android.noiseReduction.mode} != OFF</code>.
     * Similarly, for edge enhancement reprocessing, it is only effective when
     * <code>{@link CaptureRequest#EDGE_MODE android.edge.mode} != OFF</code>.</p>
     * <p><b>Units</b>: Relative exposure time increase factor.</p>
     * <p><b>Range of valid values:</b><br>
     * &gt;= 1.0</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#EDGE_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    @PublicKey
    @NonNull
    public static final Key<Float> REPROCESS_EFFECTIVE_EXPOSURE_FACTOR =
            new Key<Float>("android.reprocess.effectiveExposureFactor", float.class);

    /**
     * <p>Mode of operation for the lens distortion correction block.</p>
     * <p>The lens distortion correction block attempts to improve image quality by fixing
     * radial, tangential, or other geometric aberrations in the camera device's optics.  If
     * available, the {@link CameraCharacteristics#LENS_DISTORTION android.lens.distortion} field documents the lens's distortion parameters.</p>
     * <p>OFF means no distortion correction is done.</p>
     * <p>FAST/HIGH_QUALITY both mean camera device determined distortion correction will be
     * applied. HIGH_QUALITY mode indicates that the camera device will use the highest-quality
     * correction algorithms, even if it slows down capture rate. FAST means the camera device
     * will not slow down capture rate when applying correction. FAST may be the same as OFF if
     * any correction at all would slow down capture rate.  Every output stream will have a
     * similar amount of enhancement applied.</p>
     * <p>The correction only applies to processed outputs such as YUV, Y8, JPEG, or DEPTH16; it is
     * not applied to any RAW output.</p>
     * <p>This control will be on by default on devices that support this control. Applications
     * disabling distortion correction need to pay extra attention with the coordinate system of
     * metering regions, crop region, and face rectangles. When distortion correction is OFF,
     * metadata coordinates follow the coordinate system of
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}. When distortion is not OFF, metadata
     * coordinates follow the coordinate system of {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.  The
     * camera device will map these metadata fields to match the corrected image produced by the
     * camera device, for both capture requests and results.  However, this mapping is not very
     * precise, since rectangles do not generally map to rectangles when corrected.  Only linear
     * scaling between the active array and precorrection active array coordinates is
     * performed. Applications that require precise correction of metadata need to undo that
     * linear scaling, and apply a more complete correction that takes into the account the app's
     * own requirements.</p>
     * <p>The full list of metadata that is affected in this way by distortion correction is:</p>
     * <ul>
     * <li>{@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions}</li>
     * <li>{@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}</li>
     * <li>{@link CaptureResult#STATISTICS_FACES android.statistics.faces}</li>
     * </ul>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #DISTORTION_CORRECTION_MODE_OFF OFF}</li>
     *   <li>{@link #DISTORTION_CORRECTION_MODE_FAST FAST}</li>
     *   <li>{@link #DISTORTION_CORRECTION_MODE_HIGH_QUALITY HIGH_QUALITY}</li>
     * </ul>
     *
     * <p><b>Available values for this device:</b><br>
     * {@link CameraCharacteristics#DISTORTION_CORRECTION_AVAILABLE_MODES android.distortionCorrection.availableModes}</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_REGIONS
     * @see CaptureRequest#CONTROL_AF_REGIONS
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     * @see CameraCharacteristics#DISTORTION_CORRECTION_AVAILABLE_MODES
     * @see CameraCharacteristics#LENS_DISTORTION
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
     * @see CaptureResult#STATISTICS_FACES
     * @see #DISTORTION_CORRECTION_MODE_OFF
     * @see #DISTORTION_CORRECTION_MODE_FAST
     * @see #DISTORTION_CORRECTION_MODE_HIGH_QUALITY
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> DISTORTION_CORRECTION_MODE =
            new Key<Integer>("android.distortionCorrection.mode", int.class);

    /**
     * <p>Strength of the extension post-processing effect</p>
     * <p>This control allows Camera extension clients to configure the strength of the applied
     * extension effect. Strength equal to 0 means that the extension must not apply any
     * post-processing and return a regular captured frame. Strength equal to 100 is the
     * maximum level of post-processing. Values between 0 and 100 will have different effect
     * depending on the extension type as described below:</p>
     * <ul>
     * <li>{@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_BOKEH BOKEH} -
     * the strength is expected to control the amount of blur.</li>
     * <li>{@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_HDR HDR} and
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_NIGHT NIGHT} -
     * the strength can control the amount of images fused and the brightness of the final image.</li>
     * <li>{@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_FACE_RETOUCH FACE_RETOUCH} -
     * the strength value will control the amount of cosmetic enhancement and skin
     * smoothing.</li>
     * </ul>
     * <p>The control will be supported if the capture request key is part of the list generated by
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#getAvailableCaptureRequestKeys }.
     * The control is only defined and available to clients sending capture requests via
     * {@link android.hardware.camera2.CameraExtensionSession }.
     * If the client doesn't specify the extension strength value, then a default value will
     * be set by the extension. Clients can retrieve the default value by checking the
     * corresponding capture result.</p>
     * <p><b>Range of valid values:</b><br>
     * 0 - 100</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     */
    @PublicKey
    @NonNull
    public static final Key<Integer> EXTENSION_STRENGTH =
            new Key<Integer>("android.extension.strength", int.class);

    /**
     * <p>Used to apply an additional digital zoom factor for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.CameraMetadata#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>For the {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * feature, an additional zoom factor is applied on top of the existing {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}.
     * This additional zoom factor serves as a buffer to provide more flexibility for the
     * {@link android.hardware.camera2.CameraMetadata#EFV_STABILIZATION_MODE_LOCKED }
     * mode. If android.efv.paddingZoomFactor is not set, the default will be used.
     * The effectiveness of the stabilization may be influenced by the amount of padding zoom
     * applied. A higher padding zoom factor can stabilize the target region more effectively
     * with greater flexibility but may potentially impact image quality. Conversely, a lower
     * padding zoom factor may be used to prioritize preserving image quality, albeit with less
     * leeway in stabilizing the target region. It is recommended to set the
     * android.efv.paddingZoomFactor to at least 1.5.</p>
     * <p>If android.efv.autoZoom is enabled, the requested android.efv.paddingZoomFactor will be overridden.
     * android.efv.maxPaddingZoomFactor can be checked for more details on controlling the
     * padding zoom factor during android.efv.autoZoom.</p>
     * <p><b>Range of valid values:</b><br>
     * android.efv.paddingZoomFactorRange</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @hide
     */
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Float> EFV_PADDING_ZOOM_FACTOR =
            new Key<Float>("android.efv.paddingZoomFactor", float.class);

    /**
     * <p>Used to enable or disable auto zoom for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.CameraMetadata#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>Turn on auto zoom to let the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * feature decide at any given point a combination of
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} and android.efv.paddingZoomFactor
     * to keep the target region in view and stabilized. The combination chosen by the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * will equal the requested {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} multiplied with the requested
     * android.efv.paddingZoomFactor. A limit can be set on the padding zoom if wanting
     * to control image quality further using android.efv.maxPaddingZoomFactor.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @hide
     */
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Boolean> EFV_AUTO_ZOOM =
            new Key<Boolean>("android.efv.autoZoom", boolean.class);

    /**
     * <p>Used to limit the android.efv.paddingZoomFactor if
     * android.efv.autoZoom is enabled for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.CameraMetadata#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>If android.efv.autoZoom is enabled, this key can be used to set a limit
     * on the android.efv.paddingZoomFactor chosen by the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.CameraMetadata#EFV_STABILIZATION_MODE_LOCKED } mode
     * to control image quality.</p>
     * <p><b>Range of valid values:</b><br>
     * The range of android.efv.paddingZoomFactorRange. Use a value greater than or equal to
     * the android.efv.paddingZoomFactor to effectively utilize this key.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * @hide
     */
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Float> EFV_MAX_PADDING_ZOOM_FACTOR =
            new Key<Float>("android.efv.maxPaddingZoomFactor", float.class);

    /**
     * <p>Set the stabilization mode for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension</p>
     * <p>The desired stabilization mode. Gimbal stabilization mode provides simple, non-locked
     * video stabilization. Locked mode uses the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * stabilization feature to fixate on the current region, utilizing it as the target area for
     * stabilization.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #EFV_STABILIZATION_MODE_OFF OFF}</li>
     *   <li>{@link #EFV_STABILIZATION_MODE_GIMBAL GIMBAL}</li>
     *   <li>{@link #EFV_STABILIZATION_MODE_LOCKED LOCKED}</li>
     * </ul>
     *
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * @see #EFV_STABILIZATION_MODE_OFF
     * @see #EFV_STABILIZATION_MODE_GIMBAL
     * @see #EFV_STABILIZATION_MODE_LOCKED
     * @hide
     */
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Integer> EFV_STABILIZATION_MODE =
            new Key<Integer>("android.efv.stabilizationMode", int.class);

    /**
     * <p>Used to update the target region for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.CameraMetadata#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>A android.util.Pair<Integer,Integer> that represents the desired
     * <Horizontal,Vertical> shift of the current locked view (or target region) in
     * pixels. Negative values indicate left and upward shifts, while positive values indicate
     * right and downward shifts in the active array coordinate system.</p>
     * <p><b>Range of valid values:</b><br>
     * android.util.Pair<Integer,Integer> represents the
     * <Horizontal,Vertical> shift. The range for the horizontal shift is
     * [-max(android.efv.paddingRegion-left), max(android.efv.paddingRegion-right)].
     * The range for the vertical shift is
     * [-max(android.efv.paddingRegion-top), max(android.efv.paddingRegion-bottom)]</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * @hide
     */
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<android.util.Pair<Integer,Integer>> EFV_TRANSLATE_VIEWPORT =
            new Key<android.util.Pair<Integer,Integer>>("android.efv.translateViewport", new TypeReference<android.util.Pair<Integer,Integer>>() {{ }});

    /**
     * <p>Representing the desired clockwise rotation
     * of the target region in degrees for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.CameraMetadata#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>Value representing the desired clockwise rotation of the target
     * region in degrees.</p>
     * <p><b>Range of valid values:</b><br>
     * 0 to 360</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * @hide
     */
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Float> EFV_ROTATE_VIEWPORT =
            new Key<Float>("android.efv.rotateViewport", float.class);

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/









}
