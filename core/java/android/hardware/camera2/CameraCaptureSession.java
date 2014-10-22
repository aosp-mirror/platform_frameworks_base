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

import android.os.Handler;
import java.util.List;

/**
 * A configured capture session for a {@link CameraDevice}, used for capturing
 * images from the camera.
 *
 * <p>A CameraCaptureSession is created by providing a set of target output surfaces to
 * {@link CameraDevice#createCaptureSession createCaptureSession}. Once created, the session is
 * active until a new session is created by the camera device, or the camera device is closed.</p>
 *
 * <p>Creating a session is an expensive operation and can take several hundred milliseconds, since
 * it requires configuring the camera device's internal pipelines and allocating memory buffers for
 * sending images to the desired targets. Therefore the setup is done asynchronously, and
 * {@link CameraDevice#createCaptureSession createCaptureSession} will send the ready-to-use
 * CameraCaptureSession to the provided listener's
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
     * Get the camera device that this session is created for.
     */
    public abstract CameraDevice getDevice();

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
     * <p>Multiple requests can be in progress at once. They are processed in
     * first-in, first-out order, with minimal delays between each
     * capture. Requests submitted through this method have higher priority than
     * those submitted through {@link #setRepeatingRequest} or
     * {@link #setRepeatingBurst}, and will be processed as soon as the current
     * repeat/repeatBurst processing completes.</p>
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
     *                                  configured as outputs for this session. Or if the handler is
     *                                  null, the listener is not null, and the calling thread has
     *                                  no looper.
     *
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see #abortCaptures
     */
    public abstract int capture(CaptureRequest request, CaptureCallback listener, Handler handler)
            throws CameraAccessException;

    /**
     * Submit a list of requests to be captured in sequence as a burst. The
     * burst will be captured in the minimum amount of time possible, and will
     * not be interleaved with requests submitted by other capture or repeat
     * calls.
     *
     * <p>The requests will be captured in order, each capture producing one {@link CaptureResult}
     * and image buffers for one or more target {@link android.view.Surface surfaces}. The target
     * surfaces (set with {@link CaptureRequest.Builder#addTarget}) must be a subset of the surfaces
     * provided when this capture session was created.</p>
     *
     * <p>The main difference between this method and simply calling
     * {@link #capture} repeatedly is that this method guarantees that no
     * other requests will be interspersed with the burst.</p>
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
     * @throws IllegalArgumentException If the requests target no Surfaces or Surfaces not currently
     *                                  configured as outputs. Or if the handler is null, the
     *                                  listener is not null, and the calling thread has no looper.
     *
     * @see #capture
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see #abortCaptures
     */
    public abstract int captureBurst(List<CaptureRequest> requests, CaptureCallback listener,
            Handler handler) throws CameraAccessException;

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
     * @throws IllegalArgumentException If the requests reference no Surfaces or Surfaces that are
     *                                  not currently configured as outputs. Or if the handler is
     *                                  null, the listener is not null, and the calling thread has
     *                                  no looper. Or if no requests were passed in.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingBurst
     * @see #stopRepeating
     * @see #abortCaptures
     */
    public abstract int setRepeatingRequest(CaptureRequest request, CaptureCallback listener,
            Handler handler) throws CameraAccessException;

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
     * @throws IllegalArgumentException If the requests reference no Surfaces or Surfaces not
     *                                  currently configured as outputs. Or if the handler is null,
     *                                  the listener is not null, and the calling thread has no
     *                                  looper. Or if no requests were passed in.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #stopRepeating
     * @see #abortCaptures
     */
    public abstract int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback listener,
            Handler handler) throws CameraAccessException;

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
     * @see StateCallback#onIdle
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
     * {@link CameraDevice#createCaptureSession}, at the cost of discarding in-progress work. It
     * must be called before the new session is created. Once all pending requests are either
     * completed or thrown away, the {@link StateCallback#onReady} callback will be called,
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
     */
    public abstract void abortCaptures() throws CameraAccessException;

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
        public abstract void onConfigured(CameraCaptureSession session);

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
        public abstract void onConfigureFailed(CameraCaptureSession session);

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
        public void onReady(CameraCaptureSession session) {
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
        public void onActive(CameraCaptureSession session) {
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
        public void onClosed(CameraCaptureSession session) {
            // default empty implementation
        }
    }

    /**
     * Temporary for migrating to Callback naming
     * @hide
     */
    public static abstract class StateListener extends StateCallback {
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
         * the output image for the request, at the beginning of image exposure.
         *
         * <p>This callback is invoked right as the capture of a frame begins,
         * so it is the most appropriate time for playing a shutter sound,
         * or triggering UI indicators of capture.</p>
         *
         * <p>The request that is being used for this capture is provided, along
         * with the actual timestamp for the start of exposure. This timestamp
         * matches the timestamp that will be included in
         * {@link CaptureResult#SENSOR_TIMESTAMP the result timestamp field},
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
         * @param timestamp the timestamp at start of capture, in nanoseconds.
         * @param frameNumber the frame number for this capture
         *
         * @see android.media.MediaActionSound
         */
        public void onCaptureStarted(CameraCaptureSession session,
                CaptureRequest request, long timestamp, long frameNumber) {
            // Temporary trampoline for API change transition
            onCaptureStarted(session, request, timestamp);
        }

        /**
         * Temporary for API change transition
         * @hide
         */
        public void onCaptureStarted(CameraCaptureSession session,
                CaptureRequest request, long timestamp) {
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
        public void onCaptureProgressed(CameraCaptureSession session,
                CaptureRequest request, CaptureResult partialResult) {
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
        public void onCaptureCompleted(CameraCaptureSession session,
                CaptureRequest request, TotalCaptureResult result) {
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
        public void onCaptureFailed(CameraCaptureSession session,
                CaptureRequest request, CaptureFailure failure) {
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
        public void onCaptureSequenceCompleted(CameraCaptureSession session,
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
        public void onCaptureSequenceAborted(CameraCaptureSession session,
                int sequenceId) {
            // default empty implementation
        }
    }

    /**
     * Temporary for migrating to Callback naming
     * @hide
     */
    public static abstract class CaptureListener extends CaptureCallback {
    }

}
