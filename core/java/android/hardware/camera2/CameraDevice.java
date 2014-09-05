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

import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.os.Handler;
import android.view.Surface;

import java.util.List;

/**
 * <p>The CameraDevice class is a representation of a single camera connected to an
 * Android device, allowing for fine-grain control of image capture and
 * post-processing at high frame rates.</p>
 *
 * <p>Your application must declare the
 * {@link android.Manifest.permission#CAMERA Camera} permission in its manifest
 * in order to access camera devices.</p>
 *
 * <p>A given camera device may provide support at one of two levels: limited or
 * full. If a device only supports the limited level, then Camera2 exposes a
 * feature set that is roughly equivalent to the older
 * {@link android.hardware.Camera Camera} API, although with a cleaner and more
 * efficient interface.  Devices that implement the full level of support
 * provide substantially improved capabilities over the older camera
 * API. Applications that target the limited level devices will run unchanged on
 * the full-level devices; if your application requires a full-level device for
 * proper operation, declare the "android.hardware.camera2.full" feature in your
 * manifest.</p>
 *
 * @see CameraManager#openCamera
 * @see android.Manifest.permission#CAMERA
 */
public abstract class CameraDevice implements AutoCloseable {

    /**
     * Create a request suitable for a camera preview window. Specifically, this
     * means that high frame rate is given priority over the highest-quality
     * post-processing. These requests would normally be used with the
     * {@link CameraCaptureSession#setRepeatingRequest} method.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_PREVIEW = 1;

    /**
     * Create a request suitable for still image capture. Specifically, this
     * means prioritizing image quality over frame rate. These requests would
     * commonly be used with the {@link CameraCaptureSession#capture} method.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_STILL_CAPTURE = 2;

    /**
     * Create a request suitable for video recording. Specifically, this means
     * that a stable frame rate is used, and post-processing is set for
     * recording quality. These requests would commonly be used with the
     * {@link CameraCaptureSession#setRepeatingRequest} method.
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
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_VIDEO_SNAPSHOT = 4;

    /**
     * Create a request suitable for zero shutter lag still capture. This means
     * means maximizing image quality without compromising preview frame rate.
     * AE/AWB/AF should be on auto mode.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_ZERO_SHUTTER_LAG = 5;

    /**
     * A basic template for direct application control of capture
     * parameters. All automatic control is disabled (auto-exposure, auto-white
     * balance, auto-focus), and post-processing parameters are set to preview
     * quality. The manual capture parameters (exposure, sensitivity, and so on)
     * are set to reasonable defaults, but should be overriden by the
     * application depending on the intended use case.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_MANUAL = 6;

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
    public abstract String getId();

    /**
     * <p>Create a new camera capture session by providing the target output set of Surfaces to the
     * camera device.</p>
     *
     * <p>The active capture session determines the set of potential output Surfaces for
     * the camera device for each capture request. A given request may use all
     * or a only some of the outputs. Once the CameraCaptureSession is created, requests can be
     * can be submitted with {@link CameraCaptureSession#capture capture},
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
     *   Surface is {@link android.view.SurfaceHolder.Callback#surfaceCreated created}, set the
     *   size of the Surface with {@link android.view.SurfaceHolder#setFixedSize} to be one of the
     *   sizes returned by
     *   {@link StreamConfigurationMap#getOutputSizes(Class) getOutputSizes(SurfaceHolder.class)}
     *   and then obtain the Surface by calling {@link android.view.SurfaceHolder#getSurface}.</li>
     *
     * <li>For accessing through an OpenGL texture via a
     *   {@link android.graphics.SurfaceTexture SurfaceTexture}: Set the size of
     *   the SurfaceTexture with
     *   {@link android.graphics.SurfaceTexture#setDefaultBufferSize} to be one
     *   of the sizes returned by
     *   {@link StreamConfigurationMap#getOutputSizes(Class) getOutputSizes(SurfaceTexture.class)}
     *   before creating a Surface from the SurfaceTexture with
     *   {@link Surface#Surface}.</li>
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
     * <li>For efficient YUV processing with {@link android.renderscript}:
     *   Create a RenderScript
     *   {@link android.renderscript.Allocation Allocation} with a supported YUV
     *   type, the IO_INPUT flag, and one of the sizes returned by
     *   {@link StreamConfigurationMap#getOutputSizes(Class) getOutputSizes(Allocation.class)},
     *   Then obtain the Surface with
     *   {@link android.renderscript.Allocation#getSurface}.</li>
     *
     * <li>For access to raw, uncompressed JPEG data in the application: Create an
     *   {@link android.media.ImageReader} object with one of the supported output formats given by
     *   {@link StreamConfigurationMap#getOutputFormats()}, setting its size to one of the
     *   corresponding supported sizes by passing the chosen output format into
     *   {@link StreamConfigurationMap#getOutputSizes(int)}. Then obtain a
     *   {@link android.view.Surface} from it with {@link android.media.ImageReader#getSurface()}.
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
     * <p>If a prior CameraCaptureSession already exists when a new one is created, the previous
     * session is closed. Any in-progress capture requests made on the prior session will be
     * completed before the new session is configured and is able to start capturing its own
     * requests. To minimize the transition time, the {@link CameraCaptureSession#abortCaptures}
     * call can be used to discard the remaining requests for the prior capture session before a new
     * one is created. Note that once the new session is created, the old one can no longer have its
     * captures aborted.</p>
     *
     * <p>Using larger resolution outputs, or more outputs, can result in slower
     * output rate from the device.</p>
     *
     * <p>Configuring a session with an empty or null list will close the current session, if
     * any. This can be used to release the current session's target surfaces for another use.</p>
     *
     * <p>While any of the sizes from {@link StreamConfigurationMap#getOutputSizes} can be used when
     * a single output stream is configured, a given camera device may not be able to support all
     * combination of sizes, formats, and targets when multiple outputs are configured at once.  The
     * tables below list the maximum guaranteed resolutions for combinations of streams and targets,
     * given the capabilities of the camera device.</p>
     *
     * <p>If an application tries to create a session using a set of targets that exceed the limits
     * described in the below tables, one of three possibilities may occur. First, the session may
     * be successfully created and work normally. Second, the session may be successfully created,
     * but the camera device won't meet the frame rate guarantees as described in
     * {@link StreamConfigurationMap#getOutputMinFrameDuration}. Or third, if the output set
     * cannot be used at all, session creation will fail entirely, with
     * {@link CameraCaptureSession.StateListener#onConfigureFailed} being invoked.</p>
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
     * <p>Legacy devices ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY LEGACY}) support at
     * least the following stream combinations:
     *
     * <table>
     * <tr><th colspan="7">LEGACY-level guaranteed configurations</th></tr>
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
     * <p>Limited-capability ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED}) devices
     * support at least the following stream combinations in addition to those for
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY LEGACY} devices:
     *
     * <table>
     * <tr><th colspan="7">LIMITED-level additional guaranteed configurations</th></tr>
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
     * <p>FULL-capability ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}
     * {@code == }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL}) devices
     * support at least the following stream combinations in addition to those for
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED} devices:
     *
     * <table>
     * <tr><th colspan="7">FULL-capability additional guaranteed configurations</th></tr>
     * <tr><th colspan="2" id="rb">Target 1</th><th colspan="2" id="rb">Target 2</th><th colspan="2" id="rb">Target 3</th> <th rowspan="2">Sample use case(s)</th> </tr>
     * <tr><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th><th>Type</th><th id="rb">Max size</th> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>Maximum-resolution GPU processing with preview.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>Maximum-resolution in-app processing with preview.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td colspan="2" id="rb"></td> <td>Maximum-resolution two-input in-app processsing.</td> </tr>
     * <tr> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code JPEG}</td><td id="rb">{@code MAXIMUM}</td> <td>Video recording with maximum-size video snapshot</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code 640x480}</td> <td>{@code PRIV}</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td>Standard video recording plus maximum-resolution in-app processing.</td> </tr>
     * <tr> <td>{@code YUV }</td><td id="rb">{@code 640x480}</td> <td>{@code YUV }</td><td id="rb">{@code PREVIEW}</td> <td>{@code YUV }</td><td id="rb">{@code MAXIMUM}</td> <td>Preview plus two-input maximum-resolution in-app processing.</td> </tr>
     * </table><br>
     * </p>
     *
     * <p>RAW-capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES} includes
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_RAW RAW}) devices additionally support
     * at least the following stream combinations on both
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL} and
     * {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED} devices:
     *
     * <table>
     * <tr><th colspan="7">RAW-capability additional guaranteed configurations</th></tr>
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
     * <p>Since the capabilities of camera devices vary greatly, a given camera device may support
     * target combinations with sizes outside of these guarantees, but this can only be tested for
     * by attempting to create a session with such targets.</p>
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
     */
    public abstract void createCaptureSession(List<Surface> outputs,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException;

    /**
     * <p>Create a {@link CaptureRequest.Builder} for new capture requests,
     * initialized with template for a target use case. The settings are chosen
     * to be the best options for the specific camera device, so it is not
     * recommended to reuse the same request for a different camera device;
     * create a builder specific for that device and template and override the
     * settings as desired, instead.</p>
     *
     * @param templateType An enumeration selecting the use case for this
     * request; one of the CameraDevice.TEMPLATE_ values.
     * @return a builder for a capture request, initialized with default
     * settings for that template, and no output streams
     *
     * @throws IllegalArgumentException if the templateType is not in the list
     * of supported templates.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see #TEMPLATE_PREVIEW
     * @see #TEMPLATE_RECORD
     * @see #TEMPLATE_STILL_CAPTURE
     * @see #TEMPLATE_VIDEO_SNAPSHOT
     * @see #TEMPLATE_MANUAL
     */
    public abstract CaptureRequest.Builder createCaptureRequest(int templateType)
            throws CameraAccessException;

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
         * This error can be produced when opening the camera fails.
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

        /**
         * The method called when a camera device has finished opening.
         *
         * <p>At this point, the camera device is ready to use, and
         * {@link CameraDevice#createCaptureSession} can be called to set up the first capture
         * session.</p>
         *
         * @param camera the camera device that has become opened
         */
        public abstract void onOpened(CameraDevice camera); // Must implement

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
        public void onClosed(CameraDevice camera) {
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
         * higher-priority use case.</p>
         *
         * <p>There may still be capture callbacks that are invoked
         * after this method is called, or new image buffers that are delivered
         * to active outputs.</p>
         *
         * <p>The default implementation logs a notice to the system log
         * about the disconnection.</p>
         *
         * <p>You should clean up the camera with {@link CameraDevice#close} after
         * this happens, as it is not recoverable until opening the camera again
         * after it becomes {@link CameraManager.AvailabilityCallback#onCameraAvailable available}.
         * </p>
         *
         * @param camera the device that has been disconnected
         */
        public abstract void onDisconnected(CameraDevice camera); // Must implement

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
         * @param error The error code, one of the
         *     {@code StateCallback.ERROR_*} values.
         *
         * @see #ERROR_CAMERA_DEVICE
         * @see #ERROR_CAMERA_SERVICE
         * @see #ERROR_CAMERA_DISABLED
         * @see #ERROR_CAMERA_IN_USE
         */
        public abstract void onError(CameraDevice camera, int error); // Must implement
    }

    /**
     * Temporary for migrating to Callback naming
     * @hide
     */
    public static abstract class StateListener extends StateCallback {
    }

    /**
     * To be inherited by android.hardware.camera2.* code only.
     * @hide
     */
    public CameraDevice() {}
}
