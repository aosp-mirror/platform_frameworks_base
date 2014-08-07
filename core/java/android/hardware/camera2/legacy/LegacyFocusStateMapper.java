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

import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.utils.ParamsUtils;
import android.util.Log;

import java.util.Objects;

import static android.hardware.camera2.CaptureRequest.*;
import static com.android.internal.util.Preconditions.*;

/**
 * Map capture request data into legacy focus state transitions.
 *
 * <p>This object will asynchronously process auto-focus changes, so no interaction
 * with it is necessary beyond reading the current state and updating with the latest trigger.</p>
 */
@SuppressWarnings("deprecation")
public class LegacyFocusStateMapper {
    private static String TAG = "LegacyFocusStateMapper";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private final Camera mCamera;

    private int mAfStatePrevious = CONTROL_AF_STATE_INACTIVE;
    private String mAfModePrevious = null;

    /** Guard mAfRun and mAfState */
    private final Object mLock = new Object();
    /** Guard access with mLock */
    private int mAfRun = 0;
    /** Guard access with mLock */
    private int mAfState = CONTROL_AF_STATE_INACTIVE;

    /**
     * Instantiate a new focus state mapper.
     *
     * @param camera a non-{@code null} camera1 device
     *
     * @throws NullPointerException if any of the args were {@code null}
     */
    public LegacyFocusStateMapper(Camera camera) {
        mCamera = checkNotNull(camera, "camera must not be null");
    }

    /**
     * Process the AF triggers from the request as a camera1 autofocus routine.
     *
     * <p>This method should be called after the parameters are {@link LegacyRequestMapper mapped}
     * with the request.</p>
     *
     * <p>Callbacks are processed in the background, and the next call to {@link #mapResultTriggers}
     * will have the latest AF state as reflected by the camera1 callbacks.</p>
     *
     * <p>None of the arguments will be mutated.</p>
     *
     * @param captureRequest a non-{@code null} request
     * @param parameters a non-{@code null} parameters corresponding to this request (read-only)
     */
    public void processRequestTriggers(CaptureRequest captureRequest,
            Camera.Parameters parameters) {
        checkNotNull(captureRequest, "captureRequest must not be null");

        /*
         * control.afTrigger
         */
        int afTrigger = ParamsUtils.getOrDefault(captureRequest, CONTROL_AF_TRIGGER,
                CONTROL_AF_TRIGGER_IDLE);

        final String afMode = parameters.getFocusMode();

        if (!Objects.equals(mAfModePrevious, afMode)) {
            if (VERBOSE) {
                Log.v(TAG, "processRequestTriggers - AF mode switched from " + mAfModePrevious +
                        " to " + afMode);
            }

            // Switching modes always goes back to INACTIVE; ignore callbacks from previous modes

            synchronized (mLock) {
                ++mAfRun;
                mAfState = CONTROL_AF_STATE_INACTIVE;
            }
            mCamera.cancelAutoFocus();
        }

        mAfModePrevious = afMode;

        // Passive AF Scanning
        {
            final int currentAfRun;

            synchronized (mLock) {
                currentAfRun = mAfRun;
            }

            mCamera.setAutoFocusMoveCallback(new Camera.AutoFocusMoveCallback() {
                @Override
                public void onAutoFocusMoving(boolean start, Camera camera) {
                    synchronized (mLock) {
                        int latestAfRun = mAfRun;

                        if (VERBOSE) {
                            Log.v(TAG, "onAutoFocusMoving - start " + start + " latest AF run " +
                                    latestAfRun + ", last AF run " + currentAfRun);
                        }

                        if (currentAfRun != latestAfRun) {
                            Log.d(TAG,
                                    "onAutoFocusMoving - ignoring move callbacks from old af run"
                                            + currentAfRun);
                            return;
                        }

                        int newAfState = start ?
                                CONTROL_AF_STATE_PASSIVE_SCAN :
                                CONTROL_AF_STATE_PASSIVE_FOCUSED;
                        // We never send CONTROL_AF_STATE_PASSIVE_UNFOCUSED

                        switch (afMode) {
                            case Parameters.FOCUS_MODE_CONTINUOUS_PICTURE:
                            case Parameters.FOCUS_MODE_CONTINUOUS_VIDEO:
                                break;
                            // This callback should never be sent in any other AF mode
                            default:
                                Log.w(TAG, "onAutoFocus - got unexpected onAutoFocus in mode "
                                        + afMode);

                        }

                        mAfState = newAfState;
                    }
                }
            });
        }

        // AF Locking
        switch (afTrigger) {
            case CONTROL_AF_TRIGGER_START:

                int afStateAfterStart;
                switch (afMode) {
                    case Parameters.FOCUS_MODE_AUTO:
                    case Parameters.FOCUS_MODE_MACRO:
                        afStateAfterStart = CONTROL_AF_STATE_ACTIVE_SCAN;
                        break;
                    case Parameters.FOCUS_MODE_CONTINUOUS_PICTURE:
                    case Parameters.FOCUS_MODE_CONTINUOUS_VIDEO:
                        afStateAfterStart = CONTROL_AF_STATE_PASSIVE_SCAN;
                    default:
                        // EDOF, INFINITY
                        afStateAfterStart = CONTROL_AF_STATE_INACTIVE;
                }

                final int currentAfRun;
                synchronized (mLock) {
                    currentAfRun = ++mAfRun;
                    mAfState = afStateAfterStart;
                }

                if (VERBOSE) {
                    Log.v(TAG, "processRequestTriggers - got AF_TRIGGER_START, " +
                            "new AF run is " + currentAfRun);
                }

                mCamera.autoFocus(new Camera.AutoFocusCallback() {
                    @Override
                    public void onAutoFocus(boolean success, Camera camera) {
                        synchronized (mLock) {
                            int latestAfRun = mAfRun;

                            if (VERBOSE) {
                                Log.v(TAG, "onAutoFocus - success " + success + " latest AF run " +
                                        latestAfRun + ", last AF run " + currentAfRun);
                            }

                            // Ignore old auto-focus results, since another trigger was requested
                            if (latestAfRun != currentAfRun) {
                                Log.d(TAG, String.format("onAutoFocus - ignoring AF callback " +
                                        "(old run %d, new run %d)", currentAfRun, latestAfRun));

                                return;
                            }

                            int newAfState = success ?
                                    CONTROL_AF_STATE_FOCUSED_LOCKED :
                                    CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;

                            switch (afMode) {
                                case Parameters.FOCUS_MODE_AUTO:
                                case Parameters.FOCUS_MODE_CONTINUOUS_PICTURE:
                                case Parameters.FOCUS_MODE_CONTINUOUS_VIDEO:
                                case Parameters.FOCUS_MODE_MACRO:
                                    break;
                                // This callback should never be sent in any other AF mode
                                default:
                                    Log.w(TAG, "onAutoFocus - got unexpected onAutoFocus in mode "
                                            + afMode);

                            }

                            mAfState = newAfState;
                        }
                    }
                });

                break;
            case CONTROL_AF_TRIGGER_CANCEL:
                synchronized (mLock) {
                    int updatedAfRun;

                    synchronized (mLock) {
                        updatedAfRun = ++mAfRun;
                        mAfState = CONTROL_AF_STATE_INACTIVE;
                    }

                    mCamera.cancelAutoFocus();

                    if (VERBOSE) {
                        Log.v(TAG, "processRequestTriggers - got AF_TRIGGER_CANCEL, " +
                                "new AF run is " + updatedAfRun);
                    }
                }

                break;
            case CONTROL_AF_TRIGGER_IDLE:
                // No action necessary. The callbacks will handle transitions.
                break;
            default:
                Log.w(TAG, "processRequestTriggers - ignoring unknown control.afTrigger = "
                        + afTrigger);
        }
    }

    /**
     * Update the {@code result} camera metadata map with the new value for the
     * {@code control.afState}.
     *
     * <p>AF callbacks are processed in the background, and each call to {@link #mapResultTriggers}
     * will have the latest AF state as reflected by the camera1 callbacks.</p>
     *
     * @param result a non-{@code null} result
     */
    public void mapResultTriggers(CameraMetadataNative result) {
        checkNotNull(result, "result must not be null");

        int newAfState;
        synchronized (mLock) {
            newAfState = mAfState;
        }

        if (VERBOSE && newAfState != mAfStatePrevious) {
            Log.v(TAG, String.format("mapResultTriggers - afState changed from %s to %s",
                    afStateToString(mAfStatePrevious), afStateToString(newAfState)));
        }

        result.set(CaptureResult.CONTROL_AF_STATE, newAfState);

        mAfStatePrevious = newAfState;
    }

    private static String afStateToString(int afState) {
        switch (afState) {
            case CONTROL_AF_STATE_ACTIVE_SCAN:
                return "ACTIVE_SCAN";
            case CONTROL_AF_STATE_FOCUSED_LOCKED:
                return "FOCUSED_LOCKED";
            case CONTROL_AF_STATE_INACTIVE:
                return "INACTIVE";
            case CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                return "NOT_FOCUSED_LOCKED";
            case CONTROL_AF_STATE_PASSIVE_FOCUSED:
                return "PASSIVE_FOCUSED";
            case CONTROL_AF_STATE_PASSIVE_SCAN:
                return "PASSIVE_SCAN";
            case CONTROL_AF_STATE_PASSIVE_UNFOCUSED:
                return "PASSIVE_UNFOCUSED";
            default :
                return "UNKNOWN(" + afState + ")";
        }
    }
}
