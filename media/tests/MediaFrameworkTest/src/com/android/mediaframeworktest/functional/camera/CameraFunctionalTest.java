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

package com.android.mediaframeworktest.functional.camera;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.CameraTestHelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.FloatMath;
import android.util.Log;
import android.view.SurfaceHolder;
import com.android.mediaframeworktest.CameraStressTestRunner;

import junit.framework.Assert;

/**
 * Junit / Instrumentation test case for the following camera APIs:
 * - flash
 * - exposure compensation
 * - white balance
 * - focus mode
 *
 * adb shell am instrument
 *  -e class com.android.mediaframeworktest.functional.camera.CameraFunctionalTest
 *  -w com.android.mediaframework/.CameraStressTestRunner
 */
public class CameraFunctionalTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private static final long WAIT_TIMEOUT = 10 * 1000; // 10 seconds
    private CameraTestHelper mCameraTestHelper;
    private Handler mHandler;
    private Thread mLooperThread;
    private Writer mOutput;

    private String TAG = "CameraFunctionalTest";

    public CameraFunctionalTest() {
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

    /**
     * Functional test iterating on the range of supported exposure compensation levels
     */
    @LargeTest
    public void testFunctionalCameraExposureCompensation() throws Exception {
        try {
            SurfaceHolder surfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            Parameters params = mCameraTestHelper.getCameraParameters();

            int min = params.getMinExposureCompensation();
            int max = params.getMaxExposureCompensation();
            assertFalse("Adjusting exposure not supported", (max == 0 && min == 0));
            float step = params.getExposureCompensationStep();
            int stepsPerEV = (int) Math.round(Math.pow((double) step, -1));

            // only get integer values for exposure compensation
            for (int i = min; i <= max; i += stepsPerEV) {
                runOnLooper(new Runnable() {
                    @Override
                    public void run() {
                        mCameraTestHelper.setupCameraTest();
                    }
                });
                Log.v(TAG, "Setting exposure compensation index to " + i);
                params.setExposureCompensation(i);
                mCameraTestHelper.setParameters(params);
                mCameraTestHelper.startCameraPreview(surfaceHolder);
                mCameraTestHelper.capturePhoto();
            }
            mCameraTestHelper.cleanupTestImages();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera exposure compensation test Exception");
        }
    }

    /**
     * Functional test iterating on the various flash modes (on, off, auto, torch)
     */
    @LargeTest
    public void testFunctionalCameraFlashModes() throws Exception {
        try {
            SurfaceHolder surfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            Parameters params = mCameraTestHelper.getCameraParameters();
            List<String> supportedFlashModes = params.getSupportedFlashModes();
            assertNotNull("No flash modes supported", supportedFlashModes);

            for (int i = 0; i < supportedFlashModes.size(); i++) {
                runOnLooper(new Runnable() {
                    @Override
                    public void run() {
                        mCameraTestHelper.setupCameraTest();
                    }
                });
                Log.v(TAG, "Setting flash mode to " + supportedFlashModes.get(i));
                params.setFlashMode(supportedFlashModes.get(i));
                mCameraTestHelper.setParameters(params);
                mCameraTestHelper.startCameraPreview(surfaceHolder);
                mCameraTestHelper.capturePhoto();
            }
            mCameraTestHelper.cleanupTestImages();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera flash mode test Exception");
        }
    }

    /**
     * Functional test iterating on the various focus modes (auto, infinitiy, macro, etc.)
     */
    @LargeTest
    public void testFunctionalCameraFocusModes() throws Exception {
        try {
            SurfaceHolder surfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            Parameters params = mCameraTestHelper.getCameraParameters();
            List<String> supportedFocusModes = params.getSupportedFocusModes();
            assertNotNull("No focus modes supported", supportedFocusModes);

            for (int i = 0; i < supportedFocusModes.size(); i++) {
                runOnLooper(new Runnable() {
                    @Override
                    public void run() {
                        mCameraTestHelper.setupCameraTest();
                    }
                });
                Log.v(TAG, "Setting focus mode to: " + supportedFocusModes.get(i));
                params.setFocusMode(supportedFocusModes.get(i));
                mCameraTestHelper.setParameters(params);
                mCameraTestHelper.startCameraPreview(surfaceHolder);
                mCameraTestHelper.capturePhoto();
            }
            mCameraTestHelper.cleanupTestImages();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera focus modes test Exception");
        }
    }

    /**
     * Functional test iterating on the various white balances (auto, daylight, cloudy, etc.)
     */
    @LargeTest
    public void testFunctionalCameraWhiteBalance() throws Exception {
        try {
            SurfaceHolder surfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            Parameters params = mCameraTestHelper.getCameraParameters();
            List<String> supportedWhiteBalance = params.getSupportedWhiteBalance();
            assertNotNull("No white balance modes supported", supportedWhiteBalance);

            for (int i = 0; i < supportedWhiteBalance.size(); i++) {
                runOnLooper(new Runnable() {
                    @Override
                    public void run() {
                        mCameraTestHelper.setupCameraTest();
                    }
                });
                Log.v(TAG, "Setting white balance to: " + supportedWhiteBalance.get(i));
                params.setWhiteBalance(supportedWhiteBalance.get(i));
                mCameraTestHelper.setParameters(params);
                mCameraTestHelper.startCameraPreview(surfaceHolder);
                mCameraTestHelper.capturePhoto();
            }
            mCameraTestHelper.cleanupTestImages();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera focus modes test Exception");
        }
    }
}
