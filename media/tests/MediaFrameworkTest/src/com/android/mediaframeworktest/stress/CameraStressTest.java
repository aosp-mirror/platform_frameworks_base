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
import com.android.mediaframeworktest.helpers.CameraTestHelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.List;

import android.hardware.Camera.Parameters;
import android.os.Handler;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.test.InstrumentationRegistry;

/**
 * Junit / Instrumentation test case for the following camera APIs:
 *  - camera zoom
 *  - scene mode
 *
 * adb shell am instrument
 *  -e class com.android.mediaframeworktest.stress.CameraStressTest
 *  -w com.android.mediaframeworktest/.CameraStressTestRunner
 */
public class CameraStressTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {

    private static final int NUMBER_OF_SCENE_MODE_LOOPS = 10;
    private static final int NUMBER_OF_ZOOM_LOOPS = 100;
    private static final long WAIT_TIMEOUT = 10 * 1000; // 10 seconds
    private static final String CAMERA_STRESS_OUTPUT = "cameraStressOutput.txt";

    private CameraTestHelper mCameraTestHelper;
    private Handler mHandler;
    private Thread mLooperThread;
    private Writer mOutput;

    private String TAG = "CameraStressTest";

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

        mCameraTestHelper = new CameraTestHelper();
        File stressOutFile = new File(String.format("%s/%s",
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null).getPath(), CAMERA_STRESS_OUTPUT));
        mOutput = new BufferedWriter(new FileWriter(stressOutFile, true));
        mOutput.write(this.getName() + "\n");
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

    /**
     * Stress test iterating on the various scene modes (action, night, party, etc.)
     */
    @LargeTest
    public void testStressCameraSceneModes() throws Exception {
        try {
            SurfaceHolder surfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            Parameters params = mCameraTestHelper.getCameraParameters();
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
                            mCameraTestHelper.setupCameraTest();
                        }
                    });
                    Log.v(TAG, "Setting scene mode to " + supportedSceneModes.get(i));
                    params.setSceneMode(supportedSceneModes.get(i));
                    mCameraTestHelper.setParameters(params);
                    mCameraTestHelper.startCameraPreview(surfaceHolder);
                    mCameraTestHelper.capturePhoto();

                    if (i == 0 && j == 0) {
                        mOutput.write(Integer.toString(j + i * NUMBER_OF_SCENE_MODE_LOOPS));
                    } else {
                        mOutput.write(", " + (j + i * NUMBER_OF_SCENE_MODE_LOOPS));
                    }
                }
            }
            mCameraTestHelper.cleanupTestImages();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera scene mode test Exception");
        }
    }

    /**
     * Stress test iterating on the range of supported camera zoom levels
     */
    @LargeTest
    public void testStressCameraZoom() throws Exception {
        try {
            SurfaceHolder surfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            Parameters params = mCameraTestHelper.getCameraParameters();

            if (!params.isSmoothZoomSupported() && !params.isZoomSupported()) {
                Log.v(TAG, "Device camera does not support zoom");
                fail("Camera zoom stress test failed due to unsupported feature");
            } else {
                Log.v(TAG, "Device camera does support zoom");
                Log.v(TAG, "Start preview");
                mOutput.write("Total number of loops: " + NUMBER_OF_ZOOM_LOOPS + "\n");
                mOutput.write("No of loops: ");

                int nextZoomLevel = 0;

                for (int i = 0; i < NUMBER_OF_ZOOM_LOOPS; i++) {
                    runOnLooper(new Runnable() {
                        @Override
                        public void run() {
                            mCameraTestHelper.setupCameraTest();
                        }
                    });

                    mCameraTestHelper.startCameraPreview(surfaceHolder);
                    params = mCameraTestHelper.mCamera.getParameters();
                    int currentZoomLevel = params.getZoom();

                    if (nextZoomLevel >= params.getMaxZoom()) {
                        nextZoomLevel = 0;
                    }
                    ++nextZoomLevel;

                    if (params.isSmoothZoomSupported()) {
                        mCameraTestHelper.mCamera.startSmoothZoom(nextZoomLevel);
                    } else {
                        params.setZoom(nextZoomLevel);
                        mCameraTestHelper.setParameters(params);
                    }
                    mCameraTestHelper.capturePhoto();

                    if (i == 0) {
                        mOutput.write(Integer.toString(i));
                    } else {
                        mOutput.write(", " + i);
                    }
                }
            }
            mCameraTestHelper.cleanupTestImages();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera zoom stress test Exception");
        }
    }
}
