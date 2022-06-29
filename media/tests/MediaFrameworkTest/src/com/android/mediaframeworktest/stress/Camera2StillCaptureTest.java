/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.mediaframeworktest.helpers.CameraTestUtils.CAPTURE_IMAGE_TIMEOUT_MS;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.MAX_READER_IMAGES;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleImageReaderListener;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.basicValidateJpegImage;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.configureCameraSession;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.dumpFile;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.getDataFromImage;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.getValueNotNull;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.makeImageReader;

import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.Image;
import android.media.ImageReader;
import android.os.ConditionVariable;
import android.util.Log;
import android.util.Pair;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import com.android.ex.camera2.blocking.BlockingSessionCallback;
import com.android.ex.camera2.exceptions.TimeoutRuntimeException;
import com.android.mediaframeworktest.Camera2SurfaceViewTestCase;
import com.android.mediaframeworktest.helpers.Camera2Focuser;
import com.android.mediaframeworktest.helpers.CameraTestUtils;
import com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleCaptureCallback;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>Tests for still capture API.</p>
 *
 * adb shell am instrument \
 *    -e class com.android.mediaframeworktest.stress.Camera2StillCaptureTest#testTakePicture \
 *    -e iterations 200 \
 *    -e waitIntervalMs 1000 \
 *    -e resultToFile false \
 *    -r -w com.android.mediaframeworktest/androidx.test.runner.AndroidJUnitRunner
 */
public class Camera2StillCaptureTest extends Camera2SurfaceViewTestCase {
    private static final String TAG = "StillCaptureTest";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    // 60 second to accommodate the possible long exposure time.
    private static final int MAX_REGIONS_AE_INDEX = 0;
    private static final int MAX_REGIONS_AWB_INDEX = 1;
    private static final int MAX_REGIONS_AF_INDEX = 2;
    private static final int WAIT_FOR_FOCUS_DONE_TIMEOUT_MS = 6000;
    private static final double AE_COMPENSATION_ERROR_TOLERANCE = 0.2;
    // 5 percent error margin for resulting metering regions
    private static final float METERING_REGION_ERROR_PERCENT_DELTA = 0.05f;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test normal still capture sequence.
     * <p>
     * Preview and and jpeg output streams are configured. Max still capture
     * size is used for jpeg capture. The sequence of still capture being test
     * is: start preview, auto focus, precapture metering (if AE is not
     * converged), then capture jpeg. The AWB and AE are in auto modes. AF mode
     * is CONTINUOUS_PICTURE.
     * </p>
     */
    public void testTakePicture() throws Exception{
        for (String id : mCameraIds) {
            try {
                Log.i(TAG, "Testing basic take picture for Camera " + id);
                openDevice(id);
                if (!mStaticInfo.isColorOutputSupported()) {
                    Log.i(TAG, "Camera " + id + " does not support color outputs, skipping");
                    continue;
                }

                // Test iteration starts...
                for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                    Log.v(TAG, String.format("Taking pictures: %d/%d", iteration + 1,
                            getIterationCount()));
                    takePictureTestByCamera(/*aeRegions*/null, /*awbRegions*/null,
                            /*afRegions*/null);
                    getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
                    Thread.sleep(getTestWaitIntervalMs());
                }
            } finally {
                closeDevice();
                closeImageReader();
            }
        }
    }

    /**
     * Test the full raw capture use case.
     *
     * This includes:
     * - Configuring the camera with a preview, jpeg, and raw output stream.
     * - Running preview until AE/AF can settle.
     * - Capturing with a request targeting all three output streams.
     */
    public void testFullRawCapture() throws Exception {
        for (int i = 0; i < mCameraIds.length; i++) {
            try {
                Log.i(TAG, "Testing raw capture for Camera " + mCameraIds[i]);
                openDevice(mCameraIds[i]);
                if (!mStaticInfo.isCapabilitySupported(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    Log.i(TAG, "RAW capability is not supported in camera " + mCameraIds[i] +
                            ". Skip the test.");
                    continue;
                }

                // Test iteration starts...
                for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                    Log.v(TAG, String.format("Taking full RAW pictures: %d/%d", iteration + 1,
                            getIterationCount()));
                    fullRawCaptureTestByCamera();
                    getResultPrinter().printStatus(getIterationCount(), iteration + 1,
                            mCameraIds[i]);
                    Thread.sleep(getTestWaitIntervalMs());
                }
            } finally {
                closeDevice();
                closeImageReader();
            }
        }
    }

    /**
     * Take a picture for a given set of 3A regions for a particular camera.
     * <p>
     * Before take a still capture, it triggers an auto focus and lock it first,
     * then wait for AWB to converge and lock it, then trigger a precapture
     * metering sequence and wait for AE converged. After capture is received, the
     * capture result and image are validated.
     * </p>
     *
     * @param aeRegions AE regions for this capture
     * @param awbRegions AWB regions for this capture
     * @param afRegions AF regions for this capture
     */
    private void takePictureTestByCamera(
            MeteringRectangle[] aeRegions, MeteringRectangle[] awbRegions,
            MeteringRectangle[] afRegions) throws Exception {
        takePictureTestByCamera(aeRegions, awbRegions, afRegions,
                /*addAeTriggerCancel*/false);
    }

    /**
     * Take a picture for a given set of 3A regions for a particular camera.
     * <p>
     * Before take a still capture, it triggers an auto focus and lock it first,
     * then wait for AWB to converge and lock it, then trigger a precapture
     * metering sequence and wait for AE converged. After capture is received, the
     * capture result and image are validated. If {@code addAeTriggerCancel} is true,
     * a precapture trigger cancel will be inserted between two adjacent triggers, which
     * should effective cancel the first trigger.
     * </p>
     *
     * @param aeRegions AE regions for this capture
     * @param awbRegions AWB regions for this capture
     * @param afRegions AF regions for this capture
     * @param addAeTriggerCancel If a AE precapture trigger cancel is sent after the trigger.
     */
    private void takePictureTestByCamera(
            MeteringRectangle[] aeRegions, MeteringRectangle[] awbRegions,
            MeteringRectangle[] afRegions, boolean addAeTriggerCancel) throws Exception {

        boolean hasFocuser = mStaticInfo.hasFocuser();

        Size maxStillSz = mOrderedStillSizes.get(0);
        Size maxPreviewSz = mOrderedPreviewSizes.get(0);
        CaptureResult result;
        SimpleCaptureCallback resultListener = new SimpleCaptureCallback();
        SimpleImageReaderListener imageListener = new SimpleImageReaderListener();
        CaptureRequest.Builder previewRequest =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        CaptureRequest.Builder stillRequest =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        prepareStillCaptureAndStartPreview(previewRequest, stillRequest, maxPreviewSz,
                maxStillSz, resultListener, imageListener);

        // Set AE mode to ON_AUTO_FLASH if flash is available.
        if (mStaticInfo.hasFlash()) {
            previewRequest.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            stillRequest.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }

        Camera2Focuser focuser = null;
        /**
         * Step 1: trigger an auto focus run, and wait for AF locked.
         */
        boolean canSetAfRegion = hasFocuser && (afRegions != null) &&
                isRegionsSupportedFor3A(MAX_REGIONS_AF_INDEX);
        if (hasFocuser) {
            SimpleAutoFocusListener afListener = new SimpleAutoFocusListener();
            focuser = new Camera2Focuser(mCamera, mSession, mPreviewSurface, afListener,
                    mStaticInfo.getCharacteristics(), mHandler);
            if (canSetAfRegion) {
                stillRequest.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
            }
            focuser.startAutoFocus(afRegions);
            afListener.waitForAutoFocusDone(WAIT_FOR_FOCUS_DONE_TIMEOUT_MS);
        }

        /**
         * Have to get the current AF mode to be used for other 3A repeating
         * request, otherwise, the new AF mode in AE/AWB request could be
         * different with existing repeating requests being sent by focuser,
         * then it could make AF unlocked too early. Beside that, for still
         * capture, AF mode must not be different with the one in current
         * repeating request, otherwise, the still capture itself would trigger
         * an AF mode change, and the AF lock would be lost for this capture.
         */
        int currentAfMode = CaptureRequest.CONTROL_AF_MODE_OFF;
        if (hasFocuser) {
            currentAfMode = focuser.getCurrentAfMode();
        }
        previewRequest.set(CaptureRequest.CONTROL_AF_MODE, currentAfMode);
        stillRequest.set(CaptureRequest.CONTROL_AF_MODE, currentAfMode);

        /**
         * Step 2: AF is already locked, wait for AWB converged, then lock it.
         */
        resultListener = new SimpleCaptureCallback();
        boolean canSetAwbRegion =
                (awbRegions != null) && isRegionsSupportedFor3A(MAX_REGIONS_AWB_INDEX);
        if (canSetAwbRegion) {
            previewRequest.set(CaptureRequest.CONTROL_AWB_REGIONS, awbRegions);
            stillRequest.set(CaptureRequest.CONTROL_AWB_REGIONS, awbRegions);
        }
        mSession.setRepeatingRequest(previewRequest.build(), resultListener, mHandler);
        if (mStaticInfo.isHardwareLevelLimitedOrBetter()) {
            waitForResultValue(resultListener, CaptureResult.CONTROL_AWB_STATE,
                    CaptureResult.CONTROL_AWB_STATE_CONVERGED, NUM_RESULTS_WAIT_TIMEOUT);
        } else {
            // LEGACY Devices don't have the AWB_STATE reported in results, so just wait
            waitForSettingsApplied(resultListener, NUM_FRAMES_WAITED_FOR_UNKNOWN_LATENCY);
        }
        boolean canSetAwbLock = mStaticInfo.isAwbLockSupported();
        if (canSetAwbLock) {
            previewRequest.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        }
        mSession.setRepeatingRequest(previewRequest.build(), resultListener, mHandler);
        // Validate the next result immediately for region and mode.
        result = resultListener.getCaptureResult(WAIT_FOR_RESULT_TIMEOUT_MS);
        mCollector.expectEquals("AWB mode in result and request should be same",
                previewRequest.get(CaptureRequest.CONTROL_AWB_MODE),
                result.get(CaptureResult.CONTROL_AWB_MODE));
        if (canSetAwbRegion) {
            MeteringRectangle[] resultAwbRegions =
                    getValueNotNull(result, CaptureResult.CONTROL_AWB_REGIONS);
            mCollector.expectEquals("AWB regions in result and request should be same",
                    awbRegions, resultAwbRegions);
        }

        /**
         * Step 3: trigger an AE precapture metering sequence and wait for AE converged.
         */
        resultListener = new SimpleCaptureCallback();
        boolean canSetAeRegion =
                (aeRegions != null) && isRegionsSupportedFor3A(MAX_REGIONS_AE_INDEX);
        if (canSetAeRegion) {
            previewRequest.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions);
            stillRequest.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions);
        }
        mSession.setRepeatingRequest(previewRequest.build(), resultListener, mHandler);
        previewRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
        mSession.capture(previewRequest.build(), resultListener, mHandler);
        if (addAeTriggerCancel) {
            // Cancel the current precapture trigger, then send another trigger.
            // The camera device should behave as if the first trigger is not sent.
            // Wait one request to make the trigger start doing something before cancel.
            waitForNumResults(resultListener, /*numResultsWait*/ 1);
            previewRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            mSession.capture(previewRequest.build(), resultListener, mHandler);
            waitForResultValue(resultListener, CaptureResult.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureResult.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL,
                    NUM_FRAMES_WAITED_FOR_UNKNOWN_LATENCY);
            // Issue another trigger
            previewRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mSession.capture(previewRequest.build(), resultListener, mHandler);
        }
        waitForAeStable(resultListener, NUM_FRAMES_WAITED_FOR_UNKNOWN_LATENCY);

        // Validate the next result immediately for region and mode.
        result = resultListener.getCaptureResult(WAIT_FOR_RESULT_TIMEOUT_MS);
        mCollector.expectEquals("AE mode in result and request should be same",
                previewRequest.get(CaptureRequest.CONTROL_AE_MODE),
                result.get(CaptureResult.CONTROL_AE_MODE));
        if (canSetAeRegion) {
            MeteringRectangle[] resultAeRegions =
                    getValueNotNull(result, CaptureResult.CONTROL_AE_REGIONS);

            mCollector.expectMeteringRegionsAreSimilar(
                    "AE regions in result and request should be similar",
                    aeRegions,
                    resultAeRegions,
                    METERING_REGION_ERROR_PERCENT_DELTA);
        }

        /**
         * Step 4: take a picture when all 3A are in good state.
         */
        resultListener = new SimpleCaptureCallback();
        CaptureRequest request = stillRequest.build();
        mSession.capture(request, resultListener, mHandler);
        // Validate the next result immediately for region and mode.
        result = resultListener.getCaptureResultForRequest(request, WAIT_FOR_RESULT_TIMEOUT_MS);
        mCollector.expectEquals("AF mode in result and request should be same",
                stillRequest.get(CaptureRequest.CONTROL_AF_MODE),
                result.get(CaptureResult.CONTROL_AF_MODE));
        if (canSetAfRegion) {
            MeteringRectangle[] resultAfRegions =
                    getValueNotNull(result, CaptureResult.CONTROL_AF_REGIONS);
            mCollector.expectMeteringRegionsAreSimilar(
                    "AF regions in result and request should be similar",
                    afRegions,
                    resultAfRegions,
                    METERING_REGION_ERROR_PERCENT_DELTA);
        }

        if (hasFocuser) {
            // Unlock auto focus.
            focuser.cancelAutoFocus();
        }

        // validate image
        Image image = imageListener.getImage(CAPTURE_IMAGE_TIMEOUT_MS);
        validateJpegCapture(image, maxStillSz);

        // Free image resources
        image.close();

        stopPreview();
    }

    private void fullRawCaptureTestByCamera() throws Exception {
        Size maxPreviewSz = mOrderedPreviewSizes.get(0);
        Size maxStillSz = mOrderedStillSizes.get(0);

        SimpleCaptureCallback resultListener = new SimpleCaptureCallback();
        SimpleImageReaderListener jpegListener = new SimpleImageReaderListener();
        SimpleImageReaderListener rawListener = new SimpleImageReaderListener();

        Size size = mStaticInfo.getRawDimensChecked();

        if (VERBOSE) {
            Log.v(TAG, "Testing multi capture with size " + size.toString()
                    + ", preview size " + maxPreviewSz);
        }

        // Prepare raw capture and start preview.
        CaptureRequest.Builder previewBuilder =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        CaptureRequest.Builder multiBuilder =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

        ImageReader rawReader = null;
        ImageReader jpegReader = null;

        try {
            // Create ImageReaders.
            rawReader = makeImageReader(size,
                    ImageFormat.RAW_SENSOR, MAX_READER_IMAGES, rawListener, mHandler);
            jpegReader = makeImageReader(maxStillSz,
                    ImageFormat.JPEG, MAX_READER_IMAGES, jpegListener, mHandler);
            updatePreviewSurface(maxPreviewSz);

            // Configure output streams with preview and jpeg streams.
            List<Surface> outputSurfaces = new ArrayList<Surface>();
            outputSurfaces.add(rawReader.getSurface());
            outputSurfaces.add(jpegReader.getSurface());
            outputSurfaces.add(mPreviewSurface);
            mSessionListener = new BlockingSessionCallback();
            mSession = configureCameraSession(mCamera, outputSurfaces,
                    mSessionListener, mHandler);

            // Configure the requests.
            previewBuilder.addTarget(mPreviewSurface);
            multiBuilder.addTarget(mPreviewSurface);
            multiBuilder.addTarget(rawReader.getSurface());
            multiBuilder.addTarget(jpegReader.getSurface());

            // Start preview.
            mSession.setRepeatingRequest(previewBuilder.build(), null, mHandler);

            // Poor man's 3A, wait 2 seconds for AE/AF (if any) to settle.
            // TODO: Do proper 3A trigger and lock (see testTakePictureTest).
            Thread.sleep(3000);

            multiBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            CaptureRequest multiRequest = multiBuilder.build();

            mSession.capture(multiRequest, resultListener, mHandler);

            CaptureResult result = resultListener.getCaptureResultForRequest(multiRequest,
                    NUM_RESULTS_WAIT_TIMEOUT);
            Image jpegImage = jpegListener.getImage(CAPTURE_IMAGE_TIMEOUT_MS);
            basicValidateJpegImage(jpegImage, maxStillSz);
            Image rawImage = rawListener.getImage(CAPTURE_IMAGE_TIMEOUT_MS);
            validateRaw16Image(rawImage, size);
            verifyRawCaptureResult(multiRequest, result);


            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (DngCreator dngCreator = new DngCreator(mStaticInfo.getCharacteristics(), result)) {
                dngCreator.writeImage(outputStream, rawImage);
            }

            if (DEBUG) {
                byte[] rawBuffer = outputStream.toByteArray();
                String rawFileName = DEBUG_FILE_NAME_BASE + "/raw16_" + TAG + size.toString() +
                        "_cam_" + mCamera.getId() + ".dng";
                Log.d(TAG, "Dump raw file into " + rawFileName);
                dumpFile(rawFileName, rawBuffer);

                byte[] jpegBuffer = getDataFromImage(jpegImage);
                String jpegFileName = DEBUG_FILE_NAME_BASE + "/jpeg_" + TAG + size.toString() +
                        "_cam_" + mCamera.getId() + ".jpg";
                Log.d(TAG, "Dump jpeg file into " + rawFileName);
                dumpFile(jpegFileName, jpegBuffer);
            }

            stopPreview();
        } finally {
            CameraTestUtils.closeImageReader(rawReader);
            CameraTestUtils.closeImageReader(jpegReader);
            rawReader = null;
            jpegReader = null;
        }
    }

    /**
     * Validate that raw {@link CaptureResult}.
     *
     * @param rawRequest a {@link CaptureRequest} use to capture a RAW16 image.
     * @param rawResult the {@link CaptureResult} corresponding to the given request.
     */
    private void verifyRawCaptureResult(CaptureRequest rawRequest, CaptureResult rawResult) {
        assertNotNull(rawRequest);
        assertNotNull(rawResult);

        Rational[] empty = new Rational[] { Rational.ZERO, Rational.ZERO, Rational.ZERO};
        Rational[] neutralColorPoint = mCollector.expectKeyValueNotNull("NeutralColorPoint",
                rawResult, CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if (neutralColorPoint != null) {
            mCollector.expectEquals("NeutralColorPoint length", empty.length,
                    neutralColorPoint.length);
            mCollector.expectNotEquals("NeutralColorPoint cannot be all zeroes, ", empty,
                    neutralColorPoint);
            mCollector.expectValuesGreaterOrEqual("NeutralColorPoint", neutralColorPoint,
                    Rational.ZERO);
        }

        mCollector.expectKeyValueGreaterOrEqual(rawResult, CaptureResult.SENSOR_GREEN_SPLIT, 0.0f);

        Pair<Double, Double>[] noiseProfile = mCollector.expectKeyValueNotNull("NoiseProfile",
                rawResult, CaptureResult.SENSOR_NOISE_PROFILE);
        if (noiseProfile != null) {
            mCollector.expectEquals("NoiseProfile length", noiseProfile.length,
                /*Num CFA channels*/4);
            for (Pair<Double, Double> p : noiseProfile) {
                mCollector.expectTrue("NoiseProfile coefficients " + p +
                        " must have: S > 0, O >= 0", p.first > 0 && p.second >= 0);
            }
        }

        Integer hotPixelMode = mCollector.expectKeyValueNotNull("HotPixelMode", rawResult,
                CaptureResult.HOT_PIXEL_MODE);
        Boolean hotPixelMapMode = mCollector.expectKeyValueNotNull("HotPixelMapMode", rawResult,
                CaptureResult.STATISTICS_HOT_PIXEL_MAP_MODE);
        Point[] hotPixelMap = rawResult.get(CaptureResult.STATISTICS_HOT_PIXEL_MAP);

        Size pixelArraySize = mStaticInfo.getPixelArraySizeChecked();
        boolean[] availableHotPixelMapModes = mStaticInfo.getValueFromKeyNonNull(
                        CameraCharacteristics.STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES);

        if (hotPixelMode != null) {
            Integer requestMode = mCollector.expectKeyValueNotNull(rawRequest,
                    CaptureRequest.HOT_PIXEL_MODE);
            if (requestMode != null) {
                mCollector.expectKeyValueEquals(rawResult, CaptureResult.HOT_PIXEL_MODE,
                        requestMode);
            }
        }

        if (hotPixelMapMode != null) {
            Boolean requestMapMode = mCollector.expectKeyValueNotNull(rawRequest,
                    CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE);
            if (requestMapMode != null) {
                mCollector.expectKeyValueEquals(rawResult,
                        CaptureResult.STATISTICS_HOT_PIXEL_MAP_MODE, requestMapMode);
            }

            if (!hotPixelMapMode) {
                mCollector.expectTrue("HotPixelMap must be empty", hotPixelMap == null ||
                        hotPixelMap.length == 0);
            } else {
                mCollector.expectTrue("HotPixelMap must not be empty", hotPixelMap != null);
                mCollector.expectNotNull("AvailableHotPixelMapModes must not be null",
                        availableHotPixelMapModes);
                if (availableHotPixelMapModes != null) {
                    mCollector.expectContains("HotPixelMapMode", availableHotPixelMapModes, true);
                }

                int height = pixelArraySize.getHeight();
                int width = pixelArraySize.getWidth();
                for (Point p : hotPixelMap) {
                    mCollector.expectTrue("Hotpixel " + p + " must be in pixelArray " +
                            pixelArraySize, p.x >= 0 && p.x < width && p.y >= 0 && p.y < height);
                }
            }
        }
        // TODO: profileHueSatMap, and profileToneCurve aren't supported yet.

    }

    //----------------------------------------------------------------
    //---------Below are common functions for all tests.--------------
    //----------------------------------------------------------------
    /**
     * Validate standard raw (RAW16) capture image.
     *
     * @param image The raw16 format image captured
     * @param rawSize The expected raw size
     */
    private static void validateRaw16Image(Image image, Size rawSize) {
        CameraTestUtils.validateImage(image, rawSize.getWidth(), rawSize.getHeight(),
                ImageFormat.RAW_SENSOR, /*filePath*/null);
    }

    /**
     * Validate JPEG capture image object soundness and test.
     * <p>
     * In addition to image object consistency, this function also does the decoding
     * test, which is slower.
     * </p>
     *
     * @param image The JPEG image to be verified.
     * @param jpegSize The JPEG capture size to be verified against.
     */
    private static void validateJpegCapture(Image image, Size jpegSize) {
        CameraTestUtils.validateImage(image, jpegSize.getWidth(), jpegSize.getHeight(),
                ImageFormat.JPEG, /*filePath*/null);
    }

    private static class SimpleAutoFocusListener implements Camera2Focuser.AutoFocusListener {
        final ConditionVariable focusDone = new ConditionVariable();
        @Override
        public void onAutoFocusLocked(boolean success) {
            focusDone.open();
        }

        public void waitForAutoFocusDone(long timeoutMs) {
            if (focusDone.block(timeoutMs)) {
                focusDone.close();
            } else {
                throw new TimeoutRuntimeException("Wait for auto focus done timed out after "
                        + timeoutMs + "ms");
            }
        }
    }

    private boolean isRegionsSupportedFor3A(int index) {
        int maxRegions = 0;
        switch (index) {
            case MAX_REGIONS_AE_INDEX:
                maxRegions = mStaticInfo.getAeMaxRegionsChecked();
                break;
            case MAX_REGIONS_AWB_INDEX:
                maxRegions = mStaticInfo.getAwbMaxRegionsChecked();
                break;
            case  MAX_REGIONS_AF_INDEX:
                maxRegions = mStaticInfo.getAfMaxRegionsChecked();
                break;
            default:
                throw new IllegalArgumentException("Unknown algorithm index");
        }
        boolean isRegionsSupported = maxRegions > 0;
        if (index == MAX_REGIONS_AF_INDEX && isRegionsSupported) {
            mCollector.expectTrue(
                    "Device reports non-zero max AF region count for a camera without focuser!",
                    mStaticInfo.hasFocuser());
            isRegionsSupported = isRegionsSupported && mStaticInfo.hasFocuser();
        }

        return isRegionsSupported;
    }
}
