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

import com.android.ex.camera2.blocking.BlockingSessionCallback;
import com.android.ex.camera2.exceptions.TimeoutRuntimeException;
import com.android.mediaframeworktest.Camera2SurfaceViewTestCase;
import com.android.mediaframeworktest.helpers.Camera2Focuser;
import com.android.mediaframeworktest.helpers.CameraTestUtils;
import com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleCaptureCallback;

import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.Image;
import android.media.ImageReader;
import android.media.CamcorderProfile;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.ConditionVariable;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.util.Range;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;

import static com.android.mediaframeworktest.helpers.CameraTestUtils.CAPTURE_IMAGE_TIMEOUT_MS;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.MAX_READER_IMAGES;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SimpleImageReaderListener;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.basicValidateJpegImage;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.configureCameraSession;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.dumpFile;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.getDataFromImage;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.getValueNotNull;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.makeImageReader;
import static com.android.ex.camera2.blocking.BlockingSessionCallback.SESSION_CLOSED;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.CAPTURE_IMAGE_TIMEOUT_MS;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SESSION_CLOSE_TIMEOUT_MS;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SIZE_BOUND_1080P;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.SIZE_BOUND_2160P;
import static com.android.mediaframeworktest.helpers.CameraTestUtils.getSupportedVideoSizes;

import com.android.ex.camera2.blocking.BlockingSessionCallback;
import com.android.mediaframeworktest.Camera2SurfaceViewTestCase;
import com.android.mediaframeworktest.helpers.CameraTestUtils;

import junit.framework.AssertionFailedError;

/**
 * <p>Tests Back/Front camera switching and Camera/Video modes witching.</p>
 *
 * adb shell am instrument \
 *    -e class com.android.mediaframeworktest.stress.Camera2SwitchPreviewTest \
 *    -e iterations 200 \
 *    -e waitIntervalMs 1000 \
 *    -e resultToFile false \
 *    -r -w com.android.mediaframeworktest/.Camera2InstrumentationTestRunner
 */
public class Camera2SwitchPreviewTest extends Camera2SurfaceViewTestCase {
    private static final String TAG = "SwitchPreviewTest";
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

    private static final boolean DEBUG_DUMP = Log.isLoggable(TAG, Log.DEBUG);
    private static final int RECORDING_DURATION_MS = 3000;
    private static final float DURATION_MARGIN = 0.2f;
    private static final double FRAME_DURATION_ERROR_TOLERANCE_MS = 3.0;
    private static final int BIT_RATE_1080P = 16000000;
    private static final int BIT_RATE_MIN = 64000;
    private static final int BIT_RATE_MAX = 40000000;
    private static final int VIDEO_FRAME_RATE = 30;
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
    private String mVideoFilePath;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mVideoFilePath = mContext.getExternalFilesDir(null).getPath();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test normal still preview switch.
     * <p>
     * Preview jpeg output streams are configured. Max still capture
     * size is used for jpeg capture.
     * </p>
     */
    public void testPreviewSwitchBackFrontCamera() throws Exception {
        List<String> mCameraColorOutputIds = cameraColorOutputCheck();
        // Test iteration starts...
        Log.i(TAG, "Testing preview switch back/front camera in still capture mode");
        for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
            for (String id : mCameraColorOutputIds) {
                try {
                    openDevice(id);
                    // Preview for basic still capture:
                    Log.v(TAG, String.format("Preview pictures: %d/%d", iteration + 1,
                            getIterationCount()));
                    stillCapturePreviewPreparer(id);
                    getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
                } finally {
                    closeDevice();
                    closeImageReader();
                }
            }
        }
    }

    /**
     * <p>
     * Test basic video preview switch.
     * </p>
     * <p>
     * This test covers the typical basic use case of video preview switch.
     * MediaRecorder is used to record the audio and video, CamcorderProfile is
     * used to configure the MediaRecorder. Preview is set to the video size.
     * </p>
     */
    public void testPreviewSwitchBackFrontVideo() throws Exception {
        List<String> mCameraColorOutputIds = cameraColorOutputCheck();
        // Test iteration starts...
        Log.i(TAG, "Testing preview switch back/front camera in video mode");
        for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
            for (String id : mCameraColorOutputIds) {
                try {
                    openDevice(id);
                    // Preview for basic video recording:
                    Log.v(TAG, String.format("Preview for recording videos: %d/%d", iteration + 1,
                            getIterationCount()));
                    recordingPreviewPreparer(id);
                    getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
                } finally {
                    closeDevice();
                    releaseRecorder();
                }
            }
        }
    }


    /**
     * Test back camera preview switch between still capture and recording mode.
     * <p>
     * This test covers the basic case of preview switch camera mode, between
     * still capture (photo) and recording (video) mode. The preview settings
     * are same with the settings in "testPreviewSwitchBackFrontCamera" and
     * "testPreviewSwitchBackFrontVideo"
     * </p>
     */
    public void testPreviewSwitchBackCameraVideo() throws Exception {
        String id = mCameraIds[0];
        openDevice(id);
        if (!mStaticInfo.isColorOutputSupported()) {
            Log.i(TAG, "Camera " + id +
                    " does not support color outputs, skipping");
            return;
        }
        closeDevice();
        // Test iteration starts...
        Log.i(TAG, "Testing preview switch between still capture/video modes for back camera");
        for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
            try {
                openDevice(id);

                // Preview for basic still capture:
                Log.v(TAG, String.format("Preview pictures: %d/%d", iteration + 1,
                        getIterationCount()));
                stillCapturePreviewPreparer(id);
                getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);

                // Preview for basic video recording:
                Log.v(TAG, String.format("Preview for recording videos: %d/%d", iteration + 1,
                        getIterationCount()));
                recordingPreviewPreparer(id);
                getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
            } finally {
                closeDevice();
                closeImageReader();
            }
        }
    }

    /**
     * Test front camera preview switch between still capture and recording mode.
     * <p>
     * This test covers the basic case of preview switch camera mode, between
     * still capture (photo) and recording (video) mode. The preview settings
     * are same with the settings in "testPreviewSwitchBackFrontCamera" and
     * "testPreviewSwitchBackFrontVideo"
     * </p>
     */
    public void testPreviewSwitchFrontCameraVideo() throws Exception{
        String id = mCameraIds[1];
        openDevice(id);
        if (!mStaticInfo.isColorOutputSupported()) {
            Log.i(TAG, "Camera " + id +
                    " does not support color outputs, skipping");
            return;
        }
        closeDevice();
        // Test iteration starts...
        Log.i(TAG, "Testing preview switch between still capture/video modes for front camera");
        for (int iteration = 0; iteration < getIterationCount(); ++iteration) {
            try {
                openDevice(id);

                // Preview for basic still capture:
                Log.v(TAG, String.format("Preview pictures: %d/%d", iteration + 1,
                        getIterationCount()));
                stillCapturePreviewPreparer(id);
                getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);

                // Preview for basic video recording:
                Log.v(TAG, String.format("Preview for recording videos: %d/%d", iteration + 1,
                        getIterationCount()));
                recordingPreviewPreparer(id);
                getResultPrinter().printStatus(getIterationCount(), iteration + 1, id);
            } finally {
                closeDevice();
                closeImageReader();
            }
        }
    }

    private void stillCapturePreviewPreparer(String id) throws Exception{
        CaptureResult result;
        SimpleCaptureCallback resultListener = new SimpleCaptureCallback();
        SimpleImageReaderListener imageListener = new SimpleImageReaderListener();
        CaptureRequest.Builder previewRequest =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        CaptureRequest.Builder stillRequest =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        // Preview Setup:
        prepareCapturePreview(previewRequest, stillRequest, resultListener, imageListener);

        Thread.sleep(getTestWaitIntervalMs());
    }

    private void recordingPreviewPreparer(String id) throws Exception{
        // Re-use the MediaRecorder object for the same camera device.
        mMediaRecorder = new MediaRecorder();
        initSupportedVideoSize(id);
        // preview Setup:
        basicRecordingPreviewTestByCamera(mCamcorderProfileList);

        Thread.sleep(getTestWaitIntervalMs());
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
     * Test camera recording preview by using each available CamcorderProfile for a
     * given camera. preview size is set to the video size.
     */
    private void basicRecordingPreviewTestByCamera(int[] camcorderProfileList)
            throws Exception {
        Size maxPreviewSize = mOrderedPreviewSizes.get(0);
        List<Range<Integer> > fpsRanges = Arrays.asList(
                mStaticInfo.getAeAvailableTargetFpsRangesChecked());
        int cameraId = Integer.parseInt(mCamera.getId());
        int maxVideoFrameRate = -1;
        int profileId = camcorderProfileList[0];
        if (!CamcorderProfile.hasProfile(cameraId, profileId) ||
                allowedUnsupported(cameraId, profileId)) {
            return;
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
            return;
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
        mOutMediaFileName = mVideoFilePath + "/test_video.mp4";
        if (DEBUG_DUMP) {
            mOutMediaFileName = mVideoFilePath + "/test_video_" + cameraId + "_"
                    + videoSz.toString() + ".mp4";
        }

        prepareRecordingWithProfile(profile);

        // prepare preview surface by using video size.
        updatePreviewSurfaceWithVideo(videoSz, profile.videoFrameRate);

        CaptureRequest.Builder previewRequest =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        CaptureRequest.Builder recordingRequest =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

        SimpleCaptureCallback resultListener = new SimpleCaptureCallback();
        SimpleImageReaderListener imageListener = new SimpleImageReaderListener();

        prepareVideoPreview(previewRequest, recordingRequest, resultListener, imageListener);

        // Can reuse the MediaRecorder object after reset.
        mMediaRecorder.reset();

        if (maxVideoFrameRate != -1) {
            // At least one CamcorderProfile is present, check FPS
            assertTrue("At least one CamcorderProfile must support >= 24 FPS",
                    maxVideoFrameRate >= 24);
        }
    }

    private void releaseRecorder() {
        if (mMediaRecorder != null) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    private List<String> cameraColorOutputCheck() throws Exception {
        List<String> mCameraColorOutputIds = new ArrayList<String>();
        for (String id : mCameraIds) {
            openDevice(id);
            if (!mStaticInfo.isColorOutputSupported()) {
                Log.i(TAG, "Camera " + id +
                        " does not support color outputs, skipping");
                continue;
            }
            mCameraColorOutputIds.add(id);
            closeDevice();
        }
        return mCameraColorOutputIds;
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
     * Update preview size with video size.
     *
     * <p>Preview size will be capped with max preview size.</p>
     *
     * @param videoSize The video size used for preview.
     * @param videoFrameRate The video frame rate
     *
     */
    private void updatePreviewSurfaceWithVideo(Size videoSize, int videoFrameRate)  throws Exception {
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

    protected void prepareVideoPreview(CaptureRequest.Builder previewRequest,
                                                 CaptureRequest.Builder recordingRequest,
                                                 CaptureCallback resultListener,
                                                 ImageReader.OnImageAvailableListener imageListener) throws Exception {

        // Configure output streams with preview and jpeg streams.
        List<Surface> outputSurfaces = new ArrayList<Surface>();
        outputSurfaces.add(mPreviewSurface);
        outputSurfaces.add(mRecordingSurface);

        mSessionListener = new BlockingSessionCallback();
        mSession = configureCameraSession(mCamera, outputSurfaces, mSessionListener, mHandler);

        previewRequest.addTarget(mPreviewSurface);
        recordingRequest.addTarget(mPreviewSurface);
        recordingRequest.addTarget(mRecordingSurface);

        // Start preview.
        mSession.setRepeatingRequest(previewRequest.build(), null, mHandler);
    }

    protected void prepareCapturePreview(CaptureRequest.Builder previewRequest,
                                                 CaptureRequest.Builder stillRequest,
                                                 CaptureCallback resultListener,
                                                 ImageReader.OnImageAvailableListener imageListener) throws Exception {

        Size captureSz = mOrderedStillSizes.get(0);
        Size previewSz = mOrderedPreviewSizes.get(1);

        if (VERBOSE) {
            Log.v(TAG, String.format("Prepare single capture (%s) and preview (%s)",
                    captureSz.toString(), previewSz.toString()));
        }

        // Update preview size.
        updatePreviewSurface(previewSz);

        // Create ImageReader.
        createImageReader(captureSz, ImageFormat.JPEG, MAX_READER_IMAGES, imageListener);

        // Configure output streams with preview and jpeg streams.
        List<Surface> outputSurfaces = new ArrayList<Surface>();
        outputSurfaces.add(mPreviewSurface);
        outputSurfaces.add(mReaderSurface);
        mSessionListener = new BlockingSessionCallback();
        mSession = configureCameraSession(mCamera, outputSurfaces, mSessionListener, mHandler);

        // Configure the requests.
        previewRequest.addTarget(mPreviewSurface);
        stillRequest.addTarget(mPreviewSurface);
        stillRequest.addTarget(mReaderSurface);

        // Start preview.
        mSession.setRepeatingRequest(previewRequest.build(), resultListener, mHandler);
    }

}
