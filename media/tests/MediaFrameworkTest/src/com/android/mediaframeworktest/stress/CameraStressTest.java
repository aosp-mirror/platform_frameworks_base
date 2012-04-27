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
 * Junit / Instrumentation test case for the camera zoom api
 *
 * adb shell am instrument
 *  -e class com.android.mediaframeworktest.stress.CameraStressTest
 *  -w com.android.mediaframeworktest/.CameraStressTestRunner
 */
public class CameraStressTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "CameraStressTest";
    private Camera mCamera;

    private static final int NUMBER_OF_ZOOM_LOOPS = 100;
    private static final long WAIT_GENERIC = 3 * 1000; // 3 seconds
    private static final long WAIT_TIMEOUT = 10 * 1000; // 10 seconds
    private static final long WAIT_ZOOM_ANIMATION = 5 * 1000; // 5 seconds
    private static final String CAMERA_STRESS_OUTPUT =
            "/sdcard/cameraStressOutput.txt";
    private static final int CAMERA_ID = 0;
    private final CameraErrorCallback mCameraErrorCallback = new CameraErrorCallback();

    private Thread mLooperThread;
    private Handler mHandler;

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
            if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
                assertTrue("Camera test mediaserver died", false);
            }
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
                fos = new FileOutputStream(String.format("%s/zoom-test-%d.jpg",
                        Environment.getExternalStorageDirectory(), System.currentTimeMillis()));
                fos.write(data);
            }
            catch (FileNotFoundException e) {
                Log.v(TAG, "File not found: " + e.toString());
            }
            catch (IOException e) {
                Log.v(TAG, "Error accessing file: " + e.toString());
            }
            finally {
                try {
                    if (fos != null) {
                        fos.close();
                    }
                }
                catch (IOException e) {
                    Log.v(TAG, "Error closing file: " + e.toString());
                }
            }
        }
    };

    // Helper method for cleaning up pics taken during testStressCameraZoom
    private void cleanupZoomImages() {
        try {
            File sdcard = Environment.getExternalStorageDirectory();
            File[] zoomImages = null;

            FilenameFilter filter = new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.startsWith("zoom-test-");
                }
            };

            zoomImages = sdcard.listFiles(filter);

            for (File f : zoomImages) {
                f.delete();
            }
        }
        catch (SecurityException e) {
            Log.v(TAG, "Security manager access violation: " + e.toString());
        }
    }

    // Test case for stressing the camera zoom in/out feature
    @LargeTest
    public void testStressCameraZoom() throws Exception {
        SurfaceHolder mSurfaceHolder;
        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        File stressOutFile = new File(CAMERA_STRESS_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(stressOutFile, true));
        output.write("Camera zoom stress:\n");
        output.write("Total number of loops: " +  NUMBER_OF_ZOOM_LOOPS + "\n");

        try {
            Log.v(TAG, "Start preview");
            output.write("No of loop: ");

            mCamera = Camera.open(CAMERA_ID);
            Camera.Parameters params = mCamera.getParameters();
            mCamera.release();

            if (!params.isSmoothZoomSupported() && !params.isZoomSupported()) {
                Log.v(TAG, "Device camera does not support zoom");
                assertTrue("Camera zoom stress test", false);
            }
            else {
                Log.v(TAG, "Device camera does support zoom");

                int nextZoomLevel = 0;

                for (int i = 0; i < NUMBER_OF_ZOOM_LOOPS; i++) {
                    runOnLooper(new Runnable() {
                        @Override
                        public void run() {
                            mCamera = Camera.open(CAMERA_ID);
                        }
                    });

                    mCamera.setErrorCallback(mCameraErrorCallback);
                    mCamera.setPreviewDisplay(mSurfaceHolder);
                    mCamera.startPreview();
                    Thread.sleep(WAIT_GENERIC);

                    params = mCamera.getParameters();
                    int currentZoomLevel = params.getZoom();

                    if (nextZoomLevel >= params.getMaxZoom()) {
                        nextZoomLevel = 0;
                    }
                    ++nextZoomLevel;

                    if (params.isSmoothZoomSupported()) {
                        mCamera.startSmoothZoom(nextZoomLevel);
                    }
                    else {
                        params.setZoom(nextZoomLevel);
                        mCamera.setParameters(params);
                    }
                    Log.v(TAG, "Zooming from " + currentZoomLevel + " to " + nextZoomLevel);

                    // sleep allows for zoom animation to finish
                    Thread.sleep(WAIT_ZOOM_ANIMATION);

                    // take picture
                    mCamera.takePicture(shutterCallback, rawCallback, jpegCallback);
                    Thread.sleep(WAIT_GENERIC);
                    mCamera.stopPreview();
                    mCamera.release();
                    output.write(" ," + i);
                }
            }

            cleanupZoomImages();
        }
        catch (Exception e) {
            assertTrue("Camera zoom stress test Exception", false);
            Log.v(TAG, e.toString());
        }
        output.write("\n\n");
        output.close();
    }
}
