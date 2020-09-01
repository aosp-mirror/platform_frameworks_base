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

import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.FaceDetectionListener;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.legacy.ParameterUtils.ZoomData;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.utils.ListUtils;
import android.hardware.camera2.utils.ParamsUtils;
import android.util.Log;
import android.util.Size;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.List;

import static android.hardware.camera2.CaptureRequest.*;
import static com.android.internal.util.Preconditions.*;

/**
 * Map legacy face detect callbacks into face detection results.
 */
@SuppressWarnings("deprecation")
public class LegacyFaceDetectMapper {
    private static String TAG = "LegacyFaceDetectMapper";
    private static final boolean DEBUG = false;

    private final Camera mCamera;
    /** Is the camera capable of face detection? */
    private final boolean mFaceDetectSupported;
    /** Is the camera is running face detection? */
    private boolean mFaceDetectEnabled = false;
    /** Did the last request say to use SCENE_MODE = FACE_PRIORITY? */
    private boolean mFaceDetectScenePriority = false;
    /** Did the last request enable the face detect mode to ON? */
    private boolean mFaceDetectReporting = false;

    /** Synchronize access to all fields */
    private final Object mLock = new Object();
    private Camera.Face[] mFaces;
    private Camera.Face[] mFacesPrev;
    /**
     * Instantiate a new face detect mapper.
     *
     * @param camera a non-{@code null} camera1 device
     * @param characteristics a  non-{@code null} camera characteristics for that camera1
     *
     * @throws NullPointerException if any of the args were {@code null}
     */
    public LegacyFaceDetectMapper(Camera camera, CameraCharacteristics characteristics) {
        mCamera = checkNotNull(camera, "camera must not be null");
        checkNotNull(characteristics, "characteristics must not be null");

        mFaceDetectSupported = ArrayUtils.contains(
                characteristics.get(
                        CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES),
                STATISTICS_FACE_DETECT_MODE_SIMPLE);

        if (!mFaceDetectSupported) {
            return;
        }

       mCamera.setFaceDetectionListener(new FaceDetectionListener() {

        @Override
        public void onFaceDetection(Camera.Face[] faces, Camera camera) {
            int lengthFaces = faces == null ? 0 : faces.length;
            synchronized (mLock) {
                if (mFaceDetectEnabled) {
                    mFaces = faces;
                } else if (lengthFaces > 0) {
                    // stopFaceDetectMode could race against the requests, print a debug log
                    Log.d(TAG,
                            "onFaceDetection - Ignored some incoming faces since" +
                            "face detection was disabled");
                }
            }

            if (DEBUG) {
                Log.v(TAG, "onFaceDetection - read " + lengthFaces + " faces");
            }
        }
       });
    }

    /**
     * Process the face detect mode from the capture request into an api1 face detect toggle.
     *
     * <p>This method should be called after the parameters are {@link LegacyRequestMapper mapped}
     * with the request.</p>
     *
     * <p>Callbacks are processed in the background, and the next call to {@link #mapResultTriggers}
     * will have the latest faces detected as reflected by the camera1 callbacks.</p>
     *
     * <p>None of the arguments will be mutated.</p>
     *
     * @param captureRequest a non-{@code null} request
     * @param parameters a non-{@code null} parameters corresponding to this request (read-only)
     */
    public void processFaceDetectMode(CaptureRequest captureRequest,
            Camera.Parameters parameters) {
        checkNotNull(captureRequest, "captureRequest must not be null");

        /*
         * statistics.faceDetectMode
         */
        int fdMode = ParamsUtils.getOrDefault(captureRequest, STATISTICS_FACE_DETECT_MODE,
                STATISTICS_FACE_DETECT_MODE_OFF);

        if (fdMode != STATISTICS_FACE_DETECT_MODE_OFF && !mFaceDetectSupported) {
            Log.w(TAG,
                    "processFaceDetectMode - Ignoring statistics.faceDetectMode; " +
                    "face detection is not available");
            return;
        }

        /*
         * control.sceneMode
         */
        int sceneMode = ParamsUtils.getOrDefault(captureRequest, CONTROL_SCENE_MODE,
                CONTROL_SCENE_MODE_DISABLED);
        if (sceneMode == CONTROL_SCENE_MODE_FACE_PRIORITY && !mFaceDetectSupported) {
            Log.w(TAG, "processFaceDetectMode - ignoring control.sceneMode == FACE_PRIORITY; " +
                    "face detection is not available");
            return;
        }

        // Print some warnings out in case the values were wrong
        switch (fdMode) {
            case STATISTICS_FACE_DETECT_MODE_OFF:
            case STATISTICS_FACE_DETECT_MODE_SIMPLE:
                break;
            case STATISTICS_FACE_DETECT_MODE_FULL:
                Log.w(TAG,
                        "processFaceDetectMode - statistics.faceDetectMode == FULL unsupported, " +
                        "downgrading to SIMPLE");
                break;
            default:
                Log.w(TAG, "processFaceDetectMode - ignoring unknown statistics.faceDetectMode = "
                        + fdMode);
                return;
        }

        boolean enableFaceDetect = (fdMode != STATISTICS_FACE_DETECT_MODE_OFF)
                || (sceneMode == CONTROL_SCENE_MODE_FACE_PRIORITY);
        synchronized (mLock) {
            // Enable/disable face detection if it's changed since last time
            if (enableFaceDetect != mFaceDetectEnabled) {
                if (enableFaceDetect) {
                    mCamera.startFaceDetection();

                    if (DEBUG) {
                        Log.v(TAG, "processFaceDetectMode - start face detection");
                    }
                } else {
                    mCamera.stopFaceDetection();

                    if (DEBUG) {
                        Log.v(TAG, "processFaceDetectMode - stop face detection");
                    }

                    mFaces = null;
                }

                mFaceDetectEnabled = enableFaceDetect;
                mFaceDetectScenePriority = sceneMode == CONTROL_SCENE_MODE_FACE_PRIORITY;
                mFaceDetectReporting = fdMode != STATISTICS_FACE_DETECT_MODE_OFF;
            }
        }
    }

    /**
     * Update the {@code result} camera metadata map with the new value for the
     * {@code statistics.faces} and {@code statistics.faceDetectMode}.
     *
     * <p>Face detect callbacks are processed in the background, and each call to
     * {@link #mapResultFaces} will have the latest faces as reflected by the camera1 callbacks.</p>
     *
     * <p>If the scene mode was set to {@code FACE_PRIORITY} but face detection is disabled,
     * the camera will still run face detection in the background, but no faces will be reported
     * in the capture result.</p>
     *
     * @param result a non-{@code null} result
     * @param legacyRequest a non-{@code null} request (read-only)
     */
    public void mapResultFaces(CameraMetadataNative result, LegacyRequest legacyRequest) {
        checkNotNull(result, "result must not be null");
        checkNotNull(legacyRequest, "legacyRequest must not be null");

        Camera.Face[] faces, previousFaces;
        int fdMode;
        boolean fdScenePriority;
        synchronized (mLock) {
            fdMode = mFaceDetectReporting ?
                            STATISTICS_FACE_DETECT_MODE_SIMPLE : STATISTICS_FACE_DETECT_MODE_OFF;

            if (mFaceDetectReporting) {
                faces = mFaces;
            } else {
                faces = null;
            }

            fdScenePriority = mFaceDetectScenePriority;

            previousFaces = mFacesPrev;
            mFacesPrev = faces;
        }

        CameraCharacteristics characteristics = legacyRequest.characteristics;
        CaptureRequest request = legacyRequest.captureRequest;
        Size previewSize = legacyRequest.previewSize;
        Camera.Parameters params = legacyRequest.parameters;

        Rect activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        ZoomData zoomData = ParameterUtils.convertToLegacyZoom(activeArray,
                request.get(CaptureRequest.SCALER_CROP_REGION),
                request.get(CaptureRequest.CONTROL_ZOOM_RATIO),
                previewSize, params);

        List<Face> convertedFaces = new ArrayList<>();
        if (faces != null) {
            for (Camera.Face face : faces) {
                if (face != null) {
                    convertedFaces.add(
                            ParameterUtils.convertFaceFromLegacy(face, activeArray, zoomData));
                } else {
                    Log.w(TAG, "mapResultFaces - read NULL face from camera1 device");
                }
            }
        }

        if (DEBUG && previousFaces != faces) { // Log only in verbose and IF the faces changed
            Log.v(TAG, "mapResultFaces - changed to " + ListUtils.listToString(convertedFaces));
        }

        result.set(CaptureResult.STATISTICS_FACES, convertedFaces.toArray(new Face[0]));
        result.set(CaptureResult.STATISTICS_FACE_DETECT_MODE, fdMode);

        // Override scene mode with FACE_PRIORITY if the request was using FACE_PRIORITY
        if (fdScenePriority) {
            result.set(CaptureResult.CONTROL_SCENE_MODE, CONTROL_SCENE_MODE_FACE_PRIORITY);
        }
    }
}
