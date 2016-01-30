/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.mediaframeworktest.helpers;

import com.android.ex.camera2.pos.AutoFocusStateMachine;
import com.android.ex.camera2.pos.AutoFocusStateMachine.AutoFocusStateListener;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

/**
 * A focuser utility class to assist camera to do auto focus.
 * <p>
 * This class need create repeating request and single request to do auto focus.
 * The repeating request is used to get the auto focus states; the single
 * request is used to trigger the auto focus. This class assumes the camera device
 * supports auto-focus. Don't use this class if the camera device doesn't have focuser
 * unit.
 * </p>
 */
/**
 * (non-Javadoc)
 * @see android.hardware.camera2.cts.helpers.Camera2Focuser
 */
public class Camera2Focuser implements AutoFocusStateListener {
    private static final String TAG = "Focuser";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private final AutoFocusStateMachine mAutoFocus = new AutoFocusStateMachine(this);
    private final Handler mHandler;
    private final AutoFocusListener mAutoFocusListener;
    private final CameraDevice mCamera;
    private final CameraCaptureSession mSession;
    private final Surface mRequestSurface;
    private final StaticMetadata mStaticInfo;

    private int mAfRun = 0;
    private MeteringRectangle[] mAfRegions;
    private boolean mLocked = false;
    private boolean mSuccess = false;
    private CaptureRequest.Builder mRepeatingBuilder;

    /**
     * The callback interface to notify auto focus result.
     */
    public interface AutoFocusListener {
        /**
         * This callback is called when auto focus completes and locked.
         *
         * @param success true if focus was successful, false if otherwise
         */
        void onAutoFocusLocked(boolean success);
    }

    /**
     * Construct a focuser object, with given capture requestSurface, listener
     * and handler.
     * <p>
     * The focuser object will use camera and requestSurface to submit capture
     * request and receive focus state changes. The {@link AutoFocusListener} is
     * used to notify the auto focus callback.
     * </p>
     *
     * @param camera The camera device associated with this focuser
     * @param session The camera capture session associated with this focuser
     * @param requestSurface The surface to issue the capture request with
     * @param listener The auto focus listener to notify AF result
     * @param staticInfo The CameraCharacteristics of the camera device
     * @param handler The handler used to post auto focus callbacks
     * @throws CameraAccessException
     */
    public Camera2Focuser(CameraDevice camera, CameraCaptureSession session, Surface requestSurface,
            AutoFocusListener listener, CameraCharacteristics staticInfo, Handler handler)
            throws CameraAccessException {
        if (camera == null) {
            throw new IllegalArgumentException("camera must not be null");
        }
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        if (requestSurface == null) {
            throw new IllegalArgumentException("requestSurface must not be null");
        }
        if (staticInfo == null) {
            throw new IllegalArgumentException("staticInfo must not be null");
        }

        mCamera = camera;
        mSession = session;
        mRequestSurface = requestSurface;
        mAutoFocusListener = listener;
        mStaticInfo = new StaticMetadata(staticInfo,
                StaticMetadata.CheckLevel.ASSERT, /*collector*/null);
        mHandler = handler;

        if (!mStaticInfo.hasFocuser()) {
            throw new IllegalArgumentException("this camera doesn't have a focuser");
        }

        /**
         * Begin by always being in passive auto focus.
         */
        cancelAutoFocus();
    }

    @Override
    public synchronized void onAutoFocusSuccess(CaptureResult result, boolean locked) {
        mSuccess = true;
        mLocked = locked;

        if (locked) {
            dispatchAutoFocusStatusLocked(/*success*/true);
        }
    }

    @Override
    public synchronized void onAutoFocusFail(CaptureResult result, boolean locked) {
        mSuccess = false;
        mLocked = locked;

        if (locked) {
            dispatchAutoFocusStatusLocked(/*success*/false);
        }
    }

    @Override
    public synchronized void onAutoFocusScan(CaptureResult result) {
        mSuccess = false;
        mLocked = false;
    }

    @Override
    public synchronized void onAutoFocusInactive(CaptureResult result) {
        mSuccess = false;
        mLocked = false;
    }

    /**
     * Start a active auto focus scan based on the given regions.
     *
     * <p>This is usually used for touch for focus, it can make the auto-focus converge based
     * on some particular region aggressively. But it is usually slow as a full active scan
     * is initiated. After the auto focus is converged, the {@link cancelAutoFocus} must be called
     * to resume the continuous auto-focus.</p>
     *
     * @param afRegions The AF regions used by focuser auto focus, full active
     * array size is used if afRegions is null.
     * @throws CameraAccessException
     */
    public synchronized void touchForAutoFocus(MeteringRectangle[] afRegions)
            throws CameraAccessException {
        startAutoFocusLocked(/*active*/true, afRegions);
    }

    /**
     * Start auto focus scan.
     * <p>
     * Start an auto focus scan if it was not done yet. If AF passively focused,
     * lock it. If AF is already locked, return. Otherwise, initiate a full
     * active scan. This is suitable for still capture: focus should need to be
     * accurate, but the AF latency also need to be as short as possible.
     * </p>
     *
     * @param afRegions The AF regions used by focuser auto focus, full active
     *            array size is used if afRegions is null.
     * @throws CameraAccessException
     */
    public synchronized void startAutoFocus(MeteringRectangle[] afRegions)
            throws CameraAccessException {
        startAutoFocusLocked(/*forceActive*/false, afRegions);
    }

    /**
     * Cancel ongoing auto focus, unlock the auto-focus if it was locked, and
     * resume to passive continuous auto focus.
     *
     * @throws CameraAccessException
     */
    public synchronized void cancelAutoFocus() throws CameraAccessException {
        mSuccess = false;
        mLocked = false;

        // reset the AF regions:
        setAfRegions(null);

        // Create request builders, the af regions are automatically updated.
        mRepeatingBuilder = createRequestBuilder();
        CaptureRequest.Builder requestBuilder = createRequestBuilder();
        mAutoFocus.setPassiveAutoFocus(/*picture*/true, mRepeatingBuilder);
        mAutoFocus.unlockAutoFocus(mRepeatingBuilder, requestBuilder);
        CaptureCallback listener = createCaptureListener();
        mSession.setRepeatingRequest(mRepeatingBuilder.build(), listener, mHandler);
        mSession.capture(requestBuilder.build(), listener, mHandler);
    }

    /**
     * Get current AF mode.
     * @return current AF mode
     * @throws IllegalStateException if there auto focus is not running.
     */
    public synchronized int getCurrentAfMode() {
        if (mRepeatingBuilder == null) {
            throw new IllegalStateException("Auto focus is not running, unable to get AF mode");
        }

        return mRepeatingBuilder.get(CaptureRequest.CONTROL_AF_MODE);
    }

    private void startAutoFocusLocked(
            boolean forceActive, MeteringRectangle[] afRegions) throws CameraAccessException {

        setAfRegions(afRegions);
        mAfRun++;

        // Create request builders, the af regions are automatically updated.
        mRepeatingBuilder = createRequestBuilder();
        CaptureRequest.Builder requestBuilder = createRequestBuilder();
        if (forceActive) {
            startAutoFocusFullActiveLocked();
        } else {
            // Not forcing a full active scan. If AF passively focused, lock it. If AF is already
            // locked, return. Otherwise, initiate a full active scan.
            if (mSuccess && mLocked) {
                dispatchAutoFocusStatusLocked(/*success*/true);
                return;
            } else if (mSuccess) {
                mAutoFocus.lockAutoFocus(mRepeatingBuilder, requestBuilder);
                CaptureCallback listener = createCaptureListener();
                mSession.setRepeatingRequest(mRepeatingBuilder.build(), listener, mHandler);
                mSession.capture(requestBuilder.build(), listener, mHandler);
            } else {
                startAutoFocusFullActiveLocked();
            }
        }
    }

    private void startAutoFocusFullActiveLocked() throws CameraAccessException {
        // Create request builders, the af regions are automatically updated.
        mRepeatingBuilder = createRequestBuilder();
        CaptureRequest.Builder requestBuilder = createRequestBuilder();
        mAutoFocus.setActiveAutoFocus(mRepeatingBuilder, requestBuilder);
        if (mRepeatingBuilder.get(CaptureRequest.CONTROL_AF_TRIGGER)
                != CaptureRequest.CONTROL_AF_TRIGGER_IDLE) {
            throw new AssertionError("Wrong trigger set in repeating request");
        }
        if (requestBuilder.get(CaptureRequest.CONTROL_AF_TRIGGER)
                != CaptureRequest.CONTROL_AF_TRIGGER_START) {
            throw new AssertionError("Wrong trigger set in queued request");
        }
        mAutoFocus.resetState();

        CaptureCallback listener = createCaptureListener();
        mSession.setRepeatingRequest(mRepeatingBuilder.build(), listener, mHandler);
        mSession.capture(requestBuilder.build(), listener, mHandler);
    }

    private void dispatchAutoFocusStatusLocked(final boolean success) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAutoFocusListener.onAutoFocusLocked(success);
            }
        });
    }

    /**
     * Create request builder, set the af regions.
     * @throws CameraAccessException
     */
    private CaptureRequest.Builder createRequestBuilder() throws CameraAccessException {
        CaptureRequest.Builder requestBuilder =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        requestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, mAfRegions);
        requestBuilder.addTarget(mRequestSurface);

        return requestBuilder;
    }

    /**
     * Set AF regions, fall back to default region if afRegions is null.
     *
     * @param afRegions The AF regions to set
     * @throws IllegalArgumentException if the region is malformed (length is 0).
     */
    private void setAfRegions(MeteringRectangle[] afRegions) {
        if (afRegions == null) {
            setDefaultAfRegions();
            return;
        }
        // Throw IAE if AF regions are malformed.
        if (afRegions.length == 0) {
            throw new IllegalArgumentException("afRegions is malformed, length: 0");
        }

        mAfRegions = afRegions;
    }

    /**
     * Set default AF region to full active array size.
     */
    private void setDefaultAfRegions() {
        // Initialize AF regions with all zeros, meaning that it is up to camera device to device
        // the regions used by AF.
        mAfRegions = new MeteringRectangle[] {
                new MeteringRectangle(0, 0, 0, 0, MeteringRectangle.METERING_WEIGHT_DONT_CARE)};
    }
    private CaptureCallback createCaptureListener() {

        int thisAfRun;
        synchronized (this) {
            thisAfRun = mAfRun;
        }

        final int finalAfRun = thisAfRun;

        return new CaptureCallback() {
            private long mLatestFrameCount = -1;

            @Override
            public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                    CaptureResult result) {
                // In case of a partial result, send to focuser if necessary
                // 3A fields are present
                if (result.get(CaptureResult.CONTROL_AF_STATE) != null &&
                        result.get(CaptureResult.CONTROL_AF_MODE) != null) {
                    if (VERBOSE) {
                        Log.v(TAG, "Focuser - got early AF state");
                    }

                    dispatchToFocuser(result);
                }
            }

            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                    TotalCaptureResult result) {
                    dispatchToFocuser(result);
            }

            private void dispatchToFocuser(CaptureResult result) {
                int afRun;
                synchronized (Camera2Focuser.this) {
                    // In case of partial results, don't send AF update twice
                    long frameCount = result.getFrameNumber();
                    if (frameCount <= mLatestFrameCount) return;
                    mLatestFrameCount = frameCount;

                    afRun = mAfRun;
                }

                if (afRun != finalAfRun) {
                    if (VERBOSE) {
                        Log.w(TAG,
                                "onCaptureCompleted - Ignoring results from previous AF run "
                                + finalAfRun);
                    }
                    return;
                }

                mAutoFocus.onCaptureCompleted(result);
            }
        };
    }
}
