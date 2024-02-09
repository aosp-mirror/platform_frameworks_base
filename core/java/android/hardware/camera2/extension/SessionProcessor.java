/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.camera2.extension;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.impl.CameraExtensionUtils.HandlerExecutor;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.camera.flags.Flags;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Interface for creating Camera2 CameraCaptureSessions with extension
 * enabled based on the advanced extension interface.
 *
 * <p><pre>
 * The flow of a extension session is shown below:
 * (1) {@link #initSession}: Camera framework prepares streams
 * configuration for creating CameraCaptureSession. Output surfaces for
 * Preview and ImageCapture are passed in and implementation is
 * responsible for outputting the results to these surfaces.
 *
 * (2) {@link #onCaptureSessionStart}: It is called after
 * CameraCaptureSession is configured. A {@link RequestProcessor} is
 * passed for the implementation to send repeating requests and single
 * requests.
 *
 * (3) {@link #startRepeating}:  Camera framework will call this method to
 * start the repeating request after CameraCaptureSession is called.
 * Implementations should start the repeating request by  {@link
 * RequestProcessor}. Implementations can also update the repeating
 * request if needed later.
 *
 * (4) {@link #setParameters}: The passed parameters will be attached
 * to the repeating request and single requests but the implementation can
 * choose to apply some of them only.
 *
 * (5) {@link #startMultiFrameCapture}: It is called when apps want
 * to start a multi-frame image capture.  {@link CaptureCallback} will be
 * called to report the status and the output image will be written to the
 * capture output surface specified in {@link #initSession}.
 *
 * (5) {@link #onCaptureSessionEnd}: It is called right BEFORE
 * CameraCaptureSession.close() is called.
 *
 * (6) {@link #deInitSession}: called when CameraCaptureSession is closed.
 * </pre>
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_CONCERT_MODE)
public abstract class SessionProcessor {
    private static final String TAG = "SessionProcessor";
    private CameraUsageTracker mCameraUsageTracker;

    /**
     * Initialize a session process instance
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public SessionProcessor() {}

    void setCameraUsageTracker(CameraUsageTracker tracker) {
        mCameraUsageTracker = tracker;
    }

    /**
     * Callback for notifying the status of {@link
     * #startMultiFrameCapture} and {@link #startRepeating}.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public interface CaptureCallback {
        /**
         * This method is called when the camera device has started
         * capturing the initial input
         * image.
         *
         * For a multi-frame capture, the method is called when the
         * CameraCaptureSession.CaptureCallback onCaptureStarted of first
         * frame is called and its timestamp is directly forwarded to
         * timestamp parameter of this method.
         *
         * @param captureSequenceId id of the current capture sequence
         * @param timestamp         the timestamp at start of capture for
         *                          repeating request or the timestamp at
         *                          start of capture of the
         *                          first frame in a multi-frame capture,
         *                          in nanoseconds.
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureStarted(int captureSequenceId, long timestamp);

        /**
         * This method is called when an image (or images in case of
         * multi-frame capture) is captured and device-specific extension
         * processing is triggered.
         *
         * @param captureSequenceId id of the current capture sequence
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureProcessStarted(int captureSequenceId);

        /**
         * This method is called instead of
         * {@link #onCaptureProcessStarted} when the camera device failed
         * to produce the required input for the device-specific
         * extension. The cause could be a failed camera capture request,
         * a failed capture result or dropped camera frame.
         *
         * @param captureSequenceId id of the current capture sequence
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureFailed(int captureSequenceId);

        /**
         * This method is called independently of the others in the
         * CaptureCallback, when a capture sequence finishes.
         *
         * <p>In total, there will be at least one
         * {@link #onCaptureProcessStarted}/{@link #onCaptureFailed}
         * invocation before this callback is triggered. If the capture
         * sequence is aborted before any requests have begun processing,
         * {@link #onCaptureSequenceAborted} is invoked instead.</p>
         *
         * @param captureSequenceId id of the current capture sequence
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureSequenceCompleted(int captureSequenceId);

        /**
         * This method is called when a capture sequence aborts.
         *
         * @param captureSequenceId id of the current capture sequence
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureSequenceAborted(int captureSequenceId);

        /**
         * Capture result callback that needs to be called when the
         * process capture results are ready as part of frame
         * post-processing.
         *
         * This callback will fire after {@link #onCaptureStarted}, {@link
         * #onCaptureProcessStarted} and before {@link
         * #onCaptureSequenceCompleted}. The callback is not expected to
         * fire in case of capture failure  {@link #onCaptureFailed} or
         * capture abort {@link #onCaptureSequenceAborted}.
         *
         * @param shutterTimestamp The timestamp at the start
         *                         of capture. The same timestamp value
         *                         passed to {@link #onCaptureStarted}.
         * @param requestId  the capture request id that generated the
         *                   capture results. This is the return value of
         *                   either {@link #startRepeating} or {@link
         *                   #startMultiFrameCapture}.
         * @param results  The supported capture results. Do note
         *                  that if results 'android.jpeg.quality' and
         *                  android.jpeg.orientation' are present in the
         *                  process capture input results, then the values
         *                  must also be passed as part of this callback.
         *                  The camera framework guarantees that those two
         *                  settings and results are always supported and
         *                  applied by the corresponding framework.
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureCompleted(long shutterTimestamp, int requestId,
                @NonNull CaptureResult results);
    }

    /**
     * Initializes the session for the extension. This is where the
     * extension implementations allocate resources for
     * preparing a CameraCaptureSession. After initSession() is called,
     * the camera ID, cameraCharacteristics and context will not change
     * until deInitSession() has been called.
     *
     * <p>The framework specifies the output surface configurations for
     * preview using the 'previewSurface' argument and for still capture
     * using the 'imageCaptureSurface' argument and implementations must
     * return a {@link ExtensionConfiguration} which consists of a list of
     * {@link CameraOutputSurface} and session parameters. The {@link
     * ExtensionConfiguration} will be used to configure the
     * CameraCaptureSession.
     *
     * <p>Implementations are responsible for outputting correct camera
     * images output to these output surfaces.</p>
     *
     * @param token Binder token that can be used to register a death
     *              notifier callback
     * @param cameraId  The camera2 id string of the camera.
     * @param map Maps camera ids to camera characteristics
     * @param previewSurface contains output surface for preview
     * @param imageCaptureSurface contains the output surface for image
     *                            capture
     * @return a {@link ExtensionConfiguration} consisting of a list of
     * {@link CameraOutputConfig} and session parameters which will decide
     * the  {@link android.hardware.camera2.params.SessionConfiguration}
     * for configuring the CameraCaptureSession. Please note that the
     * OutputConfiguration list may not be part of any
     * supported or mandatory stream combination BUT implementations must
     * ensure this list will always  produce a valid camera capture
     * session.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    @NonNull
    public abstract ExtensionConfiguration initSession(@NonNull IBinder token,
            @NonNull String cameraId, @NonNull CharacteristicsMap map,
            @NonNull CameraOutputSurface previewSurface,
            @NonNull CameraOutputSurface imageCaptureSurface);

    /**
     * Notify to de-initialize the extension. This callback will be
     * invoked after CameraCaptureSession is closed. After onDeInit() was
     * called, it is expected that the camera ID, cameraCharacteristics
     * will no longer hold and tear down any resources allocated
     * for this extension. Aborts all pending captures.
     * @param token Binder token that can be used to unlink any previously
     *              linked death notifier callbacks
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public abstract void deInitSession(@NonNull IBinder token);

    /**
     * This will be invoked once after the {@link
     * android.hardware.camera2.CameraCaptureSession}
     * has been created. {@link RequestProcessor} is passed for
     * implementations to submit single requests or set repeating
     * requests. This extension RequestProcessor will be valid to use
     * until onCaptureSessionEnd is called.
     * @param requestProcessor The request processor to be used for
     *                         managing capture requests
     * @param statsKey         Unique key that is associated with the
     *                         current Camera2 session and used by the
     *                         framework telemetry. The id can be referenced
     *                         by the extension, in case there is additional
     *                         extension specific telemetry that needs
     *                         to be linked to the regular capture session.
     *
     *
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public abstract void onCaptureSessionStart(@NonNull RequestProcessor requestProcessor,
            @NonNull String statsKey);

    /**
     * This will be invoked before the {@link
     * android.hardware.camera2.CameraCaptureSession} is
     * closed. {@link RequestProcessor} passed in onCaptureSessionStart
     * will no longer accept any requests after onCaptureSessionEnd()
     * returns.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public abstract void onCaptureSessionEnd();

    /**
     * Starts the repeating request after CameraCaptureSession is called.
     * Implementations should start the repeating request by {@link
     * RequestProcessor}. Implementations can also update the
     * repeating request when needed later.
     *
     * @param executor the executor which will be used for
     *                 invoking the callbacks
     * @param callback a callback to report the status.
     * @return the id of the capture sequence.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public abstract int startRepeating(@NonNull Executor executor,
            @NonNull CaptureCallback callback);

    /**
     * Stop the repeating request. To prevent implementations from not
     * calling stopRepeating, the framework will first stop the repeating
     * request of current CameraCaptureSession and call this API to signal
     * implementations that the repeating request was stopped and going
     * forward calling {@link RequestProcessor#setRepeating} will simply
     * do nothing.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public abstract void stopRepeating();

    /**
     * Start a multi-frame capture.
     *
     * When the capture is completed, {@link
     * CaptureCallback#onCaptureSequenceCompleted}
     * is called and {@code OnImageAvailableListener#onImageAvailable}
     * will also be called on the ImageReader that creates the image
     * capture output surface.
     *
     * <p>Only one capture can perform at a time. Starting a capture when
     * another capture is running  will cause onCaptureFailed to be called
     * immediately.
     *
     * @param executor the executor which will be used for
     *                 invoking the callbacks
     * @param callback a callback to report the status.
     * @return the id of the capture sequence.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public abstract int startMultiFrameCapture(@NonNull Executor executor,
            @NonNull CaptureCallback callback);

    /**
     * The camera framework will call these APIs to pass parameters from
     * the app to the extension implementation. It is expected that the
     * implementation would (eventually) update the repeating request if
     * the keys are supported. Setting a value to null explicitly un-sets
     * the value.
     *@param captureRequest Request that includes all client parameter
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public abstract void setParameters(@NonNull CaptureRequest captureRequest);

    /**
     * The camera framework will call this interface in response to client
     * requests involving  the output preview surface. Typical examples
     * include requests that include AF/AE triggers.
     * Extensions can disregard any capture request keys that were not
     * advertised in
     * {@link AdvancedExtender#getAvailableCaptureRequestKeys}.
     *
     * @param captureRequest Capture request that includes the respective
     *                       triggers.
     * @param executor the executor which will be used for
     *                 invoking the callbacks
     * @param callback a callback to report the status.
     * @return the id of the capture sequence.
     *
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public abstract int startTrigger(@NonNull CaptureRequest captureRequest,
            @NonNull Executor executor, @NonNull CaptureCallback callback);

    private final class SessionProcessorImpl extends ISessionProcessorImpl.Stub {
        private long mVendorId = -1;
        @Override
        public CameraSessionConfig initSession(IBinder token, String cameraId,
                Map<String, CameraMetadataNative> charsMap, OutputSurface previewSurface,
                OutputSurface imageCaptureSurface, OutputSurface postviewSurface)
                throws RemoteException {
            ExtensionConfiguration config = SessionProcessor.this.initSession(token, cameraId,
                    new CharacteristicsMap(charsMap),
                    new CameraOutputSurface(previewSurface),
                    new CameraOutputSurface(imageCaptureSurface));
            if (config == null) {
                throw  new  IllegalArgumentException("Invalid extension configuration");
            }

            Object thisClass = CameraCharacteristics.Key.class;
            Class<CameraCharacteristics.Key<?>> keyClass =
                    (Class<CameraCharacteristics.Key<?>>)thisClass;
            ArrayList<CameraCharacteristics.Key<?>> vendorKeys =
                    charsMap.get(cameraId).getAllVendorKeys(keyClass);
            if ((vendorKeys != null) && !vendorKeys.isEmpty()) {
                mVendorId = vendorKeys.get(0).getVendorId();
            }
            return config.getCameraSessionConfig();
        }

        @Override
        public void deInitSession(IBinder token) throws RemoteException {
            SessionProcessor.this.deInitSession(token);
        }

        @Override
        public void onCaptureSessionStart(IRequestProcessorImpl requestProcessor, String statsKey)
                throws RemoteException {
            if (mCameraUsageTracker != null) {
                mCameraUsageTracker.startCameraOperation();
            }
            SessionProcessor.this.onCaptureSessionStart(
                    new RequestProcessor(requestProcessor, mVendorId), statsKey);
        }

        @Override
        public void onCaptureSessionEnd() throws RemoteException {
            if (mCameraUsageTracker != null) {
                mCameraUsageTracker.finishCameraOperation();
            }
            SessionProcessor.this.onCaptureSessionEnd();
        }

        @Override
        public int startRepeating(ICaptureCallback callback) throws RemoteException {
            return SessionProcessor.this.startRepeating(
                    new HandlerExecutor(new Handler(Looper.getMainLooper())),
                    new CaptureCallbackImpl(callback));
        }

        @Override
        public void stopRepeating() throws RemoteException {
            SessionProcessor.this.stopRepeating();
        }

        @Override
        public int startCapture(ICaptureCallback callback, boolean isPostviewRequested)
                throws RemoteException {
            return SessionProcessor.this.startMultiFrameCapture(
                    new HandlerExecutor(new Handler(Looper.getMainLooper())),
                    new CaptureCallbackImpl(callback));
        }

        @Override
        public void setParameters(CaptureRequest captureRequest) throws RemoteException {
            SessionProcessor.this.setParameters(captureRequest);
        }

        @Override
        public int startTrigger(CaptureRequest captureRequest, ICaptureCallback callback)
                throws RemoteException {
            return SessionProcessor.this.startTrigger(captureRequest,
                    new HandlerExecutor(new Handler(Looper.getMainLooper())),
                    new CaptureCallbackImpl(callback));
        }

        @Override
        public LatencyPair getRealtimeCaptureLatency() throws RemoteException {
            // Feature is not supported
            return null;
        }
    }

    private static final class CaptureCallbackImpl implements CaptureCallback {
        private final ICaptureCallback mCaptureCallback;

        CaptureCallbackImpl(@NonNull ICaptureCallback cb) {
            mCaptureCallback = cb;
        }

        @Override
        public void onCaptureStarted(int captureSequenceId, long timestamp) {
            try {
                mCaptureCallback.onCaptureStarted(captureSequenceId, timestamp);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture start due to remote exception!");
            }
        }

        @Override
        public void onCaptureProcessStarted(int captureSequenceId) {
            try {
                mCaptureCallback.onCaptureProcessStarted(captureSequenceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify process start due to remote exception!");
            }
        }

        @Override
        public void onCaptureFailed(int captureSequenceId) {
            try {
                mCaptureCallback.onCaptureFailed(captureSequenceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture failure start due to remote exception!");
            }
        }

        @Override
        public void onCaptureSequenceCompleted(int captureSequenceId) {
            try {
                mCaptureCallback.onCaptureSequenceCompleted(captureSequenceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture sequence done due to remote exception!");
            }
        }

        @Override
        public void onCaptureSequenceAborted(int captureSequenceId) {
            try {
                mCaptureCallback.onCaptureSequenceAborted(captureSequenceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture sequence abort due to remote exception!");
            }
        }

        @Override
        public void onCaptureCompleted(long shutterTimestamp, int requestId,
                @androidx.annotation.NonNull CaptureResult results) {
            try {
                mCaptureCallback.onCaptureCompleted(shutterTimestamp, requestId,
                        results.getNativeCopy());
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture complete due to remote exception!");
            }
        }
    }

    @NonNull ISessionProcessorImpl getSessionProcessorBinder() {
        return new SessionProcessorImpl();
    }
}
