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

import static com.android.internal.util.Preconditions.*;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.graphics.ColorSpace;
import android.graphics.ImageFormat;
import android.graphics.ImageFormat.Format;
import android.hardware.HardwareBuffer;
import android.hardware.HardwareBuffer.Usage;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.MultiResolutionImageReader;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.MultiResolutionStreamInfo;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.hardware.camera2.utils.SurfaceUtils;
import android.media.ImageReader;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.android.internal.camera.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A class for describing camera output, which contains a {@link Surface} and its specific
 * configuration for creating capture session.
 *
 * <p>There are several ways to instantiate, modify and use OutputConfigurations. The most common
 * and recommended usage patterns are summarized in the following list:</p>
 *<ul>
 * <li>Passing a {@link Surface} to the constructor and using the OutputConfiguration instance as
 * argument to {@link CameraDevice#createCaptureSessionByOutputConfigurations}. This is the most
 * frequent usage and clients should consider it first before other more complicated alternatives.
 * </li>
 *
 * <li>Passing only a surface source class as an argument to the constructor. This is usually
 * followed by a call to create a capture session
 * (see {@link CameraDevice#createCaptureSessionByOutputConfigurations} and a {@link Surface} add
 * call {@link #addSurface} with a valid {@link Surface}. The sequence completes with
 * {@link CameraCaptureSession#finalizeOutputConfigurations}. This is the deferred usage case which
 * aims to enhance performance by allowing the resource-intensive capture session create call to
 * execute in parallel with any {@link Surface} initialization, such as waiting for a
 * {@link android.view.SurfaceView} to be ready as part of the UI initialization.</li>
 *
 * <li>The third and most complex usage pattern involves surface sharing. Once instantiated an
 * OutputConfiguration can be enabled for surface sharing via {@link #enableSurfaceSharing}. This
 * must be done before creating a new capture session and enables calls to
 * {@link CameraCaptureSession#updateOutputConfiguration}. An OutputConfiguration with enabled
 * surface sharing can be modified via {@link #addSurface} or {@link #removeSurface}. The updates
 * to this OutputConfiguration will only come into effect after
 * {@link CameraCaptureSession#updateOutputConfiguration} returns without throwing exceptions.
 * Such updates can be done as long as the session is active. Clients should always consider the
 * additional requirements and limitations placed on the output surfaces (for more details see
 * {@link #enableSurfaceSharing}, {@link #addSurface}, {@link #removeSurface},
 * {@link CameraCaptureSession#updateOutputConfiguration}). A trade-off exists between additional
 * complexity and flexibility. If exercised correctly surface sharing can switch between different
 * output surfaces without interrupting any ongoing repeating capture requests. This saves time and
 * can significantly improve the user experience.</li>
 *
 * <li>Surface sharing can be used in combination with deferred surfaces. The rules from both cases
 * are combined and clients must call {@link #enableSurfaceSharing} before creating a capture
 * session. Attach and/or remove output surfaces via  {@link #addSurface}/{@link #removeSurface} and
 * finalize the configuration using {@link CameraCaptureSession#finalizeOutputConfigurations}.
 * {@link CameraCaptureSession#updateOutputConfiguration} can be called after the configuration
 * finalize method returns without exceptions.</li>
 *
 * <li>If the camera device supports multi-resolution output streams, {@link
 * CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP} will contain the
 * formats and their corresponding stream info. The application can use an OutputConfiguration
 * created with the multi-resolution stream info queried from {@link
 * MultiResolutionStreamConfigurationMap#getOutputInfo} and
 * {@link android.hardware.camera2.MultiResolutionImageReader} to capture variable size images.
 *
 * </ul>
 *
 * <p> As of {@link android.os.Build.VERSION_CODES#P Android P}, all formats except
 * {@link ImageFormat#JPEG} and {@link ImageFormat#RAW_PRIVATE} can be used for sharing, subject to
 * device support. On prior API levels, only {@link ImageFormat#PRIVATE} format may be used.</p>
 *
 * @see CameraDevice#createCaptureSessionByOutputConfigurations
 * @see CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP
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
     * Default timestamp base.
     *
     * <p>The camera device decides the timestamp based on the properties of the
     * output surface.</p>
     *
     * <li> For a SurfaceView output surface, the timestamp base is {@link
     * #TIMESTAMP_BASE_CHOREOGRAPHER_SYNCED}. The timestamp is overridden with choreographer
     * pulses from the display subsystem for smoother display of camera frames when the camera
     * device runs in fixed frame rate. The timestamp is roughly in the same time base as
     * {@link android.os.SystemClock#uptimeMillis}.</li>
     * <li> For an output surface of MediaRecorder, MediaCodec, or ImageReader with {@link
     * android.hardware.HardwareBuffer#USAGE_VIDEO_ENCODE} usage flag, the timestamp base is
     * {@link #TIMESTAMP_BASE_MONOTONIC}, which is roughly the same time base as
     * {@link android.os.SystemClock#uptimeMillis}.</li>
     * <li> For all other cases, the timestamp base is {@link #TIMESTAMP_BASE_SENSOR}, the same
     * as what's specified by {@link CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE}.
     * <ul><li> For a SurfaceTexture output surface, the camera system re-spaces the delivery
     * of output frames based on image readout intervals, reducing viewfinder jitter. The timestamps
     * of images remain to be {@link #TIMESTAMP_BASE_SENSOR}.</li></ul></li>
     *
     * <p>Note that the reduction of frame jitter for SurfaceView and SurfaceTexture comes with
     * slight increase in photon-to-photon latency, which is the time from when photons hit the
     * scene to when the corresponding pixels show up on the screen. If the photon-to-photon latency
     * is more important than the smoothness of viewfinder, {@link #TIMESTAMP_BASE_SENSOR} should be
     * used instead.</p>
     *
     * @see #TIMESTAMP_BASE_CHOREOGRAPHER_SYNCED
     * @see #TIMESTAMP_BASE_MONOTONIC
     * @see #TIMESTAMP_BASE_SENSOR
     */
    public static final int TIMESTAMP_BASE_DEFAULT = 0;

    /**
     * Timestamp base of {@link CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE}.
     *
     * <p>The timestamps of the output images are in the time base as specified by {@link
     * CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE}. The application can look up the
     * corresponding result metadata by matching the timestamp with a {@link
     * CameraCaptureSession.CaptureCallback#onCaptureStarted}, or with a {@link
     * CameraCaptureSession.CaptureCallback#onReadoutStarted} if readout timestamp is used.</p>
     */
    public static final int TIMESTAMP_BASE_SENSOR = 1;

    /**
     * Timestamp base roughly the same as {@link android.os.SystemClock#uptimeMillis}.
     *
     * <p>The timestamps of the output images are monotonically increasing, and are roughly in the
     * same time base as {@link android.os.SystemClock#uptimeMillis}. The timestamps with this
     * time base can be directly used for audio-video sync in video recording.</p>
     *
     * <p>If the camera device's {@link CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE} is
     * REALTIME, timestamps with this time base cannot directly match the timestamps in
     * {@link CameraCaptureSession.CaptureCallback#onCaptureStarted}, {@link
     * CameraCaptureSession.CaptureCallback#onReadoutStarted}, or the sensor timestamps in
     * {@link android.hardware.camera2.CaptureResult}.</p>
     */
    public static final int TIMESTAMP_BASE_MONOTONIC = 2;

    /**
     * Timestamp base roughly the same as {@link android.os.SystemClock#elapsedRealtime}.
     *
     * <p>The timestamps of the output images are roughly in the
     * same time base as {@link android.os.SystemClock#elapsedRealtime}. The timestamps with this
     * time base cannot be directly used for audio-video sync in video recording.</p>
     *
     * <p>If the camera device's {@link CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE} is
     * UNKNOWN, timestamps with this time base cannot directly match the timestamps in
     * {@link CameraCaptureSession.CaptureCallback#onCaptureStarted}, {@link
     * CameraCaptureSession.CaptureCallback#onReadoutStarted}, or the sensor timestamps in
     * {@link android.hardware.camera2.CaptureResult}.</p>
     *
     * <p>If using a REALTIME timestamp base on a device that supports only
     * TIMESTAMP_SOURCE_UNKNOWN, the accuracy of timestamps is only what is guaranteed in the
     * documentation for UNKNOWN. In particular, they have no guarantees about being accurate
     * enough to use in fusing image data with the output of inertial sensors, for features such as
     * image stabilization or augmented reality.</p>
     */
    public static final int TIMESTAMP_BASE_REALTIME = 3;

    /**
     * Timestamp is synchronized to choreographer.
     *
     * <p>The timestamp of the output images are overridden with choreographer pulses from the
     * display subsystem for smoother display of camera frames. An output target of SurfaceView
     * uses this time base by default. Note that the timestamp override is done for fixed camera
     * frame rate only.</p>
     *
     * <p>This timestamp base isn't applicable to SurfaceTexture targets. SurfaceTexture's
     * {@link android.graphics.SurfaceTexture#updateTexImage updateTexImage} function always
     * uses the latest image from the camera stream. In the case of a TextureView, the image is
     * displayed right away.</p>
     *
     * <p>Timestamps with this time base cannot directly match the timestamps in
     * {@link CameraCaptureSession.CaptureCallback#onCaptureStarted}, {@link
     * CameraCaptureSession.CaptureCallback#onReadoutStarted}, or the sensor timestamps in
     * {@link android.hardware.camera2.CaptureResult}. This timestamp base shouldn't be used if the
     * timestamp needs to be used for audio-video synchronization.</p>
     */
    public static final int TIMESTAMP_BASE_CHOREOGRAPHER_SYNCED = 4;

    /**
     * Timestamp is the start of readout in the same time domain as TIMESTAMP_BASE_SENSOR.
     *
     * <p>NOTE: do not use! Use setReadoutTimestampEnabled instead.</p>
     *
     * @hide
     */
    public static final int TIMESTAMP_BASE_READOUT_SENSOR = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"TIMESTAMP_BASE_"}, value =
        {TIMESTAMP_BASE_DEFAULT,
         TIMESTAMP_BASE_SENSOR,
         TIMESTAMP_BASE_MONOTONIC,
         TIMESTAMP_BASE_REALTIME,
         TIMESTAMP_BASE_CHOREOGRAPHER_SYNCED,
         TIMESTAMP_BASE_READOUT_SENSOR})
    public @interface TimestampBase {};

    /** @hide */
     @Retention(RetentionPolicy.SOURCE)
     @IntDef(prefix = {"SENSOR_PIXEL_MODE_"}, value =
         {CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT,
          CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION})
     public @interface SensorPixelMode {};

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STREAM_USE_CASE_"}, value =
        {CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT,
         CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW,
         CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE,
         CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD,
         CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL,
         CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL,
         CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW})
    public @interface StreamUseCase {};

    /**
     * Automatic mirroring based on camera facing
     *
     * <p>This is the default mirroring mode for the camera device. With this mode,
     * the camera output is mirrored horizontally for front-facing cameras. There is
     * no mirroring for rear-facing and external cameras.</p>
     */
    public static final int MIRROR_MODE_AUTO = 0;

    /**
     * No mirror transform is applied
     *
     * <p>No mirroring is applied to the camera output regardless of the camera facing.</p>
     */
    public static final int MIRROR_MODE_NONE = 1;

    /**
     * Camera output is mirrored horizontally
     *
     * <p>The camera output is mirrored horizontally, the same behavior as in AUTO mode for
     * front facing camera.</p>
     */
    public static final int MIRROR_MODE_H = 2;

    /**
     * Camera output is mirrored vertically
     */
    public static final int MIRROR_MODE_V = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"MIRROR_MODE_"}, value =
        {MIRROR_MODE_AUTO,
          MIRROR_MODE_NONE,
          MIRROR_MODE_H,
          MIRROR_MODE_V})
    public @interface MirrorMode {};

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
     * The surface is obtained from {@link android.media.MediaRecorder}.
     */
    private static final int SURFACE_TYPE_MEDIA_RECORDER = 2;

    /**
     * The surface is obtained from {@link android.media.MediaCodec}.
     */
    private static final int SURFACE_TYPE_MEDIA_CODEC = 3;

    /**
     * The surface is obtained from {@link android.media.ImageReader}.
     */
    private static final int SURFACE_TYPE_IMAGE_READER = 4;

    /**
     * Maximum number of surfaces supported by one {@link OutputConfiguration}.
     *
     * <p>The combined number of surfaces added by the constructor and
     * {@link OutputConfiguration#addSurface} should not exceed this value.</p>
     *
     */
    private static final int MAX_SURFACES_COUNT = 4;

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
     * Set the multi-resolution output flag.
     *
     * <p>Specify that this OutputConfiguration is part of a multi-resolution output stream group
     * used by {@link android.hardware.camera2.MultiResolutionImageReader}.</p>
     *
     * <p>This function must only be called for an OutputConfiguration with a non-negative
     * group ID. And all OutputConfigurations of a MultiResolutionImageReader will have the same
     * group ID and have this flag set.</p>
     *
     * @throws IllegalStateException If surface sharing is enabled via {@link #enableSurfaceSharing}
     *         call, or no non-negative group ID has been set.
     * @hide
     */
    public void setMultiResolutionOutput() {
        if (mIsShared) {
            throw new IllegalStateException("Multi-resolution output flag must not be set for " +
                    "configuration with surface sharing");
        }
        if (mSurfaceGroupId == SURFACE_GROUP_ID_NONE) {
            throw new IllegalStateException("Multi-resolution output flag should only be set for " +
                    "surface with non-negative group ID");
        }

        mIsMultiResolution = true;
    }

    /**
     * Set a specific device supported dynamic range profile.
     *
     * <p>Clients can choose from any profile advertised as supported in
     * CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
     * queried using {@link DynamicRangeProfiles#getSupportedProfiles()}.
     * If this is not explicitly set, then the default profile will be
     * {@link DynamicRangeProfiles#STANDARD}.</p>
     *
     * <p>Do note that invalid combinations between the registered output
     * surface pixel format and the configured dynamic range profile will
     * cause capture session initialization failure. Invalid combinations
     * include any 10-bit dynamic range profile advertised in
     * {@link DynamicRangeProfiles#getSupportedProfiles()} combined with
     * an output Surface pixel format different from {@link ImageFormat#PRIVATE}
     * (the default for Surfaces initialized by {@link android.view.SurfaceView},
     * {@link android.view.TextureView}, {@link android.media.MediaRecorder},
     * {@link android.media.MediaCodec} etc.)
     * or {@link ImageFormat#YCBCR_P010}.</p>
     */
    public void setDynamicRangeProfile(@DynamicRangeProfiles.Profile long profile) {
        mDynamicRangeProfile = profile;
    }

    /**
     * Return current dynamic range profile.
     *
     * @return the currently set dynamic range profile
     */
    public @DynamicRangeProfiles.Profile long getDynamicRangeProfile() {
        return mDynamicRangeProfile;
    }

    /**
     * Set a specific device-supported color space.
     *
     * <p>Clients can choose from any profile advertised as supported in
     * {@link CameraCharacteristics#REQUEST_AVAILABLE_COLOR_SPACE_PROFILES}
     * queried using {@link ColorSpaceProfiles#getSupportedColorSpaces}.
     * When set, the colorSpace will override the default color spaces of the output targets,
     * or the color space implied by the dataSpace passed into an {@link ImageReader}'s
     * constructor.</p>
     *
     * @hide
     */
    @TestApi
    public void setColorSpace(@NonNull ColorSpace.Named colorSpace) {
        mColorSpace = colorSpace.ordinal();
    }

    /**
     * Clear the color space, such that the default color space will be used.
     *
     * @hide
     */
    @TestApi
    public void clearColorSpace() {
        mColorSpace = ColorSpaceProfiles.UNSPECIFIED;
    }

    /**
     * Return the current color space.
     *
     * @return the currently set color space
     * @hide
     */
    @TestApi
    @SuppressLint("MethodNameUnits")
    public @Nullable ColorSpace getColorSpace() {
        if (mColorSpace != ColorSpaceProfiles.UNSPECIFIED) {
            return ColorSpace.get(ColorSpace.Named.values()[mColorSpace]);
        } else {
            return null;
        }
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
        mPhysicalCameraId = null;
        mIsMultiResolution = false;
        mSensorPixelModesUsed = new ArrayList<Integer>();
        mDynamicRangeProfile = DynamicRangeProfiles.STANDARD;
        mColorSpace = ColorSpaceProfiles.UNSPECIFIED;
        mStreamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT;
        mTimestampBase = TIMESTAMP_BASE_DEFAULT;
        mMirrorMode = MIRROR_MODE_AUTO;
        mReadoutTimestampEnabled = false;
        mIsReadoutSensorTimestampBase = false;
        mUsage = 0;
    }

    /**
     * Create a list of {@link OutputConfiguration} instances for the outputs used by a
     * {@link android.hardware.camera2.MultiResolutionImageReader}.
     *
     * <p>This constructor takes an argument for a
     * {@link android.hardware.camera2.MultiResolutionImageReader}.</p>
     *
     * @param multiResolutionImageReader
     *          The multi-resolution image reader object.
     */
    public static @NonNull Collection<OutputConfiguration> createInstancesForMultiResolutionOutput(
            @NonNull MultiResolutionImageReader multiResolutionImageReader)  {
        checkNotNull(multiResolutionImageReader, "Multi-resolution image reader must not be null");

        int groupId = getAndIncreaseMultiResolutionGroupId();
        ImageReader[] imageReaders = multiResolutionImageReader.getReaders();
        ArrayList<OutputConfiguration> configs = new ArrayList<OutputConfiguration>();
        for (int i = 0; i < imageReaders.length; i++) {
            MultiResolutionStreamInfo streamInfo =
                    multiResolutionImageReader.getStreamInfoForImageReader(imageReaders[i]);

            OutputConfiguration config = new OutputConfiguration(
                    groupId, imageReaders[i].getSurface());
            config.setPhysicalCameraId(streamInfo.getPhysicalCameraId());
            config.setMultiResolutionOutput();
            configs.add(config);

            // No need to call addSensorPixelModeUsed for ultra high resolution sensor camera,
            // because regular and max resolution output configurations are used for DEFAULT mode
            // and MAX_RESOLUTION mode respectively by default.
        }

        return configs;
    }

    /**
     * Create a list of {@link OutputConfiguration} instances for a
     * {@link android.hardware.camera2.params.MultiResolutionImageReader}.
     *
     * <p>This method can be used to create query OutputConfigurations for a
     * MultiResolutionImageReader that can be included in a SessionConfiguration passed into
     * {@link CameraDeviceSetup#isSessionConfigurationSupported} before opening and setting up
     * a camera device in full, at which point {@link #setSurfacesForMultiResolutionOutput}
     * can be used to link to the actual MultiResolutionImageReader.</p>
     *
     * <p>This constructor takes same arguments used to create a {@link
     * MultiResolutionImageReader}: a collection of {@link MultiResolutionStreamInfo}
     * objects and the format.</p>
     *
     * @param streams The group of multi-resolution stream info objects, which are used to create a
     *                multi-resolution image reader containing a number of ImageReaders.
     * @param format The format of the MultiResolutionImageReader. This must be one of the {@link
     *               android.graphics.ImageFormat} or {@link android.graphics.PixelFormat} constants
     *               supported by the camera device. Note that not all formats are supported, like
     *               {@link ImageFormat.NV21}. The supported multi-resolution reader format can be
     *               queried by {@link MultiResolutionStreamConfigurationMap#getOutputFormats}.
     *
     * @return The list of {@link OutputConfiguration} objects for a MultiResolutionImageReader.
     *
     * @throws IllegaArgumentException If the {@code streams} is null or doesn't contain
     *                                 at least 2 items, or if {@code format} isn't a valid camera
     *                                 format.
     *
     * @see MultiResolutionImageReader
     * @see MultiResolutionStreamInfo
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public static @NonNull List<OutputConfiguration> createInstancesForMultiResolutionOutput(
            @NonNull Collection<MultiResolutionStreamInfo> streams,
            @Format int format)  {
        if (streams == null || streams.size() <= 1) {
            throw new IllegalArgumentException(
                "The streams list must contain at least 2 entries");
        }
        if (format == ImageFormat.NV21) {
            throw new IllegalArgumentException(
                    "NV21 format is not supported");
        }

        int groupId = getAndIncreaseMultiResolutionGroupId();
        ArrayList<OutputConfiguration> configs = new ArrayList<OutputConfiguration>();
        for (MultiResolutionStreamInfo stream : streams) {
            Size surfaceSize = new Size(stream.getWidth(), stream.getHeight());
            OutputConfiguration config = new OutputConfiguration(
                    groupId, format, surfaceSize);
            config.setPhysicalCameraId(stream.getPhysicalCameraId());
            config.setMultiResolutionOutput();
            configs.add(config);

            // No need to call addSensorPixelModeUsed for ultra high resolution sensor camera,
            // because regular and max resolution output configurations are used for DEFAULT mode
            // and MAX_RESOLUTION mode respectively by default.
        }

        return configs;
    }

    /**
     * Set the OutputConfiguration surfaces corresponding to the {@link MultiResolutionImageReader}.
     *
     * <p>This function should be used together with {@link
     * #createInstancesForMultiResolutionOutput}. The application calls {@link
     * #createInstancesForMultiResolutionOutput} first to create a list of
     * OutputConfiguration objects without the actual MultiResolutionImageReader.
     * Once the MultiResolutionImageReader is created later during full camera setup, the
     * application then calls this function to assign the surfaces to the OutputConfiguration
     * instances.</p>
     *
     * @param outputConfigurations The OutputConfiguration objects created by {@link
     *                             #createInstancesFromMultiResolutionOutput}
     * @param multiResolutionImageReader The MultiResolutionImageReader object created from the same
     *                                   MultiResolutionStreamInfo parameters as
     *                                   {@code outputConfigurations}.
     * @throws IllegalArgumentException If {@code outputConfigurations} or {@code
     *                                  multiResolutionImageReader} is {@code null}, the {@code
     *                                  outputConfigurations} and {@code multiResolutionImageReader}
     *                                  sizes don't match, or if the
     *                                  {@code multiResolutionImageReader}'s surfaces don't match
     *                                  with the {@code outputConfigurations}.
     * @throws IllegalStateException If {@code outputConfigurations} already contains valid output
     *                               surfaces.
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public static void setSurfacesForMultiResolutionOutput(
            @NonNull Collection<OutputConfiguration> outputConfigurations,
            @NonNull MultiResolutionImageReader multiResolutionImageReader) {
        checkNotNull(outputConfigurations, "outputConfigurations must not be null");
        checkNotNull(multiResolutionImageReader, "multiResolutionImageReader must not be null");
        if (outputConfigurations.size() != multiResolutionImageReader.getReaders().length) {
            throw new IllegalArgumentException(
                    "outputConfigurations and multiResolutionImageReader sizes must match");
        }

        for (OutputConfiguration config : outputConfigurations) {
            String physicalCameraId = config.getPhysicalCameraId();
            if (physicalCameraId == null) {
                physicalCameraId = "";
            }
            Surface surface = multiResolutionImageReader.getSurface(config.getConfiguredSize(),
                    physicalCameraId);
            config.addSurface(surface);
        }
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
     *
     * <p>Starting from {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM Android V},
     * the deferred Surface can be obtained: (1) from {@link android.view.SurfaceView}
     * by calling {@link android.view.SurfaceHolder#getSurface}, (2) from
     * {@link android.graphics.SurfaceTexture} via
     * {@link android.view.Surface#Surface(android.graphics.SurfaceTexture)}, (3) from {@link
     * android.media.MediaRecorder} via {@link android.media.MediaRecorder.getSurface} or {@link
     * android.media.MediaCodec#createPersistentInputSurface}, or (4) from {@link
     * android.media.MediaCodce} via {@link android.media.MediaCodec#createInputSurface} or {@link
     * android.media.MediaCodec#createPersistentInputSource}.</p>
     *
     * <ul>
     * <li>Surfaces for {@link android.view.SurfaceView} and {@link android.graphics.SurfaceTexture}
     * can be deferred until after {@link CameraDevice#createCaptureSession}. In that case, the
     * output Surface must be set via {@link #addSurface}, and the Surface configuration must be
     * finalized via {@link CameraCaptureSession#finalizeOutputConfiguration} before submitting
     * a request with the Surface target.</li>
     * <li>For all other target types, the output Surface must be set by {@link #addSurface},
     * and {@link CameraCaptureSession#finalizeOutputConfiguration} is not needed because the
     * OutputConfiguration used to create the session will contain the actual Surface.</li>
     * </ul>
     *
     * <p>Before {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM Android V}, only {@link
     * android.view.SurfaceView} and {@link android.graphics.SurfaceTexture} are supported. Both
     * kind of outputs can be deferred until after {@link
     * CameraDevice#createCaptureSessionByOutputConfiguration}.</p>
     *
     * <p>An OutputConfiguration object created by this constructor can be used for {@link
     * CameraDeviceSetup.isSessionConfigurationSupported} and {@link
     * CameraDeviceSetup.getSessionCharacteristics} without having called {@link #addSurface}.</p>
     *
     * @param surfaceSize Size for the deferred surface.
     * @param klass a non-{@code null} {@link Class} object reference that indicates the source of
     *            this surface. Only {@link android.view.SurfaceHolder SurfaceHolder.class},
     *            {@link android.graphics.SurfaceTexture SurfaceTexture.class}, {@link
     *            android.media.MediaRecorder MediaRecorder.class}, and
     *            {@link android.media.MediaCodec MediaCodec.class} are supported.
     *            Before {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM Android V}, only
     *            {@link android.view.SurfaceHolder SurfaceHolder.class} and {@link
     *            android.graphics.SurfaceTexture SurfaceTexture.class} are supported.
     * @throws IllegalArgumentException if the Surface source class is not supported, or Surface
     *         size is zero.
     */
    public <T> OutputConfiguration(@NonNull Size surfaceSize, @NonNull Class<T> klass) {
        checkNotNull(surfaceSize, "surfaceSize must not be null");
        checkNotNull(klass, "klass must not be null");
        if (klass == android.view.SurfaceHolder.class) {
            mSurfaceType = SURFACE_TYPE_SURFACE_VIEW;
            mIsDeferredConfig = true;
        } else if (klass == android.graphics.SurfaceTexture.class) {
            mSurfaceType = SURFACE_TYPE_SURFACE_TEXTURE;
            mIsDeferredConfig = true;
        } else if (klass == android.media.MediaRecorder.class) {
            mSurfaceType = SURFACE_TYPE_MEDIA_RECORDER;
            mIsDeferredConfig = false;
        } else if (klass == android.media.MediaCodec.class) {
            mSurfaceType = SURFACE_TYPE_MEDIA_CODEC;
            mIsDeferredConfig = false;
        } else {
            mSurfaceType = SURFACE_TYPE_UNKNOWN;
            throw new IllegalArgumentException("Unknown surface source class type");
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
        mIsShared = false;
        mPhysicalCameraId = null;
        mIsMultiResolution = false;
        mSensorPixelModesUsed = new ArrayList<Integer>();
        mDynamicRangeProfile = DynamicRangeProfiles.STANDARD;
        mColorSpace = ColorSpaceProfiles.UNSPECIFIED;
        mStreamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT;
        mReadoutTimestampEnabled = false;
        mIsReadoutSensorTimestampBase = false;
        mUsage = 0;
    }

    /**
     * Create a new {@link OutputConfiguration} instance for an {@link ImageReader} for a given
     * format and size.
     *
     * <p>This constructor creates an OutputConfiguration for an ImageReader without providing
     * the actual output Surface. The actual output Surface must be set via {@link #addSurface}
     * before creating the capture session.</p>
     *
     * <p>An OutputConfiguration object created by this constructor can be used for {@link
     * CameraDeviceSetup.isSessionConfigurationSupported} and {@link
     * CameraDeviceSetup.getSessionCharacteristics} without having called {@link #addSurface}.</p>
     *
     * @param format The format of the ImageReader output. This must be one of the
     *               {@link android.graphics.ImageFormat} or {@link android.graphics.PixelFormat}
     *               constants. Note that not all formats are supported by the camera device.
     * @param surfaceSize Size for the ImageReader surface.
     * @throws IllegalArgumentException if the Surface size is null or zero.
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public OutputConfiguration(@Format int format, @NonNull Size surfaceSize) {
        this(format, surfaceSize,
                format == ImageFormat.PRIVATE ? 0 : HardwareBuffer.USAGE_CPU_READ_OFTEN);
    }

    /**
     * Create a new {@link OutputConfiguration} instance for an {@link ImageReader} for a given
     * surfaceGroupId, format, and size.
     *
     * <p>This constructor creates an OutputConfiguration for an ImageReader without providing
     * the actual output Surface. The actual output Surface must be set via {@link #addSurface}
     * before creating the capture session.</p>
     *
     * <p>An OutputConfiguration object created by this constructor can be used for {@link
     * CameraDeviceSetup.isSessionConfigurationSupported} and {@link
     * CameraDeviceSetup.getSessionCharacteristics} without having called {@link #addSurface}.</p>
     *
     * @param surfaceGroupId A group ID for this output, used for sharing memory between multiple
     *                       outputs.
     * @param format The format of the ImageReader output. This must be one of the
     *               {@link android.graphics.ImageFormat} or {@link android.graphics.PixelFormat}
     *               constants. Note that not all formats are supported by the camera device.
     * @param surfaceSize Size for the ImageReader surface.
     * @throws IllegalArgumentException if the Surface size is null or zero.
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public OutputConfiguration(int surfaceGroupId, @Format int format, @NonNull Size surfaceSize) {
        this(surfaceGroupId, format, surfaceSize,
                format == ImageFormat.PRIVATE ? 0 : HardwareBuffer.USAGE_CPU_READ_OFTEN);
    }

    /**
     * Create a new {@link OutputConfiguration} instance for an {@link ImageReader} for a given
     * format, size, and usage flags.
     *
     * <p>This constructor creates an OutputConfiguration for an ImageReader without providing
     * the actual output Surface. The actual output Surface must be set via {@link #addSurface}
     * before creating the capture session.</p>
     *
     * <p>An OutputConfiguration object created by this constructor can be used for {@link
     * CameraDeviceSetup.isSessionConfigurationSupported} and {@link
     * CameraDeviceSetup.getSessionCharacteristics} without having called {@link #addSurface}.</p>
     *
     * @param format The format of the ImageReader output. This must be one of the
     *               {@link android.graphics.ImageFormat} or {@link android.graphics.PixelFormat}
     *               constants. Note that not all formats are supported by the camera device.
     * @param surfaceSize Size for the ImageReader surface.
     * @param usage The usage flags of the ImageReader output surface.
     * @throws IllegalArgumentException if the Surface size is null or zero.
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public OutputConfiguration(@Format int format, @NonNull Size surfaceSize, @Usage long usage) {
        this(SURFACE_GROUP_ID_NONE, format, surfaceSize, usage);
    }

    /**
     * Create a new {@link OutputConfiguration} instance for an {@link ImageReader} for a given
     * surface group id, format, size, and usage flags.
     *
     * <p>This constructor creates an OutputConfiguration for an ImageReader without providing
     * the actual output Surface. The actual output Surface must be set via {@link #addSurface}
     * before creating the capture session.</p>
     *
     * <p>An OutputConfiguration object created by this constructor can be used for {@link
     * CameraDeviceSetup.isSessionConfigurationSupported} and {@link
     * CameraDeviceSetup.getSessionCharacteristics} without having called {@link #addSurface}.</p>
     *
     * @param surfaceGroupId A group ID for this output, used for sharing memory between multiple
     *                       outputs.
     * @param format The format of the ImageReader output. This must be one of the
     *               {@link android.graphics.ImageFormat} or {@link android.graphics.PixelFormat}
     *               constants. Note that not all formats are supported by the camera device.
     * @param surfaceSize Size for the ImageReader surface.
     * @param usage The usage flags of the ImageReader output surface.
     * @throws IllegalArgumentException if the Surface size is null or zero.
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public OutputConfiguration(int surfaceGroupId, @Format int format,
            @NonNull Size surfaceSize, @Usage long usage) {
        checkNotNull(surfaceSize, "surfaceSize must not be null");
        if (surfaceSize.getWidth() == 0 || surfaceSize.getHeight() == 0) {
            throw new IllegalArgumentException("Surface size needs to be non-zero");
        }

        mSurfaceType = SURFACE_TYPE_IMAGE_READER;
        mSurfaceGroupId = surfaceGroupId;
        mSurfaces = new ArrayList<Surface>();
        mRotation = ROTATION_0;
        mConfiguredSize = surfaceSize;
        mConfiguredFormat = StreamConfigurationMap.imageFormatToInternal(format);
        mConfiguredDataspace = StreamConfigurationMap.imageFormatToDataspace(format);
        mConfiguredGenerationId = 0;
        mIsDeferredConfig = false;
        mIsShared = false;
        mPhysicalCameraId = null;
        mIsMultiResolution = false;
        mSensorPixelModesUsed = new ArrayList<Integer>();
        mDynamicRangeProfile = DynamicRangeProfiles.STANDARD;
        mColorSpace = ColorSpaceProfiles.UNSPECIFIED;
        mStreamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT;
        mReadoutTimestampEnabled = false;
        mIsReadoutSensorTimestampBase = false;
        mUsage = usage;
    }

    /**
     * Enable multiple surfaces sharing the same OutputConfiguration
     *
     * <p>For advanced use cases, a camera application may require more streams than the combination
     * guaranteed by {@link CameraDevice#createCaptureSession}. In this case, more than one
     * compatible surface can be attached to an OutputConfiguration so that they map to one
     * camera stream, and the outputs share memory buffers when possible. Due to buffer sharing
     * clients should be careful when adding surface outputs that modify their input data. If such
     * case exists, camera clients should have an additional mechanism to synchronize read and write
     * access between individual consumers.</p>
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
     * CameraDevice#createCaptureSessionByOutputConfigurations} or {@link
     * CameraDevice#createReprocessableCaptureSessionByConfigurations}. Calling this function after
     * {@link CameraDevice#createCaptureSessionByOutputConfigurations} has no effect.</p>
     *
     * <p>Up to {@link #getMaxSharedSurfaceCount} surfaces can be shared for an OutputConfiguration.
     * The supported surfaces for sharing must be of type SurfaceTexture, SurfaceView,
     * MediaRecorder, MediaCodec, or implementation defined ImageReader.</p>
     *
     * <p>This function must not be called from OutputConfigurations created by {@link
     * #createInstancesForMultiResolutionOutput}.</p>
     *
     * @throws IllegalStateException If this OutputConfiguration is created via {@link
     * #createInstancesForMultiResolutionOutput} to back a MultiResolutionImageReader.
     */
    public void enableSurfaceSharing() {
        if (mIsMultiResolution) {
            throw new IllegalStateException("Cannot enable surface sharing on "
                    + "multi-resolution output configurations");
        }
        mIsShared = true;
    }

    /**
     * Set the id of the physical camera for this OutputConfiguration
     *
     * <p>In the case one logical camera is made up of multiple physical cameras, it could be
     * desirable for the camera application to request streams from individual physical cameras.
     * This call achieves it by mapping the OutputConfiguration to the physical camera id.</p>
     *
     * <p>The valid physical camera ids can be queried by {@link
     * CameraCharacteristics#getPhysicalCameraIds}.</p>
     *
     * <p>Passing in a null physicalCameraId means that the OutputConfiguration is for a logical
     * stream.</p>
     *
     * <p>This function must be called before {@link
     * CameraDevice#createCaptureSessionByOutputConfigurations} or {@link
     * CameraDevice#createReprocessableCaptureSessionByConfigurations}. Calling this function
     * after {@link CameraDevice#createCaptureSessionByOutputConfigurations} or {@link
     * CameraDevice#createReprocessableCaptureSessionByConfigurations} has no effect.</p>
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#S Android 12}, an image buffer from a
     * physical camera stream can be used for reprocessing to logical camera streams and streams
     * from the same physical camera if the camera device supports multi-resolution input and output
     * streams. See {@link CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP}
     * for details. The behaviors of reprocessing from a non-physical camera stream to a physical
     * camera stream, and from a physical camera stream to a physical camera stream of different
     * physical camera, are device-specific and not guaranteed to be supported.</p>
     *
     * <p>On prior API levels, the surface belonging to a physical camera OutputConfiguration must
     * not be used as input or output of a reprocessing request. </p>
     */
    public void setPhysicalCameraId(@Nullable String physicalCameraId) {
        mPhysicalCameraId = physicalCameraId;
    }

    /**
     * Add a sensor pixel mode that this OutputConfiguration will be used in.
     *
     * <p> In the case that this output stream configuration (format, width, height) is
     * available through {@link android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP}
     * configurations and
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION},
     * configurations, the camera sub-system will assume that this {@link OutputConfiguration} will
     * be used only with {@link android.hardware.camera2.CaptureRequest}s which has
     * {@link android.hardware.camera2.CaptureRequest#SENSOR_PIXEL_MODE} set to
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_DEFAULT}.
     * In such cases, if clients intend to use the
     * {@link OutputConfiguration}(s) in a {@link android.hardware.camera2.CaptureRequest} with
     * other sensor pixel modes, they must specify which
     * {@link android.hardware.camera2.CaptureRequest#SENSOR_PIXEL_MODE}(s) they will use this
     * {@link OutputConfiguration} with, by calling this method.
     *
     * In case this output stream configuration (format, width, height) is only in
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION},
     * configurations, this output target must only be used with
     * {@link android.hardware.camera2.CaptureRequest}s which has
     * {@link android.hardware.camera2.CaptureRequest#SENSOR_PIXEL_MODE} set to
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION} and that
     * is what the camera sub-system will assume. If clients add
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_DEFAULT} in this
     * case, session configuration will fail, if this {@link OutputConfiguration} is included.
     *
     * In case this output stream configuration (format, width, height) is only in
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP},
     * configurations, this output target must only be used with
     * {@link android.hardware.camera2.CaptureRequest}s which has
     * {@link android.hardware.camera2.CaptureRequest#SENSOR_PIXEL_MODE} set to
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_DEFAULT} and that is what
     * the camera sub-system will assume. If clients add
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION} in this
     * case, session configuration will fail, if this {@link OutputConfiguration} is included.
     *
     * @param sensorPixelModeUsed The sensor pixel mode this OutputConfiguration will be used with
     * </p>
     *
     */
    public void addSensorPixelModeUsed(@SensorPixelMode int sensorPixelModeUsed) {
        // Verify that the values are in range.
        if (sensorPixelModeUsed != CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT &&
                sensorPixelModeUsed != CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) {
            throw new IllegalArgumentException("Not a valid sensor pixel mode " +
                    sensorPixelModeUsed);
        }

        if (mSensorPixelModesUsed.contains(sensorPixelModeUsed)) {
            // Already added, ignore;
            return;
        }
        mSensorPixelModesUsed.add(sensorPixelModeUsed);
    }

    /**
     * Remove a sensor pixel mode, previously added through addSensorPixelModeUsed, from this
     * OutputConfiguration.
     *
     * <p> Sensor pixel modes added via calls to {@link #addSensorPixelModeUsed} can also be removed
     * from the OutputConfiguration.</p>
     *
     * @param sensorPixelModeUsed The sensor pixel mode to be removed.
     *
     * @throws IllegalArgumentException If the sensor pixel mode wasn't previously added
     *                                  through {@link #addSensorPixelModeUsed}.
     */
    public void removeSensorPixelModeUsed(@SensorPixelMode int sensorPixelModeUsed) {
      if (!mSensorPixelModesUsed.remove(Integer.valueOf(sensorPixelModeUsed))) {
            throw new IllegalArgumentException("sensorPixelMode " + sensorPixelModeUsed +
                    "is not part of this output configuration");
      }
    }

    /**
     * Check if this configuration is for a physical camera.
     *
     * <p>This returns true if the output configuration was for a physical camera making up a
     * logical multi camera via {@link OutputConfiguration#setPhysicalCameraId}.</p>
     * @hide
     */
    public boolean isForPhysicalCamera() {
        return (mPhysicalCameraId != null);
    }

    /**
     * Check if this configuration has deferred configuration.
     *
     * <p>This will return true if the output configuration was constructed with {@link
     * android.view.SurfaceView} or {@link android.graphics.SurfaceTexture} deferred by
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
     * {@link CameraCaptureSession#finalizeOutputConfigurations}. It is possible to call this method
     * after the output configurations have been finalized only in cases of enabled surface sharing
     * see {@link #enableSurfaceSharing}. The modified output configuration must be updated with
     * {@link CameraCaptureSession#updateOutputConfiguration}. If this function is called before
     * session creation, {@link CameraCaptureSession#finalizeOutputConfigurations} doesn't need to
     * be called.</p>
     *
     * <p> If the OutputConfiguration was constructed by {@link
     * OutputConfiguration#OutputConfiguration(Size, Class)}, the added surface must be obtained:
     * <ul>
     * <li>from {@link android.view.SurfaceView} by calling
     * {@link android.view.SurfaceHolder#getSurface}</li>
     * <li>from {@link android.graphics.SurfaceTexture} by calling
     * {@link android.view.Surface#Surface(android.graphics.SurfaceTexture)}</li>
     * <li>from {@link android.media.MediaRecorder} by calling
     * {@link android.media.MediaRecorder#getSurface} or {@link
     * android.media.MediaCodec#createPersistentInputSurface}</li>
     * <li>from {@link android.media.MediaCodce} by calling
     * {@link android.media.MediaCodec#createInputSurface} or {@link
     * android.media.MediaCodec#createPersistentInputSource}</li>
     * </ul>
     *
     * <p> If the OutputConfiguration was constructed by {@link #OutputConfiguration(int, Size)}
     * or its variants, the added surface must be obtained from {@link android.media.ImageReader}
     * by calling {@link android.media.ImageReader#getSurface}.</p>
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
     * Remove a surface from this OutputConfiguration.
     *
     * <p> Surfaces added via calls to {@link #addSurface} can also be removed from the
     *  OutputConfiguration. The only notable exception is the surface associated with
     *  the OutputConfiguration (see {@link #getSurface}) which was passed as part of the
     *  constructor or was added first in the case of
     *  {@link OutputConfiguration#OutputConfiguration(Size, Class)}, {@link
     *  OutputConfiguration#OutputConfiguration(int, Size)}, {@link
     *  OutputConfiguration#OutputConfiguration(int, Size, long)}, {@link
     *  OutputConfiguration#OutputConfiguration(int, int, Size)}, {@link
     *  OutputConfiguration#OutputConfiguration(int, int, Size, long)}.</p>
     *
     * @param surface The surface to be removed.
     *
     * @throws IllegalArgumentException If the surface is associated with this OutputConfiguration
     *                                  (see {@link #getSurface}) or the surface didn't get added
     *                                  with {@link #addSurface}.
     */
    public void removeSurface(@NonNull Surface surface) {
        checkNotNull(surface, "Surface must not be null");
        if (getSurface() == surface) {
            throw new IllegalArgumentException(
                    "Cannot remove surface associated with this output configuration");
        }
        if (!mSurfaces.remove(surface)) {
            throw new IllegalArgumentException("Surface is not part of this output configuration");
        }
    }

    /**
     * Set stream use case for this OutputConfiguration
     *
     * <p>Stream use case is used to describe the purpose of the stream, whether it's for live
     * preview, still image capture, video recording, or their combinations. This flag is useful
     * for scenarios where the immediate consumer target isn't sufficient to indicate the stream's
     * usage.</p>
     *
     * <p>The main difference between stream use case and capture intent is that the former
     * enables the camera device to optimize camera hardware and software pipelines based on user
     * scenarios for each stream, whereas the latter is mainly a hint to camera to decide
     * optimal 3A strategy that's applicable to the whole session. The camera device carries out
     * configurations such as selecting tuning parameters, choosing camera sensor mode, and
     * constructing image processing pipeline based on the streams's use cases. Capture intents are
     * then used to fine tune 3A behaviors such as adjusting AE/AF convergence speed, and capture
     * intents may change during the lifetime of a session. For example, for a session with a
     * PREVIEW_VIDEO_STILL use case stream and a STILL_CAPTURE use case stream, the capture intents
     * may be PREVIEW with fast 3A convergence speed and flash metering with automatic control for
     * live preview, STILL_CAPTURE with best 3A parameters for still photo capture, or VIDEO_RECORD
     * with slower 3A convergence speed for better video playback experience.</p>
     *
     * <p>The supported stream use cases supported by a camera device can be queried by
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES}.</p>
     *
     * <p>The mandatory stream combinations involving stream use cases can be found at {@link
     * android.hardware.camera2.CameraDevice#createCaptureSession}, as well as queried via
     * {@link android.hardware.camera2.params.MandatoryStreamCombination}. The application is
     * strongly recommended to select one of the guaranteed stream combinations where all streams'
     * use cases are set to non-DEFAULT values. If the application chooses a stream combination
     * not in the mandatory list, the camera device may ignore some use case flags due to
     * hardware constraints or implementation details.</p>
     *
     * <p>This function must be called before {@link CameraDevice#createCaptureSession} or {@link
     * CameraDevice#createCaptureSessionByOutputConfigurations}. Calling this function after
     * {@link CameraDevice#createCaptureSession} or
     * {@link CameraDevice#createCaptureSessionByOutputConfigurations} has no effect to the camera
     * session.</p>
     *
     * @param streamUseCase The stream use case to be set.
     *
     * @throws IllegalArgumentException If the streamUseCase isn't within the range of valid
     *                                  values.
     */
    public void setStreamUseCase(@StreamUseCase long streamUseCase) {
        // Verify that the value is in range
        long maxUseCaseValue = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW;
        if (streamUseCase > maxUseCaseValue &&
                streamUseCase < CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VENDOR_START) {
            throw new IllegalArgumentException("Not a valid stream use case value " +
                    streamUseCase);
        }

        mStreamUseCase = streamUseCase;
    }

    /**
     * Get the current stream use case
     *
     * <p>If no {@link #setStreamUseCase} is called first, this function returns
     * {@link CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT DEFAULT}.</p>
     *
     * @return the currently set stream use case
     */
    public long getStreamUseCase() {
        return mStreamUseCase;
    }

    /**
     * Set timestamp base for this output target
     *
     * <p>Timestamp base describes the time domain of images from this
     * camera output and its relationship with {@link
     * CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE}.</p>
     *
     * <p>If this function is not called, the timestamp base for this output
     * is {@link #TIMESTAMP_BASE_DEFAULT}, with which the camera device adjusts
     * timestamps based on the output target.</p>
     *
     * <p>See {@link #TIMESTAMP_BASE_DEFAULT}, {@link #TIMESTAMP_BASE_SENSOR},
     * and {@link #TIMESTAMP_BASE_CHOREOGRAPHER_SYNCED} for details of each timestamp base.</p>
     *
     * @param timestampBase The timestamp base to be set.
     *
     * @throws IllegalArgumentException If the timestamp base isn't within the range of valid
     *                                  values.
     */
    public void setTimestampBase(@TimestampBase int timestampBase) {
        // Verify that the value is in range
        if (timestampBase < TIMESTAMP_BASE_DEFAULT ||
                timestampBase > TIMESTAMP_BASE_READOUT_SENSOR) {
            throw new IllegalArgumentException("Not a valid timestamp base value " +
                    timestampBase);
        }

        if (timestampBase == TIMESTAMP_BASE_READOUT_SENSOR) {
            mTimestampBase = TIMESTAMP_BASE_SENSOR;
            mReadoutTimestampEnabled = true;
            mIsReadoutSensorTimestampBase = true;
        } else {
            mTimestampBase = timestampBase;
            mIsReadoutSensorTimestampBase = false;
        }
    }

    /**
     * Get the current timestamp base
     *
     * <p>If no {@link #setTimestampBase} is called first, this function returns
     * {@link #TIMESTAMP_BASE_DEFAULT}.</p>
     *
     * @return The currently set timestamp base
     */
    public @TimestampBase int getTimestampBase() {
        if (mIsReadoutSensorTimestampBase) {
            return TIMESTAMP_BASE_READOUT_SENSOR;
        } else {
            return mTimestampBase;
        }
    }

    /**
     * Set the mirroring mode for this output target
     *
     * <p>If this function is not called, the mirroring mode for this output is
     * {@link #MIRROR_MODE_AUTO}, with which the camera API will mirror the output images
     * horizontally for front facing camera.</p>
     *
     * <p>For efficiency, the mirror effect is applied as a transform flag, so it is only effective
     * in some outputs. It works automatically for SurfaceView and TextureView outputs. For manual
     * use of SurfaceTexture, it is reflected in the value of
     * {@link android.graphics.SurfaceTexture#getTransformMatrix}. For other end points, such as
     * ImageReader, MediaRecorder, or MediaCodec, the mirror mode has no effect. If mirroring is
     * needed for such outputs, the application needs to mirror the image buffers itself before
     * passing them onward.</p>
     */
    public void setMirrorMode(@MirrorMode int mirrorMode) {
        // Verify that the value is in range
        if (mirrorMode < MIRROR_MODE_AUTO ||
                mirrorMode > MIRROR_MODE_V) {
            throw new IllegalArgumentException("Not a valid mirror mode " + mirrorMode);
        }
        mMirrorMode = mirrorMode;
    }

    /**
     * Get the current mirroring mode
     *
     * <p>If no {@link #setMirrorMode} is called first, this function returns
     * {@link #MIRROR_MODE_AUTO}.</p>
     *
     * @return The currently set mirroring mode
     */
    public @MirrorMode int getMirrorMode() {
        return mMirrorMode;
    }

    /**
     * Use the camera sensor's readout time for the image timestamp.
     *
     * <p>The start of the camera sensor readout after exposure. For a rolling shutter camera
     * sensor, the timestamp is typically equal to {@code (the start of exposure time) +
     * (exposure time) + (certain fixed offset)}. The fixed offset can vary per session, depending
     * on the underlying sensor configuration. The benefit of using readout time is that when
     * camera runs in a fixed frame rate, the timestamp intervals between frames are constant.</p>
     *
     * <p>Readout timestamp is supported only if {@link
     * CameraCharacteristics#SENSOR_READOUT_TIMESTAMP} is
     * {@link CameraMetadata#SENSOR_READOUT_TIMESTAMP_HARDWARE}.</p>
     *
     * <p>As long as readout timestamp is supported, if the timestamp base is
     * {@link #TIMESTAMP_BASE_CHOREOGRAPHER_SYNCED}, or if the timestamp base is DEFAULT for a
     * SurfaceView output, the image timestamps for the output are always readout time regardless
     * of whether this function is called.</p>
     *
     * @param on The output image timestamp is the start of exposure time if false, and
     *           the start of readout time if true.
     */
    public void setReadoutTimestampEnabled(boolean on) {
        mReadoutTimestampEnabled = on;
    }

    /** Whether readout timestamp is used for this OutputConfiguration.
     *
     * @see #setReadoutTimestampEnabled
     */
    public boolean isReadoutTimestampEnabled() {
        return mReadoutTimestampEnabled;
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
        this.mPhysicalCameraId = other.mPhysicalCameraId;
        this.mIsMultiResolution = other.mIsMultiResolution;
        this.mSensorPixelModesUsed = other.mSensorPixelModesUsed;
        this.mDynamicRangeProfile = other.mDynamicRangeProfile;
        this.mColorSpace = other.mColorSpace;
        this.mStreamUseCase = other.mStreamUseCase;
        this.mTimestampBase = other.mTimestampBase;
        this.mMirrorMode = other.mMirrorMode;
        this.mReadoutTimestampEnabled = other.mReadoutTimestampEnabled;
        this.mUsage = other.mUsage;
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
        String physicalCameraId = source.readString();
        boolean isMultiResolutionOutput = source.readInt() == 1;
        int[] sensorPixelModesUsed = source.createIntArray();

        checkArgumentInRange(rotation, ROTATION_0, ROTATION_270, "Rotation constant");
        long dynamicRangeProfile = source.readLong();
        DynamicRangeProfiles.checkProfileValue(dynamicRangeProfile);
        int colorSpace = source.readInt();
        long streamUseCase = source.readLong();

        int timestampBase = source.readInt();
        int mirrorMode = source.readInt();
        boolean readoutTimestampEnabled = source.readInt() == 1;
        int format = source.readInt();
        int dataSpace = source.readInt();
        long usage = source.readLong();

        mSurfaceGroupId = surfaceSetId;
        mRotation = rotation;
        mSurfaces = surfaces;
        mConfiguredSize = new Size(width, height);
        mIsDeferredConfig = isDeferred;
        mIsShared = isShared;
        mSurfaces = surfaces;
        mUsage = 0;
        if (mSurfaces.size() > 0) {
            mSurfaceType = SURFACE_TYPE_UNKNOWN;
            mConfiguredFormat = SurfaceUtils.getSurfaceFormat(mSurfaces.get(0));
            mConfiguredDataspace = SurfaceUtils.getSurfaceDataspace(mSurfaces.get(0));
            mConfiguredGenerationId = mSurfaces.get(0).getGenerationId();
        } else {
            mSurfaceType = surfaceType;
            if (mSurfaceType != SURFACE_TYPE_IMAGE_READER) {
                mConfiguredFormat = StreamConfigurationMap.imageFormatToInternal(
                        ImageFormat.PRIVATE);
                mConfiguredDataspace =
                        StreamConfigurationMap.imageFormatToDataspace(ImageFormat.PRIVATE);
            } else {
                mConfiguredFormat = format;
                mConfiguredDataspace = dataSpace;
                mUsage = usage;
            }
            mConfiguredGenerationId = 0;
        }
        mPhysicalCameraId = physicalCameraId;
        mIsMultiResolution = isMultiResolutionOutput;
        mSensorPixelModesUsed = convertIntArrayToIntegerList(sensorPixelModesUsed);
        mDynamicRangeProfile = dynamicRangeProfile;
        mColorSpace = colorSpace;
        mStreamUseCase = streamUseCase;
        mTimestampBase = timestampBase;
        mMirrorMode = mirrorMode;
        mReadoutTimestampEnabled = readoutTimestampEnabled;
    }

    /**
     * Get the maximum supported shared {@link Surface} count.
     *
     * @return the maximum number of surfaces that can be added per each OutputConfiguration.
     *
     * @see #enableSurfaceSharing
     */
    public int getMaxSharedSurfaceCount() {
        return MAX_SURFACES_COUNT;
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

    /**
     * Get the configured size associated with this {@link OutputConfiguration}.
     *
     * @return The configured size associated with this {@link OutputConfiguration}.
     *
     * @hide
     */
    public Size getConfiguredSize() {
        return mConfiguredSize;
    }

    /**
     * Get the physical camera ID associated with this {@link OutputConfiguration}.
     *
     * <p>If this OutputConfiguration isn't targeting a physical camera of a logical
     * multi-camera, this function returns {@code null}.</p>
     *
     * @return The physical camera Id associated with this {@link OutputConfiguration}.
     *
     * @hide
     */
    public @Nullable String getPhysicalCameraId() {
        return mPhysicalCameraId;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<OutputConfiguration> CREATOR =
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

    private static int[] convertIntegerToIntList(List<Integer> integerList) {
        int[] integerArray = new int[integerList.size()];
        for (int i = 0; i < integerList.size(); i++) {
            integerArray[i] = integerList.get(i);
        }
        return integerArray;
    }

    private static ArrayList<Integer> convertIntArrayToIntegerList(int[] intArray) {
        ArrayList<Integer> integerList = new ArrayList<Integer>();
        if (intArray == null) {
            return integerList;
        }
        for (int i = 0; i < intArray.length; i++) {
            integerList.add(intArray[i]);
        }
        return integerList;
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
        dest.writeString(mPhysicalCameraId);
        dest.writeInt(mIsMultiResolution ? 1 : 0);
        // writeList doesn't seem to work well with Integer list.
        dest.writeIntArray(convertIntegerToIntList(mSensorPixelModesUsed));
        dest.writeLong(mDynamicRangeProfile);
        dest.writeInt(mColorSpace);
        dest.writeLong(mStreamUseCase);
        dest.writeInt(mTimestampBase);
        dest.writeInt(mMirrorMode);
        dest.writeInt(mReadoutTimestampEnabled ? 1 : 0);
        dest.writeInt(mConfiguredFormat);
        dest.writeInt(mConfiguredDataspace);
        dest.writeLong(mUsage);
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
    public boolean equals(@Nullable Object obj) {
        if (obj == null) {
            return false;
        } else if (this == obj) {
            return true;
        } else if (obj instanceof OutputConfiguration) {
            final OutputConfiguration other = (OutputConfiguration) obj;
            if (mRotation != other.mRotation
                    || !mConfiguredSize.equals(other.mConfiguredSize)
                    || mConfiguredFormat != other.mConfiguredFormat
                    || mSurfaceGroupId != other.mSurfaceGroupId
                    || mSurfaceType != other.mSurfaceType
                    || mIsDeferredConfig != other.mIsDeferredConfig
                    || mIsShared != other.mIsShared
                    || mConfiguredDataspace != other.mConfiguredDataspace
                    || mConfiguredGenerationId != other.mConfiguredGenerationId
                    || !Objects.equals(mPhysicalCameraId, other.mPhysicalCameraId)
                    || mIsMultiResolution != other.mIsMultiResolution
                    || mStreamUseCase != other.mStreamUseCase
                    || mTimestampBase != other.mTimestampBase
                    || mMirrorMode != other.mMirrorMode
                    || mReadoutTimestampEnabled != other.mReadoutTimestampEnabled
                    || mUsage != other.mUsage) {
                return false;
            }
            if (mSensorPixelModesUsed.size() != other.mSensorPixelModesUsed.size()) {
                return false;
            }
            for (int j = 0; j < mSensorPixelModesUsed.size(); j++) {
                if (!Objects.equals(
                        mSensorPixelModesUsed.get(j), other.mSensorPixelModesUsed.get(j))) {
                    return false;
                }
            }
            int minLen = Math.min(mSurfaces.size(), other.mSurfaces.size());
            for (int i = 0;  i < minLen; i++) {
                if (mSurfaces.get(i) != other.mSurfaces.get(i))
                    return false;
            }
            if (!mIsDeferredConfig && mSurfaces.size() != other.mSurfaces.size()) return false;
            if (mDynamicRangeProfile != other.mDynamicRangeProfile) {
                return false;
            }
            if (mColorSpace != other.mColorSpace) {
                return false;
            }

            return true;
        }
        return false;
    }

    /**
     * Get and increase the next MultiResolution group id.
     *
     * If the ID reaches -1, skip it.
     */
    private static int getAndIncreaseMultiResolutionGroupId() {
        return sNextMultiResolutionGroupId.getAndUpdate(i ->
                i + 1 == SURFACE_GROUP_ID_NONE ? i + 2 : i + 1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        // Need ensure that the hashcode remains unchanged after adding a deferred surface.
        // Otherwise the deferred output configuration will be lost in the camera stream map
        // after the deferred surface is set.
        if (mIsDeferredConfig) {
            return HashCodeHelpers.hashCode(
                    mRotation, mConfiguredSize.hashCode(), mConfiguredFormat, mConfiguredDataspace,
                    mSurfaceGroupId, mSurfaceType, mIsShared ? 1 : 0,
                    mPhysicalCameraId == null ? 0 : mPhysicalCameraId.hashCode(),
                    mIsMultiResolution ? 1 : 0, mSensorPixelModesUsed.hashCode(),
                    mDynamicRangeProfile, mColorSpace, mStreamUseCase,
                    mTimestampBase, mMirrorMode, mReadoutTimestampEnabled ? 1 : 0,
                    Long.hashCode(mUsage));
        }

        return HashCodeHelpers.hashCode(
                mRotation, mSurfaces.hashCode(), mConfiguredGenerationId,
                mConfiguredSize.hashCode(), mConfiguredFormat,
                mConfiguredDataspace, mSurfaceGroupId, mIsShared ? 1 : 0,
                mPhysicalCameraId == null ? 0 : mPhysicalCameraId.hashCode(),
                mIsMultiResolution ? 1 : 0, mSensorPixelModesUsed.hashCode(),
                mDynamicRangeProfile, mColorSpace, mStreamUseCase, mTimestampBase,
                mMirrorMode, mReadoutTimestampEnabled ? 1 : 0, Long.hashCode(mUsage));
    }

    private static final String TAG = "OutputConfiguration";

    // A surfaceGroupId counter used for MultiResolutionImageReader. Its value is
    // incremented every time {@link createInstancesForMultiResolutionOutput} is called.
    private static AtomicInteger sNextMultiResolutionGroupId = new AtomicInteger(0);

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
    // The physical camera id that this output configuration is for.
    private String mPhysicalCameraId;
    // Flag indicating if this config is for a multi-resolution output with a
    // MultiResolutionImageReader
    private boolean mIsMultiResolution;
    // The sensor pixel modes that this OutputConfiguration will use
    private ArrayList<Integer> mSensorPixelModesUsed;
    // Dynamic range profile
    private long mDynamicRangeProfile;
    // Color space
    private int mColorSpace;
    // Stream use case
    private long mStreamUseCase;
    // Timestamp base
    private int mTimestampBase;
    // Mirroring mode
    private int mMirrorMode;
    // readout timestamp
    private boolean mReadoutTimestampEnabled;
    // Whether the timestamp base is set to READOUT_SENSOR
    private boolean mIsReadoutSensorTimestampBase;
    // The usage flags. Only set for instances created for ImageReader without specifying surface.
    private long mUsage;
}
