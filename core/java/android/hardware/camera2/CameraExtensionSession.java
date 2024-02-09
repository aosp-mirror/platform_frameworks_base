/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.camera2.utils.HashCodeHelpers;

import com.android.internal.camera.flags.Flags;

import java.util.concurrent.Executor;

/**
 * A camera capture session that enables access to device-specific camera extensions, which
 * often use multi-frame bursts and sophisticated post-process algorithms for image capture.
 *
 * <p>The capture session will be returned after a successful call to
 * {@link CameraDevice#createExtensionSession} as part of the argument
 * in the registered state callback {@link StateCallback#onConfigured}
 * method. </p>
 *
 * <p>Note that CameraExtensionSession is currently limited to a maximum of two output
 * surfaces for continuous repeating and multi-frame processing respectively. Some
 * features such as capture settings will not be supported as the device-specific
 * Extension is allowed to override all capture parameters.</p>
 *
 * <p>Information about support for specific device-specific extensions can be queried
 * from {@link CameraExtensionCharacteristics}. </p>
 */
public abstract class CameraExtensionSession implements AutoCloseable {
     /** @hide */
    public CameraExtensionSession () {}

    /**
     * A callback object for tracking the progress of a
     * {@link CaptureRequest} submitted to the camera device.
     *
     * <p>This callback is invoked when a request triggers a capture to start,
     * and when the device-specific Extension post processing begins. In case of an
     * error capturing an image, the error method is triggered instead of
     * the completion method.</p>
     *
     * @see #capture
     * @see #setRepeatingRequest
     */
    public static abstract class ExtensionCaptureCallback {

        /**
         * This method is called when the camera device has started
         * capturing the initial input image of the device-specific extension
         * post-process request.
         *
         * <p>This callback is invoked right as the capture of a frame begins,
         * so it is the most appropriate time for playing a shutter sound,
         * or triggering UI indicators of capture.</p>
         *
         * <p>The request that is being used for this capture is provided,
         * along with the actual timestamp for the start of exposure.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session   the session received during
         *                  {@link StateCallback#onConfigured(CameraExtensionSession)}
         * @param request   the request for the capture that just begun
         * @param timestamp the timestamp at start of capture for repeating
         *                  request or the timestamp at start of capture of the
         *                  first frame in a multi-frame capture.
         */
        public void onCaptureStarted(@NonNull CameraExtensionSession session,
                @NonNull CaptureRequest request, long timestamp) {
            // default empty implementation
        }

        /**
         * This method is called when an image (or images in case of multi-frame
         * capture) is captured and device-specific extension
         * processing is triggered.
         *
         * <p>Each request will generate at most {@code 1}
         * {@link #onCaptureProcessStarted}.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session the session received during
         *                {@link StateCallback#onConfigured(CameraExtensionSession)}
         * @param request The request that was given to the CameraExtensionSession
         *
         * @see #capture
         * @see #setRepeatingRequest
         */
        public void onCaptureProcessStarted(@NonNull CameraExtensionSession session,
                @NonNull CaptureRequest request) {
            // default empty implementation
        }

        /**
         * This method is called instead of
         * {@link #onCaptureProcessStarted} when the camera device failed
         * to produce the required input for the device-specific extension. The
         * cause could be a failed camera capture request, a failed
         * capture result or dropped camera frame.
         *
         * <p>Other requests are unaffected, and some or all image buffers
         * from the capture may have been pushed to their respective output
         * streams.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session the session received during
         *                {@link StateCallback#onConfigured(CameraExtensionSession)}
         * @param request The request that was given to the CameraDevice
         *
         * @see #capture
         * @see #setRepeatingRequest
         */
        public void onCaptureFailed(@NonNull CameraExtensionSession session,
                @NonNull CaptureRequest request) {
            // default empty implementation
        }

        /**
         * This method is called instead of
         * {@link #onCaptureProcessStarted} when the camera device failed
         * to produce the required input for the device-specific extension. The
         * cause could be a failed camera capture request, a failed
         * capture result or dropped camera frame. More information about
         * the reason is included in the 'failure' argument.
         *
         * <p>Other requests are unaffected, and some or all image buffers
         * from the capture may have been pushed to their respective output
         * streams.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session the session received during
         *                {@link StateCallback#onConfigured(CameraExtensionSession)}
         * @param request The request that was given to the CameraDevice
         * @param failure The capture failure reason
         *
         * @see #capture
         * @see #setRepeatingRequest
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        public void onCaptureFailed(@NonNull CameraExtensionSession session,
                @NonNull CaptureRequest request, @CaptureFailure.FailureReason int failure) {
            // default empty implementation
        }

        /**
         * This method is called independently of the others in
         * ExtensionCaptureCallback, when a capture sequence finishes.
         *
         * <p>In total, there will be at least one
         * {@link #onCaptureProcessStarted}/{@link #onCaptureFailed}
         * invocation before this callback is triggered. If the capture
         * sequence is aborted before any requests have begun processing,
         * {@link #onCaptureSequenceAborted} is invoked instead.</p>
         *
         * <p>The default implementation does nothing.</p>
         *
         * @param session    the session received during
         *                   {@link StateCallback#onConfigured(CameraExtensionSession)}
         * @param sequenceId A sequence ID returned by the {@link #capture}
         *                   family of functions.
         * @see #onCaptureSequenceAborted
         */
        public void onCaptureSequenceCompleted(@NonNull CameraExtensionSession session,
                int sequenceId) {
            // default empty implementation
        }

        /**
         * This method is called when a capture sequence aborts.
         *
         * <p>Due to the asynchronous nature of the camera device, not all
         * submitted captures are immediately processed. It is possible to
         * clear out the pending requests by a variety of operations such
         * as {@link CameraExtensionSession#stopRepeating}. When such an event
         * happens, {@link #onCaptureProcessStarted} will not be called.</p>
         *
         * <p>The default implementation does nothing.</p>
         *
         * @param session    the session received during
         *                   {@link StateCallback#onConfigured(CameraExtensionSession)}
         * @param sequenceId A sequence ID returned by the {@link #capture}
         *                   family of functions.
         * @see #onCaptureProcessStarted
         */
        public void onCaptureSequenceAborted(@NonNull CameraExtensionSession session,
                int sequenceId) {
            // default empty implementation
        }

        /**
         * This method is called when an image capture has fully completed and all the
         * result metadata is available.
         *
         * <p>This callback will only be called in case
         * {@link CameraExtensionCharacteristics#getAvailableCaptureResultKeys} returns a valid
         * non-empty list.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session The session received during
         *                {@link StateCallback#onConfigured(CameraExtensionSession)}
         * @param request The request that was given to the CameraDevice
         * @param result The total output metadata from the capture, which only includes the
         * capture result keys advertised as supported in
         * {@link CameraExtensionCharacteristics#getAvailableCaptureResultKeys}.
         *
         * @see #capture
         * @see #setRepeatingRequest
         * @see CameraExtensionCharacteristics#getAvailableCaptureResultKeys
         */
        public void onCaptureResultAvailable(@NonNull CameraExtensionSession session,
                @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            // default empty implementation
        }

        /**
         * This method is called when image capture processing is ongoing between
         * {@link #onCaptureProcessStarted} and the processed still capture frame returning
         * to the client surface.
         *
         * <p>The value included in the arguments provides clients with an estimate
         * of the post-processing progress which could take significantly more time
         * relative to the rest of the {@link #capture} sequence.</p>
         *
         * <p>The callback will be triggered only by extensions that return {@code true}
         * from calls
         * {@link CameraExtensionCharacteristics#isCaptureProcessProgressAvailable}.</p>
         *
         * <p>If support for this callback is present, then clients will be notified at least once
         * with progress value 100.</p>
         *
         * <p>The callback will be triggered only for still capture requests {@link #capture} and
         * is not supported for repeating requests {@link #setRepeatingRequest}.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param session The session received during
         *                {@link StateCallback#onConfigured(CameraExtensionSession)}
         * @param request The request that was given to the CameraDevice
         * @param progress Value between 0 and 100 (inclusive) indicating the current
         *                post-processing progress
         *
         * @see CameraExtensionCharacteristics#isCaptureProcessProgressAvailable
         *
         */
        public void onCaptureProcessProgressed(@NonNull CameraExtensionSession session,
                @NonNull CaptureRequest request, @IntRange(from = 0, to = 100) int progress) {
            // default empty implementation
        }
    }

    /**
     * A callback object for receiving updates about the state of a camera extension session.
     *
     */
    public static abstract class StateCallback {

        /**
         * This method is called when the camera device has finished configuring itself, and the
         * session can start processing capture requests.
         *
         * <p>If the camera device configuration fails, then {@link #onConfigureFailed} will
         * be invoked instead of this callback.</p>
         *
         * @param session A valid extension session
         */
        public abstract void onConfigured(@NonNull CameraExtensionSession session);

        /**
         * This method is called if the session cannot be configured as requested.
         *
         * <p>This can happen if the set of requested outputs contains unsupported sizes,
         * too many outputs are requested at once or when trying to initialize multiple
         * concurrent extension sessions from two (or more) separate camera devices
         * or the camera device encounters an unrecoverable error during configuration.</p>
         *
         * <p>The session is considered to be closed, and all methods called on it after this
         * callback is invoked will throw an IllegalStateException.</p>
         *
         * @param session the session instance that failed to configure
         */
        public abstract void onConfigureFailed(@NonNull CameraExtensionSession session);

        /**
         * This method is called when the session is closed.
         *
         * <p>A session is closed when a new session is created by the parent camera device,
         * or when the parent camera device is closed (either by the user closing the device,
         * or due to a camera device disconnection or fatal error).</p>
         *
         * <p>Once a session is closed, all methods on it will throw an IllegalStateException, and
         * any repeating requests are stopped (as if {@link #stopRepeating()} was called).
         * However, any in-progress capture requests submitted to the session will be completed
         * as normal.</p>
         *
         * @param session the session received during
         *                {@link StateCallback#onConfigured(CameraExtensionSession)}
         */
        public void onClosed(@NonNull CameraExtensionSession session) {
            // default empty implementation
        }
    }

    /**
     * Get the camera device that this session is created for.
     */
    @NonNull
    public android.hardware.camera2.CameraDevice getDevice() {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Submit a request for device-specific processing using input
     * from the camera device, to produce a single high-quality output result.
     *
     * <p>Note that single capture requests currently do not support
     * client parameters except for controls advertised in
     * {@link CameraExtensionCharacteristics#getAvailableCaptureRequestKeys}.
     * The rest of the settings included in the request will be entirely overridden by
     * the device-specific extension. </p>
     *
     * <p> If {@link CameraExtensionCharacteristics#isPostviewAvailable} returns
     * false, the {@link CaptureRequest.Builder#addTarget} will support only one
     * ImageFormat.YUV_420_888 or ImageFormat.JPEG target surface. {@link CaptureRequest}
     * arguments that include further targets will cause IllegalArgumentException to be thrown.
     * If postview is available, {@link CaptureRequest.Builder#addTarget} will support up to two
     * ImageFormat.YUV_420_888 or ImageFormat.JPEG target surfaces for the still capture and
     * postview. IllegalArgumentException will be thrown if a postview target is added without
     * a still capture target, if more than two target surfaces are added, or if the surface
     * formats for postview and capture are not equivalent.
     *
     * <p>Starting with Android {@link android.os.Build.VERSION_CODES#TIRAMISU} single capture
     * requests will also support the preview {@link android.graphics.ImageFormat#PRIVATE} target
     * surface. These can typically be used for enabling AF/AE triggers. Do note, that single
     * capture requests referencing both output surfaces remain unsupported.</p>
     *
     * <p>Each request will produce one new frame for one target Surface, set
     * with the CaptureRequest builder's
     * {@link CaptureRequest.Builder#addTarget} method.</p>
     *
     * <p>Multiple requests can be in progress at once. Requests are
     * processed in first-in, first-out order.</p>
     *
     * <p>Requests submitted through this method have higher priority than
     * those submitted through {@link #setRepeatingRequest}, and will be
     * processed as soon as the current repeat processing completes.</p>
     *
     * @param request the settings for this capture
     * @param executor the executor which will be used for invoking the
     *                 listener.
     * @param listener The callback object to notify once this request has
     *                 been processed.
     * @return int A unique capture sequence ID used by
     * {@link ExtensionCaptureCallback#onCaptureSequenceCompleted}.
     * @throws CameraAccessException    if the camera device is no longer
     *                                  connected or has encountered a fatal error
     * @throws IllegalStateException    if this session is no longer active,
     *                                  either because the session was explicitly closed, a new
     *                                  session has been created or the camera device has been
     *                                  closed.
     * @throws IllegalArgumentException if the request targets no Surfaces
     *                                  or Surfaces that are not configured as outputs for this
     *                                  session; or the request targets a set of Surfaces that
     *                                  cannot be submitted simultaneously.
     */
    public int capture(@NonNull CaptureRequest request,
            @NonNull Executor executor,
            @NonNull ExtensionCaptureCallback listener) throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Request endlessly repeating device-specific extension processing of
     * camera images.
     *
     * <p>With this method, the camera device will continually capture images
     * and process them using the device-specific extension at the maximum
     * rate possible.</p>
     *
     * <p>Note that repeating capture requests currently do not support
     * client parameters except for controls advertised in
     * {@link CameraExtensionCharacteristics#getAvailableCaptureRequestKeys}.
     * The rest of the settings included in the request will be entirely overridden by
     * the device-specific extension. </p>
     *
     * <p>The {@link CaptureRequest.Builder#addTarget} supports only one
     * target surface. {@link CaptureRequest} arguments that include further
     * targets will cause IllegalArgumentException to be thrown.</p>
     *
     * <p>Repeating requests are a simple way for an application to maintain a
     * preview or other continuous stream of frames.</p>
     *
     * <p>Repeat requests have lower priority than those submitted
     * through {@link #capture}, so if  {@link #capture} is called when a
     * repeating request is active, the capture request will be processed
     * before any further repeating requests are processed.</p>
     *
     * <p>To stop the repeating capture, call {@link #stopRepeating}.</p>
     *
     * <p>Calling this method will replace any earlier repeating request.</p>
     *
     * @param request the request to repeat indefinitely
     * @param executor the executor which will be used for invoking the
     *                 listener.
     * @param listener The callback object to notify every time the
     *                 request finishes processing.
     * @return int A unique capture sequence ID used by
     * {@link ExtensionCaptureCallback#onCaptureSequenceCompleted}.
     * @throws CameraAccessException    if the camera device is no longer
     *                                  connected or has encountered a fatal error
     * @throws IllegalStateException    if this session is no longer active,
     *                                  either because the session was explicitly closed, a new
     *                                  session has been created or the camera device has been
     *                                  closed.
     * @throws IllegalArgumentException If the request references no
     *                                  Surfaces or references Surfaces that are not currently
     *                                  configured as outputs.
     * @see #capture
     */
    public int setRepeatingRequest(@NonNull CaptureRequest request,
            @NonNull Executor executor,
            @NonNull ExtensionCaptureCallback listener) throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Cancel any ongoing repeating capture set by
     * {@link #setRepeatingRequest setRepeatingRequest}. Has no effect on
     * requests submitted through {@link #capture capture}.
     *
     * <p>Any currently in-flight captures will still complete.</p>
     *
     * @throws CameraAccessException if the camera device is no longer
     *                               connected or has  encountered a fatal error
     * @throws IllegalStateException if this session is no longer active,
     *                               either because the session was explicitly closed, a new
     *                               session has been created or the camera device has been closed.
     * @see #setRepeatingRequest
     */
    public void stopRepeating() throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Realtime calculated still {@link #capture} latency.
     *
     * @see #getRealtimeStillCaptureLatency()
     */
    public final static class StillCaptureLatency {
        private final long mCaptureLatency, mProcessingLatency;

        public StillCaptureLatency(long captureLatency, long processingLatency) {
            mCaptureLatency = captureLatency;
            mProcessingLatency = processingLatency;
        }
        /**
         * Return the capture latency from
         * {@link ExtensionCaptureCallback#onCaptureStarted} until
         * {@link ExtensionCaptureCallback#onCaptureProcessStarted}.
         *
         * @return The realtime capture latency in milliseconds.
         */
        public long getCaptureLatency() {
            return mCaptureLatency;
        }

        /**
         * Return the estimated post-processing latency from
         * {@link ExtensionCaptureCallback#onCaptureProcessStarted} until the processed frame
         * returns to the client.
         *
         * @return returns post-processing latency in milliseconds
         */
        public long getProcessingLatency() {
            return mProcessingLatency;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            StillCaptureLatency latency = (StillCaptureLatency) o;

            if (mCaptureLatency != latency.mCaptureLatency) return false;
            if (mProcessingLatency != latency.mProcessingLatency) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return HashCodeHelpers.hashCode(mCaptureLatency, mProcessingLatency);
        }

        @Override
        public String toString() {
            return "StillCaptureLatency(processingLatency:" + mProcessingLatency +
                    ", captureLatency: " + mCaptureLatency + ")";
        }
    }

    /**
     * Return the realtime still {@link #capture} latency.
     *
     * <p>The estimations will take into account the current environment conditions, the camera
     * state and will include the time spent processing the multi-frame capture request along with
     * any additional time for encoding of the processed buffer if necessary.</p>
     *
     * @return The realtime still capture latency,
     * or {@code null} if the estimation is not supported.
     */
    @Nullable
    public StillCaptureLatency getRealtimeStillCaptureLatency() throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }

    /**
     * Close this capture session asynchronously.
     *
     * <p>Closing a session frees up the target output Surfaces of the session
     * for reuse with either a new session, or to other APIs that can draw
     * to Surfaces.</p>
     *
     * <p>Note that creating a new capture session with
     * {@link android.hardware.camera2.CameraDevice#createCaptureSession} or
     * {@link android.hardware.camera2.CameraDevice#createExtensionSession}
     * will close any existing capture session automatically, and call the
     * older session listener's {@link StateCallback#onClosed} callback.
     * Using
     * {@link android.hardware.camera2.CameraDevice#createCaptureSession} or
     * {@link android.hardware.camera2.CameraDevice#createExtensionSession}
     * directly without closing is the recommended approach for quickly
     * switching to a new session, since unchanged target outputs can be
     * reused more efficiently.</p>
     *
     * <p>Once a session is closed, all methods on it will throw an
     * IllegalStateException, and any repeating requests are
     * stopped (as if {@link #stopRepeating()} was called).</p>
     *
     * <p>Closing a session is idempotent; closing more than once has no
     * effect.</p>
     */
    public void close() throws CameraAccessException {
        throw new UnsupportedOperationException("Subclasses must override this method");
    }
}
