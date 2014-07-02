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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Manages the flashlight.
 */
public class FlashlightController {

    private static final String TAG = "FlashlightController";

    private final CameraManager mCameraManager;
    /** Call {@link #ensureHandler()} before using */
    private Handler mHandler;

    /** Lock on mListeners when accessing */
    private final ArrayList<WeakReference<FlashlightListener>> mListeners = new ArrayList<>(1);

    /** Lock on {@code this} when accessing */
    private boolean mFlashlightEnabled;

    private CameraDevice mCameraDevice;
    private CaptureRequest mFlashlightRequest;
    private CameraCaptureSession mSession;
    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;

    public FlashlightController(Context mContext) {
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
    }

    public synchronized void setFlashlight(boolean enabled) {
        if (mFlashlightEnabled != enabled) {
            mFlashlightEnabled = enabled;
            postUpdateFlashlight();
        }
    }

    public boolean isAvailable() {
        try {
            return getCameraId() != null;
        } catch (CameraAccessException e) {
            return false;
        }
    }

    public void addListener(FlashlightListener l) {
        synchronized (mListeners) {
            cleanUpListenersLocked(l);
            mListeners.add(new WeakReference<>(l));
        }
    }

    public void removeListener(FlashlightListener l) {
        synchronized (mListeners) {
            cleanUpListenersLocked(l);
        }
    }

    private synchronized void ensureHandler() {
        if (mHandler == null) {
            HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            mHandler = new Handler(thread.getLooper());
        }
    }

    private void startDevice() throws CameraAccessException {
        mCameraManager.openCamera(getCameraId(), mCameraListener, mHandler);
    }

    private void startSession() throws CameraAccessException {
        mSurfaceTexture = new SurfaceTexture(false);
        Size size = getSmallestSize(mCameraDevice.getId());
        mSurfaceTexture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        mSurface = new Surface(mSurfaceTexture);
        ArrayList<Surface> outputs = new ArrayList<>(1);
        outputs.add(mSurface);
        mCameraDevice.createCaptureSession(outputs, mSessionListener, mHandler);
    }

    private Size getSmallestSize(String cameraId) throws CameraAccessException {
        Size[] outputSizes = mCameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                .getOutputSizes(SurfaceTexture.class);
        if (outputSizes == null || outputSizes.length == 0) {
            throw new IllegalStateException(
                    "Camera " + cameraId + "doesn't support any outputSize.");
        }
        Size chosen = outputSizes[0];
        for (Size s : outputSizes) {
            if (chosen.getWidth() >= s.getWidth() && chosen.getHeight() >= s.getHeight()) {
                chosen = s;
            }
        }
        return chosen;
    }

    private void postUpdateFlashlight() {
        ensureHandler();
        mHandler.post(mUpdateFlashlightRunnable);
    }

    private String getCameraId() throws CameraAccessException {
        String[] ids = mCameraManager.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
            if (flashAvailable != null && flashAvailable
                    && lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private void updateFlashlight(boolean forceDisable) {
        try {
            boolean enabled;
            synchronized (this) {
                enabled = mFlashlightEnabled && !forceDisable;
            }
            if (enabled) {
                if (mCameraDevice == null) {
                    startDevice();
                    return;
                }
                if (mSession == null) {
                    startSession();
                    return;
                }
                if (mFlashlightRequest == null) {
                    CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW);
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                    builder.addTarget(mSurface);
                    CaptureRequest request = builder.build();
                    mSession.capture(request, null, mHandler);
                    mFlashlightRequest = request;
                }
            } else {
                if (mCameraDevice != null) {
                    mCameraDevice.close();
                    teardown();
                }
            }

        } catch (CameraAccessException|IllegalStateException|UnsupportedOperationException e) {
            Log.e(TAG, "Error in updateFlashlight", e);
            handleError();
        }
    }

    private void teardown() {
        mCameraDevice = null;
        mSession = null;
        mFlashlightRequest = null;
        if (mSurface != null) {
            mSurface.release();
            mSurfaceTexture.release();
        }
        mSurface = null;
        mSurfaceTexture = null;
    }

    private void handleError() {
        synchronized (this) {
            mFlashlightEnabled = false;
        }
        dispatchError();
        dispatchOff();
        updateFlashlight(true /* forceDisable */);
    }

    private void dispatchOff() {
        dispatchListeners(false, true /* off */);
    }

    private void dispatchError() {
        dispatchListeners(true /* error */, false);
    }

    private void dispatchListeners(boolean error, boolean off) {
        synchronized (mListeners) {
            final int N = mListeners.size();
            boolean cleanup = false;
            for (int i = 0; i < N; i++) {
                FlashlightListener l = mListeners.get(i).get();
                if (l != null) {
                    if (error) {
                        l.onFlashlightError();
                    } else if (off) {
                        l.onFlashlightOff();
                    }
                } else {
                    cleanup = true;
                }
            }
            if (cleanup) {
                cleanUpListenersLocked(null);
            }
        }
    }

    private void cleanUpListenersLocked(FlashlightListener listener) {
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            FlashlightListener found = mListeners.get(i).get();
            if (found == null || found == listener) {
                mListeners.remove(i);
            }
        }
    }

    private final CameraDevice.StateListener mCameraListener = new CameraDevice.StateListener() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            postUpdateFlashlight();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            if (mCameraDevice == camera) {
                dispatchOff();
                teardown();
            }
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: camera=" + camera + " error=" + error);
            if (camera == mCameraDevice || mCameraDevice == null) {
                handleError();
            }
        }
    };

    private final CameraCaptureSession.StateListener mSessionListener =
            new CameraCaptureSession.StateListener() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            mSession = session;
            postUpdateFlashlight();
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "Configure failed.");
            if (mSession == null || mSession == session) {
                handleError();
            }
        }
    };

    private final Runnable mUpdateFlashlightRunnable = new Runnable() {
        @Override
        public void run() {
            updateFlashlight(false /* forceDisable */);
        }
    };

    public interface FlashlightListener {

        /**
         * Called when the flashlight turns off unexpectedly.
         */
        void onFlashlightOff();

        /**
         * Called when there is an error that turns the flashlight off.
         */
        void onFlashlightError();
    }
}
