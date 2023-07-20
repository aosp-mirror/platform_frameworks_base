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
package android.hardware.camera2.impl;

import android.app.ActivityThread;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraOfflineSession;
import android.hardware.camera2.CameraOfflineSession.CameraOfflineSessionCallback;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.utils.TaskDrainer;
import android.hardware.camera2.utils.TaskSingleDrainer;
import android.os.Binder;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.view.Surface;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import static android.hardware.camera2.impl.CameraDeviceImpl.checkHandler;
import static com.android.internal.util.Preconditions.*;

public class CameraCaptureSessionImpl extends CameraCaptureSession
        implements CameraCaptureSessionCore {
    private static final String TAG = "CameraCaptureSession";
    private static final boolean DEBUG = false;

    /** Simple integer ID for session for debugging */
    private final int mId;
    private final String mIdString;

    /** Input surface configured by native camera framework based on user-specified configuration */
    private final Surface mInput;
    /**
     * User-specified state callback, used for outgoing events; calls to this object will be
     * automatically invoked via {@code mStateExecutor}.
     */
    private final CameraCaptureSession.StateCallback mStateCallback;
    /** User-specified state executor used for outgoing state callback events */
    private final Executor mStateExecutor;

    /** Internal camera device; used to translate calls into existing deprecated API */
    private final android.hardware.camera2.impl.CameraDeviceImpl mDeviceImpl;
    /** Internal executor; used for all incoming events to preserve total order */
    private final Executor mDeviceExecutor;

    /** Drain Sequence IDs which have been queued but not yet finished with aborted/completed */
    private final TaskDrainer<Integer> mSequenceDrainer;
    /** Drain state transitions from ACTIVE -> IDLE */
    private final TaskSingleDrainer mIdleDrainer;
    /** Drain state transitions from BUSY -> IDLE */
    private final TaskSingleDrainer mAbortDrainer;

    /** This session is closed; all further calls will throw ISE */
    private boolean mClosed = false;
    /** This session failed to be configured successfully */
    private final boolean mConfigureSuccess;
    /** Do not unconfigure if this is set; another session will overwrite configuration */
    private boolean mSkipUnconfigure = false;

    /** Is the session in the process of aborting? Pay attention to BUSY->IDLE transitions. */
    private volatile boolean mAborting;

    /**
     * Create a new CameraCaptureSession.
     *
     * <p>The camera device must already be in the {@code IDLE} state when this is invoked.
     * There must be no pending actions
     * (e.g. no pending captures, no repeating requests, no flush).</p>
     */
    CameraCaptureSessionImpl(int id, Surface input,
            CameraCaptureSession.StateCallback callback, Executor stateExecutor,
            android.hardware.camera2.impl.CameraDeviceImpl deviceImpl,
            Executor deviceStateExecutor, boolean configureSuccess) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        mId = id;
        mIdString = String.format("Session %d: ", mId);

        mInput = input;
        mStateExecutor = checkNotNull(stateExecutor, "stateExecutor must not be null");
        mStateCallback = createUserStateCallbackProxy(mStateExecutor, callback);

        mDeviceExecutor = checkNotNull(deviceStateExecutor,
                "deviceStateExecutor must not be null");
        mDeviceImpl = checkNotNull(deviceImpl, "deviceImpl must not be null");

        /*
         * Use the same handler as the device's StateCallback for all the internal coming events
         *
         * This ensures total ordering between CameraDevice.StateCallback and
         * CaptureCallback events.
         */
        mSequenceDrainer = new TaskDrainer<>(mDeviceExecutor, new SequenceDrainListener(),
                /*name*/"seq");
        mIdleDrainer = new TaskSingleDrainer(mDeviceExecutor, new IdleDrainListener(),
                /*name*/"idle");
        mAbortDrainer = new TaskSingleDrainer(mDeviceExecutor, new AbortDrainListener(),
                /*name*/"abort");

        // CameraDevice should call configureOutputs and have it finish before constructing us

        if (configureSuccess) {
            mStateCallback.onConfigured(this);
            if (DEBUG) Log.v(TAG, mIdString + "Created session successfully");
            mConfigureSuccess = true;
        } else {
            mStateCallback.onConfigureFailed(this);
            mClosed = true; // do not fire any other callbacks, do not allow any other work
            Log.e(TAG, mIdString + "Failed to create capture session; configuration failed");
            mConfigureSuccess = false;
        }

        setSkipUnconfigure();
    }

    private void setSkipUnconfigure() {
        String packageName = ActivityThread.currentOpPackageName();
        List<String> packageList = Arrays.asList(SystemProperties.get(
                "vendor.camera.skip_unconfigure.packagelist", packageName).split(","));

        if (packageList.contains(packageName)) {
            mSkipUnconfigure = true;
        }
    }

    @Override
    public CameraDevice getDevice() {
        return mDeviceImpl;
    }

    @Override
    public void prepare(Surface surface) throws CameraAccessException {
        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();
            mDeviceImpl.prepare(surface);
        }
    }

    @Override
    public void prepare(int maxCount, Surface surface) throws CameraAccessException {
        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();
            mDeviceImpl.prepare(maxCount, surface);
        }
    }

    @Override
    public void tearDown(Surface surface) throws CameraAccessException {
        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();
            mDeviceImpl.tearDown(surface);
        }
    }

    @Override
    public void finalizeOutputConfigurations(
            List<OutputConfiguration> outputConfigs) throws CameraAccessException {
        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();
            mDeviceImpl.finalizeOutputConfigs(outputConfigs);
        }
    }

    @Override
    public int capture(CaptureRequest request, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        checkCaptureRequest(request);

        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            handler = checkHandler(handler, callback);

            if (DEBUG) {
                Log.v(TAG, mIdString + "capture - request " + request + ", callback " + callback +
                        " handler " + handler);
            }

            return addPendingSequence(mDeviceImpl.capture(request,
                    createCaptureCallbackProxy(handler, callback), mDeviceExecutor));
        }
    }

    @Override
    public int captureSingleRequest(CaptureRequest request, Executor executor,
            CaptureCallback callback) throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        } else if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        checkCaptureRequest(request);

        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            executor = CameraDeviceImpl.checkExecutor(executor, callback);

            if (DEBUG) {
                Log.v(TAG, mIdString + "capture - request " + request + ", callback " + callback +
                        " executor " + executor);
            }

            return addPendingSequence(mDeviceImpl.capture(request,
                    createCaptureCallbackProxyWithExecutor(executor, callback), mDeviceExecutor));
        }
    }

    private void checkCaptureRequest(CaptureRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        } else if (request.isReprocess() && !isReprocessable()) {
            throw new IllegalArgumentException("this capture session cannot handle reprocess " +
                    "requests");
        } else if (!mDeviceImpl.isPrivilegedApp() &&
                request.isReprocess() && request.getReprocessableSessionId() != mId) {
            throw new IllegalArgumentException("capture request was created for another session");
        }
    }

    @Override
    public int captureBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        checkCaptureRequests(requests);

        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            handler = checkHandler(handler, callback);

            if (DEBUG) {
                CaptureRequest[] requestArray = requests.toArray(new CaptureRequest[0]);
                Log.v(TAG, mIdString + "captureBurst - requests " + Arrays.toString(requestArray) +
                        ", callback " + callback + " handler " + handler);
            }

            return addPendingSequence(mDeviceImpl.captureBurst(requests,
                    createCaptureCallbackProxy(handler, callback), mDeviceExecutor));
        }
    }

    @Override
    public int captureBurstRequests(List<CaptureRequest> requests, Executor executor,
            CaptureCallback callback) throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        } else if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        checkCaptureRequests(requests);

        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            executor = CameraDeviceImpl.checkExecutor(executor, callback);

            if (DEBUG) {
                CaptureRequest[] requestArray = requests.toArray(new CaptureRequest[0]);
                Log.v(TAG, mIdString + "captureBurst - requests " + Arrays.toString(requestArray) +
                        ", callback " + callback + " executor " + executor);
            }

            return addPendingSequence(mDeviceImpl.captureBurst(requests,
                    createCaptureCallbackProxyWithExecutor(executor, callback), mDeviceExecutor));
        }
    }

    private void checkCaptureRequests(List<CaptureRequest> requests) {
        if (requests == null) {
            throw new IllegalArgumentException("Requests must not be null");
        } else if (requests.isEmpty()) {
            throw new IllegalArgumentException("Requests must have at least one element");
        }

        for (CaptureRequest request : requests) {
            if (request.isReprocess()) {
                if (!isReprocessable()) {
                    throw new IllegalArgumentException("This capture session cannot handle " +
                            "reprocess requests");
                } else if (request.getReprocessableSessionId() != mId) {
                    throw new IllegalArgumentException("Capture request was created for another " +
                            "session");
                }
            }
        }

    }

    @Override
    public int setRepeatingRequest(CaptureRequest request, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        checkRepeatingRequest(request);

        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            handler = checkHandler(handler, callback);

            if (DEBUG) {
                Log.v(TAG, mIdString + "setRepeatingRequest - request " + request + ", callback " +
                        callback + " handler" + " " + handler);
            }

            return addPendingSequence(mDeviceImpl.setRepeatingRequest(request,
                    createCaptureCallbackProxy(handler, callback), mDeviceExecutor));
        }
    }

    @Override
    public int setSingleRepeatingRequest(CaptureRequest request, Executor executor,
            CaptureCallback callback) throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        } else if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        checkRepeatingRequest(request);

        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            executor = CameraDeviceImpl.checkExecutor(executor, callback);

            if (DEBUG) {
                Log.v(TAG, mIdString + "setRepeatingRequest - request " + request + ", callback " +
                        callback + " executor" + " " + executor);
            }

            return addPendingSequence(mDeviceImpl.setRepeatingRequest(request,
                    createCaptureCallbackProxyWithExecutor(executor, callback), mDeviceExecutor));
        }
    }

    private void checkRepeatingRequest(CaptureRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        } else if (request.isReprocess()) {
            throw new IllegalArgumentException("repeating reprocess requests are not supported");
        }
    }

    @Override
    public int setRepeatingBurst(List<CaptureRequest> requests,
            CaptureCallback callback, Handler handler) throws CameraAccessException {
        checkRepeatingRequests(requests);

        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            handler = checkHandler(handler, callback);

            if (DEBUG) {
                CaptureRequest[] requestArray = requests.toArray(new CaptureRequest[0]);
                Log.v(TAG, mIdString + "setRepeatingBurst - requests " +
                        Arrays.toString(requestArray) + ", callback " + callback +
                        " handler" + "" + handler);
            }

            return addPendingSequence(mDeviceImpl.setRepeatingBurst(requests,
                    createCaptureCallbackProxy(handler, callback), mDeviceExecutor));
        }
    }

    @Override
    public int setRepeatingBurstRequests(List<CaptureRequest> requests, Executor executor,
            CaptureCallback callback) throws CameraAccessException {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        } else if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        checkRepeatingRequests(requests);

        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            executor = CameraDeviceImpl.checkExecutor(executor, callback);

            if (DEBUG) {
                CaptureRequest[] requestArray = requests.toArray(new CaptureRequest[0]);
                Log.v(TAG, mIdString + "setRepeatingBurst - requests " +
                        Arrays.toString(requestArray) + ", callback " + callback +
                        " executor" + "" + executor);
            }

            return addPendingSequence(mDeviceImpl.setRepeatingBurst(requests,
                    createCaptureCallbackProxyWithExecutor(executor, callback), mDeviceExecutor));
        }
    }

    private void checkRepeatingRequests(List<CaptureRequest> requests) {
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        } else if (requests.isEmpty()) {
            throw new IllegalArgumentException("requests must have at least one element");
        }

        for (CaptureRequest r : requests) {
            if (r.isReprocess()) {
                throw new IllegalArgumentException("repeating reprocess burst requests are not " +
                        "supported");
            }
        }
    }

    @Override
    public void stopRepeating() throws CameraAccessException {
        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            if (DEBUG) {
                Log.v(TAG, mIdString + "stopRepeating");
            }

            mDeviceImpl.stopRepeating();
        }
    }

    @Override
    public void abortCaptures() throws CameraAccessException {
        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            if (DEBUG) {
                Log.v(TAG, mIdString + "abortCaptures");
            }

            if (mAborting) {
                Log.w(TAG, mIdString + "abortCaptures - Session is already aborting; doing nothing");
                return;
            }

            mAborting = true;
            mAbortDrainer.taskStarted();

            mDeviceImpl.flush();
            // The next BUSY -> IDLE set of transitions will mark the end of the abort.
        }
    }

    @Override
    public void updateOutputConfiguration(OutputConfiguration config)
            throws CameraAccessException {
        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();

            if (DEBUG) {
                Log.v(TAG, mIdString + "updateOutputConfiguration");
            }

            mDeviceImpl.updateOutputConfiguration(config);
        }
    }

    @Override
    public CameraOfflineSession switchToOffline(Collection<Surface> offlineOutputs,
            Executor executor, CameraOfflineSessionCallback listener) throws CameraAccessException {
        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();
        }
        return mDeviceImpl.switchToOffline(offlineOutputs, executor, listener);
    }


    @Override
    public boolean supportsOfflineProcessing(Surface surface) {
        synchronized (mDeviceImpl.mInterfaceLock) {
            checkNotClosed();
        }
        return mDeviceImpl.supportsOfflineProcessing(surface);
    }

    @Override
    public boolean isReprocessable() {
        return mInput != null;
    }

    @Override
    public Surface getInputSurface() {
        return mInput;
    }

    /**
     * Replace this session with another session.
     *
     * <p>This is an optimization to avoid unconfiguring and then immediately having to
     * reconfigure again.</p>
     *
     * <p>The semantics are identical to {@link #close}, except that unconfiguring will be skipped.
     * <p>
     *
     * <p>After this call completes, the session will not call any further methods on the camera
     * device.</p>
     *
     * @see CameraCaptureSession#close
     */
    @Override
    public void replaceSessionClose() {
        synchronized (mDeviceImpl.mInterfaceLock) {
            /*
             * In order for creating new sessions to be fast, the new session should be created
             * before the old session is closed.
             *
             * Otherwise the old session will always unconfigure if there is no new session to
             * replace it.
             *
             * Unconfiguring could add hundreds of milliseconds of delay. We could race and attempt
             * to skip unconfigure if a new session is created before the captures are all drained,
             * but this would introduce nondeterministic behavior.
             */

            if (DEBUG) Log.v(TAG, mIdString + "replaceSessionClose");

            // Set up fast shutdown. Possible alternative paths:
            // - This session is active, so close() below starts the shutdown drain
            // - This session is mid-shutdown drain, and hasn't yet reached the idle drain listener.
            // - This session is already closed and has executed the idle drain listener, and
            //   configureOutputsChecked(null) has already been called.
            //
            // Do not call configureOutputsChecked(null) going forward, since it would race with the
            // configuration for the new session. If it was already called, then we don't care,
            // since it won't get called again.
            mSkipUnconfigure = true;
            close();
        }
    }

    @Override
    public void closeWithoutDraining() {
        synchronized (mDeviceImpl.mInterfaceLock) {
            if (mClosed) {
                if (DEBUG) Log.v(TAG, mIdString + "close - reentering");
                return;
            }

            if (DEBUG) Log.v(TAG, mIdString + "close - first time");

            mClosed = true;
            mStateCallback.onClosed(this);
        }

        if (mInput != null) {
            mInput.release();
        }
    }

    @Override
    public void close() {
        synchronized (mDeviceImpl.mInterfaceLock) {
            if (mClosed) {
                if (DEBUG) Log.v(TAG, mIdString + "close - reentering");
                return;
            }

            if (DEBUG) Log.v(TAG, mIdString + "close - first time");

            mClosed = true;

            /*
             * Flush out any repeating request. Since camera is closed, no new requests
             * can be queued, and eventually the entire request queue will be drained.
             *
             * If the camera device was already closed, short circuit and do nothing; since
             * no more internal device callbacks will fire anyway.
             *
             * Otherwise, once stopRepeating is done, wait for camera to idle, then unconfigure
             * the camera. Once that's done, fire #onClosed.
             */
            try {
                mDeviceImpl.stopRepeating();
            } catch (IllegalStateException e) {
                // OK: Camera device may already be closed, nothing else to do

                // TODO: Fire onClosed anytime we get the device onClosed or the ISE?
                // or just suppress the ISE only and rely onClosed.
                // Also skip any of the draining work if this is already closed.

                // Short-circuit; queue callback immediately and return
                mStateCallback.onClosed(this);
                return;
            } catch (CameraAccessException e) {
                // OK: close does not throw checked exceptions.
                Log.e(TAG, mIdString + "Exception while stopping repeating: ", e);

                // TODO: call onError instead of onClosed if this happens
            }

            // If no sequences are pending, fire #onClosed immediately
            mSequenceDrainer.beginDrain();
        }
        if (mInput != null) {
            mInput.release();
        }
    }

    /**
     * Whether currently in mid-abort.
     *
     * <p>This is used by the implementation to set the capture failure
     * reason, in lieu of more accurate error codes from the camera service.
     * Unsynchronized to avoid deadlocks between simultaneous session->device,
     * device->session calls.</p>
     *
     */
    @Override
    public boolean isAborting() {
        return mAborting;
    }

    /**
     * Post calls into a CameraCaptureSession.StateCallback to the user-specified {@code executor}.
     */
    private StateCallback createUserStateCallbackProxy(Executor executor, StateCallback callback) {
        return new CallbackProxies.SessionStateCallbackProxy(executor, callback);
    }

    /**
     * Forward callbacks that usually originate from
     * CameraDeviceImpl.CameraDeviceCallbacks to the CameraCaptureSession.CaptureCallback.
     *
     * <p>When a capture sequence finishes, update the pending checked sequences set.</p>
     */
    @SuppressWarnings("deprecation")
    private android.hardware.camera2.impl.CaptureCallback createCaptureCallbackProxy(
            Handler handler, CaptureCallback callback) {
        final Executor executor = (callback != null) ? CameraDeviceImpl.checkAndWrapHandler(
                handler) : null;

        return createCaptureCallbackProxyWithExecutor(executor, callback);
    }

    private android.hardware.camera2.impl.CaptureCallback createCaptureCallbackProxyWithExecutor(
            Executor executor, CaptureCallback callback) {
        return new android.hardware.camera2.impl.CaptureCallback(executor, callback) {
            @Override
            public void onCaptureStarted(CameraDevice camera,
                    CaptureRequest request, long timestamp, long frameNumber) {
                if ((callback != null) && (executor != null)) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onCaptureStarted(
                                    CameraCaptureSessionImpl.this, request, timestamp,
                                    frameNumber));
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            }

            @Override
            public void onCapturePartial(CameraDevice camera,
                    CaptureRequest request, android.hardware.camera2.CaptureResult result) {
                if ((callback != null) && (executor != null)) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onCapturePartial(
                                    CameraCaptureSessionImpl.this, request, result));
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            }

            @Override
            public void onCaptureProgressed(CameraDevice camera,
                    CaptureRequest request, android.hardware.camera2.CaptureResult partialResult) {
                if ((callback != null) && (executor != null)) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onCaptureProgressed(
                                    CameraCaptureSessionImpl.this, request, partialResult));
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            }

            @Override
            public void onCaptureCompleted(CameraDevice camera,
                    CaptureRequest request, android.hardware.camera2.TotalCaptureResult result) {
                if ((callback != null) && (executor != null)) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onCaptureCompleted(
                                    CameraCaptureSessionImpl.this, request, result));
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            }

            @Override
            public void onCaptureFailed(CameraDevice camera,
                    CaptureRequest request, android.hardware.camera2.CaptureFailure failure) {
                if ((callback != null) && (executor != null)) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onCaptureFailed(
                                    CameraCaptureSessionImpl.this, request, failure));
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            }

            @Override
            public void onCaptureSequenceCompleted(CameraDevice camera,
                    int sequenceId, long frameNumber) {
                if ((callback != null) && (executor != null)) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onCaptureSequenceCompleted(
                                    CameraCaptureSessionImpl.this, sequenceId, frameNumber));
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
                finishPendingSequence(sequenceId);
            }

            @Override
            public void onCaptureSequenceAborted(CameraDevice camera,
                    int sequenceId) {
                if ((callback != null) && (executor != null)) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onCaptureSequenceAborted(
                                    CameraCaptureSessionImpl.this, sequenceId));
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
                finishPendingSequence(sequenceId);
            }

            @Override
            public void onCaptureBufferLost(CameraDevice camera,
                    CaptureRequest request, Surface target, long frameNumber) {
                if ((callback != null) && (executor != null)) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onCaptureBufferLost(
                                    CameraCaptureSessionImpl.this, request, target, frameNumber));
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            }
        };
    }

    /**
     *
     * Create an internal state callback, to be invoked on the mDeviceExecutor
     *
     * <p>It has a few behaviors:
     * <ul>
     * <li>Convert device state changes into session state changes.
     * <li>Keep track of async tasks that the session began (idle, abort).
     * </ul>
     * </p>
     * */
    @Override
    public CameraDeviceImpl.StateCallbackKK getDeviceStateCallback() {
        final CameraCaptureSession session = this;
        final Object interfaceLock = mDeviceImpl.mInterfaceLock;


        return new CameraDeviceImpl.StateCallbackKK() {
            private boolean mBusy = false;
            private boolean mActive = false;

            @Override
            public void onOpened(CameraDevice camera) {
                throw new AssertionError("Camera must already be open before creating a session");
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                if (DEBUG) Log.v(TAG, mIdString + "onDisconnected");
                close();
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                // Should not be reached, handled by device code
                Log.wtf(TAG, mIdString + "Got device error " + error);
            }

            @Override
            public void onActive(CameraDevice camera) {
                mIdleDrainer.taskStarted();
                mActive = true;

                if (DEBUG) Log.v(TAG, mIdString + "onActive");
                mStateCallback.onActive(session);
            }

            @Override
            public void onIdle(CameraDevice camera) {
                boolean isAborting;
                if (DEBUG) Log.v(TAG, mIdString + "onIdle");

                synchronized (interfaceLock) {
                    isAborting = mAborting;
                }

                /*
                 * Check which states we transitioned through:
                 *
                 * (ACTIVE -> IDLE)
                 * (BUSY -> IDLE)
                 *
                 * Note that this is also legal:
                 * (ACTIVE -> BUSY -> IDLE)
                 *
                 * and mark those tasks as finished
                 */
                if (mBusy && isAborting) {
                    mAbortDrainer.taskFinished();

                    synchronized (interfaceLock) {
                        mAborting = false;
                    }
                }

                if (mActive) {
                    mIdleDrainer.taskFinished();
                }

                mBusy = false;
                mActive = false;

                mStateCallback.onReady(session);
            }

            @Override
            public void onBusy(CameraDevice camera) {
                mBusy = true;

                // TODO: Queue captures during abort instead of failing them
                // since the app won't be able to distinguish the two actives
                // Don't signal the application since there's no clean mapping here
                if (DEBUG) Log.v(TAG, mIdString + "onBusy");
            }

            @Override
            public void onUnconfigured(CameraDevice camera) {
                if (DEBUG) Log.v(TAG, mIdString + "onUnconfigured");
            }

            @Override
            public void onRequestQueueEmpty() {
                if (DEBUG) Log.v(TAG, mIdString + "onRequestQueueEmpty");
                mStateCallback.onCaptureQueueEmpty(session);
            }

            @Override
            public void onSurfacePrepared(Surface surface) {
                if (DEBUG) Log.v(TAG, mIdString + "onSurfacePrepared");
                mStateCallback.onSurfacePrepared(session, surface);
            }
        };

    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private void checkNotClosed() {
        if (mClosed) {
            throw new IllegalStateException(
                    "Session has been closed; further changes are illegal.");
        }
    }

    /**
     * Notify the session that a pending capture sequence has just been queued.
     *
     * <p>During a shutdown/close, the session waits until all pending sessions are finished
     * before taking any further steps to shut down itself.</p>
     *
     * @see #finishPendingSequence
     */
    private int addPendingSequence(int sequenceId) {
        mSequenceDrainer.taskStarted(sequenceId);
        return sequenceId;
    }

    /**
     * Notify the session that a pending capture sequence is now finished.
     *
     * <p>During a shutdown/close, once all pending sequences finish, it is safe to
     * close the camera further by unconfiguring and then firing {@code onClosed}.</p>
     */
    private void finishPendingSequence(int sequenceId) {
        try {
            mSequenceDrainer.taskFinished(sequenceId);
        } catch (IllegalStateException e) {
            // Workaround for b/27870771
            Log.w(TAG, e.getMessage());
        }
    }

    private class SequenceDrainListener implements TaskDrainer.DrainListener {
        @Override
        public void onDrained() {
            /*
             * No repeating request is set; and the capture queue has fully drained.
             *
             * If no captures were queued to begin with, and an abort was queued,
             * it's still possible to get another BUSY before the last IDLE.
             *
             * If the camera is already "IDLE" and no aborts are pending,
             * then the drain immediately finishes.
             */
            if (DEBUG) Log.v(TAG, mIdString + "onSequenceDrained");


            // Fire session close as soon as all sequences are complete.
            // We may still need to unconfigure the device, but a new session might be created
            // past this point, and notifications would then stop to this instance.
            mStateCallback.onClosed(CameraCaptureSessionImpl.this);

            // Fast path: A new capture session has replaced this one; don't wait for abort/idle
            // as we won't get state updates any more anyway.
            if (mSkipUnconfigure) {
                return;
            }

            mAbortDrainer.beginDrain();
        }
    }

    private class AbortDrainListener implements TaskDrainer.DrainListener {
        @Override
        public void onDrained() {
            if (DEBUG) Log.v(TAG, mIdString + "onAbortDrained");
            synchronized (mDeviceImpl.mInterfaceLock) {
                /*
                 * Any queued aborts have now completed.
                 *
                 * It's now safe to wait to receive the final "IDLE" event, as the camera device
                 * will no longer again transition to "ACTIVE" by itself.
                 *
                 * If the camera is already "IDLE", then the drain immediately finishes.
                 */

                // Fast path: A new capture session has replaced this one; don't wait for idle
                // as we won't get state updates any more anyway.
                if (mSkipUnconfigure) {
                    return;
                }
                mIdleDrainer.beginDrain();
            }
        }
    }

    private class IdleDrainListener implements TaskDrainer.DrainListener {
        @Override
        public void onDrained() {
            if (DEBUG) Log.v(TAG, mIdString + "onIdleDrained");

            // Take device lock before session lock so that we can call back into device
            // without causing a deadlock
            synchronized (mDeviceImpl.mInterfaceLock) {
                /*
                 * The device is now IDLE, and has settled. It will not transition to
                 * ACTIVE or BUSY again by itself.
                 *
                 * It's now safe to unconfigure the outputs.
                 *
                 * This operation is idempotent; a session will not be closed twice.
                 */
                if (DEBUG)
                    Log.v(TAG, mIdString + "Session drain complete, skip unconfigure: " +
                            mSkipUnconfigure);

                // Fast path: A new capture session has replaced this one; don't wait for idle
                // as we won't get state updates any more anyway.
                if (mSkipUnconfigure) {
                    return;
                }

                // Final slow path: unconfigure the camera, no session has replaced us and
                // everything is idle.
                try {
                    // begin transition to unconfigured
                    mDeviceImpl.configureStreamsChecked(/*inputConfig*/null, /*outputs*/null,
                            /*operatingMode*/ ICameraDeviceUser.NORMAL_MODE,
                            /*sessionParams*/ null, SystemClock.uptimeMillis());
                } catch (CameraAccessException e) {
                    // OK: do not throw checked exceptions.
                    Log.e(TAG, mIdString + "Exception while unconfiguring outputs: ", e);

                    // TODO: call onError instead of onClosed if this happens
                } catch (IllegalStateException e) {
                    // Camera is already closed, so nothing left to do
                    if (DEBUG) Log.v(TAG, mIdString +
                            "Camera was already closed or busy, skipping unconfigure");
                }
            }
        }
    }

}
