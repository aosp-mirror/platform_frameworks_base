/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.camera2.CameraOfflineSession;
import android.hardware.camera2.CameraOfflineSession.CameraOfflineSessionCallback;
import android.hardware.camera2.params.OutputConfiguration;
import android.os.Handler;
import android.view.Surface;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A configured capture session for a {@link CameraDevice}, used for capturing images from the
 * camera or reprocessing images captured from the camera in the same session previously.
 *
 * <p>A CameraCaptureSession is created by providing a set of target output surfaces to
 * {@link CameraDevice#createCaptureSession createCaptureSession}, or by providing an
 * {@link android.hardware.camera2.params.InputConfiguration} and a set of target output surfaces to
 * {@link CameraDevice#createReprocessableCaptureSession createReprocessableCaptureSession} for a
 * reprocessable capture session. Once created, the session is active until a new session is
 * created by the camera device, or the camera device is closed.</p>
 *
 * <p>All capture sessions can be used for capturing images from the camera but only reprocessable
 * capture sessions can reprocess images captured from the camera in the same session previously.
 * </p>
 *
 * <p>Creating a session is an expensive operation and can take several hundred milliseconds, since
 * it requires configuring the camera device's internal pipelines and allocating memory buffers for
 * sending images to the desired targets. Therefore the setup is done asynchronously, and
 * {@link CameraDevice#createCaptureSession createCaptureSession} and
 * {@link CameraDevice#createReprocessableCaptureSession createReprocessableCaptureSession} will
 * send the ready-to-use CameraCaptureSession to the provided listener's
 * {@link CameraCaptureSession.StateCallback#onConfigured onConfigured} callback. If configuration
 * cannot be completed, then the
 * {@link CameraCaptureSession.StateCallback#onConfigureFailed onConfigureFailed} is called, and the
 * session will not become active.</p>
 *<!--
 * <p>Any capture requests (repeating or non-repeating) submitted before the session is ready will
 * be queued up and will begin capture once the session becomes ready. In case the session cannot be
 * configured and {@link StateCallback#onConfigureFailed onConfigureFailed} is called, all queued
 * capture requests are discarded.</p>
 *-->
 * <p>If a new session is created by the camera device, then the previous session is closed, and its
 * associated {@link StateCallback#onClosed onClosed} callback will be invoked.  All
 * of the session methods will throw an IllegalStateException if called once the session is
 * closed.</p>
 *
 * <p>A closed session clears any repeating requests (as if {@link #stopRepeating} had been called),
 * but will still complete all of its in-progress capture requests as normal, before a newly
 * created session takes over and reconfigures the camera device.</p>
 */
public abstract class CameraCaptureSession implements AutoCloseable {

    /**
     * Used to identify invalid session ID.
     * @hide
     */
    public static final int SESSION_ID_NONE = -1;

    /**
     * Get the camera device that this session is created for.
     */
    @NonNull
    public abstract CameraDevice getDevice();

    /**
     * <p>Pre-allocate all buffers for an output Surface.</p>
     *
     * <p>Normally, the image buffers for a given output Surface are allocated on-demand,
     * to minimize startup latency and memory overhead.</p>
     *
     * <p>However, in some cases, it may be desirable for the buffers to be allocated before
     * any requests targeting the Surface are actually submitted to the device. Large buffers
     * may take some time to allocate, which can result in delays in submitting requests until
     * sufficient buffers are allocated to reach steady-state behavior. Such delays can cause
     * bursts to take longer than desired, or cause skips or stutters in preview output.</p>
     *
     * <p>The prepare() method can be used to perform this preallocation. It may only be called for
     * a given output Surface before that Surface is used as a target for a request. The number of
     * buffers allocated is the sum of the count needed by the consumer providing the output
     * Surface, and the maximum number needed by the camera device to fill its pipeline. Since this
     * may be a larger number than what is actually required for steady-state operation, using
     * prepare may result in higher memory consumption than the normal on-demand behavior results
     * in. Prepare() will also delay the time to first output to a given Surface, in exchange for
     * smoother frame rate once the allocation is complete.</p>
     *
     * <p>For example, an application that creates an
     * {@link android.media.ImageReader#newInstance ImageReader} with a maxImages argument of 10,
     * but only uses 3 simultaneous Images at once would normally only cause those 3 images to be
     * allocated (plus what is needed by the camera device for smooth operation).  But using
     * prepare() on the ImageReader Surface will result in all 10 Images being allocated. So
     * applications using this method should take care to request only the number of buffers
     * actually necessary for their application.</p>
     *
     * <p>If the same output Surface is used in consecutive sessions (without closing the first
     * session explicitly), then its already-allocated buffers are carried over, and if it was
     * used as a target of a capture request in the first session, prepare cannot be called on it
     * in the second session.</p>
     *
     * <p>Once allocation is complete, {@link StateCallback#onSurfacePrepared} will be invoked with
     * the Surface provided to this method. Between the prepare call and the onSurfacePrepared call,
     * the Surface provided to prepare must not be used as a target of a CaptureRequest submitted
     * to this session.</p>
     *
     * <p>Note that if 2 surfaces share the same stream via {@link
     * OutputConfiguration#enableSurfaceSharing} and {@link OutputConfiguration#addSurface},
     * prepare() only needs to be called on one surface, and {link
     * StateCallback#onSurfacePrepared} will be triggered for both surfaces.</p>
     *
     * <p>{@link android.hardware.camera2.CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY LEGACY}
     * devices cannot pre-allocate output buffers; for those devices,
     * {@link StateCallback#onSurfacePrepared} will be immediately called, and no preallocation is
     * done.</p>
     *
     * @param surface the output Surface for which buffers should be pre-allocated. Must be one of
     * the output Surfaces used to create this session.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException if the Surface is invalid, not part of this Session, or has
     *                                  already been used as a target of a CaptureRequest in this
     *                                  session or immediately prior sessions.
     *
     * @see StateCallback#onSurfacePrepared
     */
    public abstract void prepare(@NonNull Surface surface) throws CameraAccessException;

    /**
     * <p>Pre-allocate at most maxCount buffers for an output Surface.</p>
     *
     * <p>Like the {@link #prepare(Surface)} method, this method can be used to allocate output
     * buffers for a given Surface.  However, while the {@link #prepare(Surface)} method allocates
     * the maximum possible buffer count, this method allocates at most maxCount buffers.</p>
     *
     * <p>If maxCount is greater than the possible maximum count (which is the sum of the buffer
     * count requested by the creator of the Surface and the count requested by the camera device),
     * only the possible maximum count is allocated, in which case the function acts exactly like
     * {@link #prepare(Surface)}.</p>
     *
     * <p>The restrictions on when this method can be called are the same as for
     * {@link #prepare(Surface)}.</p>
     *
     * <p>Repeated calls to this method are allowed, and a mix of {@link #prepare(Surface)} and
     * this method is also allowed. Note that after the first call to {@link #prepare(Surface)},
     * subsequent calls to either prepare method are effectively no-ops.  In addition, this method
     * is not additive in terms of buffer count.  This means calling it twice with maxCount = 2
     * will only allocate 2 buffers, not 4 (assuming the possible maximum is at least 2); to
     * allocate two buffers on the first call and two on the second, the application needs to call
     * prepare with prepare(surface, 2) and prepare(surface, 4).</p>
     *
     * @param maxCount the buffer count to try to allocate. If this is greater than the possible
     *                 maximum for this output, the possible maximum is allocated instead. If
     *                 maxCount buffers are already allocated, then prepare will do nothing.
     * @param surface the output Surface for which buffers should be pre-allocated.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error.
     * @throws IllegalStateException if this session is no longer active, either because the
     *                               session was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException if the Surface is invalid, not part of this Session,
     *                                  or has already been used as a target of a CaptureRequest in
     *                                  this session or immediately prior sessions without an
     *                                  intervening tearDown call.
     *
     * @hide
     */
    public abstract void prepare(int maxCount, @NonNull Surface surface)
            throws CameraAccessException;

    /**
     * <p>Free all buffers allocated for an output Surface.</p>
     *
     * <p>Normally, once allocated, the image buffers for a given output Surface remain allocated
     * for the lifetime of the capture session, to minimize latency of captures and to reduce
     * memory allocation overhead.</p>
     *
     * <p>However, in some cases, it may be desirable for allocated buffers to be freed to reduce
     * the application's memory consumption, if the particular output Surface will not be used by
     * the application for some time.</p>
     *
     * <p>The tearDown() method can be used to perform this operation. After the call finishes, all
     * unfilled image buffers will have been freed. Any future use of the target Surface may require
     * allocation of additional buffers, as if the session had just been created.  Buffers being
     * held by the application (either explicitly as Image objects from ImageReader, or implicitly
     * as the current texture in a SurfaceTexture or the current contents of a RS Allocation, will
     * remain valid and allocated even when tearDown is invoked.</p>
     *
     * <p>A Surface that has had tearDown() called on it is eligible to have prepare() invoked on it
     * again even if it was used as a request target before the tearDown() call, as long as it
     * doesn't get used as a target of a request between the tearDown() and prepare() calls.</p>
     *
     * @param surface the output Surface for which buffers should be freed. Must be one of the
     * the output Surfaces used to create this session.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error.
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException if the Surface is invalid, not part of this Session, or has
     *                                  already been used as a target of a CaptureRequest in this
     *                                  session or immediately prior sessions.
     *
     * @hide
     */
    public abstract void tearDown(@NonNull Surface surface) throws CameraAccessException;

    /**
     * <p>Finalize the output configurations that now have their deferred and/or extra Surfaces
     * included.</p>
     *
     * <p>For camera use cases where a preview and other output configurations need to be
     * configured, it can take some time for the preview Surface to be ready. For example, if the
     * preview Surface is obtained from {@link android.view.SurfaceView}, the SurfaceView will only
     * be ready after the UI layout is done, potentially delaying camera startup.</p>
     *
     * <p>To speed up camera startup time, the application can configure the
     * {@link CameraCaptureSession} with the eventual preview size (via
     * {@link OutputConfiguration#OutputConfiguration(Size,Class) a deferred OutputConfiguration}),
     * and defer the preview output configuration until the Surface is ready. After the
     * {@link CameraCaptureSession} is created successfully with this deferred output and other
     * normal outputs, the application can start submitting requests as long as they do not include
     * deferred output Surfaces. Once a deferred Surface is ready, the application can add the
     * Surface to the deferred output configuration with the
     * {@link OutputConfiguration#addSurface} method, and then update the deferred output
     * configuration via this method, before it can submit capture requests with this output
     * target.</p>
     *
     * <p>This function can also be called in case where multiple surfaces share the same
     * OutputConfiguration, and one of the surfaces becomes available after the {@link
     * CameraCaptureSession} is created. In that case, the application must first create the
     * OutputConfiguration with the available Surface, then enable further surface sharing via
     * {@link OutputConfiguration#enableSurfaceSharing}, before creating the CameraCaptureSession.
     * After the CameraCaptureSession is created, and once the extra Surface becomes available, the
     * application must then call {@link OutputConfiguration#addSurface} before finalizing the
     * configuration with this method.</p>
     *
     * <p>If the provided OutputConfigurations are unchanged from session creation, this function
     * call has no effect. This function must only be called once for a particular output
     * configuration. </p>
     *
     * <p>The output Surfaces included by this list of
     * {@link OutputConfiguration OutputConfigurations} can be used as {@link CaptureRequest}
     * targets as soon as this call returns.</p>
     *
     * <p>This method is not supported by
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY LEGACY}-level devices.</p>
     *
     * @param outputConfigs a list of {@link OutputConfiguration OutputConfigurations} that
     *            have had {@link OutputConfiguration#addSurface addSurface} invoked with a valid
     *            output Surface after {@link CameraDevice#createCaptureSessionByOutputConfigurations}.
     * @throws CameraAccessException if the camera device is no longer connected or has encountered
     *             a fatal error.
     * @throws IllegalStateException if this session is no longer active, either because the session
     *             was explicitly closed, a new session has been created, or the camera device has
     *             been closed.
     * @throws IllegalArgumentException for invalid output configurations, including ones where the
     *             source of the Surface is no longer valid or the Surface is from a unsupported
     *             source. Or if one of the output configuration was already finished with an
     *             included surface in a prior call.
     */
    public abstract void finalizeOutputConfigurations(
            List<OutputConfiguration> outputConfigs) throws CameraAccessException;

    /**
     * <p>Submit a request for an image to be captured by the camera device.</p>
     *
     * <p>The request defines all the parameters for capturing the single image,
     * including sensor, lens, flash, and post-processing settings.</p>
     *
     * <p>Each request will produce one {@link CaptureResult} and produce new frames for one or more
     * target Surfaces, set with the CaptureRequest builder's
     * {@link CaptureRequest.Builder#addTarget} method. The target surfaces (set with
     * {@link CaptureRequest.Builder#addTarget}) must be a subset of the surfaces provided when this
     * capture session was created.</p>
     *
     * <p>Multiple regular and reprocess requests can be in progress at once. If there are only
     * regular requests or reprocess requests in progress, they are processed in first-in,
     * first-out order. If there are both regular and reprocess requests in progress, regular
     * requests are processed in first-in, first-out order and reprocess requests are processed in
     * first-in, first-out order, respectively. However, the processing order of a regular request
     * and a reprocess request in progress is not specified. In other words, a regular request
     * will always be processed before regular requets that are submitted later. A reprocess request
     * will always be processed before reprocess requests that are submitted later. However, a
     * regular request may not be processed before reprocess requests that are submitted later.<p>
     *
     * <p>Requests submitted through this method have higher priority than
     * those submitted through {@link #setRepeatingRequest} or
     * {@link #setRepeatingBurst}, and will be processed as soon as the current
     * repeat/repeatBurst processing completes.</p>
     *
     * <p>All capture sessions can be used for capturing images from the camera but only capture
     * sessions created by
     * {@link CameraDevice#createReprocessableCaptureSession createReprocessableCaptureSession}
     * can submit reprocess capture requests. Submitting a reprocess request to a regular capture
     * session will result in an {@link IllegalArgumentException}.</p>
     *
     * @param request the settings for this capture
     * @param listener The callback object to notify once this request has been
     * processed. If null, no metadata will be produced for this capture,
     * although image data will still be produced.
     * @param handler the handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper
     * looper}.
     *
     * @return int A unique capture sequence ID used by
     *             {@link CaptureCallback#onCaptureSequenceCompleted}.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException if the request targets no Surfaces or Surfaces that are not
     *                                  configured as outputs for this session; or the request
     *                                  targets a set of Surfaces that cannot be submitted
     *                                  simultaneously in a reprocessable capture session; or a
     *                                  reprocess capture request is submitted in a
     *                                  non-reprocessable capture session; or the reprocess capture
     *                                  request was created with a {@link TotalCaptureResult} from
     *                                  a different session; or the capture targets a Surface in
     *                                  the middle of being {@link #prepare prepared}; or the
     *                                  handler is null, the listener is not null, and the calling
     *                                  thread has no looper.
     *
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see #abortCaptures
     * @see CameraDevice#createReprocessableCaptureSession
     */
    public abstract int capture(@NonNull CaptureRequest request,
            @Nullable CaptureCallback listener, @Nullable Handler handler)
            throws CameraAccessException;

    /**
     * <p>Submit a request for an image to be captured by the camera device.</p>
     *
     * <p>The behavior of this method matches that of
     * {@link #capture(CaptureRequest, CaptureCallback, Handler)},
     * except that it uses {@link java.util.concurrent.Executor} as an argument
     * instead of {@link android.os.Handler}.</p>
     *
     * @param request the settings for this capture
     * @param executor the executor which will be used for invoking the listener.
     * @param listener The callback object to notify once this request has been
     * processed.
     *
     * @return int A unique capture sequence ID used by
     *             {@link CaptureCallback#onCaptureSequenceCompleted}.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException if the request targets no Surfaces or Surfaces that are not
     *                                  configured as outputs for this session; or the request
     *                                  targets a set of Surfaces that cannot be submitted
     *                                  simultaneously in a reprocessable capture session; or a
     *                                  reprocess capture request is submitted in a
     *                                  non-reprocessable capture session; or the reprocess capture
     *                                  request was created with a {@link TotalCaptureResult} from
     *                                  a different session; or the capture targets a Surface in
     *                                  the middle of being {@link #prepare prepared}; or the
     *                                  executor is null, or the listener is not null.
     *
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see #abortCaptures
     * @see CameraDevice#createReprocessableCaptureSession
     */
    public int captureSingleRequest(@NonNull CaptureRequest request,
            @NonNull @CallbackExecutor Executor executor, @NonNull CaptureCallback listener)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Submit a list of requests to be captured in sequence as a burst. The
     * burst will be captured in the minimum amount of time possible, and will
     * not be interleaved with requests submitted by other capture or repeat
     * calls.
     *
     * <p>Regular and reprocess requests can be mixed together in a single burst. Regular requests
     * will be captured in order and reprocess requests will be processed in order, respectively.
     * However, the processing order between a regular request and a reprocess request is not
     * specified. Each capture produces one {@link CaptureResult} and image buffers for one or more
     * target {@link android.view.Surface surfaces}. The target surfaces (set with
     * {@link CaptureRequest.Builder#addTarget}) must be a subset of the surfaces provided when
     * this capture session was created.</p>
     *
     * <p>The main difference between this method and simply calling
     * {@link #capture} repeatedly is that this method guarantees that no
     * other requests will be interspersed with the burst.</p>
     *
     * <p>All capture sessions can be used for capturing images from the camera but only capture
     * sessions created by
     * {@link CameraDevice#createReprocessableCaptureSession createReprocessableCaptureSession}
     * can submit reprocess capture requests. Submitting a reprocess request to a regular
     * capture session will result in an {@link IllegalArgumentException}.</p>
     *
     * @param requests the list of settings for this burst capture
     * @param listener The callback object to notify each time one of the
     * requests in the burst has been processed. If null, no metadata will be
     * produced for any requests in this burst, although image data will still
     * be produced.
     * @param handler the handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper
     * looper}.
     *
     * @return int A unique capture sequence ID used by
     *             {@link CaptureCallback#onCaptureSequenceCompleted}.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException If the requests target no Surfaces, or the requests target
     *                                  Surfaces not currently configured as outputs; or one of the
     *                                  requests targets a set of Surfaces that cannot be submitted
     *                                  simultaneously in a reprocessable capture session; or a
     *                                  reprocess capture request is submitted in a
     *                                  non-reprocessable capture session; or one of the reprocess
     *                                  capture requests was created with a
     *                                  {@link TotalCaptureResult} from a different session; or one
     *                                  of the captures targets a Surface in the middle of being
     *                                  {@link #prepare prepared}; or if the handler is null, the
     *                                  listener is not null, and the calling thread has no looper.
     *
     * @see #capture
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see #abortCaptures
     */
    public abstract int captureBurst(@NonNull List<CaptureRequest> requests,
            @Nullable CaptureCallback listener, @Nullable Handler handler)
            throws CameraAccessException;

    /**
     * Submit a list of requests to be captured in sequence as a burst. The
     * burst will be captured in the minimum amount of time possible, and will
     * not be interleaved with requests submitted by other capture or repeat
     * calls.
     *
     * <p>The behavior of this method matches that of
     * {@link #captureBurst(List, CaptureCallback, Handler)},
     * except that it uses {@link java.util.concurrent.Executor} as an argument
     * instead of {@link android.os.Handler}.</p>
     *
     * @param requests the list of settings for this burst capture
     * @param executor the executor which will be used for invoking the listener.
     * @param listener The callback object to notify each time one of the
     * requests in the burst has been processed.
     *
     * @return int A unique capture sequence ID used by
     *             {@link CaptureCallback#onCaptureSequenceCompleted}.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException If the requests target no Surfaces, or the requests target
     *                                  Surfaces not currently configured as outputs; or one of the
     *                                  requests targets a set of Surfaces that cannot be submitted
     *                                  simultaneously in a reprocessable capture session; or a
     *                                  reprocess capture request is submitted in a
     *                                  non-reprocessable capture session; or one of the reprocess
     *                                  capture requests was created with a
     *                                  {@link TotalCaptureResult} from a different session; or one
     *                                  of the captures targets a Surface in the middle of being
     *                                  {@link #prepare prepared}; or if the executor is null; or if
     *                                  the listener is null.
     *
     * @see #capture
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see #abortCaptures
     */
    public int captureBurstRequests(@NonNull List<CaptureRequest> requests,
            @NonNull @CallbackExecutor Executor executor, @NonNull CaptureCallback listener)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Request endlessly repeating capture of images by this capture session.
     *
     * <p>With this method, the camera device will continually capture images
     * using the settings in the provided {@link CaptureRequest}, at the maximum
     * rate possible.</p>
     *
     * <p>Repeating requests are a simple way for an application to maintain a
     * preview or other continuous stream of frames, without having to
     * continually submit identical requests through {@link #capture}.</p>
     *
     * <p>Repeat requests have lower priority than those submitted
     * through {@link #capture} or {@link #captureBurst}, so if
     * {@link #capture} is called when a repeating request is active, the
     * capture request will be processed before any further repeating
     * requests are processed.<p>
     *
     * <p>To stop the repeating capture, call {@link #stopRepeating}. Calling
     * {@link #abortCaptures} will also clear the request.</p>
     *
     * <p>Calling this method will replace any earlier repeating request or
     * burst set up by this method or {@link #setRepeatingBurst}, although any
     * in-progress burst will be completed before the new repeat request will be
     * used.</p>
     *
     * <p>This method does not support reprocess capture requests because each reprocess
     * {@link CaptureRequest} must be created from the {@link TotalCaptureResult} that matches
     * the input image to be reprocessed. This is either the {@link TotalCaptureResult} of capture
     * that is sent for reprocessing, or one of the {@link TotalCaptureResult TotalCaptureResults}
     * of a set of captures, when data from the whole set is combined by the application into a
     * single reprocess input image. The request must be capturing images from the camera. If a
     * reprocess capture request is submitted, this method will throw IllegalArgumentException.</p>
     *
     * @param request the request to repeat indefinitely
     * @param listener The callback object to notify every time the
     * request finishes processing. If null, no metadata will be
     * produced for this stream of requests, although image data will
     * still be produced.
     * @param handler the handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper
     * looper}.
     *
     * @return int A unique capture sequence ID used by
     *             {@link CaptureCallback#onCaptureSequenceCompleted}.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException If the request references no Surfaces or references Surfaces
     *                                  that are not currently configured as outputs; or the request
     *                                  is a reprocess capture request; or the capture targets a
     *                                  Surface in the middle of being {@link #prepare prepared}; or
     *                                  the handler is null, the listener is not null, and the
     *                                  calling thread has no looper; or no requests were passed in.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingBurst
     * @see #stopRepeating
     * @see #abortCaptures
     */
    public abstract int setRepeatingRequest(@NonNull CaptureRequest request,
            @Nullable CaptureCallback listener, @Nullable Handler handler)
            throws CameraAccessException;

    /**
     * Request endlessly repeating capture of images by this capture session.
     *
     * <p>The behavior of this method matches that of
     * {@link #setRepeatingRequest(CaptureRequest, CaptureCallback, Handler)},
     * except that it uses {@link java.util.concurrent.Executor} as an argument
     * instead of {@link android.os.Handler}.</p>
     *
     * @param request the request to repeat indefinitely
     * @param executor the executor which will be used for invoking the listener.
     * @param listener The callback object to notify every time the
     * request finishes processing.
     *
     * @return int A unique capture sequence ID used by
     *             {@link CaptureCallback#onCaptureSequenceCompleted}.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException If the request references no Surfaces or references Surfaces
     *                                  that are not currently configured as outputs; or the request
     *                                  is a reprocess capture request; or the capture targets a
     *                                  Surface in the middle of being {@link #prepare prepared}; or
     *                                  the executor is null; or the listener is null.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingBurst
     * @see #stopRepeating
     * @see #abortCaptures
     */
    public int setSingleRepeatingRequest(@NonNull CaptureRequest request,
            @NonNull @CallbackExecutor Executor executor, @NonNull CaptureCallback listener)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * <p>Request endlessly repeating capture of a sequence of images by this
     * capture session.</p>
     *
     * <p>With this method, the camera device will continually capture images,
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
     * request is different in a predicatable way, without having to continually
     * submit requests through {@link #captureBurst}.</p>
     *
     * <p>To stop the repeating capture, call {@link #stopRepeating}. Any
     * ongoing burst will still be completed, however. Calling
     * {@link #abortCaptures} will also clear the request.</p>
     *
     * <p>Calling this method will replace a previously-set repeating request or
     * burst set up by this method or {@link #setRepeatingRequest}, although any
     * in-progress burst will be completed before the new repeat burst will be
     * used.</p>
     *
     * <p>This method does not support reprocess capture requests because each reprocess
     * {@link CaptureRequest} must be created from the {@link TotalCaptureResult} that matches
     * the input image to be reprocessed. This is either the {@link TotalCaptureResult} of capture
     * that is sent for reprocessing, or one of the {@link TotalCaptureResult TotalCaptureResults}
     * of a set of captures, when data from the whole set is combined by the application into a
     * single reprocess input image. The request must be capturing images from the camera. If a
     * reprocess capture request is submitted, this method will throw IllegalArgumentException.</p>
     *
     * @param requests the list of requests to cycle through indefinitely
     * @param listener The callback object to notify each time one of the
     * requests in the repeating bursts has finished processing. If null, no
     * metadata will be produced for this stream of requests, although image
     * data will still be produced.
     * @param handler the handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper
     * looper}.
     *
     * @return int A unique capture sequence ID used by
     *             {@link CaptureCallback#onCaptureSequenceCompleted}.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException If the requests reference no Surfaces or reference Surfaces
     *                                  not currently configured as outputs; or one of the requests
     *                                  is a reprocess capture request; or one of the captures
     *                                  targets a Surface in the middle of being
     *                                  {@link #prepare prepared}; or the handler is null, the
     *                                  listener is not null, and the calling thread has no looper;
     *                                  or no requests were passed in.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #stopRepeating
     * @see #abortCaptures
     */
    public abstract int setRepeatingBurst(@NonNull List<CaptureRequest> requests,
            @Nullable CaptureCallback listener, @Nullable Handler handler)
            throws CameraAccessException;

    /**
     * <p>Request endlessly repeating capture of a sequence of images by this
     * capture session.</p>
     *
     * <p>The behavior of this method matches that of
     * {@link #setRepeatingBurst(List, CaptureCallback, Handler)},
     * except that it uses {@link java.util.concurrent.Executor} as an argument
     * instead of {@link android.os.Handler}.</p>
     *
     * @param requests the list of requests to cycle through indefinitely
     * @param executor the executor which will be used for invoking the listener.
     * @param listener The callback object to notify each time one of the
     * requests in the repeating bursts has finished processing.
     *
     * @return int A unique capture sequence ID used by
     *             {@link CaptureCallback#onCaptureSequenceCompleted}.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException If the requests reference no Surfaces or reference Surfaces
     *                                  not currently configured as outputs; or one of the requests
     *                                  is a reprocess capture request; or one of the captures
     *                                  targets a Surface in the middle of being
     *                                  {@link #prepare prepared}; or the executor is null; or the
     *                                  listener is null.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #stopRepeating
     * @see #abortCaptures
     */
    public int setRepeatingBurstRequests(@NonNull List<CaptureRequest> requests,
            @NonNull @CallbackExecutor Executor executor, @NonNull CaptureCallback listener)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * <p>Cancel any ongoing repeating capture set by either
     * {@link #setRepeatingRequest setRepeatingRequest} or
     * {@link #setRepeatingBurst}. Has no effect on requests submitted through
     * {@link #capture capture} or {@link #captureBurst captureBurst}.</p>
     *
     * <p>Any currently in-flight captures will still complete, as will any burst that is
     * mid-capture. To ensure that the device has finished processing all of its capture requests
     * and is in ready state, wait for the {@link StateCallback#onReady} callback after
     * calling this method.</p>
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     *
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see StateCallback#onReady
     */
    public abstract void stopRepeating() throws CameraAccessException;

    /**
     * Discard all captures currently pending and in-progress as fast as possible.
     *
     * <p>The camera device will discard all of its current work as fast as possible. Some in-flight
     * captures may complete successfully and call {@link CaptureCallback#onCaptureCompleted}, while
     * others will trigger their {@link CaptureCallback#onCaptureFailed} callbacks. If a repeating
     * request or a repeating burst is set, it will be cleared.</p>
     *
     * <p>This method is the fastest way to switch the camera device to a new session with
     * {@link CameraDevice#createCaptureSession} or
     * {@link CameraDevice#createReprocessableCaptureSession}, at the cost of discarding in-progress
     * work. It must be called before the new session is created. Once all pending requests are
     * either completed or thrown away, the {@link StateCallback#onReady} callback will be called,
     * if the session has not been closed. Otherwise, the {@link StateCallback#onClosed}
     * callback will be fired when a new session is created by the camera device.</p>
     *
     * <p>Cancelling will introduce at least a brief pause in the stream of data from the camera
     * device, since once the camera device is emptied, the first new request has to make it through
     * the entire camera pipeline before new output buffers are produced.</p>
     *
     * <p>This means that using {@code abortCaptures()} to simply remove pending requests is not
     * recommended; it's best used for quickly switching output configurations, or for cancelling
     * long in-progress requests (such as a multi-second capture).</p>
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     *
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see CameraDevice#createCaptureSession
     * @see CameraDevice#createReprocessableCaptureSession
     */
    public abstract void abortCaptures() throws CameraAccessException;

    /**
     * Return if the application can submit reprocess capture requests with this camera capture
     * session.
     *
     * @return {@code true} if the application can submit reprocess capture requests with this
     *         camera capture session. {@code false} otherwise.
     *
     * @see CameraDevice#createReprocessableCaptureSession
     */
    public abstract boolean isReprocessable();

    /**
     * Get the input Surface associated with a reprocessable capture session.
     *
     * <p>Each reprocessable capture session has an input {@link Surface} where the reprocess
     * capture requests get the input images from, rather than the camera device. The application
     * can create a {@link android.media.ImageWriter ImageWriter} with this input {@link Surface}
     * and use it to provide input images for reprocess capture requests. When the reprocessable
     * capture session is closed, the input {@link Surface} is abandoned and becomes invalid.</p>
     *
     * @return The {@link Surface} where reprocessing capture requests get the input images from. If
     *         this is not a reprocess capture session, {@code null} will be returned.
     *
     * @see CameraDevice#createReprocessableCaptureSession
     * @see android.media.ImageWriter
     * @see android.media.ImageReader
     */
    @Nullable
    public abstract Surface getInputSurface();

    /**
     * Update {@link OutputConfiguration} after configuration finalization see
     * {@link #finalizeOutputConfigurations}.
     *
     * <p>Any {@link OutputConfiguration} that has been modified via calls to
     * {@link OutputConfiguration#addSurface} or {@link OutputConfiguration#removeSurface} must be
     * updated. After the update call returns without throwing exceptions any newly added surfaces
     * can be referenced in subsequent capture requests.</p>
     *
     * <p>Surfaces that get removed must not be part of any active repeating or single/burst
     * request or have any pending results. Consider updating any repeating requests first via
     * {@link #setRepeatingRequest} or {@link #setRepeatingBurst} and then wait for the last frame
     * number when the sequence completes {@link CaptureCallback#onCaptureSequenceCompleted}
     * before calling updateOutputConfiguration to remove a previously active Surface.</p>
     *
     * <p>Surfaces that get added must not be part of any other registered
     * {@link OutputConfiguration}.</p>
     *
     * @param config Modified output configuration.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error.
     * @throws IllegalArgumentException if an attempt was made to add a {@link Surface} already
     *                               in use by another buffer-producing API, such as MediaCodec or
     *                               a different camera device or {@link OutputConfiguration}; or
     *                               new surfaces are not compatible (see
     *                               {@link OutputConfiguration#enableSurfaceSharing}); or a
     *                               {@link Surface} that was removed from the modified
     *                               {@link OutputConfiguration} still has pending requests.
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     */
    public void updateOutputConfiguration(OutputConfiguration config)
        throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Switch the current capture session and a given set of registered camera surfaces
     * to offline processing mode.
     *
     * <p>Offline processing mode and the corresponding {@link CameraOfflineSession} differ from
     * a regular online camera capture session in several ways. Successful offline switches will
     * close the currently active camera capture session. Camera clients are also allowed
     * to call {@link CameraDevice#close} while offline processing of selected capture
     * requests is still in progress. Such side effects free device close is only possible
     * when the offline session moves to the ready state. Once this happens, closing the camera
     * device will not affect the pending offline requests and they must complete as normal.</p>
     *
     * <p>Offline processing mode switches may need several hundred milliseconds to complete
     * as the underlying camera implementation must abort all currently active repeating requests
     * as well as all other pending requests not specified by the client. Additionally the switch
     * will be blocked until all requests that continue processing within the offline session
     * acquire their initial input frame from camera sensor. The call to {@link #switchToOffline}
     * itself is not blocking and will only trigger the offline switch sequence. Clients will
     * be notified via {@link CameraOfflineSessionCallback#onReady} once the entire sequence is
     * complete.</p>
     *
     * <p>Please note that after a successful call to this method the currently active capture
     * session will no longer be valid and clients will begin receiving capture
     * callbacks with a corresponding {@link CameraOfflineSession} passed as a session
     * argument.</p>
     *
     * @param offlineSurfaces Client-specified collection of input/output camera registered surfaces
     *                        that need to be switched to offline mode along with their pending
     *                        capture requests. Do note that not all camera registered
     *                        surfaces can be switched to offline mode. Offline processing
     *                        support for individual surfaces can be queried using
     *                        {@link #supportsOfflineProcessing}. Additionally offline mode
     *                        switches are not available for shared surfaces
     *                        {@link OutputConfiguration#enableSurfaceSharing} and surfaces
     *                        as part of a surface group.
     *
     * @param executor The executor which will be used for invoking the offline callback listener.
     *
     * @param listener The callback object to notify for offline session events.
     *
     * @return camera offline session which in case of successful offline switch will move in ready
     *         state after clients receive {@link CameraOfflineSessionCallback#onReady}. In case the
     *         offline switch was not successful clients will receive respective
     *         {@link CameraOfflineSessionCallback#onSwitchFailed} notification.
     *
     * @throws IllegalArgumentException if an attempt was made to pass a {@link Surface} that was
     *                                  not registered with this capture session or a shared
     *                                  surface {@link OutputConfiguration#enableSurfaceSharing} or
     *                                  surface as part of a surface group. The current capture
     *                                  session will remain valid and active in case of this
     *                                  exception.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error.
     *
     * @see CameraOfflineSession
     * @see CameraOfflineSessionCallback
     * @see #supportsOfflineProcessing
     */
    @Nullable
    public CameraOfflineSession switchToOffline(@NonNull Collection<Surface> offlineSurfaces,
            @NonNull Executor executor, @NonNull CameraOfflineSessionCallback listener)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * <p>Query whether a given Surface is able to support offline mode. </p>
     *
     * <p>Surfaces that support offline mode can be passed as arguments to {@link #switchToOffline}.
     * </p>
     *
     * @param surface An input/output surface that was used to create this session or the result of
     *                {@link #getInputSurface}.
     *
     * @return {@code true} if the surface can support offline mode and can be passed as argument to
     *         {@link #switchToOffline}. {@code false} otherwise.
     *
     * @throws IllegalArgumentException if an attempt was made to pass a {@link Surface} that was
     *                                  not registered with this capture session.
     * @throws UnsupportedOperationException if an attempt was made to call this method using
     *                                       unsupported camera capture session like
     *                                       {@link CameraConstrainedHighSpeedCaptureSession} or
     *                                       {@link CameraOfflineSession}.
     *
     * @see #switchToOffline
     */
    public boolean supportsOfflineProcessing(@NonNull Surface surface) {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Close this capture session asynchronously.
     *
     * <p>Closing a session frees up the target output Surfaces of the session for reuse with either
     * a new session, or to other APIs that can draw to Surfaces.</p>
     *
     * <p>Note that creating a new capture session with {@link CameraDevice#createCaptureSession}
     * will close any existing capture session automatically, and call the older session listener's
     * {@link StateCallback#onClosed} callback. Using {@link CameraDevice#createCaptureSession}
     * directly without closing is the recommended approach for quickly switching to a new session,
     * since unchanged target outputs can be reused more efficiently.</p>
     *
     * <p>Once a session is closed, all methods on it will throw an IllegalStateException, and any
     * repeating requests or bursts are stopped (as if {@link #stopRepeating()} was called).
     * However, any in-progress capture requests submitted to the session will be completed as
     * normal; once all captures have completed and the session has been torn down,
     * {@link StateCallback#onClosed} will be called.</p>
     *
     * <p>Closing a session is idempotent; closing more than once has no effect.</p>
     */
    @Override
    public abstract void close();

    /**
     * A callback object for receiving updates about the state of a camera capture session.
     *
     */
    public static abstract class StateCallback {

        /**
         * This method is called when the camera device has finished configuring itself, and the
         * session can start processing capture requests.
         *
         * <p>If there are capture requests already queued with the session, they will start
         * processing once this callback is invoked, and the session will call {@link #onActive}
         * right after this callback is invoked.</p>
         *
         * <p>If no capture requests have been submitted, then the session will invoke
         * {@link #onReady} right after this callback.</p>
         *
         * <p>If the camera device configuration fails, then {@link #onConfigureFailed} will
         * be invoked instead of this callback.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         */
        public abstract void onConfigured(@NonNull CameraCaptureSession session);

        /**
         * This method is called if the session cannot be configured as requested.
         *
         * <p>This can happen if the set of requested outputs contains unsupported sizes,
         * or too many outputs are requested at once.</p>
         *
         * <p>The session is considered to be closed, and all methods called on it after this
         * callback is invoked will throw an IllegalStateException. Any capture requests submitted
         * to the session prior to this callback will be discarded and will not produce any
         * callbacks on their listeners.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         */
        public abstract void onConfigureFailed(@NonNull CameraCaptureSession session);

        /**
         * This method is called every time the session has no more capture requests to process.
         *
         * <p>During the creation of a new session, this callback is invoked right after
         * {@link #onConfigured} if no capture requests were submitted to the session prior to it
         * completing configuration.</p>
         *
         * <p>Otherwise, this callback will be invoked any time the session finishes processing
         * all of its active capture requests, and no repeating request or burst is set up.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         *
         */
        public void onReady(@NonNull CameraCaptureSession session) {
            // default empty implementation
        }

        /**
         * This method is called when the session starts actively processing capture requests.
         *
         * <p>If capture requests are submitted prior to {@link #onConfigured} being called,
         * then the session will start processing those requests immediately after the callback,
         * and this method will be immediately called after {@link #onConfigured}.
         *
         * <p>If the session runs out of capture requests to process and calls {@link #onReady},
         * then this callback will be invoked again once new requests are submitted for capture.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         */
        public void onActive(@NonNull CameraCaptureSession session) {
            // default empty implementation
        }

        /**
         * This method is called when camera device's input capture queue becomes empty,
         * and is ready to accept the next request.
         *
         * <p>Pending capture requests exist in one of two queues: the in-flight queue where requests
         * are already in different stages of processing pipeline, and an input queue where requests
         * wait to enter the in-flight queue. The input queue is needed because more requests may be
         * submitted than the current camera device pipeline depth.</p>
         *
         * <p>This callback is fired when the input queue becomes empty, and the camera device may
         * have to fall back to the repeating request if set, or completely skip the next frame from
         * the sensor. This can cause glitches to camera preview output, for example. This callback
         * will only fire after requests queued by capture() or captureBurst(), not after a
         * repeating request or burst enters the in-flight queue. For example, in the common case
         * of a repeating request and a single-shot JPEG capture, this callback only fires when the
         * JPEG request has entered the in-flight queue for capture.</p>
         *
         * <p>By only sending a new {@link #capture} or {@link #captureBurst} when the input
         * queue is empty, pipeline latency can be minimized.</p>
         *
         * <p>This callback is not fired when the session is first created. It is different from
         * {@link #onReady}, which is fired when all requests in both queues have been processed.</p>
         *
         * @param session
         *            The session returned by {@link CameraDevice#createCaptureSession}
         */
        public void onCaptureQueueEmpty(@NonNull CameraCaptureSession session) {
            // default empty implementation
        }

        /**
         * This method is called when the session is closed.
         *
         * <p>A session is closed when a new session is created by the parent camera device,
         * or when the parent camera device is closed (either by the user closing the device,
         * or due to a camera device disconnection or fatal error).</p>
         *
         * <p>Once a session is closed, all methods on it will throw an IllegalStateException, and
         * any repeating requests or bursts are stopped (as if {@link #stopRepeating()} was called).
         * However, any in-progress capture requests submitted to the session will be completed
         * as normal.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         */
        public void onClosed(@NonNull CameraCaptureSession session) {
            // default empty implementation
        }

        /**
         * This method is called when the buffer pre-allocation for an output Surface is complete.
         *
         * <p>Buffer pre-allocation for an output Surface is started by the {@link #prepare} call.
         * While allocation is underway, the Surface must not be used as a capture target.
         * Once this callback fires, the output Surface provided can again be used as a target for
         * a capture request.</p>
         *
         * <p>In case of a error during pre-allocation (such as running out of suitable memory),
         * this callback is still invoked after the error is encountered, though some buffers may
         * not have been successfully pre-allocated.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         * @param surface the Surface that was used with the {@link #prepare} call.
         */
        public void onSurfacePrepared(@NonNull CameraCaptureSession session,
                @NonNull Surface surface) {
            // default empty implementation
        }
    }

    /**
     * <p>A callback object for tracking the progress of a {@link CaptureRequest} submitted to the
     * camera device.</p>
     *
     * <p>This callback is invoked when a request triggers a capture to start,
     * and when the capture is complete. In case on an error capturing an image,
     * the error method is triggered instead of the completion method.</p>
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     */
    public static abstract class CaptureCallback {

        /**
         * This constant is used to indicate that no images were captured for
         * the request.
         *
         * @hide
         */
        public static final int NO_FRAMES_CAPTURED = -1;

        /**
         * This method is called when the camera device has started capturing
         * the output image for the request, at the beginning of image exposure, or
         * when the camera device has started processing an input image for a reprocess
         * request.
         *
         * <p>For a regular capture request, this callback is invoked right as
         * the capture of a frame begins, so it is the most appropriate time
         * for playing a shutter sound, or triggering UI indicators of capture.</p>
         *
         * <p>The request that is being used for this capture is provided, along
         * with the actual timestamp for the start of exposure. For a reprocess
         * request, this timestamp will be the input image's start of exposure
         * which matches {@link CaptureResult#SENSOR_TIMESTAMP the result timestamp field}
         * of the {@link TotalCaptureResult} that was used to
         * {@link CameraDevice#createReprocessCaptureRequest create the reprocess request}.
         * This timestamp matches the timestamps that will be
         * included in {@link CaptureResult#SENSOR_TIMESTAMP the result timestamp field},
         * and in the buffers sent to each output Surface. These buffer
         * timestamps are accessible through, for example,
         * {@link android.media.Image#getTimestamp() Image.getTimestamp()} or
         * {@link android.graphics.SurfaceTexture#getTimestamp()}.
         * The frame number included is equal to the frame number that will be included in
         * {@link CaptureResult#getFrameNumber}.</p>
         *
         * <p>For the simplest way to play a shutter sound camera shutter or a
         * video recording start/stop sound, see the
         * {@link android.media.MediaActionSound} class.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         * @param request the request for the capture that just begun
         * @param timestamp the timestamp at start of capture for a regular request, or
         *                  the timestamp at the input image's start of capture for a
         *                  reprocess request, in nanoseconds.
         * @param frameNumber the frame number for this capture
         *
         * @see android.media.MediaActionSound
         */
        public void onCaptureStarted(@NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            // default empty implementation
        }

        /**
         * This method is called when some results from an image capture are
         * available.
         *
         * <p>The result provided here will contain some subset of the fields of
         * a full result. Multiple onCapturePartial calls may happen per
         * capture; a given result field will only be present in one partial
         * capture at most. The final onCaptureCompleted call will always
         * contain all the fields, whether onCapturePartial was called or
         * not.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         * @param request The request that was given to the CameraDevice
         * @param result The partial output metadata from the capture, which
         * includes a subset of the CaptureResult fields.
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         *
         * @hide
         */
        public void onCapturePartial(CameraCaptureSession session,
                CaptureRequest request, CaptureResult result) {
            // default empty implementation
        }

        /**
         * This method is called when an image capture makes partial forward progress; some
         * (but not all) results from an image capture are available.
         *
         * <p>The result provided here will contain some subset of the fields of
         * a full result. Multiple {@link #onCaptureProgressed} calls may happen per
         * capture; a given result field will only be present in one partial
         * capture at most. The final {@link #onCaptureCompleted} call will always
         * contain all the fields (in particular, the union of all the fields of all
         * the partial results composing the total result).</p>
         *
         * <p>For each request, some result data might be available earlier than others. The typical
         * delay between each partial result (per request) is a single frame interval.
         * For performance-oriented use-cases, applications should query the metadata they need
         * to make forward progress from the partial results and avoid waiting for the completed
         * result.</p>
         *
         * <p>For a particular request, {@link #onCaptureProgressed} may happen before or after
         * {@link #onCaptureStarted}.</p>
         *
         * <p>Each request will generate at least {@code 1} partial results, and at most
         * {@link CameraCharacteristics#REQUEST_PARTIAL_RESULT_COUNT} partial results.</p>
         *
         * <p>Depending on the request settings, the number of partial results per request
         * will vary, although typically the partial count could be the same as long as the
         * camera device subsystems enabled stay the same.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         * @param request The request that was given to the CameraDevice
         * @param partialResult The partial output metadata from the capture, which
         * includes a subset of the {@link TotalCaptureResult} fields.
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         */
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            // default empty implementation
        }

        /**
         * This method is called when an image capture has fully completed and all the
         * result metadata is available.
         *
         * <p>This callback will always fire after the last {@link #onCaptureProgressed};
         * in other words, no more partial results will be delivered once the completed result
         * is available.</p>
         *
         * <p>For performance-intensive use-cases where latency is a factor, consider
         * using {@link #onCaptureProgressed} instead.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         * @param request The request that was given to the CameraDevice
         * @param result The total output metadata from the capture, including the
         * final capture parameters and the state of the camera system during
         * capture.
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         */
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            // default empty implementation
        }

        /**
         * This method is called instead of {@link #onCaptureCompleted} when the
         * camera device failed to produce a {@link CaptureResult} for the
         * request.
         *
         * <p>Other requests are unaffected, and some or all image buffers from
         * the capture may have been pushed to their respective output
         * streams.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session
         *            The session returned by {@link CameraDevice#createCaptureSession}
         * @param request
         *            The request that was given to the CameraDevice
         * @param failure
         *            The output failure from the capture, including the failure reason
         *            and the frame number.
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         */
        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            // default empty implementation
        }

        /**
         * This method is called independently of the others in CaptureCallback,
         * when a capture sequence finishes and all {@link CaptureResult}
         * or {@link CaptureFailure} for it have been returned via this listener.
         *
         * <p>In total, there will be at least one result/failure returned by this listener
         * before this callback is invoked. If the capture sequence is aborted before any
         * requests have been processed, {@link #onCaptureSequenceAborted} is invoked instead.</p>
         *
         * <p>The default implementation does nothing.</p>
         *
         * @param session
         *            The session returned by {@link CameraDevice#createCaptureSession}
         * @param sequenceId
         *            A sequence ID returned by the {@link #capture} family of functions.
         * @param frameNumber
         *            The last frame number (returned by {@link CaptureResult#getFrameNumber}
         *            or {@link CaptureFailure#getFrameNumber}) in the capture sequence.
         *
         * @see CaptureResult#getFrameNumber()
         * @see CaptureFailure#getFrameNumber()
         * @see CaptureResult#getSequenceId()
         * @see CaptureFailure#getSequenceId()
         * @see #onCaptureSequenceAborted
         */
        public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session,
                int sequenceId, long frameNumber) {
            // default empty implementation
        }

        /**
         * This method is called independently of the others in CaptureCallback,
         * when a capture sequence aborts before any {@link CaptureResult}
         * or {@link CaptureFailure} for it have been returned via this listener.
         *
         * <p>Due to the asynchronous nature of the camera device, not all submitted captures
         * are immediately processed. It is possible to clear out the pending requests
         * by a variety of operations such as {@link CameraCaptureSession#stopRepeating} or
         * {@link CameraCaptureSession#abortCaptures}. When such an event happens,
         * {@link #onCaptureSequenceCompleted} will not be called.</p>
         *
         * <p>The default implementation does nothing.</p>
         *
         * @param session
         *            The session returned by {@link CameraDevice#createCaptureSession}
         * @param sequenceId
         *            A sequence ID returned by the {@link #capture} family of functions.
         *
         * @see CaptureResult#getFrameNumber()
         * @see CaptureFailure#getFrameNumber()
         * @see CaptureResult#getSequenceId()
         * @see CaptureFailure#getSequenceId()
         * @see #onCaptureSequenceCompleted
         */
        public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session,
                int sequenceId) {
            // default empty implementation
        }

        /**
         * <p>This method is called if a single buffer for a capture could not be sent to its
         * destination surface.</p>
         *
         * <p>If the whole capture failed, then {@link #onCaptureFailed} will be called instead. If
         * some but not all buffers were captured but the result metadata will not be available,
         * then onCaptureFailed will be invoked with {@link CaptureFailure#wasImageCaptured}
         * returning true, along with one or more calls to {@link #onCaptureBufferLost} for the
         * failed outputs.</p>
         *
         * @param session
         *            The session returned by {@link CameraDevice#createCaptureSession}
         * @param request
         *            The request that was given to the CameraDevice
         * @param target
         *            The target Surface that the buffer will not be produced for
         * @param frameNumber
         *            The frame number for the request
         */
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
            // default empty implementation
        }
    }

}
