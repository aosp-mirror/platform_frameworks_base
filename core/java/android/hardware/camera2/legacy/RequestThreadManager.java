/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.legacy;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.utils.LongParcelable;
import android.hardware.camera2.utils.SizeAreaComparator;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.MutableLong;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;

import java.io.IOError;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.android.internal.util.Preconditions.*;

/**
 * This class executes requests to the {@link Camera}.
 *
 * <p>
 * The main components of this class are:
 * - A message queue of requests to the {@link Camera}.
 * - A thread that consumes requests to the {@link Camera} and executes them.
 * - A {@link GLThreadManager} that draws to the configured output {@link Surface}s.
 * - An {@link CameraDeviceState} state machine that manages the callbacks for various operations.
 * </p>
 */
@SuppressWarnings("deprecation")
public class RequestThreadManager {
    private final String TAG;
    private final int mCameraId;
    private final RequestHandlerThread mRequestThread;

    private static final boolean DEBUG = Log.isLoggable(LegacyCameraDevice.DEBUG_PROP, Log.DEBUG);
    // For slightly more spammy messages that will get repeated every frame
    private static final boolean VERBOSE =
            Log.isLoggable(LegacyCameraDevice.DEBUG_PROP, Log.VERBOSE);
    private final Camera mCamera;
    private final CameraCharacteristics mCharacteristics;

    private final CameraDeviceState mDeviceState;
    private final CaptureCollector mCaptureCollector;
    private final LegacyFocusStateMapper mFocusStateMapper;
    private final LegacyFaceDetectMapper mFaceDetectMapper;

    private static final int MSG_CONFIGURE_OUTPUTS = 1;
    private static final int MSG_SUBMIT_CAPTURE_REQUEST = 2;
    private static final int MSG_CLEANUP = 3;

    private static final int MAX_IN_FLIGHT_REQUESTS = 2;

    private static final int PREVIEW_FRAME_TIMEOUT = 300; // ms
    private static final int JPEG_FRAME_TIMEOUT = 3000; // ms (same as CTS for API2)
    private static final int REQUEST_COMPLETE_TIMEOUT = 3000; // ms (same as JPEG timeout)

    private static final float ASPECT_RATIO_TOLERANCE = 0.01f;
    private boolean mPreviewRunning = false;

    private final List<Surface> mPreviewOutputs = new ArrayList<>();
    private final List<Surface> mCallbackOutputs = new ArrayList<>();
    private GLThreadManager mGLThreadManager;
    private SurfaceTexture mPreviewTexture;
    private Camera.Parameters mParams;

    private Size mIntermediateBufferSize;

    private final RequestQueue mRequestQueue = new RequestQueue();
    private LegacyRequest mLastRequest = null;
    private SurfaceTexture mDummyTexture;
    private Surface mDummySurface;

    private final FpsCounter mPrevCounter = new FpsCounter("Incoming Preview");
    private final FpsCounter mRequestCounter = new FpsCounter("Incoming Requests");

    /**
     * Container object for Configure messages.
     */
    private static class ConfigureHolder {
        public final ConditionVariable condition;
        public final Collection<Surface> surfaces;

        public ConfigureHolder(ConditionVariable condition, Collection<Surface> surfaces) {
            this.condition = condition;
            this.surfaces = surfaces;
        }
    }

    /**
     * Counter class used to calculate and log the current FPS of frame production.
     */
    public static class FpsCounter {
        //TODO: Hook this up to SystTrace?
        private static final String TAG = "FpsCounter";
        private int mFrameCount = 0;
        private long mLastTime = 0;
        private long mLastPrintTime = 0;
        private double mLastFps = 0;
        private final String mStreamType;
        private static final long NANO_PER_SECOND = 1000000000; //ns

        public FpsCounter(String streamType) {
            mStreamType = streamType;
        }

        public synchronized void countFrame() {
            mFrameCount++;
            long nextTime = SystemClock.elapsedRealtimeNanos();
            if (mLastTime == 0) {
                mLastTime = nextTime;
            }
            if (nextTime > mLastTime + NANO_PER_SECOND) {
                long elapsed = nextTime - mLastTime;
                mLastFps = mFrameCount * (NANO_PER_SECOND / (double) elapsed);
                mFrameCount = 0;
                mLastTime = nextTime;
            }
        }

        public synchronized double checkFps() {
            return mLastFps;
        }

        public synchronized void staggeredLog() {
            if (mLastTime > mLastPrintTime + 5 * NANO_PER_SECOND) {
                mLastPrintTime = mLastTime;
                Log.d(TAG, "FPS for " + mStreamType + " stream: " + mLastFps );
            }
        }

        public synchronized void countAndLog() {
            countFrame();
            staggeredLog();
        }
    }
    /**
     * Fake preview for jpeg captures when there is no active preview
     */
    private void createDummySurface() {
        if (mDummyTexture == null || mDummySurface == null) {
            mDummyTexture = new SurfaceTexture(/*ignored*/0);
            // TODO: use smallest default sizes
            mDummyTexture.setDefaultBufferSize(640, 480);
            mDummySurface = new Surface(mDummyTexture);
        }
    }

    private final ConditionVariable mReceivedJpeg = new ConditionVariable(false);

    private final Camera.PictureCallback mJpegCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            Log.i(TAG, "Received jpeg.");
            Pair<RequestHolder, Long> captureInfo = mCaptureCollector.jpegProduced();
            RequestHolder holder = captureInfo.first;
            long timestamp = captureInfo.second;
            if (holder == null) {
                Log.e(TAG, "Dropping jpeg frame.");
                return;
            }
            for (Surface s : holder.getHolderTargets()) {
                try {
                    if (RequestHolder.jpegType(s)) {
                        Log.i(TAG, "Producing jpeg buffer...");
                        LegacyCameraDevice.setSurfaceDimens(s, data.length, /*height*/1);
                        LegacyCameraDevice.setNextTimestamp(s, timestamp);
                        LegacyCameraDevice.produceFrame(s, data, data.length, /*height*/1,
                                CameraMetadataNative.NATIVE_JPEG_FORMAT);
                    }
                } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                    Log.w(TAG, "Surface abandoned, dropping frame. ", e);
                }
            }

            mReceivedJpeg.open();
        }
    };

    private final Camera.ShutterCallback mJpegShutterCallback = new Camera.ShutterCallback() {
        @Override
        public void onShutter() {
            mCaptureCollector.jpegCaptured(SystemClock.elapsedRealtimeNanos());
        }
    };

    private final SurfaceTexture.OnFrameAvailableListener mPreviewCallback =
            new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    if (DEBUG) {
                        mPrevCounter.countAndLog();
                    }
                    mGLThreadManager.queueNewFrame();
                }
            };

    private void stopPreview() {
        if (VERBOSE) {
            Log.v(TAG, "stopPreview - preview running? " + mPreviewRunning);
        }
        if (mPreviewRunning) {
            mCamera.stopPreview();
            mPreviewRunning = false;
        }
    }

    private void startPreview() {
        if (VERBOSE) {
            Log.v(TAG, "startPreview - preview running? " + mPreviewRunning);
        }
        if (!mPreviewRunning) {
            // XX: CameraClient:;startPreview is not getting called after a stop
            mCamera.startPreview();
            mPreviewRunning = true;
        }
    }

    private void doJpegCapturePrepare(RequestHolder request) throws IOException {
        if (DEBUG) Log.d(TAG, "doJpegCapturePrepare - preview running? " + mPreviewRunning);

        if (!mPreviewRunning) {
            if (DEBUG) Log.d(TAG, "doJpegCapture - create fake surface");

            createDummySurface();
            mCamera.setPreviewTexture(mDummyTexture);
            startPreview();
        }
    }

    private void doJpegCapture(RequestHolder request) {
        if (DEBUG) Log.d(TAG, "doJpegCapturePrepare");

        mCamera.takePicture(mJpegShutterCallback, /*raw*/null, mJpegCallback);
        mPreviewRunning = false;
    }

    private void doPreviewCapture(RequestHolder request) throws IOException {
        if (VERBOSE) {
            Log.v(TAG, "doPreviewCapture - preview running? " + mPreviewRunning);
        }

        if (mPreviewRunning) {
            return; // Already running
        }

        if (mPreviewTexture == null) {
            throw new IllegalStateException(
                    "Preview capture called with no preview surfaces configured.");
        }

        mPreviewTexture.setDefaultBufferSize(mIntermediateBufferSize.getWidth(),
                mIntermediateBufferSize.getHeight());
        mCamera.setPreviewTexture(mPreviewTexture);

        startPreview();
    }

    private void configureOutputs(Collection<Surface> outputs) throws IOException {
        if (DEBUG) {
            String outputsStr = outputs == null ? "null" : (outputs.size() + " surfaces");

            Log.d(TAG, "configureOutputs with " + outputsStr);
        }

        stopPreview();
        /*
         * Try to release the previous preview's surface texture earlier if we end up
         * using a different one; this also reduces the likelihood of getting into a deadlock
         * when disconnecting from the old previous texture at a later time.
         */
        mCamera.setPreviewTexture(/*surfaceTexture*/null);

        if (mGLThreadManager != null) {
            mGLThreadManager.waitUntilStarted();
            mGLThreadManager.ignoreNewFrames();
            mGLThreadManager.waitUntilIdle();
        }
        mPreviewOutputs.clear();
        mCallbackOutputs.clear();
        mPreviewTexture = null;

        int facing = mCharacteristics.get(CameraCharacteristics.LENS_FACING);
        int orientation = mCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (outputs != null) {
            for (Surface s : outputs) {
                try {
                    int format = LegacyCameraDevice.detectSurfaceType(s);
                    LegacyCameraDevice.setSurfaceOrientation(s, facing, orientation);
                    switch (format) {
                        case CameraMetadataNative.NATIVE_JPEG_FORMAT:
                            mCallbackOutputs.add(s);
                            break;
                        default:
                            mPreviewOutputs.add(s);
                            break;
                    }
                } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                    Log.w(TAG, "Surface abandoned, skipping...", e);
                }
            }
        }
        mParams = mCamera.getParameters();

        List<int[]> supportedFpsRanges = mParams.getSupportedPreviewFpsRange();
        int[] bestRange = getPhotoPreviewFpsRange(supportedFpsRanges);
        if (DEBUG) {
            Log.d(TAG, "doPreviewCapture - Selected range [" +
                    bestRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX] + "," +
                    bestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] + "]");
        }
        mParams.setPreviewFpsRange(bestRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                bestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);

        if (mPreviewOutputs.size() > 0) {
            List<Size> outputSizes = new ArrayList<>(outputs.size());
            for (Surface s : mPreviewOutputs) {
                try {
                    Size size = LegacyCameraDevice.getSurfaceSize(s);
                    outputSizes.add(size);
                } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                    Log.w(TAG, "Surface abandoned, skipping...", e);
                }
            }

            Size largestOutput = SizeAreaComparator.findLargestByArea(outputSizes);

            // Find largest jpeg dimension - assume to have the same aspect ratio as sensor.
            Size largestJpegDimen = ParameterUtils.getLargestSupportedJpegSizeByArea(mParams);

            List<Size> supportedPreviewSizes = ParameterUtils.convertSizeList(
                    mParams.getSupportedPreviewSizes());

            // Use smallest preview dimension with same aspect ratio as sensor that is >= than all
            // of the configured output dimensions.  If none exists, fall back to using the largest
            // supported preview size.
            long largestOutputArea = largestOutput.getHeight() * (long) largestOutput.getWidth();
            Size bestPreviewDimen = SizeAreaComparator.findLargestByArea(supportedPreviewSizes);
            for (Size s : supportedPreviewSizes) {
                long currArea = s.getWidth() * s.getHeight();
                long bestArea = bestPreviewDimen.getWidth() * bestPreviewDimen.getHeight();
                if (checkAspectRatiosMatch(largestJpegDimen, s) && (currArea < bestArea &&
                        currArea >= largestOutputArea)) {
                    bestPreviewDimen = s;
                }
            }

            mIntermediateBufferSize = bestPreviewDimen;
            if (DEBUG) {
                Log.d(TAG, "Intermediate buffer selected with dimens: " +
                        bestPreviewDimen.toString());
            }
        } else {
            mIntermediateBufferSize = null;
            if (DEBUG) {
                Log.d(TAG, "No Intermediate buffer selected, no preview outputs were configured");
            }
        }

        Size smallestSupportedJpegSize = calculatePictureSize(mCallbackOutputs, mParams);
        if (smallestSupportedJpegSize != null) {
            /*
             * Set takePicture size to the smallest supported JPEG size large enough
             * to scale/crop out of for the bounding rectangle of the configured JPEG sizes.
             */

            Log.i(TAG, "configureOutputs - set take picture size to " + smallestSupportedJpegSize);
            mParams.setPictureSize(
                    smallestSupportedJpegSize.getWidth(), smallestSupportedJpegSize.getHeight());
        }

        // TODO: Detect and optimize single-output paths here to skip stream teeing.
        if (mGLThreadManager == null) {
            mGLThreadManager = new GLThreadManager(mCameraId);
            mGLThreadManager.start();
        }
        mGLThreadManager.waitUntilStarted();
        mGLThreadManager.setConfigurationAndWait(mPreviewOutputs, mCaptureCollector);
        mGLThreadManager.allowNewFrames();
        mPreviewTexture = mGLThreadManager.getCurrentSurfaceTexture();
        if (mPreviewTexture != null) {
            mPreviewTexture.setOnFrameAvailableListener(mPreviewCallback);
        }

        mCamera.setParameters(mParams);
        // TODO: configure the JPEG surface with some arbitrary size
        // using LegacyCameraDevice.nativeConfigureSurface
    }

    /**
     * Find a JPEG size (that is supported by the legacy camera device) which is equal to or larger
     * than all of the configured {@code JPEG} outputs (by both width and height).
     *
     * <p>If multiple supported JPEG sizes are larger, select the smallest of them which
     * still satisfies the above constraint.</p>
     *
     * <p>As a result, the returned size is guaranteed to be usable without needing
     * to upscale any of the outputs. If only one {@code JPEG} surface is used,
     * then no scaling/cropping is necessary between the taken picture and
     * the {@code JPEG} output surface.</p>
     *
     * @param callbackOutputs a non-{@code null} list of {@code Surface}s with any image formats
     * @param params api1 parameters (used for reading only)
     *
     * @return a size large enough to fit all of the configured {@code JPEG} outputs, or
     *          {@code null} if the {@code callbackOutputs} did not have any {@code JPEG}
     *          surfaces.
     */
    private Size calculatePictureSize(
            Collection<Surface> callbackOutputs, Camera.Parameters params) {
        /*
         * Find the largest JPEG size (if any), from the configured outputs:
         * - the api1 picture size should be set to the smallest legal size that's at least as large
         *   as the largest configured JPEG size
         */
        List<Size> configuredJpegSizes = new ArrayList<Size>();
        for (Surface callbackSurface : callbackOutputs) {
            try {
                int format = LegacyCameraDevice.detectSurfaceType(callbackSurface);

                if (format != CameraMetadataNative.NATIVE_JPEG_FORMAT) {
                    continue; // Ignore non-JPEG callback formats
                }

                Size jpegSize = LegacyCameraDevice.getSurfaceSize(callbackSurface);
                configuredJpegSizes.add(jpegSize);
            } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                Log.w(TAG, "Surface abandoned, skipping...", e);
            }
        }
        if (!configuredJpegSizes.isEmpty()) {
            /*
             * Find the largest configured JPEG width, and height, independently
             * of the rest.
             *
             * The rest of the JPEG streams can be cropped out of this smallest bounding
             * rectangle.
             */
            int maxConfiguredJpegWidth = -1;
            int maxConfiguredJpegHeight = -1;
            for (Size jpegSize : configuredJpegSizes) {
                maxConfiguredJpegWidth = jpegSize.getWidth() > maxConfiguredJpegWidth ?
                        jpegSize.getWidth() : maxConfiguredJpegWidth;
                maxConfiguredJpegHeight = jpegSize.getHeight() > maxConfiguredJpegHeight ?
                        jpegSize.getHeight() : maxConfiguredJpegHeight;
            }
            Size smallestBoundJpegSize = new Size(maxConfiguredJpegWidth, maxConfiguredJpegHeight);

            List<Size> supportedJpegSizes = ParameterUtils.convertSizeList(
                    params.getSupportedPictureSizes());

            /*
             * Find the smallest supported JPEG size that can fit the smallest bounding
             * rectangle for the configured JPEG sizes.
             */
            List<Size> candidateSupportedJpegSizes = new ArrayList<>();
            for (Size supportedJpegSize : supportedJpegSizes) {
                if (supportedJpegSize.getWidth() >= maxConfiguredJpegWidth &&
                    supportedJpegSize.getHeight() >= maxConfiguredJpegHeight) {
                    candidateSupportedJpegSizes.add(supportedJpegSize);
                }
            }

            if (candidateSupportedJpegSizes.isEmpty()) {
                throw new AssertionError(
                        "Could not find any supported JPEG sizes large enough to fit " +
                        smallestBoundJpegSize);
            }

            Size smallestSupportedJpegSize = Collections.min(candidateSupportedJpegSizes,
                    new SizeAreaComparator());

            if (!smallestSupportedJpegSize.equals(smallestBoundJpegSize)) {
                Log.w(TAG,
                        String.format(
                                "configureOutputs - Will need to crop picture %s into "
                                + "smallest bound size %s",
                                smallestSupportedJpegSize, smallestBoundJpegSize));
            }

            return smallestSupportedJpegSize;
        }

        return null;
    }

    private static boolean checkAspectRatiosMatch(Size a, Size b) {
        float aAspect = a.getWidth() / (float) a.getHeight();
        float bAspect = b.getWidth() / (float) b.getHeight();

        return Math.abs(aAspect - bAspect) < ASPECT_RATIO_TOLERANCE;
    }

    // Calculate the highest FPS range supported
    private int[] getPhotoPreviewFpsRange(List<int[]> frameRates) {
        if (frameRates.size() == 0) {
            Log.e(TAG, "No supported frame rates returned!");
            return null;
        }

        int bestMin = 0;
        int bestMax = 0;
        int bestIndex = 0;
        int index = 0;
        for (int[] rate : frameRates) {
            int minFps = rate[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int maxFps = rate[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            if (maxFps > bestMax || (maxFps == bestMax && minFps > bestMin)) {
                bestMin = minFps;
                bestMax = maxFps;
                bestIndex = index;
            }
            index++;
        }

        return frameRates.get(bestIndex);
    }

    private final Handler.Callback mRequestHandlerCb = new Handler.Callback() {
        private boolean mCleanup = false;
        private final LegacyResultMapper mMapper = new LegacyResultMapper();

        @Override
        public boolean handleMessage(Message msg) {
            if (mCleanup) {
                return true;
            }

            if (DEBUG) {
                Log.d(TAG, "Request thread handling message:" + msg.what);
            }
            long startTime = 0;
            if (DEBUG) {
                startTime = SystemClock.elapsedRealtimeNanos();
            }
            switch (msg.what) {
                case MSG_CONFIGURE_OUTPUTS:
                    ConfigureHolder config = (ConfigureHolder) msg.obj;
                    int sizes = config.surfaces != null ? config.surfaces.size() : 0;
                    Log.i(TAG, "Configure outputs: " + sizes +
                            " surfaces configured.");

                    try {
                        boolean success = mCaptureCollector.waitForEmpty(JPEG_FRAME_TIMEOUT,
                                TimeUnit.MILLISECONDS);
                        if (!success) {
                            Log.e(TAG, "Timed out while queueing configure request.");
                        }
                    } catch (InterruptedException e) {
                        // TODO: report error to CameraDevice
                        Log.e(TAG, "Interrupted while waiting for requests to complete.");
                    }

                    try {
                        configureOutputs(config.surfaces);
                    } catch (IOException e) {
                        // TODO: report error to CameraDevice
                        throw new IOError(e);
                    }
                    config.condition.open();
                    if (DEBUG) {
                        long totalTime = SystemClock.elapsedRealtimeNanos() - startTime;
                        Log.d(TAG, "Configure took " + totalTime + " ns");
                    }
                    break;
                case MSG_SUBMIT_CAPTURE_REQUEST:
                    Handler handler = RequestThreadManager.this.mRequestThread.getHandler();

                    // Get the next burst from the request queue.
                    Pair<BurstHolder, Long> nextBurst = mRequestQueue.getNext();
                    if (nextBurst == null) {
                        try {
                            boolean success = mCaptureCollector.waitForEmpty(JPEG_FRAME_TIMEOUT,
                                    TimeUnit.MILLISECONDS);
                            if (!success) {
                                Log.e(TAG, "Timed out while waiting for empty.");
                            }
                        } catch (InterruptedException e) {
                            // TODO: report error to CameraDevice
                            Log.e(TAG, "Interrupted while waiting for requests to complete.");
                        }
                        mDeviceState.setIdle();
                        break;
                    } else {
                        // Queue another capture if we did not get the last burst.
                        handler.sendEmptyMessage(MSG_SUBMIT_CAPTURE_REQUEST);
                    }

                    // Complete each request in the burst
                    List<RequestHolder> requests =
                            nextBurst.first.produceRequestHolders(nextBurst.second);
                    for (RequestHolder holder : requests) {
                        CaptureRequest request = holder.getRequest();

                        boolean paramsChanged = false;

                        // Lazily process the rest of the request
                        if (mLastRequest == null || mLastRequest.captureRequest != request) {

                            // The intermediate buffer is sometimes null, but we always need
                            // the camera1's configured preview size
                            Size previewSize = ParameterUtils.convertSize(mParams.getPreviewSize());

                            LegacyRequest legacyRequest = new LegacyRequest(
                                    mCharacteristics, request, previewSize,
                                    mParams); // params are copied

                            mLastRequest = legacyRequest;
                            // Parameters are mutated as a side-effect
                            LegacyMetadataMapper.convertRequestMetadata(/*inout*/legacyRequest);

                            if (!mParams.same(legacyRequest.parameters)) {
                                mParams = legacyRequest.parameters;
                                mCamera.setParameters(mParams);

                                paramsChanged = true;
                            }
                        }

                        try {
                            boolean success = mCaptureCollector.queueRequest(holder,
                                    mLastRequest, JPEG_FRAME_TIMEOUT, TimeUnit.MILLISECONDS);

                            if (!success) {
                                Log.e(TAG, "Timed out while queueing capture request.");
                            }
                            // Starting the preview needs to happen before enabling
                            // face detection or auto focus
                            if (holder.hasPreviewTargets()) {
                                doPreviewCapture(holder);
                            }
                            if (holder.hasJpegTargets()) {
                                success = mCaptureCollector.
                                        waitForPreviewsEmpty(PREVIEW_FRAME_TIMEOUT *
                                                MAX_IN_FLIGHT_REQUESTS, TimeUnit.MILLISECONDS);
                                if (!success) {
                                    Log.e(TAG, "Timed out waiting for prior requests to complete.");
                                }
                                mReceivedJpeg.close();
                                doJpegCapturePrepare(holder);
                                if (!mReceivedJpeg.block(JPEG_FRAME_TIMEOUT)) {
                                    // TODO: report error to CameraDevice
                                    Log.e(TAG, "Hit timeout for jpeg callback!");
                                }
                            }

                            /*
                             * Do all the actions that require a preview to have been started
                             */

                            // Toggle face detection on/off
                            // - do this before AF to give AF a chance to use faces
                            mFaceDetectMapper.processFaceDetectMode(request, /*in*/mParams);

                            // Unconditionally process AF triggers, since they're non-idempotent
                            // - must be done after setting the most-up-to-date AF mode
                            mFocusStateMapper.processRequestTriggers(request, mParams);

                            if (holder.hasJpegTargets()) {
                                doJpegCapture(holder);
                                if (!mReceivedJpeg.block(JPEG_FRAME_TIMEOUT)) {
                                    // TODO: report error to CameraDevice
                                    Log.e(TAG, "Hit timeout for jpeg callback!");
                                }
                            }

                        } catch (IOException e) {
                            // TODO: report error to CameraDevice
                            throw new IOError(e);
                        } catch (InterruptedException e) {
                            // TODO: report error to CameraDevice
                            Log.e(TAG, "Interrupted during capture.", e);
                        }

                        if (paramsChanged) {
                            if (DEBUG) {
                                Log.d(TAG, "Params changed -- getting new Parameters from HAL.");
                            }
                            mParams = mCamera.getParameters();

                            // Update parameters to the latest that we think the camera is using
                            mLastRequest.setParameters(mParams);
                        }

                        MutableLong timestampMutable = new MutableLong(/*value*/0L);
                        try {
                            boolean success = mCaptureCollector.waitForRequestCompleted(holder,
                                    REQUEST_COMPLETE_TIMEOUT, TimeUnit.MILLISECONDS,
                                    /*out*/timestampMutable);

                            if (!success) {
                                Log.e(TAG, "Timed out while waiting for request to complete.");
                            }
                        } catch (InterruptedException e) {
                         // TODO: report error to CameraDevice
                            Log.e(TAG, "Interrupted during request completition.", e);
                        }

                        CameraMetadataNative result = mMapper.cachedConvertResultMetadata(
                                mLastRequest, timestampMutable.value);
                        /*
                         * Order matters: The default result mapper is state-less; the
                         * other mappers carry state and may override keys set by the default
                         * mapper with their own values.
                         */

                        // Update AF state
                        mFocusStateMapper.mapResultTriggers(result);
                        // Update face-related results
                        mFaceDetectMapper.mapResultFaces(result, mLastRequest);

                        mDeviceState.setCaptureResult(holder, result);
                    }
                    if (DEBUG) {
                        long totalTime = SystemClock.elapsedRealtimeNanos() - startTime;
                        Log.d(TAG, "Capture request took " + totalTime + " ns");
                        mRequestCounter.countAndLog();
                    }
                    break;
                case MSG_CLEANUP:
                    mCleanup = true;
                    try {
                        boolean success = mCaptureCollector.waitForEmpty(JPEG_FRAME_TIMEOUT,
                                TimeUnit.MILLISECONDS);
                        if (!success) {
                            Log.e(TAG, "Timed out while queueing cleanup request.");
                        }
                    } catch (InterruptedException e) {
                        // TODO: report error to CameraDevice
                        Log.e(TAG, "Interrupted while waiting for requests to complete.");
                    }
                    if (mGLThreadManager != null) {
                        mGLThreadManager.quit();
                    }
                    if (mCamera != null) {
                        mCamera.release();
                    }
                    break;
                default:
                    throw new AssertionError("Unhandled message " + msg.what +
                            " on RequestThread.");
            }
            return true;
        }
    };

    /**
     * Create a new RequestThreadManager.
     *
     * @param cameraId the id of the camera to use.
     * @param camera an open camera object.  The RequestThreadManager takes ownership of this camera
     *               object, and is responsible for closing it.
     * @param characteristics the static camera characteristics corresponding to this camera device
     * @param deviceState a {@link CameraDeviceState} state machine.
     */
    public RequestThreadManager(int cameraId, Camera camera, CameraCharacteristics characteristics,
                                CameraDeviceState deviceState) {
        mCamera = checkNotNull(camera, "camera must not be null");
        mCameraId = cameraId;
        mCharacteristics = checkNotNull(characteristics, "characteristics must not be null");
        String name = String.format("RequestThread-%d", cameraId);
        TAG = name;
        mDeviceState = checkNotNull(deviceState, "deviceState must not be null");
        mFocusStateMapper = new LegacyFocusStateMapper(mCamera);
        mFaceDetectMapper = new LegacyFaceDetectMapper(mCamera, mCharacteristics);
        mCaptureCollector = new CaptureCollector(MAX_IN_FLIGHT_REQUESTS, mDeviceState);
        mRequestThread = new RequestHandlerThread(name, mRequestHandlerCb);
    }

    /**
     * Start the request thread.
     */
    public void start() {
        mRequestThread.start();
    }

    /**
     * Flush the pending requests.
     */
    public void flush() {
        // TODO: Implement flush.
        Log.e(TAG, "flush not yet implemented.");
    }

    /**
     * Quit the request thread, and clean up everything.
     */
    public void quit() {
        Handler handler = mRequestThread.waitAndGetHandler();
        handler.sendMessageAtFrontOfQueue(handler.obtainMessage(MSG_CLEANUP));
        mRequestThread.quitSafely();
        try {
            mRequestThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, String.format("Thread %s (%d) interrupted while quitting.",
                    mRequestThread.getName(), mRequestThread.getId()));
        }
    }

    /**
     * Submit the given burst of requests to be captured.
     *
     * <p>If the burst is repeating, replace the current repeating burst.</p>
     *
     * @param requests the burst of requests to add to the queue.
     * @param repeating true if the burst is repeating.
     * @param frameNumber an output argument that contains either the frame number of the last frame
     *                    that will be returned for this request, or the frame number of the last
     *                    frame that will be returned for the current repeating request if this
     *                    burst is set to be repeating.
     * @return the request id.
     */
    public int submitCaptureRequests(List<CaptureRequest> requests, boolean repeating,
            /*out*/LongParcelable frameNumber) {
        Handler handler = mRequestThread.waitAndGetHandler();
        int ret = mRequestQueue.submit(requests, repeating, frameNumber);
        handler.sendEmptyMessage(MSG_SUBMIT_CAPTURE_REQUEST);
        return ret;
    }

    /**
     * Cancel a repeating request.
     *
     * @param requestId the id of the repeating request to cancel.
     * @return the last frame to be returned from the HAL for the given repeating request, or
     *          {@code INVALID_FRAME} if none exists.
     */
    public long cancelRepeating(int requestId) {
        return mRequestQueue.stopRepeating(requestId);
    }


    /**
     * Configure with the current list of output Surfaces.
     *
     * <p>
     * This operation blocks until the configuration is complete.
     * </p>
     *
     * <p>Using a {@code null} or empty {@code outputs} list is the equivalent of unconfiguring.</p>
     *
     * @param outputs a {@link java.util.Collection} of outputs to configure.
     */
    public void configure(Collection<Surface> outputs) {
        Handler handler = mRequestThread.waitAndGetHandler();
        final ConditionVariable condition = new ConditionVariable(/*closed*/false);
        ConfigureHolder holder = new ConfigureHolder(condition, outputs);
        handler.sendMessage(handler.obtainMessage(MSG_CONFIGURE_OUTPUTS, 0, 0, holder));
        condition.block();
    }
}
