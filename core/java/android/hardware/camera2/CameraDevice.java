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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;

import com.android.internal.camera.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * <p>The CameraDevice class is a representation of a single camera connected to an
 * Android device, allowing for fine-grain control of image capture and
 * post-processing at high frame rates.</p>
 *
 * <p>Your application must declare the
 * {@link android.Manifest.permission#CAMERA Camera} permission in its manifest
 * in order to access camera devices.</p>
 *
 * <p>A given camera device may provide support at one of several levels defined
 * in {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}.
 * If a device supports {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY LEGACY} level,
 * the camera device is running in backward compatibility mode and has minimum camera2 API support.
 * If a device supports the {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED}
 * level, then Camera2 exposes a feature set that is roughly equivalent to the older
 * {@link android.hardware.Camera Camera} API, although with a cleaner and more
 * efficient interface.
 * If a device supports the {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL EXTERNAL}
 * level, then the device is a removable camera that provides similar but slightly less features
 * as the {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED} level.
 * Devices that implement the {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL} or
 * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_3 LEVEL3} level of support
 * provide substantially improved capabilities over the older camera
 * API. If your application requires a full-level device for
 * proper operation, declare the "android.hardware.camera.level.full" feature in your
 * manifest.</p>
 *
 * @see CameraManager#openCamera
 * @see android.Manifest.permission#CAMERA
 * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
 */
public abstract class CameraDevice implements AutoCloseable {

    /**
     * Create a request suitable for a camera preview window. Specifically, this
     * means that high frame rate is given priority over the highest-quality
     * post-processing. These requests would normally be used with the
     * {@link CameraCaptureSession#setRepeatingRequest} method.
     * This template is guaranteed to be supported on all camera devices.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_PREVIEW = 1;

    /**
     * Create a request suitable for still image capture. Specifically, this
     * means prioritizing image quality over frame rate. These requests would
     * commonly be used with the {@link CameraCaptureSession#capture} method.
     * This template is guaranteed to be supported on all camera devices except
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT DEPTH_OUTPUT} devices
     * that are not {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
     * BACKWARD_COMPATIBLE}.
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_STILL_CAPTURE = 2;

    /**
     * Create a request suitable for video recording. Specifically, this means
     * that a stable frame rate is used, and post-processing is set for
     * recording quality. These requests would commonly be used with the
     * {@link CameraCaptureSession#setRepeatingRequest} method.
     * This template is guaranteed to be supported on all camera devices except
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT DEPTH_OUTPUT} devices
     * that are not {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
     * BACKWARD_COMPATIBLE}.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_RECORD  = 3;

    /**
     * Create a request suitable for still image capture while recording
     * video. Specifically, this means maximizing image quality without
     * disrupting the ongoing recording. These requests would commonly be used
     * with the {@link CameraCaptureSession#capture} method while a request based on
     * {@link #TEMPLATE_RECORD} is is in use with {@link CameraCaptureSession#setRepeatingRequest}.
     * This template is guaranteed to be supported on all camera devices except
     * legacy devices ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY LEGACY}) and
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT DEPTH_OUTPUT} devices
     * that are not {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
     * BACKWARD_COMPATIBLE}.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_VIDEO_SNAPSHOT = 4;

    /**
     * Create a request suitable for zero shutter lag still capture. This means
     * means maximizing image quality without compromising preview frame rate.
     * AE/AWB/AF should be on auto mode. This is intended for application-operated ZSL. For
     * device-operated ZSL, use {@link CaptureRequest#CONTROL_ENABLE_ZSL} if available.
     * This template is guaranteed to be supported on camera devices that support the
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING PRIVATE_REPROCESSING}
     * capability or the
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING YUV_REPROCESSING}
     * capability.
     *
     * @see #createCaptureRequest
     * @see CaptureRequest#CONTROL_ENABLE_ZSL
     */
    public static final int TEMPLATE_ZERO_SHUTTER_LAG = 5;

    /**
     * A basic template for direct application control of capture
     * parameters. All automatic control is disabled (auto-exposure, auto-white
     * balance, auto-focus), and post-processing parameters are set to preview
     * quality. The manual capture parameters (exposure, sensitivity, and so on)
     * are set to reasonable defaults, but should be overridden by the
     * application depending on the intended use case.
     * This template is guaranteed to be supported on camera devices that support the
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR MANUAL_SENSOR}
     * capability.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_MANUAL = 6;

     /** @hide */
     @Retention(RetentionPolicy.SOURCE)
     @IntDef(prefix = {"TEMPLATE_"}, value =
         {TEMPLATE_PREVIEW,
          TEMPLATE_STILL_CAPTURE,
          TEMPLATE_RECORD,
          TEMPLATE_VIDEO_SNAPSHOT,
          TEMPLATE_ZERO_SHUTTER_LAG,
          TEMPLATE_MANUAL})
     public @interface RequestTemplate {};

     /**
      * No vibration or sound muting for this camera device. This is the default
      * mode for all camera devices.
      *
      * @see #setCameraAudioRestriction
      */
     public static final int AUDIO_RESTRICTION_NONE = 0;

     /**
      * Mute vibration from ringtones, alarms or notifications while this camera device is in use.
      *
      * @see #setCameraAudioRestriction
      */
     public static final int AUDIO_RESTRICTION_VIBRATION = 1;

     /**
      * Mute vibration and sound from ringtones, alarms or notifications while this camera device is
      * in use.
      *
      * @see #setCameraAudioRestriction
      */
     public static final int AUDIO_RESTRICTION_VIBRATION_SOUND = 3;

     /** @hide */
     @Retention(RetentionPolicy.SOURCE)
     @IntDef(prefix = {"AUDIO_RESTRICTION_"}, value =
         {AUDIO_RESTRICTION_NONE,
          AUDIO_RESTRICTION_VIBRATION,
          AUDIO_RESTRICTION_VIBRATION_SOUND})
     public @interface CAMERA_AUDIO_RESTRICTION {};

    /**
     * Get the ID of this camera device.
     *
     * <p>This matches the ID given to {@link CameraManager#openCamera} to instantiate this
     * this camera device.</p>
     *
     * <p>This ID can be used to query the camera device's {@link
     * CameraCharacteristics fixed properties} with {@link
     * CameraManager#getCameraCharacteristics}.</p>
     *
     * <p>This method can be called even if the device has been closed or has encountered
     * a serious error.</p>
     *
     * @return the ID for this camera device
     *
     * @see CameraManager#getCameraCharacteristics
     * @see CameraManager#getCameraIdList
     */
    @NonNull
    public abstract String getId();

    /**
     * <p>Create a new camera capture session by providing the target output set of Surfaces to the
     * camera device.</p>
     *
     * @param outputs The new set of Surfaces that should be made available as
     *                targets for captured image data.
     * @param callback The callback to notify about the status of the new capture session.
     * @param handler The handler on which the callback should be invoked, or {@code null} to use
     *                the current thread's {@link android.os.Looper looper}.
     *
     * @throws IllegalArgumentException if the set of output Surfaces do not meet the requirements,
     *                                  the callback is null, or the handler is null but the current
     *                                  thread has no looper.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see CameraCaptureSession
     * @see StreamConfigurationMap#getOutputFormats()
     * @see StreamConfigurationMap#getOutputSizes(int)
     * @see StreamConfigurationMap#getOutputSizes(Class)
     * @deprecated Please use {@link
     *      #createCaptureSession(android.hardware.camera2.params.SessionConfiguration)} for the
     *      full set of configuration options available.
     */
    @Deprecated
    public abstract void createCaptureSession(@NonNull List<Surface> outputs,
            @NonNull CameraCaptureSession.StateCallback callback, @Nullable Handler handler)
            throws CameraAccessException;

    /**
     * <p>Create a new camera capture session by providing the target output set of Surfaces and
     * its corresponding surface configuration to the camera device.</p>
     *
     * @see #createCaptureSession
     * @see OutputConfiguration
     * @deprecated Please use {@link
     *      #createCaptureSession(android.hardware.camera2.params.SessionConfiguration)} for the
     *      full set of configuration options available.
     */
    @Deprecated
    public abstract void createCaptureSessionByOutputConfigurations(
            List<OutputConfiguration> outputConfigurations,
            CameraCaptureSession.StateCallback callback, @Nullable Handler handler)
            throws CameraAccessException;
    /**
     * Create a new reprocessable camera capture session by providing the desired reprocessing
     * input Surface configuration and the target output set of Surfaces to the camera device.
     *
     * @param inputConfig The configuration for the input {@link Surface}
     * @param outputs The new set of Surfaces that should be made available as
     *                targets for captured image data.
     * @param callback The callback to notify about the status of the new capture session.
     * @param handler The handler on which the callback should be invoked, or {@code null} to use
     *                the current thread's {@link android.os.Looper looper}.
     *
     * @throws IllegalArgumentException if the input configuration is null or not supported, the set
     *                                  of output Surfaces do not meet the requirements, the
     *                                  callback is null, or the handler is null but the current
     *                                  thread has no looper.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see #createCaptureSession
     * @see CameraCaptureSession
     * @see StreamConfigurationMap#getInputFormats
     * @see StreamConfigurationMap#getInputSizes
     * @see StreamConfigurationMap#getValidOutputFormatsForInput
     * @see StreamConfigurationMap#getOutputSizes(int)
     * @see StreamConfigurationMap#getOutputSizes(Class)
     * @see android.media.ImageWriter
     * @see android.media.ImageReader
     * @deprecated Please use {@link
     *      #createCaptureSession(android.hardware.camera2.params.SessionConfiguration)} for the
     *      full set of configuration options available.
     */
    @Deprecated
    public abstract void createReprocessableCaptureSession(@NonNull InputConfiguration inputConfig,
            @NonNull List<Surface> outputs, @NonNull CameraCaptureSession.StateCallback callback,
            @Nullable Handler handler)
            throws CameraAccessException;

    /**
     * Create a new reprocessable camera capture session by providing the desired reprocessing
     * input configuration and output {@link OutputConfiguration}
     * to the camera device.
     *
     * @see #createReprocessableCaptureSession
     * @see OutputConfiguration
     * @deprecated Please use {@link
     *      #createCaptureSession(android.hardware.camera2.params.SessionConfiguration)} for the
     *      full set of configuration options available.
     */
    @Deprecated
    public abstract void createReprocessableCaptureSessionByConfigurations(
            @NonNull InputConfiguration inputConfig,
            @NonNull List<OutputConfiguration> outputs,
            @NonNull CameraCaptureSession.StateCallback callback,
            @Nullable Handler handler)
            throws CameraAccessException;

    /**
     * <p>Create a new constrained high speed capture session.</p>
     *
     * @param outputs The new set of Surfaces that should be made available as
     *                targets for captured high speed image data.
     * @param callback The callback to notify about the status of the new capture session.
     * @param handler The handler on which the callback should be invoked, or {@code null} to use
     *                the current thread's {@link android.os.Looper looper}.
     *
     * @throws IllegalArgumentException if the set of output Surfaces do not meet the requirements,
     *                                  the callback is null, or the handler is null but the current
     *                                  thread has no looper, or the camera device doesn't support
     *                                  high speed video capability.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see #createCaptureSession
     * @see CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE
     * @see StreamConfigurationMap#getHighSpeedVideoSizes
     * @see StreamConfigurationMap#getHighSpeedVideoFpsRangesFor
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
     * @see CameraCaptureSession#captureBurst
     * @see CameraCaptureSession#setRepeatingBurst
     * @see CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList
     * @deprecated Please use {@link
     *      #createCaptureSession(android.hardware.camera2.params.SessionConfiguration)} for the
     *      full set of configuration options available.
     */
    @Deprecated
    public abstract void createConstrainedHighSpeedCaptureSession(@NonNull List<Surface> outputs,
            @NonNull CameraCaptureSession.StateCallback callback,
            @Nullable Handler handler)
            throws CameraAccessException;

    /**
     * Initialize a specific device-specific extension augmented camera capture
     * session.
     *
     * <p>Extension sessions can be used to enable device-specific operation modes like
     * {@link CameraExtensionCharacteristics#EXTENSION_NIGHT} or
     * {@link CameraExtensionCharacteristics#EXTENSION_HDR}. These modes are less flexible than the
     * full camera API, but enable access to more sophisticated processing algorithms that can
     * capture multi-frame bursts to generate single output images. To query for available
     * extensions on this device call
     * {@link CameraExtensionCharacteristics#getSupportedExtensions()}.</p>
     *
     * <p>This method will also trigger the setup of the internal
     * processing pipeline for extension augmented preview and multi-frame
     * still capture.</p>
     *
     * <p>If a prior CameraCaptureSession already exists when this method is called, the previous
     * session will no longer be able to accept new capture requests and will be closed. Any
     * in-progress capture requests made on the prior session will be completed before it's closed.
     * </p>
     *
     * <p>The CameraExtensionSession will be active until the client
     * either calls CameraExtensionSession.close() or creates a new camera
     * capture session. In both cases all internal resources will be
     * released, continuous repeating requests stopped and any pending
     * multi-frame capture requests flushed.</p>
     *
     * <p>Note that the CameraExtensionSession currently supports at most wo
     * multi frame capture surface formats: ImageFormat.JPEG will be supported
     * by all extensions and ImageFormat.YUV_420_888 may or may not be supported.
     * Clients must query the multi-frame capture format support using
     * {@link CameraExtensionCharacteristics#getExtensionSupportedSizes(int, int)}.
     * For repeating requests CameraExtensionSession supports only
     * {@link android.graphics.SurfaceTexture} as output. Clients can query the supported resolution
     * for the repeating request output using
     * {@link CameraExtensionCharacteristics#getExtensionSupportedSizes(int, Class)
     * getExtensionSupportedSizes(..., Class)}.</p>
     *
     * <p>At the very minimum the initialization expects either one valid output
     * surface for repeating or one valid output for high-quality single requests registered in the
     * outputs argument of the extension configuration argument. At the maximum the initialization
     * will accept two valid output surfaces, one for repeating and the other for single requests.
     * Additional unsupported surfaces passed to ExtensionSessionConfiguration will cause an
     * {@link IllegalArgumentException} to be thrown.</p>
     *
     * @param extensionConfiguration extension configuration
     * @throws IllegalArgumentException If both the preview and still
     *                                  capture surfaces are not set or invalid, or if any of the
     *                                  registered surfaces do not meet the device-specific
     *                                  extension requirements such as dimensions and/or
     *                                  (output format)/(surface type), or if the extension is not
     *                                  supported, or if any of the output configurations select
     *                                  a dynamic range different from
     *                                  {@link android.hardware.camera2.params.DynamicRangeProfiles#STANDARD},
     *                                  or if any of the output configurations sets a stream use
     *                                  case different from {@link
     *                                  android.hardware.camera2.CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT}.
     * @see CameraExtensionCharacteristics#getSupportedExtensions
     * @see CameraExtensionCharacteristics#getExtensionSupportedSizes
     */
    public void createExtensionSession(
            @NonNull ExtensionSessionConfiguration extensionConfiguration)
            throws CameraAccessException {
        throw new UnsupportedOperationException("No default implementation");
    }

    /**
     * Standard camera operation mode.
     *
     * @see #createCustomCaptureSession
     * @hide
     */
    @SystemApi
    public static final int SESSION_OPERATION_MODE_NORMAL =
            0; // ICameraDeviceUser.NORMAL_MODE;

    /**
     * Constrained high-speed operation mode.
     *
     * @see #createCustomCaptureSession
     * @hide
     */
    @SystemApi
    public static final int SESSION_OPERATION_MODE_CONSTRAINED_HIGH_SPEED =
            1; // ICameraDeviceUser.CONSTRAINED_HIGH_SPEED_MODE;

     /**
     * Shared camera operation mode.
     *
     * @see #CameraSharedCaptureSession
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAMERA_MULTI_CLIENT)
    public static final int SESSION_OPERATION_MODE_SHARED =
            2; // ICameraDeviceUser.SHARED_MODE;

    /**
     * First vendor-specific operating mode
     *
     * @see #createCustomCaptureSession
     * @hide
     */
    @SystemApi
    public static final int SESSION_OPERATION_MODE_VENDOR_START =
            0x8000; // ICameraDeviceUser.VENDOR_MODE_START;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SESSION_OPERATION_MODE"}, value =
            {SESSION_OPERATION_MODE_NORMAL,
             SESSION_OPERATION_MODE_CONSTRAINED_HIGH_SPEED,
             SESSION_OPERATION_MODE_SHARED,
             SESSION_OPERATION_MODE_VENDOR_START})
    public @interface SessionOperatingMode {};

    /**
     * Create a new camera capture session with a custom operating mode.
     *
     * @param inputConfig The configuration for the input {@link Surface} if a reprocessing session
     *                is desired, or {@code null} otherwise.
     * @param outputs The new set of {@link OutputConfiguration OutputConfigurations} that should be
     *                made available as targets for captured image data.
     * @param operatingMode The custom operating mode to use; a nonnegative value, either a custom
     *                vendor value or one of the SESSION_OPERATION_MODE_* values.
     * @param callback The callback to notify about the status of the new capture session.
     * @param handler The handler on which the callback should be invoked, or {@code null} to use
     *                the current thread's {@link android.os.Looper looper}.
     *
     * @throws IllegalArgumentException if the input configuration is null or not supported, the set
     *                                  of output Surfaces do not meet the requirements, the
     *                                  callback is null, or the handler is null but the current
     *                                  thread has no looper.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see #createCaptureSession
     * @see #createReprocessableCaptureSession
     * @see CameraCaptureSession
     * @see OutputConfiguration
     * @deprecated Please use {@link
     *      #createCaptureSession(android.hardware.camera2.params.SessionConfiguration)} for the
     *      full set of configuration options available.
     * @hide
     */
    @SystemApi
    @Deprecated
    public abstract void createCustomCaptureSession(
            InputConfiguration inputConfig,
            @NonNull List<OutputConfiguration> outputs,
            @SessionOperatingMode int operatingMode,
            @NonNull CameraCaptureSession.StateCallback callback,
            @Nullable Handler handler)
            throws CameraAccessException;

    /**
     * <p>Create a new {@link CameraCaptureSession} using a {@link SessionConfiguration} helper
     * object that aggregates all supported parameters.</p>
     * <p>The active capture session determines the set of potential output Surfaces for
     * the camera device for each capture request. A given request may use all
     * or only some of the outputs. Once the CameraCaptureSession is created, requests can be
     * submitted with {@link CameraCaptureSession#capture capture},
     * {@link CameraCaptureSession#captureBurst captureBurst},
     * {@link CameraCaptureSession#setRepeatingRequest setRepeatingRequest}, or
     * {@link CameraCaptureSession#setRepeatingBurst setRepeatingBurst}.</p>
     *
     * <p>Surfaces suitable for inclusion as a camera output can be created for
     * various use cases and targets:</p>
     *
     * <ul>
     *
     * <li>For drawing to a {@link android.view.SurfaceView SurfaceView}: Once the SurfaceView's
     *   Surface is {@link android.view.SurfaceHolder.Callback#surfaceCreated created}, set the size
     *   of the Surface with {@link android.view.SurfaceHolder#setFixedSize} to be one of the sizes
     *   returned by {@link StreamConfigurationMap#getOutputSizes(Class)
     *   getOutputSizes(SurfaceHolder.class)} and then obtain the Surface by calling {@link
     *   android.view.SurfaceHolder#getSurface}. If the size is not set by the application, it will
     *   be rounded to the nearest supported size less than 1080p, by the camera device.</li>
     *
     * <li>For accessing through an OpenGL texture via a {@link android.graphics.SurfaceTexture
     *   SurfaceTexture}: Set the size of the SurfaceTexture with {@link
     *   android.graphics.SurfaceTexture#setDefaultBufferSize} to be one of the sizes returned by
     *   {@link StreamConfigurationMap#getOutputSizes(Class) getOutputSizes(SurfaceTexture.class)}
     *   before creating a Surface from the SurfaceTexture with
     *   {@link Surface#Surface(SurfaceTexture)}. If the size is not set by the application,
     *   it will be set to be the smallest supported size less than 1080p, by the camera
     *   device.</li>
     *
     * <li>For recording with {@link android.media.MediaCodec}: Call
     *   {@link android.media.MediaCodec#createInputSurface} after configuring
     *   the media codec to use one of the sizes returned by
     *   {@link StreamConfigurationMap#getOutputSizes(Class) getOutputSizes(MediaCodec.class)}
     *   </li>
     *
     * <li>For recording with {@link android.media.MediaRecorder}: Call
     *   {@link android.media.MediaRecorder#getSurface} after configuring the media recorder to use
     *   one of the sizes returned by
     *   {@link StreamConfigurationMap#getOutputSizes(Class) getOutputSizes(MediaRecorder.class)},
     *   or configuring it to use one of the supported
     *   {@link android.media.CamcorderProfile CamcorderProfiles}.</li>
     *
     * <li>For access to RAW, uncompressed YUV, or compressed JPEG data in the application: Create an
     *   {@link android.media.ImageReader} object with one of the supported output formats given by
     *   {@link StreamConfigurationMap#getOutputFormats()}, setting its size to one of the
     *   corresponding supported sizes by passing the chosen output format into
     *   {@link StreamConfigurationMap#getOutputSizes(int)}. Then obtain a
     *   {@link android.view.Surface} from it with {@link android.media.ImageReader#getSurface()}.
     *   If the ImageReader size is not set to a supported size, it will be rounded to a supported
     *   size less than 1080p by the camera device.
     *   </li>
     *
     * </ul>
     *
     * <p>The camera device will query each Surface's size and formats upon this
     * call, so they must be set to a valid setting at this time.</p>
     *
     * <p>It can take several hundred milliseconds for the session's configuration to complete,
     * since camera hardware may need to be powered on or reconfigured. Once the configuration is
     * complete and the session is ready to actually capture data, the provided
     * {@link CameraCaptureSession.StateCallback}'s
     * {@link CameraCaptureSession.StateCallback#onConfigured} callback will be called.</p>
     *
     * <p>If a prior CameraCaptureSession already exists when this method is called, the previous
     * session will no longer be able to accept new capture requests and will be closed. Any
     * in-progress capture requests made on the prior session will be completed before it's closed.
     * {@link CameraCaptureSession.StateCallback#onConfigured} for the new session may be invoked
     * before {@link CameraCaptureSession.StateCallback#onClosed} is invoked for the prior
     * session. Once the new session is {@link CameraCaptureSession.StateCallback#onConfigured
     * configured}, it is able to start capturing its own requests. To minimize the transition time,
     * the {@link CameraCaptureSession#abortCaptures} call can be used to discard the remaining
     * requests for the prior capture session before a new one is created. Note that once the new
     * session is created, the old one can no longer have its captures aborted.</p>
     *
     * <p>Using larger resolution outputs, or more outputs, can result in slower
     * output rate from the device.</p>
     *
     * <p>Configuring a session with an empty or null list will close the current session, if
     * any. This can be used to release the current session's target surfaces for another use.</p>
     *
     * <p>This function throws an {@code IllegalArgumentException} if called with a
     * SessionConfiguration lacking state callbacks or valid output surfaces. The only exceptions
     * are deferred SurfaceView or SurfaceTexture outputs. See {@link
     * OutputConfiguration#OutputConfiguration(Size, Class)} for details.</p>
     *
     * <h3>Regular capture</h3>
     *
     * <p>While any of the sizes from {@link StreamConfigurationMap#getOutputSizes} can be used when
     * a single output stream is configured, a given camera device may not be able to support all
     * combination of sizes, formats, and targets when multiple outputs are configured at once.  The
     * tables below list the maximum guaranteed resolutions for combinations of streams and targets,
     * given the capabilities of the camera device. These are valid for when the
     * {@link android.hardware.camera2.params.SessionConfiguration#setInputConfiguration
     * input configuration} is not set and therefore no reprocessing is active.</p>
     *
     * <p>If an application tries to create a session using a set of targets that exceed the limits
     * described in the below tables, one of three possibilities may occur. First, the session may
     * be successfully created and work normally. Second, the session may be successfully created,
     * but the camera device won't meet the frame rate guarantees as described in
     * {@link StreamConfigurationMap#getOutputMinFrameDuration}. Or third, if the output set
     * cannot be used at all, session creation will fail entirely, with
     * {@link CameraCaptureSession.StateCallback#onConfigureFailed} being invoked.</p>
     *
     * <p>For the type column, {@code PRIV} refers to any target whose available sizes are found
     * using {@link StreamConfigurationMap#getOutputSizes(Class)} with no direct application-visible
     * format, {@code YUV} refers to a target Surface using the
     * {@link android.graphics.ImageFormat#YUV_420_888} format, {@code JPEG} refers to the
     * {@link android.graphics.ImageFormat#JPEG} format, and {@code RAW} refers to the
     * {@link android.graphics.ImageFormat#RAW_SENSOR} format.</p>
     *
     * <p>For the maximum size column, {@code PREVIEW} refers to the best size match to the
     * device's screen resolution, or to 1080p ({@code 1920x1080}), whichever is
     * smaller. {@code RECORD} refers to the camera device's maximum supported recording resolution,
     * as determined by {@link android.media.CamcorderProfile}. And {@code MAXIMUM} refers to the
     * camera device's maximum output resolution for that format or target from
     * {@link StreamConfigurationMap#getOutputSizes}.</p>
     *
     * <p>To use these tables, determine the number and the formats/targets of outputs needed, and
     * find the row(s) of the table with those targets. The sizes indicate the maximum set of sizes
     * that can be used; it is guaranteed that for those targets, the listed sizes and anything
     * smaller from the list given by {@link StreamConfigurationMap#getOutputSizes} can be
     * successfully used to create a session.  For example, if a row indicates that a 8 megapixel
     * (MP) YUV_420_888 output can be used together with a 2 MP {@code PRIV} output, then a session
     * can be created with targets {@code [8 MP YUV, 2 MP PRIV]} or targets {@code [2 MP YUV, 2 MP
     * PRIV]}; but a session with targets {@code [8 MP YUV, 4 MP PRIV]}, targets {@code [4 MP YUV, 4
     * MP PRIV]}, or targets {@code [8 MP PRIV, 2 MP YUV]} would not be guaranteed to work, unless
     * some other row of the table lists such a combination.</p>
     *
     * <style scoped>
     *  #rb { border-right-width: thick; }
     * </style>
     *
     * <h5>LEGACY-level guaranteed configurations</h5>
     *
     * <p>Legacy devices ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY LEGACY}) support at
     * least the following stream combinations:
     *
     * <table>
     * <tr> <th colspan="2" id="rb">Target 1</th> <th colspan="2" id="rb">Target 2</th>  <th colspan="2" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr> <th>Type</th><th id="rb">Max size</th> <th>Type</th><th id="rb">Max size</th> <th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td colspan="2" id="rb"></td> <td>Simple preview, GPU video processing, or no-preview video recording.</td> </tr>
     * <tr> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td colspan="2" id="rb"></td> <td>No-viewfinder still image capture.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td colspan="2" id="rb"></td> <td>In-application video/image processing.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>Standard still imaging.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>In-app processing plus still capture.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td colspan="2" id="rb"></td> <td>Standard recording.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td colspan="2" id="rb"></td> <td>Preview plus in-app processing.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>Still capture plus in-app processing.</td> </tr>
     * </table><br>
     * </p>
     *
     * <h5>LIMITED-level additional guaranteed configurations</h5>
     *
     * <p>Limited-level ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED}) devices
     * support at least the following stream combinations in addition to those for
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY LEGACY} devices:
     *
     * <table>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code RECORD }</td> <td colspan="2" id="rb"></td> <td>High-resolution video recording with preview.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code RECORD }</td> <td colspan="2" id="rb"></td> <td>High-resolution in-app video processing with preview.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code RECORD }</td> <td colspan="2" id="rb"></td> <td>Two-input in-app video processing.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code RECORD }</td> <td>{@code JPEG}</td><td id="rb">{@code RECORD }</td> <td>High-resolution recording with video snapshot.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code RECORD }</td> <td>{@code JPEG}</td><td id="rb">{@code RECORD }</td> <td>High-resolution in-app processing with video snapshot.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>Two-input in-app processing with still capture.</td> </tr>
     * </table><br>
     * </p>
     *
     * <h5>FULL-level additional guaranteed configurations</h5>
     *
     * <p>FULL-level ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL}) devices
     * support at least the following stream combinations in addition to those for
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED} devices:
     *
     * <table>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>Maximum-resolution GPU processing with preview.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>Maximum-resolution in-app processing with preview.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>Maximum-resolution two-input in-app processing.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>Video recording with maximum-size video snapshot</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code 640x480}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td>Standard video recording plus maximum-resolution in-app processing.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code 640x480}</td> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td>Preview plus two-input maximum-resolution in-app processing.</td> </tr>
     * </table><br>
     * </p>
     *
     * <h5>RAW-capability additional guaranteed configurations</h5>
     *
     * <p>RAW-capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES} includes
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_RAW RAW}) devices additionally support
     * at least the following stream combinations on both
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL} and
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED} devices:
     *
     * <table>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th> </tr>
     * <tr> <td>{@code RAW }</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td colspan="2" id="rb"></td> <td>No-preview DNG capture.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code RAW }</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>Standard DNG capture.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code RAW }</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>In-app processing plus DNG capture.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code RAW }</td><td id="rb">{@code MAXIMUM}</td> <td>Video recording with DNG capture.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code RAW }</td><td id="rb">{@code MAXIMUM}</td> <td>Preview with in-app processing and DNG capture.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code RAW }</td><td id="rb">{@code MAXIMUM}</td> <td>Two-input in-app processing plus DNG capture.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code RAW }</td><td id="rb">{@code MAXIMUM}</td> <td>Still capture with simultaneous JPEG and DNG.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code RAW }</td><td id="rb">{@code MAXIMUM}</td> <td>In-app processing with simultaneous JPEG and DNG.</td> </tr>
     * </table><br>
     * </p>
     *
     * <h5>BURST-capability additional guaranteed configurations</h5>
     *
     * <p>BURST-capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES} includes
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE BURST_CAPTURE}) devices
     * support at least the below stream combinations in addition to those for
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED} devices. Note that all
     * FULL-level devices support the BURST capability, and the below list is a strict subset of the
     * list for FULL-level devices, so this table is only relevant for LIMITED-level devices that
     * support the BURST_CAPTURE capability.
     *
     * <table>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code MAXIMUM}</td> <td>Maximum-resolution GPU processing with preview.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td>Maximum-resolution in-app processing with preview.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td>Maximum-resolution two-input in-app processing.</td> </tr>
     * </table><br>
     * </p>
     *
     * <h5>LEVEL-3 additional guaranteed configurations</h5>
     *
     * <p>LEVEL-3 ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_3 LEVEL_3})
     * support at least the following stream combinations in addition to the combinations for
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL} and for
     * RAW capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES} includes
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_RAW RAW}):
     *
     * <table>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th><th colspan="2" id="rb">Target 4</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code 640x480}</td> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td> <td>In-app viewfinder analysis with dynamic selection of output format.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code 640x480}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td> <td>In-app viewfinder analysis with dynamic selection of output format.</td> </tr>
     * </table><br>
     * </p>
     *
     * <h5>Concurrent stream guaranteed configurations</h5>
     *
     * <p>BACKWARD_COMPATIBLE devices capable of streaming concurrently with other devices as
     * described by {@link android.hardware.camera2.CameraManager#getConcurrentCameraIds} have the
     * following guaranteed streams (when streaming concurrently with other devices)</p>
     *
     * <p> Note: The sizes mentioned for these concurrent streams are the maximum sizes guaranteed
     * to be supported. Sizes smaller than these, obtained by {@link StreamConfigurationMap#getOutputSizes} for a particular format, are supported as well. </p>
     *
     * <p>
     * <table>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th> </tr>
     * <tr> <td>{@code YUV}</td><td id="rb">{@code s1440p}</td>  <td colspan="2" id="rb"></td> <td>In-app video / image processing.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code s1440p}</td>  <td colspan="2" id="rb"></td> <td>In-app viewfinder analysis.</td> </tr>
     * <tr> <td>{@code JPEG}</td><td id="rb">{@code s1440p}</td>  <td colspan="2" id="rb"></td> <td>No viewfinder still image capture.</td> </tr>
     * <tr> <td>{@code YUV / PRIV}</td><td id="rb">{@code s720p}</td> <td>{@code JPEG}</td><td id="rb">{@code s1440p}</td> <td> Standard still imaging.</td> </tr>
     * <tr> <td>{@code YUV / PRIV}</td><td id="rb">{@code s720p}</td> <td>{@code YUV / PRIV }</td><td id="rb">{@code s1440p}</td> <td>In-app video / processing with preview.</td> </tr>
     * </table><br>
     * </p>
     *
     * <p> Devices which are not backwards-compatible, support a mandatory single stream of size sVGA with image format {@code DEPTH16} during concurrent operation. </p>
     *
     * <p> For guaranteed concurrent stream configurations:</p>
     * <p> sVGA refers to the camera device's maximum resolution for that format from {@link StreamConfigurationMap#getOutputSizes} or
     * VGA resolution (640X480) whichever is lower. </p>
     * <p> s720p refers to the camera device's maximum resolution for that format from {@link StreamConfigurationMap#getOutputSizes} or
     * 720p(1280X720) whichever is lower. </p>
     * <p> s1440p refers to the camera device's maximum resolution for that format from {@link StreamConfigurationMap#getOutputSizes} or
     * 1440p(1920X1440) whichever is lower. </p>
     * <p>MONOCHROME-capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES}
     * includes {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME MONOCHROME}) devices
     * supporting {@link android.graphics.ImageFormat#Y8 Y8} support substituting {@code YUV}
     * streams with {@code Y8} in all guaranteed stream combinations for the device's hardware level
     * and capabilities.</p>
     *
     * <p>Clients can access the above mandatory stream combination tables via
     * {@link android.hardware.camera2.params.MandatoryStreamCombination}.</p>
     *
     * <p>Devices capable of outputting HEIC formats ({@link StreamConfigurationMap#getOutputFormats}
     * contains {@link android.graphics.ImageFormat#HEIC}) will support substituting {@code JPEG}
     * streams with {@code HEIC} in all guaranteed stream combinations for the device's hardware
     * level and capabilities. Calling createCaptureSession with both JPEG and HEIC outputs is not
     * supported.</p>
     *
     * <h5>LEGACY-level additional guaranteed combinations with multi-resolution outputs</h5>
     *
     * <p>Devices capable of multi-resolution output for a particular format (
     * {@link android.hardware.camera2.params.MultiResolutionStreamConfigurationMap#getOutputInfo}
     * returns a non-empty list) support using {@link MultiResolutionImageReader} for MAXIMUM
     * resolution streams of that format for all mandatory stream combinations. For example,
     * if a LIMITED camera device supports multi-resolution output streams for both {@code JPEG} and
     * {@code PRIVATE}, in addition to the stream configurations
     * in the LIMITED and Legacy table above, the camera device supports the following guaranteed
     * stream combinations ({@code MULTI_RES} in the Max size column refers to a {@link
     * MultiResolutionImageReader} created based on the variable max resolutions supported):
     *
     * <table>
     * <tr> <th colspan="2" id="rb">Target 1</th> <th colspan="2" id="rb">Target 2</th>  <th colspan="2" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr> <th>Type</th><th id="rb">Max size</th> <th>Type</th><th id="rb">Max size</th> <th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code MULTI_RES}</td> <td colspan="2" id="rb"></td> <td colspan="2" id="rb"></td> <td>Simple preview, GPU video processing, or no-preview video recording.</td> </tr>
     * <tr> <td>{@code JPEG}</td><td id="rb">{@code MULTI_RES}</td> <td colspan="2" id="rb"></td> <td colspan="2" id="rb"></td> <td>No-viewfinder still image capture.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MULTI_RES}</td> <td colspan="2" id="rb"></td> <td>Standard still imaging.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MULTI_RES}</td> <td>Still capture plus in-app processing.</td> </tr>
     * </table><br>
     * </p>
     *
     * <h5>LIMITED-level additional guaranteed configurations with multi-resolution outputs</h5>
     *
     * <p>
     * <table>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MULTI_RES}</td> <td>Two-input in-app processing with still capture.</td> </tr>
     * </table><br>
     * The same logic applies to other hardware levels and capabilities.
     * </p>
     *
     * <h5>Additional guaranteed combinations for ULTRA_HIGH_RESOLUTION sensors</h5>
     *
     * <p> Devices with the ULTRA_HIGH_RESOLUTION_SENSOR capability have some additional guarantees
     * which clients can take advantage of:
     *
     * <table>
     * <tr> <th colspan="3" id="rb">Target 1</th> <th colspan="3" id="rb">Target 2</th>  <th colspan="3" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr> <th>Type</th><th id="rb"> SC Map</th><th id="rb">Max size</th> <th>Type</th><th id="rb"> SC Map</th><th id="rb">Max size</th> <th>Type</th><th id="rb"> SC Map</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code YUV / JPEG / RAW}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td><td id="rb">{@code PRIV / YUV}</td><td id="rb">{@code DEFAULT}</td><td id="rb">{@code PREVIEW}</td><td colspan="3" id="rb"></td> <td>Ultra high res still image capture with preview</td> </tr>
     * <tr> <td>{@code YUV / JPEG / RAW}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td><td id="rb">{@code PRIV}</td><td id="rb">{@code DEFAULT}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PRIV / YUV}</td><td id="rb">{@code DEFAULT}</td><td id="rb">{@code RECORD}</td> <td>Ultra high res still capture with preview + app based RECORD size analysis</td> </tr>
     * <tr> <td>{@code YUV / JPEG / RAW}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td><td id="rb">{@code PRIV}</td><td id="rb">{@code DEFAULT}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code JPEG / YUV / RAW}</td><td id="rb">{@code DEFAULT}</td><td id="rb">{@code MAX}</td> <td>Ultra high res still image capture with preview + default sensor pixel mode analysis stream</td> </tr>
     * </table><br>
     * </p>
     *
     * <p> Here, SC Map, refers to the {@link StreamConfigurationMap}, the target stream sizes must
     * be chosen from. {@code DEFAULT} refers to the default sensor pixel mode {@link
     * StreamConfigurationMap} and {@code MAX_RES} refers to the maximum resolution {@link
     * StreamConfigurationMap}. For {@code MAX_RES} streams, {@code MAX} in the {@code Max size} column refers to the maximum size from
     * {@link StreamConfigurationMap#getOutputSizes} and {@link StreamConfigurationMap#getHighResolutionOutputSizes}.
     * Note: The same capture request must not mix targets from
     * {@link StreamConfigurationMap}s corresponding to different sensor pixel modes. </p>
     *
     * <h5>10-bit output additional guaranteed configurations</h5>
     *
     * <p>10-bit output capable
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT}
     * devices support at least the following stream combinations:
     *
     * <table>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code MAXIMUM}</td> </td> <td colspan="4" id="rb"></td> <td>Simple preview, GPU video processing, or no-preview video recording.</td> </tr>
     * <tr> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> </td> <td colspan="4" id="rb"></td> <td>In-application video/image processing.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM }</td> <td colspan="2" id="rb"></td> <td>Standard still imaging.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM }</td> <td colspan="2" id="rb"></td> <td>Maximum-resolution in-app processing with preview.</td> </tr>
     * <tr> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM }</td> <td colspan="2" id="rb"></td> <td>Maximum-resolution two-input in-app processing.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code RECORD }</td> <td colspan="2" id="rb"></td> <td>High-resolution video recording with preview.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code RECORD }</td> <td>{@code YUV}</td><td id="rb">{@code RECORD }</td> <td>High-resolution recording with in-app snapshot.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV }</td><td id="rb">{@code RECORD }</td> <td>{@code JPEG}</td><td id="rb">{@code RECORD }</td> <td>High-resolution recording with video snapshot.</td> </tr>
     * </table><br>
     * </p>
     *
     * <p>Here PRIV can be either 8 or 10-bit {@link android.graphics.ImageFormat#PRIVATE} pixel
     * format. YUV can be either {@link android.graphics.ImageFormat#YUV_420_888} or
     * {@link android.graphics.ImageFormat#YCBCR_P010}.
     * For the maximum size column, PREVIEW refers to the best size match to the device's screen
     * resolution, or to 1080p (1920x1080), whichever is smaller. RECORD refers to the camera
     * device's maximum supported recording resolution, as determined by
     * {@link android.media.CamcorderProfile}. MAXIMUM refers to the camera device's maximum output
     * resolution for that format or target from {@link StreamConfigurationMap#getOutputSizes(int)}.
     * Do note that invalid combinations such as having a camera surface configured to use pixel
     * format {@link android.graphics.ImageFormat#YUV_420_888} with a 10-bit profile
     * will cause a capture session initialization failure.
     * </p>
     * <p>{@link android.graphics.ImageFormat#JPEG_R} may also be supported if advertised by
     * {@link android.hardware.camera2.params.StreamConfigurationMap}. When initializing a capture
     * session that includes a Jpeg/R camera output clients must consider the following items w.r.t.
     * the 10-bit mandatory stream combination table:
     *
     * <ul>
     *     <li>To generate the compressed Jpeg/R image a single
     *     {@link android.graphics.ImageFormat#YCBCR_P010} output will be used internally by
     *     the camera device.</li>
     *     <li>On camera devices that are able to support concurrent 10 and 8-bit capture requests
     *     see {@link android.hardware.camera2.params.DynamicRangeProfiles#getProfileCaptureRequestConstraints}
     *     an extra {@link android.graphics.ImageFormat#JPEG} will also
     *     be configured internally to help speed up the encoding process.</li>
     * </ul>
     *
     * Jpeg/R camera outputs will typically be able to support the MAXIMUM device resolution.
     * Clients can also call {@link StreamConfigurationMap#getOutputSizes(int)} for a complete list
     * supported sizes.
     * Camera clients that register a Jpeg/R output within a stream combination that doesn't fit
     * in the mandatory stream table above can call
     * {@link #isSessionConfigurationSupported} to ensure that this particular
     * configuration is supported.</p>
     *
     * <h5>STREAM_USE_CASE capability additional guaranteed configurations</h5>
     *
     * <p>Devices with the STREAM_USE_CASE capability ({@link
     * CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES} includes {@link
     * CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE}) support below additional
     * stream combinations:
     *
     * <table>
     * <tr><th colspan="3" id="rb">Target 1</th><th colspan="3" id="rb">Target 2</th><th colspan="3" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Usecase</th><th>Type</th><th id="rb">Max size</th><th>Usecase</th><th>Type</th><th id="rb">Max size</th><th>Usecase</th> </tr>
     * <tr> <td>{@code YUV / PRIV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td colspan="3" id="rb"></td> <td colspan="3" id="rb"></td> <td>Simple preview or in-app image processing</td> </tr>
     * <tr> <td>{@code YUV / PRIV}</td><td id="rb">{@code RECORD}</td><td id="rb">{@code VIDEO_RECORD}</td> <td colspan="3" id="rb"></td> <td colspan="3" id="rb"></td> <td>Simple video recording or in-app video processing</td> </tr>
     * <tr> <td>{@code YUV / JPEG}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code STILL_CAPTURE}</td> <td colspan="3" id="rb"></td> <td colspan="3" id="rb"></td> <td>Simple JPEG or YUV still image capture</td> </tr>
     * <tr> <td>{@code YUV / PRIV}</td><td id="rb">{@code s1440p}</td><td id="rb">{@code PREVIEW_VIDEO_STILL}</td> <td colspan="3" id="rb"></td> <td colspan="3" id="rb"></td> <td>Multi-purpose stream for preview, video and still image capture</td> </tr>
     * <tr> <td>{@code YUV / PRIV}</td><td id="rb">{@code s1440p}</td><td id="rb">{@code VIDEO_CALL}</td> <td colspan="3" id="rb"></td> <td colspan="3" id="rb"></td> <td>Simple video call</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV / JPEG}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code STILL_CAPTURE}</td> <td colspan="3" id="rb"></td> <td>Preview with JPEG or YUV still image capture</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV / PRIV}</td><td id="rb">{@code RECORD}</td><td id="rb">{@code VIDEO_RECORD}</td> <td colspan="3" id="rb"></td> <td>Preview with video recording or in-app video processing</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td colspan="3" id="rb"></td> <td>Preview with in-application image processing</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV / PRIV}</td><td id="rb">{@code s1440p}</td><td id="rb">{@code VIDEO_CALL}</td> <td colspan="3" id="rb"></td> <td>Preview with video call</td> </tr>
     * <tr> <td>{@code YUV / PRIV}</td><td id="rb">{@code s1440p}</td><td id="rb">{@code PREVIEW_VIDEO_STILL}</td> <td>{@code YUV / JPEG}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code STILL_CAPTURE}</td> <td colspan="3" id="rb"></td> <td>MultI-purpose stream with JPEG or YUV still capture</td> </tr>
     * <tr> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code STILL_CAPTURE}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code STILL_CAPTURE}</td> <td colspan="3" id="rb"></td> <td>YUV and JPEG concurrent still image capture (for testing)</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV / PRIV}</td><td id="rb">{@code RECORD}</td><td id="rb">{@code VIDEO_RECORD}</td> <td>{@code JPEG}</td><td id="rb">{@code RECORD}</td><td id="rb">{@code STILL_CAPTURE}</td> <td>Preview, video record and JPEG video snapshot</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code STILL_CAPTURE}</td> <td>Preview, in-application image processing, and JPEG still image capture</td> </tr>
     * </table><br>
     * </p>
     *
     * <h5>STREAM_USE_CASE_CROPPED_RAW capability additional guaranteed configurations</h5>
     *
     * <p>Devices that include the {@link CameraMetadata#SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW}
     * stream use-case in {@link CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES},
     * support the additional stream combinations below:
     *
     * <table>
     * <tr><th colspan="3" id="rb">Target 1</th><th colspan="3" id="rb">Target 2</th><th colspan="3" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Usecase</th><th>Type</th><th id="rb">Max size</th><th>Usecase</th><th>Type</th><th id="rb">Max size</th><th>Usecase</th> </tr>
     * <tr> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code CROPPED_RAW}</td> <td colspan="3" id="rb"></td> <td colspan="3" id="rb"></td> <td>Cropped RAW still capture without preview</td> </tr>
     * <tr> <td>{@code PRIV / YUV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code CROPPED_RAW}</td> <td colspan="3" id="rb"></td> <td>Preview with cropped RAW still capture</td> </tr>
     * <tr> <td>{@code PRIV / YUV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV / JPEG}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code STILL_CAPTURE}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code CROPPED_RAW}</td> <td>Preview with YUV / JPEG and cropped RAW still capture</td> </tr>
     * <tr> <td>{@code PRIV / YUV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV / YUV}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code VIDEO_RECORD / PREVIEW}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td><td id="rb">{@code CROPPED_RAW}</td> <td>Video recording with preview and cropped RAW still capture</td> </tr>
     * </table><br>
     * </p>
     *
     * <h5>Preview stabilization guaranteed stream configurations</h5>
     *
     * <p>For devices where
     * {@link CameraCharacteristics#CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES} includes
     * {@link CameraMetadata#CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION},
     * the following stream combinations are guaranteed,
     * for CaptureRequests where {@link CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE} is set to
     * {@link CameraMetadata#CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION}
     *
     * <table>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code PRIV / YUV}</td><td id="rb">{@code s1440p}</td><td colspan="2" id="rb"></td> <td>Stabilized preview, GPU video processing, or no-preview stabilized video recording.</td> </tr>
     * <tr> <td>{@code PRIV / YUV}</td><td id="rb">{@code s1440p}</td> <td>{@code JPEG / YUV}</td><td id="rb">{@code MAXIMUM }</td><td>Standard still imaging with stabilized preview.</td> </tr>
     * <tr> <td>{@code PRIV / YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV / YUV}</td><td id="rb">{@code s1440p }</td><td>High-resolution recording with stabilized preview and recording stream.</td> </tr>
     * </table><br>
     * </p>
     *
     * <p>
     * For the maximum size column, PREVIEW refers to the best size match to the device's screen
     * resolution, or to 1080p (1920x1080), whichever is smaller. RECORD refers to the camera
     * device's maximum supported recording resolution, as determined by
     * {@link android.media.CamcorderProfile}. MAXIMUM refers to the camera device's maximum output
     * resolution for that format or target from {@link StreamConfigurationMap#getOutputSizes(int)}.
     * </p>
     *
     * <p>Since the capabilities of camera devices vary greatly, a given camera device may support
     * target combinations with sizes outside of these guarantees, but this can only be tested for
     * by calling {@link #isSessionConfigurationSupported} or attempting
     * to create a session with such targets.</p>
     *
     * <p>Exception on 176x144 (QCIF) resolution:
     * Camera devices usually have a fixed capability for downscaling from larger resolution to
     * smaller, and the QCIF resolution sometimes is not fully supported due to this
     * limitation on devices with high-resolution image sensors. Therefore, trying to configure a
     * QCIF resolution stream together with any other stream larger than 1920x1080 resolution
     * (either width or height) might not be supported, and capture session creation will fail if it
     * is not.</p>
     *
     * <h3>Reprocessing</h3>
     *
     * <p>If a camera device supports YUV reprocessing
     * ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING}) or PRIVATE
     * reprocessing
     * ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING}), the
     * application can also create a reprocessable capture session to submit reprocess capture
     * requests in addition to regular capture requests, by setting an
     * {@link android.hardware.camera2.params.SessionConfiguration#setInputConfiguration
     * input configuration} for the session. A reprocess capture request takes the next available
     * buffer from the
     * session's input Surface, and sends it through the camera device's processing pipeline again,
     * to produce buffers for the request's target output Surfaces. No new image data is captured
     * for a reprocess request. However the input buffer provided by the application must be
     * captured previously by the same camera device in the same session directly (e.g. for
     * Zero-Shutter-Lag use case) or indirectly (e.g. combining multiple output images).</p>
     *
     * <p>The active reprocessable capture session determines an input {@link Surface} and the set
     * of potential output Surfaces for the camera devices for each capture request. The application
     * can use {@link #createCaptureRequest createCaptureRequest} to create regular capture requests
     * to capture new images from the camera device, and use {@link #createReprocessCaptureRequest
     * createReprocessCaptureRequest} to create reprocess capture requests to process buffers from
     * the input {@link Surface}. Some combinations of output Surfaces in a session may not be used
     * in a request simultaneously. The guaranteed combinations of output Surfaces that can be used
     * in a request simultaneously are listed in the tables under {@link #createCaptureSession
     * createCaptureSession}. All the output Surfaces in one capture request will come from the
     * same source, either from a new capture by the camera device, or from the input Surface
     * depending on if the request is a reprocess capture request.</p>
     *
     * <p>Input formats and sizes supported by the camera device can be queried via
     * {@link StreamConfigurationMap#getInputFormats} and
     * {@link StreamConfigurationMap#getInputSizes}. For each supported input format, the camera
     * device supports a set of output formats and sizes for reprocessing that can be queried via
     * {@link StreamConfigurationMap#getValidOutputFormatsForInput} and
     * {@link StreamConfigurationMap#getOutputSizes}. While output Surfaces with formats that
     * aren't valid reprocess output targets for the input configuration can be part of a session,
     * they cannot be used as targets for a reprocessing request.</p>
     *
     * <p>Since the application cannot access {@link android.graphics.ImageFormat#PRIVATE} images
     * directly, an output Surface created by {@link android.media.ImageReader#newInstance} with
     * {@link android.graphics.ImageFormat#PRIVATE} as the format will be considered as intended to
     * be used for reprocessing input and thus the {@link android.media.ImageReader} size must
     * match one of the supported input sizes for {@link android.graphics.ImageFormat#PRIVATE}
     * format. Otherwise, creating a reprocessable capture session will fail.</p>
     *
     * <p>Starting from API level 30, recreating a reprocessable capture session will flush all the
     * queued but not yet processed buffers from the input surface.</p>
     *
     * <p>The configurations in the tables below are guaranteed for creating a reprocessable
     * capture session if the camera device supports YUV reprocessing or PRIVATE reprocessing.
     * However, not all output targets used to create a reprocessable session may be used in a
     * {@link CaptureRequest} simultaneously. For devices that support only 1 output target in a
     * reprocess {@link CaptureRequest}, submitting a reprocess {@link CaptureRequest} with multiple
     * output targets will result in a {@link CaptureFailure}. For devices that support multiple
     * output targets in a reprocess {@link CaptureRequest}, the guaranteed output targets that can
     * be included in a {@link CaptureRequest} simultaneously are listed in the tables under
     * {@link #createCaptureSession createCaptureSession}. For example, with a FULL-capability
     * ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL} {@code == }
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL}) device that supports PRIVATE
     * reprocessing, an application can create a reprocessable capture session with 1 input,
     * ({@code PRIV}, {@code MAXIMUM}), and 3 outputs, ({@code PRIV}, {@code MAXIMUM}),
     * ({@code PRIV}, {@code PREVIEW}), and ({@code YUV}, {@code MAXIMUM}). However, it's not
     * guaranteed that an application can submit a regular or reprocess capture with ({@code PRIV},
     * {@code MAXIMUM}) and ({@code YUV}, {@code MAXIMUM}) outputs based on the table listed under
     * {@link #createCaptureSession createCaptureSession}. In other words, use the tables below to
     * determine the guaranteed stream configurations for creating a reprocessable capture session,
     * and use the tables under {@link #createCaptureSession createCaptureSession} to determine the
     * guaranteed output targets that can be submitted in a regular or reprocess
     * {@link CaptureRequest} simultaneously.</p>
     *
     * <p>Reprocessing with 10-bit output targets on 10-bit capable
     * {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT} devices is
     * not supported. Trying to initialize a repreocessable capture session with one ore more
     * output configurations set {@link OutputConfiguration#setDynamicRangeProfile} to use
     * a 10-bit dynamic range profile {@link android.hardware.camera2.params.DynamicRangeProfiles}
     * will trigger {@link IllegalArgumentException}.</p>
     *
     * <style scoped>
     *  #rb { border-right-width: thick; }
     * </style>
     *
     * <p>LIMITED-level ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED}) devices
     * support at least the following stream combinations for creating a reprocessable capture
     * session in addition to those listed earlier for regular captures for
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED} devices:
     *
     * <table>
     * <tr><th colspan="11">LIMITED-level additional guaranteed configurations for creating a reprocessable capture session<br>({@code PRIV} input is guaranteed only if PRIVATE reprocessing is supported. {@code YUV} input is guaranteed only if YUV reprocessing is supported)</th></tr>
     * <tr><th colspan="2" id="rb">Input</th><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th><th colspan="2" id="rb">Target 4</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td></td><td id="rb"></td> <td></td><td id="rb"></td> <td>No-viewfinder still image reprocessing.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td></td><td id="rb"></td> <td>ZSL(Zero-Shutter-Lag) still imaging.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td></td><td id="rb"></td> <td>ZSL still and in-app processing imaging.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>ZSL in-app processing with still capture.</td> </tr>
     * </table><br>
     * </p>
     *
     * <p>FULL-level ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL}) devices
     * support at least the following stream combinations for creating a reprocessable capture
     * session in addition to those for
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED} devices:
     *
     * <table>
     * <tr><th colspan="11">FULL-level additional guaranteed configurations for creating a reprocessable capture session<br>({@code PRIV} input is guaranteed only if PRIVATE reprocessing is supported. {@code YUV} input is guaranteed only if YUV reprocessing is supported)</th></tr>
     * <tr><th colspan="2" id="rb">Input</th><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th><th colspan="2" id="rb">Target 4</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td></td><td id="rb"></td> <td></td><td id="rb"></td> <td>Maximum-resolution multi-frame image fusion in-app processing with regular preview.</td> </tr>
     * <tr> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td></td><td id="rb"></td> <td></td><td id="rb"></td> <td>Maximum-resolution multi-frame image fusion two-input in-app processing.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code RECORD}</td> <td></td><td id="rb"></td> <td>High-resolution ZSL in-app video processing with regular preview.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td></td><td id="rb"></td> <td>Maximum-resolution ZSL in-app processing with regular preview.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td></td><td id="rb"></td> <td>Maximum-resolution two-input ZSL in-app processing.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>ZSL still capture and in-app processing.</td> </tr>
     * </table><br>
     * </p>
     *
     * <p>RAW-capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES} includes
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_RAW RAW}) devices additionally support
     * at least the following stream combinations for creating a reprocessable capture session
     * on both {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL} and
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED} devices
     *
     * <table>
     * <tr><th colspan="11">RAW-capability additional guaranteed configurations for creating a reprocessable capture session<br>({@code PRIV} input is guaranteed only if PRIVATE reprocessing is supported. {@code YUV} input is guaranteed only if YUV reprocessing is supported)</th></tr>
     * <tr><th colspan="2" id="rb">Input</th><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th><th colspan="2" id="rb">Target 4</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td> <td></td><td id="rb"></td> <td>Mutually exclusive ZSL in-app processing and DNG capture.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td> <td>Mutually exclusive ZSL in-app processing and preview with DNG capture.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td> <td>Mutually exclusive ZSL two-input in-app processing and DNG capture.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td> <td>Mutually exclusive ZSL still capture and preview with DNG capture.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td> <td>Mutually exclusive ZSL in-app processing with still capture and DNG capture.</td> </tr>
     * </table><br>
     * </p>
     *
     * <p>LEVEL-3 ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_3 LEVEL_3}) devices
     * support at least the following stream combinations for creating a reprocessable capture
     * session in addition to those for
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL} devices. Note that while
     * the second configuration allows for configuring {@code MAXIMUM} {@code YUV} and {@code JPEG}
     * outputs at the same time, that configuration is not listed for regular capture sessions, and
     * therefore simultaneous output to both targets is not allowed.
     *
     * <table>
     * <tr><th colspan="13">LEVEL-3 additional guaranteed configurations for creating a reprocessable capture session<br>({@code PRIV} input is guaranteed only if PRIVATE reprocessing is supported. {@code YUV} input is always guaranteed.</th></tr>
     * <tr><th colspan="2" id="rb">Input</th><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th><th colspan="2" id="rb">Target 4</th><th colspan="2" id="rb">Target 5</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code 640x480}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td> <td></td><td id="rb"></td> <td>In-app viewfinder analysis with ZSL and RAW.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MAXIMUM}</td> <td>Same as input</td><td id="rb">{@code MAXIMUM}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code 640x480}</td> <td>{@code RAW}</td><td id="rb">{@code MAXIMUM}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td><td>In-app viewfinder analysis with ZSL, RAW, and JPEG reprocessing output.</td> </tr>
     * </table><br>
     * </p>
     *
     * <p>If a camera device supports multi-resolution {@code YUV} input and multi-resolution
     * {@code YUV} output or supports multi-resolution {@code PRIVATE} input and multi-resolution
     * {@code PRIVATE} output, the additional mandatory stream combinations for LIMITED and FULL devices are listed
     * below ({@code MULTI_RES} in the Max size column refers to a
     * {@link MultiResolutionImageReader} for output, and a multi-resolution
     * {@link InputConfiguration} for input):
     * <table>
     * <tr><th colspan="11">LIMITED-level additional guaranteed configurations for creating a reprocessable capture session with multi-resolution input and multi-resolution outputs<br>({@code PRIV} input is guaranteed only if PRIVATE reprocessing is supported. {@code YUV} input is guaranteed only if YUV reprocessing is supported)</th></tr>
     * <tr><th colspan="2" id="rb">Input</th><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th><th colspan="2" id="rb">Target 4</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MULTI_RES}</td> <td>Same as input</td><td id="rb">{@code MULTI_RES}</td> <td>{@code JPEG}</td><td id="rb">{@code MULTI_RES}</td> <td></td><td id="rb"></td> <td></td><td id="rb"></td> <td>No-viewfinder still image reprocessing.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MULTI_RES}</td> <td>Same as input</td><td id="rb">{@code MULTI_RES}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MULTI_RES}</td> <td></td><td id="rb"></td> <td>ZSL(Zero-Shutter-Lag) still imaging.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MULTI_RES}</td> <td>Same as input</td><td id="rb">{@code MULTI_RES}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MULTI_RES}</td> <td></td><td id="rb"></td> <td>ZSL still and in-app processing imaging.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MULTI_RES}</td> <td>Same as input</td><td id="rb">{@code MULTI_RES}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MULTI_RES}</td> <td>ZSL in-app processing with still capture.</td> </tr>
     * </table><br>
     * <table>
     * <tr><th colspan="11">FULL-level additional guaranteed configurations for creating a reprocessable capture session with multi-resolution input and multi-resolution outputs<br>({@code PRIV} input is guaranteed only if PRIVATE reprocessing is supported. {@code YUV} input is guaranteed only if YUV reprocessing is supported)</th></tr>
     * <tr><th colspan="2" id="rb">Input</th><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th><th colspan="2" id="rb">Target 4</th><th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code MULTI_RES}</td> <td>{@code PRIV}</td><td id="rb">{@code MULTI_RES}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code MULTI_RES}</td> <td></td><td id="rb"></td> <td>Maximum-resolution ZSL in-app processing with regular preview.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code MULTI_RES}</td> <td>{@code PRIV}</td><td id="rb">{@code MULTI_RES}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code MULTI_RES}</td> <td></td><td id="rb"></td> <td>Maximum-resolution two-input ZSL in-app processing.</td> </tr>
     * <tr> <td>{@code PRIV}/{@code YUV}</td><td id="rb">{@code MULTI_RES}</td> <td>Same as input</td><td id="rb">{@code MULTI_RES}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MULTI_RES}</td> <td>ZSL still capture and in-app processing.</td> </tr>
     * </table><br>
     * <p> Devices with the ULTRA_HIGH_RESOLUTION_SENSOR capability have some additional guarantees
     * which clients can take advantage of : </p>
     * <table>
     * <tr><th colspan="13">Additional guaranteed combinations for ULTRA_HIGH_RESOLUTION sensors (YUV / PRIV inputs are guaranteed only if YUV / PRIVATE reprocessing are supported)</th></tr>
     * <tr> <th colspan="3" id="rb">Input</th> <th colspan="3" id="rb">Target 1</th> <th colspan="3" id="rb">Target 2</th>  <th colspan="3" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr> <th>Type</th><th id="rb"> SC Map</th><th id="rb">Max size</th><th>Type</th><th id="rb"> SC Map</th><th id="rb">Max size</th> <th>Type</th><th id="rb"> SC Map</th><th id="rb">Max size</th> <th>Type</th><th id="rb"> SC Map</th><th id="rb">Max size</th></tr>
     * <tr> <td>{@code RAW}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td><td>{@code RAW}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td><td id="rb">{@code PRIV / YUV}</td><td id="rb">{@code DEFAULT}</td><td id="rb">{@code PREVIEW}</td><td colspan="3" id="rb"></td> <td>RAW remosaic reprocessing with separate preview</td> </tr>
     * <tr> <td>{@code RAW}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td><td>{@code RAW}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td><td id="rb">{@code PRIV / YUV}</td><td id="rb">{@code DEFAULT}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code JPEG / YUV}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td> <td>Ultra high res RAW -> JPEG / YUV with seperate preview</td> </tr>
     * <tr> <td>{@code YUV / PRIV}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td> <td>{@code YUV / PRIV}</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td><td id="rb">{@code YUV / PRIV}</td><td id="rb">{@code DEFAULT}</td><td id="rb">{@code PREVIEW}</td><td id="rb">{@code JPEG }</td><td id="rb">{@code MAX_RES}</td><td id="rb">{@code MAX}</td> <td> Ultra high res PRIV / YUV -> YUV / JPEG reprocessing with seperate preview</td> </tr>
     * </table><br>
     * No additional mandatory stream combinations for RAW capability and LEVEL-3 hardware level.
     * </p>
     *
     * <h3>Constrained high-speed recording</h3>
     *
     * <p>The application can use a
     * {@link android.hardware.camera2.params.SessionConfiguration#SESSION_REGULAR
     * normal capture session}
     * for high speed capture if the desired high speed FPS ranges are advertised by
     * {@link CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES}, in which case all API
     * semantics associated with normal capture sessions applies.</p>
     *
     * <p>A
     * {@link android.hardware.camera2.params.SessionConfiguration#SESSION_HIGH_SPEED
     * high-speed capture session}
     * can be use for high speed video recording (>=120fps) when the camera device supports high
     * speed video capability (i.e., {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES}
     * contains {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO}).
     * A constrained high-speed capture session has special limitations compared with a normal
     * capture session:</p>
     *
     * <ul>
     *
     * <li>In addition to the output target Surface requirements specified above for regular
     *   captures, a high speed capture session will only support up to 2 output Surfaces, though
     *   the application might choose to configure just one Surface (e.g., preview only). All
     *   Surfaces must be either video encoder surfaces (acquired by
     *   {@link android.media.MediaRecorder#getSurface} or
     *   {@link android.media.MediaCodec#createInputSurface}) or preview surfaces (obtained from
     *   {@link android.view.SurfaceView}, {@link android.graphics.SurfaceTexture} via
     *   {@link android.view.Surface#Surface(android.graphics.SurfaceTexture)}). The Surface sizes
     *   must be one of the sizes reported by {@link StreamConfigurationMap#getHighSpeedVideoSizes}.
     *   When multiple Surfaces are configured, their size must be same.</li>
     *
     * <li>An active high speed capture session only accepts request lists created via
     *   {@link CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList}, and the
     *   request list can only be submitted to this session via
     *   {@link CameraCaptureSession#captureBurst captureBurst}, or
     *   {@link CameraCaptureSession#setRepeatingBurst setRepeatingBurst}.</li>
     *
     * <li>The FPS ranges being requested to this session must be selected from
     *   {@link StreamConfigurationMap#getHighSpeedVideoFpsRangesFor}. The application can still use
     *   {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE} to control the desired FPS range.
     *   Switching to an FPS range that has different
     *   {@link android.util.Range#getUpper() maximum FPS} may trigger some camera device
     *   reconfigurations, which may introduce extra latency. It is recommended that the
     *   application avoids unnecessary maximum target FPS changes as much as possible during high
     *   speed streaming.</li>
     *
     * <li>For the request lists submitted to this session, the camera device will override the
     *   {@link CaptureRequest#CONTROL_MODE control mode}, auto-exposure (AE), auto-white balance
     *   (AWB) and auto-focus (AF) to {@link CameraMetadata#CONTROL_MODE_AUTO},
     *   {@link CameraMetadata#CONTROL_AE_MODE_ON}, {@link CameraMetadata#CONTROL_AWB_MODE_AUTO}
     *   and {@link CameraMetadata#CONTROL_AF_MODE_CONTINUOUS_VIDEO}, respectively. All
     *   post-processing block mode controls will be overridden to be FAST. Therefore, no manual
     *   control of capture and post-processing parameters is possible. Beside these, only a subset
     *   of controls will work, see
     *   {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO} for
     *   more details.</li>
     *
     * </ul>
     *
     * @param config A session configuration (see {@link SessionConfiguration}).
     *
     * @throws IllegalArgumentException In case the session configuration is invalid; or the output
     *                                  configurations are empty; or the session configuration
     *                                  executor is invalid;
     *                                  or the output dynamic range combination is
     *                                  invalid/unsupported.
     * @throws CameraAccessException In case the camera device is no longer connected or has
     *                               encountered a fatal error.
     * @see #createCaptureSession(List, CameraCaptureSession.StateCallback, Handler)
     * @see #createCaptureSessionByOutputConfigurations
     * @see #createReprocessableCaptureSession
     * @see #createConstrainedHighSpeedCaptureSession
     * @see OutputConfiguration#setDynamicRangeProfile
     * @see android.hardware.camera2.params.DynamicRangeProfiles
     */
    public void createCaptureSession(
            SessionConfiguration config) throws CameraAccessException {
        throw new UnsupportedOperationException("No default implementation");
    }

    /**
     * <p>Create a {@link CaptureRequest.Builder} for new capture requests,
     * initialized with template for a target use case. The settings are chosen
     * to be the best options for the specific camera device, so it is not
     * recommended to reuse the same request for a different camera device;
     * create a builder specific for that device and template and override the
     * settings as desired, instead.</p>
     *
     * @param templateType An enumeration selecting the use case for this request. Not all template
     * types are supported on every device. See the documentation for each template type for
     * details.
     * @return a builder for a capture request, initialized with default
     * settings for that template, and no output streams
     *
     * @throws IllegalArgumentException if the templateType is not supported by
     * this device.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     */
    @NonNull
    public abstract CaptureRequest.Builder createCaptureRequest(@RequestTemplate int templateType)
            throws CameraAccessException;

    /**
     * <p>Create a {@link CaptureRequest.Builder} for new capture requests,
     * initialized with template for a target use case. This methods allows
     * clients to pass physical camera ids which can be used to customize the
     * request for a specific physical camera. The settings are chosen
     * to be the best options for the specific logical camera device. If
     * additional physical camera ids are passed, then they will also use the
     * same settings template. Clients can further modify individual camera
     * settings by calling {@link CaptureRequest.Builder#setPhysicalCameraKey}.</p>
     *
     * <p>Individual physical camera settings will only be honored for camera session
     * that was initialized with corresponding physical camera id output configuration
     * {@link OutputConfiguration#setPhysicalCameraId} and the same output targets are
     * also attached in the request by {@link CaptureRequest.Builder#addTarget}.</p>
     *
     * <p>The output is undefined for any logical camera streams in case valid physical camera
     * settings are attached.</p>
     *
     * @param templateType An enumeration selecting the use case for this request. Not all template
     * types are supported on every device. See the documentation for each template type for
     * details.
     * @param physicalCameraIdSet A set of physical camera ids that can be used to customize
     *                            the request for a specific physical camera.
     * @return a builder for a capture request, initialized with default
     * settings for that template, and no output streams
     *
     * @throws IllegalArgumentException if the templateType is not supported by
     * this device, or one of the physical id arguments matches with logical camera id.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see #TEMPLATE_PREVIEW
     * @see #TEMPLATE_RECORD
     * @see #TEMPLATE_STILL_CAPTURE
     * @see #TEMPLATE_VIDEO_SNAPSHOT
     * @see #TEMPLATE_MANUAL
     * @see CaptureRequest.Builder#setPhysicalCameraKey
     * @see CaptureRequest.Builder#getPhysicalCameraKey
     */
    @NonNull
    public CaptureRequest.Builder createCaptureRequest(@RequestTemplate int templateType,
            Set<String> physicalCameraIdSet) throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * <p>Create a {@link CaptureRequest.Builder} for a new reprocess {@link CaptureRequest} from a
     * {@link TotalCaptureResult}.
     *
     * <p>Each reprocess {@link CaptureRequest} processes one buffer from
     * {@link CameraCaptureSession}'s input {@link Surface} to all output {@link Surface Surfaces}
     * included in the reprocess capture request. The reprocess input images must be generated from
     * one or multiple output images captured from the same camera device. The application can
     * provide input images to camera device via {@link android.media.ImageWriter#queueInputImage}.
     * The application must use the capture result of one of those output images to create a
     * reprocess capture request so that the camera device can use the information to achieve
     * optimal reprocess image quality. For camera devices that support only 1 output
     * {@link Surface}, submitting a reprocess {@link CaptureRequest} with multiple
     * output targets will result in a {@link CaptureFailure}.
     *
     * From Android 14 onward, {@link CaptureRequest#CONTROL_CAPTURE_INTENT} will be set to
     * {@link CameraMetadata#CONTROL_CAPTURE_INTENT_STILL_CAPTURE} by default. Prior to Android 14,
     * apps will need to explicitly set this key themselves.
     *
     * @param inputResult The capture result of the output image or one of the output images used
     *                       to generate the reprocess input image for this capture request.
     *
     * @throws IllegalArgumentException if inputResult is null.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see CaptureRequest.Builder
     * @see TotalCaptureResult
     * @see CameraDevice#createCaptureSession(android.hardware.camera2.params.SessionConfiguration)
     * @see android.media.ImageWriter
     */
    @NonNull
    public abstract CaptureRequest.Builder createReprocessCaptureRequest(
            @NonNull TotalCaptureResult inputResult) throws CameraAccessException;

    /**
     * Close the connection to this camera device as quickly as possible.
     *
     * <p>Immediately after this call, all calls to the camera device or active session interface
     * will throw a {@link IllegalStateException}, except for calls to close(). Once the device has
     * fully shut down, the {@link StateCallback#onClosed} callback will be called, and the camera
     * is free to be re-opened.</p>
     *
     * <p>Immediately after this call, besides the final {@link StateCallback#onClosed} calls, no
     * further callbacks from the device or the active session will occur, and any remaining
     * submitted capture requests will be discarded, as if
     * {@link CameraCaptureSession#abortCaptures} had been called, except that no success or failure
     * callbacks will be invoked.</p>
     *
     */
    @Override
    public abstract void close();

    /**
     * Checks whether a particular {@link SessionConfiguration} is supported by the camera device.
     *
     * <p>This method performs a runtime check of a given {@link SessionConfiguration}. The result
     * confirms whether or not the passed session configuration can be successfully used to
     * create a camera capture session using
     * {@link CameraDevice#createCaptureSession(
     * android.hardware.camera2.params.SessionConfiguration)}.
     * </p>
     *
     * <p>The method can be called at any point before, during and after active capture session.
     * It must not impact normal camera behavior in any way and must complete significantly
     * faster than creating a regular or constrained capture session.</p>
     *
     * <p>Although this method is faster than creating a new capture session, it is not intended
     * to be used for exploring the entire space of supported stream combinations. The available
     * mandatory stream combinations
     * {@link android.hardware.camera2.params.MandatoryStreamCombination} are better suited for this
     * purpose.</p>
     *
     * <p>If this function returns {@code true} for a particular stream combination, the camera
     * device supports concurrent captures on all of the streams in the same CaptureRequest, with
     * two exceptions below where concurrent captures are not supported: </p>
     * <ul>
     * <li>Supported stream combinations with exclusive dynamic range profiles as specified by
     * {@link android.hardware.camera2.params.DynamicRangeProfiles#getProfileCaptureRequestConstraints}.</li>
     * <li>Supported combinations of 'default' mode and 'max resolution' mode streams for devices
     * with ULTRA_HIGH_RESOLUTION_SENSOR capability.</li>
     * </ul>
     * <p>For other cases where concurrent captures of a stream combination are not supported,
     * this function returns {@code false}.</p>
     *
     * <p><b>NOTE:</b></p>
     * <ul>
     * <li>For apps targeting {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} and above,
     * this method will automatically delegate to
     * {@link CameraDeviceSetup#isSessionConfigurationSupported} whenever possible. This
     * means that the output of this method will consider parameters set through
     * {@link SessionConfiguration#setSessionParameters} as well.</li>
     *
     * <li>Session Parameters will be ignored for apps targeting <=
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, or if
     * {@link CameraManager#isCameraDeviceSetupSupported} returns false for the camera id
     * associated with this {@code CameraDevice}.</li>
     * </ul>
     *
     * @return {@code true} if the given session configuration is supported by the camera device
     *         {@code false} otherwise.
     * @throws UnsupportedOperationException if the query operation is not supported by the camera
     *                                       device
     * @throws IllegalArgumentException if the session configuration is invalid, including, if it
     *                                  contains certain non-supported features queryable via
     *                                  CameraCharacteristics.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see CameraManager#isCameraDeviceSetupSupported(String)
     * @see CameraDeviceSetup#isSessionConfigurationSupported(SessionConfiguration)
     */
    public boolean isSessionConfigurationSupported(
            @NonNull SessionConfiguration sessionConfig) throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * A callback objects for receiving updates about the state of a camera device.
     *
     * <p>A callback instance must be provided to the {@link CameraManager#openCamera} method to
     * open a camera device.</p>
     *
     * <p>These state updates include notifications about the device completing startup (
     * allowing for {@link #createCaptureSession} to be called), about device
     * disconnection or closure, and about unexpected device errors.</p>
     *
     * <p>Events about the progress of specific {@link CaptureRequest CaptureRequests} are provided
     * through a {@link CameraCaptureSession.CaptureCallback} given to the
     * {@link CameraCaptureSession#capture}, {@link CameraCaptureSession#captureBurst},
     * {@link CameraCaptureSession#setRepeatingRequest}, or
     * {@link CameraCaptureSession#setRepeatingBurst} methods.
     *
     * @see CameraManager#openCamera
     */
    public static abstract class StateCallback {
       /**
         * An error code that can be reported by {@link #onError}
         * indicating that the camera device is in use already.
         *
         * <p>
         * This error can be produced when opening the camera fails due to the camera
        *  being used by a higher-priority camera API client.
         * </p>
         *
         * @see #onError
         */
        public static final int ERROR_CAMERA_IN_USE = 1;

        /**
         * An error code that can be reported by {@link #onError}
         * indicating that the camera device could not be opened
         * because there are too many other open camera devices.
         *
         * <p>
         * The system-wide limit for number of open cameras has been reached,
         * and more camera devices cannot be opened until previous instances are
         * closed.
         * </p>
         *
         * <p>
         * This error can be produced when opening the camera fails.
         * </p>
         *
         * @see #onError
         */
        public static final int ERROR_MAX_CAMERAS_IN_USE = 2;

        /**
         * An error code that can be reported by {@link #onError}
         * indicating that the camera device could not be opened due to a device
         * policy.
         *
         * @see android.app.admin.DevicePolicyManager#setCameraDisabled(android.content.ComponentName, boolean)
         * @see #onError
         */
        public static final int ERROR_CAMERA_DISABLED = 3;

       /**
         * An error code that can be reported by {@link #onError}
         * indicating that the camera device has encountered a fatal error.
         *
         * <p>The camera device needs to be re-opened to be used again.</p>
         *
         * @see #onError
         */
        public static final int ERROR_CAMERA_DEVICE = 4;

        /**
         * An error code that can be reported by {@link #onError}
         * indicating that the camera service has encountered a fatal error.
         *
         * <p>The Android device may need to be shut down and restarted to restore
         * camera function, or there may be a persistent hardware problem.</p>
         *
         * <p>An attempt at recovery <i>may</i> be possible by closing the
         * CameraDevice and the CameraManager, and trying to acquire all resources
         * again from scratch.</p>
         *
         * @see #onError
         */
        public static final int ERROR_CAMERA_SERVICE = 5;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"ERROR_"}, value =
            {ERROR_CAMERA_IN_USE,
             ERROR_MAX_CAMERAS_IN_USE,
             ERROR_CAMERA_DISABLED,
             ERROR_CAMERA_DEVICE,
             ERROR_CAMERA_SERVICE })
        public @interface ErrorCode {};

        /**
         * The method called when a camera device has finished opening.
         *
         * <p>At this point, the camera device is ready to use, and
         * {@link CameraDevice#createCaptureSession} can be called to set up the first capture
         * session.</p>
         *
         * @param camera the camera device that has become opened
         */
        public abstract void onOpened(@NonNull CameraDevice camera); // Must implement

        /**
         * The method called when a camera device has finished opening in shared mode,
         * where there can be more than one client accessing the same camera.
         *
         * <p>At this point, the camera device is ready to use, and
         * {@link CameraDevice#createCaptureSession} can be called to set up the shared capture
         * session.</p>
         *
         * @param camera the camera device that has become opened
         * @param isPrimaryClient true if the client opening the camera is currently the primary
         *                             client.
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_CAMERA_MULTI_CLIENT)
        public void onOpenedInSharedMode(@NonNull CameraDevice camera, boolean isPrimaryClient) {
          // Default empty implementation
        }

        /**
         * The method called when client access priorities have changed for a camera device opened
         * in shared mode where there can be more than one client accessing the same camera.
         *
         * If the client priority changed from secondary to primary, then it can now
         * create capture request and change the capture request parameters. If client priority
         * changed from primary to secondary, that implies that a higher priority client has also
         * opened the camera in shared mode and the new client is now a primary client
         *
         * @param camera the camera device whose access priorities have changed.
         * @param isPrimaryClient true if the client is now the primary client.
         *                        false if another higher priority client also opened the
         *                        camera and is now the new primary client and this client is
         *                        now a secondary client.
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_CAMERA_MULTI_CLIENT)
        public void onClientSharedAccessPriorityChanged(@NonNull CameraDevice camera,
                boolean isPrimaryClient) {
          // Default empty implementation
        }

        /**
         * The method called when a camera device has been closed with
         * {@link CameraDevice#close}.
         *
         * <p>Any attempt to call methods on this CameraDevice in the
         * future will throw a {@link IllegalStateException}.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param camera the camera device that has become closed
         */
        public void onClosed(@NonNull CameraDevice camera) {
            // Default empty implementation
        }

        /**
         * The method called when a camera device is no longer available for
         * use.
         *
         * <p>This callback may be called instead of {@link #onOpened}
         * if opening the camera fails.</p>
         *
         * <p>Any attempt to call methods on this CameraDevice will throw a
         * {@link CameraAccessException}. The disconnection could be due to a
         * change in security policy or permissions; the physical disconnection
         * of a removable camera device; or the camera being needed for a
         * higher-priority camera API client.</p>
         *
         * <p>There may still be capture callbacks that are invoked
         * after this method is called, or new image buffers that are delivered
         * to active outputs.</p>
         *
         * <p>The default implementation logs a notice to the system log
         * about the disconnection.</p>
         *
         * <p>You should clean up the camera with {@link CameraDevice#close} after
         * this happens, as it is not recoverable until the camera can be opened
         * again. For most use cases, this will be when the camera again becomes
         * {@link CameraManager.AvailabilityCallback#onCameraAvailable available}.
         * </p>
         *
         * @param camera the device that has been disconnected
         */
        public abstract void onDisconnected(@NonNull CameraDevice camera); // Must implement

        /**
         * The method called when a camera device has encountered a serious error.
         *
         * <p>This callback may be called instead of {@link #onOpened}
         * if opening the camera fails.</p>
         *
         * <p>This indicates a failure of the camera device or camera service in
         * some way. Any attempt to call methods on this CameraDevice in the
         * future will throw a {@link CameraAccessException} with the
         * {@link CameraAccessException#CAMERA_ERROR CAMERA_ERROR} reason.
         * </p>
         *
         * <p>There may still be capture completion or camera stream callbacks
         * that will be called after this error is received.</p>
         *
         * <p>You should clean up the camera with {@link CameraDevice#close} after
         * this happens. Further attempts at recovery are error-code specific.</p>
         *
         * @param camera The device reporting the error
         * @param error The error code.
         *
         * @see #ERROR_CAMERA_IN_USE
         * @see #ERROR_MAX_CAMERAS_IN_USE
         * @see #ERROR_CAMERA_DISABLED
         * @see #ERROR_CAMERA_DEVICE
         * @see #ERROR_CAMERA_SERVICE
         */
        public abstract void onError(@NonNull CameraDevice camera,
                @ErrorCode int error); // Must implement
    }

    /**
     * CameraDeviceSetup is a limited representation of {@link CameraDevice} that can be used to
     * query device specific information which would otherwise need a CameraDevice instance.
     * This class can be constructed without calling {@link CameraManager#openCamera} and paying
     * the latency cost of CameraDevice creation. Use {@link CameraManager#getCameraDeviceSetup}
     * to get an instance of this class.
     *
     * <p>Can only be instantiated for camera devices for which
     * {@link CameraManager#isCameraDeviceSetupSupported} returns true.</p>
     *
     * @see CameraManager#isCameraDeviceSetupSupported(String)
     * @see CameraManager#getCameraDeviceSetup(String)
     */
    @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
    public abstract static class CameraDeviceSetup {
        /**
         * Create a {@link CaptureRequest.Builder} for new capture requests,
         * initialized with a template for target use case.
         *
         * <p>The settings are chosen to be the best options for the specific camera device,
         * so it is not recommended to reuse the same request for a different camera device;
         * create a builder specific for that device and template and override the
         * settings as desired, instead.</p>
         *
         * <p>Supported if {@link CameraCharacteristics#INFO_SESSION_CONFIGURATION_QUERY_VERSION}
         * is at least {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}. If less or equal to
         * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, this function throws an
         * {@link UnsupportedOperationException}.</p>
         *
         * @param templateType An enumeration selecting the use case for this request. Not all
         *                     template types are supported on every device. See the documentation
         *                     for each template type for details.
         *
         * @return a builder for a capture request, initialized with default settings for that
         * template, and no output streams
         *
         * @throws CameraAccessException if the querying the camera device failed or there has been
         * a fatal error
         * @throws IllegalArgumentException if the templateType is not supported by this device
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
        public abstract CaptureRequest.Builder createCaptureRequest(
                @RequestTemplate int templateType) throws CameraAccessException;

        /**
         * Checks whether a particular {@link SessionConfiguration} is supported by the camera
         * device.
         *
         * <p>This method performs a runtime check of a given {@link SessionConfiguration}. The
         * result confirms whether or not the {@code SessionConfiguration}, <b>including the
         * parameters specified via {@link SessionConfiguration#setSessionParameters}</b>, can
         * be used to create a camera capture session using
         * {@link CameraDevice#createCaptureSession(SessionConfiguration)}.</p>
         *
         * <p>This method is supported if the
         * {@link CameraCharacteristics#INFO_SESSION_CONFIGURATION_QUERY_VERSION}
         * is at least {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}. If less or equal
         * to {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, this function throws
         * {@link UnsupportedOperationException}.</p>
         *
         * <p>Although this method is much faster than creating a new capture session, it can still
         * take a few milliseconds per call. Applications should therefore not use this method to
         * explore the entire space of supported session combinations.</p>
         *
         * <p>Instead, applications should use this method to query whether combinations of
         * certain features are supported. {@link
         * CameraCharacteristics#INFO_SESSION_CONFIGURATION_QUERY_VERSION} provides the list of
         * feature combinations the camera device will reliably report.</p>
         *
         * <p>If this function returns {@code true} for a particular stream combination, the camera
         * device supports concurrent captures on all of the streams in the same CaptureRequest,
         * with two exceptions below where concurrent captures are not supported: </p>
         * <ul>
         * <li>Supported stream combinations with exclusive dynamic range profiles as specified by
         * {@link android.hardware.camera2.params.DynamicRangeProfiles#getProfileCaptureRequestConstraints}.</li>
         * <li>Supported combinations of 'default' mode and 'max resolution' mode streams for
         * devices with ULTRA_HIGH_RESOLUTION_SENSOR capability.</li>
         * </ul>
         * <p>For other cases where concurrent captures of a stream combination are not supported,
         * this function returns {@code false}.</p>
         *
         * <p><b>IMPORTANT:</b></p>
         * <ul>
         * <li>If feature support can be queried via {@link CameraCharacteristics}, applications
         * should directly use that route rather than calling this function as: (1) using
         * {@code CameraCharacteristics} is more efficient, and (2) querying a feature explicitly
         * deemed unsupported by CameraCharacteristics may throw a
         * {@link IllegalArgumentException}.</li>
         *
         * <li>To minimize {@link SessionConfiguration} creation latency due to its dependency on
         * output surfaces, the application can call this method before acquiring valid
         * {@link android.view.SurfaceView}, {@link android.graphics.SurfaceTexture},
         * {@link android.media.MediaRecorder}, {@link android.media.MediaCodec}, or {@link
         * android.media.ImageReader} surfaces. For {@link android.view.SurfaceView},
         * {@link android.graphics.SurfaceTexture}, {@link android.media.MediaRecorder}, and
         * {@link android.media.MediaCodec}, the application can call
         * {@link OutputConfiguration#OutputConfiguration(Size, Class)}. For {@link
         * android.media.ImageReader}, the application can call {@link
         * OutputConfiguration#OutputConfiguration(int, Size)}, {@link
         * OutputConfiguration#OutputConfiguration(int, int, Size)}, {@link
         * OutputConfiguration#OutputConfiguration(int, Size, long)}, or {@link
         * OutputConfiguration#OutputConfiguration(int, int, Size, long)}. The {@link
         * SessionConfiguration} can then be created using the OutputConfiguration objects and
         * be used to query whether it's supported by the camera device. To create the
         * CameraCaptureSession, the application still needs to make sure all output surfaces
         * are added via {@link OutputConfiguration#addSurface} with the exception of deferred
         * surfaces for {@link android.view.SurfaceView} and
         * {@link android.graphics.SurfaceTexture}.</li>
         * </ul>
         *
         * @return {@code true} if the given session configuration is supported by the camera
         * device, {@code false} otherwise.
         *
         * @throws CameraAccessException if the camera device is no longer connected or has
         * encountered a fatal error
         * @throws IllegalArgumentException if the session configuration is invalid, including,
         * if it contains certain non-supported features queryable via CameraCharacteristics.
         *
         * @see CameraCharacteristics#INFO_SESSION_CONFIGURATION_QUERY_VERSION
         * @see SessionConfiguration
         * @see android.media.ImageReader
         */
        @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
        public abstract boolean isSessionConfigurationSupported(
                @NonNull SessionConfiguration config) throws CameraAccessException;

        /**
         * Get camera characteristics for a particular session configuration for this camera
         * device.
         *
         * <p>The camera characteristics returned by this method are different from those returned
         * from {@link CameraManager#getCameraCharacteristics}. The characteristics returned here
         * reflect device capabilities more accurately if the device were to be configured with
         * {@code sessionConfig}. The keys that may get updated are listed in
         * {@link CameraCharacteristics#getAvailableSessionCharacteristicsKeys}.</p>
         *
         * <p>Other than that, the characteristics returned here can be used in the same way as
         * those returned from {@link CameraManager#getCameraCharacteristics}.</p>
         *
         * <p>To optimize latency, the application can call this method before acquiring valid
         * {@link android.view.SurfaceView}, {@link android.graphics.SurfaceTexture},
         * {@link android.media.MediaRecorder}, {@link android.media.MediaCodec}, or {@link
         * android.media.ImageReader} surfaces. For {@link android.view.SurfaceView},
         * {@link android.graphics.SurfaceTexture}, {@link android.media.MediaRecorder}, and
         * {@link android.media.MediaCodec}, the application can call
         * {@link OutputConfiguration#OutputConfiguration(Size, Class)}. For {@link
         * android.media.ImageReader}, the application can call {@link
         * OutputConfiguration#OutputConfiguration(int, Size)}, {@link
         * OutputConfiguration#OutputConfiguration(int, int, Size)}, {@link
         * OutputConfiguration#OutputConfiguration(int, Size, long)}, or {@link
         * OutputConfiguration#OutputConfiguration(int, int, Size, long)}. The {@link
         * SessionConfiguration} can then be created using the OutputConfiguration objects and
         * be used for this function. To create the CameraCaptureSession, the application still
         * needs to make sure all output surfaces are added via {@link
         * OutputConfiguration#addSurface} with the exception of deferred surfaces for {@link
         * android.view.SurfaceView} and {@link android.graphics.SurfaceTexture}.</p>
         *
         * @param sessionConfig The session configuration for which characteristics are fetched.
         * @return CameraCharacteristics specific to a given session configuration.
         *
         * @throws IllegalArgumentException if the session configuration is invalid or if
         *                                  {@link #isSessionConfigurationSupported} returns
         *                                  {@code false} for the provided
         *                                  {@link SessionConfiguration}
         * @throws CameraAccessException    if the camera device is no longer connected or has
         *                                  encountered a fatal error
         *
         * @see CameraCharacteristics#getAvailableSessionCharacteristicsKeys
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
        public abstract CameraCharacteristics getSessionCharacteristics(
                @NonNull SessionConfiguration sessionConfig) throws CameraAccessException;

        /**
         * Utility function to forward the call to
         * {@link CameraManager#openCamera(String, Executor, StateCallback)}. This function simply
         * calls {@code CameraManager.openCamera} for the cameraId for which this class was
         * constructed. All semantics are consistent with {@code CameraManager.openCamera}.
         *
         * @param executor The executor which will be used when invoking the callback.
         * @param callback The callback which is invoked once the camera is opened
         *
         * @throws CameraAccessException if the camera is disabled by device policy,
         * has been disconnected, or is being used by a higher-priority camera API client.
         *
         * @throws IllegalArgumentException if cameraId, the callback or the executor was null,
         * or the cameraId does not match any currently or previously available
         * camera device.
         *
         * @throws SecurityException if the application does not have permission to
         * access the camera
         *
         * @see CameraManager#openCamera(String, Executor, StateCallback)
         */
        @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
        @RequiresPermission(android.Manifest.permission.CAMERA)
        public abstract void openCamera(@NonNull @CallbackExecutor Executor executor,
                @NonNull StateCallback callback) throws CameraAccessException;

        /**
         * Get the ID of this camera device.
         *
         * <p>This matches the ID given to {@link CameraManager#getCameraDeviceSetup} to instantiate
         * this object.</p>
         *
         * @return the ID for this camera device
         *
         * @see CameraManager#getCameraIdList
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
        public abstract String getId();

        /**
         * To be implemented by camera2 classes only.
         * @hide
         */
        public CameraDeviceSetup() {}
    }

    /**
     * Set audio restriction mode when this CameraDevice is being used.
     *
     * <p>Some camera hardware (e.g. devices with optical image stabilization support)
     * are sensitive to device vibration and video recordings can be ruined by unexpected sounds.
     * Applications can use this method to suppress vibration or sounds coming from
     * ringtones, alarms or notifications.
     * Other vibration or sounds (e.g. media playback or accessibility) will not be muted.</p>
     *
     * <p>The mute mode is a system-wide setting. When multiple CameraDevice objects
     * are setting different modes, the system will pick a the mode that's union of
     * all modes set by CameraDevice. Applications can also use
     * {@link #getCameraAudioRestriction} to query current system-wide camera
     * mute mode in effect.</p>
     *
     * <p>The mute settings from this CameraDevice will be automatically removed when the
     * CameraDevice is closed or the application is disconnected from the camera.</p>
     *
     * @param mode An enumeration selecting the audio restriction mode for this camera device.
     *
     * @throws IllegalArgumentException if the mode is not supported
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see #getCameraAudioRestriction
     */
    public void setCameraAudioRestriction(
            @CAMERA_AUDIO_RESTRICTION int mode) throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Get currently applied global camera audio restriction mode.
     *
     * <p>Application can use this method to retrieve the system-wide camera audio restriction
     * settings described in {@link #setCameraAudioRestriction}.</p>
     *
     * @return The current system-wide mute mode setting in effect
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see #setCameraAudioRestriction
     */
    public @CAMERA_AUDIO_RESTRICTION int getCameraAudioRestriction() throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * To be inherited by android.hardware.camera2.* code only.
     * @hide
     */
    public CameraDevice() {}
}
