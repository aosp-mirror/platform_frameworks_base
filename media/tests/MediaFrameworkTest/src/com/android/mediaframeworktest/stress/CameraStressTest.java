/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.mediaframeworktest.stress;

import com.android.mediaframeworktest.MediaFrameworkTest;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.List;

import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.SurfaceHolder;
import com.android.mediaframeworktest.CameraStressTestRunner;

import junit.framework.Assert;

/**
 * Junit / Instrumentation test case for the camera zoom and scene mode APIs
 *
 * adb shell am instrument
 *  -e class com.android.mediaframeworktest.stress.CameraStressTest
 *  -w com.android.mediaframeworktest/.CameraStressTestRunner
 */
public class CameraStressTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "CameraStressTest";
    private Camera mCamera;

    private static final int CAMERA_ID = 0;
    private static final int NUMBER_OF_ZOOM_LOOPS = 100;
    private static final int NUMBER_OF_SCENE_MODE_LOOPS = 10;
    private static final long WAIT_GENERIC = 3 * 1000; // 3 seconds
    private static final long WAIT_TIMEOUT = 10 * 1000; // 10 seconds
    private static final long WAIT_ZOOM_ANIMATION = 5 * 1000; // 5 seconds
    private static final String CAMERA_STRESS_IMAGES_DIRECTORY = "cameraStressImages";
    private static final String CAMERA_STRESS_IMAGES_PREFIX = "camera-stress-test";
    private static final String CAMERA_STRESS_OUTPUT = "cameraStressOutput.txt";
    private final CameraErrorCallback mCameraErrorCallback = new CameraErrorCallback();

    private Thread mLooperThread;
    private Handler mHandler;

    private Writer mOutput;

    public CameraStressTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    protected void setUp() throws Exception {
        final Semaphore sem = new Semaphore(0);
        mLooperThread = new Thread() {
            @Override
            public void run() {
                Log.v(TAG, "starting looper");
                Looper.prepare();
                mHandler = new Handler();
                sem.release();
                Looper.loop();
                Log.v(TAG, "quit looper");
            }
        };
        mLooperThread.start();
        if (!sem.tryAcquire(WAIT_TIMEOUT, TimeUnit.MILLISECONDS)) {
            fail("Failed to start the looper.");
        }
        getActivity();
        super.setUp();

        File sdcard = Environment.getExternalStorageDirectory();

        // Create the test images directory if it doesn't exist
        File stressImagesDirectory = new File(String.format("%s/%s", sdcard,
                CAMERA_STRESS_IMAGES_DIRECTORY));
        if (!stressImagesDirectory.exists()) {
            stressImagesDirectory.mkdir();
        }

        // Start writing output file
        File stressOutFile = new File(String.format("%s/%s",sdcard, CAMERA_STRESS_OUTPUT));
        mOutput = new BufferedWriter(new FileWriter(stressOutFile, true));
        mOutput.write(this.getName() + ":\n");
    }

    @Override
    protected void tearDown() throws Exception {
        if (mHandler != null) {
            mHandler.getLooper().quit();
            mHandler = null;
        }
        if (mLooperThread != null) {
            mLooperThread.join(WAIT_TIMEOUT);
            if (mLooperThread.isAlive()) {
                fail("Failed to stop the looper.");
            }
            mLooperThread = null;
        }

        mOutput.write("\n\n");
        mOutput.close();

        super.tearDown();
    }

    private void runOnLooper(final Runnable command) throws InterruptedException {
        final Semaphore sem = new Semaphore(0);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    command.run();
                } finally {
                    sem.release();
                }
            }
        });
        if (!sem.tryAcquire(WAIT_TIMEOUT, TimeUnit.MILLISECONDS)) {
            fail("Failed to run the command on the looper.");
        }
    }

    private final class CameraErrorCallback implements android.hardware.Camera.ErrorCallback {
        public void onError(int error, android.hardware.Camera camera) {
            fail(String.format("Camera error, code: %d", error));
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
                        Environment.getExternalStorageDirectory(), CAMERA_STRESS_IMAGES_DIRECTORY,
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

    // Helper method for cleaning up pics taken during testStressCameraZoom
    private void cleanupStressTestImages() {
        try {
            File stressImagesDirectory = new File(String.format("%s/%s",
                    Environment.getExternalStorageDirectory(), CAMERA_STRESS_IMAGES_DIRECTORY));
            File[] zoomImages = null;

            FilenameFilter filter = new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.startsWith(CAMERA_STRESS_IMAGES_PREFIX);
                }
            };

            zoomImages = stressImagesDirectory.listFiles(filter);

            for (File f : zoomImages) {
                f.delete();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security manager access violation: " + e.toString());
        }
    }

    // Helper method for starting up the camera preview
    private void startCameraPreview(SurfaceHolder surfaceHolder) {
        try {
            mCamera.setErrorCallback(mCameraErrorCallback);
            mCamera.setPreviewDisplay(surfaceHolder);
            mCamera.startPreview();
            Thread.sleep(WAIT_GENERIC);
        } catch (IOException e) {
            Log.e(TAG, "Error setting preview display: " + e.toString());
        } catch (InterruptedException e) {
            Log.e(TAG, "Error waiting for preview to come up: " + e.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error starting up camera preview: " + e.toString());
        }
    }

    // Helper method for taking a photo
    private void capturePhoto() {
        try {
            mCamera.takePicture(shutterCallback, rawCallback, jpegCallback);
            Thread.sleep(WAIT_GENERIC);
            mCamera.stopPreview();
            mCamera.release();
        } catch (InterruptedException e) {
            Log.e(TAG, "Error waiting for photo to be taken: " + e.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error capturing photo: " + e.toString());
        }
    }

    // Test case for stressing the camera zoom in/out feature
    @LargeTest
    public void testStressCameraZoom() throws Exception {
        SurfaceHolder mSurfaceHolder;
        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        mOutput.write("Total number of loops: " + NUMBER_OF_ZOOM_LOOPS + "\n");

        try {
            Log.v(TAG, "Start preview");
            mOutput.write("No of loop: ");

            mCamera = Camera.open(CAMERA_ID);
            Camera.Parameters params = mCamera.getParameters();
            mCamera.release();

            if (!params.isSmoothZoomSupported() && !params.isZoomSupported()) {
                Log.v(TAG, "Device camera does not support zoom");
                fail("Camera zoom stress test failed");
            } else {
                Log.v(TAG, "Device camera does support zoom");

                int nextZoomLevel = 0;

                for (int i = 0; i < NUMBER_OF_ZOOM_LOOPS; i++) {
                    runOnLooper(new Runnable() {
                        @Override
                        public void run() {
                            mCamera = Camera.open(CAMERA_ID);
                        }
                    });

                    startCameraPreview(mSurfaceHolder);
                    params = mCamera.getParameters();
                    int currentZoomLevel = params.getZoom();

                    if (nextZoomLevel >= params.getMaxZoom()) {
                        nextZoomLevel = 0;
                    }
                    ++nextZoomLevel;

                    if (params.isSmoothZoomSupported()) {
                        mCamera.startSmoothZoom(nextZoomLevel);
                    } else {
                        params.setZoom(nextZoomLevel);
                        mCamera.setParameters(params);
                    }
                    Log.v(TAG, "Zooming from " + currentZoomLevel + " to " + nextZoomLevel);

                    // sleep allows for zoom animation to finish
                    Thread.sleep(WAIT_ZOOM_ANIMATION);
                    capturePhoto();

                    if (i == 0) {
                        mOutput.write(Integer.toString(i));
                    } else {
                        mOutput.write(", " + i);
                    }
                }
            }
            cleanupStressTestImages();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera zoom stress test Exception");
        }
    }

    // Test case for stressing the camera scene mode feature
    @LargeTest
    public void testStressCameraSceneModes() throws Exception {
        SurfaceHolder mSurfaceHolder;
        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();

        try {
            mCamera = Camera.open(CAMERA_ID);
            Camera.Parameters params = mCamera.getParameters();
            mCamera.release();
            List<String> supportedSceneModes = params.getSupportedSceneModes();
            assertNotNull("No scene modes supported", supportedSceneModes);

            mOutput.write("Total number of loops: " +
                    (NUMBER_OF_SCENE_MODE_LOOPS * supportedSceneModes.size()) + "\n");
            Log.v(TAG, "Start preview");
            mOutput.write("No of loop: ");

            for (int i = 0; i < supportedSceneModes.size(); i++) {
                for (int j = 0; j < NUMBER_OF_SCENE_MODE_LOOPS; j++) {
                    runOnLooper(new Runnable() {
                        @Override
                        public void run() {
                            mCamera = Camera.open(CAMERA_ID);
                        }
                    });

                    startCameraPreview(mSurfaceHolder);
                    Log.v(TAG, "Setting mode to " + supportedSceneModes.get(i));
                    params.setSceneMode(supportedSceneModes.get(i));
                    mCamera.setParameters(params);
                    capturePhoto();

                    if ((i == 0) && (j == 0)) {
                        mOutput.write(Integer.toString(j + i * NUMBER_OF_SCENE_MODE_LOOPS));
                    } else {
                        mOutput.write(", " + (j + i * NUMBER_OF_SCENE_MODE_LOOPS));
                    }
                }
            }
            cleanupStressTestImages();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera scene mode test Exception");
        }
    }
}
