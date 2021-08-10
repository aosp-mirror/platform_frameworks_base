/*
 * Copyright (C) 2015 The Android Open Source Project
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


package android.hardware.camera2.params;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.hardware.camera2.utils.SurfaceUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;

import static com.android.internal.util.Preconditions.*;

/**
 * A class for describing camera output, which contains a {@link Surface} and its specific
 * configuration for creating capture session.
 *
 * @see CameraDevice#createCaptureSessionByOutputConfigurations
 *
 */
public final class OutputConfiguration implements Parcelable {

    /**
     * Rotation constant: 0 degree rotation (no rotation)
     *
     * @hide
     */
    @SystemApi
    public static final int ROTATION_0 = 0;

    /**
     * Rotation constant: 90 degree counterclockwise rotation.
     *
     * @hide
     */
    @SystemApi
    public static final int ROTATION_90 = 1;

    /**
     * Rotation constant: 180 degree counterclockwise rotation.
     *
     * @hide
     */
    @SystemApi
    public static final int ROTATION_180 = 2;

    /**
     * Rotation constant: 270 degree counterclockwise rotation.
     *
     * @hide
     */
    @SystemApi
    public static final int ROTATION_270 = 3;

    /**
     * Invalid surface group ID.
     *
     *<p>An {@link OutputConfiguration} with this value indicates that the included surface
     *doesn't belong to any surface group.</p>
     */
    public static final int SURFACE_GROUP_ID_NONE = -1;

    /**
     * Create a new {@link OutputConfiguration} instance with a {@link Surface}.
     *
     * @param surface
     *          A Surface for camera to output to.
     *
     * <p>This constructor creates a default configuration, with a surface group ID of
     * {@value #SURFACE_GROUP_ID_NONE}.</p>
     *
     */
    public OutputConfiguration(@NonNull Surface surface) {
        this(SURFACE_GROUP_ID_NONE, surface, ROTATION_0);
    }

    /**
     * Unknown surface source type.
     */
    private final int SURFACE_TYPE_UNKNOWN = -1;

    /**
     * The surface is obtained from {@link android.view.SurfaceView}.
     */
    private final int SURFACE_TYPE_SURFACE_VIEW = 0;

    /**
     * The surface is obtained from {@link android.graphics.SurfaceTexture}.
     */
    private final int SURFACE_TYPE_SURFACE_TEXTURE = 1;

    /**
     * Maximum number of surfaces supported by one {@link OutputConfiguration}.
     *
     * <p>The combined number of surfaces added by the constructor and
     * {@link OutputConfiguration#addSurface} should not exceed this value.</p>
     *
     */
    private static final int MAX_SURFACES_COUNT = 2;

    /**
     * Create a new {@link OutputConfiguration} instance with a {@link Surface},
     * with a surface group ID.
     *
     * <p>
     * A surface group ID is used to identify which surface group this output surface belongs to. A
     * surface group is a group of output surfaces that are not intended to receive camera output
     * buffer streams simultaneously. The {@link CameraDevice} may be able to share the buffers used
     * by all the surfaces from the same surface group, therefore may reduce the overall memory
     * footprint. The application should only set the same set ID for the streams that are not
     * simultaneously streaming. A negative ID indicates that this surface doesn't belong to any
     * surface group. The default value is {@value #SURFACE_GROUP_ID_NONE}.</p>
     *
     * <p>For example, a video chat application that has an adaptive output resolution feature would
     * need two (or more) output resolutions, to switch resolutions without any output glitches.
     * However, at any given time, only one output is active to minimize outgoing network bandwidth
     * and encoding overhead.  To save memory, the application should set the video outputs to have
     * the same non-negative group ID, so that the camera device can share the same memory region
     * for the alternating outputs.</p>
     *
     * <p>It is not an error to include output streams with the same group ID in the same capture
     * request, but the resulting memory consumption may be higher than if the two streams were
     * not in the same surface group to begin with, especially if the outputs have substantially
     * different dimensions.</p>
     *
     * @param surfaceGroupId
     *          A group ID for this output, used for sharing memory between multiple outputs.
     * @param surface
     *          A Surface for camera to output to.
     *
     */
    public OutputConfiguration(int surfaceGroupId, @NonNull Surface surface) {
        this(surfaceGroupId, surface, ROTATION_0);
    }

    /**
     * Create a new {@link OutputConfiguration} instance.
     *
     * <p>This constructor takes an argument for desired camera rotation</p>
     *
     * @param surface
     *          A Surface for camera to output to.
     * @param rotation
     *          The desired rotation to be applied on camera output. Value must be one of
     *          ROTATION_[0, 90, 180, 270]. Note that when the rotation is 90 or 270 degrees,
     *          application should make sure corresponding surface size has width and height
     *          transposed relative to the width and height without rotation. For example,
     *          if application needs camera to capture 1280x720 picture and rotate it by 90 degree,
     *          application should set rotation to {@code ROTATION_90} and make sure the
     *          corresponding Surface size is 720x1280. Note that {@link CameraDevice} might
     *          throw {@code IllegalArgumentException} if device cannot perform such rotation.
     * @hide
     */
    @SystemApi
    public OutputConfiguration(@NonNull Surface surface, int rotation) {
        this(SURFACE_GROUP_ID_NONE, surface, rotation);
    }

    /**
     * Create a new {@link OutputConfiguration} instance, with rotation and a group ID.
     *
     * <p>This constructor takes an argument for desired camera rotation and for the surface group
     * ID.  See {@link #OutputConfiguration(int, Surface)} for details of the group ID.</p>
     *
     * @param surfaceGroupId
     *          A group ID for this output, used for sharing memory between multiple outputs.
     * @param surface
     *          A Surface for camera to output to.
     * @param rotation
     *          The desired rotation to be applied on camera output. Value must be one of
     *          ROTATION_[0, 90, 180, 270]. Note that when the rotation is 90 or 270 degrees,
     *          application should make sure corresponding surface size has width and height
     *          transposed relative to the width and height without rotation. For example,
     *          if application needs camera to capture 1280x720 picture and rotate it by 90 degree,
     *          application should set rotation to {@code ROTATION_90} and make sure the
     *          corresponding Surface size is 720x1280. Note that {@link CameraDevice} might
     *          throw {@code IllegalArgumentException} if device cannot perform such rotation.
     * @hide
     */
    @SystemApi
    public OutputConfiguration(int surfaceGroupId, @NonNull Surface surface, int rotation) {
        checkNotNull(surface, "Surface must not be null");
        checkArgumentInRange(rotation, ROTATION_0, ROTATION_270, "Rotation constant");
        mSurfaceGroupId = surfaceGroupId;
        mSurfaceType = SURFACE_TYPE_UNKNOWN;
        mSurfaces = new ArrayList<Surface>();
        mSurfaces.add(surface);
        mRotation = rotation;
        mConfiguredSize = SurfaceUtils.getSurfaceSize(surface);
        mConfiguredFormat = SurfaceUtils.getSurfaceFormat(surface);
        mConfiguredDataspace = SurfaceUtils.getSurfaceDataspace(surface);
        mConfiguredGenerationId = surface.getGenerationId();
        mIsDeferredConfig = false;
        mIsShared = false;
    }

    /**
     * Create a new {@link OutputConfiguration} instance, with desired Surface size and Surface
     * source class.
     * <p>
     * This constructor takes an argument for desired Surface size and the Surface source class
     * without providing the actual output Surface. This is used to setup an output configuration
     * with a deferred Surface. The application can use this output configuration to create a
     * session.
     * </p>
     * <p>
     * However, the actual output Surface must be set via {@link #addSurface} and the deferred
     * Surface configuration must be finalized via {@link
     * CameraCaptureSession#finalizeOutputConfigurations} before submitting a request with this
     * Surface target. The deferred Surface can only be obtained either from {@link
     * android.view.SurfaceView} by calling {@link android.view.SurfaceHolder#getSurface}, or from
     * {@link android.graphics.SurfaceTexture} via
     * {@link android.view.Surface#Surface(android.graphics.SurfaceTexture)}).
     * </p>
     *
     * @param surfaceSize Size for the deferred surface.
     * @param klass a non-{@code null} {@link Class} object reference that indicates the source of
     *            this surface. Only {@link android.view.SurfaceHolder SurfaceHolder.class} and
     *            {@link android.graphics.SurfaceTexture SurfaceTexture.class} are supported.
     * @throws IllegalArgumentException if the Surface source class is not supported, or Surface
     *         size is zero.
     */
    public <T> OutputConfiguration(@NonNull Size surfaceSize, @NonNull Class<T> klass) {
        checkNotNull(klass, "surfaceSize must not be null");
        checkNotNull(klass, "klass must not be null");
        if (klass == android.view.SurfaceHolder.class) {
            mSurfaceType = SURFACE_TYPE_SURFACE_VIEW;
        } else if (klass == android.graphics.SurfaceTexture.class) {
            mSurfaceType = SURFACE_TYPE_SURFACE_TEXTURE;
        } else {
            mSurfaceType = SURFACE_TYPE_UNKNOWN;
            throw new IllegalArgumentException("Unknow surface source class type");
        }

        if (surfaceSize.getWidth() == 0 || surfaceSize.getHeight() == 0) {
            throw new IllegalArgumentException("Surface size needs to be non-zero");
        }

        mSurfaceGroupId = SURFACE_GROUP_ID_NONE;
        mSurfaces = new ArrayList<Surface>();
        mRotation = ROTATION_0;
        mConfiguredSize = surfaceSize;
        mConfiguredFormat = StreamConfigurationMap.imageFormatToInternal(ImageFormat.PRIVATE);
        mConfiguredDataspace = StreamConfigurationMap.imageFormatToDataspace(ImageFormat.PRIVATE);
        mConfiguredGenerationId = 0;
        mIsDeferredConfig = true;
        mIsShared = false;
    }

    /**
     * Enable multiple surfaces sharing the same OutputConfiguration
     *
     * <p>For advanced use cases, a camera application may require more streams than the combination
     * guaranteed by {@link CameraDevice#createCaptureSession}. In this case, more than one
     * compatible surface can be attached to an OutputConfiguration so that they map to one
     * camera stream, and the outputs share memory buffers when possible. </p>
     *
     * <p>Two surfaces are compatible in the below cases:</p>
     *
     * <li> Surfaces with the same size, format, dataSpace, and Surface source class. In this case,
     * {@link CameraDevice#createCaptureSessionByOutputConfigurations} is guaranteed to succeed.
     *
     * <li> Surfaces with the same size, format, and dataSpace, but different Surface source classes
     * that are generally not compatible. However, on some devices, the underlying camera device is
     * able to use the same buffer layout for both surfaces. The only way to discover if this is the
     * case is to create a capture session with that output configuration. For example, if the
     * camera device uses the same private buffer format between a SurfaceView/SurfaceTexture and a
     * MediaRecorder/MediaCodec, {@link CameraDevice#createCaptureSessionByOutputConfigurations}
     * will succeed. Otherwise, it fails with {@link
     * CameraCaptureSession.StateCallback#onConfigureFailed}.
     * </ol>
     *
     * <p>To enable surface sharing, this function must be called before {@link
     * CameraDevice#createCaptureSessionByOutputConfigurations}. Calling this function after {@link
     * CameraDevice#createCaptureSessionByOutputConfigurations} has no effect.</p>
     *
     * <p>Up to 2 surfaces can be shared for an OutputConfiguration. The supported surfaces for
     * sharing must be of type SurfaceTexture, SurfaceView, MediaRecorder, MediaCodec, or
     * implementation defined ImageReader.</p>
     */
    public void enableSurfaceSharing() {
        mIsShared = true;
    }

    /**
     * Check if this configuration has deferred configuration.
     *
     * <p>This will return true if the output configuration was constructed with surface deferred by
     * {@link OutputConfiguration#OutputConfiguration(Size, Class)}. It will return true even after
     * the deferred surface is added later by {@link OutputConfiguration#addSurface}.</p>
     *
     * @return true if this configuration has deferred surface.
     * @hide
     */
    public boolean isDeferredConfiguration() {
        return mIsDeferredConfig;
    }

    /**
     * Add a surface to this OutputConfiguration.
     *
     * <p> This function can be called before or after {@link
     * CameraDevice#createCaptureSessionByOutputConfigurations}. If it's called after,
     * the application must finalize the capture session with
     * {@link CameraCaptureSession#finalizeOutputConfigurations}.
     * </p>
     *
     * <p> If the OutputConfiguration was constructed with a deferred surface by {@link
     * OutputConfiguration#OutputConfiguration(Size, Class)}, the added surface must be obtained
     * from {@link android.view.SurfaceView} by calling {@link android.view.SurfaceHolder#getSurface},
     * or from {@link android.graphics.SurfaceTexture} via
     * {@link android.view.Surface#Surface(android.graphics.SurfaceTexture)}).</p>
     *
     * <p> If the OutputConfiguration was constructed by other constructors, the added
     * surface must be compatible with the existing surface. See {@link #enableSurfaceSharing} for
     * details of compatible surfaces.</p>
     *
     * <p> If the OutputConfiguration already contains a Surface, {@link #enableSurfaceSharing} must
     * be called before calling this function to add a new Surface.</p>
     *
     * @param surface The surface to be added.
     * @throws IllegalArgumentException if the Surface is invalid, the Surface's
     *         dataspace/format doesn't match, or adding the Surface would exceed number of
     *         shared surfaces supported.
     * @throws IllegalStateException if the Surface was already added to this OutputConfiguration,
     *         or if the OutputConfiguration is not shared and it already has a surface associated
     *         with it.
     */
    public void addSurface(@NonNull Surface surface) {
        checkNotNull(surface, "Surface must not be null");
        if (mSurfaces.contains(surface)) {
            throw new IllegalStateException("Surface is already added!");
        }
        if (mSurfaces.size() == 1 && !mIsShared) {
            throw new IllegalStateException("Cannot have 2 surfaces for a non-sharing configuration");
        }
        if (mSurfaces.size() + 1 > MAX_SURFACES_COUNT) {
            throw new IllegalArgumentException("Exceeds maximum number of surfaces");
        }

        // This will throw IAE is the surface was abandoned.
        Size surfaceSize = SurfaceUtils.getSurfaceSize(surface);
        if (!surfaceSize.equals(mConfiguredSize)) {
            Log.w(TAG, "Added surface size " + surfaceSize +
                    " is different than pre-configured size " + mConfiguredSize +
                    ", the pre-configured size will be used.");
        }

        if (mConfiguredFormat != SurfaceUtils.getSurfaceFormat(surface)) {
            throw new IllegalArgumentException("The format of added surface format doesn't match");
        }

        // If the surface format is PRIVATE, do not enforce dataSpace because camera device may
        // override it.
        if (mConfiguredFormat != ImageFormat.PRIVATE &&
                mConfiguredDataspace != SurfaceUtils.getSurfaceDataspace(surface)) {
            throw new IllegalArgumentException("The dataspace of added surface doesn't match");
        }

        mSurfaces.add(surface);
    }

    /**
     * Create a new {@link OutputConfiguration} instance with another {@link OutputConfiguration}
     * instance.
     *
     * @param other Another {@link OutputConfiguration} instance to be copied.
     *
     * @hide
     */
    public OutputConfiguration(@NonNull OutputConfiguration other) {
        if (other == null) {
            throw new IllegalArgumentException("OutputConfiguration shouldn't be null");
        }

        this.mSurfaces = other.mSurfaces;
        this.mRotation = other.mRotation;
        this.mSurfaceGroupId = other.mSurfaceGroupId;
        this.mSurfaceType = other.mSurfaceType;
        this.mConfiguredDataspace = other.mConfiguredDataspace;
        this.mConfiguredFormat = other.mConfiguredFormat;
        this.mConfiguredSize = other.mConfiguredSize;
        this.mConfiguredGenerationId = other.mConfiguredGenerationId;
        this.mIsDeferredConfig = other.mIsDeferredConfig;
        this.mIsShared = other.mIsShared;
    }

    /**
     * Create an OutputConfiguration from Parcel.
     */
    private OutputConfiguration(@NonNull Parcel source) {
        int rotation = source.readInt();
        int surfaceSetId = source.readInt();
        int surfaceType = source.readInt();
        int width = source.readInt();
        int height = source.readInt();
        boolean isDeferred = source.readInt() == 1;
        boolean isShared = source.readInt() == 1;
        ArrayList<Surface> surfaces = new ArrayList<Surface>();
        source.readTypedList(surfaces, Surface.CREATOR);

        checkArgumentInRange(rotation, ROTATION_0, ROTATION_270, "Rotation constant");

        mSurfaceGroupId = surfaceSetId;
        mRotation = rotation;
        mSurfaces = surfaces;
        mConfiguredSize = new Size(width, height);
        mIsDeferredConfig = isDeferred;
        mIsShared = isShared;
        mSurfaces = surfaces;
        if (mSurfaces.size() > 0) {
            mSurfaceType = SURFACE_TYPE_UNKNOWN;
            mConfiguredFormat = SurfaceUtils.getSurfaceFormat(mSurfaces.get(0));
            mConfiguredDataspace = SurfaceUtils.getSurfaceDataspace(mSurfaces.get(0));
            mConfiguredGenerationId = mSurfaces.get(0).getGenerationId();
        } else {
            mSurfaceType = surfaceType;
            mConfiguredFormat = StreamConfigurationMap.imageFormatToInternal(ImageFormat.PRIVATE);
            mConfiguredDataspace =
                    StreamConfigurationMap.imageFormatToDataspace(ImageFormat.PRIVATE);
            mConfiguredGenerationId = 0;
        }
    }

    /**
     * Get the {@link Surface} associated with this {@link OutputConfiguration}.
     *
     * If more than one surface is associated with this {@link OutputConfiguration}, return the
     * first one as specified in the constructor or {@link OutputConfiguration#addSurface}.
     */
    public @Nullable Surface getSurface() {
        if (mSurfaces.size() == 0) {
            return null;
        }

        return mSurfaces.get(0);
    }

    /**
     * Get the immutable list of surfaces associated with this {@link OutputConfiguration}.
     *
     * @return the list of surfaces associated with this {@link OutputConfiguration} as specified in
     * the constructor and {@link OutputConfiguration#addSurface}. The list should not be modified.
     */
    @NonNull
    public List<Surface> getSurfaces() {
        return Collections.unmodifiableList(mSurfaces);
    }

    /**
     * Get the rotation associated with this {@link OutputConfiguration}.
     *
     * @return the rotation associated with this {@link OutputConfiguration}.
     *         Value will be one of ROTATION_[0, 90, 180, 270]
     *
     * @hide
     */
    @SystemApi
    public int getRotation() {
        return mRotation;
    }

    /**
     * Get the surface group ID associated with this {@link OutputConfiguration}.
     *
     * @return the surface group ID associated with this {@link OutputConfiguration}.
     *         The default value is {@value #SURFACE_GROUP_ID_NONE}.
     */
    public int getSurfaceGroupId() {
        return mSurfaceGroupId;
    }

    public static final Parcelable.Creator<OutputConfiguration> CREATOR =
            new Parcelable.Creator<OutputConfiguration>() {
        @Override
        public OutputConfiguration createFromParcel(Parcel source) {
            return new OutputConfiguration(source);
        }

        @Override
        public OutputConfiguration[] newArray(int size) {
            return new OutputConfiguration[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null");
        }
        dest.writeInt(mRotation);
        dest.writeInt(mSurfaceGroupId);
        dest.writeInt(mSurfaceType);
        dest.writeInt(mConfiguredSize.getWidth());
        dest.writeInt(mConfiguredSize.getHeight());
        dest.writeInt(mIsDeferredConfig ? 1 : 0);
        dest.writeInt(mIsShared ? 1 : 0);
        dest.writeTypedList(mSurfaces);
    }

    /**
     * Check if this {@link OutputConfiguration} is equal to another {@link OutputConfiguration}.
     *
     * <p>Two output configurations are only equal if and only if the underlying surfaces, surface
     * properties (width, height, format, dataspace) when the output configurations are created,
     * and all other configuration parameters are equal. </p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        } else if (this == obj) {
            return true;
        } else if (obj instanceof OutputConfiguration) {
            final OutputConfiguration other = (OutputConfiguration) obj;
            if (mRotation != other.mRotation ||
                    !mConfiguredSize.equals(other.mConfiguredSize) ||
                    mConfiguredFormat != other.mConfiguredFormat ||
                    mSurfaceGroupId != other.mSurfaceGroupId ||
                    mSurfaceType != other.mSurfaceType ||
                    mIsDeferredConfig != other.mIsDeferredConfig ||
                    mIsShared != other.mIsShared ||
                    mConfiguredFormat != other.mConfiguredFormat ||
                    mConfiguredDataspace != other.mConfiguredDataspace ||
                    mConfiguredGenerationId != other.mConfiguredGenerationId)
                return false;

            int minLen = Math.min(mSurfaces.size(), other.mSurfaces.size());
            for (int i = 0;  i < minLen; i++) {
                if (mSurfaces.get(i) != other.mSurfaces.get(i))
                    return false;
            }

            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        // Need ensure that the hashcode remains unchanged after adding a deferred surface. Otherwise
        // the deferred output configuration will be lost in the camera streammap after the deferred
        // surface is set.
        if (mIsDeferredConfig) {
            return HashCodeHelpers.hashCode(
                    mRotation, mConfiguredSize.hashCode(), mConfiguredFormat, mConfiguredDataspace,
                    mSurfaceGroupId, mSurfaceType, mIsShared ? 1 : 0);
        }

        return HashCodeHelpers.hashCode(
                mRotation, mSurfaces.hashCode(), mConfiguredGenerationId,
                mConfiguredSize.hashCode(), mConfiguredFormat,
                mConfiguredDataspace, mSurfaceGroupId, mIsShared ? 1 : 0);
    }

    private static final String TAG = "OutputConfiguration";
    private ArrayList<Surface> mSurfaces;
    private final int mRotation;
    private final int mSurfaceGroupId;
    // Surface source type, this is only used by the deferred surface configuration objects.
    private final int mSurfaceType;

    // The size, format, and dataspace of the surface when OutputConfiguration is created.
    private final Size mConfiguredSize;
    private final int mConfiguredFormat;
    private final int mConfiguredDataspace;
    // Surface generation ID to distinguish changes to Surface native internals
    private final int mConfiguredGenerationId;
    // Flag indicating if this config has deferred surface.
    private final boolean mIsDeferredConfig;
    // Flag indicating if this config has shared surfaces
    private boolean mIsShared;
}
