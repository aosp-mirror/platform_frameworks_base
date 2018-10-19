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

import junit.framework.AssertionFailedError;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static com.android.ex.camera2.blocking.BlockingSessionCallback.SESSION_CLOSED;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.CAPTURE_IMAGE_TIMEOUT_MS;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SESSION_CLOSE_TIMEOUT_MS;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SIZE_BOUND_1080P;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SIZE_BOUND_2160P;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleCaptureCallback;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleImageReaderListener;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.configureCameraSession;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.configureCameraSessionWithParameters;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.getSupportedVideoSizes;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.getValueNotNull;

/**
 * CameraDevice video recording use case tests by using MediaRecorder and
 * MediaCodec.
 *
 * adb shell am instrument \
 *    -e class com.android.mediaframeworktest.stress.Camera2RecordingTest#testBasicRecording \
 *    -e iterations 10 \
 *    -e waitIntervalMs 1000 \
 *    -e resultToFile false \
 *    -r -w com.android.mediaframeworktest/.Camera2InstrumentationTestRunner
 */
@LargeTest
public class Camera2RecordingTest extends Camera2SurfaceViewTestCase {
    private static final String TAG = "RecordingTest";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final boolean DEBUG_DUMP = Log.isLoggable(TAG, Log.DEBUG);
    private static final int RECORDING_DURATION_MS = 3000;
    private static final float DURATION_MARGIN = 0.2f;
    private static final double FRAME_DURATION_ERROR_TOLERANCE_MS = 3.0;
    private static final int BIT_RATE_1080P = 16000000;
    private static final int BIT_RATE_MIN = 64000;
    private static final int BIT_RATE_MAX = 40000000;
    private static final int VIDEO_FRAME_RATE = 30;
    private final String VIDEO_FILE_PATH = Environment.getExternalStorageDirectory().getPath();
    private static final int[] mCamcorderProfileList = {
            CamcorderProfile.QUALITY_HIGH,
            CamcorderProfile.QUALITY_2160P,
            CamcorderProfile.QUALITY_1080P,
            CamcorderProfile.QUALITY_720P,
            CamcorderProfile.QUALITY_480P,
            CamcorderProfile.QUALITY_CIF,
            CamcorderProfile.QUALITY_QCIF,
            CamcorderProfile.QUALITY_QVGA,
            CamcorderProfile.QUALITY_LOW,
    };
    private static final int MAX_VIDEO_SNAPSHOT_IMAGES = 5;
    private static final int BURST_VIDEO_SNAPSHOT_NUM = 3;
    private static final int SLOWMO_SLOW_FACTOR = 4;
    private static final int MAX_NUM_FRAME_DROP_INTERVAL_ALLOWED = 4;
    private List<Size> mSupportedVideoSizes;
    private Surface mRecordingSurface;
    private Surface mPersistentSurface;
    private MediaRecorder mMediaRecorder;
    private String mOutMediaFileName;
    private int mVideoFrameRate;
    private Size mVideoSize;
    private long mRecordingStartTime;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    private void doBasicRecording(boolean useVideoStab) throws Exception {
        for (int i = 0; i < mCameraIds.length; i++) {
            try {
                Log.i(TAG, "Testing basic recording for camera " + mCameraIds[i]);
                // Re-use the MediaRecorder object for the same camera device.
                mMediaRecorder = new MediaRecorder();
                openDevice(mCameraIds[i]);
                if (!mStaticInfo.isColorOutputSupported()) {
                    Log.i(TAG, "Camera " + mCameraIds[i] +
                            " does not support color outputs, skipping");
                    continue;
                }

                if (!mStaticInfo.isVideoStabilizationSupported() && useVideoStab) {
                    Log.i(TAG, "Camera " + mCameraIds[i] +
                            " does not support video stabilization, skipping the stabilization"
                            + " test");
                    continue;
                }

                initSupportedVideoSize(mCameraIds[i]);

                // Test iteration starts...
                for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                    Log.v(TAG, String.format("Recording video: %d/%d", iteration + 1,
                            getIterationCount()));
                    basicRecordingTestByCamera(mCamcorderProfileList, useVideoStab);
                    getResultPrinter().printStatus(getIterationCount(), iteration + 1,
                            mCameraIds[i]);
                    Thread.sleep(getTestWaitIntervalMs());
                }
            } finally {
                closeDevice();
                releaseRecorder();
            }
        }
    }

    /**
     * <p>
     * Test basic camera recording.
     * </p>
     * <p>
     * This test covers the typical basic use case of camera recording.
     * MediaRecorder is used to record the audio and video, CamcorderProfile is
     * used to configure the MediaRecorder. It goes through the pre-defined
     * CamcorderProfile list, test each profile configuration and validate the
     * recorded video. Preview is set to the video size.
     * </p>
     */
    public void testBasicRecording() throws Exception {
        doBasicRecording(/*useVideoStab*/false);
    }

    /**
     * <p>
     * Test video snapshot for each camera.
     * </p>
     * <p>
     * This test covers video snapshot typical use case. The MediaRecorder is used to record the
     * video for each available video size. The largest still capture size is selected to
     * capture the JPEG image. The still capture images are validated according to the capture
     * configuration. The timestamp of capture result before and after video snapshot is also
     * checked to make sure no frame drop caused by video snapshot.
     * </p>
     */
    public void testVideoSnapshot() throws Exception {
        videoSnapshotHelper(/*burstTest*/false);
    }

    public void testConstrainedHighSpeedRecording() throws Exception {
        constrainedHighSpeedRecording();
    }

    private void constrainedHighSpeedRecording() throws Exception {
        for (String id : mCameraIds) {
            try {
                Log.i(TAG, "Testing constrained high speed recording for camera " + id);
                // Re-use the MediaRecorder object for the same camera device.
                mMediaRecorder = new MediaRecorder();
                openDevice(id);

                if (!mStaticInfo.isConstrainedHighSpeedVideoSupported()) {
                    Log.i(TAG, "Camera " + id + " doesn't support high speed recording, skipping.");
                    continue;
                }

                // Test iteration starts...
                for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                    Log.v(TAG, String.format("Constrained high speed recording: %d/%d",
                            iteration + 1, getIterationCount()));

                    StreamConfigurationMap config =
                            mStaticInfo.getValueFromKeyNonNull(
                                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    Size[] highSpeedVideoSizes = config.getHighSpeedVideoSizes();
                    for (Size size : highSpeedVideoSizes) {
                        List<Range<Integer>> fixedFpsRanges =
                                getHighSpeedFixedFpsRangeForSize(config, size);
                        mCollector.expectTrue("Unable to find the fixed frame rate fps range for " +
                                "size " + size, fixedFpsRanges.size() > 0);
                        // Test recording for each FPS range
                        for (Range<Integer> fpsRange : fixedFpsRanges) {
                            int captureRate = fpsRange.getLower();
                            final int VIDEO_FRAME_RATE = 30;
                            // Skip the test if the highest recording FPS supported by CamcorderProfile
                            if (fpsRange.getUpper() > getFpsFromHighSpeedProfileForSize(size)) {
                                Log.w(TAG, "high speed recording " + size + "@" + captureRate + "fps"
                                        + " is not supported by CamcorderProfile");
                                continue;
                            }

                            mOutMediaFileName = VIDEO_FILE_PATH + "/test_cslowMo_video_" + captureRate +
                                    "fps_" + id + "_" + size.toString() + ".mp4";

                            prepareRecording(size, VIDEO_FRAME_RATE, captureRate);

                            // prepare preview surface by using video size.
                            updatePreviewSurfaceWithVideo(size, captureRate);

                            // Start recording
                            SimpleCaptureCallback resultListener = new SimpleCaptureCallback();
                            startSlowMotionRecording(/*useMediaRecorder*/true, VIDEO_FRAME_RATE,
                                    captureRate, fpsRange, resultListener,
                                    /*useHighSpeedSession*/true);

                            // Record certain duration.
                            SystemClock.sleep(RECORDING_DURATION_MS);

                            // Stop recording and preview
                            stopRecording(/*useMediaRecorder*/true);
                            // Convert number of frames camera produced into the duration in unit of ms.
                            int durationMs = (int) (resultListener.getTotalNumFrames() * 1000.0f /
                                            VIDEO_FRAME_RATE);

                            // Validation.
                            validateRecording(size, durationMs);
                        }

                    getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
                    Thread.sleep(getTestWaitIntervalMs());
                    }
                }

            } finally {
                closeDevice();
                releaseRecorder();
            }
        }
    }

    /**
     * Get high speed FPS from CamcorderProfiles for a given size.
     *
     * @param size The size used to search the CamcorderProfiles for the FPS.
     * @return high speed video FPS, 0 if the given size is not supported by the CamcorderProfiles.
     */
    private int getFpsFromHighSpeedProfileForSize(Size size) {
        for (int quality = CamcorderProfile.QUALITY_HIGH_SPEED_480P;
                quality <= CamcorderProfile.QUALITY_HIGH_SPEED_2160P; quality++) {
            if (CamcorderProfile.hasProfile(quality)) {
                CamcorderProfile profile = CamcorderProfile.get(quality);
                if (size.equals(new Size(profile.videoFrameWidth, profile.videoFrameHeight))){
                    return profile.videoFrameRate;
                }
            }
        }

        return 0;
    }

    private List<Range<Integer>> getHighSpeedFixedFpsRangeForSize(StreamConfigurationMap config,
            Size size) {
        Range<Integer>[] availableFpsRanges = config.getHighSpeedVideoFpsRangesFor(size);
        List<Range<Integer>> fixedRanges = new ArrayList<Range<Integer>>();
        for (Range<Integer> range : availableFpsRanges) {
            if (range.getLower().equals(range.getUpper())) {
                fixedRanges.add(range);
            }
        }
        return fixedRanges;
    }

    private void startSlowMotionRecording(boolean useMediaRecorder, int videoFrameRate,
            int captureRate, Range<Integer> fpsRange,
            CameraCaptureSession.CaptureCallback listener, boolean useHighSpeedSession) throws Exception {
        List<Surface> outputSurfaces = new ArrayList<Surface>(2);
        assertTrue("Both preview and recording surfaces should be valid",
                mPreviewSurface.isValid() && mRecordingSurface.isValid());
        outputSurfaces.add(mPreviewSurface);
        outputSurfaces.add(mRecordingSurface);
        // Video snapshot surface
        if (mReaderSurface != null) {
            outputSurfaces.add(mReaderSurface);
        }
        mSessionListener = new BlockingSessionCallback();
        mSession = configureCameraSession(mCamera, outputSurfaces, useHighSpeedSession,
                mSessionListener, mHandler);

        // Create slow motion request list
        List<CaptureRequest> slowMoRequests = null;
        if (useHighSpeedSession) {
            CaptureRequest.Builder requestBuilder =
                    mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            requestBuilder.addTarget(mPreviewSurface);
            requestBuilder.addTarget(mRecordingSurface);
            slowMoRequests = ((CameraConstrainedHighSpeedCaptureSession) mSession).
                    createHighSpeedRequestList(requestBuilder.build());
        } else {
            CaptureRequest.Builder recordingRequestBuilder =
                    mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            recordingRequestBuilder.set(CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
            recordingRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO);

            CaptureRequest.Builder recordingOnlyBuilder =
                    mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            recordingOnlyBuilder.set(CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
            recordingOnlyBuilder.set(CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO);
            int slowMotionFactor = captureRate / videoFrameRate;

            // Make sure camera output frame rate is set to correct value.
            recordingRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            recordingRequestBuilder.addTarget(mRecordingSurface);
            recordingRequestBuilder.addTarget(mPreviewSurface);
            recordingOnlyBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            recordingOnlyBuilder.addTarget(mRecordingSurface);

            slowMoRequests = new ArrayList<CaptureRequest>();
            slowMoRequests.add(recordingRequestBuilder.build());// Preview + recording.

            for (int i = 0; i < slowMotionFactor - 1; i++) {
                slowMoRequests.add(recordingOnlyBuilder.build()); // Recording only.
            }
        }

        mSession.setRepeatingBurst(slowMoRequests, listener, mHandler);

        if (useMediaRecorder) {
            mMediaRecorder.start();
        } else {
            // TODO: need implement MediaCodec path.
        }

    }

    /**
     * Test camera recording by using each available CamcorderProfile for a
     * given camera. preview size is set to the video size.
     */
    private void basicRecordingTestByCamera(int[] camcorderProfileList, boolean useVideoStab)
            throws Exception {
        Size maxPreviewSize = mOrderedPreviewSizes.get(0);
        List<Range<Integer> > fpsRanges = Arrays.asList(
                mStaticInfo.getAeAvailableTargetFpsRangesChecked());
        int cameraId = Integer.parseInt(mCamera.getId());
        int maxVideoFrameRate = -1;
        for (int profileId : camcorderProfileList) {
            if (!CamcorderProfile.hasProfile(cameraId, profileId) ||
                    allowedUnsupported(cameraId, profileId)) {
                continue;
            }

            CamcorderProfile profile = CamcorderProfile.get(cameraId, profileId);
            Size videoSz = new Size(profile.videoFrameWidth, profile.videoFrameHeight);
            Range<Integer> fpsRange = new Range(profile.videoFrameRate, profile.videoFrameRate);
            if (maxVideoFrameRate < profile.videoFrameRate) {
                maxVideoFrameRate = profile.videoFrameRate;
            }
            if (mStaticInfo.isHardwareLevelLegacy() &&
                    (videoSz.getWidth() > maxPreviewSize.getWidth() ||
                     videoSz.getHeight() > maxPreviewSize.getHeight())) {
                // Skip. Legacy mode can only do recording up to max preview size
                continue;
            }
            assertTrue("Video size " + videoSz.toString() + " for profile ID " + profileId +
                            " must be one of the camera device supported video size!",
                            mSupportedVideoSizes.contains(videoSz));
            assertTrue("Frame rate range " + fpsRange + " (for profile ID " + profileId +
                    ") must be one of the camera device available FPS range!",
                    fpsRanges.contains(fpsRange));

            if (VERBOSE) {
                Log.v(TAG, "Testing camera recording with video size " + videoSz.toString());
            }

            // Configure preview and recording surfaces.
            mOutMediaFileName = VIDEO_FILE_PATH + "/test_video.mp4";
            if (DEBUG_DUMP) {
                mOutMediaFileName = VIDEO_FILE_PATH + "/test_video_" + cameraId + "_"
                        + videoSz.toString() + ".mp4";
            }

            prepareRecordingWithProfile(profile);

            // prepare preview surface by using video size.
            updatePreviewSurfaceWithVideo(videoSz, profile.videoFrameRate);

            // Start recording
            SimpleCaptureCallback resultListener = new SimpleCaptureCallback();
            startRecording(/* useMediaRecorder */true, resultListener, useVideoStab);

            // Record certain duration.
            SystemClock.sleep(RECORDING_DURATION_MS);

            // Stop recording and preview
            stopRecording(/* useMediaRecorder */true);
            // Convert number of frames camera produced into the duration in unit of ms.
            int durationMs = (int) (resultListener.getTotalNumFrames() * 1000.0f /
                            profile.videoFrameRate);

            if (VERBOSE) {
                Log.v(TAG, "video frame rate: " + profile.videoFrameRate +
                                ", num of frames produced: " + resultListener.getTotalNumFrames());
            }

            // Validation.
            validateRecording(videoSz, durationMs);
        }
        if (maxVideoFrameRate != -1) {
            // At least one CamcorderProfile is present, check FPS
            assertTrue("At least one CamcorderProfile must support >= 24 FPS",
                    maxVideoFrameRate >= 24);
        }
    }

    /**
     * Initialize the supported video sizes.
     */
    private void initSupportedVideoSize(String cameraId)  throws Exception {
        Size maxVideoSize = SIZE_BOUND_1080P;
        if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_2160P)) {
            maxVideoSize = SIZE_BOUND_2160P;
        }
        mSupportedVideoSizes =
                getSupportedVideoSizes(cameraId, mCameraManager, maxVideoSize);
    }

    /**
     * Simple wrapper to wrap normal/burst video snapshot tests
     */
    private void videoSnapshotHelper(boolean burstTest) throws Exception {
            for (String id : mCameraIds) {
                try {
                    Log.i(TAG, "Testing video snapshot for camera " + id);
                    // Re-use the MediaRecorder object for the same camera device.
                    mMediaRecorder = new MediaRecorder();

                    openDevice(id);

                    if (!mStaticInfo.isColorOutputSupported()) {
                        Log.i(TAG, "Camera " + id +
                                " does not support color outputs, skipping");
                        continue;
                    }

                    initSupportedVideoSize(id);

                    // Test iteration starts...
                    for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
                        Log.v(TAG, String.format("Video snapshot: %d/%d", iteration + 1,
                                getIterationCount()));
                        videoSnapshotTestByCamera(burstTest);
                        getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
                        Thread.sleep(getTestWaitIntervalMs());
                    }
                } finally {
                    closeDevice();
                    releaseRecorder();
                }
            }
    }

    /**
     * Returns {@code true} if the {@link CamcorderProfile} ID is allowed to be unsupported.
     *
     * <p>This only allows unsupported profiles when using the LEGACY mode of the Camera API.</p>
     *
     * @param profileId a {@link CamcorderProfile} ID to check.
     * @return {@code true} if supported.
     */
    private boolean allowedUnsupported(int cameraId, int profileId) {
        if (!mStaticInfo.isHardwareLevelLegacy()) {
            return false;
        }

        switch(profileId) {
            case CamcorderProfile.QUALITY_2160P:
            case CamcorderProfile.QUALITY_1080P:
            case CamcorderProfile.QUALITY_HIGH:
                return !CamcorderProfile.hasProfile(cameraId, profileId) ||
                        CamcorderProfile.get(cameraId, profileId).videoFrameWidth >= 1080;
        }
        return false;
    }

    /**
     * Test video snapshot for each  available CamcorderProfile for a given camera.
     *
     * <p>
     * Preview size is set to the video size. For the burst test, frame drop and jittering
     * is not checked.
     * </p>
     *
     * @param burstTest Perform burst capture or single capture. For burst capture
     *                  {@value #BURST_VIDEO_SNAPSHOT_NUM} capture requests will be sent.
     */
    private void videoSnapshotTestByCamera(boolean burstTest)
            throws Exception {
        final int NUM_SINGLE_SHOT_TEST = 5;
        final int FRAMEDROP_TOLERANCE = 8;
        final int FRAME_SIZE_15M = 15000000;
        final float FRAME_DROP_TOLERENCE_FACTOR = 1.5f;
        int kFrameDrop_Tolerence = FRAMEDROP_TOLERANCE;

        for (int profileId : mCamcorderProfileList) {
            int cameraId = Integer.parseInt(mCamera.getId());
            if (!CamcorderProfile.hasProfile(cameraId, profileId) ||
                    allowedUnsupported(cameraId, profileId)) {
                continue;
            }

            CamcorderProfile profile = CamcorderProfile.get(cameraId, profileId);
            Size videoSz = new Size(profile.videoFrameWidth, profile.videoFrameHeight);
            Size maxPreviewSize = mOrderedPreviewSizes.get(0);

            if (mStaticInfo.isHardwareLevelLegacy() &&
                    (videoSz.getWidth() > maxPreviewSize.getWidth() ||
                     videoSz.getHeight() > maxPreviewSize.getHeight())) {
                // Skip. Legacy mode can only do recording up to max preview size
                continue;
            }

            if (!mSupportedVideoSizes.contains(videoSz)) {
                mCollector.addMessage("Video size " + videoSz.toString() + " for profile ID " +
                        profileId + " must be one of the camera device supported video size!");
                continue;
            }

            // For LEGACY, find closest supported smaller or equal JPEG size to the current video
            // size; if no size is smaller than the video, pick the smallest JPEG size.  The assert
            // for video size above guarantees that for LIMITED or FULL, we select videoSz here.
            // Also check for minFrameDuration here to make sure jpeg stream won't slow down
            // video capture
            Size videoSnapshotSz = mOrderedStillSizes.get(mOrderedStillSizes.size() - 1);
            // Allow a bit tolerance so we don't fail for a few nano seconds of difference
            final float FRAME_DURATION_TOLERANCE = 0.01f;
            long videoFrameDuration = (long) (1e9 / profile.videoFrameRate *
                    (1.0 + FRAME_DURATION_TOLERANCE));
            HashMap<Size, Long> minFrameDurationMap = mStaticInfo.
                    getAvailableMinFrameDurationsForFormatChecked(ImageFormat.JPEG);
            for (int i = mOrderedStillSizes.size() - 2; i >= 0; i--) {
                Size candidateSize = mOrderedStillSizes.get(i);
                if (mStaticInfo.isHardwareLevelLegacy()) {
                    // Legacy level doesn't report min frame duration
                    if (candidateSize.getWidth() <= videoSz.getWidth() &&
                            candidateSize.getHeight() <= videoSz.getHeight()) {
                        videoSnapshotSz = candidateSize;
                    }
                } else {
                    Long jpegFrameDuration = minFrameDurationMap.get(candidateSize);
                    assertTrue("Cannot find minimum frame duration for jpeg size " + candidateSize,
                            jpegFrameDuration != null);
                    if (candidateSize.getWidth() <= videoSz.getWidth() &&
                            candidateSize.getHeight() <= videoSz.getHeight() &&
                            jpegFrameDuration <= videoFrameDuration) {
                        videoSnapshotSz = candidateSize;
                    }
                }
            }

            /**
             * Only test full res snapshot when below conditions are all true.
             * 1. Camera is a FULL device
             * 2. video size is up to max preview size, which will be bounded by 1080p.
             * 3. Full resolution jpeg stream can keep up to video stream speed.
             *    When full res jpeg stream cannot keep up to video stream speed, search
             *    the largest jpeg size that can susptain video speed instead.
             */
            if (mStaticInfo.isHardwareLevelFull() &&
                    videoSz.getWidth() <= maxPreviewSize.getWidth() &&
                    videoSz.getHeight() <= maxPreviewSize.getHeight()) {
                for (Size jpegSize : mOrderedStillSizes) {
                    Long jpegFrameDuration = minFrameDurationMap.get(jpegSize);
                    assertTrue("Cannot find minimum frame duration for jpeg size " + jpegSize,
                            jpegFrameDuration != null);
                    if (jpegFrameDuration <= videoFrameDuration) {
                        videoSnapshotSz = jpegSize;
                        break;
                    }
                    if (jpegSize.equals(videoSz)) {
                        throw new AssertionFailedError(
                                "Cannot find adequate video snapshot size for video size" +
                                        videoSz);
                    }
                }
            }

            Log.i(TAG, "Testing video snapshot size " + videoSnapshotSz +
                    " for video size " + videoSz);
            if (videoSnapshotSz.getWidth() * videoSnapshotSz.getHeight() > FRAME_SIZE_15M)
                kFrameDrop_Tolerence = (int)(FRAMEDROP_TOLERANCE * FRAME_DROP_TOLERENCE_FACTOR);

            createImageReader(
                    videoSnapshotSz, ImageFormat.JPEG,
                    MAX_VIDEO_SNAPSHOT_IMAGES, /*listener*/null);

            if (VERBOSE) {
                Log.v(TAG, "Testing camera recording with video size " + videoSz.toString());
            }

            // Configure preview and recording surfaces.
            mOutMediaFileName = VIDEO_FILE_PATH + "/test_video.mp4";
            if (DEBUG_DUMP) {
                mOutMediaFileName = VIDEO_FILE_PATH + "/test_video_" + cameraId + "_"
                        + videoSz.toString() + ".mp4";
            }

            int numTestIterations = burstTest ? 1 : NUM_SINGLE_SHOT_TEST;
            int totalDroppedFrames = 0;

            for (int numTested = 0; numTested < numTestIterations; numTested++) {
                prepareRecordingWithProfile(profile);

                // prepare video snapshot
                SimpleCaptureCallback resultListener = new SimpleCaptureCallback();
                SimpleImageReaderListener imageListener = new SimpleImageReaderListener();
                CaptureRequest.Builder videoSnapshotRequestBuilder =
                        mCamera.createCaptureRequest((mStaticInfo.isHardwareLevelLegacy()) ?
                                CameraDevice.TEMPLATE_RECORD :
                                CameraDevice.TEMPLATE_VIDEO_SNAPSHOT);

                // prepare preview surface by using video size.
                updatePreviewSurfaceWithVideo(videoSz, profile.videoFrameRate);

                prepareVideoSnapshot(videoSnapshotRequestBuilder, imageListener);
                Range<Integer> fpsRange = Range.create(profile.videoFrameRate,
                        profile.videoFrameRate);
                videoSnapshotRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        fpsRange);
                CaptureRequest request = videoSnapshotRequestBuilder.build();

                // Start recording
                startRecording(/* useMediaRecorder */true, resultListener, /*useVideoStab*/false);
                long startTime = SystemClock.elapsedRealtime();

                // Record certain duration.
                SystemClock.sleep(RECORDING_DURATION_MS / 2);

                // take video snapshot
                if (burstTest) {
                    List<CaptureRequest> requests =
                            new ArrayList<CaptureRequest>(BURST_VIDEO_SNAPSHOT_NUM);
                    for (int i = 0; i < BURST_VIDEO_SNAPSHOT_NUM; i++) {
                        requests.add(request);
                    }
                    mSession.captureBurst(requests, resultListener, mHandler);
                } else {
                    mSession.capture(request, resultListener, mHandler);
                }

                // make sure recording is still going after video snapshot
                SystemClock.sleep(RECORDING_DURATION_MS / 2);

                // Stop recording and preview
                int durationMs = stopRecording(/* useMediaRecorder */true);
                // For non-burst test, use number of frames to also double check video frame rate.
                // Burst video snapshot is allowed to cause frame rate drop, so do not use number
                // of frames to estimate duration
                if (!burstTest) {
                    durationMs = (int) (resultListener.getTotalNumFrames() * 1000.0f /
                        profile.videoFrameRate);
                }

                // Validation recorded video
                validateRecording(videoSz, durationMs);

                if (burstTest) {
                    for (int i = 0; i < BURST_VIDEO_SNAPSHOT_NUM; i++) {
                        Image image = imageListener.getImage(CAPTURE_IMAGE_TIMEOUT_MS);
                        validateVideoSnapshotCapture(image, videoSnapshotSz);
                        image.close();
                    }
                } else {
                    // validate video snapshot image
                    Image image = imageListener.getImage(CAPTURE_IMAGE_TIMEOUT_MS);
                    validateVideoSnapshotCapture(image, videoSnapshotSz);

                    // validate if there is framedrop around video snapshot
                    totalDroppedFrames +=  validateFrameDropAroundVideoSnapshot(
                            resultListener, image.getTimestamp());

                    //TODO: validate jittering. Should move to PTS
                    //validateJittering(resultListener);

                    image.close();
                }
            }

            if (!burstTest) {
                Log.w(TAG, String.format("Camera %d Video size %s: Number of dropped frames " +
                        "detected in %d trials is %d frames.", cameraId, videoSz.toString(),
                        numTestIterations, totalDroppedFrames));
                mCollector.expectLessOrEqual(
                        String.format(
                                "Camera %d Video size %s: Number of dropped frames %d must not"
                                + " be larger than %d",
                                cameraId, videoSz.toString(), totalDroppedFrames,
                                kFrameDrop_Tolerence),
                        kFrameDrop_Tolerence, totalDroppedFrames);
            }
            closeImageReader();
        }
    }

    /**
     * Configure video snapshot request according to the still capture size
     */
    private void prepareVideoSnapshot(
            CaptureRequest.Builder requestBuilder,
            ImageReader.OnImageAvailableListener imageListener)
            throws Exception {
        mReader.setOnImageAvailableListener(imageListener, mHandler);
        assertNotNull("Recording surface must be non-null!", mRecordingSurface);
        requestBuilder.addTarget(mRecordingSurface);
        assertNotNull("Preview surface must be non-null!", mPreviewSurface);
        requestBuilder.addTarget(mPreviewSurface);
        assertNotNull("Reader surface must be non-null!", mReaderSurface);
        requestBuilder.addTarget(mReaderSurface);
    }

    /**
     * Update preview size with video size.
     *
     * <p>Preview size will be capped with max preview size.</p>
     *
     * @param videoSize The video size used for preview.
     * @param videoFrameRate The video frame rate
     *
     */
    private void updatePreviewSurfaceWithVideo(Size videoSize, int videoFrameRate) {
        if (mOrderedPreviewSizes == null) {
            throw new IllegalStateException("supported preview size list is not initialized yet");
        }
        final float FRAME_DURATION_TOLERANCE = 0.01f;
        long videoFrameDuration = (long) (1e9 / videoFrameRate *
                (1.0 + FRAME_DURATION_TOLERANCE));
        HashMap<Size, Long> minFrameDurationMap = mStaticInfo.
                getAvailableMinFrameDurationsForFormatChecked(ImageFormat.PRIVATE);
        Size maxPreviewSize = mOrderedPreviewSizes.get(0);
        Size previewSize = null;
        if (videoSize.getWidth() > maxPreviewSize.getWidth() ||
                videoSize.getHeight() > maxPreviewSize.getHeight()) {
            for (Size s : mOrderedPreviewSizes) {
                Long frameDuration = minFrameDurationMap.get(s);
                if (mStaticInfo.isHardwareLevelLegacy()) {
                    // Legacy doesn't report min frame duration
                    frameDuration = new Long(0);
                }
                assertTrue("Cannot find minimum frame duration for private size" + s,
                        frameDuration != null);
                if (frameDuration <= videoFrameDuration &&
                        s.getWidth() <= videoSize.getWidth() &&
                        s.getHeight() <= videoSize.getHeight()) {
                    Log.w(TAG, "Overwrite preview size from " + videoSize.toString() +
                            " to " + s.toString());
                    previewSize = s;
                    break;
                    // If all preview size doesn't work then we fallback to video size
                }
            }
        }
        if (previewSize == null) {
            previewSize = videoSize;
        }
        updatePreviewSurface(previewSize);
    }

    /**
     * Configure MediaRecorder recording session with CamcorderProfile, prepare
     * the recording surface.
     */
    private void prepareRecordingWithProfile(CamcorderProfile profile)
            throws Exception {
        // Prepare MediaRecorder.
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setProfile(profile);
        mMediaRecorder.setOutputFile(mOutMediaFileName);
        if (mPersistentSurface != null) {
            mMediaRecorder.setInputSurface(mPersistentSurface);
            mRecordingSurface = mPersistentSurface;
        }
        mMediaRecorder.prepare();
        if (mPersistentSurface == null) {
            mRecordingSurface = mMediaRecorder.getSurface();
        }
        assertNotNull("Recording surface must be non-null!", mRecordingSurface);
        mVideoFrameRate = profile.videoFrameRate;
        mVideoSize = new Size(profile.videoFrameWidth, profile.videoFrameHeight);
    }

    /**
     * Configure MediaRecorder recording session with CamcorderProfile, prepare
     * the recording surface. Use AVC for video compression, AAC for audio compression.
     * Both are required for android devices by android CDD.
     */
    private void prepareRecording(Size sz, int videoFrameRate, int captureRate)
            throws Exception {
        // Prepare MediaRecorder.
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        mMediaRecorder.setOutputFile(mOutMediaFileName);
        mMediaRecorder.setVideoEncodingBitRate(getVideoBitRate(sz));
        mMediaRecorder.setVideoFrameRate(videoFrameRate);
        mMediaRecorder.setCaptureRate(captureRate);
        mMediaRecorder.setVideoSize(sz.getWidth(), sz.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        if (mPersistentSurface != null) {
            mMediaRecorder.setInputSurface(mPersistentSurface);
            mRecordingSurface = mPersistentSurface;
        }
        mMediaRecorder.prepare();
        if (mPersistentSurface == null) {
            mRecordingSurface = mMediaRecorder.getSurface();
        }
        assertNotNull("Recording surface must be non-null!", mRecordingSurface);
        mVideoFrameRate = videoFrameRate;
        mVideoSize = sz;
    }

    private void startRecording(boolean useMediaRecorder,
            CameraCaptureSession.CaptureCallback listener, boolean useVideoStab) throws Exception {
        if (!mStaticInfo.isVideoStabilizationSupported() && useVideoStab) {
            throw new IllegalArgumentException("Video stabilization is not supported");
        }

        List<Surface> outputSurfaces = new ArrayList<Surface>(2);
        assertTrue("Both preview and recording surfaces should be valid",
                mPreviewSurface.isValid() && mRecordingSurface.isValid());
        outputSurfaces.add(mPreviewSurface);
        outputSurfaces.add(mRecordingSurface);
        // Video snapshot surface
        if (mReaderSurface != null) {
            outputSurfaces.add(mReaderSurface);
        }
        mSessionListener = new BlockingSessionCallback();

        CaptureRequest.Builder recordingRequestBuilder =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        // Make sure camera output frame rate is set to correct value.
        Range<Integer> fpsRange = Range.create(mVideoFrameRate, mVideoFrameRate);
        recordingRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        if (useVideoStab) {
            recordingRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }
        recordingRequestBuilder.addTarget(mRecordingSurface);
        recordingRequestBuilder.addTarget(mPreviewSurface);
        CaptureRequest recordingRequest = recordingRequestBuilder.build();
        mSession = configureCameraSessionWithParameters(mCamera, outputSurfaces, mSessionListener,
                mHandler, recordingRequest);
        mSession.setRepeatingRequest(recordingRequest, listener, mHandler);

        if (useMediaRecorder) {
            mMediaRecorder.start();
        } else {
            // TODO: need implement MediaCodec path.
        }
        mRecordingStartTime = SystemClock.elapsedRealtime();
    }

    private void stopCameraStreaming() throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "Stopping camera streaming and waiting for idle");
        }
        // Stop repeating, wait for captures to complete, and disconnect from
        // surfaces
        mSession.close();
        mSessionListener.getStateWaiter().waitForState(SESSION_CLOSED, SESSION_CLOSE_TIMEOUT_MS);
    }

    // Stop recording and return the estimated video duration in milliseconds.
    private int stopRecording(boolean useMediaRecorder) throws Exception {
        long stopRecordingTime = SystemClock.elapsedRealtime();
        if (useMediaRecorder) {
            stopCameraStreaming();

            mMediaRecorder.stop();
            // Can reuse the MediaRecorder object after reset.
            mMediaRecorder.reset();
        } else {
            // TODO: need implement MediaCodec path.
        }
        if (mPersistentSurface == null && mRecordingSurface != null) {
            mRecordingSurface.release();
            mRecordingSurface = null;
        }
        return (int) (stopRecordingTime - mRecordingStartTime);
    }

    private void releaseRecorder() {
        if (mMediaRecorder != null) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    private void validateRecording(Size sz, int expectedDurationMs) throws Exception {
        File outFile = new File(mOutMediaFileName);
        assertTrue("No video is recorded", outFile.exists());

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(mOutMediaFileName);
            long durationUs = 0;
            int width = -1, height = -1;
            int numTracks = extractor.getTrackCount();
            final String VIDEO_MIME_TYPE = "video";
            for (int i = 0; i < numTracks; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.contains(VIDEO_MIME_TYPE)) {
                    Log.i(TAG, "video format is: " + format.toString());
                    durationUs = format.getLong(MediaFormat.KEY_DURATION);
                    width = format.getInteger(MediaFormat.KEY_WIDTH);
                    height = format.getInteger(MediaFormat.KEY_HEIGHT);
                    break;
                }
            }
            Size videoSz = new Size(width, height);
            assertTrue("Video size doesn't match, expected " + sz.toString() +
                    " got " + videoSz.toString(), videoSz.equals(sz));
            int duration = (int) (durationUs / 1000);
            if (VERBOSE) {
                Log.v(TAG, String.format("Video duration: recorded %dms, expected %dms",
                                         duration, expectedDurationMs));
            }

            // TODO: Don't skip this for video snapshot
            if (!mStaticInfo.isHardwareLevelLegacy()) {
                assertTrue(String.format(
                        "Camera %s: Video duration doesn't match: recorded %dms, expected %dms.",
                        mCamera.getId(), duration, expectedDurationMs),
                        Math.abs(duration - expectedDurationMs) <
                        DURATION_MARGIN * expectedDurationMs);
            }
        } finally {
            extractor.release();
            if (!DEBUG_DUMP) {
                outFile.delete();
            }
        }
    }

    /**
     * Validate video snapshot capture image object sanity and test.
     *
     * <p> Check for size, format and jpeg decoding</p>
     *
     * @param image The JPEG image to be verified.
     * @param size The JPEG capture size to be verified against.
     */
    private void validateVideoSnapshotCapture(Image image, Size size) {
        CameraTestUtils.validateImage(image, size.getWidth(), size.getHeight(),
                ImageFormat.JPEG, /*filePath*/null);
    }

    /**
     * Validate if video snapshot causes frame drop.
     * Here frame drop is defined as frame duration >= 2 * expected frame duration.
     * Return the estimated number of frames dropped during video snapshot
     */
    private int validateFrameDropAroundVideoSnapshot(
            SimpleCaptureCallback resultListener, long imageTimeStamp) {
        double expectedDurationMs = 1000.0 / mVideoFrameRate;
        CaptureResult prevResult = resultListener.getCaptureResult(WAIT_FOR_RESULT_TIMEOUT_MS);
        long prevTS = getValueNotNull(prevResult, CaptureResult.SENSOR_TIMESTAMP);
        while (!resultListener.hasMoreResults()) {
            CaptureResult currentResult =
                    resultListener.getCaptureResult(WAIT_FOR_RESULT_TIMEOUT_MS);
            long currentTS = getValueNotNull(currentResult, CaptureResult.SENSOR_TIMESTAMP);
            if (currentTS == imageTimeStamp) {
                // validate the timestamp before and after, then return
                CaptureResult nextResult =
                        resultListener.getCaptureResult(WAIT_FOR_RESULT_TIMEOUT_MS);
                long nextTS = getValueNotNull(nextResult, CaptureResult.SENSOR_TIMESTAMP);
                double durationMs = (currentTS - prevTS) / 1000000.0;
                int totalFramesDropped = 0;

                // Snapshots in legacy mode pause the preview briefly.  Skip the duration
                // requirements for legacy mode unless this is fixed.
                if (!mStaticInfo.isHardwareLevelLegacy()) {
                    mCollector.expectTrue(
                            String.format(
                                    "Video %dx%d Frame drop detected before video snapshot: " +
                                            "duration %.2fms (expected %.2fms)",
                                    mVideoSize.getWidth(), mVideoSize.getHeight(),
                                    durationMs, expectedDurationMs
                            ),
                            durationMs <= (expectedDurationMs * MAX_NUM_FRAME_DROP_INTERVAL_ALLOWED)
                    );
                    // Log a warning is there is any frame drop detected.
                    if (durationMs >= expectedDurationMs * 2) {
                        Log.w(TAG, String.format(
                                "Video %dx%d Frame drop detected before video snapshot: " +
                                        "duration %.2fms (expected %.2fms)",
                                mVideoSize.getWidth(), mVideoSize.getHeight(),
                                durationMs, expectedDurationMs
                        ));
                    }

                    durationMs = (nextTS - currentTS) / 1000000.0;
                    mCollector.expectTrue(
                            String.format(
                                    "Video %dx%d Frame drop detected after video snapshot: " +
                                            "duration %.2fms (expected %.2fms)",
                                    mVideoSize.getWidth(), mVideoSize.getHeight(),
                                    durationMs, expectedDurationMs
                            ),
                            durationMs <= (expectedDurationMs * MAX_NUM_FRAME_DROP_INTERVAL_ALLOWED)
                    );
                    // Log a warning is there is any frame drop detected.
                    if (durationMs >= expectedDurationMs * 2) {
                        Log.w(TAG, String.format(
                                "Video %dx%d Frame drop detected after video snapshot: " +
                                        "duration %fms (expected %fms)",
                                mVideoSize.getWidth(), mVideoSize.getHeight(),
                                durationMs, expectedDurationMs
                        ));
                    }

                    double totalDurationMs = (nextTS - prevTS) / 1000000.0;
                    // Minus 2 for the expected 2 frames interval
                    totalFramesDropped = (int) (totalDurationMs / expectedDurationMs) - 2;
                    if (totalFramesDropped < 0) {
                        Log.w(TAG, "totalFrameDropped is " + totalFramesDropped +
                                ". Video frame rate might be too fast.");
                    }
                    totalFramesDropped = Math.max(0, totalFramesDropped);
                }
                return totalFramesDropped;
            }
            prevTS = currentTS;
        }
        throw new AssertionFailedError(
                "Video snapshot timestamp does not match any of capture results!");
    }

    /**
     * Calculate a video bit rate based on the size. The bit rate is scaled
     * based on ratio of video size to 1080p size.
     */
    private int getVideoBitRate(Size sz) {
        int rate = BIT_RATE_1080P;
        float scaleFactor = sz.getHeight() * sz.getWidth() / (float)(1920 * 1080);
        rate = (int)(rate * scaleFactor);

        // Clamp to the MIN, MAX range.
        return Math.max(BIT_RATE_MIN, Math.min(BIT_RATE_MAX, rate));
    }
}
