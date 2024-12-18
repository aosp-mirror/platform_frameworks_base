/*
 * Copyright 2016 The Android Open Source Project
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

import static android.hardware.camera2.CameraCharacteristics.CONTROL_AE_MODE_OFF;
import static android.hardware.camera2.CameraCharacteristics.CONTROL_AE_MODE_ON;
import static android.hardware.camera2.CameraCharacteristics.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
import static android.hardware.camera2.CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH;
import static android.hardware.camera2.CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE;

import static com.android.mediaframeworktest.helpers.CameraTestUtils.getValueNotNull;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Log;
import android.util.Size;

import com.android.mediaframeworktest.Camera2SurfaceViewTestCase;
import com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleCaptureCallback;

import java.util.Arrays;

/**
 * <p>
 * Basic test for camera CaptureRequest key controls.
 * </p>
 * <p>
 * Several test categories are covered: manual sensor control, 3A control,
 * manual ISP control and other per-frame control and synchronization.
 * </p>
 *
 * adb shell am instrument \
 *    -e class com.android.mediaframeworktest.stress.Camera2CaptureRequestTest#testAeModeAndLock \
 *    -e iterations 10 \
 *    -e waitIntervalMs 1000 \
 *    -e resultToFile false \
 *    -r -w com.android.mediaframeworktest/androidx.test.runner.AndroidJUnitRunner
 */
public class Camera2CaptureRequestTest extends Camera2SurfaceViewTestCase {
    private static final String TAG = "CaptureRequestTest";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    /** 30ms exposure time must be supported by full capability devices. */
    private static final long DEFAULT_EXP_TIME_NS = 30000000L;
    private static final int DEFAULT_SENSITIVITY = 100;
    private static final long EXPOSURE_TIME_ERROR_MARGIN_NS = 100000L; // 100us, Approximation.
    private static final float EXPOSURE_TIME_ERROR_MARGIN_RATE = 0.03f; // 3%, Approximation.
    private static final float SENSITIVITY_ERROR_MARGIN_RATE = 0.06f; // 6%, Approximation.
    private static final int DEFAULT_NUM_EXPOSURE_TIME_STEPS = 3;
    private static final int DEFAULT_NUM_SENSITIVITY_STEPS = 16;
    private static final int DEFAULT_SENSITIVITY_STEP_SIZE = 100;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test AE mode and lock.
     *
     * <p>
     * For AE lock, when it is locked, exposure parameters shouldn't be changed.
     * For AE modes, each mode should satisfy the per frame controls defined in
     * API specifications.
     * </p>
     */
    public void testAeModeAndLock() throws Exception {
        for (int i = 0; i < mCameraIds.length; i++) {
            try {
                openDevice(mCameraIds[i]);
                if (!mStaticInfo.isColorOutputSupported()) {
                    Log.i(TAG, "Camera " + mCameraIds[i] +
                            " does not support color outputs, skipping");
                    continue;
                }

                Size maxPreviewSz = mOrderedPreviewSizes.get(0); // Max preview size.

                // Update preview surface with given size for all sub-tests.
                updatePreviewSurface(maxPreviewSz);

                // Test iteration starts...
                for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                    Log.v(TAG, String.format("AE mode and lock: %d/%d", iteration + 1,
                            getIterationCount()));

                    // Test aeMode and lock
                    int[] aeModes = mStaticInfo.getAeAvailableModesChecked();
                    for (int mode : aeModes) {
                        aeModeAndLockTestByMode(mode);
                    }
                    getResultPrinter().printStatus(getIterationCount(), iteration + 1, mCameraIds[i]);
                    Thread.sleep(getTestWaitIntervalMs());
                }
            } finally {
                closeDevice();
            }
        }
    }

    /**
     * Test the all available AE modes and AE lock.
     * <p>
     * For manual AE mode, test iterates through different sensitivities and
     * exposure times, validate the result exposure time correctness. For
     * CONTROL_AE_MODE_ON_ALWAYS_FLASH mode, the AE lock and flash are tested.
     * For the rest of the AUTO mode, AE lock is tested.
     * </p>
     *
     * @param mode
     */
    private void aeModeAndLockTestByMode(int mode)
            throws Exception {
        switch (mode) {
            case CONTROL_AE_MODE_OFF:
                if (mStaticInfo.isCapabilitySupported(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                    // Test manual exposure control.
                    aeManualControlTest();
                } else {
                    Log.w(TAG,
                            "aeModeAndLockTestByMode - can't test AE mode OFF without " +
                            "manual sensor control");
                }
                break;
            case CONTROL_AE_MODE_ON:
            case CONTROL_AE_MODE_ON_AUTO_FLASH:
            case CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE:
            case CONTROL_AE_MODE_ON_ALWAYS_FLASH:
                // Test AE lock for above AUTO modes.
                aeAutoModeTestLock(mode);
                break;
            default:
                throw new UnsupportedOperationException("Unhandled AE mode " + mode);
        }
    }

    /**
     * Test AE auto modes.
     * <p>
     * Use single request rather than repeating request to test AE lock per frame control.
     * </p>
     */
    private void aeAutoModeTestLock(int mode) throws Exception {
        CaptureRequest.Builder requestBuilder =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        if (mStaticInfo.isAeLockSupported()) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        }
        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, mode);
        configurePreviewOutput(requestBuilder);

        final int MAX_NUM_CAPTURES_DURING_LOCK = 5;
        for (int i = 1; i <= MAX_NUM_CAPTURES_DURING_LOCK; i++) {
            autoAeMultipleCapturesThenTestLock(requestBuilder, mode, i);
        }
    }

    /**
     * Issue multiple auto AE captures, then lock AE, validate the AE lock vs.
     * the first capture result after the AE lock. The right AE lock behavior is:
     * When it is locked, it locks to the current exposure value, and all subsequent
     * request with lock ON will have the same exposure value locked.
     */
    private void autoAeMultipleCapturesThenTestLock(
            CaptureRequest.Builder requestBuilder, int aeMode, int numCapturesDuringLock)
            throws Exception {
        if (numCapturesDuringLock < 1) {
            throw new IllegalArgumentException("numCapturesBeforeLock must be no less than 1");
        }
        if (VERBOSE) {
            Log.v(TAG, "Camera " + mCamera.getId() + ": Testing auto AE mode and lock for mode "
                    + aeMode + " with " + numCapturesDuringLock + " captures before lock");
        }

        final int NUM_CAPTURES_BEFORE_LOCK = 2;
        SimpleCaptureCallback listener =  new SimpleCaptureCallback();

        CaptureResult[] resultsDuringLock = new CaptureResult[numCapturesDuringLock];
        boolean canSetAeLock = mStaticInfo.isAeLockSupported();

        // Reset the AE lock to OFF, since we are reusing this builder many times
        if (canSetAeLock) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        }

        // Just send several captures with auto AE, lock off.
        CaptureRequest request = requestBuilder.build();
        for (int i = 0; i < NUM_CAPTURES_BEFORE_LOCK; i++) {
            mSession.capture(request, listener, mHandler);
        }
        waitForNumResults(listener, NUM_CAPTURES_BEFORE_LOCK);

        if (!canSetAeLock) {
            // Without AE lock, the remaining tests items won't work
            return;
        }

        // Then fire several capture to lock the AE.
        requestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);

        int requestCount = captureRequestsSynchronized(
                requestBuilder.build(), numCapturesDuringLock, listener, mHandler);

        int[] sensitivities = new int[numCapturesDuringLock];
        long[] expTimes = new long[numCapturesDuringLock];
        Arrays.fill(sensitivities, -1);
        Arrays.fill(expTimes, -1L);

        // Get the AE lock on result and validate the exposure values.
        waitForNumResults(listener, requestCount - numCapturesDuringLock);
        for (int i = 0; i < resultsDuringLock.length; i++) {
            resultsDuringLock[i] = listener.getCaptureResult(WAIT_FOR_RESULT_TIMEOUT_MS);
        }

        for (int i = 0; i < numCapturesDuringLock; i++) {
            mCollector.expectKeyValueEquals(
                    resultsDuringLock[i], CaptureResult.CONTROL_AE_LOCK, true);
        }

        // Can't read manual sensor/exposure settings without manual sensor
        if (mStaticInfo.isCapabilitySupported(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS)) {
            int sensitivityLocked =
                    getValueNotNull(resultsDuringLock[0], CaptureResult.SENSOR_SENSITIVITY);
            long expTimeLocked =
                    getValueNotNull(resultsDuringLock[0], CaptureResult.SENSOR_EXPOSURE_TIME);
            for (int i = 1; i < resultsDuringLock.length; i++) {
                mCollector.expectKeyValueEquals(
                        resultsDuringLock[i], CaptureResult.SENSOR_EXPOSURE_TIME, expTimeLocked);
                mCollector.expectKeyValueEquals(
                        resultsDuringLock[i], CaptureResult.SENSOR_SENSITIVITY, sensitivityLocked);
            }
        }
    }

    /**
     * Iterate through exposure times and sensitivities for manual AE control.
     * <p>
     * Use single request rather than repeating request to test manual exposure
     * value change per frame control.
     * </p>
     */
    private void aeManualControlTest()
            throws Exception {
        CaptureRequest.Builder requestBuilder =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CONTROL_AE_MODE_OFF);
        configurePreviewOutput(requestBuilder);
        SimpleCaptureCallback listener =  new SimpleCaptureCallback();

        long[] expTimes = getExposureTimeTestValues();
        int[] sensitivities = getSensitivityTestValues();
        // Submit single request at a time, then verify the result.
        for (int i = 0; i < expTimes.length; i++) {
            for (int j = 0; j < sensitivities.length; j++) {
                if (VERBOSE) {
                    Log.v(TAG, "Camera " + mCamera.getId() + ": Testing sensitivity "
                            + sensitivities[j] + ", exposure time " + expTimes[i] + "ns");
                }

                changeExposure(requestBuilder, expTimes[i], sensitivities[j]);
                mSession.capture(requestBuilder.build(), listener, mHandler);

                // make sure timeout is long enough for long exposure time
                long timeout = WAIT_FOR_RESULT_TIMEOUT_MS + expTimes[i];
                CaptureResult result = listener.getCaptureResult(timeout);
                long resultExpTime = getValueNotNull(result, CaptureResult.SENSOR_EXPOSURE_TIME);
                int resultSensitivity = getValueNotNull(result, CaptureResult.SENSOR_SENSITIVITY);
                validateExposureTime(expTimes[i], resultExpTime);
                validateSensitivity(sensitivities[j], resultSensitivity);
                validateFrameDurationForCapture(result);
            }
        }
        // TODO: Add another case to test where we can submit all requests, then wait for
        // results, which will hide the pipeline latency. this is not only faster, but also
        // test high speed per frame control and synchronization.
    }

    //----------------------------------------------------------------
    //---------Below are common functions for all tests.--------------
    //----------------------------------------------------------------

    /**
     * Enable exposure manual control and change exposure and sensitivity and
     * clamp the value into the supported range.
     */
    private void changeExposure(CaptureRequest.Builder requestBuilder,
            long expTime, int sensitivity) {
        // Check if the max analog sensitivity is available and no larger than max sensitivity.
        // The max analog sensitivity is not actually used here. This is only an extra check.
        mStaticInfo.getMaxAnalogSensitivityChecked();

        expTime = mStaticInfo.getExposureClampToRange(expTime);
        sensitivity = mStaticInfo.getSensitivityClampToRange(sensitivity);

        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CONTROL_AE_MODE_OFF);
        requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expTime);
        requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);
    }

    /**
     * Get the exposure time array that contains multiple exposure time steps in
     * the exposure time range.
     */
    private long[] getExposureTimeTestValues() {
        long[] testValues = new long[DEFAULT_NUM_EXPOSURE_TIME_STEPS + 1];
        long maxExpTime = mStaticInfo.getExposureMaximumOrDefault(DEFAULT_EXP_TIME_NS);
        long minExpTime = mStaticInfo.getExposureMinimumOrDefault(DEFAULT_EXP_TIME_NS);

        long range = maxExpTime - minExpTime;
        double stepSize = range / (double)DEFAULT_NUM_EXPOSURE_TIME_STEPS;
        for (int i = 0; i < testValues.length; i++) {
            testValues[i] = maxExpTime - (long)(stepSize * i);
            testValues[i] = mStaticInfo.getExposureClampToRange(testValues[i]);
        }

        return testValues;
    }

    /**
     * Get the sensitivity array that contains multiple sensitivity steps in the
     * sensitivity range.
     * <p>
     * Sensitivity number of test values is determined by
     * {@value #DEFAULT_SENSITIVITY_STEP_SIZE} and sensitivity range, and
     * bounded by {@value #DEFAULT_NUM_SENSITIVITY_STEPS}.
     * </p>
     */
    private int[] getSensitivityTestValues() {
        int maxSensitivity = mStaticInfo.getSensitivityMaximumOrDefault(
                DEFAULT_SENSITIVITY);
        int minSensitivity = mStaticInfo.getSensitivityMinimumOrDefault(
                DEFAULT_SENSITIVITY);

        int range = maxSensitivity - minSensitivity;
        int stepSize = DEFAULT_SENSITIVITY_STEP_SIZE;
        int numSteps = range / stepSize;
        // Bound the test steps to avoid supper long test.
        if (numSteps > DEFAULT_NUM_SENSITIVITY_STEPS) {
            numSteps = DEFAULT_NUM_SENSITIVITY_STEPS;
            stepSize = range / numSteps;
        }
        int[] testValues = new int[numSteps + 1];
        for (int i = 0; i < testValues.length; i++) {
            testValues[i] = maxSensitivity - stepSize * i;
            testValues[i] = mStaticInfo.getSensitivityClampToRange(testValues[i]);
        }

        return testValues;
    }

    /**
     * Validate the AE manual control exposure time.
     *
     * <p>Exposure should be close enough, and only round down if they are not equal.</p>
     *
     * @param request Request exposure time
     * @param result Result exposure time
     */
    private void validateExposureTime(long request, long result) {
        long expTimeDelta = request - result;
        long expTimeErrorMargin = (long)(Math.max(EXPOSURE_TIME_ERROR_MARGIN_NS, request
                * EXPOSURE_TIME_ERROR_MARGIN_RATE));
        // First, round down not up, second, need close enough.
        mCollector.expectTrue("Exposture time is invalid for AE manaul control test, request: "
                + request + " result: " + result,
                expTimeDelta < expTimeErrorMargin && expTimeDelta >= 0);
    }

    /**
     * Validate AE manual control sensitivity.
     *
     * @param request Request sensitivity
     * @param result Result sensitivity
     */
    private void validateSensitivity(int request, int result) {
        float sensitivityDelta = request - result;
        float sensitivityErrorMargin = request * SENSITIVITY_ERROR_MARGIN_RATE;
        // First, round down not up, second, need close enough.
        mCollector.expectTrue("Sensitivity is invalid for AE manaul control test, request: "
                + request + " result: " + result,
                sensitivityDelta < sensitivityErrorMargin && sensitivityDelta >= 0);
    }

    /**
     * Validate frame duration for a given capture.
     *
     * <p>Frame duration should be longer than exposure time.</p>
     *
     * @param result The capture result for a given capture
     */
    private void validateFrameDurationForCapture(CaptureResult result) {
        long expTime = getValueNotNull(result, CaptureResult.SENSOR_EXPOSURE_TIME);
        long frameDuration = getValueNotNull(result, CaptureResult.SENSOR_FRAME_DURATION);
        if (VERBOSE) {
            Log.v(TAG, "frame duration: " + frameDuration + " Exposure time: " + expTime);
        }

        mCollector.expectTrue(String.format("Frame duration (%d) should be longer than exposure"
                + " time (%d) for a given capture", frameDuration, expTime),
                frameDuration >= expTime);

        validatePipelineDepth(result);
    }

    /**
     * Validate the pipeline depth result.
     *
     * @param result The capture result to get pipeline depth data
     */
    private void validatePipelineDepth(CaptureResult result) {
        final byte MIN_PIPELINE_DEPTH = 1;
        byte maxPipelineDepth = mStaticInfo.getPipelineMaxDepthChecked();
        Byte pipelineDepth = getValueNotNull(result, CaptureResult.REQUEST_PIPELINE_DEPTH);
        mCollector.expectInRange(String.format("Pipeline depth must be in the range of [%d, %d]",
                MIN_PIPELINE_DEPTH, maxPipelineDepth), pipelineDepth, MIN_PIPELINE_DEPTH,
                maxPipelineDepth);
    }
}
