/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraOfflineSession;
import android.hardware.camera2.CameraOfflineSession.CameraOfflineSessionCallback;
import android.hardware.camera2.CameraSharedCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.os.ConditionVariable;
import android.os.Handler;
import android.view.Surface;

import com.android.internal.camera.flags.Flags;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Standard implementation of CameraSharedCaptureSession.
 *
 * <p>
 * Mostly just forwards calls to an instance of CameraCaptureSessionImpl,
 * but implements the few necessary behavior changes and additional methods required
 * for the shared session mode.
 * </p>
 */
@FlaggedApi(Flags.FLAG_CAMERA_MULTI_CLIENT)
public class CameraSharedCaptureSessionImpl
        extends CameraSharedCaptureSession implements CameraCaptureSessionCore {
    private static final String TAG = "CameraSharedCaptureSessionImpl";
    private final CameraCaptureSessionImpl mSessionImpl;
    private final ConditionVariable mInitialized = new ConditionVariable();
    private final android.hardware.camera2.impl.CameraDeviceImpl mCameraDevice;
    private final Executor mDeviceExecutor;  

    /**
     * Create a new CameraCaptureSession.
     */
    CameraSharedCaptureSessionImpl(int id,
            CameraCaptureSession.StateCallback callback, Executor stateExecutor,
            android.hardware.camera2.impl.CameraDeviceImpl deviceImpl,
            Executor deviceStateExecutor, boolean configureSuccess) {
        CameraCaptureSession.StateCallback wrapperCallback = new WrapperCallback(callback);
        mSessionImpl = new CameraCaptureSessionImpl(id, /*input*/null, wrapperCallback,
                stateExecutor, deviceImpl, deviceStateExecutor, configureSuccess);
        mCameraDevice = deviceImpl;
        mDeviceExecutor = deviceStateExecutor;
        mInitialized.open();
    }

    @Override
    public int startStreaming(List<Surface> surfaces, Executor executor, CaptureCallback callback)
            throws CameraAccessException {
        if (surfaces.isEmpty()) {
            throw new IllegalArgumentException("No surfaces provided for streaming");
        } else if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        } else if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        return mSessionImpl.startStreaming(surfaces, executor, callback);
    }

    @Override
    public void stopStreaming() throws CameraAccessException {
        mSessionImpl.stopRepeating();
    }

    @Override
    public void close() {
        mSessionImpl.close();
    }

    @Override
    public Surface getInputSurface() {
        return null;
    }

    @Override
    public boolean isReprocessable() {
        return false;
    }

    @Override
    public boolean supportsOfflineProcessing(Surface surface) {
        return false;
    }

    @Override
    public void abortCaptures() throws CameraAccessException {
        if (mCameraDevice.isPrimaryClient()) {
            mSessionImpl.abortCaptures();
            return;
        }
        throw new UnsupportedOperationException("Shared capture session only supports this method"
                + " for primary clients");
    }

    @Override
    public int setRepeatingRequest(CaptureRequest request, CaptureCallback listener,
            Handler handler) throws CameraAccessException {
        if (mCameraDevice.isPrimaryClient()) {
            return mSessionImpl.setRepeatingRequest(request, listener, handler);
        }
        throw new UnsupportedOperationException("Shared capture session only supports this method"
                + " for primary clients");
    }

    @Override
    public int setSingleRepeatingRequest(CaptureRequest request, Executor executor,
            CaptureCallback listener)
            throws CameraAccessException {
        if (mCameraDevice.isPrimaryClient()) {
            return mSessionImpl.setSingleRepeatingRequest(request, executor, listener);
        }
        throw new UnsupportedOperationException("Shared capture session only supports this method"
                + " for primary clients");
    }

    @Override
    public void stopRepeating() throws CameraAccessException {
        if (mCameraDevice.isPrimaryClient()) {
            mSessionImpl.stopRepeating();
            return;
        }
        throw new UnsupportedOperationException("Shared capture session only supports this method"
                + " for primary clients");
    }

    @Override
    public int capture(CaptureRequest request, CaptureCallback listener, Handler handler)
            throws CameraAccessException {
        if (mCameraDevice.isPrimaryClient()) {
            return mSessionImpl.capture(request, listener, handler);
        }
        throw new UnsupportedOperationException("Shared capture session only supports this method"
                + " for primary clients");
    }

    @Override
    public int captureSingleRequest(CaptureRequest request, Executor executor,
            CaptureCallback listener)
            throws CameraAccessException {
        if (mCameraDevice.isPrimaryClient()) {
            return mSessionImpl.captureSingleRequest(request, executor, listener);
        }
        throw new UnsupportedOperationException("Shared capture session only supports this method"
                + " for primary clients");
    }

    @Override
    public void tearDown(Surface surface) throws CameraAccessException {
        mSessionImpl.tearDown(surface);
    }

    @Override
    public CameraDevice getDevice() {
        return mSessionImpl.getDevice();
    }

    @Override
    public boolean isAborting() {
        return mSessionImpl.isAborting();
    }

    @Override
    public CameraDeviceImpl.StateCallbackKK getDeviceStateCallback() {
        return mSessionImpl.getDeviceStateCallback();
    }

    @Override
    public void replaceSessionClose() {
        mSessionImpl.replaceSessionClose();
    }

    @Override
    public CameraOfflineSession switchToOffline(Collection<Surface> offlineSurfaces,
            Executor executor, CameraOfflineSessionCallback listener)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Shared capture session do not support this method"
                );
    }

    @Override
    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback listener,
            Handler handler) throws CameraAccessException {
        throw new UnsupportedOperationException("Shared Capture session do not support"
                + " this method");
    }

    @Override
    public int setRepeatingBurstRequests(List<CaptureRequest> requests,
            Executor executor, CaptureCallback listener)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Shared Capture session do not support"
                + " this method");
    }

    @Override
    public int captureBurst(List<CaptureRequest> requests, CaptureCallback listener,
            Handler handler) throws CameraAccessException {
        throw new UnsupportedOperationException("Shared Capture session do not support"
                + " this method");
    }

    @Override
    public int captureBurstRequests(List<CaptureRequest> requests,
            Executor executor, CaptureCallback listener)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Shared Capture session do not support"
                + " this method");
    }

    @Override
    public void updateOutputConfiguration(OutputConfiguration config)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Shared capture session do not support"
                + " this method");
    }

    @Override
    public void finalizeOutputConfigurations(List<OutputConfiguration> deferredOutputConfigs)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Shared capture session do not support"
                + " this method");
    }

    @Override
    public void prepare(Surface surface) throws CameraAccessException {
        throw new UnsupportedOperationException("Shared capture session do not support"
                + " this method");
    }

    @Override
    public void prepare(int maxCount, Surface surface) throws CameraAccessException {
        throw new UnsupportedOperationException("Shared capture session do not support"
                + " this method");
    }

    @Override
    public void closeWithoutDraining() {
        throw new UnsupportedOperationException("Shared capture session do not support"
                + " this method");
    }

    private class WrapperCallback extends StateCallback {
        private final StateCallback mCallback;

        WrapperCallback(StateCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            mInitialized.block();
            mCallback.onConfigured(CameraSharedCaptureSessionImpl.this);
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            mInitialized.block();
            mCallback.onConfigureFailed(CameraSharedCaptureSessionImpl.this);
        }

        @Override
        public void onReady(CameraCaptureSession session) {
            mCallback.onReady(CameraSharedCaptureSessionImpl.this);
        }

        @Override
        public void onActive(CameraCaptureSession session) {
            mCallback.onActive(CameraSharedCaptureSessionImpl.this);
        }

        @Override
        public void onCaptureQueueEmpty(CameraCaptureSession session) {
            mCallback.onCaptureQueueEmpty(CameraSharedCaptureSessionImpl.this);
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            mCallback.onClosed(CameraSharedCaptureSessionImpl.this);
        }

        @Override
        public void onSurfacePrepared(CameraCaptureSession session, Surface surface) {
            mCallback.onSurfacePrepared(CameraSharedCaptureSessionImpl.this,
                    surface);
        }
    }
}
