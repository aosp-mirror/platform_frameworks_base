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

import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.SurfaceHolder;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.List;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.helpers.CameraTestHelper;

/**
 * Junit / Instrumentation test case for camera API pairwise testing
 * Settings tested against: flash mode, exposure compensation, white balance,
 *  scene mode, picture size, and geotagging
 *
 * adb shell am instrument
 *  - e class com.android.mediaframeworktest.stress.CameraPairwiseTest
 *  - w com.android.mediaframeworktest/.CameraStressTestRunner
 */
public class CameraPairwiseTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private CameraTestHelper mCameraTestHelper;
    private Handler mHandler;
    private Thread mLooperThread;
    private String TAG = "CameraPairwiseTest";

    private static final long WAIT_TIMEOUT = 10 * 1000; // 10 seconds

    // coordinates of the Getty Museuem in Los Angeles
    private static final double MOCK_LATITUDE = 34.076621;
    private static final double MOCK_LONGITUDE = -118.473215;

    // camera setting enums
    public enum Flash { ON, OFF, AUTO };
    public enum Exposure { MIN, MAX, NONE };
    public enum WhiteBalance { DAYLIGHT, FLUORESCENT, CLOUDY, INCANDESCENT, AUTO };
    public enum SceneMode { SUNSET, ACTION, PARTY, NIGHT, AUTO };
    public enum PictureSize { SMALL, MEDIUM, LARGE };
    public enum Geotagging { ON, OFF };

    public CameraPairwiseTest() {
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
     * Flash: Auto / Exposure: None / WB: Daylight
     * Scene: Sunset / Pic: Medium / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario01() throws Exception {
        genericPairwiseTestCase(Flash.AUTO, Exposure.NONE, WhiteBalance.DAYLIGHT, SceneMode.SUNSET,
                PictureSize.MEDIUM, Geotagging.OFF);
    }

    /**
     * Flash: On / Exposure: Min / WB: Fluorescent
     * Scene: Auto / Pic: Large / Geo: on
     */
    @LargeTest
    public void testCameraPairwiseScenario02() throws Exception {
        genericPairwiseTestCase(Flash.ON, Exposure.MIN, WhiteBalance.FLUORESCENT, SceneMode.AUTO,
                PictureSize.LARGE, Geotagging.ON);
    }

    /**
     * Flash: Off / Exposure: Max / WB: Auto
     * Scene: Night / Pic: Small / Geo: on
     */
    @LargeTest
    public void testCameraPairwiseScenario03() throws Exception {
        genericPairwiseTestCase(Flash.OFF, Exposure.MAX, WhiteBalance.AUTO, SceneMode.NIGHT,
                PictureSize.SMALL, Geotagging.ON);
    }

    /**
     * Flash: Off / Exposure: Max / WB: Cloudy
     * Scene: Auto / Pic: Med / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario04() throws Exception {
        genericPairwiseTestCase(Flash.OFF, Exposure.MAX, WhiteBalance.CLOUDY, SceneMode.AUTO,
                PictureSize.MEDIUM, Geotagging.OFF);
    }

    /**
     * Flash: Auto / Exposure: Max / WB: Incandescent
     * Scene: Auto / Pic: Large / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario05() throws Exception {
        genericPairwiseTestCase(Flash.AUTO, Exposure.MAX, WhiteBalance.INCANDESCENT,
                SceneMode.AUTO, PictureSize.LARGE, Geotagging.OFF);
    }

    /**
     * Flash: On / Exposure: None / WB: Cloudy
     * Scene: Auto / Pic: Small / Geo: on
     */
    @LargeTest
    public void testCameraPairwiseScenario06() throws Exception {
        genericPairwiseTestCase(Flash.ON, Exposure.NONE, WhiteBalance.CLOUDY, SceneMode.AUTO,
                PictureSize.SMALL, Geotagging.ON);
    }

    /**
     * Flash: Auto / Exposure: Min / WB: Auto
     * Scene: Action / Pic: Small / Geo: on
     */
    @LargeTest
    public void testCameraPairwiseScenario07() throws Exception {
        genericPairwiseTestCase(Flash.AUTO, Exposure.MIN, WhiteBalance.AUTO, SceneMode.ACTION,
                PictureSize.SMALL, Geotagging.ON);
    }

    /**
     * Flash: On / Exposure: Min / WB: Auto
     * Scene: Action / Pic: Medium / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario08() throws Exception {
        genericPairwiseTestCase(Flash.ON, Exposure.MIN, WhiteBalance.AUTO, SceneMode.ACTION,
                PictureSize.MEDIUM, Geotagging.OFF);
    }

    /**
     * Flash: Off / Exposure: Min / WB: Auto
     * Scene: Night / Pic: Large / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario09() throws Exception {
        genericPairwiseTestCase(Flash.OFF, Exposure.MIN, WhiteBalance.AUTO, SceneMode.NIGHT,
                PictureSize.LARGE, Geotagging.OFF);
    }

    /**
     * Flash: Off / Exposure: Min / WB: Daylight
     * Scene: Sunset / Pic: Small / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario10() throws Exception {
        genericPairwiseTestCase(Flash.OFF, Exposure.MIN, WhiteBalance.DAYLIGHT, SceneMode.SUNSET,
                PictureSize.SMALL, Geotagging.OFF);
    }

    /**
     * Flash: On / Exposure: Max / WB: Daylight
     * Scene: Sunset / Pic: Large / Geo: on
     */
    @LargeTest
    public void testCameraPairwiseScenario11() throws Exception {
        genericPairwiseTestCase(Flash.ON, Exposure.MAX, WhiteBalance.DAYLIGHT, SceneMode.SUNSET,
                PictureSize.LARGE, Geotagging.ON);
    }

    /**
     * Flash: Auto / Exposure: Min / WB: Cloudy
     * Scene: Auto / Pic: Large / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario12() throws Exception {
        genericPairwiseTestCase(Flash.AUTO, Exposure.MIN, WhiteBalance.CLOUDY, SceneMode.AUTO,
                PictureSize.LARGE, Geotagging.OFF);
    }

    /**
     * Flash: Off / Exposure: None / WB: Auto
     * Scene: Party / Pic: Medium / Geo: on
     */
    @LargeTest
    public void testCameraPairwiseScenario13() throws Exception {
        genericPairwiseTestCase(Flash.OFF, Exposure.NONE, WhiteBalance.AUTO, SceneMode.PARTY,
                PictureSize.MEDIUM, Geotagging.ON);
    }

    /**
     * Flash: Auto / Exposure: None / WB: Auto
     * Scene: Night / Pic: Small / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario14() throws Exception {
        genericPairwiseTestCase(Flash.AUTO, Exposure.NONE, WhiteBalance.AUTO, SceneMode.NIGHT,
                PictureSize.SMALL, Geotagging.OFF);
    }

    /**
     * Flash: On / Exposure: None / WB: Incandescent
     * Scene: Auto / Pic: Medium / Geo: on
     */
    @LargeTest
    public void testCameraPairwiseScenario15() throws Exception {
        genericPairwiseTestCase(Flash.ON, Exposure.NONE, WhiteBalance.INCANDESCENT, SceneMode.AUTO,
                PictureSize.MEDIUM, Geotagging.ON);
    }

    /**
     * Flash: Auto / Exposure: Min / WB: Auto
     * Scene: Party / Pic: Small / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario16() throws Exception {
        genericPairwiseTestCase(Flash.AUTO, Exposure.MIN, WhiteBalance.AUTO, SceneMode.PARTY,
                PictureSize.SMALL, Geotagging.OFF);
    }

    /**
     * Flash: Off / Exposure: Min / WB: Incandescent
     * Scene: Auto / Pic: Small / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario17() throws Exception {
        genericPairwiseTestCase(Flash.OFF, Exposure.MIN, WhiteBalance.INCANDESCENT, SceneMode.AUTO,
                PictureSize.SMALL, Geotagging.OFF);
    }

    /**
     * Flash: On / Exposure: None / WB: Auto
     * Scene: Party / Pic: Large / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario18() throws Exception {
        genericPairwiseTestCase(Flash.ON, Exposure.NONE, WhiteBalance.AUTO, SceneMode.PARTY,
                PictureSize.LARGE, Geotagging.OFF);
    }

    /**
     * Flash Off / Exposure: None / WB: Auto
     * Scene: Action / Pic: Large / Geo: off
     */
    @LargeTest
    public void testCameraPairwiseScenario19() throws Exception {
        genericPairwiseTestCase(Flash.OFF, Exposure.NONE, WhiteBalance.AUTO, SceneMode.ACTION,
                PictureSize.LARGE, Geotagging.OFF);
    }

    /**
     * Flash: Off / Exposure: Max / WB: Fluorescent
     * Scene: Auto / Pic: Medium / Geo: Off
     */
    @LargeTest
    public void testCameraPairwiseScenario20() throws Exception {
        genericPairwiseTestCase(Flash.OFF, Exposure.MAX, WhiteBalance.FLUORESCENT, SceneMode.AUTO,
                PictureSize.MEDIUM, Geotagging.OFF);
    }

    /**
     * Flash: Off / Exposure: Min / WB: Auto
     * Scene: Auto / Pic: Medium / Geo: off
     */
    public void testCameraPairwiseScenario21() throws Exception {
        genericPairwiseTestCase(Flash.OFF, Exposure.MIN, WhiteBalance.AUTO, SceneMode.AUTO,
                PictureSize.MEDIUM, Geotagging.OFF);
    }

    /**
     * Flash: On / Exposure: Max / WB: Auto
     * Scene: Action / Pic: Small / Geo: off
     */
    public void testCameraPairwiseScenario22() throws Exception {
        genericPairwiseTestCase(Flash.ON, Exposure.MAX, WhiteBalance.AUTO, SceneMode.ACTION,
                PictureSize.SMALL, Geotagging.OFF);
    }

    /**
     * Flash: On / Exposure: Max / WB: Auto
     * Scene: Night / Pic: Medium / Geo: on
     */
    public void testCameraPairwiseScenario23() throws Exception {
        genericPairwiseTestCase(Flash.ON, Exposure.MAX, WhiteBalance.AUTO, SceneMode.NIGHT,
                PictureSize.MEDIUM, Geotagging.ON);
    }

    /**
     * Flash: Auto / Exposure: None / WB: Fluorescent
     * Scene: Auto / Pic: Small / Geo: on
     */
    public void testCameraPairwiseScenario24() throws Exception {
        genericPairwiseTestCase(Flash.AUTO, Exposure.NONE, WhiteBalance.FLUORESCENT,
                SceneMode.AUTO, PictureSize.SMALL, Geotagging.ON);
    }

    /**
     * Flash: Auto / Exposure: Max / WB: Daylight
     * Scene: Auto / Pic: Medium / Geo: off
     */
    public void testCameraPairwiseScenario25() throws Exception {
        genericPairwiseTestCase(Flash.AUTO, Exposure.MAX, WhiteBalance.DAYLIGHT, SceneMode.AUTO,
                PictureSize.MEDIUM, Geotagging.OFF);
    }

    /**
     * Flash: Auto / Exposure: Max / WB: Auto
     * Scene: Party / Pic: Medium / Geo: on
     */
    public void testCameraPairwiseScenario26() throws Exception {
        genericPairwiseTestCase(Flash.AUTO, Exposure.MAX, WhiteBalance.AUTO, SceneMode.PARTY,
                PictureSize.MEDIUM, Geotagging.ON);
    }

    /**
     * Generic pairwise test method
     */
    private void genericPairwiseTestCase(Flash flash, Exposure exposure, WhiteBalance whitebalance,
            SceneMode scenemode, PictureSize picturesize, Geotagging geotagging) throws Exception {
        try {
            SurfaceHolder surfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            Camera.Parameters params = mCameraTestHelper.getCameraParameters();

            runOnLooper(new Runnable() {
                @Override
                public void run() {
                    mCameraTestHelper.setupCameraTest();
                }
            });

            // Configure flash setting
            switch (flash) {
                case ON:
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
                    break;
                case OFF:
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    break;
                case AUTO:
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
                    break;
            }

            // Configure exposure setting
            switch (exposure) {
                case MIN:
                    params.setExposureCompensation(params.getMinExposureCompensation());
                    break;
                case MAX:
                    params.setExposureCompensation(params.getMaxExposureCompensation());
                    break;
                case NONE:
                    params.setExposureCompensation(0);
                    break;
            }

            // Configure white balance setting
            switch (whitebalance) {
                case DAYLIGHT:
                    params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_DAYLIGHT);
                    break;
                case FLUORESCENT:
                    params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_FLUORESCENT);
                    break;
                case INCANDESCENT:
                    params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_INCANDESCENT);
                    break;
                case CLOUDY:
                    params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_CLOUDY_DAYLIGHT);
                    break;
                case AUTO:
                    params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
                    break;
            }

            // Configure scene mode setting
            switch (scenemode) {
                case SUNSET:
                    params.setSceneMode(Camera.Parameters.SCENE_MODE_SUNSET);
                    break;
                case ACTION:
                    params.setSceneMode(Camera.Parameters.SCENE_MODE_ACTION);
                    break;
                case PARTY:
                    params.setSceneMode(Camera.Parameters.SCENE_MODE_PARTY);
                    break;
                case NIGHT:
                    params.setSceneMode(Camera.Parameters.SCENE_MODE_NIGHT);
                    break;
                case AUTO:
                    params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
                    break;
            }

            // Configure picture size setting
            List<Camera.Size> supportedPictureSizes = params.getSupportedPictureSizes();
            int mid = (int) Math.floor(supportedPictureSizes.size() / 2);
            int low = supportedPictureSizes.size() - 1;
            switch (picturesize) {
                case SMALL:
                    params.setPictureSize(supportedPictureSizes.get(low).width,
                            supportedPictureSizes.get(low).height);
                    break;
                case MEDIUM:
                    params.setPictureSize(supportedPictureSizes.get(mid).width,
                            supportedPictureSizes.get(mid).height);
                    break;
                case LARGE:
                    params.setPictureSize(supportedPictureSizes.get(0).width,
                            supportedPictureSizes.get(mid).height);
                    break;
            }

            // Configure geotagging setting
            switch (geotagging) {
                case ON:
                    params.setGpsLatitude(MOCK_LATITUDE);
                    params.setGpsLongitude(MOCK_LONGITUDE);
                    break;
                case OFF:
                    break;
            }

            mCameraTestHelper.setParameters(params);
            mCameraTestHelper.startCameraPreview(surfaceHolder);
            mCameraTestHelper.capturePhoto();
            mCameraTestHelper.cleanupTestImages();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Test case failed");
        }
    }
}
