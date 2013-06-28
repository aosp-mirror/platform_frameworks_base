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

package android.hardware.photography;

import android.view.Surface;

import java.lang.AutoCloseable;
import java.util.List;

/**
 * <p>The CameraDevice class is an interface to a single camera connected to an
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
public interface CameraDevice extends AutoCloseable {

    /**
     * Create a request suitable for a camera preview window. Specifically, this
     * means that high frame rate is given priority over the highest-quality
     * post-processing. These requests would normally be used with the
     * {@link #setRepeatingRequest} method.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_PREVIEW = 1;

    /**
     * Create a request suitable for still image capture. Specifically, this
     * means prioritizing image quality over frame rate. These requests would
     * commonly be used with the {@link #capture} method.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_STILL_CAPTURE = 2;

    /**
     * Create a request suitable for video recording. Specifically, this means
     * that a stable frame rate is used, and post-processing is set for
     * recording quality. These requests would commonly be used with the
     * {@link #setRepeatingRequest} method.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_RECORD  = 3;

    /**
     * Create a request suitable for still image capture while recording
     * video. Specifically, this means maximizing image quality without
     * disrupting the ongoing recording. These requests would commonly be used
     * with the {@link #capture} method while a request based on
     * {@link #TEMPLATE_RECORD} is is in use with {@link #setRepeatingRequest}.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_VIDEO_SNAPSHOT = 4;

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
    public static final int TEMPLATE_MANUAL = 5;

    /**
     * Get the static properties for this camera. These are identical to the
     * properties returned by {@link CameraManager#getCameraProperties}.
     *
     * @return the static properties of the camera.
     *
     * @throws CameraAccessException if the camera device is no longer connected
     *
     * @see CameraManager#getCameraProperties
     */
    public CameraProperties getProperties() throws CameraAccessException;
    /**
     * <p>Set up a new output set of Surfaces for the camera device.</p>
     *
     * <p>The configuration determines the set of potential output Surfaces for
     * the camera device for each capture request. A given request may use all
     * or a only some of the outputs. This method must be called before requests
     * can be submitted to the camera with {@link #capture capture},
     * {@link #captureBurst captureBurst},
     * {@link #setRepeatingRequest setRepeatingRequest}, or
     * {@link #setRepeatingBurst setRepeatingBurst}.</p>
     *
     * <p>Surfaces suitable for inclusion as a camera output can be created for
     * various use cases and targets:</p>
     *
     * <ul>
     *
     * <li>For drawing to a {@link android.view.SurfaceView SurfaceView}: Set
     *   the size of the Surface with
     *   {@link android.view.SurfaceHolder#setFixedSize} to be one of the
     *   supported
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed sizes}
     *   before calling {@link android.view.SurfaceHolder#getSurface}.</li>
     *
     * <li>For accessing through an OpenGL texture via a
     *   {@link android.graphics.SurfaceTexture SurfaceTexture}: Set the size of
     *   the SurfaceTexture with
     *   {@link android.graphics.SurfaceTexture#setDefaultBufferSize} to be one
     *   of the supported
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed sizes}
     *   before creating a Surface from the SurfaceTexture with
     *   {@link Surface#Surface}.</li>
     *
     * <li>For recording with {@link android.media.MediaCodec}: Call
     *   {@link android.media.MediaCodec#createInputSurface} after configuring
     *   the media codec to use one of the
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed sizes}
     *   </li>
     *
     * <li>For recording with {@link android.media.MediaRecorder}: TODO</li>
     *
     * <li>For efficient YUV processing with {@link android.renderscript}:
     *   Create a RenderScript
     *   {@link android.renderscript.Allocation Allocation} with a supported YUV
     *   type, the IO_INPUT flag, and one of the supported
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed sizes}. Then
     *   obtain the Surface with
     *   {@link android.renderscript.Allocation#getSurface}.</li>
     *
     * <li>For access to uncompressed, JPEG, or raw sensor data in the
     *   application: Create a {@link android.media.ImageReader} object with the
     *   desired {@link CameraProperties#SCALER_AVAILABLE_FORMATS image format},
     *   and a size from the matching
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed},
     *   {@link CameraProperties#SCALER_AVAILABLE_JPEG_SIZES jpeg}, or
     *   {@link CameraProperties#SCALER_AVAILABLE_RAW_SIZES raw} sizes. Then
     *   obtain a Surface from it.</li>
     *
     * </ul>
     *
     * </p>
     *
     * <p>This function can take several hundred milliseconds to execute, since
     * camera hardware may need to be powered on or reconfigured.</p>
     *
     * <p>To change the configuration after requests have been submitted to the
     * device, the camera device must be idle. To idle the device, stop any
     * repeating requests with {@link #stopRepeating stopRepeating}, and then
     * call {@link #waitUntilIdle waitUntilIdle}.</p>
     *
     * <p>Using larger resolution outputs, or more outputs, can result in slower
     * output rate from the device.</p>
     *
     * @param outputs the new set of Surfaces that should be made available as
     * targets for captured image data.
     *
     * @throws IllegalArgumentException if the set of output Surfaces do not
     * meet the requirements
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device is not idle, or has
     * encountered a fatal error
     */
    public void configureOutputs(List<Surface> outputs) throws CameraAccessException;

    /**
     * <p>Create a {@link CaptureRequest} initialized with template for a target
     * use case. The settings are chosen to be the best options for the specific
     * camera device, so it is not recommended to reuse the same request for a
     * different camera device; create a request for that device and override
     * the settings as desired, instead.</p>
     *
     * @param templateType An enumeration selecting the use case for this
     * request; one of the CameraDevice.TEMPLATE_ values.
     * @return a filled-in CaptureRequest, except for output streams.
     *
     * @throws IllegalArgumentException if the templateType is not in the list
     * of supported templates.
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     *
     * @see #TEMPLATE_PREVIEW
     * @see #TEMPLATE_RECORD
     * @see #TEMPLATE_STILL_CAPTURE
     * @see #TEMPLATE_VIDEO_SNAPSHOT
     * @see #TEMPLATE_MANUAL
     */
    public CaptureRequest createCaptureRequest(int templateType)
            throws CameraAccessException;

    /**
     * <p>Submit a request for an image to be captured by this CameraDevice.</p>
     *
     * <p>The request defines all the parameters for capturing the single image,
     * including sensor, lens, flash, and post-processing settings.</p>
     *
     * <p>Each request will produce one {@link CaptureResult} and produce new
     * frames for one or more target Surfaces, as defined by the request's .</p>
     *
     * <p>Multiple requests can be in progress at once. They are processed in
     * first-in, first-out order, with minimal delays between each
     * capture. Requests submitted through this method have higher priority than
     * those submitted through {@link #setRepeatingRequest} or
     * {@link #setRepeatingBurst}, and will be processed as soon as the current
     * repeat/repeatBurst processing completes.</p>
     *
     * @param request the settings for this capture.
     * @param listener the callback object to notify once this request has been
     * processed. If null, no metadata will be produced for this capture,
     * although image data will still be produced.
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     *
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     */
    public void capture(CaptureRequest request, CaptureListener listener)
            throws CameraAccessException;

    /**
     * <p>Submit a list of requests to be captured in sequence as a burst. The
     * burst will be captured in the minimum amount of time possible, and will
     * not be interleaved with requests submitted by other capture or repeat
     * calls.</p>
     *
     * <p>The requests will be captured in order, each capture producing one
     * {@link CaptureResult} and frames for one or more
     * target {@link android.view.Surface surfaces}.</p>
     *
     * <p>The main difference between this method and simply calling
     * {@link #capture} repeatedly is that this method guarantees that no
     * other requests will be interspersed with the burst.</p>
     *
     * @param requests the list of settings for this burst capture.
     * @param listener the callback object to notify each time one of the
     * requests in the burst has been processed. If null, no metadata will be
     * produced for any requests in this burst, although image data will still
     * be produced.
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     *
     * @see #capture
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     */
    public void captureBurst(List<CaptureRequest> requests,
            CaptureListener listener) throws CameraAccessException;

    /**
     * <p>Request endlessly repeating capture of images by this
     * CameraDevice.</p>
     *
     * <p>With this method, the CameraDevice will continually capture
     * images using the settings in the provided {@link
     * CaptureRequest}, at the maximum rate possible.</p>
     *
     * <p>Repeat requests have lower priority than those submitted
     * through {@link #capture} or {@link #captureBurst}, so if
     * capture() is called when a repeating request is active, the
     * capture request will be processed before any further repeating
     * requests are processed.<p>
     *
     * <p>Repeating requests are a simple way for an application to maintain a
     * preview or other continuous stream of frames, without having to submit
     * requests through {@link #capture} at video rates.</p>
     *
     * <p>To stop the repeating capture, call {@link #stopRepeating}</p>
     *
     * <p>Calling repeat will replace a burst set up by {@link
     * #setRepeatingBurst}, although any in-progress burst will be
     * completed before the new repeat request will be used.</p>
     *
     * @param request the request to repeat indefinitely
     * @param listener the callback object to notify every time the
     * request finishes processing. If null, no metadata will be
     * produced for this stream of requests, although image data will
     * still be produced.
     *
     * @throws CameraAccessException if the camera device is no longer
     * connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingBurst
     */
    public void setRepeatingRequest(CaptureRequest request, CaptureListener listener)
            throws CameraAccessException;

    /**
     * <p>Request endlessly repeating capture of a sequence of images by this
     * CameraDevice.</p>
     *
     * <p>With this method, the CameraDevice will continually capture images,
     * cycling through the settings in the provided list of
     * {@link CaptureRequest CaptureRequests}, at the maximum rate possible.</p>
     *
     * <p>If a request is submitted through {@link #capture} or
     * {@link #captureBurst}, the current repetition of the request list will be
     * completed before the higher-priority request is handled. This guarantees
     * that the application always receives a complete repeat burst captured in
     * minimal time, instead of bursts interleaved with higher-priority
     * captures, or incomplete captures.</p>
     *
     * <p>Repeating burst requests are a simple way for an application to
     * maintain a preview or other continuous stream of frames where each
     * request is different in a predicatable way, without having to submit
     * requests through {@link #capture} at video rates.</p>
     *
     * <p>To stop the repeating capture, call {@link #stopRepeating}. Any
     * ongoing burst will still be completed, however.</p>
     *
     * <p>Calling repeatBurst will replace a repeating request set up by
     * {@link #setRepeatingRequest}, although any in-progress capture will be completed
     * before the new repeat burst will be used.</p>
     *
     * @param requests the list of requests to cycle through indefinitely.
     * @param listener the callback object to notify each time one of the
     * requests in the repeating bursts has finished processing. If null, no
     * metadata will be produced for this stream of requests, although image
     * data will still be produced.
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingRequest
     */
    public void setRepeatingBurst(List<CaptureRequest> requests, CaptureListener listener)
            throws CameraAccessException;

    /**
     * <p>Cancel any ongoing repeating capture set by either
     * {@link #setRepeatingRequest setRepeatingRequest} or
     * {@link #setRepeatingBurst}. Has no effect on requests submitted through
     * {@link #capture capture} or {@link #captureBurst captureBurst}.</p>
     *
     * <p>Any currently in-flight captures will still complete, as will any
     * burst that is mid-capture. To ensure that the device has finished
     * processing all of its capture requests and is in idle state, use the
     * {@link #waitUntilIdle waitUntilIdle} method.</p>
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     *
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see #waitUntilIdle
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed, the
     * device has encountered a fatal error, or if there is an active repeating
     * request or burst.
     */
    public void stopRepeating() throws CameraAccessException;

    /**
     * <p>Wait until all the submitted requests have finished processing</p>
     *
     * <p>This method blocks until all the requests that have been submitted to
     * the camera device, either through {@link #capture capture},
     * {@link #captureBurst captureBurst},
     * {@link #setRepeatingRequest setRepeatingRequest}, or
     * {@link #setRepeatingBurst setRepeatingBurst}, have completed their
     * processing.</p>
     *
     * <p>Once this call returns successfully, the device is in an idle state,
     * and can be reconfigured with {@link #configureOutputs configureOutputs}.</p>
     *
     * <p>This method cannot be used if there is an active repeating request or
     * burst, set with {@link #setRepeatingRequest setRepeatingRequest} or
     * {@link #setRepeatingBurst setRepeatingBurst}. Call
     * {@link #stopRepeating stopRepeating} before calling this method.</p>
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed, the
     * device has encountered a fatal error, or if there is an active repeating
     * request or burst.
     */
    public void waitUntilIdle() throws CameraAccessException;

    /**
     * Set the error listener object to call when an asynchronous error
     * occurs. The errors reported here are only device-wide errors; errors
     * about individual requests or frames are reported through
     * {@link CaptureListener#onCaptureFailed}.
     *
     * @param listener the ErrorListener to send asynchronous error
     * notifications to. Setting this to null will stop notifications about
     * asynchronous errors.
     */
    public void setErrorListener(ErrorListener listener);

    /**
     * Close the connection to this camera device. After this call, all calls to
     * the camera device interface will throw a {@link IllegalStateException},
     * except for calls to close().
     * @throws Exception
     */
    @Override
    public void close() throws Exception;
    // TODO: We should decide on the behavior of in-flight requests should be on close.

    /**
     * A listener for receiving metadata about completed image captures. The
     * metadata includes, among other things, the final capture settings and the
     * state of the control algorithms.
     */
    public interface CaptureListener {
        /**
         * <p>Called when a capture request has been processed by a
         * {@link CameraDevice}.</p>
         *
         * @param camera The CameraDevice sending the callback.
         * @param request The request that was given to the CameraDevice
         * @param result The output metadata from the capture, including the
         * final capture parameters and the state of the camera system during
         * capture.
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         */
        public void onCaptureComplete(CameraDevice camera,
                CaptureRequest request, CaptureResult result);

        /**
         * <p>Called instead of onCaptureComplete when the camera device failed
         * to produce a CaptureResult for the request. Other requests are
         * unaffected, and some or all image buffers from the capture may have
         * been pushed to their respective output streams.</p>
         *
         * @param camera The CameraDevice sending the callback.
         * @param request The request that was given to the CameraDevice
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         */
        public void onCaptureFailed(CameraDevice camera,
                CaptureRequest request);
    }

    /**
     * <p>A listener for asynchronous errors from the camera device. Errors
     * about specific {@link CaptureRequest CaptureRequests} are sent through
     * the capture {@link CaptureListener#onCaptureFailed listener}
     * interface. Errors reported through this listener affect the device as a
     * whole.</p>
     */
    public interface ErrorListener {
        /**
         * <p>This camera device has been disconnected by the camera
         * service. Any attempt to call methods on this CameraDevice will throw
         * a {@link CameraAccessException}. The disconnection could be due to a
         * change in security policy or permissions; the physical disconnection
         * of a removable camera device; or the camera being needed for a
         * higher-priority use case.</p>
         *
         * <p>There may still be capture completion or camera stream listeners
         * that will be called after this error is received.</p>
         */
        public static final int DEVICE_DISCONNECTED = 1;

        /**
         * <p>The camera device has encountered a fatal error. Any attempt to
         * call methods on this CameraDevice will throw a
         * {@link java.lang.IllegalStateException}.</p>
         *
         * <p>There may still be capture completion or camera stream listeners
         * that will be called after this error is received.</p>
         *
         * <p>The application needs to re-open the camera device to use it
         * again.</p>
         */
        public static final int DEVICE_ERROR = 2;

        /**
         * <p>The camera service has encountered a fatal error. Any attempt to
         * call methods on this CameraDevice in the future will throw a
         * {@link java.lang.IllegalStateException}.</p>
         *
         * <p>There may still be capture completion or camera stream listeners
         * that will be called after this error is received.</p>
         *
         * <p>The device may need to be shut down and restarted to restore
         * camera function, or there may be a persistent hardware problem.</p>
         */
        public static final int SERVICE_ERROR = 3;

        /**
         * The method to call when a camera device has encountered an error.
         *
         * @param camera The device reporting the error
         * @param error The error code, one of the ErrorListener.ERROR_ values.
         */
        public void onCameraDeviceError(CameraDevice camera, int error);
    }
}
