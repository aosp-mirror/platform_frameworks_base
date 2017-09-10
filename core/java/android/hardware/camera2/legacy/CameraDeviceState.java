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

package android.hardware.camera2.legacy;

import android.hardware.camera2.impl.CameraDeviceImpl;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.os.Handler;
import android.util.Log;

/**
 * Emulates a the state of a single Camera2 device.
 *
 * <p>
 * This class acts as the state machine for a camera device.  Valid state transitions are given
 * in the table below:
 * </p>
 *
 * <ul>
 *      <li>{@code UNCONFIGURED -> CONFIGURING}</li>
 *      <li>{@code CONFIGURING -> IDLE}</li>
 *      <li>{@code IDLE -> CONFIGURING}</li>
 *      <li>{@code IDLE -> CAPTURING}</li>
 *      <li>{@code IDLE -> IDLE}</li>
 *      <li>{@code CAPTURING -> IDLE}</li>
 *      <li>{@code ANY -> ERROR}</li>
 * </ul>
 */
public class CameraDeviceState {
    private static final String TAG = "CameraDeviceState";
    private static final boolean DEBUG = false;

    private static final int STATE_ERROR = 0;
    private static final int STATE_UNCONFIGURED = 1;
    private static final int STATE_CONFIGURING = 2;
    private static final int STATE_IDLE = 3;
    private static final int STATE_CAPTURING = 4;

    private static final String[] sStateNames = { "ERROR", "UNCONFIGURED", "CONFIGURING", "IDLE",
            "CAPTURING"};

    private int mCurrentState = STATE_UNCONFIGURED;
    private int mCurrentError = NO_CAPTURE_ERROR;

    private RequestHolder mCurrentRequest = null;

    private Handler mCurrentHandler = null;
    private CameraDeviceStateListener mCurrentListener = null;

    /**
     * Error code used by {@link #setCaptureStart} and {@link #setCaptureResult} to indicate that no
     * error has occurred.
     */
    public static final int NO_CAPTURE_ERROR = -1;

    /**
     * CameraDeviceStateListener callbacks to be called after state transitions.
     */
    public interface CameraDeviceStateListener {
        void onError(int errorCode, Object errorArg, RequestHolder holder);
        void onConfiguring();
        void onIdle();
        void onBusy();
        void onCaptureStarted(RequestHolder holder, long timestamp);
        void onCaptureResult(CameraMetadataNative result, RequestHolder holder);
        void onRequestQueueEmpty();
        void onRepeatingRequestError(long lastFrameNumber, int repeatingRequestId);
    }

    /**
     * Transition to the {@code ERROR} state.
     *
     * <p>
     * The device cannot exit the {@code ERROR} state.  If the device was not already in the
     * {@code ERROR} state, {@link CameraDeviceStateListener#onError(int, RequestHolder)} will be
     * called.
     * </p>
     *
     * @param error the error to set.  Should be one of the error codes defined in
     *      {@link CameraDeviceImpl.CameraDeviceCallbacks}.
     */
    public synchronized void setError(int error) {
        mCurrentError = error;
        doStateTransition(STATE_ERROR);
    }

    /**
     * Transition to the {@code CONFIGURING} state, or {@code ERROR} if in an invalid state.
     *
     * <p>
     * If the device was not already in the {@code CONFIGURING} state,
     * {@link CameraDeviceStateListener#onConfiguring()} will be called.
     * </p>
     *
     * @return {@code false} if an error has occurred.
     */
    public synchronized boolean setConfiguring() {
        doStateTransition(STATE_CONFIGURING);
        return mCurrentError == NO_CAPTURE_ERROR;
    }

    /**
     * Transition to the {@code IDLE} state, or {@code ERROR} if in an invalid state.
     *
     * <p>
     * If the device was not already in the {@code IDLE} state,
     * {@link CameraDeviceStateListener#onIdle()} will be called.
     * </p>
     *
     * @return {@code false} if an error has occurred.
     */
    public synchronized boolean setIdle() {
        doStateTransition(STATE_IDLE);
        return mCurrentError == NO_CAPTURE_ERROR;
    }

    /**
     * Transition to the {@code CAPTURING} state, or {@code ERROR} if in an invalid state.
     *
     * <p>
     * If the device was not already in the {@code CAPTURING} state,
     * {@link CameraDeviceStateListener#onCaptureStarted(RequestHolder)} will be called.
     * </p>
     *
     * @param request A {@link RequestHolder} containing the request for the current capture.
     * @param timestamp The timestamp of the capture start in nanoseconds.
     * @param captureError Report a recoverable error for a single request using a valid
     *                     error code for {@code ICameraDeviceCallbacks}, or
     *                     {@link #NO_CAPTURE_ERROR}
     * @return {@code false} if an error has occurred.
     */
    public synchronized boolean setCaptureStart(final RequestHolder request, long timestamp,
                                            int captureError) {
        mCurrentRequest = request;
        doStateTransition(STATE_CAPTURING, timestamp, captureError);
        return mCurrentError == NO_CAPTURE_ERROR;
    }

    /**
     * Set the result for a capture.
     *
     * <p>
     * If the device was in the {@code CAPTURING} state,
     * {@link CameraDeviceStateListener#onCaptureResult(CameraMetadataNative, RequestHolder)} will
     * be called with the given result, otherwise this will result in the device transitioning to
     * the {@code ERROR} state,
     * </p>
     *
     * @param request The {@link RequestHolder} request that created this result.
     * @param result The {@link CameraMetadataNative} result to set.
     * @param captureError Report a recoverable error for a single buffer or result using a valid
     *                     error code for {@code ICameraDeviceCallbacks}, or
     *                     {@link #NO_CAPTURE_ERROR}.
     * @param captureErrorArg An argument for some error captureError codes.
     * @return {@code false} if an error has occurred.
     */
    public synchronized boolean setCaptureResult(final RequestHolder request,
            final CameraMetadataNative result,
            final int captureError, final Object captureErrorArg) {
        if (mCurrentState != STATE_CAPTURING) {
            Log.e(TAG, "Cannot receive result while in state: " + mCurrentState);
            mCurrentError = CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE;
            doStateTransition(STATE_ERROR);
            return mCurrentError == NO_CAPTURE_ERROR;
        }

        if (mCurrentHandler != null && mCurrentListener != null) {
            if (captureError != NO_CAPTURE_ERROR) {
                mCurrentHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mCurrentListener.onError(captureError, captureErrorArg, request);
                    }
                });
            } else {
                mCurrentHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mCurrentListener.onCaptureResult(result, request);
                    }
                });
            }
        }
        return mCurrentError == NO_CAPTURE_ERROR;
    }

    public synchronized boolean setCaptureResult(final RequestHolder request,
            final CameraMetadataNative result) {
        return setCaptureResult(request, result, NO_CAPTURE_ERROR, /*errorArg*/null);
    }

    /**
     * Set repeating request error.
     *
     * <p>Repeating request has been stopped due to an error such as abandoned output surfaces.</p>
     *
     * @param lastFrameNumber Frame number of the last repeating request before it is stopped.
     * @param repeatingRequestId The ID of the repeating request being stopped
     */
    public synchronized void setRepeatingRequestError(final long lastFrameNumber,
            final int repeatingRequestId) {
        mCurrentHandler.post(new Runnable() {
            @Override
            public void run() {
                mCurrentListener.onRepeatingRequestError(lastFrameNumber, repeatingRequestId);
            }
        });
    }

    /**
     * Indicate that request queue (non-repeating) becomes empty.
     *
     * <p> Send notification that all non-repeating requests have been sent to camera device. </p>
     */
    public synchronized void setRequestQueueEmpty() {
        mCurrentHandler.post(new Runnable() {
            @Override
            public void run() {
                mCurrentListener.onRequestQueueEmpty();
            }
        });
    }

    /**
     * Set the listener for state transition callbacks.
     *
     * @param handler handler on which to call the callbacks.
     * @param listener the {@link CameraDeviceStateListener} callbacks to call.
     */
    public synchronized void setCameraDeviceCallbacks(Handler handler,
                                                      CameraDeviceStateListener listener) {
        mCurrentHandler = handler;
        mCurrentListener = listener;
    }

    private void doStateTransition(int newState) {
        doStateTransition(newState, /*timestamp*/0, NO_CAPTURE_ERROR);
    }

    private void doStateTransition(int newState, final long timestamp, final int error) {
        if (newState != mCurrentState) {
            String stateName = "UNKNOWN";
            if (newState >= 0 && newState < sStateNames.length) {
                stateName = sStateNames[newState];
            }
            Log.i(TAG, "Legacy camera service transitioning to state " + stateName);
        }

        // If we transitioned into a non-IDLE/non-ERROR state then mark the device as busy
        if(newState != STATE_ERROR && newState != STATE_IDLE) {
            if (mCurrentState != newState && mCurrentHandler != null &&
                    mCurrentListener != null) {
                mCurrentHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mCurrentListener.onBusy();
                    }
                });
            }
        }

        switch(newState) {
            case STATE_ERROR:
                if (mCurrentState != STATE_ERROR && mCurrentHandler != null &&
                        mCurrentListener != null) {
                    mCurrentHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCurrentListener.onError(mCurrentError, /*errorArg*/null, mCurrentRequest);
                        }
                    });
                }
                mCurrentState = STATE_ERROR;
                break;
            case STATE_CONFIGURING:
                if (mCurrentState != STATE_UNCONFIGURED && mCurrentState != STATE_IDLE) {
                    Log.e(TAG, "Cannot call configure while in state: " + mCurrentState);
                    mCurrentError = CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE;
                    doStateTransition(STATE_ERROR);
                    break;
                }
                if (mCurrentState != STATE_CONFIGURING && mCurrentHandler != null &&
                        mCurrentListener != null) {
                    mCurrentHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCurrentListener.onConfiguring();
                        }
                    });
                }
                mCurrentState = STATE_CONFIGURING;
                break;
            case STATE_IDLE:
                if (mCurrentState == STATE_IDLE) {
                    break;
                }

                if (mCurrentState != STATE_CONFIGURING && mCurrentState != STATE_CAPTURING) {
                    Log.e(TAG, "Cannot call idle while in state: " + mCurrentState);
                    mCurrentError = CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE;
                    doStateTransition(STATE_ERROR);
                    break;
                }

                if (mCurrentState != STATE_IDLE && mCurrentHandler != null &&
                        mCurrentListener != null) {
                    mCurrentHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCurrentListener.onIdle();
                        }
                    });
                }
                mCurrentState = STATE_IDLE;
                break;
            case STATE_CAPTURING:
                if (mCurrentState != STATE_IDLE && mCurrentState != STATE_CAPTURING) {
                    Log.e(TAG, "Cannot call capture while in state: " + mCurrentState);
                    mCurrentError = CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE;
                    doStateTransition(STATE_ERROR);
                    break;
                }

                if (mCurrentHandler != null && mCurrentListener != null) {
                    if (error != NO_CAPTURE_ERROR) {
                        mCurrentHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mCurrentListener.onError(error, /*errorArg*/null, mCurrentRequest);
                            }
                        });
                    } else {
                        mCurrentHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mCurrentListener.onCaptureStarted(mCurrentRequest, timestamp);
                            }
                        });
                    }
                }
                mCurrentState = STATE_CAPTURING;
                break;
            default:
                throw new IllegalStateException("Transition to unknown state: " + newState);
        }
    }


}
