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

import com.android.ex.camera2.blocking.BlockingSessionCallback;
import com.android.mediaframeworktest.Camera2SurfaceViewTestCase;
import com.android.mediaframeworktest.helpers.CameraTestUtils;
import com.android.mediaframeworktest.helpers.StaticMetadata;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.InputConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.android.mediaframeworktest.helpers.CameraTestUtils.EXIF_TEST_DATA;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SESSION_CLOSE_TIMEOUT_MS;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleCaptureCallback;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleImageReaderListener;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleImageWriterListener;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.configureReprocessableCameraSession;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.dumpFile;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.getAscendingOrderSizes;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.getDataFromImage;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.makeImageReader;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.setJpegKeys;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.verifyJpegKeys;

/**
 * <p>Tests for Reprocess API.</p>
 *
 * adb shell am instrument \
 *    -e class \
 *    com.android.mediaframeworktest.stress.Camera2StillCaptureTest#Camera2ReprocessCaptureTest \
 *    -e iterations 1 \
 *    -e waitIntervalMs 1000 \
 *    -e resultToFile false \
 *    -r -w com.android.mediaframeworktest/.Camera2InstrumentationTestRunner
 */
public class Camera2ReprocessCaptureTest extends Camera2SurfaceViewTestCase  {
    private static final String TAG = "ReprocessCaptureTest";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int CAPTURE_TIMEOUT_FRAMES = 100;
    private static final int CAPTURE_TIMEOUT_MS = 3000;
    private static final int WAIT_FOR_SURFACE_CHANGE_TIMEOUT_MS = 1000;
    private static final int CAPTURE_TEMPLATE = CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG;
    private static final int ZSL_TEMPLATE = CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG;
    private static final int NUM_REPROCESS_TEST_LOOP = 3;
    private static final int NUM_REPROCESS_CAPTURES = 3;
    private static final int NUM_REPROCESS_BURST = 3;
    private int mDumpFrameCount = 0;

    // The image reader for the first regular capture
    private ImageReader mFirstImageReader;
    // The image reader for the reprocess capture
    private ImageReader mSecondImageReader;
    // A flag indicating whether the regular capture and the reprocess capture share the same image
    // reader. If it's true, mFirstImageReader should be used for regular and reprocess outputs.
    private boolean mShareOneImageReader;
    private SimpleImageReaderListener mFirstImageReaderListener;
    private SimpleImageReaderListener mSecondImageReaderListener;
    private Surface mInputSurface;
    private ImageWriter mImageWriter;
    private SimpleImageWriterListener mImageWriterListener;

    private enum CaptureTestCase {
        SINGLE_SHOT,
        BURST,
        MIXED_BURST,
        ABORT_CAPTURE,
        TIMESTAMPS,
        JPEG_EXIF,
        REQUEST_KEYS,
    }

    /**
     * Test YUV_420_888 -> JPEG with maximal supported sizes
     */
    public void testBasicYuvToJpegReprocessing() throws Exception {
        for (String id : mCameraIds) {
            if (!isYuvReprocessSupported(id)) {
                continue;
            }

            // Test iteration starts...
            for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                Log.v(TAG, String.format("Reprocessing YUV to JPEG: %d/%d", iteration + 1,
                        getIterationCount()));
                // YUV_420_888 -> JPEG must be supported.
                testBasicReprocessing(id, ImageFormat.YUV_420_888, ImageFormat.JPEG);
                getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
                Thread.sleep(getTestWaitIntervalMs());
            }
        }
    }

    /**
     * Test OPAQUE -> JPEG with maximal supported sizes
     */
    public void testBasicOpaqueToJpegReprocessing() throws Exception {
        for (String id : mCameraIds) {
            if (!isOpaqueReprocessSupported(id)) {
                continue;
            }

            // Test iteration starts...
            for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                Log.v(TAG, String.format("Reprocessing OPAQUE to JPEG: %d/%d", iteration + 1,
                        getIterationCount()));
                // OPAQUE -> JPEG must be supported.
                testBasicReprocessing(id, ImageFormat.PRIVATE, ImageFormat.JPEG);
                getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
                Thread.sleep(getTestWaitIntervalMs());
            }

        }
    }

    /**
     * Test all supported size and format combinations with preview.
     */
    public void testReprocessingSizeFormatWithPreview() throws Exception {
        for (String id : mCameraIds) {
            if (!isYuvReprocessSupported(id) && !isOpaqueReprocessSupported(id)) {
                continue;
            }

            try {
                // open Camera device
                openDevice(id);

                // Test iteration starts...
                for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                    Log.v(TAG, String.format("Reprocessing size format with preview: %d/%d",
                            iteration + 1, getIterationCount()));
                    testReprocessingAllCombinations(id, mOrderedPreviewSizes.get(0),
                            CaptureTestCase.SINGLE_SHOT);
                    getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
                    Thread.sleep(getTestWaitIntervalMs());
                }
            } finally {
                closeDevice();
            }
        }
    }

    /**
     * Test burst captures mixed with regular and reprocess captures with and without preview.
     */
    public void testMixedBurstReprocessing() throws Exception {
        for (String id : mCameraIds) {
            if (!isYuvReprocessSupported(id) && !isOpaqueReprocessSupported(id)) {
                continue;
            }

            try {
                // open Camera device
                openDevice(id);

                // Test iteration starts...
                for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                    Log.v(TAG, String.format("Reprocessing mixed burst with or without preview: "
                            + "%d/%d", iteration + 1, getIterationCount()));
                    // no preview
                    testReprocessingAllCombinations(id, /*previewSize*/null,
                            CaptureTestCase.MIXED_BURST);
                    // with preview
                    testReprocessingAllCombinations(id, mOrderedPreviewSizes.get(0),
                            CaptureTestCase.MIXED_BURST);
                    getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
                    Thread.sleep(getTestWaitIntervalMs());
                }
            } finally {
                closeDevice();
            }
        }
    }

    /**
     * Test the input format and output format with the largest input and output sizes.
     */
    private void testBasicReprocessing(String cameraId, int inputFormat,
            int reprocessOutputFormat) throws Exception {
        try {
            openDevice(cameraId);

            testReprocessingMaxSizes(cameraId, inputFormat, reprocessOutputFormat,
                    /* previewSize */null, CaptureTestCase.SINGLE_SHOT);
        } finally {
            closeDevice();
        }
    }

    /**
     * Test the input format and output format with the largest input and output sizes for a
     * certain test case.
     */
    private void testReprocessingMaxSizes(String cameraId, int inputFormat,
            int reprocessOutputFormat, Size previewSize, CaptureTestCase captureTestCase)
            throws Exception {
        Size maxInputSize = getMaxSize(inputFormat, StaticMetadata.StreamDirection.Input);
        Size maxReprocessOutputSize =
                getMaxSize(reprocessOutputFormat, StaticMetadata.StreamDirection.Output);

        switch (captureTestCase) {
            case SINGLE_SHOT:
                testReprocess(cameraId, maxInputSize, inputFormat, maxReprocessOutputSize,
                        reprocessOutputFormat, previewSize, NUM_REPROCESS_CAPTURES);
                break;
            case ABORT_CAPTURE:
                testReprocessAbort(cameraId, maxInputSize, inputFormat, maxReprocessOutputSize,
                        reprocessOutputFormat);
                break;
            case TIMESTAMPS:
                testReprocessTimestamps(cameraId, maxInputSize, inputFormat, maxReprocessOutputSize,
                        reprocessOutputFormat);
                break;
            case JPEG_EXIF:
                testReprocessJpegExif(cameraId, maxInputSize, inputFormat, maxReprocessOutputSize);
                break;
            case REQUEST_KEYS:
                testReprocessRequestKeys(cameraId, maxInputSize, inputFormat,
                        maxReprocessOutputSize, reprocessOutputFormat);
                break;
            default:
                throw new IllegalArgumentException("Invalid test case");
        }
    }

    /**
     * Test all input format, input size, output format, and output size combinations.
     */
    private void testReprocessingAllCombinations(String cameraId, Size previewSize,
            CaptureTestCase captureTestCase) throws Exception {

        int[] supportedInputFormats =
                mStaticInfo.getAvailableFormats(StaticMetadata.StreamDirection.Input);
        for (int inputFormat : supportedInputFormats) {
            Size[] supportedInputSizes =
                    mStaticInfo.getAvailableSizesForFormatChecked(inputFormat,
                    StaticMetadata.StreamDirection.Input);

            for (Size inputSize : supportedInputSizes) {
                int[] supportedReprocessOutputFormats =
                        mStaticInfo.getValidOutputFormatsForInput(inputFormat);

                for (int reprocessOutputFormat : supportedReprocessOutputFormats) {
                    Size[] supportedReprocessOutputSizes =
                            mStaticInfo.getAvailableSizesForFormatChecked(reprocessOutputFormat,
                            StaticMetadata.StreamDirection.Output);

                    for (Size reprocessOutputSize : supportedReprocessOutputSizes) {
                        switch (captureTestCase) {
                            case SINGLE_SHOT:
                                testReprocess(cameraId, inputSize, inputFormat,
                                        reprocessOutputSize, reprocessOutputFormat, previewSize,
                                        NUM_REPROCESS_CAPTURES);
                                break;
                            case BURST:
                                testReprocessBurst(cameraId, inputSize, inputFormat,
                                        reprocessOutputSize, reprocessOutputFormat, previewSize,
                                        NUM_REPROCESS_BURST);
                                break;
                            case MIXED_BURST:
                                testReprocessMixedBurst(cameraId, inputSize, inputFormat,
                                        reprocessOutputSize, reprocessOutputFormat, previewSize,
                                        NUM_REPROCESS_BURST);
                                break;
                            default:
                                throw new IllegalArgumentException("Invalid test case");
                        }
                    }
                }
            }
        }
    }

    /**
     * Test burst that is mixed with regular and reprocess capture requests.
     */
    private void testReprocessMixedBurst(String cameraId, Size inputSize, int inputFormat,
            Size reprocessOutputSize, int reprocessOutputFormat, Size previewSize,
            int numBurst) throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "testReprocessMixedBurst: cameraId: " + cameraId + " inputSize: " +
                    inputSize + " inputFormat: " + inputFormat + " reprocessOutputSize: " +
                    reprocessOutputSize + " reprocessOutputFormat: " + reprocessOutputFormat +
                    " previewSize: " + previewSize + " numBurst: " + numBurst);
        }

        boolean enablePreview = (previewSize != null);
        ImageResultHolder[] imageResultHolders = new ImageResultHolder[0];

        try {
            // totalNumBurst = number of regular burst + number of reprocess burst.
            int totalNumBurst = numBurst * 2;

            if (enablePreview) {
                updatePreviewSurface(previewSize);
            } else {
                mPreviewSurface = null;
            }

            setupImageReaders(inputSize, inputFormat, reprocessOutputSize, reprocessOutputFormat,
                totalNumBurst);
            setupReprocessableSession(mPreviewSurface, /*numImageWriterImages*/numBurst);

            if (enablePreview) {
                startPreview(mPreviewSurface);
            }

            // Prepare an array of booleans indicating each capture's type (regular or reprocess)
            boolean[] isReprocessCaptures = new boolean[totalNumBurst];
            for (int i = 0; i < totalNumBurst; i++) {
                if ((i & 1) == 0) {
                    isReprocessCaptures[i] = true;
                } else {
                    isReprocessCaptures[i] = false;
                }
            }

            imageResultHolders = doMixedReprocessBurstCapture(isReprocessCaptures);
            for (ImageResultHolder holder : imageResultHolders) {
                Image reprocessedImage = holder.getImage();
                TotalCaptureResult result = holder.getTotalCaptureResult();

                mCollector.expectImageProperties("testReprocessMixedBurst", reprocessedImage,
                            reprocessOutputFormat, reprocessOutputSize,
                            result.get(CaptureResult.SENSOR_TIMESTAMP));

                if (DEBUG) {
                    Log.d(TAG, String.format("camera %s in %dx%d %d out %dx%d %d",
                            cameraId, inputSize.getWidth(), inputSize.getHeight(), inputFormat,
                            reprocessOutputSize.getWidth(), reprocessOutputSize.getHeight(),
                            reprocessOutputFormat));
                    dumpImage(reprocessedImage,
                            "/testReprocessMixedBurst_camera" + cameraId + "_" + mDumpFrameCount);
                    mDumpFrameCount++;
                }
            }
        } finally {
            for (ImageResultHolder holder : imageResultHolders) {
                holder.getImage().close();
            }
            closeReprossibleSession();
            closeImageReaders();
        }
    }

    /**
     * Test burst of reprocess capture requests.
     */
    private void testReprocessBurst(String cameraId, Size inputSize, int inputFormat,
            Size reprocessOutputSize, int reprocessOutputFormat, Size previewSize,
            int numBurst) throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "testReprocessBurst: cameraId: " + cameraId + " inputSize: " +
                    inputSize + " inputFormat: " + inputFormat + " reprocessOutputSize: " +
                    reprocessOutputSize + " reprocessOutputFormat: " + reprocessOutputFormat +
                    " previewSize: " + previewSize + " numBurst: " + numBurst);
        }

        boolean enablePreview = (previewSize != null);
        ImageResultHolder[] imageResultHolders = new ImageResultHolder[0];

        try {
            if (enablePreview) {
                updatePreviewSurface(previewSize);
            } else {
                mPreviewSurface = null;
            }

            setupImageReaders(inputSize, inputFormat, reprocessOutputSize, reprocessOutputFormat,
                numBurst);
            setupReprocessableSession(mPreviewSurface, numBurst);

            if (enablePreview) {
                startPreview(mPreviewSurface);
            }

            imageResultHolders = doReprocessBurstCapture(numBurst);
            for (ImageResultHolder holder : imageResultHolders) {
                Image reprocessedImage = holder.getImage();
                TotalCaptureResult result = holder.getTotalCaptureResult();

                mCollector.expectImageProperties("testReprocessBurst", reprocessedImage,
                            reprocessOutputFormat, reprocessOutputSize,
                            result.get(CaptureResult.SENSOR_TIMESTAMP));

                if (DEBUG) {
                    Log.d(TAG, String.format("camera %s in %dx%d %d out %dx%d %d",
                            cameraId, inputSize.getWidth(), inputSize.getHeight(), inputFormat,
                            reprocessOutputSize.getWidth(), reprocessOutputSize.getHeight(),
                            reprocessOutputFormat));
                    dumpImage(reprocessedImage,
                            "/testReprocessBurst_camera" + cameraId + "_" + mDumpFrameCount);
                    mDumpFrameCount++;
                }
            }
        } finally {
            for (ImageResultHolder holder : imageResultHolders) {
                holder.getImage().close();
            }
            closeReprossibleSession();
            closeImageReaders();
        }
    }

    /**
     * Test a sequences of reprocess capture requests.
     */
    private void testReprocess(String cameraId, Size inputSize, int inputFormat,
            Size reprocessOutputSize, int reprocessOutputFormat, Size previewSize,
            int numReprocessCaptures) throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "testReprocess: cameraId: " + cameraId + " inputSize: " +
                    inputSize + " inputFormat: " + inputFormat + " reprocessOutputSize: " +
                    reprocessOutputSize + " reprocessOutputFormat: " + reprocessOutputFormat +
                    " previewSize: " + previewSize);
        }

        boolean enablePreview = (previewSize != null);

        try {
            if (enablePreview) {
                updatePreviewSurface(previewSize);
            } else {
                mPreviewSurface = null;
            }

            setupImageReaders(inputSize, inputFormat, reprocessOutputSize, reprocessOutputFormat,
                    /*maxImages*/1);
            setupReprocessableSession(mPreviewSurface, /*numImageWriterImages*/1);

            if (enablePreview) {
                startPreview(mPreviewSurface);
            }

            for (int i = 0; i < numReprocessCaptures; i++) {
                ImageResultHolder imageResultHolder = null;

                try {
                    imageResultHolder = doReprocessCapture();
                    Image reprocessedImage = imageResultHolder.getImage();
                    TotalCaptureResult result = imageResultHolder.getTotalCaptureResult();

                    mCollector.expectImageProperties("testReprocess", reprocessedImage,
                            reprocessOutputFormat, reprocessOutputSize,
                            result.get(CaptureResult.SENSOR_TIMESTAMP));

                    if (DEBUG) {
                        Log.d(TAG, String.format("camera %s in %dx%d %d out %dx%d %d",
                                cameraId, inputSize.getWidth(), inputSize.getHeight(), inputFormat,
                                reprocessOutputSize.getWidth(), reprocessOutputSize.getHeight(),
                                reprocessOutputFormat));

                        dumpImage(reprocessedImage,
                                "/testReprocess_camera" + cameraId + "_" + mDumpFrameCount);
                        mDumpFrameCount++;
                    }
                } finally {
                    if (imageResultHolder != null) {
                        imageResultHolder.getImage().close();
                    }
                }
            }
        } finally {
            closeReprossibleSession();
            closeImageReaders();
        }
    }

    /**
     * Test aborting a burst reprocess capture and multiple single reprocess captures.
     */
    private void testReprocessAbort(String cameraId, Size inputSize, int inputFormat,
            Size reprocessOutputSize, int reprocessOutputFormat) throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "testReprocessAbort: cameraId: " + cameraId + " inputSize: " +
                    inputSize + " inputFormat: " + inputFormat + " reprocessOutputSize: " +
                    reprocessOutputSize + " reprocessOutputFormat: " + reprocessOutputFormat);
        }

        try {
            setupImageReaders(inputSize, inputFormat, reprocessOutputSize, reprocessOutputFormat,
                    NUM_REPROCESS_CAPTURES);
            setupReprocessableSession(/*previewSurface*/null, NUM_REPROCESS_CAPTURES);

            // Test two cases: submitting reprocess requests one by one and in a burst.
            boolean submitInBursts[] = {false, true};
            for (boolean submitInBurst : submitInBursts) {
                // Prepare reprocess capture requests.
                ArrayList<CaptureRequest> reprocessRequests =
                        new ArrayList<>(NUM_REPROCESS_CAPTURES);

                for (int i = 0; i < NUM_REPROCESS_CAPTURES; i++) {
                    TotalCaptureResult result = submitCaptureRequest(mFirstImageReader.getSurface(),
                            /*inputResult*/null);

                    mImageWriter.queueInputImage(
                            mFirstImageReaderListener.getImage(CAPTURE_TIMEOUT_MS));
                    CaptureRequest.Builder builder = mCamera.createReprocessCaptureRequest(result);
                    builder.addTarget(getReprocessOutputImageReader().getSurface());
                    reprocessRequests.add(builder.build());
                }

                SimpleCaptureCallback captureCallback = new SimpleCaptureCallback();

                // Submit reprocess capture requests.
                if (submitInBurst) {
                    mSession.captureBurst(reprocessRequests, captureCallback, mHandler);
                } else {
                    for (CaptureRequest request : reprocessRequests) {
                        mSession.capture(request, captureCallback, mHandler);
                    }
                }

                // Abort after getting the first result
                TotalCaptureResult reprocessResult =
                        captureCallback.getTotalCaptureResultForRequest(reprocessRequests.get(0),
                        CAPTURE_TIMEOUT_FRAMES);
                mSession.abortCaptures();

                // Wait until the session is ready again.
                mSessionListener.getStateWaiter().waitForState(
                        BlockingSessionCallback.SESSION_READY, SESSION_CLOSE_TIMEOUT_MS);

                // Gather all failed requests.
                ArrayList<CaptureFailure> failures =
                        captureCallback.getCaptureFailures(NUM_REPROCESS_CAPTURES - 1);
                ArrayList<CaptureRequest> failedRequests = new ArrayList<>();
                for (CaptureFailure failure : failures) {
                    failedRequests.add(failure.getRequest());
                }

                // For each request that didn't fail must have a valid result.
                for (int i = 1; i < reprocessRequests.size(); i++) {
                    CaptureRequest request = reprocessRequests.get(i);
                    if (!failedRequests.contains(request)) {
                        captureCallback.getTotalCaptureResultForRequest(request,
                                CAPTURE_TIMEOUT_FRAMES);
                    }
                }

                // Drain the image reader listeners.
                mFirstImageReaderListener.drain();
                if (!mShareOneImageReader) {
                    mSecondImageReaderListener.drain();
                }

                // Make sure all input surfaces are released.
                for (int i = 0; i < NUM_REPROCESS_CAPTURES; i++) {
                    mImageWriterListener.waitForImageReleased(CAPTURE_TIMEOUT_MS);
                }
            }
        } finally {
            closeReprossibleSession();
            closeImageReaders();
        }
    }

    /**
     * Test timestamps for reprocess requests. Reprocess request's shutter timestamp, result's
     * sensor timestamp, and output image's timestamp should match the reprocess input's timestamp.
     */
    private void testReprocessTimestamps(String cameraId, Size inputSize, int inputFormat,
            Size reprocessOutputSize, int reprocessOutputFormat) throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "testReprocessTimestamps: cameraId: " + cameraId + " inputSize: " +
                    inputSize + " inputFormat: " + inputFormat + " reprocessOutputSize: " +
                    reprocessOutputSize + " reprocessOutputFormat: " + reprocessOutputFormat);
        }

        try {
            setupImageReaders(inputSize, inputFormat, reprocessOutputSize, reprocessOutputFormat,
                    NUM_REPROCESS_CAPTURES);
            setupReprocessableSession(/*previewSurface*/null, NUM_REPROCESS_CAPTURES);

            // Prepare reprocess capture requests.
            ArrayList<CaptureRequest> reprocessRequests = new ArrayList<>(NUM_REPROCESS_CAPTURES);
            ArrayList<Long> expectedTimestamps = new ArrayList<>(NUM_REPROCESS_CAPTURES);

            for (int i = 0; i < NUM_REPROCESS_CAPTURES; i++) {
                TotalCaptureResult result = submitCaptureRequest(mFirstImageReader.getSurface(),
                        /*inputResult*/null);

                mImageWriter.queueInputImage(
                        mFirstImageReaderListener.getImage(CAPTURE_TIMEOUT_MS));
                CaptureRequest.Builder builder = mCamera.createReprocessCaptureRequest(result);
                builder.addTarget(getReprocessOutputImageReader().getSurface());
                reprocessRequests.add(builder.build());
                // Reprocess result's timestamp should match input image's timestamp.
                expectedTimestamps.add(result.get(CaptureResult.SENSOR_TIMESTAMP));
            }

            // Submit reprocess requests.
            SimpleCaptureCallback captureCallback = new SimpleCaptureCallback();
            mSession.captureBurst(reprocessRequests, captureCallback, mHandler);

            // Verify we get the expected timestamps.
            for (int i = 0; i < reprocessRequests.size(); i++) {
                captureCallback.waitForCaptureStart(reprocessRequests.get(i),
                        expectedTimestamps.get(i), CAPTURE_TIMEOUT_FRAMES);
            }

            TotalCaptureResult[] reprocessResults =
                    captureCallback.getTotalCaptureResultsForRequests(reprocessRequests,
                    CAPTURE_TIMEOUT_FRAMES);

            for (int i = 0; i < expectedTimestamps.size(); i++) {
                // Verify the result timestamps match the input image's timestamps.
                long expected = expectedTimestamps.get(i);
                long timestamp = reprocessResults[i].get(CaptureResult.SENSOR_TIMESTAMP);
                assertEquals("Reprocess result timestamp (" + timestamp + ") doesn't match input " +
                        "image's timestamp (" + expected + ")", expected, timestamp);

                // Verify the reprocess output image timestamps match the input image's timestamps.
                Image image = getReprocessOutputImageReaderListener().getImage(CAPTURE_TIMEOUT_MS);
                timestamp = image.getTimestamp();
                image.close();

                assertEquals("Reprocess output timestamp (" + timestamp + ") doesn't match input " +
                        "image's timestamp (" + expected + ")", expected, timestamp);
            }

            // Make sure all input surfaces are released.
            for (int i = 0; i < NUM_REPROCESS_CAPTURES; i++) {
                mImageWriterListener.waitForImageReleased(CAPTURE_TIMEOUT_MS);
            }
        } finally {
            closeReprossibleSession();
            closeImageReaders();
        }
    }

    /**
     * Test JPEG tags for reprocess requests. Reprocess result's JPEG tags and JPEG image's tags
     * match reprocess request's JPEG tags.
     */
    private void testReprocessJpegExif(String cameraId, Size inputSize, int inputFormat,
            Size reprocessOutputSize) throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "testReprocessJpegExif: cameraId: " + cameraId + " inputSize: " +
                    inputSize + " inputFormat: " + inputFormat + " reprocessOutputSize: " +
                    reprocessOutputSize);
        }

        Size[] thumbnailSizes = mStaticInfo.getAvailableThumbnailSizesChecked();
        Size[] testThumbnailSizes = new Size[EXIF_TEST_DATA.length];
        Arrays.fill(testThumbnailSizes, thumbnailSizes[thumbnailSizes.length - 1]);
        // Make sure thumbnail size (0, 0) is covered.
        testThumbnailSizes[0] = new Size(0, 0);

        try {
            setupImageReaders(inputSize, inputFormat, reprocessOutputSize, ImageFormat.JPEG,
                    EXIF_TEST_DATA.length);
            setupReprocessableSession(/*previewSurface*/null, EXIF_TEST_DATA.length);

            // Prepare reprocess capture requests.
            ArrayList<CaptureRequest> reprocessRequests = new ArrayList<>(EXIF_TEST_DATA.length);

            for (int i = 0; i < EXIF_TEST_DATA.length; i++) {
                TotalCaptureResult result = submitCaptureRequest(mFirstImageReader.getSurface(),
                        /*inputResult*/null);
                mImageWriter.queueInputImage(
                        mFirstImageReaderListener.getImage(CAPTURE_TIMEOUT_MS));

                CaptureRequest.Builder builder = mCamera.createReprocessCaptureRequest(result);
                builder.addTarget(getReprocessOutputImageReader().getSurface());

                // set jpeg keys
                setJpegKeys(builder, EXIF_TEST_DATA[i], testThumbnailSizes[i], mCollector);
                reprocessRequests.add(builder.build());
            }

            // Submit reprocess requests.
            SimpleCaptureCallback captureCallback = new SimpleCaptureCallback();
            mSession.captureBurst(reprocessRequests, captureCallback, mHandler);

            TotalCaptureResult[] reprocessResults =
                    captureCallback.getTotalCaptureResultsForRequests(reprocessRequests,
                    CAPTURE_TIMEOUT_FRAMES);

            for (int i = 0; i < EXIF_TEST_DATA.length; i++) {
                // Verify output image's and result's JPEG EXIF data.
                Image image = getReprocessOutputImageReaderListener().getImage(CAPTURE_TIMEOUT_MS);
                verifyJpegKeys(image, reprocessResults[i], reprocessOutputSize,
                        testThumbnailSizes[i], EXIF_TEST_DATA[i], mStaticInfo, mCollector);
                image.close();

            }
        } finally {
            closeReprossibleSession();
            closeImageReaders();
        }
    }



    /**
     * Test the following keys in reprocess results match the keys in reprocess requests:
     *   1. EDGE_MODE
     *   2. NOISE_REDUCTION_MODE
     *   3. REPROCESS_EFFECTIVE_EXPOSURE_FACTOR (only for YUV reprocess)
     */
    private void testReprocessRequestKeys(String cameraId, Size inputSize, int inputFormat,
            Size reprocessOutputSize, int reprocessOutputFormat) throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "testReprocessRequestKeys: cameraId: " + cameraId + " inputSize: " +
                    inputSize + " inputFormat: " + inputFormat + " reprocessOutputSize: " +
                    reprocessOutputSize + " reprocessOutputFormat: " + reprocessOutputFormat);
        }

        final Integer[] EDGE_MODES = {CaptureRequest.EDGE_MODE_FAST,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY, CaptureRequest.EDGE_MODE_OFF,
                CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG};
        final Integer[] NR_MODES = {CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG,
                CaptureRequest.NOISE_REDUCTION_MODE_FAST};
        final Float[] EFFECTIVE_EXP_FACTORS = {null, 1.0f, 2.5f, 4.0f};
        int numFrames = EDGE_MODES.length;

        try {
            setupImageReaders(inputSize, inputFormat, reprocessOutputSize, reprocessOutputFormat,
                    numFrames);
            setupReprocessableSession(/*previewSurface*/null, numFrames);

            // Prepare reprocess capture requests.
            ArrayList<CaptureRequest> reprocessRequests = new ArrayList<>(numFrames);

            for (int i = 0; i < numFrames; i++) {
                TotalCaptureResult result = submitCaptureRequest(mFirstImageReader.getSurface(),
                        /*inputResult*/null);
                mImageWriter.queueInputImage(
                        mFirstImageReaderListener.getImage(CAPTURE_TIMEOUT_MS));

                CaptureRequest.Builder builder = mCamera.createReprocessCaptureRequest(result);
                builder.addTarget(getReprocessOutputImageReader().getSurface());

                // Set reprocess request keys
                builder.set(CaptureRequest.EDGE_MODE, EDGE_MODES[i]);
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, NR_MODES[i]);
                if (inputFormat == ImageFormat.YUV_420_888) {
                    builder.set(CaptureRequest.REPROCESS_EFFECTIVE_EXPOSURE_FACTOR,
                            EFFECTIVE_EXP_FACTORS[i]);
                }
                reprocessRequests.add(builder.build());
            }

            // Submit reprocess requests.
            SimpleCaptureCallback captureCallback = new SimpleCaptureCallback();
            mSession.captureBurst(reprocessRequests, captureCallback, mHandler);

            TotalCaptureResult[] reprocessResults =
                    captureCallback.getTotalCaptureResultsForRequests(reprocessRequests,
                    CAPTURE_TIMEOUT_FRAMES);

            for (int i = 0; i < numFrames; i++) {
                // Verify result's keys
                Integer resultEdgeMode = reprocessResults[i].get(CaptureResult.EDGE_MODE);
                Integer resultNoiseReductionMode =
                        reprocessResults[i].get(CaptureResult.NOISE_REDUCTION_MODE);

                assertEquals("Reprocess result edge mode (" + resultEdgeMode +
                        ") doesn't match requested edge mode (" + EDGE_MODES[i] + ")",
                        resultEdgeMode, EDGE_MODES[i]);
                assertEquals("Reprocess result noise reduction mode (" + resultNoiseReductionMode +
                        ") doesn't match requested noise reduction mode (" +
                        NR_MODES[i] + ")", resultNoiseReductionMode,
                        NR_MODES[i]);

                if (inputFormat == ImageFormat.YUV_420_888) {
                    Float resultEffectiveExposureFactor = reprocessResults[i].get(
                            CaptureResult.REPROCESS_EFFECTIVE_EXPOSURE_FACTOR);
                    assertEquals("Reprocess effective exposure factor (" +
                            resultEffectiveExposureFactor + ") doesn't match requested " +
                            "effective exposure factor (" + EFFECTIVE_EXP_FACTORS[i] + ")",
                            resultEffectiveExposureFactor, EFFECTIVE_EXP_FACTORS[i]);
                }
            }
        } finally {
            closeReprossibleSession();
            closeImageReaders();
        }
    }

    /**
     * Set up two image readers: one for regular capture (used for reprocess input) and one for
     * reprocess capture.
     */
    private void setupImageReaders(Size inputSize, int inputFormat, Size reprocessOutputSize,
            int reprocessOutputFormat, int maxImages) {

        mShareOneImageReader = false;
        // If the regular output and reprocess output have the same size and format,
        // they can share one image reader.
        if (inputFormat == reprocessOutputFormat &&
                inputSize.equals(reprocessOutputSize)) {
            maxImages *= 2;
            mShareOneImageReader = true;
        }
        // create an ImageReader for the regular capture
        mFirstImageReaderListener = new SimpleImageReaderListener();
        mFirstImageReader = makeImageReader(inputSize, inputFormat, maxImages,
                mFirstImageReaderListener, mHandler);

        if (!mShareOneImageReader) {
            // create an ImageReader for the reprocess capture
            mSecondImageReaderListener = new SimpleImageReaderListener();
            mSecondImageReader = makeImageReader(reprocessOutputSize, reprocessOutputFormat,
                    maxImages, mSecondImageReaderListener, mHandler);
        }
    }

    /**
     * Close two image readers.
     */
    private void closeImageReaders() {
        CameraTestUtils.closeImageReader(mFirstImageReader);
        mFirstImageReader = null;
        CameraTestUtils.closeImageReader(mSecondImageReader);
        mSecondImageReader = null;
    }

    /**
     * Get the ImageReader for reprocess output.
     */
    private ImageReader getReprocessOutputImageReader() {
        if (mShareOneImageReader) {
            return mFirstImageReader;
        } else {
            return mSecondImageReader;
        }
    }

    private SimpleImageReaderListener getReprocessOutputImageReaderListener() {
        if (mShareOneImageReader) {
            return mFirstImageReaderListener;
        } else {
            return mSecondImageReaderListener;
        }
    }

    /**
     * Set up a reprocessable session and create an ImageWriter with the sessoin's input surface.
     */
    private void setupReprocessableSession(Surface previewSurface, int numImageWriterImages)
            throws Exception {
        // create a reprocessable capture session
        List<Surface> outSurfaces = new ArrayList<Surface>();
        outSurfaces.add(mFirstImageReader.getSurface());
        if (!mShareOneImageReader) {
            outSurfaces.add(mSecondImageReader.getSurface());
        }
        if (previewSurface != null) {
            outSurfaces.add(previewSurface);
        }

        InputConfiguration inputConfig = new InputConfiguration(mFirstImageReader.getWidth(),
                mFirstImageReader.getHeight(), mFirstImageReader.getImageFormat());
        String inputConfigString = inputConfig.toString();
        if (VERBOSE) {
            Log.v(TAG, "InputConfiguration: " + inputConfigString);
        }
        assertTrue(String.format("inputConfig is wrong: %dx%d format %d. Expect %dx%d format %d",
                inputConfig.getWidth(), inputConfig.getHeight(), inputConfig.getFormat(),
                mFirstImageReader.getWidth(), mFirstImageReader.getHeight(),
                mFirstImageReader.getImageFormat()),
                inputConfig.getWidth() == mFirstImageReader.getWidth() &&
                inputConfig.getHeight() == mFirstImageReader.getHeight() &&
                inputConfig.getFormat() == mFirstImageReader.getImageFormat());

        mSessionListener = new BlockingSessionCallback();
        mSession = configureReprocessableCameraSession(mCamera, inputConfig, outSurfaces,
                mSessionListener, mHandler);

        // create an ImageWriter
        mInputSurface = mSession.getInputSurface();
        mImageWriter = ImageWriter.newInstance(mInputSurface,
                numImageWriterImages);

        mImageWriterListener = new SimpleImageWriterListener(mImageWriter);
        mImageWriter.setOnImageReleasedListener(mImageWriterListener, mHandler);
    }

    /**
     * Close the reprocessable session and ImageWriter.
     */
    private void closeReprossibleSession() {
        mInputSurface = null;

        if (mSession != null) {
            mSession.close();
            mSession = null;
        }

        if (mImageWriter != null) {
            mImageWriter.close();
            mImageWriter = null;
        }
    }

    /**
     * Do one reprocess capture.
     */
    private ImageResultHolder doReprocessCapture() throws Exception {
        return doReprocessBurstCapture(/*numBurst*/1)[0];
    }

    /**
     * Do a burst of reprocess captures.
     */
    private ImageResultHolder[] doReprocessBurstCapture(int numBurst) throws Exception {
        boolean[] isReprocessCaptures = new boolean[numBurst];
        for (int i = 0; i < numBurst; i++) {
            isReprocessCaptures[i] = true;
        }

        return doMixedReprocessBurstCapture(isReprocessCaptures);
    }

    /**
     * Do a burst of captures that are mixed with regular and reprocess captures.
     *
     * @param isReprocessCaptures An array whose elements indicate whether it's a reprocess capture
     *                            request. If the element is true, it represents a reprocess capture
     *                            request. If the element is false, it represents a regular capture
     *                            request. The size of the array is the number of capture requests
     *                            in the burst.
     */
    private ImageResultHolder[] doMixedReprocessBurstCapture(boolean[] isReprocessCaptures)
            throws Exception {
        if (isReprocessCaptures == null || isReprocessCaptures.length <= 0) {
            throw new IllegalArgumentException("isReprocessCaptures must have at least 1 capture.");
        }

        boolean hasReprocessRequest = false;
        boolean hasRegularRequest = false;

        TotalCaptureResult[] results = new TotalCaptureResult[isReprocessCaptures.length];
        for (int i = 0; i < isReprocessCaptures.length; i++) {
            // submit a capture and get the result if this entry is a reprocess capture.
            if (isReprocessCaptures[i]) {
                results[i] = submitCaptureRequest(mFirstImageReader.getSurface(),
                        /*inputResult*/null);
                mImageWriter.queueInputImage(
                        mFirstImageReaderListener.getImage(CAPTURE_TIMEOUT_MS));
                hasReprocessRequest = true;
            } else {
                hasRegularRequest = true;
            }
        }

        Surface[] outputSurfaces = new Surface[isReprocessCaptures.length];
        for (int i = 0; i < isReprocessCaptures.length; i++) {
            outputSurfaces[i] = getReprocessOutputImageReader().getSurface();
        }

        TotalCaptureResult[] finalResults = submitMixedCaptureBurstRequest(outputSurfaces, results);

        ImageResultHolder[] holders = new ImageResultHolder[isReprocessCaptures.length];
        for (int i = 0; i < isReprocessCaptures.length; i++) {
            Image image = getReprocessOutputImageReaderListener().getImage(CAPTURE_TIMEOUT_MS);
            if (hasReprocessRequest && hasRegularRequest) {
                // If there are mixed requests, images and results may not be in the same order.
                for (int j = 0; j < finalResults.length; j++) {
                    if (finalResults[j] != null &&
                            finalResults[j].get(CaptureResult.SENSOR_TIMESTAMP) ==
                            image.getTimestamp()) {
                        holders[i] = new ImageResultHolder(image, finalResults[j]);
                        finalResults[j] = null;
                        break;
                    }
                }

                assertNotNull("Cannot find a result matching output image's timestamp: " +
                        image.getTimestamp(), holders[i]);
            } else {
                // If no mixed requests, images and results should be in the same order.
                holders[i] = new ImageResultHolder(image, finalResults[i]);
            }
        }

        return holders;
    }

    /**
     * Start preview without a listener.
     */
    private void startPreview(Surface previewSurface) throws Exception {
        CaptureRequest.Builder builder = mCamera.createCaptureRequest(ZSL_TEMPLATE);
        builder.addTarget(previewSurface);
        mSession.setRepeatingRequest(builder.build(), null, mHandler);
    }

    /**
     * Issue a capture request and return the result. If inputResult is null, it's a regular
     * request. Otherwise, it's a reprocess request.
     */
    private TotalCaptureResult submitCaptureRequest(Surface output,
            TotalCaptureResult inputResult) throws Exception {
        Surface[] outputs = new Surface[1];
        outputs[0] = output;
        TotalCaptureResult[] inputResults = new TotalCaptureResult[1];
        inputResults[0] = inputResult;

        return submitMixedCaptureBurstRequest(outputs, inputResults)[0];
    }

    /**
     * Submit a burst request mixed with regular and reprocess requests.
     *
     * @param outputs An array of output surfaces. One output surface will be used in one request
     *                so the length of the array is the number of requests in a burst request.
     * @param inputResults An array of input results. If it's null, all requests are regular
     *                     requests. If an element is null, that element represents a regular
     *                     request. If an element if not null, that element represents a reprocess
     *                     request.
     *
     */
    private TotalCaptureResult[] submitMixedCaptureBurstRequest(Surface[] outputs,
            TotalCaptureResult[] inputResults) throws Exception {
        if (outputs == null || outputs.length <= 0) {
            throw new IllegalArgumentException("outputs must have at least 1 surface");
        } else if (inputResults != null && inputResults.length != outputs.length) {
            throw new IllegalArgumentException("The lengths of outputs and inputResults " +
                    "don't match");
        }

        int numReprocessCaptures = 0;
        SimpleCaptureCallback captureCallback = new SimpleCaptureCallback();
        ArrayList<CaptureRequest> captureRequests = new ArrayList<>(outputs.length);

        // Prepare a list of capture requests. Whether it's a regular or reprocess capture request
        // is based on inputResults array.
        for (int i = 0; i < outputs.length; i++) {
            CaptureRequest.Builder builder;
            boolean isReprocess = (inputResults != null && inputResults[i] != null);
            if (isReprocess) {
                builder = mCamera.createReprocessCaptureRequest(inputResults[i]);
                numReprocessCaptures++;
            } else {
                builder = mCamera.createCaptureRequest(CAPTURE_TEMPLATE);
            }
            builder.addTarget(outputs[i]);
            CaptureRequest request = builder.build();
            assertTrue("Capture request reprocess type " + request.isReprocess() + " is wrong.",
                request.isReprocess() == isReprocess);

            captureRequests.add(request);
        }

        if (captureRequests.size() == 1) {
            mSession.capture(captureRequests.get(0), captureCallback, mHandler);
        } else {
            mSession.captureBurst(captureRequests, captureCallback, mHandler);
        }

        TotalCaptureResult[] results;
        if (numReprocessCaptures == 0 || numReprocessCaptures == outputs.length) {
            results = new TotalCaptureResult[outputs.length];
            // If the requests are not mixed, they should come in order.
            for (int i = 0; i < results.length; i++){
                results[i] = captureCallback.getTotalCaptureResultForRequest(
                        captureRequests.get(i), CAPTURE_TIMEOUT_FRAMES);
            }
        } else {
            // If the requests are mixed, they may not come in order.
            results = captureCallback.getTotalCaptureResultsForRequests(
                    captureRequests, CAPTURE_TIMEOUT_FRAMES * captureRequests.size());
        }

        // make sure all input surfaces are released.
        for (int i = 0; i < numReprocessCaptures; i++) {
            mImageWriterListener.waitForImageReleased(CAPTURE_TIMEOUT_MS);
        }

        return results;
    }

    private Size getMaxSize(int format, StaticMetadata.StreamDirection direction) {
        Size[] sizes = mStaticInfo.getAvailableSizesForFormatChecked(format, direction);
        return getAscendingOrderSizes(Arrays.asList(sizes), /*ascending*/false).get(0);
    }

    private boolean isYuvReprocessSupported(String cameraId) throws Exception {
        return isReprocessSupported(cameraId, ImageFormat.YUV_420_888);
    }

    private boolean isOpaqueReprocessSupported(String cameraId) throws Exception {
        return isReprocessSupported(cameraId, ImageFormat.PRIVATE);
    }

    private void dumpImage(Image image, String name) {
        String filename = DEBUG_FILE_NAME_BASE + name;
        switch(image.getFormat()) {
            case ImageFormat.JPEG:
                filename += ".jpg";
                break;
            case ImageFormat.NV16:
            case ImageFormat.NV21:
            case ImageFormat.YUV_420_888:
                filename += ".yuv";
                break;
            default:
                filename += "." + image.getFormat();
                break;
        }

        Log.d(TAG, "dumping an image to " + filename);
        dumpFile(filename , getDataFromImage(image));
    }

    /**
     * A class that holds an Image and a TotalCaptureResult.
     */
    private static class ImageResultHolder {
        private final Image mImage;
        private final TotalCaptureResult mResult;

        public ImageResultHolder(Image image, TotalCaptureResult result) {
            mImage = image;
            mResult = result;
        }

        public Image getImage() {
            return mImage;
        }

        public TotalCaptureResult getTotalCaptureResult() {
            return mResult;
        }
    }
}
