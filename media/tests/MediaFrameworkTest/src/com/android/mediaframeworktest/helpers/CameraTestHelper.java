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

package com.android.mediaframeworktest.helpers;

import junit.framework.Assert;

import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.test.InstrumentationRegistry;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class CameraTestHelper {

    public Camera mCamera;
    private String TAG = "CameraTestHelper";
    private static final int CAMERA_ID = 0;
    private static final long WAIT_GENERIC = 3 * 1000; // 3 seconds
    private static final long WAIT_ZOOM_ANIMATION = 5 * 1000; // 5 seconds
    protected static final String CAMERA_STRESS_IMAGES_DIRECTORY = "cameraStressImages";
    private static final String CAMERA_STRESS_IMAGES_PREFIX = "camera-stress-test";
    private final CameraErrorCallback mCameraErrorCallback = new CameraErrorCallback();

    private final class CameraErrorCallback implements android.hardware.Camera.ErrorCallback {
        public void onError(int error, android.hardware.Camera camera) {
            Assert.fail(String.format("Camera error, code: %d", error));
        }
    }

    private ShutterCallback shutterCallback = new ShutterCallback() {
        @Override
        public void onShutter() {
            Log.v(TAG, "Shutter");
        }
    };

    private PictureCallback rawCallback = new PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            Log.v(TAG, "Raw picture taken");
        }
    };

    private PictureCallback jpegCallback = new PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            FileOutputStream fos = null;

            try {
                Log.v(TAG, "JPEG picture taken");
                fos = new FileOutputStream(String.format("%s/%s/%s-%d.jpg",
                        InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getExternalFilesDir(null).getPath(), CAMERA_STRESS_IMAGES_DIRECTORY,
                        CAMERA_STRESS_IMAGES_PREFIX, System.currentTimeMillis()));
                fos.write(data);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "File not found: " + e.toString());
            } catch (IOException e) {
                Log.e(TAG, "Error accessing file: " + e.toString());
            } finally {
                try {
                    if (fos != null) {
                        fos.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing file: " + e.toString());
                }
            }
        }
    };

    /**
     * Helper method for prepping test
     */
    public void setupCameraTest() {
        // Create the test images directory if it doesn't exist
        File stressImagesDirectory = new File(String.format("%s/%s",
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null).getPath(), CAMERA_STRESS_IMAGES_DIRECTORY));
        if (!stressImagesDirectory.exists()) {
            stressImagesDirectory.mkdir();
        }

        mCamera = Camera.open(CAMERA_ID);
    }

    /**
     * Helper method for getting the available parameters of the default camera
     */
    public Parameters getCameraParameters() {
        mCamera = Camera.open(CAMERA_ID);
        Parameters params = mCamera.getParameters();
        mCamera.release();
        return params;
    }

    /**
     * Helper method for taking a photo
     */
    public void capturePhoto() throws Exception {
        mCamera.takePicture(shutterCallback, rawCallback, jpegCallback);
        Thread.sleep(WAIT_GENERIC);
        mCamera.stopPreview();
        mCamera.release();
    }

    /**
     * Helper method for cleaning up pics taken during tests
     */
    public void cleanupTestImages() {
        try {
            File stressImagesDirectory = new File(String.format("%s/%s",
                    InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getExternalFilesDir(null).getPath(), CAMERA_STRESS_IMAGES_DIRECTORY));
            File[] stressImages = stressImagesDirectory.listFiles();
            for (File f : stressImages) {
                f.delete();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security manager access violation: " + e.toString());
        }
    }

    /**
     * Helper method for setting the camera parameters
     */
    public void setParameters(Parameters params) {
        try {
            mCamera.setParameters(params);
        } catch (Exception e) {
            Log.e(TAG, "Error setting camera parameters");
        }
    }

    /**
     * Helper method for starting up the camera preview
     */
    public void startCameraPreview(SurfaceHolder surfaceHolder) throws Exception {
        mCamera.setErrorCallback(mCameraErrorCallback);
        mCamera.setPreviewDisplay(surfaceHolder);
        mCamera.startPreview();
        Thread.sleep(WAIT_GENERIC);
    }
}

