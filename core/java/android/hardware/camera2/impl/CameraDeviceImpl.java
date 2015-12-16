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

package android.hardware.camera2.impl;

import static android.hardware.camera2.CameraAccessException.CAMERA_IN_USE;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.ReprocessFormatsMap;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.hardware.camera2.utils.LongParcelable;
import android.hardware.camera2.utils.SurfaceUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseArray;
import android.view.Surface;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.TreeMap;

/**
 * HAL2.1+ implementation of CameraDevice. Use CameraManager#open to instantiate
 */
public class CameraDeviceImpl extends CameraDevice {
    private final String TAG;
    private final boolean DEBUG = false;

    private static final int REQUEST_ID_NONE = -1;

    // TODO: guard every function with if (!mRemoteDevice) check (if it was closed)
    private ICameraDeviceUser mRemoteDevice;

    // Lock to synchronize cross-thread access to device public interface
    final Object mInterfaceLock = new Object(); // access from this class and Session only!
    private final CameraDeviceCallbacks mCallbacks = new CameraDeviceCallbacks();

    private final StateCallback mDeviceCallback;
    private volatile StateCallbackKK mSessionStateCallback;
    private final Handler mDeviceHandler;

    private final AtomicBoolean mClosing = new AtomicBoolean();
    private boolean mInError = false;
    private boolean mIdle = true;

    /** map request IDs to callback/request data */
    private final SparseArray<CaptureCallbackHolder> mCaptureCallbackMap =
            new SparseArray<CaptureCallbackHolder>();

    private int mRepeatingRequestId = REQUEST_ID_NONE;
    private final ArrayList<Integer> mRepeatingRequestIdDeletedList = new ArrayList<Integer>();
    // Map stream IDs to input/output configurations
    private SimpleEntry<Integer, InputConfiguration> mConfiguredInput =
            new SimpleEntry<>(REQUEST_ID_NONE, null);
    private final SparseArray<OutputConfiguration> mConfiguredOutputs =
            new SparseArray<>();

    private final String mCameraId;
    private final CameraCharacteristics mCharacteristics;
    private final int mTotalPartialCount;

    /**
     * A list tracking request and its expected last regular frame number and last reprocess frame
     * number. Updated when calling ICameraDeviceUser methods.
     */
    private final List<RequestLastFrameNumbersHolder> mRequestLastFrameNumbersList =
            new ArrayList<>();

    /**
     * An object tracking received frame numbers.
     * Updated when receiving callbacks from ICameraDeviceCallbacks.
     */
    private final FrameNumberTracker mFrameNumberTracker = new FrameNumberTracker();

    private CameraCaptureSessionCore mCurrentSession;
    private int mNextSessionId = 0;

    // Runnables for all state transitions, except error, which needs the
    // error code argument

    private final Runnable mCallOnOpened = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onOpened(CameraDeviceImpl.this);
            }
            mDeviceCallback.onOpened(CameraDeviceImpl.this);
        }
    };

    private final Runnable mCallOnUnconfigured = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onUnconfigured(CameraDeviceImpl.this);
            }
        }
    };

    private final Runnable mCallOnActive = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onActive(CameraDeviceImpl.this);
            }
        }
    };

    private final Runnable mCallOnBusy = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onBusy(CameraDeviceImpl.this);
            }
        }
    };

    private final Runnable mCallOnClosed = new Runnable() {
        private boolean mClosedOnce = false;

        @Override
        public void run() {
            if (mClosedOnce) {
                throw new AssertionError("Don't post #onClosed more than once");
            }
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onClosed(CameraDeviceImpl.this);
            }
            mDeviceCallback.onClosed(CameraDeviceImpl.this);
            mClosedOnce = true;
        }
    };

    private final Runnable mCallOnIdle = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onIdle(CameraDeviceImpl.this);
            }
        }
    };

    private final Runnable mCallOnDisconnected = new Runnable() {
        @Override
        public void run() {
            StateCallbackKK sessionCallback = null;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                sessionCallback = mSessionStateCallback;
            }
            if (sessionCallback != null) {
                sessionCallback.onDisconnected(CameraDeviceImpl.this);
            }
            mDeviceCallback.onDisconnected(CameraDeviceImpl.this);
        }
    };

    public CameraDeviceImpl(String cameraId, StateCallback callback, Handler handler,
                        CameraCharacteristics characteristics) {
        if (cameraId == null || callback == null || handler == null || characteristics == null) {
            throw new IllegalArgumentException("Null argument given");
        }
        mCameraId = cameraId;
        mDeviceCallback = callback;
        mDeviceHandler = handler;
        mCharacteristics = characteristics;

        final int MAX_TAG_LEN = 23;
        String tag = String.format("CameraDevice-JV-%s", mCameraId);
        if (tag.length() > MAX_TAG_LEN) {
            tag = tag.substring(0, MAX_TAG_LEN);
        }
        TAG = tag;

        Integer partialCount =
                mCharacteristics.get(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT);
        if (partialCount == null) {
            // 1 means partial result is not supported.
            mTotalPartialCount = 1;
        } else {
            mTotalPartialCount = partialCount;
        }
    }

    public CameraDeviceCallbacks getCallbacks() {
        return mCallbacks;
    }

    public void setRemoteDevice(ICameraDeviceUser remoteDevice) {
        synchronized(mInterfaceLock) {
            // TODO: Move from decorator to direct binder-mediated exceptions
            // If setRemoteFailure already called, do nothing
            if (mInError) return;

            mRemoteDevice = CameraBinderDecorator.newInstance(remoteDevice);

            mDeviceHandler.post(mCallOnOpened);
            mDeviceHandler.post(mCallOnUnconfigured);
        }
    }

    /**
     * Call to indicate failed connection to a remote camera device.
     *
     * <p>This places the camera device in the error state and informs the callback.
     * Use in place of setRemoteDevice() when startup fails.</p>
     */
    public void setRemoteFailure(final CameraRuntimeException failure) {
        int failureCode = StateCallback.ERROR_CAMERA_DEVICE;
        boolean failureIsError = true;

        switch (failure.getReason()) {
            case CameraAccessException.CAMERA_IN_USE:
                failureCode = StateCallback.ERROR_CAMERA_IN_USE;
                break;
            case CameraAccessException.MAX_CAMERAS_IN_USE:
                failureCode = StateCallback.ERROR_MAX_CAMERAS_IN_USE;
                break;
            case CameraAccessException.CAMERA_DISABLED:
                failureCode = StateCallback.ERROR_CAMERA_DISABLED;
                break;
            case CameraAccessException.CAMERA_DISCONNECTED:
                failureIsError = false;
                break;
            case CameraAccessException.CAMERA_ERROR:
                failureCode = StateCallback.ERROR_CAMERA_DEVICE;
                break;
            default:
                Log.wtf(TAG, "Unknown failure in opening camera device: " + failure.getReason());
                break;
        }
        final int code = failureCode;
        final boolean isError = failureIsError;
        synchronized(mInterfaceLock) {
            mInError = true;
            mDeviceHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isError) {
                        mDeviceCallback.onError(CameraDeviceImpl.this, code);
                    } else {
                        mDeviceCallback.onDisconnected(CameraDeviceImpl.this);
                    }
                }
            });
        }
    }

    @Override
    public String getId() {
        return mCameraId;
    }

    public void configureOutputs(List<Surface> outputs) throws CameraAccessException {
        // Leave this here for backwards compatibility with older code using this directly
        ArrayList<OutputConfiguration> outputConfigs = new ArrayList<>(outputs.size());
        for (Surface s : outputs) {
            outputConfigs.add(new OutputConfiguration(s));
        }
        configureStreamsChecked(/*inputConfig*/null, outputConfigs,
                /*isConstrainedHighSpeed*/false);

    }

    /**
     * Attempt to configure the input and outputs; the device goes to idle and then configures the
     * new input and outputs if possible.
     *
     * <p>The configuration may gracefully fail, if input configuration is not supported,
     * if there are too many outputs, if the formats are not supported, or if the sizes for that
     * format is not supported. In this case this function will return {@code false} and the
     * unconfigured callback will be fired.</p>
     *
     * <p>If the configuration succeeds (with 1 or more outputs with or without an input),
     * then the idle callback is fired. Unconfiguring the device always fires the idle callback.</p>
     *
     * @param inputConfig input configuration or {@code null} for no input
     * @param outputs a list of one or more surfaces, or {@code null} to unconfigure
     * @param isConstrainedHighSpeed If the streams configuration is for constrained high speed output.
     * @return whether or not the configuration was successful
     *
     * @throws CameraAccessException if there were any unexpected problems during configuration
     */
    public boolean configureStreamsChecked(InputConfiguration inputConfig,
            List<OutputConfiguration> outputs, boolean isConstrainedHighSpeed)
                    throws CameraAccessException {
        // Treat a null input the same an empty list
        if (outputs == null) {
            outputs = new ArrayList<OutputConfiguration>();
        }
        if (outputs.size() == 0 && inputConfig != null) {
            throw new IllegalArgumentException("cannot configure an input stream without " +
                    "any output streams");
        }

        checkInputConfiguration(inputConfig);

        boolean success = false;

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();
            // Streams to create
            HashSet<OutputConfiguration> addSet = new HashSet<OutputConfiguration>(outputs);
            // Streams to delete
            List<Integer> deleteList = new ArrayList<Integer>();

            // Determine which streams need to be created, which to be deleted
            for (int i = 0; i < mConfiguredOutputs.size(); ++i) {
                int streamId = mConfiguredOutputs.keyAt(i);
                OutputConfiguration outConfig = mConfiguredOutputs.valueAt(i);

                if (!outputs.contains(outConfig)) {
                    deleteList.add(streamId);
                } else {
                    addSet.remove(outConfig);  // Don't create a stream previously created
                }
            }

            mDeviceHandler.post(mCallOnBusy);
            stopRepeating();

            try {
                waitUntilIdle();

                mRemoteDevice.beginConfigure();

                // reconfigure the input stream if the input configuration is different.
                InputConfiguration currentInputConfig = mConfiguredInput.getValue();
                if (inputConfig != currentInputConfig &&
                        (inputConfig == null || !inputConfig.equals(currentInputConfig))) {
                    if (currentInputConfig != null) {
                        mRemoteDevice.deleteStream(mConfiguredInput.getKey());
                        mConfiguredInput = new SimpleEntry<Integer, InputConfiguration>(
                                REQUEST_ID_NONE, null);
                    }
                    if (inputConfig != null) {
                        int streamId = mRemoteDevice.createInputStream(inputConfig.getWidth(),
                                inputConfig.getHeight(), inputConfig.getFormat());
                        mConfiguredInput = new SimpleEntry<Integer, InputConfiguration>(
                                streamId, inputConfig);
                    }
                }

                // Delete all streams first (to free up HW resources)
                for (Integer streamId : deleteList) {
                    mRemoteDevice.deleteStream(streamId);
                    mConfiguredOutputs.delete(streamId);
                }

                // Add all new streams
                for (OutputConfiguration outConfig : outputs) {
                    if (addSet.contains(outConfig)) {
                        int streamId = mRemoteDevice.createStream(outConfig);
                        mConfiguredOutputs.put(streamId, outConfig);
                    }
                }

                try {
                    mRemoteDevice.endConfigure(isConstrainedHighSpeed);
                }
                catch (IllegalArgumentException e) {
                    // OK. camera service can reject stream config if it's not supported by HAL
                    // This is only the result of a programmer misusing the camera2 api.
                    Log.w(TAG, "Stream configuration failed");
                    return false;
                }

                success = true;
            } catch (CameraRuntimeException e) {
                if (e.getReason() == CAMERA_IN_USE) {
                    throw new IllegalStateException("The camera is currently busy." +
                            " You must wait until the previous operation completes.");
                }

                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return false;
            } finally {
                if (success && outputs.size() > 0) {
                    mDeviceHandler.post(mCallOnIdle);
                } else {
                    // Always return to the 'unconfigured' state if we didn't hit a fatal error
                    mDeviceHandler.post(mCallOnUnconfigured);
                }
            }
        }

        return success;
    }

    @Override
    public void createCaptureSession(List<Surface> outputs,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        List<OutputConfiguration> outConfigurations = new ArrayList<>(outputs.size());
        for (Surface surface : outputs) {
            outConfigurations.add(new OutputConfiguration(surface));
        }
        createCaptureSessionInternal(null, outConfigurations, callback, handler,
                /*isConstrainedHighSpeed*/false);
    }

    @Override
    public void createCaptureSessionByOutputConfiguration(
            List<OutputConfiguration> outputConfigurations,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        if (DEBUG) {
            Log.d(TAG, "createCaptureSessionByOutputConfiguration");
        }

        createCaptureSessionInternal(null, outputConfigurations, callback, handler,
                /*isConstrainedHighSpeed*/false);
    }

    @Override
    public void createReprocessableCaptureSession(InputConfiguration inputConfig,
            List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        if (DEBUG) {
            Log.d(TAG, "createReprocessableCaptureSession");
        }

        if (inputConfig == null) {
            throw new IllegalArgumentException("inputConfig cannot be null when creating a " +
                    "reprocessable capture session");
        }
        List<OutputConfiguration> outConfigurations = new ArrayList<>(outputs.size());
        for (Surface surface : outputs) {
            outConfigurations.add(new OutputConfiguration(surface));
        }
        createCaptureSessionInternal(inputConfig, outConfigurations, callback, handler,
                /*isConstrainedHighSpeed*/false);
    }

    @Override
    public void createConstrainedHighSpeedCaptureSession(List<Surface> outputs,
            android.hardware.camera2.CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        if (outputs == null || outputs.size() == 0 || outputs.size() > 2) {
            throw new IllegalArgumentException(
                    "Output surface list must not be null and the size must be no more than 2");
        }
        StreamConfigurationMap config =
                getCharacteristics().get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        SurfaceUtils.checkConstrainedHighSpeedSurfaces(outputs, /*fpsRange*/null, config);

        List<OutputConfiguration> outConfigurations = new ArrayList<>(outputs.size());
        for (Surface surface : outputs) {
            outConfigurations.add(new OutputConfiguration(surface));
        }
        createCaptureSessionInternal(null, outConfigurations, callback, handler,
                /*isConstrainedHighSpeed*/true);
    }

    private void createCaptureSessionInternal(InputConfiguration inputConfig,
            List<OutputConfiguration> outputConfigurations,
            CameraCaptureSession.StateCallback callback, Handler handler,
            boolean isConstrainedHighSpeed) throws CameraAccessException {
        synchronized(mInterfaceLock) {
            if (DEBUG) {
                Log.d(TAG, "createCaptureSessionInternal");
            }

            checkIfCameraClosedOrInError();

            if (isConstrainedHighSpeed && inputConfig != null) {
                throw new IllegalArgumentException("Constrained high speed session doesn't support"
                        + " input configuration yet.");
            }

            // Notify current session that it's going away, before starting camera operations
            // After this call completes, the session is not allowed to call into CameraDeviceImpl
            if (mCurrentSession != null) {
                mCurrentSession.replaceSessionClose();
            }

            // TODO: dont block for this
            boolean configureSuccess = true;
            CameraAccessException pendingException = null;
            Surface input = null;
            try {
                // configure streams and then block until IDLE
                configureSuccess = configureStreamsChecked(inputConfig, outputConfigurations,
                        isConstrainedHighSpeed);
                if (configureSuccess == true && inputConfig != null) {
                    input = new Surface();
                    try {
                        mRemoteDevice.getInputSurface(/*out*/input);
                    } catch (CameraRuntimeException e) {
                        e.asChecked();
                    }
                }
            } catch (CameraAccessException e) {
                configureSuccess = false;
                pendingException = e;
                input = null;
                if (DEBUG) {
                    Log.v(TAG, "createCaptureSession - failed with exception ", e);
                }
            } catch (RemoteException e) {
                // impossible
                return;
            }

            List<Surface> outSurfaces = new ArrayList<>(outputConfigurations.size());
            for (OutputConfiguration config : outputConfigurations) {
                outSurfaces.add(config.getSurface());
            }
            // Fire onConfigured if configureOutputs succeeded, fire onConfigureFailed otherwise.
            CameraCaptureSessionCore newSession = null;
            if (isConstrainedHighSpeed) {
                newSession = new CameraConstrainedHighSpeedCaptureSessionImpl(mNextSessionId++,
                        outSurfaces, callback, handler, this, mDeviceHandler, configureSuccess,
                        mCharacteristics);
            } else {
                newSession = new CameraCaptureSessionImpl(mNextSessionId++, input,
                        outSurfaces, callback, handler, this, mDeviceHandler,
                        configureSuccess);
            }

            // TODO: wait until current session closes, then create the new session
            mCurrentSession = newSession;

            if (pendingException != null) {
                throw pendingException;
            }

            mSessionStateCallback = mCurrentSession.getDeviceStateCallback();
        }
    }

    /**
     * For use by backwards-compatibility code only.
     */
    public void setSessionListener(StateCallbackKK sessionCallback) {
        synchronized(mInterfaceLock) {
            mSessionStateCallback = sessionCallback;
        }
    }

    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType)
            throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            CameraMetadataNative templatedRequest = new CameraMetadataNative();

            try {
                mRemoteDevice.createDefaultRequest(templateType, /*out*/templatedRequest);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return null;
            }

            CaptureRequest.Builder builder = new CaptureRequest.Builder(
                    templatedRequest, /*reprocess*/false, CameraCaptureSession.SESSION_ID_NONE);

            return builder;
        }
    }

    @Override
    public CaptureRequest.Builder createReprocessCaptureRequest(TotalCaptureResult inputResult)
            throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            CameraMetadataNative resultMetadata = new
                    CameraMetadataNative(inputResult.getNativeCopy());

            return new CaptureRequest.Builder(resultMetadata, /*reprocess*/true,
                    inputResult.getSessionId());
        }
    }

    public void prepare(Surface surface) throws CameraAccessException {
        if (surface == null) throw new IllegalArgumentException("Surface is null");

        synchronized(mInterfaceLock) {
            int streamId = -1;
            for (int i = 0; i < mConfiguredOutputs.size(); i++) {
                if (surface == mConfiguredOutputs.valueAt(i).getSurface()) {
                    streamId = mConfiguredOutputs.keyAt(i);
                    break;
                }
            }
            if (streamId == -1) {
                throw new IllegalArgumentException("Surface is not part of this session");
            }
            try {
                mRemoteDevice.prepare(streamId);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    public void prepare(int maxCount, Surface surface) throws CameraAccessException {
        if (surface == null) throw new IllegalArgumentException("Surface is null");
        if (maxCount <= 0) throw new IllegalArgumentException("Invalid maxCount given: " +
                maxCount);

        synchronized(mInterfaceLock) {
            int streamId = -1;
            for (int i = 0; i < mConfiguredOutputs.size(); i++) {
                if (surface == mConfiguredOutputs.valueAt(i).getSurface()) {
                    streamId = mConfiguredOutputs.keyAt(i);
                    break;
                }
            }
            if (streamId == -1) {
                throw new IllegalArgumentException("Surface is not part of this session");
            }
            try {
                mRemoteDevice.prepare2(maxCount, streamId);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    public void tearDown(Surface surface) throws CameraAccessException {
        if (surface == null) throw new IllegalArgumentException("Surface is null");

        synchronized(mInterfaceLock) {
            int streamId = -1;
            for (int i = 0; i < mConfiguredOutputs.size(); i++) {
                if (surface == mConfiguredOutputs.valueAt(i).getSurface()) {
                    streamId = mConfiguredOutputs.keyAt(i);
                    break;
                }
            }
            if (streamId == -1) {
                throw new IllegalArgumentException("Surface is not part of this session");
            }
            try {
                mRemoteDevice.tearDown(streamId);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    public int capture(CaptureRequest request, CaptureCallback callback, Handler handler)
            throws CameraAccessException {
        if (DEBUG) {
            Log.d(TAG, "calling capture");
        }
        List<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitCaptureRequest(requestList, callback, handler, /*streaming*/false);
    }

    public int captureBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one request must be given");
        }
        return submitCaptureRequest(requests, callback, handler, /*streaming*/false);
    }

    /**
     * This method checks lastFrameNumber returned from ICameraDeviceUser methods for
     * starting and stopping repeating request and flushing.
     *
     * <p>If lastFrameNumber is NO_FRAMES_CAPTURED, it means that the request was never
     * sent to HAL. Then onCaptureSequenceAborted is immediately triggered.
     * If lastFrameNumber is non-negative, then the requestId and lastFrameNumber as the last
     * regular frame number will be added to the list mRequestLastFrameNumbersList.</p>
     *
     * @param requestId the request ID of the current repeating request.
     *
     * @param lastFrameNumber last frame number returned from binder.
     */
    private void checkEarlyTriggerSequenceComplete(
            final int requestId, final long lastFrameNumber) {
        // lastFrameNumber being equal to NO_FRAMES_CAPTURED means that the request
        // was never sent to HAL. Should trigger onCaptureSequenceAborted immediately.
        if (lastFrameNumber == CaptureCallback.NO_FRAMES_CAPTURED) {
            final CaptureCallbackHolder holder;
            int index = mCaptureCallbackMap.indexOfKey(requestId);
            holder = (index >= 0) ? mCaptureCallbackMap.valueAt(index) : null;
            if (holder != null) {
                mCaptureCallbackMap.removeAt(index);
                if (DEBUG) {
                    Log.v(TAG, String.format(
                            "remove holder for requestId %d, "
                            + "because lastFrame is %d.",
                            requestId, lastFrameNumber));
                }
            }

            if (holder != null) {
                if (DEBUG) {
                    Log.v(TAG, "immediately trigger onCaptureSequenceAborted because"
                            + " request did not reach HAL");
                }

                Runnable resultDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!CameraDeviceImpl.this.isClosed()) {
                            if (DEBUG) {
                                Log.d(TAG, String.format(
                                        "early trigger sequence complete for request %d",
                                        requestId));
                            }
                            holder.getCallback().onCaptureSequenceAborted(
                                    CameraDeviceImpl.this,
                                    requestId);
                        }
                    }
                };
                holder.getHandler().post(resultDispatch);
            } else {
                Log.w(TAG, String.format(
                        "did not register callback to request %d",
                        requestId));
            }
        } else {
            // This function is only called for regular request so lastFrameNumber is the last
            // regular frame number.
            mRequestLastFrameNumbersList.add(new RequestLastFrameNumbersHolder(requestId,
                    lastFrameNumber));

            // It is possible that the last frame has already arrived, so we need to check
            // for sequence completion right away
            checkAndFireSequenceComplete();
        }
    }

    private int submitCaptureRequest(List<CaptureRequest> requestList, CaptureCallback callback,
            Handler handler, boolean repeating) throws CameraAccessException {

        // Need a valid handler, or current thread needs to have a looper, if
        // callback is valid
        handler = checkHandler(handler, callback);

        // Make sure that there all requests have at least 1 surface; all surfaces are non-null
        for (CaptureRequest request : requestList) {
            if (request.getTargets().isEmpty()) {
                throw new IllegalArgumentException(
                        "Each request must have at least one Surface target");
            }

            for (Surface surface : request.getTargets()) {
                if (surface == null) {
                    throw new IllegalArgumentException("Null Surface targets are not allowed");
                }
            }
        }

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();
            int requestId;

            if (repeating) {
                stopRepeating();
            }

            LongParcelable lastFrameNumberRef = new LongParcelable();
            try {
                requestId = mRemoteDevice.submitRequestList(requestList, repeating,
                        /*out*/lastFrameNumberRef);
                if (DEBUG) {
                    Log.v(TAG, "last frame number " + lastFrameNumberRef.getNumber());
                }
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return -1;
            }

            if (callback != null) {
                mCaptureCallbackMap.put(requestId, new CaptureCallbackHolder(callback,
                        requestList, handler, repeating, mNextSessionId - 1));
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Listen for request " + requestId + " is null");
                }
            }

            long lastFrameNumber = lastFrameNumberRef.getNumber();

            if (repeating) {
                if (mRepeatingRequestId != REQUEST_ID_NONE) {
                    checkEarlyTriggerSequenceComplete(mRepeatingRequestId, lastFrameNumber);
                }
                mRepeatingRequestId = requestId;
            } else {
                mRequestLastFrameNumbersList.add(new RequestLastFrameNumbersHolder(requestList,
                        requestId, lastFrameNumber));
            }

            if (mIdle) {
                mDeviceHandler.post(mCallOnActive);
            }
            mIdle = false;

            return requestId;
        }
    }

    public int setRepeatingRequest(CaptureRequest request, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        List<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitCaptureRequest(requestList, callback, handler, /*streaming*/true);
    }

    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one request must be given");
        }
        return submitCaptureRequest(requests, callback, handler, /*streaming*/true);
    }

    public void stopRepeating() throws CameraAccessException {

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();
            if (mRepeatingRequestId != REQUEST_ID_NONE) {

                int requestId = mRepeatingRequestId;
                mRepeatingRequestId = REQUEST_ID_NONE;

                // Queue for deletion after in-flight requests finish
                if (mCaptureCallbackMap.get(requestId) != null) {
                    mRepeatingRequestIdDeletedList.add(requestId);
                }

                try {
                    LongParcelable lastFrameNumberRef = new LongParcelable();
                    mRemoteDevice.cancelRequest(requestId, /*out*/lastFrameNumberRef);
                    long lastFrameNumber = lastFrameNumberRef.getNumber();

                    checkEarlyTriggerSequenceComplete(requestId, lastFrameNumber);

                } catch (CameraRuntimeException e) {
                    throw e.asChecked();
                } catch (RemoteException e) {
                    // impossible
                    return;
                }
            }
        }
    }

    private void waitUntilIdle() throws CameraAccessException {

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            if (mRepeatingRequestId != REQUEST_ID_NONE) {
                throw new IllegalStateException("Active repeating request ongoing");
            }
            try {
                mRemoteDevice.waitUntilIdle();
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    public void flush() throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            mDeviceHandler.post(mCallOnBusy);

            // If already idle, just do a busy->idle transition immediately, don't actually
            // flush.
            if (mIdle) {
                mDeviceHandler.post(mCallOnIdle);
                return;
            }
            try {
                LongParcelable lastFrameNumberRef = new LongParcelable();
                mRemoteDevice.flush(/*out*/lastFrameNumberRef);
                if (mRepeatingRequestId != REQUEST_ID_NONE) {
                    long lastFrameNumber = lastFrameNumberRef.getNumber();
                    checkEarlyTriggerSequenceComplete(mRepeatingRequestId, lastFrameNumber);
                    mRepeatingRequestId = REQUEST_ID_NONE;
                }
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    @Override
    public void close() {
        synchronized (mInterfaceLock) {
            if (mClosing.getAndSet(true)) {
                return;
            }

            try {
                if (mRemoteDevice != null) {
                    mRemoteDevice.disconnect();
                }
            } catch (CameraRuntimeException e) {
                Log.e(TAG, "Exception while closing: ", e.asChecked());
            } catch (RemoteException e) {
                // impossible
            }

            // Only want to fire the onClosed callback once;
            // either a normal close where the remote device is valid
            // or a close after a startup error (no remote device but in error state)
            if (mRemoteDevice != null || mInError) {
                mDeviceHandler.post(mCallOnClosed);
            }

            mRemoteDevice = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        }
        finally {
            super.finalize();
        }
    }

    private void checkInputConfiguration(InputConfiguration inputConfig) {
        if (inputConfig != null) {
            StreamConfigurationMap configMap = mCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            int[] inputFormats = configMap.getInputFormats();
            boolean validFormat = false;
            for (int format : inputFormats) {
                if (format == inputConfig.getFormat()) {
                    validFormat = true;
                }
            }

            if (validFormat == false) {
                throw new IllegalArgumentException("input format " + inputConfig.getFormat() +
                        " is not valid");
            }

            boolean validSize = false;
            Size[] inputSizes = configMap.getInputSizes(inputConfig.getFormat());
            for (Size s : inputSizes) {
                if (inputConfig.getWidth() == s.getWidth() &&
                        inputConfig.getHeight() == s.getHeight()) {
                    validSize = true;
                }
            }

            if (validSize == false) {
                throw new IllegalArgumentException("input size " + inputConfig.getWidth() + "x" +
                        inputConfig.getHeight() + " is not valid");
            }
        }
    }

    /**
     * <p>A callback for tracking the progress of a {@link CaptureRequest}
     * submitted to the camera device.</p>
     *
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
         * @see android.media.MediaActionSound
         */
        public void onCaptureStarted(CameraDevice camera,
                CaptureRequest request, long timestamp, long frameNumber) {
            // default empty implementation
        }

        /**
         * This method is called when some results from an image capture are
         * available.
         *
         * @hide
         */
        public void onCapturePartial(CameraDevice camera,
                CaptureRequest request, CaptureResult result) {
            // default empty implementation
        }

        /**
         * This method is called when an image capture makes partial forward progress; some
         * (but not all) results from an image capture are available.
         *
         */
        public void onCaptureProgressed(CameraDevice camera,
                CaptureRequest request, CaptureResult partialResult) {
            // default empty implementation
        }

        /**
         * This method is called when an image capture has fully completed and all the
         * result metadata is available.
         */
        public void onCaptureCompleted(CameraDevice camera,
                CaptureRequest request, TotalCaptureResult result) {
            // default empty implementation
        }

        /**
         * This method is called instead of {@link #onCaptureCompleted} when the
         * camera device failed to produce a {@link CaptureResult} for the
         * request.
         */
        public void onCaptureFailed(CameraDevice camera,
                CaptureRequest request, CaptureFailure failure) {
            // default empty implementation
        }

        /**
         * This method is called independently of the others in CaptureCallback,
         * when a capture sequence finishes and all {@link CaptureResult}
         * or {@link CaptureFailure} for it have been returned via this callback.
         */
        public void onCaptureSequenceCompleted(CameraDevice camera,
                int sequenceId, long frameNumber) {
            // default empty implementation
        }

        /**
         * This method is called independently of the others in CaptureCallback,
         * when a capture sequence aborts before any {@link CaptureResult}
         * or {@link CaptureFailure} for it have been returned via this callback.
         */
        public void onCaptureSequenceAborted(CameraDevice camera,
                int sequenceId) {
            // default empty implementation
        }
    }

    /**
     * A callback for notifications about the state of a camera device, adding in the callbacks that
     * were part of the earlier KK API design, but now only used internally.
     */
    public static abstract class StateCallbackKK extends StateCallback {
        /**
         * The method called when a camera device has no outputs configured.
         *
         */
        public void onUnconfigured(CameraDevice camera) {
            // Default empty implementation
        }

        /**
         * The method called when a camera device begins processing
         * {@link CaptureRequest capture requests}.
         *
         */
        public void onActive(CameraDevice camera) {
            // Default empty implementation
        }

        /**
         * The method called when a camera device is busy.
         *
         */
        public void onBusy(CameraDevice camera) {
            // Default empty implementation
        }

        /**
         * The method called when a camera device has finished processing all
         * submitted capture requests and has reached an idle state.
         *
         */
        public void onIdle(CameraDevice camera) {
            // Default empty implementation
        }

        /**
         * The method called when the camera device has finished preparing
         * an output Surface
         */
        public void onSurfacePrepared(Surface surface) {
            // Default empty implementation
        }
    }

    static class CaptureCallbackHolder {

        private final boolean mRepeating;
        private final CaptureCallback mCallback;
        private final List<CaptureRequest> mRequestList;
        private final Handler mHandler;
        private final int mSessionId;

        CaptureCallbackHolder(CaptureCallback callback, List<CaptureRequest> requestList,
                Handler handler, boolean repeating, int sessionId) {
            if (callback == null || handler == null) {
                throw new UnsupportedOperationException(
                    "Must have a valid handler and a valid callback");
            }
            mRepeating = repeating;
            mHandler = handler;
            mRequestList = new ArrayList<CaptureRequest>(requestList);
            mCallback = callback;
            mSessionId = sessionId;
        }

        public boolean isRepeating() {
            return mRepeating;
        }

        public CaptureCallback getCallback() {
            return mCallback;
        }

        public CaptureRequest getRequest(int subsequenceId) {
            if (subsequenceId >= mRequestList.size()) {
                throw new IllegalArgumentException(
                        String.format(
                                "Requested subsequenceId %d is larger than request list size %d.",
                                subsequenceId, mRequestList.size()));
            } else {
                if (subsequenceId < 0) {
                    throw new IllegalArgumentException(String.format(
                            "Requested subsequenceId %d is negative", subsequenceId));
                } else {
                    return mRequestList.get(subsequenceId);
                }
            }
        }

        public CaptureRequest getRequest() {
            return getRequest(0);
        }

        public Handler getHandler() {
            return mHandler;
        }

        public int getSessionId() {
            return mSessionId;
        }
    }

    /**
     * This class holds a capture ID and its expected last regular frame number and last reprocess
     * frame number.
     */
    static class RequestLastFrameNumbersHolder {
        // request ID
        private final int mRequestId;
        // The last regular frame number for this request ID. It's
        // CaptureCallback.NO_FRAMES_CAPTURED if the request ID has no regular request.
        private final long mLastRegularFrameNumber;
        // The last reprocess frame number for this request ID. It's
        // CaptureCallback.NO_FRAMES_CAPTURED if the request ID has no reprocess request.
        private final long mLastReprocessFrameNumber;

        /**
         * Create a request-last-frame-numbers holder with a list of requests, request ID, and
         * the last frame number returned by camera service.
         */
        public RequestLastFrameNumbersHolder(List<CaptureRequest> requestList, int requestId,
                long lastFrameNumber) {
            long lastRegularFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;
            long lastReprocessFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;
            long frameNumber = lastFrameNumber;

            if (lastFrameNumber < requestList.size() - 1) {
                throw new IllegalArgumentException("lastFrameNumber: " + lastFrameNumber +
                        " should be at least " + (requestList.size() - 1) + " for the number of " +
                        " requests in the list: " + requestList.size());
            }

            // find the last regular frame number and the last reprocess frame number
            for (int i = requestList.size() - 1; i >= 0; i--) {
                CaptureRequest request = requestList.get(i);
                if (request.isReprocess() && lastReprocessFrameNumber ==
                        CaptureCallback.NO_FRAMES_CAPTURED) {
                    lastReprocessFrameNumber = frameNumber;
                } else if (!request.isReprocess() && lastRegularFrameNumber ==
                        CaptureCallback.NO_FRAMES_CAPTURED) {
                    lastRegularFrameNumber = frameNumber;
                }

                if (lastReprocessFrameNumber != CaptureCallback.NO_FRAMES_CAPTURED &&
                        lastRegularFrameNumber != CaptureCallback.NO_FRAMES_CAPTURED) {
                    break;
                }

                frameNumber--;
            }

            mLastRegularFrameNumber = lastRegularFrameNumber;
            mLastReprocessFrameNumber = lastReprocessFrameNumber;
            mRequestId = requestId;
        }

        /**
         * Create a request-last-frame-numbers holder with a request ID and last regular frame
         * number.
         */
        public RequestLastFrameNumbersHolder(int requestId, long lastRegularFrameNumber) {
            mLastRegularFrameNumber = lastRegularFrameNumber;
            mLastReprocessFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;
            mRequestId = requestId;
        }

        /**
         * Return the last regular frame number. Return CaptureCallback.NO_FRAMES_CAPTURED if
         * it contains no regular request.
         */
        public long getLastRegularFrameNumber() {
            return mLastRegularFrameNumber;
        }

        /**
         * Return the last reprocess frame number. Return CaptureCallback.NO_FRAMES_CAPTURED if
         * it contains no reprocess request.
         */
        public long getLastReprocessFrameNumber() {
            return mLastReprocessFrameNumber;
        }

        /**
         * Return the last frame number overall.
         */
        public long getLastFrameNumber() {
            return Math.max(mLastRegularFrameNumber, mLastReprocessFrameNumber);
        }

        /**
         * Return the request ID.
         */
        public int getRequestId() {
            return mRequestId;
        }
    }

    /**
     * This class tracks the last frame number for submitted requests.
     */
    public class FrameNumberTracker {

        private long mCompletedFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;
        private long mCompletedReprocessFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;
        /** the skipped frame numbers that belong to regular results */
        private final LinkedList<Long> mSkippedRegularFrameNumbers = new LinkedList<Long>();
        /** the skipped frame numbers that belong to reprocess results */
        private final LinkedList<Long> mSkippedReprocessFrameNumbers = new LinkedList<Long>();
        /** frame number -> is reprocess */
        private final TreeMap<Long, Boolean> mFutureErrorMap = new TreeMap<Long, Boolean>();
        /** Map frame numbers to list of partial results */
        private final HashMap<Long, List<CaptureResult>> mPartialResults = new HashMap<>();

        private void update() {
            Iterator iter = mFutureErrorMap.entrySet().iterator();
            while (iter.hasNext()) {
                TreeMap.Entry pair = (TreeMap.Entry)iter.next();
                Long errorFrameNumber = (Long)pair.getKey();
                Boolean reprocess = (Boolean)pair.getValue();
                Boolean removeError = true;
                if (reprocess) {
                    if (errorFrameNumber == mCompletedReprocessFrameNumber + 1) {
                        mCompletedReprocessFrameNumber = errorFrameNumber;
                    } else if (mSkippedReprocessFrameNumbers.isEmpty() != true &&
                            errorFrameNumber == mSkippedReprocessFrameNumbers.element()) {
                        mCompletedReprocessFrameNumber = errorFrameNumber;
                        mSkippedReprocessFrameNumbers.remove();
                    } else {
                        removeError = false;
                    }
                } else {
                    if (errorFrameNumber == mCompletedFrameNumber + 1) {
                        mCompletedFrameNumber = errorFrameNumber;
                    } else if (mSkippedRegularFrameNumbers.isEmpty() != true &&
                            errorFrameNumber == mSkippedRegularFrameNumbers.element()) {
                        mCompletedFrameNumber = errorFrameNumber;
                        mSkippedRegularFrameNumbers.remove();
                    } else {
                        removeError = false;
                    }
                }
                if (removeError) {
                    iter.remove();
                }
            }
        }

        /**
         * This function is called every time when a result or an error is received.
         * @param frameNumber the frame number corresponding to the result or error
         * @param isError true if it is an error, false if it is not an error
         * @param isReprocess true if it is a reprocess result, false if it is a regular result.
         */
        public void updateTracker(long frameNumber, boolean isError, boolean isReprocess) {
            if (isError) {
                mFutureErrorMap.put(frameNumber, isReprocess);
            } else {
                try {
                    if (isReprocess) {
                        updateCompletedReprocessFrameNumber(frameNumber);
                    } else {
                        updateCompletedFrameNumber(frameNumber);
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, e.getMessage());
                }
            }
            update();
        }

        /**
         * This function is called every time a result has been completed.
         *
         * <p>It keeps a track of all the partial results already created for a particular
         * frame number.</p>
         *
         * @param frameNumber the frame number corresponding to the result
         * @param result the total or partial result
         * @param partial {@true} if the result is partial, {@code false} if total
         * @param isReprocess true if it is a reprocess result, false if it is a regular result.
         */
        public void updateTracker(long frameNumber, CaptureResult result, boolean partial,
                boolean isReprocess) {
            if (!partial) {
                // Update the total result's frame status as being successful
                updateTracker(frameNumber, /*isError*/false, isReprocess);
                // Don't keep a list of total results, we don't need to track them
                return;
            }

            if (result == null) {
                // Do not record blank results; this also means there will be no total result
                // so it doesn't matter that the partials were not recorded
                return;
            }

            // Partial results must be aggregated in-order for that frame number
            List<CaptureResult> partials = mPartialResults.get(frameNumber);
            if (partials == null) {
                partials = new ArrayList<>();
                mPartialResults.put(frameNumber, partials);
            }

            partials.add(result);
        }

        /**
         * Attempt to pop off all of the partial results seen so far for the {@code frameNumber}.
         *
         * <p>Once popped-off, the partial results are forgotten (unless {@code updateTracker}
         * is called again with new partials for that frame number).</p>
         *
         * @param frameNumber the frame number corresponding to the result
         * @return a list of partial results for that frame with at least 1 element,
         *         or {@code null} if there were no partials recorded for that frame
         */
        public List<CaptureResult> popPartialResults(long frameNumber) {
            return mPartialResults.remove(frameNumber);
        }

        public long getCompletedFrameNumber() {
            return mCompletedFrameNumber;
        }

        public long getCompletedReprocessFrameNumber() {
            return mCompletedReprocessFrameNumber;
        }

        /**
         * Update the completed frame number for regular results.
         *
         * It validates that all previous frames have arrived except for reprocess frames.
         *
         * If there is a gap since previous regular frame number, assume the frames in the gap are
         * reprocess frames and store them in the skipped reprocess frame number queue to check
         * against when reprocess frames arrive.
         */
        private void updateCompletedFrameNumber(long frameNumber) throws IllegalArgumentException {
            if (frameNumber <= mCompletedFrameNumber) {
                throw new IllegalArgumentException("frame number " + frameNumber + " is a repeat");
            } else if (frameNumber <= mCompletedReprocessFrameNumber) {
                // if frame number is smaller than completed reprocess frame number,
                // it must be the head of mSkippedRegularFrameNumbers
                if (mSkippedRegularFrameNumbers.isEmpty() == true ||
                        frameNumber < mSkippedRegularFrameNumbers.element()) {
                    throw new IllegalArgumentException("frame number " + frameNumber +
                            " is a repeat");
                } else if (frameNumber > mSkippedRegularFrameNumbers.element()) {
                    throw new IllegalArgumentException("frame number " + frameNumber +
                            " comes out of order. Expecting " +
                            mSkippedRegularFrameNumbers.element());
                }
                // frame number matches the head of the skipped frame number queue.
                mSkippedRegularFrameNumbers.remove();
            } else {
                // there is a gap of unseen frame numbers which should belong to reprocess result
                // put all the skipped frame numbers in the queue
                for (long i = Math.max(mCompletedFrameNumber, mCompletedReprocessFrameNumber) + 1;
                        i < frameNumber; i++) {
                    mSkippedReprocessFrameNumbers.add(i);
                }
            }

            mCompletedFrameNumber = frameNumber;
        }

        /**
         * Update the completed frame number for reprocess results.
         *
         * It validates that all previous frames have arrived except for regular frames.
         *
         * If there is a gap since previous reprocess frame number, assume the frames in the gap are
         * regular frames and store them in the skipped regular frame number queue to check
         * against when regular frames arrive.
         */
        private void updateCompletedReprocessFrameNumber(long frameNumber)
                throws IllegalArgumentException {
            if (frameNumber < mCompletedReprocessFrameNumber) {
                throw new IllegalArgumentException("frame number " + frameNumber + " is a repeat");
            } else if (frameNumber < mCompletedFrameNumber) {
                // if reprocess frame number is smaller than completed regular frame number,
                // it must be the head of the skipped reprocess frame number queue.
                if (mSkippedReprocessFrameNumbers.isEmpty() == true ||
                        frameNumber < mSkippedReprocessFrameNumbers.element()) {
                    throw new IllegalArgumentException("frame number " + frameNumber +
                            " is a repeat");
                } else if (frameNumber > mSkippedReprocessFrameNumbers.element()) {
                    throw new IllegalArgumentException("frame number " + frameNumber +
                            " comes out of order. Expecting " +
                            mSkippedReprocessFrameNumbers.element());
                }
                // frame number matches the head of the skipped frame number queue.
                mSkippedReprocessFrameNumbers.remove();
            } else {
                // put all the skipped frame numbers in the queue
                for (long i = Math.max(mCompletedFrameNumber, mCompletedReprocessFrameNumber) + 1;
                        i < frameNumber; i++) {
                    mSkippedRegularFrameNumbers.add(i);
                }
            }
            mCompletedReprocessFrameNumber = frameNumber;
        }
    }

    private void checkAndFireSequenceComplete() {
        long completedFrameNumber = mFrameNumberTracker.getCompletedFrameNumber();
        long completedReprocessFrameNumber = mFrameNumberTracker.getCompletedReprocessFrameNumber();
        boolean isReprocess = false;
        Iterator<RequestLastFrameNumbersHolder> iter = mRequestLastFrameNumbersList.iterator();
        while (iter.hasNext()) {
            final RequestLastFrameNumbersHolder requestLastFrameNumbers = iter.next();
            boolean sequenceCompleted = false;
            final int requestId = requestLastFrameNumbers.getRequestId();
            final CaptureCallbackHolder holder;
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) {
                    Log.w(TAG, "Camera closed while checking sequences");
                    return;
                }

                int index = mCaptureCallbackMap.indexOfKey(requestId);
                holder = (index >= 0) ?
                        mCaptureCallbackMap.valueAt(index) : null;
                if (holder != null) {
                    long lastRegularFrameNumber =
                            requestLastFrameNumbers.getLastRegularFrameNumber();
                    long lastReprocessFrameNumber =
                            requestLastFrameNumbers.getLastReprocessFrameNumber();

                    // check if it's okay to remove request from mCaptureCallbackMap
                    if (lastRegularFrameNumber <= completedFrameNumber &&
                            lastReprocessFrameNumber <= completedReprocessFrameNumber) {
                        sequenceCompleted = true;
                        mCaptureCallbackMap.removeAt(index);
                        if (DEBUG) {
                            Log.v(TAG, String.format(
                                    "Remove holder for requestId %d, because lastRegularFrame %d " +
                                    "is <= %d and lastReprocessFrame %d is <= %d", requestId,
                                    lastRegularFrameNumber, completedFrameNumber,
                                    lastReprocessFrameNumber, completedReprocessFrameNumber));
                        }
                    }
                }
            }

            // If no callback is registered for this requestId or sequence completed, remove it
            // from the frame number->request pair because it's not needed anymore.
            if (holder == null || sequenceCompleted) {
                iter.remove();
            }

            // Call onCaptureSequenceCompleted
            if (sequenceCompleted) {
                Runnable resultDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!CameraDeviceImpl.this.isClosed()){
                            if (DEBUG) {
                                Log.d(TAG, String.format(
                                        "fire sequence complete for request %d",
                                        requestId));
                            }

                            holder.getCallback().onCaptureSequenceCompleted(
                                CameraDeviceImpl.this,
                                requestId,
                                requestLastFrameNumbers.getLastFrameNumber());
                        }
                    }
                };
                holder.getHandler().post(resultDispatch);
            }
        }
    }

    public class CameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {
        //
        // Constants below need to be kept up-to-date with
        // frameworks/av/include/camera/camera2/ICameraDeviceCallbacks.h
        //

        //
        // Error codes for onCameraError
        //

        /**
         * Camera has been disconnected
         */
        public static final int ERROR_CAMERA_DISCONNECTED = 0;
        /**
         * Camera has encountered a device-level error
         * Matches CameraDevice.StateCallback#ERROR_CAMERA_DEVICE
         */
        public static final int ERROR_CAMERA_DEVICE = 1;
        /**
         * Camera has encountered a service-level error
         * Matches CameraDevice.StateCallback#ERROR_CAMERA_SERVICE
         */
        public static final int ERROR_CAMERA_SERVICE = 2;
        /**
         * Camera has encountered an error processing a single request.
         */
        public static final int ERROR_CAMERA_REQUEST = 3;
        /**
         * Camera has encountered an error producing metadata for a single capture
         */
        public static final int ERROR_CAMERA_RESULT = 4;
        /**
         * Camera has encountered an error producing an image buffer for a single capture
         */
        public static final int ERROR_CAMERA_BUFFER = 5;

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onDeviceError(final int errorCode, CaptureResultExtras resultExtras) {
            if (DEBUG) {
                Log.d(TAG, String.format(
                        "Device error received, code %d, frame number %d, request ID %d, subseq ID %d",
                        errorCode, resultExtras.getFrameNumber(), resultExtras.getRequestId(),
                        resultExtras.getSubsequenceId()));
            }

            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) {
                    return; // Camera already closed
                }

                switch (errorCode) {
                    case ERROR_CAMERA_DISCONNECTED:
                        CameraDeviceImpl.this.mDeviceHandler.post(mCallOnDisconnected);
                        break;
                    default:
                        Log.e(TAG, "Unknown error from camera device: " + errorCode);
                        // no break
                    case ERROR_CAMERA_DEVICE:
                    case ERROR_CAMERA_SERVICE:
                        mInError = true;
                        Runnable r = new Runnable() {
                            @Override
                            public void run() {
                                if (!CameraDeviceImpl.this.isClosed()) {
                                    mDeviceCallback.onError(CameraDeviceImpl.this, errorCode);
                                }
                            }
                        };
                        CameraDeviceImpl.this.mDeviceHandler.post(r);
                        break;
                    case ERROR_CAMERA_REQUEST:
                    case ERROR_CAMERA_RESULT:
                    case ERROR_CAMERA_BUFFER:
                        onCaptureErrorLocked(errorCode, resultExtras);
                        break;
                }
            }
        }

        @Override
        public void onDeviceIdle() {
            if (DEBUG) {
                Log.d(TAG, "Camera now idle");
            }
            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                if (!CameraDeviceImpl.this.mIdle) {
                    CameraDeviceImpl.this.mDeviceHandler.post(mCallOnIdle);
                }
                CameraDeviceImpl.this.mIdle = true;
            }
        }

        @Override
        public void onCaptureStarted(final CaptureResultExtras resultExtras, final long timestamp) {
            int requestId = resultExtras.getRequestId();
            final long frameNumber = resultExtras.getFrameNumber();

            if (DEBUG) {
                Log.d(TAG, "Capture started for id " + requestId + " frame number " + frameNumber);
            }
            final CaptureCallbackHolder holder;

            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                // Get the callback for this frame ID, if there is one
                holder = CameraDeviceImpl.this.mCaptureCallbackMap.get(requestId);

                if (holder == null) {
                    return;
                }

                if (isClosed()) return;

                // Dispatch capture start notice
                holder.getHandler().post(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()) {
                                holder.getCallback().onCaptureStarted(
                                    CameraDeviceImpl.this,
                                    holder.getRequest(resultExtras.getSubsequenceId()),
                                    timestamp, frameNumber);
                            }
                        }
                    });

            }
        }

        @Override
        public void onResultReceived(CameraMetadataNative result,
                CaptureResultExtras resultExtras) throws RemoteException {

            int requestId = resultExtras.getRequestId();
            long frameNumber = resultExtras.getFrameNumber();

            if (DEBUG) {
                Log.v(TAG, "Received result frame " + frameNumber + " for id "
                        + requestId);
            }

            synchronized(mInterfaceLock) {
                if (mRemoteDevice == null) return; // Camera already closed

                // TODO: Handle CameraCharacteristics access from CaptureResult correctly.
                result.set(CameraCharacteristics.LENS_INFO_SHADING_MAP_SIZE,
                        getCharacteristics().get(CameraCharacteristics.LENS_INFO_SHADING_MAP_SIZE));

                final CaptureCallbackHolder holder =
                        CameraDeviceImpl.this.mCaptureCallbackMap.get(requestId);
                final CaptureRequest request = holder.getRequest(resultExtras.getSubsequenceId());

                boolean isPartialResult =
                        (resultExtras.getPartialResultCount() < mTotalPartialCount);
                boolean isReprocess = request.isReprocess();

                // Check if we have a callback for this
                if (holder == null) {
                    if (DEBUG) {
                        Log.d(TAG,
                                "holder is null, early return at frame "
                                        + frameNumber);
                    }

                    mFrameNumberTracker.updateTracker(frameNumber, /*result*/null, isPartialResult,
                            isReprocess);

                    return;
                }

                if (isClosed()) {
                    if (DEBUG) {
                        Log.d(TAG,
                                "camera is closed, early return at frame "
                                        + frameNumber);
                    }

                    mFrameNumberTracker.updateTracker(frameNumber, /*result*/null, isPartialResult,
                            isReprocess);
                    return;
                }


                Runnable resultDispatch = null;

                CaptureResult finalResult;

                // Either send a partial result or the final capture completed result
                if (isPartialResult) {
                    final CaptureResult resultAsCapture =
                            new CaptureResult(result, request, resultExtras);

                    // Partial result
                    resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()){
                                holder.getCallback().onCaptureProgressed(
                                    CameraDeviceImpl.this,
                                    request,
                                    resultAsCapture);
                            }
                        }
                    };

                    finalResult = resultAsCapture;
                } else {
                    List<CaptureResult> partialResults =
                            mFrameNumberTracker.popPartialResults(frameNumber);

                    final TotalCaptureResult resultAsCapture = new TotalCaptureResult(result,
                            request, resultExtras, partialResults, holder.getSessionId());

                    // Final capture result
                    resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()){
                                holder.getCallback().onCaptureCompleted(
                                    CameraDeviceImpl.this,
                                    request,
                                    resultAsCapture);
                            }
                        }
                    };

                    finalResult = resultAsCapture;
                }

                holder.getHandler().post(resultDispatch);

                // Collect the partials for a total result; or mark the frame as totally completed
                mFrameNumberTracker.updateTracker(frameNumber, finalResult, isPartialResult,
                        isReprocess);

                // Fire onCaptureSequenceCompleted
                if (!isPartialResult) {
                    checkAndFireSequenceComplete();
                }
            }
        }

        @Override
        public void onPrepared(int streamId) {
            final OutputConfiguration output;
            final StateCallbackKK sessionCallback;

            if (DEBUG) {
                Log.v(TAG, "Stream " + streamId + " is prepared");
            }

            synchronized(mInterfaceLock) {
                output = mConfiguredOutputs.get(streamId);
                sessionCallback = mSessionStateCallback;
            }

            if (sessionCallback == null) return;

            if (output == null) {
                Log.w(TAG, "onPrepared invoked for unknown output Surface");
                return;
            }
            final Surface surface = output.getSurface();

            sessionCallback.onSurfacePrepared(surface);
        }

        /**
         * Called by onDeviceError for handling single-capture failures.
         */
        private void onCaptureErrorLocked(int errorCode, CaptureResultExtras resultExtras) {

            final int requestId = resultExtras.getRequestId();
            final int subsequenceId = resultExtras.getSubsequenceId();
            final long frameNumber = resultExtras.getFrameNumber();
            final CaptureCallbackHolder holder =
                    CameraDeviceImpl.this.mCaptureCallbackMap.get(requestId);

            final CaptureRequest request = holder.getRequest(subsequenceId);

            // No way to report buffer errors right now
            if (errorCode == ERROR_CAMERA_BUFFER) {
                Log.e(TAG, String.format("Lost output buffer reported for frame %d", frameNumber));
                return;
            }

            boolean mayHaveBuffers = (errorCode == ERROR_CAMERA_RESULT);

            // This is only approximate - exact handling needs the camera service and HAL to
            // disambiguate between request failures to due abort and due to real errors.
            // For now, assume that if the session believes we're mid-abort, then the error
            // is due to abort.
            int reason = (mCurrentSession != null && mCurrentSession.isAborting()) ?
                    CaptureFailure.REASON_FLUSHED :
                    CaptureFailure.REASON_ERROR;

            final CaptureFailure failure = new CaptureFailure(
                request,
                reason,
                /*dropped*/ mayHaveBuffers,
                requestId,
                frameNumber);

            Runnable failureDispatch = new Runnable() {
                @Override
                public void run() {
                    if (!CameraDeviceImpl.this.isClosed()){
                        holder.getCallback().onCaptureFailed(
                            CameraDeviceImpl.this,
                            request,
                            failure);
                    }
                }
            };
            holder.getHandler().post(failureDispatch);

            // Fire onCaptureSequenceCompleted if appropriate
            if (DEBUG) {
                Log.v(TAG, String.format("got error frame %d", frameNumber));
            }
            mFrameNumberTracker.updateTracker(frameNumber, /*error*/true, request.isReprocess());
            checkAndFireSequenceComplete();
        }

    } // public class CameraDeviceCallbacks

    /**
     * Default handler management.
     *
     * <p>
     * If handler is null, get the current thread's
     * Looper to create a Handler with. If no looper exists, throw {@code IllegalArgumentException}.
     * </p>
     */
    static Handler checkHandler(Handler handler) {
        if (handler == null) {
            Looper looper = Looper.myLooper();
            if (looper == null) {
                throw new IllegalArgumentException(
                    "No handler given, and current thread has no looper!");
            }
            handler = new Handler(looper);
        }
        return handler;
    }

    /**
     * Default handler management, conditional on there being a callback.
     *
     * <p>If the callback isn't null, check the handler, otherwise pass it through.</p>
     */
    static <T> Handler checkHandler(Handler handler, T callback) {
        if (callback != null) {
            return checkHandler(handler);
        }
        return handler;
    }

    private void checkIfCameraClosedOrInError() throws CameraAccessException {
        if (mRemoteDevice == null) {
            throw new IllegalStateException("CameraDevice was already closed");
        }
        if (mInError) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                    "The camera device has encountered a serious error");
        }
    }

    /** Whether the camera device has started to close (may not yet have finished) */
    private boolean isClosed() {
        return mClosing.get();
    }

    private CameraCharacteristics getCharacteristics() {
        return mCharacteristics;
    }

}
