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

import static com.android.internal.util.function.pooled.PooledLambda.obtainRunnable;

import android.annotation.NonNull;
import android.hardware.ICameraService;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.utils.SubmitInfo;
import android.hardware.camera2.utils.SurfaceUtils;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseArray;
import android.view.Surface;

import com.android.internal.util.Preconditions;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;


/**
 * HAL2.1+ implementation of CameraDevice. Use CameraManager#open to instantiate
 */
public class CameraDeviceImpl extends CameraDevice
        implements IBinder.DeathRecipient {
    private final String TAG;
    private final boolean DEBUG = false;

    private static final int REQUEST_ID_NONE = -1;

    // TODO: guard every function with if (!mRemoteDevice) check (if it was closed)
    private ICameraDeviceUserWrapper mRemoteDevice;

    // Lock to synchronize cross-thread access to device public interface
    final Object mInterfaceLock = new Object(); // access from this class and Session only!
    private final CameraDeviceCallbacks mCallbacks = new CameraDeviceCallbacks();

    private final StateCallback mDeviceCallback;
    private volatile StateCallbackKK mSessionStateCallback;
    private final Executor mDeviceExecutor;

    private final AtomicBoolean mClosing = new AtomicBoolean();
    private boolean mInError = false;
    private boolean mIdle = true;

    /** map request IDs to callback/request data */
    private final SparseArray<CaptureCallbackHolder> mCaptureCallbackMap =
            new SparseArray<CaptureCallbackHolder>();

    private int mRepeatingRequestId = REQUEST_ID_NONE;
    // Latest repeating request list's types
    private int[] mRepeatingRequestTypes;
    // Map stream IDs to input/output configurations
    private SimpleEntry<Integer, InputConfiguration> mConfiguredInput =
            new SimpleEntry<>(REQUEST_ID_NONE, null);
    private final SparseArray<OutputConfiguration> mConfiguredOutputs =
            new SparseArray<>();

    private final String mCameraId;
    private final CameraCharacteristics mCharacteristics;
    private final int mTotalPartialCount;

    private static final long NANO_PER_SECOND = 1000000000; //ns

    /**
     * A list tracking request and its expected last regular/reprocess/zslStill frame
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

    private final int mAppTargetSdkVersion;

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

    public CameraDeviceImpl(String cameraId, StateCallback callback, Executor executor,
                        CameraCharacteristics characteristics, int appTargetSdkVersion) {
        if (cameraId == null || callback == null || executor == null || characteristics == null) {
            throw new IllegalArgumentException("Null argument given");
        }
        mCameraId = cameraId;
        mDeviceCallback = callback;
        mDeviceExecutor = executor;
        mCharacteristics = characteristics;
        mAppTargetSdkVersion = appTargetSdkVersion;

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

    /**
     * Set remote device, which triggers initial onOpened/onUnconfigured callbacks
     *
     * <p>This function may post onDisconnected and throw CAMERA_DISCONNECTED if remoteDevice dies
     * during setup.</p>
     *
     */
    public void setRemoteDevice(ICameraDeviceUser remoteDevice) throws CameraAccessException {
        synchronized(mInterfaceLock) {
            // TODO: Move from decorator to direct binder-mediated exceptions
            // If setRemoteFailure already called, do nothing
            if (mInError) return;

            mRemoteDevice = new ICameraDeviceUserWrapper(remoteDevice);

            IBinder remoteDeviceBinder = remoteDevice.asBinder();
            // For legacy camera device, remoteDevice is in the same process, and
            // asBinder returns NULL.
            if (remoteDeviceBinder != null) {
                try {
                    remoteDeviceBinder.linkToDeath(this, /*flag*/ 0);
                } catch (RemoteException e) {
                    CameraDeviceImpl.this.mDeviceExecutor.execute(mCallOnDisconnected);

                    throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                            "The camera device has encountered a serious error");
                }
            }

            mDeviceExecutor.execute(mCallOnOpened);
            mDeviceExecutor.execute(mCallOnUnconfigured);
        }
    }

    /**
     * Call to indicate failed connection to a remote camera device.
     *
     * <p>This places the camera device in the error state and informs the callback.
     * Use in place of setRemoteDevice() when startup fails.</p>
     */
    public void setRemoteFailure(final ServiceSpecificException failure) {
        int failureCode = StateCallback.ERROR_CAMERA_DEVICE;
        boolean failureIsError = true;

        switch (failure.errorCode) {
            case ICameraService.ERROR_CAMERA_IN_USE:
                failureCode = StateCallback.ERROR_CAMERA_IN_USE;
                break;
            case ICameraService.ERROR_MAX_CAMERAS_IN_USE:
                failureCode = StateCallback.ERROR_MAX_CAMERAS_IN_USE;
                break;
            case ICameraService.ERROR_DISABLED:
                failureCode = StateCallback.ERROR_CAMERA_DISABLED;
                break;
            case ICameraService.ERROR_DISCONNECTED:
                failureIsError = false;
                break;
            case ICameraService.ERROR_INVALID_OPERATION:
                failureCode = StateCallback.ERROR_CAMERA_DEVICE;
                break;
            default:
                Log.e(TAG, "Unexpected failure in opening camera device: " + failure.errorCode +
                        failure.getMessage());
                break;
        }
        final int code = failureCode;
        final boolean isError = failureIsError;
        synchronized(mInterfaceLock) {
            mInError = true;
            mDeviceExecutor.execute(new Runnable() {
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
                /*operatingMode*/ICameraDeviceUser.NORMAL_MODE, /*sessionParams*/ null);

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
     * @param operatingMode If the stream configuration is for a normal session,
     *     a constrained high speed session, or something else.
     * @param sessionParams Session parameters.
     * @return whether or not the configuration was successful
     *
     * @throws CameraAccessException if there were any unexpected problems during configuration
     */
    public boolean configureStreamsChecked(InputConfiguration inputConfig,
            List<OutputConfiguration> outputs, int operatingMode, CaptureRequest sessionParams)
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

                if (!outputs.contains(outConfig) || outConfig.isDeferredConfiguration()) {
                    // Always delete the deferred output configuration when the session
                    // is created, as the deferred output configuration doesn't have unique surface
                    // related identifies.
                    deleteList.add(streamId);
                } else {
                    addSet.remove(outConfig);  // Don't create a stream previously created
                }
            }

            mDeviceExecutor.execute(mCallOnBusy);
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

                if (sessionParams != null) {
                    mRemoteDevice.endConfigure(operatingMode, sessionParams.getNativeCopy());
                } else {
                    mRemoteDevice.endConfigure(operatingMode, null);
                }

                success = true;
            } catch (IllegalArgumentException e) {
                // OK. camera service can reject stream config if it's not supported by HAL
                // This is only the result of a programmer misusing the camera2 api.
                Log.w(TAG, "Stream configuration failed due to: " + e.getMessage());
                return false;
            } catch (CameraAccessException e) {
                if (e.getReason() == CameraAccessException.CAMERA_IN_USE) {
                    throw new IllegalStateException("The camera is currently busy." +
                            " You must wait until the previous operation completes.", e);
                }
                throw e;
            } finally {
                if (success && outputs.size() > 0) {
                    mDeviceExecutor.execute(mCallOnIdle);
                } else {
                    // Always return to the 'unconfigured' state if we didn't hit a fatal error
                    mDeviceExecutor.execute(mCallOnUnconfigured);
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
        createCaptureSessionInternal(null, outConfigurations, callback,
                checkAndWrapHandler(handler), /*operatingMode*/ICameraDeviceUser.NORMAL_MODE,
                /*sessionParams*/ null);
    }

    @Override
    public void createCaptureSessionByOutputConfigurations(
            List<OutputConfiguration> outputConfigurations,
            CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        if (DEBUG) {
            Log.d(TAG, "createCaptureSessionByOutputConfigurations");
        }

        // OutputConfiguration objects are immutable, but need to have our own array
        List<OutputConfiguration> currentOutputs = new ArrayList<>(outputConfigurations);

        createCaptureSessionInternal(null, currentOutputs, callback, checkAndWrapHandler(handler),
                /*operatingMode*/ICameraDeviceUser.NORMAL_MODE, /*sessionParams*/null);
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
        createCaptureSessionInternal(inputConfig, outConfigurations, callback,
                checkAndWrapHandler(handler), /*operatingMode*/ICameraDeviceUser.NORMAL_MODE,
                /*sessionParams*/ null);
    }

    @Override
    public void createReprocessableCaptureSessionByConfigurations(InputConfiguration inputConfig,
            List<OutputConfiguration> outputs,
            android.hardware.camera2.CameraCaptureSession.StateCallback callback, Handler handler)
                    throws CameraAccessException {
        if (DEBUG) {
            Log.d(TAG, "createReprocessableCaptureSessionWithConfigurations");
        }

        if (inputConfig == null) {
            throw new IllegalArgumentException("inputConfig cannot be null when creating a " +
                    "reprocessable capture session");
        }

        if (outputs == null) {
            throw new IllegalArgumentException("Output configurations cannot be null when " +
                    "creating a reprocessable capture session");
        }

        // OutputConfiguration objects aren't immutable, make a copy before using.
        List<OutputConfiguration> currentOutputs = new ArrayList<OutputConfiguration>();
        for (OutputConfiguration output : outputs) {
            currentOutputs.add(new OutputConfiguration(output));
        }
        createCaptureSessionInternal(inputConfig, currentOutputs,
                callback, checkAndWrapHandler(handler),
                /*operatingMode*/ICameraDeviceUser.NORMAL_MODE, /*sessionParams*/ null);
    }

    @Override
    public void createConstrainedHighSpeedCaptureSession(List<Surface> outputs,
            android.hardware.camera2.CameraCaptureSession.StateCallback callback, Handler handler)
            throws CameraAccessException {
        if (outputs == null || outputs.size() == 0 || outputs.size() > 2) {
            throw new IllegalArgumentException(
                    "Output surface list must not be null and the size must be no more than 2");
        }
        List<OutputConfiguration> outConfigurations = new ArrayList<>(outputs.size());
        for (Surface surface : outputs) {
            outConfigurations.add(new OutputConfiguration(surface));
        }
        createCaptureSessionInternal(null, outConfigurations, callback,
                checkAndWrapHandler(handler),
                /*operatingMode*/ICameraDeviceUser.CONSTRAINED_HIGH_SPEED_MODE,
                /*sessionParams*/ null);
    }

    @Override
    public void createCustomCaptureSession(InputConfiguration inputConfig,
            List<OutputConfiguration> outputs,
            int operatingMode,
            android.hardware.camera2.CameraCaptureSession.StateCallback callback,
            Handler handler) throws CameraAccessException {
        List<OutputConfiguration> currentOutputs = new ArrayList<OutputConfiguration>();
        for (OutputConfiguration output : outputs) {
            currentOutputs.add(new OutputConfiguration(output));
        }
        createCaptureSessionInternal(inputConfig, currentOutputs, callback,
                checkAndWrapHandler(handler), operatingMode, /*sessionParams*/ null);
    }

    @Override
    public void createCaptureSession(SessionConfiguration config)
            throws CameraAccessException {
        if (config == null) {
            throw new IllegalArgumentException("Invalid session configuration");
        }

        List<OutputConfiguration> outputConfigs = config.getOutputConfigurations();
        if (outputConfigs == null) {
            throw new IllegalArgumentException("Invalid output configurations");
        }
        if (config.getExecutor() == null) {
            throw new IllegalArgumentException("Invalid executor");
        }
        createCaptureSessionInternal(config.getInputConfiguration(), outputConfigs,
                config.getStateCallback(), config.getExecutor(), config.getSessionType(),
                config.getSessionParameters());
    }

    private void createCaptureSessionInternal(InputConfiguration inputConfig,
            List<OutputConfiguration> outputConfigurations,
            CameraCaptureSession.StateCallback callback, Executor executor,
            int operatingMode, CaptureRequest sessionParams) throws CameraAccessException {
        synchronized(mInterfaceLock) {
            if (DEBUG) {
                Log.d(TAG, "createCaptureSessionInternal");
            }

            checkIfCameraClosedOrInError();

            boolean isConstrainedHighSpeed =
                    (operatingMode == ICameraDeviceUser.CONSTRAINED_HIGH_SPEED_MODE);
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
                        operatingMode, sessionParams);
                if (configureSuccess == true && inputConfig != null) {
                    input = mRemoteDevice.getInputSurface();
                }
            } catch (CameraAccessException e) {
                configureSuccess = false;
                pendingException = e;
                input = null;
                if (DEBUG) {
                    Log.v(TAG, "createCaptureSession - failed with exception ", e);
                }
            }

            // Fire onConfigured if configureOutputs succeeded, fire onConfigureFailed otherwise.
            CameraCaptureSessionCore newSession = null;
            if (isConstrainedHighSpeed) {
                ArrayList<Surface> surfaces = new ArrayList<>(outputConfigurations.size());
                for (OutputConfiguration outConfig : outputConfigurations) {
                    surfaces.add(outConfig.getSurface());
                }
                StreamConfigurationMap config =
                    getCharacteristics().get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                SurfaceUtils.checkConstrainedHighSpeedSurfaces(surfaces, /*fpsRange*/null, config);

                newSession = new CameraConstrainedHighSpeedCaptureSessionImpl(mNextSessionId++,
                        callback, executor, this, mDeviceExecutor, configureSuccess,
                        mCharacteristics);
            } else {
                newSession = new CameraCaptureSessionImpl(mNextSessionId++, input,
                        callback, executor, this, mDeviceExecutor, configureSuccess);
            }

            // TODO: wait until current session closes, then create the new session
            mCurrentSession = newSession;

            if (pendingException != null) {
                throw pendingException;
            }

            mSessionStateCallback = mCurrentSession.getDeviceStateCallback();
        }
    }

    @Override
    public boolean isSessionConfigurationSupported(
            @NonNull SessionConfiguration sessionConfig) throws CameraAccessException,
            UnsupportedOperationException, IllegalArgumentException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            return mRemoteDevice.isSessionConfigurationSupported(sessionConfig);
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

    private void overrideEnableZsl(CameraMetadataNative request, boolean newValue) {
        Boolean enableZsl = request.get(CaptureRequest.CONTROL_ENABLE_ZSL);
        if (enableZsl == null) {
            // If enableZsl is not available, don't override.
            return;
        }

        request.set(CaptureRequest.CONTROL_ENABLE_ZSL, newValue);
    }

    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType,
            Set<String> physicalCameraIdSet)
            throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            for (String physicalId : physicalCameraIdSet) {
                if (physicalId == getId()) {
                    throw new IllegalStateException("Physical id matches the logical id!");
                }
            }

            CameraMetadataNative templatedRequest = null;

            templatedRequest = mRemoteDevice.createDefaultRequest(templateType);

            // If app target SDK is older than O, or it's not a still capture template, enableZsl
            // must be false in the default request.
            if (mAppTargetSdkVersion < Build.VERSION_CODES.O ||
                    templateType != TEMPLATE_STILL_CAPTURE) {
                overrideEnableZsl(templatedRequest, false);
            }

            CaptureRequest.Builder builder = new CaptureRequest.Builder(
                    templatedRequest, /*reprocess*/false, CameraCaptureSession.SESSION_ID_NONE,
                    getId(), physicalCameraIdSet);

            return builder;
        }
    }

    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType)
            throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            CameraMetadataNative templatedRequest = null;

            templatedRequest = mRemoteDevice.createDefaultRequest(templateType);

            // If app target SDK is older than O, or it's not a still capture template, enableZsl
            // must be false in the default request.
            if (mAppTargetSdkVersion < Build.VERSION_CODES.O ||
                    templateType != TEMPLATE_STILL_CAPTURE) {
                overrideEnableZsl(templatedRequest, false);
            }

            CaptureRequest.Builder builder = new CaptureRequest.Builder(
                    templatedRequest, /*reprocess*/false, CameraCaptureSession.SESSION_ID_NONE,
                    getId(), /*physicalCameraIdSet*/ null);

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
                    inputResult.getSessionId(), getId(), /*physicalCameraIdSet*/ null);
        }
    }

    public void prepare(Surface surface) throws CameraAccessException {
        if (surface == null) throw new IllegalArgumentException("Surface is null");

        synchronized(mInterfaceLock) {
            int streamId = -1;
            for (int i = 0; i < mConfiguredOutputs.size(); i++) {
                final List<Surface> surfaces = mConfiguredOutputs.valueAt(i).getSurfaces();
                if (surfaces.contains(surface)) {
                    streamId = mConfiguredOutputs.keyAt(i);
                    break;
                }
            }
            if (streamId == -1) {
                throw new IllegalArgumentException("Surface is not part of this session");
            }

            mRemoteDevice.prepare(streamId);
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

            mRemoteDevice.prepare2(maxCount, streamId);
        }
    }

    public void updateOutputConfiguration(OutputConfiguration config)
            throws CameraAccessException {
        synchronized(mInterfaceLock) {
            int streamId = -1;
            for (int i = 0; i < mConfiguredOutputs.size(); i++) {
                if (config.getSurface() == mConfiguredOutputs.valueAt(i).getSurface()) {
                    streamId = mConfiguredOutputs.keyAt(i);
                    break;
                }
            }
            if (streamId == -1) {
                throw new IllegalArgumentException("Invalid output configuration");
            }

            mRemoteDevice.updateOutputConfiguration(streamId, config);
            mConfiguredOutputs.put(streamId, config);
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

            mRemoteDevice.tearDown(streamId);
        }
    }

    public void finalizeOutputConfigs(List<OutputConfiguration> outputConfigs)
            throws CameraAccessException {
        if (outputConfigs == null || outputConfigs.size() == 0) {
            throw new IllegalArgumentException("deferred config is null or empty");
        }

        synchronized(mInterfaceLock) {
            for (OutputConfiguration config : outputConfigs) {
                int streamId = -1;
                for (int i = 0; i < mConfiguredOutputs.size(); i++) {
                    // Have to use equal here, as createCaptureSessionByOutputConfigurations() and
                    // createReprocessableCaptureSessionByConfigurations() do a copy of the configs.
                    if (config.equals(mConfiguredOutputs.valueAt(i))) {
                        streamId = mConfiguredOutputs.keyAt(i);
                        break;
                    }
                }
                if (streamId == -1) {
                    throw new IllegalArgumentException("Deferred config is not part of this "
                            + "session");
                }

                if (config.getSurfaces().size() == 0) {
                    throw new IllegalArgumentException("The final config for stream " + streamId
                            + " must have at least 1 surface");
                }
                mRemoteDevice.finalizeOutputConfigurations(streamId, config);
                mConfiguredOutputs.put(streamId, config);
            }
        }
    }

    public int capture(CaptureRequest request, CaptureCallback callback, Executor executor)
            throws CameraAccessException {
        if (DEBUG) {
            Log.d(TAG, "calling capture");
        }
        List<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitCaptureRequest(requestList, callback, executor, /*streaming*/false);
    }

    public int captureBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Executor executor) throws CameraAccessException {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one request must be given");
        }
        return submitCaptureRequest(requests, callback, executor, /*streaming*/false);
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
     * @param lastFrameNumber last frame number returned from binder.
     * @param repeatingRequestTypes the repeating requests' types.
     */
    private void checkEarlyTriggerSequenceComplete(
            final int requestId, final long lastFrameNumber,
            final int[] repeatingRequestTypes) {
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
                final long ident = Binder.clearCallingIdentity();
                try {
                    holder.getExecutor().execute(resultDispatch);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                Log.w(TAG, String.format(
                        "did not register callback to request %d",
                        requestId));
            }
        } else {
            // This function is only called for regular/ZslStill request so lastFrameNumber is the
            // last regular/ZslStill frame number.
            mRequestLastFrameNumbersList.add(new RequestLastFrameNumbersHolder(requestId,
                    lastFrameNumber, repeatingRequestTypes));

            // It is possible that the last frame has already arrived, so we need to check
            // for sequence completion right away
            checkAndFireSequenceComplete();
        }
    }

    private int[] getRequestTypes(final CaptureRequest[] requestArray) {
        int[] requestTypes = new int[requestArray.length];
        for (int i = 0; i < requestArray.length; i++) {
            requestTypes[i] = requestArray[i].getRequestType();
        }
        return requestTypes;
    }

    private int submitCaptureRequest(List<CaptureRequest> requestList, CaptureCallback callback,
            Executor executor, boolean repeating) throws CameraAccessException {

        // Need a valid executor, or current thread needs to have a looper, if
        // callback is valid
        executor = checkExecutor(executor, callback);

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            // Make sure that there all requests have at least 1 surface; all surfaces are non-null;
            // the surface isn't a physical stream surface for reprocessing request
            for (CaptureRequest request : requestList) {
                if (request.getTargets().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Each request must have at least one Surface target");
                }

                for (Surface surface : request.getTargets()) {
                    if (surface == null) {
                        throw new IllegalArgumentException("Null Surface targets are not allowed");
                    }

                    for (int i = 0; i < mConfiguredOutputs.size(); i++) {
                        OutputConfiguration configuration = mConfiguredOutputs.valueAt(i);
                        if (configuration.isForPhysicalCamera()
                                && configuration.getSurfaces().contains(surface)) {
                            if (request.isReprocess()) {
                                throw new IllegalArgumentException(
                                        "Reprocess request on physical stream is not allowed");
                            }
                        }
                    }
                }
            }

            if (repeating) {
                stopRepeating();
            }

            SubmitInfo requestInfo;

            CaptureRequest[] requestArray = requestList.toArray(new CaptureRequest[requestList.size()]);
            // Convert Surface to streamIdx and surfaceIdx
            for (CaptureRequest request : requestArray) {
                request.convertSurfaceToStreamId(mConfiguredOutputs);
            }

            requestInfo = mRemoteDevice.submitRequestList(requestArray, repeating);
            if (DEBUG) {
                Log.v(TAG, "last frame number " + requestInfo.getLastFrameNumber());
            }

            for (CaptureRequest request : requestArray) {
                request.recoverStreamIdToSurface();
            }

            if (callback != null) {
                mCaptureCallbackMap.put(requestInfo.getRequestId(),
                        new CaptureCallbackHolder(
                            callback, requestList, executor, repeating, mNextSessionId - 1));
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Listen for request " + requestInfo.getRequestId() + " is null");
                }
            }

            if (repeating) {
                if (mRepeatingRequestId != REQUEST_ID_NONE) {
                    checkEarlyTriggerSequenceComplete(mRepeatingRequestId,
                            requestInfo.getLastFrameNumber(),
                            mRepeatingRequestTypes);
                }
                mRepeatingRequestId = requestInfo.getRequestId();
                mRepeatingRequestTypes = getRequestTypes(requestArray);
            } else {
                mRequestLastFrameNumbersList.add(
                    new RequestLastFrameNumbersHolder(requestList, requestInfo));
            }

            if (mIdle) {
                mDeviceExecutor.execute(mCallOnActive);
            }
            mIdle = false;

            return requestInfo.getRequestId();
        }
    }

    public int setRepeatingRequest(CaptureRequest request, CaptureCallback callback,
            Executor executor) throws CameraAccessException {
        List<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitCaptureRequest(requestList, callback, executor, /*streaming*/true);
    }

    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Executor executor) throws CameraAccessException {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one request must be given");
        }
        return submitCaptureRequest(requests, callback, executor, /*streaming*/true);
    }

    public void stopRepeating() throws CameraAccessException {

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();
            if (mRepeatingRequestId != REQUEST_ID_NONE) {

                int requestId = mRepeatingRequestId;
                mRepeatingRequestId = REQUEST_ID_NONE;
                int[] requestTypes = mRepeatingRequestTypes;
                mRepeatingRequestTypes = null;

                long lastFrameNumber;
                try {
                    lastFrameNumber = mRemoteDevice.cancelRequest(requestId);
                } catch (IllegalArgumentException e) {
                    if (DEBUG) {
                        Log.v(TAG, "Repeating request was already stopped for request " + requestId);
                    }
                    // Repeating request was already stopped. Nothing more to do.
                    return;
                }

                checkEarlyTriggerSequenceComplete(requestId, lastFrameNumber, requestTypes);
            }
        }
    }

    private void waitUntilIdle() throws CameraAccessException {

        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            if (mRepeatingRequestId != REQUEST_ID_NONE) {
                throw new IllegalStateException("Active repeating request ongoing");
            }

            mRemoteDevice.waitUntilIdle();
        }
    }

    public void flush() throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();

            mDeviceExecutor.execute(mCallOnBusy);

            // If already idle, just do a busy->idle transition immediately, don't actually
            // flush.
            if (mIdle) {
                mDeviceExecutor.execute(mCallOnIdle);
                return;
            }

            long lastFrameNumber = mRemoteDevice.flush();
            if (mRepeatingRequestId != REQUEST_ID_NONE) {
                checkEarlyTriggerSequenceComplete(mRepeatingRequestId, lastFrameNumber,
                        mRepeatingRequestTypes);
                mRepeatingRequestId = REQUEST_ID_NONE;
                mRepeatingRequestTypes = null;
            }
        }
    }

    @Override
    public void close() {
        synchronized (mInterfaceLock) {
            if (mClosing.getAndSet(true)) {
                return;
            }

            if (mRemoteDevice != null) {
                mRemoteDevice.disconnect();
                mRemoteDevice.unlinkToDeath(this, /*flags*/0);
            }

            // Only want to fire the onClosed callback once;
            // either a normal close where the remote device is valid
            // or a close after a startup error (no remote device but in error state)
            if (mRemoteDevice != null || mInError) {
                mDeviceExecutor.execute(mCallOnClosed);
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
     * An interface instead of an abstract class because this is internal and
     * we want to make sure we always implement all its callbacks until we reach
     * the public layer.
     */
    public interface CaptureCallback {

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
                CaptureRequest request, long timestamp, long frameNumber);

        /**
         * This method is called when some results from an image capture are
         * available.
         *
         * @hide
         */
        public void onCapturePartial(CameraDevice camera,
                CaptureRequest request, CaptureResult result);

        /**
         * This method is called when an image capture makes partial forward progress; some
         * (but not all) results from an image capture are available.
         *
         */
        public void onCaptureProgressed(CameraDevice camera,
                CaptureRequest request, CaptureResult partialResult);

        /**
         * This method is called when an image capture has fully completed and all the
         * result metadata is available.
         */
        public void onCaptureCompleted(CameraDevice camera,
                CaptureRequest request, TotalCaptureResult result);

        /**
         * This method is called instead of {@link #onCaptureCompleted} when the
         * camera device failed to produce a {@link CaptureResult} for the
         * request.
         */
        public void onCaptureFailed(CameraDevice camera,
                CaptureRequest request, CaptureFailure failure);

        /**
         * This method is called independently of the others in CaptureCallback,
         * when a capture sequence finishes and all {@link CaptureResult}
         * or {@link CaptureFailure} for it have been returned via this callback.
         */
        public void onCaptureSequenceCompleted(CameraDevice camera,
                int sequenceId, long frameNumber);

        /**
         * This method is called independently of the others in CaptureCallback,
         * when a capture sequence aborts before any {@link CaptureResult}
         * or {@link CaptureFailure} for it have been returned via this callback.
         */
        public void onCaptureSequenceAborted(CameraDevice camera,
                int sequenceId);

        /**
         * This method is called independently of the others in CaptureCallback, if an output buffer
         * is dropped for a particular capture request.
         *
         * Loss of metadata is communicated via onCaptureFailed, independently of any buffer loss.
         */
        public void onCaptureBufferLost(CameraDevice camera,
                CaptureRequest request, Surface target, long frameNumber);
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
         * This method is called when camera device's non-repeating request queue is empty,
         * and is ready to start capturing next image.
         */
        public void onRequestQueueEmpty() {
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
        private final Executor mExecutor;
        private final int mSessionId;
        /**
         * <p>Determine if the callback holder is for a constrained high speed request list that
         * expects batched capture results. Capture results will be batched if the request list
         * is interleaved with preview and video requests. Capture results won't be batched if the
         * request list only contains preview requests, or if the request doesn't belong to a
         * constrained high speed list.
         */
        private final boolean mHasBatchedOutputs;

        CaptureCallbackHolder(CaptureCallback callback, List<CaptureRequest> requestList,
                Executor executor, boolean repeating, int sessionId) {
            if (callback == null || executor == null) {
                throw new UnsupportedOperationException(
                    "Must have a valid handler and a valid callback");
            }
            mRepeating = repeating;
            mExecutor = executor;
            mRequestList = new ArrayList<CaptureRequest>(requestList);
            mCallback = callback;
            mSessionId = sessionId;

            // Check whether this callback holder is for batched outputs.
            // The logic here should match createHighSpeedRequestList.
            boolean hasBatchedOutputs = true;
            for (int i = 0; i < requestList.size(); i++) {
                CaptureRequest request = requestList.get(i);
                if (!request.isPartOfCRequestList()) {
                    hasBatchedOutputs = false;
                    break;
                }
                if (i == 0) {
                    Collection<Surface> targets = request.getTargets();
                    if (targets.size() != 2) {
                        hasBatchedOutputs = false;
                        break;
                    }
                }
            }
            mHasBatchedOutputs = hasBatchedOutputs;
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

        public Executor getExecutor() {
            return mExecutor;
        }

        public int getSessionId() {
            return mSessionId;
        }

        public int getRequestCount() {
            return mRequestList.size();
        }

        public boolean hasBatchedOutputs() {
            return mHasBatchedOutputs;
        }
    }

    /**
     * This class holds a capture ID and its expected last regular, zslStill, and reprocess
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
        // The last ZSL still capture frame number for this request ID. It's
        // CaptureCallback.NO_FRAMES_CAPTURED if the request ID has no zsl request.
        private final long mLastZslStillFrameNumber;

        /**
         * Create a request-last-frame-numbers holder with a list of requests, request ID, and
         * the last frame number returned by camera service.
         */
        public RequestLastFrameNumbersHolder(List<CaptureRequest> requestList, SubmitInfo requestInfo) {
            long lastRegularFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;
            long lastReprocessFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;
            long lastZslStillFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;
            long frameNumber = requestInfo.getLastFrameNumber();

            if (requestInfo.getLastFrameNumber() < requestList.size() - 1) {
                throw new IllegalArgumentException(
                        "lastFrameNumber: " + requestInfo.getLastFrameNumber() +
                        " should be at least " + (requestList.size() - 1) + " for the number of " +
                        " requests in the list: " + requestList.size());
            }

            // find the last regular, zslStill, and reprocess frame number
            for (int i = requestList.size() - 1; i >= 0; i--) {
                CaptureRequest request = requestList.get(i);
                int requestType = request.getRequestType();
                if (requestType == CaptureRequest.REQUEST_TYPE_REPROCESS
                        && lastReprocessFrameNumber == CaptureCallback.NO_FRAMES_CAPTURED) {
                    lastReprocessFrameNumber = frameNumber;
                } else if (requestType == CaptureRequest.REQUEST_TYPE_ZSL_STILL
                        && lastZslStillFrameNumber == CaptureCallback.NO_FRAMES_CAPTURED) {
                    lastZslStillFrameNumber = frameNumber;
                } else if (requestType == CaptureRequest.REQUEST_TYPE_REGULAR
                        && lastRegularFrameNumber == CaptureCallback.NO_FRAMES_CAPTURED) {
                    lastRegularFrameNumber = frameNumber;
                }

                if (lastReprocessFrameNumber != CaptureCallback.NO_FRAMES_CAPTURED
                        && lastZslStillFrameNumber != CaptureCallback.NO_FRAMES_CAPTURED
                        && lastRegularFrameNumber != CaptureCallback.NO_FRAMES_CAPTURED) {
                    break;
                }

                frameNumber--;
            }

            mLastRegularFrameNumber = lastRegularFrameNumber;
            mLastReprocessFrameNumber = lastReprocessFrameNumber;
            mLastZslStillFrameNumber = lastZslStillFrameNumber;
            mRequestId = requestInfo.getRequestId();
        }

        /**
         * Create a request-last-frame-numbers holder with a request ID and last regular/ZslStill
         * frame number.
         */
        RequestLastFrameNumbersHolder(int requestId, long lastFrameNumber,
                int[] repeatingRequestTypes) {
            long lastRegularFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;
            long lastZslStillFrameNumber = CaptureCallback.NO_FRAMES_CAPTURED;

            if (repeatingRequestTypes == null) {
                throw new IllegalArgumentException(
                        "repeatingRequest list must not be null");
            }
            if (lastFrameNumber < repeatingRequestTypes.length - 1) {
                throw new IllegalArgumentException(
                        "lastFrameNumber: " + lastFrameNumber + " should be at least "
                        + (repeatingRequestTypes.length - 1)
                        + " for the number of requests in the list: "
                        + repeatingRequestTypes.length);
            }

            long frameNumber = lastFrameNumber;
            for (int i = repeatingRequestTypes.length - 1; i >= 0; i--) {
                if (repeatingRequestTypes[i] == CaptureRequest.REQUEST_TYPE_ZSL_STILL
                        && lastZslStillFrameNumber == CaptureCallback.NO_FRAMES_CAPTURED) {
                    lastZslStillFrameNumber = frameNumber;
                } else if (repeatingRequestTypes[i] == CaptureRequest.REQUEST_TYPE_REGULAR
                        && lastRegularFrameNumber == CaptureCallback.NO_FRAMES_CAPTURED) {
                    lastRegularFrameNumber = frameNumber;
                }

                if (lastZslStillFrameNumber != CaptureCallback.NO_FRAMES_CAPTURED
                        && lastRegularFrameNumber != CaptureCallback.NO_FRAMES_CAPTURED) {
                    break;
                }

                frameNumber--;
            }

            mLastRegularFrameNumber = lastRegularFrameNumber;
            mLastZslStillFrameNumber = lastZslStillFrameNumber;
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
         * Return the last ZslStill frame number. Return CaptureCallback.NO_FRAMES_CAPTURED if
         * it contains no Zsl request.
         */
        public long getLastZslStillFrameNumber() {
            return mLastZslStillFrameNumber;
        }

        /**
         * Return the last frame number overall.
         */
        public long getLastFrameNumber() {
            return Math.max(mLastZslStillFrameNumber,
                    Math.max(mLastRegularFrameNumber, mLastReprocessFrameNumber));
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

        /** the completed frame number for each type of capture results */
        private long[] mCompletedFrameNumber = new long[CaptureRequest.REQUEST_TYPE_COUNT];

        /** the skipped frame numbers that don't belong to each type of capture results */
        private final LinkedList<Long>[] mSkippedOtherFrameNumbers =
                new LinkedList[CaptureRequest.REQUEST_TYPE_COUNT];

        /** the skipped frame numbers that belong to each type of capture results */
        private final LinkedList<Long>[] mSkippedFrameNumbers =
                new LinkedList[CaptureRequest.REQUEST_TYPE_COUNT];

        /** frame number -> request type */
        private final TreeMap<Long, Integer> mFutureErrorMap = new TreeMap<Long, Integer>();
        /** Map frame numbers to list of partial results */
        private final HashMap<Long, List<CaptureResult>> mPartialResults = new HashMap<>();

        public FrameNumberTracker() {
            for (int i = 0; i < CaptureRequest.REQUEST_TYPE_COUNT; i++) {
                mCompletedFrameNumber[i] = CaptureCallback.NO_FRAMES_CAPTURED;
                mSkippedOtherFrameNumbers[i] = new LinkedList<Long>();
                mSkippedFrameNumbers[i] = new LinkedList<Long>();
            }
        }

        private void update() {
            Iterator iter = mFutureErrorMap.entrySet().iterator();
            while (iter.hasNext()) {
                TreeMap.Entry pair = (TreeMap.Entry)iter.next();
                Long errorFrameNumber = (Long)pair.getKey();
                int requestType = (int) pair.getValue();
                Boolean removeError = false;
                if (errorFrameNumber == mCompletedFrameNumber[requestType] + 1) {
                    mCompletedFrameNumber[requestType] = errorFrameNumber;
                    removeError = true;
                } else {
                    if (!mSkippedFrameNumbers[requestType].isEmpty()) {
                        if (errorFrameNumber == mSkippedFrameNumbers[requestType].element()) {
                            mCompletedFrameNumber[requestType] = errorFrameNumber;
                            mSkippedFrameNumbers[requestType].remove();
                            removeError = true;
                        }
                    } else {
                        for (int i = 1; i < CaptureRequest.REQUEST_TYPE_COUNT; i++) {
                            int otherType = (requestType + i) % CaptureRequest.REQUEST_TYPE_COUNT;
                            if (!mSkippedOtherFrameNumbers[otherType].isEmpty() && errorFrameNumber
                                    == mSkippedOtherFrameNumbers[otherType].element()) {
                                mCompletedFrameNumber[requestType] = errorFrameNumber;
                                mSkippedOtherFrameNumbers[otherType].remove();
                                removeError = true;
                                break;
                            }
                        }
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
         * @param requestType the type of capture request: Reprocess, ZslStill, or Regular.
         */
        public void updateTracker(long frameNumber, boolean isError, int requestType) {
            if (isError) {
                mFutureErrorMap.put(frameNumber, requestType);
            } else {
                try {
                    updateCompletedFrameNumber(frameNumber, requestType);
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
         * @param requestType the type of capture request: Reprocess, ZslStill, or Regular.
         */
        public void updateTracker(long frameNumber, CaptureResult result, boolean partial,
                int requestType) {
            if (!partial) {
                // Update the total result's frame status as being successful
                updateTracker(frameNumber, /*isError*/false, requestType);
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
            return mCompletedFrameNumber[CaptureRequest.REQUEST_TYPE_REGULAR];
        }

        public long getCompletedReprocessFrameNumber() {
            return mCompletedFrameNumber[CaptureRequest.REQUEST_TYPE_REPROCESS];
        }

        public long getCompletedZslStillFrameNumber() {
            return mCompletedFrameNumber[CaptureRequest.REQUEST_TYPE_ZSL_STILL];
        }

        /**
         * Update the completed frame number for results of 3 categories
         * (Regular/Reprocess/ZslStill).
         *
         * It validates that all previous frames of the same category have arrived.
         *
         * If there is a gap since previous frame number of the same category, assume the frames in
         * the gap are other categories and store them in the skipped frame number queue to check
         * against when frames of those categories arrive.
         */
        private void updateCompletedFrameNumber(long frameNumber,
                int requestType) throws IllegalArgumentException {
            if (frameNumber <= mCompletedFrameNumber[requestType]) {
                throw new IllegalArgumentException("frame number " + frameNumber + " is a repeat");
            }

            // Assume there are only 3 different types of capture requests.
            int otherType1 = (requestType + 1) % CaptureRequest.REQUEST_TYPE_COUNT;
            int otherType2 = (requestType + 2) % CaptureRequest.REQUEST_TYPE_COUNT;
            long maxOtherFrameNumberSeen =
                    Math.max(mCompletedFrameNumber[otherType1], mCompletedFrameNumber[otherType2]);
            if (frameNumber < maxOtherFrameNumberSeen) {
                // if frame number is smaller than completed frame numbers of other categories,
                // it must be:
                // - the head of mSkippedFrameNumbers for this category, or
                // - in one of other mSkippedOtherFrameNumbers
                if (!mSkippedFrameNumbers[requestType].isEmpty()) {
                    // frame number must be head of current type of mSkippedFrameNumbers if
                    // mSkippedFrameNumbers isn't empty.
                    if (frameNumber < mSkippedFrameNumbers[requestType].element()) {
                        throw new IllegalArgumentException("frame number " + frameNumber
                                + " is a repeat");
                    } else if (frameNumber > mSkippedFrameNumbers[requestType].element()) {
                        throw new IllegalArgumentException("frame number " + frameNumber
                                + " comes out of order. Expecting "
                                + mSkippedFrameNumbers[requestType].element());
                    }
                    // frame number matches the head of the skipped frame number queue.
                    mSkippedFrameNumbers[requestType].remove();
                } else {
                    // frame number must be in one of the other mSkippedOtherFrameNumbers.
                    int index1 = mSkippedOtherFrameNumbers[otherType1].indexOf(frameNumber);
                    int index2 = mSkippedOtherFrameNumbers[otherType2].indexOf(frameNumber);
                    boolean inSkippedOther1 = index1 != -1;
                    boolean inSkippedOther2 = index2 != -1;
                    if (!(inSkippedOther1 ^ inSkippedOther2)) {
                        throw new IllegalArgumentException("frame number " + frameNumber
                                + " is a repeat or invalid");
                    }

                    // We know the category of frame numbers in skippedOtherFrameNumbers leading up
                    // to the current frame number. Move them into the correct skippedFrameNumbers.
                    LinkedList<Long> srcList, dstList;
                    int index;
                    if (inSkippedOther1) {
                        srcList = mSkippedOtherFrameNumbers[otherType1];
                        dstList = mSkippedFrameNumbers[otherType2];
                        index = index1;
                    } else {
                        srcList = mSkippedOtherFrameNumbers[otherType2];
                        dstList = mSkippedFrameNumbers[otherType1];
                        index = index2;
                    }
                    for (int i = 0; i < index; i++) {
                        dstList.add(srcList.removeFirst());
                    }

                    // Remove current frame number from skippedOtherFrameNumbers
                    srcList.remove();
                }
            } else {
                // there is a gap of unseen frame numbers which should belong to the other
                // 2 categories. Put all the skipped frame numbers in the queue.
                for (long i =
                        Math.max(maxOtherFrameNumberSeen, mCompletedFrameNumber[requestType]) + 1;
                        i < frameNumber; i++) {
                    mSkippedOtherFrameNumbers[requestType].add(i);
                }
            }

            mCompletedFrameNumber[requestType] = frameNumber;
        }
    }

    private void checkAndFireSequenceComplete() {
        long completedFrameNumber = mFrameNumberTracker.getCompletedFrameNumber();
        long completedReprocessFrameNumber = mFrameNumberTracker.getCompletedReprocessFrameNumber();
        long completedZslStillFrameNumber = mFrameNumberTracker.getCompletedZslStillFrameNumber();
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
                    long lastZslStillFrameNumber =
                            requestLastFrameNumbers.getLastZslStillFrameNumber();
                    // check if it's okay to remove request from mCaptureCallbackMap
                    if (lastRegularFrameNumber <= completedFrameNumber
                            && lastReprocessFrameNumber <= completedReprocessFrameNumber
                            && lastZslStillFrameNumber <= completedZslStillFrameNumber) {
                        sequenceCompleted = true;
                        mCaptureCallbackMap.removeAt(index);
                        if (DEBUG) {
                            Log.v(TAG, String.format(
                                    "Remove holder for requestId %d, because lastRegularFrame %d "
                                    + "is <= %d, lastReprocessFrame %d is <= %d, "
                                    + "lastZslStillFrame %d is <= %d", requestId,
                                    lastRegularFrameNumber, completedFrameNumber,
                                    lastReprocessFrameNumber, completedReprocessFrameNumber,
                                    lastZslStillFrameNumber, completedZslStillFrameNumber));
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
                final long ident = Binder.clearCallingIdentity();
                try {
                    holder.getExecutor().execute(resultDispatch);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    public class CameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {

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
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            CameraDeviceImpl.this.mDeviceExecutor.execute(mCallOnDisconnected);
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                        break;
                    case ERROR_CAMERA_REQUEST:
                    case ERROR_CAMERA_RESULT:
                    case ERROR_CAMERA_BUFFER:
                        onCaptureErrorLocked(errorCode, resultExtras);
                        break;
                    case ERROR_CAMERA_DEVICE:
                        scheduleNotifyError(StateCallback.ERROR_CAMERA_DEVICE);
                        break;
                    case ERROR_CAMERA_DISABLED:
                        scheduleNotifyError(StateCallback.ERROR_CAMERA_DISABLED);
                        break;
                    default:
                        Log.e(TAG, "Unknown error from camera device: " + errorCode);
                        scheduleNotifyError(StateCallback.ERROR_CAMERA_SERVICE);
                }
            }
        }

        private void scheduleNotifyError(int code) {
            mInError = true;
            final long ident = Binder.clearCallingIdentity();
            try {
                CameraDeviceImpl.this.mDeviceExecutor.execute(obtainRunnable(
                            CameraDeviceCallbacks::notifyError, this, code).recycleOnUse());
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        private void notifyError(int code) {
            if (!CameraDeviceImpl.this.isClosed()) {
                mDeviceCallback.onError(CameraDeviceImpl.this, code);
            }
        }

        @Override
        public void onRepeatingRequestError(long lastFrameNumber, int repeatingRequestId) {
            if (DEBUG) {
                Log.d(TAG, "Repeating request error received. Last frame number is " +
                        lastFrameNumber);
            }

            synchronized(mInterfaceLock) {
                // Camera is already closed or no repeating request is present.
                if (mRemoteDevice == null || mRepeatingRequestId == REQUEST_ID_NONE) {
                    return; // Camera already closed
                }

                checkEarlyTriggerSequenceComplete(mRepeatingRequestId, lastFrameNumber,
                        mRepeatingRequestTypes);
                // Check if there is already a new repeating request
                if (mRepeatingRequestId == repeatingRequestId) {
                    mRepeatingRequestId = REQUEST_ID_NONE;
                    mRepeatingRequestTypes = null;
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
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        CameraDeviceImpl.this.mDeviceExecutor.execute(mCallOnIdle);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
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
                final long ident = Binder.clearCallingIdentity();
                try {
                    holder.getExecutor().execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (!CameraDeviceImpl.this.isClosed()) {
                                    final int subsequenceId = resultExtras.getSubsequenceId();
                                    final CaptureRequest request = holder.getRequest(subsequenceId);

                                    if (holder.hasBatchedOutputs()) {
                                        // Send derived onCaptureStarted for requests within the
                                        // batch
                                        final Range<Integer> fpsRange =
                                            request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
                                        for (int i = 0; i < holder.getRequestCount(); i++) {
                                            holder.getCallback().onCaptureStarted(
                                                CameraDeviceImpl.this,
                                                holder.getRequest(i),
                                                timestamp - (subsequenceId - i) *
                                                NANO_PER_SECOND/fpsRange.getUpper(),
                                                frameNumber - (subsequenceId - i));
                                        }
                                    } else {
                                        holder.getCallback().onCaptureStarted(
                                            CameraDeviceImpl.this,
                                            holder.getRequest(resultExtras.getSubsequenceId()),
                                            timestamp, frameNumber);
                                    }
                                }
                            }
                        });
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void onResultReceived(CameraMetadataNative result,
                CaptureResultExtras resultExtras, PhysicalCaptureResultInfo physicalResults[])
                throws RemoteException {

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
                int requestType = request.getRequestType();

                // Check if we have a callback for this
                if (holder == null) {
                    if (DEBUG) {
                        Log.d(TAG,
                                "holder is null, early return at frame "
                                        + frameNumber);
                    }

                    mFrameNumberTracker.updateTracker(frameNumber, /*result*/null, isPartialResult,
                            requestType);

                    return;
                }

                if (isClosed()) {
                    if (DEBUG) {
                        Log.d(TAG,
                                "camera is closed, early return at frame "
                                        + frameNumber);
                    }

                    mFrameNumberTracker.updateTracker(frameNumber, /*result*/null, isPartialResult,
                            requestType);
                    return;
                }


                Runnable resultDispatch = null;

                CaptureResult finalResult;
                // Make a copy of the native metadata before it gets moved to a CaptureResult
                // object.
                final CameraMetadataNative resultCopy;
                if (holder.hasBatchedOutputs()) {
                    resultCopy = new CameraMetadataNative(result);
                } else {
                    resultCopy = null;
                }

                // Either send a partial result or the final capture completed result
                if (isPartialResult) {
                    final CaptureResult resultAsCapture =
                            new CaptureResult(result, request, resultExtras);
                    // Partial result
                    resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()) {
                                if (holder.hasBatchedOutputs()) {
                                    // Send derived onCaptureProgressed for requests within
                                    // the batch.
                                    for (int i = 0; i < holder.getRequestCount(); i++) {
                                        CameraMetadataNative resultLocal =
                                                new CameraMetadataNative(resultCopy);
                                        CaptureResult resultInBatch = new CaptureResult(
                                                resultLocal, holder.getRequest(i), resultExtras);

                                        holder.getCallback().onCaptureProgressed(
                                            CameraDeviceImpl.this,
                                            holder.getRequest(i),
                                            resultInBatch);
                                    }
                                } else {
                                    holder.getCallback().onCaptureProgressed(
                                        CameraDeviceImpl.this,
                                        request,
                                        resultAsCapture);
                                }
                            }
                        }
                    };
                    finalResult = resultAsCapture;
                } else {
                    List<CaptureResult> partialResults =
                            mFrameNumberTracker.popPartialResults(frameNumber);

                    final long sensorTimestamp =
                            result.get(CaptureResult.SENSOR_TIMESTAMP);
                    final Range<Integer> fpsRange =
                            request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
                    final int subsequenceId = resultExtras.getSubsequenceId();
                    final TotalCaptureResult resultAsCapture = new TotalCaptureResult(result,
                            request, resultExtras, partialResults, holder.getSessionId(),
                            physicalResults);
                    // Final capture result
                    resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()){
                                if (holder.hasBatchedOutputs()) {
                                    // Send derived onCaptureCompleted for requests within
                                    // the batch.
                                    for (int i = 0; i < holder.getRequestCount(); i++) {
                                        resultCopy.set(CaptureResult.SENSOR_TIMESTAMP,
                                                sensorTimestamp - (subsequenceId - i) *
                                                NANO_PER_SECOND/fpsRange.getUpper());
                                        CameraMetadataNative resultLocal =
                                                new CameraMetadataNative(resultCopy);
                                        // No logical multi-camera support for batched output mode.
                                        TotalCaptureResult resultInBatch = new TotalCaptureResult(
                                            resultLocal, holder.getRequest(i), resultExtras,
                                            partialResults, holder.getSessionId(),
                                            new PhysicalCaptureResultInfo[0]);

                                        holder.getCallback().onCaptureCompleted(
                                            CameraDeviceImpl.this,
                                            holder.getRequest(i),
                                            resultInBatch);
                                    }
                                } else {
                                    holder.getCallback().onCaptureCompleted(
                                        CameraDeviceImpl.this,
                                        request,
                                        resultAsCapture);
                                }
                            }
                        }
                    };
                    finalResult = resultAsCapture;
                }

                final long ident = Binder.clearCallingIdentity();
                try {
                    holder.getExecutor().execute(resultDispatch);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }

                // Collect the partials for a total result; or mark the frame as totally completed
                mFrameNumberTracker.updateTracker(frameNumber, finalResult, isPartialResult,
                        requestType);

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
            final List<Surface> surfaces = output.getSurfaces();
            for (Surface surface : surfaces) {
                sessionCallback.onSurfacePrepared(surface);
            }
        }

        @Override
        public void onRequestQueueEmpty() {
            final StateCallbackKK sessionCallback;

            if (DEBUG) {
                Log.v(TAG, "Request queue becomes empty");
            }

            synchronized(mInterfaceLock) {
                sessionCallback = mSessionStateCallback;
            }

            if (sessionCallback == null) return;

            sessionCallback.onRequestQueueEmpty();
        }

        /**
         * Called by onDeviceError for handling single-capture failures.
         */
        private void onCaptureErrorLocked(int errorCode, CaptureResultExtras resultExtras) {

            final int requestId = resultExtras.getRequestId();
            final int subsequenceId = resultExtras.getSubsequenceId();
            final long frameNumber = resultExtras.getFrameNumber();
            final String errorPhysicalCameraId = resultExtras.getErrorPhysicalCameraId();
            final CaptureCallbackHolder holder =
                    CameraDeviceImpl.this.mCaptureCallbackMap.get(requestId);

            if (holder == null) {
                Log.e(TAG, String.format("Receive capture error on unknown request ID %d",
                        requestId));
                return;
            }

            final CaptureRequest request = holder.getRequest(subsequenceId);

            Runnable failureDispatch = null;
            if (errorCode == ERROR_CAMERA_BUFFER) {
                // Because 1 stream id could map to multiple surfaces, we need to specify both
                // streamId and surfaceId.
                OutputConfiguration config = mConfiguredOutputs.get(
                        resultExtras.getErrorStreamId());
                if (config == null) {
                    Log.v(TAG, String.format(
                            "Stream %d has been removed. Skipping buffer lost callback",
                            resultExtras.getErrorStreamId()));
                    return;
                }
                for (Surface surface : config.getSurfaces()) {
                    if (!request.containsTarget(surface)) {
                        continue;
                    }
                    if (DEBUG) {
                        Log.v(TAG, String.format(
                                "Lost output buffer reported for frame %d, target %s",
                                frameNumber, surface));
                    }
                    failureDispatch = new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDeviceImpl.this.isClosed()){
                                holder.getCallback().onCaptureBufferLost(
                                    CameraDeviceImpl.this,
                                    request,
                                    surface,
                                    frameNumber);
                            }
                        }
                    };
                    // Dispatch the failure callback
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        holder.getExecutor().execute(failureDispatch);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            } else {
                boolean mayHaveBuffers = (errorCode == ERROR_CAMERA_RESULT);

                // This is only approximate - exact handling needs the camera service and HAL to
                // disambiguate between request failures to due abort and due to real errors.  For
                // now, assume that if the session believes we're mid-abort, then the error is due
                // to abort.
                int reason = (mCurrentSession != null && mCurrentSession.isAborting()) ?
                        CaptureFailure.REASON_FLUSHED :
                        CaptureFailure.REASON_ERROR;

                final CaptureFailure failure = new CaptureFailure(
                    request,
                    reason,
                    /*dropped*/ mayHaveBuffers,
                    requestId,
                    frameNumber,
                    errorPhysicalCameraId);

                failureDispatch = new Runnable() {
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

                // Fire onCaptureSequenceCompleted if appropriate
                if (DEBUG) {
                    Log.v(TAG, String.format("got error frame %d", frameNumber));
                }
                mFrameNumberTracker.updateTracker(frameNumber,
                        /*error*/true, request.getRequestType());
                checkAndFireSequenceComplete();

                // Dispatch the failure callback
                final long ident = Binder.clearCallingIdentity();
                try {
                    holder.getExecutor().execute(failureDispatch);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

        }

    } // public class CameraDeviceCallbacks

    /**
     * A camera specific adapter {@link Executor} that posts all executed tasks onto the given
     * {@link Handler}.
     *
     * @hide
     */
    private static class CameraHandlerExecutor implements Executor {
        private final Handler mHandler;

        public CameraHandlerExecutor(@NonNull Handler handler) {
            mHandler = Preconditions.checkNotNull(handler);
        }

        @Override
        public void execute(Runnable command) {
            // Return value of 'post()' will be ignored in order to keep the
            // same camera behavior. For further details see b/74605221 .
            mHandler.post(command);
        }
    }

    /**
     * Default executor management.
     *
     * <p>
     * If executor is null, get the current thread's
     * Looper to create a Executor with. If no looper exists, throw
     * {@code IllegalArgumentException}.
     * </p>
     */
    static Executor checkExecutor(Executor executor) {
        return (executor == null) ? checkAndWrapHandler(null) : executor;
    }

    /**
     * Default executor management.
     *
     * <p>If the callback isn't null, check the executor, otherwise pass it through.</p>
     */
    public static <T> Executor checkExecutor(Executor executor, T callback) {
        return (callback != null) ? checkExecutor(executor) : executor;
    }

    /**
     * Wrap Handler in Executor.
     *
     * <p>
     * If handler is null, get the current thread's
     * Looper to create a Executor with. If no looper exists, throw
     * {@code IllegalArgumentException}.
     * </p>
     */
    public static Executor checkAndWrapHandler(Handler handler) {
        return new CameraHandlerExecutor(checkHandler(handler));
    }

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

    /**
     * Listener for binder death.
     *
     * <p> Handle binder death for ICameraDeviceUser. Trigger onError.</p>
     */
    @Override
    public void binderDied() {
        Log.w(TAG, "CameraDevice " + mCameraId + " died unexpectedly");

        if (mRemoteDevice == null) {
            return; // Camera already closed
        }

        mInError = true;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!isClosed()) {
                    mDeviceCallback.onError(CameraDeviceImpl.this,
                            StateCallback.ERROR_CAMERA_SERVICE);
                }
            }
        };
        final long ident = Binder.clearCallingIdentity();
        try {
            CameraDeviceImpl.this.mDeviceExecutor.execute(r);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setCameraAudioRestriction(
            @CAMERA_AUDIO_RESTRICTION int mode) throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();
            mRemoteDevice.setCameraAudioRestriction(mode);
        }
    }

    @Override
    public @CAMERA_AUDIO_RESTRICTION int getCameraAudioRestriction() throws CameraAccessException {
        synchronized(mInterfaceLock) {
            checkIfCameraClosedOrInError();
            return mRemoteDevice.getGlobalAudioRestriction();
        }
    }
}
