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

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.impl.CameraDeviceImpl;
import android.hardware.camera2.params.StreamConfigurationMap;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private Camera mCamera;
    private final CameraCharacteristics mCharacteristics;

    private final CameraDeviceState mDeviceState;
    private final CaptureCollector mCaptureCollector;
    private final LegacyFocusStateMapper mFocusStateMapper;
    private final LegacyFaceDetectMapper mFaceDetectMapper;

    private static final int MSG_CONFIGURE_OUTPUTS = 1;
    private static final int MSG_SUBMIT_CAPTURE_REQUEST = 2;
    private static final int MSG_CLEANUP = 3;

    private static final int MAX_IN_FLIGHT_REQUESTS = 2;

    private static final int PREVIEW_FRAME_TIMEOUT = 1000; // ms
    private static final int JPEG_FRAME_TIMEOUT = 4000; // ms (same as CTS for API2)
    private static final int REQUEST_COMPLETE_TIMEOUT = JPEG_FRAME_TIMEOUT; // ms (same as JPEG timeout)

    private static final float ASPECT_RATIO_TOLERANCE = 0.01f;
    private boolean mPreviewRunning = false;

    private final List<Surface> mPreviewOutputs = new ArrayList<>();
    private final List<Surface> mCallbackOutputs = new ArrayList<>();
    private GLThreadManager mGLThreadManager;
    private SurfaceTexture mPreviewTexture;
    private Camera.Parameters mParams;

    private final List<Long> mJpegSurfaceIds = new ArrayList<>();

    private Size mIntermediateBufferSize;

    private final RequestQueue mRequestQueue = new RequestQueue(mJpegSurfaceIds);
    private LegacyRequest mLastRequest = null;
    private SurfaceTexture mDummyTexture;
    private Surface mDummySurface;

    private final Object mIdleLock = new Object();
    private final FpsCounter mPrevCounter = new FpsCounter("Incoming Preview");
    private final FpsCounter mRequestCounter = new FpsCounter("Incoming Requests");

    private final AtomicBoolean mQuit = new AtomicBoolean(false);

    // Stuff JPEGs into HAL_PIXEL_FORMAT_RGBA_8888 gralloc buffers to get around SW write
    // limitations for (b/17379185).
    private static final boolean USE_BLOB_FORMAT_OVERRIDE = true;

    /**
     * Container object for Configure messages.
     */
    private static class ConfigureHolder {
        public final ConditionVariable condition;
        public final Collection<Pair<Surface, Size>> surfaces;

        public ConfigureHolder(ConditionVariable condition, Collection<Pair<Surface,
                Size>> surfaces) {
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

    private final Camera.ErrorCallback mErrorCallback = new Camera.ErrorCallback() {
        @Override
        public void onError(int i, Camera camera) {
            Log.e(TAG, "Received error " + i + " from the Camera1 ErrorCallback");
            mDeviceState.setError(CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
        }
    };

    private final ConditionVariable mReceivedJpeg = new ConditionVariable(false);

    private final Camera.PictureCallback mJpegCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            Log.i(TAG, "Received jpeg.");
            Pair<RequestHolder, Long> captureInfo = mCaptureCollector.jpegProduced();
            if (captureInfo == null || captureInfo.first == null) {
                Log.e(TAG, "Dropping jpeg frame.");
                return;
            }
            RequestHolder holder = captureInfo.first;
            long timestamp = captureInfo.second;
            for (Surface s : holder.getHolderTargets()) {
                try {
                    if (LegacyCameraDevice.containsSurfaceId(s, mJpegSurfaceIds)) {
                        Log.i(TAG, "Producing jpeg buffer...");

                        int totalSize = data.length + LegacyCameraDevice.nativeGetJpegFooterSize();
                        totalSize = (totalSize + 3) & ~0x3; // round up to nearest octonibble
                        LegacyCameraDevice.setNextTimestamp(s, timestamp);

                        if (USE_BLOB_FORMAT_OVERRIDE) {
                            // Override to RGBA_8888 format.
                            LegacyCameraDevice.setSurfaceFormat(s,
                                    LegacyMetadataMapper.HAL_PIXEL_FORMAT_RGBA_8888);

                            int dimen = (int) Math.ceil(Math.sqrt(totalSize));
                            dimen = (dimen + 0xf) & ~0xf; // round up to nearest multiple of 16
                            LegacyCameraDevice.setSurfaceDimens(s, dimen, dimen);
                            LegacyCameraDevice.produceFrame(s, data, dimen, dimen,
                                    CameraMetadataNative.NATIVE_JPEG_FORMAT);
                        } else {
                            LegacyCameraDevice.setSurfaceDimens(s, totalSize, /*height*/1);
                            LegacyCameraDevice.produceFrame(s, data, totalSize, /*height*/1,
                                    CameraMetadataNative.NATIVE_JPEG_FORMAT);
                        }
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

    private void configureOutputs(Collection<Pair<Surface, Size>> outputs) {
        if (DEBUG) {
            String outputsStr = outputs == null ? "null" : (outputs.size() + " surfaces");
            Log.d(TAG, "configureOutputs with " + outputsStr);
        }

        try {
            stopPreview();
        }  catch (RuntimeException e) {
            Log.e(TAG, "Received device exception in configure call: ", e);
            mDeviceState.setError(
                    CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
            return;
        }

        /*
         * Try to release the previous preview's surface texture earlier if we end up
         * using a different one; this also reduces the likelihood of getting into a deadlock
         * when disconnecting from the old previous texture at a later time.
         */
        try {
            mCamera.setPreviewTexture(/*surfaceTexture*/null);
        } catch (IOException e) {
            Log.w(TAG, "Failed to clear prior SurfaceTexture, may cause GL deadlock: ", e);
        } catch (RuntimeException e) {
            Log.e(TAG, "Received device exception in configure call: ", e);
            mDeviceState.setError(
                    CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
            return;
        }

        if (mGLThreadManager != null) {
            mGLThreadManager.waitUntilStarted();
            mGLThreadManager.ignoreNewFrames();
            mGLThreadManager.waitUntilIdle();
        }
        resetJpegSurfaceFormats(mCallbackOutputs);
        mPreviewOutputs.clear();
        mCallbackOutputs.clear();
        mJpegSurfaceIds.clear();
        mPreviewTexture = null;

        List<Size> previewOutputSizes = new ArrayList<>();
        List<Size> callbackOutputSizes = new ArrayList<>();

        int facing = mCharacteristics.get(CameraCharacteristics.LENS_FACING);
        int orientation = mCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (outputs != null) {
            for (Pair<Surface, Size> outPair : outputs) {
                Surface s = outPair.first;
                Size outSize = outPair.second;
                try {
                    int format = LegacyCameraDevice.detectSurfaceType(s);
                    LegacyCameraDevice.setSurfaceOrientation(s, facing, orientation);
                    switch (format) {
                        case CameraMetadataNative.NATIVE_JPEG_FORMAT:
                            if (USE_BLOB_FORMAT_OVERRIDE) {
                                // Override to RGBA_8888 format.
                                LegacyCameraDevice.setSurfaceFormat(s,
                                        LegacyMetadataMapper.HAL_PIXEL_FORMAT_RGBA_8888);
                            }
                            mJpegSurfaceIds.add(LegacyCameraDevice.getSurfaceId(s));
                            mCallbackOutputs.add(s);
                            callbackOutputSizes.add(outSize);
                            break;
                        default:
                            mPreviewOutputs.add(s);
                            previewOutputSizes.add(outSize);
                            break;
                    }
                } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                    Log.w(TAG, "Surface abandoned, skipping...", e);
                }
            }
        }
        try {
            mParams = mCamera.getParameters();
        } catch (RuntimeException e) {
            Log.e(TAG, "Received device exception: ", e);
            mDeviceState.setError(
                CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
            return;
        }

        List<int[]> supportedFpsRanges = mParams.getSupportedPreviewFpsRange();
        int[] bestRange = getPhotoPreviewFpsRange(supportedFpsRanges);
        if (DEBUG) {
            Log.d(TAG, "doPreviewCapture - Selected range [" +
                    bestRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX] + "," +
                    bestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] + "]");
        }
        mParams.setPreviewFpsRange(bestRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                bestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);

        if (previewOutputSizes.size() > 0) {

            Size largestOutput = SizeAreaComparator.findLargestByArea(previewOutputSizes);

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
            mParams.setPreviewSize(mIntermediateBufferSize.getWidth(),
                    mIntermediateBufferSize.getHeight());

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

        Size smallestSupportedJpegSize = calculatePictureSize(mCallbackOutputs,
                callbackOutputSizes, mParams);
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
            mGLThreadManager = new GLThreadManager(mCameraId, facing, mDeviceState);
            mGLThreadManager.start();
        }
        mGLThreadManager.waitUntilStarted();
        List<Pair<Surface, Size>> previews = new ArrayList<>();
        Iterator<Size> previewSizeIter = previewOutputSizes.iterator();
        for (Surface p : mPreviewOutputs) {
            previews.add(new Pair<>(p, previewSizeIter.next()));
        }
        mGLThreadManager.setConfigurationAndWait(previews, mCaptureCollector);
        mGLThreadManager.allowNewFrames();
        mPreviewTexture = mGLThreadManager.getCurrentSurfaceTexture();
        if (mPreviewTexture != null) {
            mPreviewTexture.setOnFrameAvailableListener(mPreviewCallback);
        }

        try {
            mCamera.setParameters(mParams);
        } catch (RuntimeException e) {
                Log.e(TAG, "Received device exception while configuring: ", e);
                mDeviceState.setError(
                        CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);

        }
    }

    private void resetJpegSurfaceFormats(Collection<Surface> surfaces) {
        if (!USE_BLOB_FORMAT_OVERRIDE || surfaces == null) {
            return;
        }
        for(Surface s : surfaces) {
            try {
                LegacyCameraDevice.setSurfaceFormat(s, LegacyMetadataMapper.HAL_PIXEL_FORMAT_BLOB);
            } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                Log.w(TAG, "Surface abandoned, skipping...", e);
            }
        }
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
    private Size calculatePictureSize( List<Surface> callbackOutputs,
                                       List<Size> callbackSizes, Camera.Parameters params) {
        /*
         * Find the largest JPEG size (if any), from the configured outputs:
         * - the api1 picture size should be set to the smallest legal size that's at least as large
         *   as the largest configured JPEG size
         */
        if (callbackOutputs.size() != callbackSizes.size()) {
            throw new IllegalStateException("Input collections must be same length");
        }
        List<Size> configuredJpegSizes = new ArrayList<>();
        Iterator<Size> sizeIterator = callbackSizes.iterator();
        for (Surface callbackSurface : callbackOutputs) {
            Size jpegSize = sizeIterator.next();
                if (!LegacyCameraDevice.containsSurfaceId(callbackSurface, mJpegSurfaceIds)) {
                    continue; // Ignore non-JPEG callback formats
                }

                configuredJpegSizes.add(jpegSize);
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
                    Log.i(TAG, "Configure outputs: " + sizes + " surfaces configured.");

                    try {
                        boolean success = mCaptureCollector.waitForEmpty(JPEG_FRAME_TIMEOUT,
                                TimeUnit.MILLISECONDS);
                        if (!success) {
                            Log.e(TAG, "Timed out while queueing configure request.");
                            mCaptureCollector.failAll();
                        }
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Interrupted while waiting for requests to complete.");
                        mDeviceState.setError(
                                CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
                        break;
                    }

                    configureOutputs(config.surfaces);
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
                        // If there are no further requests queued, wait for any currently executing
                        // requests to complete, then switch to idle state.
                        try {
                            boolean success = mCaptureCollector.waitForEmpty(JPEG_FRAME_TIMEOUT,
                                    TimeUnit.MILLISECONDS);
                            if (!success) {
                                Log.e(TAG,
                                        "Timed out while waiting for prior requests to complete.");
                                mCaptureCollector.failAll();
                            }
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Interrupted while waiting for requests to complete: ", e);
                            mDeviceState.setError(
                                    CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
                            break;
                        }

                        synchronized (mIdleLock) {
                            // Retry the the request queue.
                            nextBurst = mRequestQueue.getNext();

                            // If we still have no queued requests, go idle.
                            if (nextBurst == null) {
                                mDeviceState.setIdle();
                                break;
                            }
                        }
                    }

                    if (nextBurst != null) {
                        // Queue another capture if we did not get the last burst.
                        handler.sendEmptyMessage(MSG_SUBMIT_CAPTURE_REQUEST);
                    }

                    // Complete each request in the burst
                    List<RequestHolder> requests =
                            nextBurst.first.produceRequestHolders(nextBurst.second);
                    for (RequestHolder holder : requests) {
                        CaptureRequest request = holder.getRequest();

                        boolean paramsChanged = false;

                        // Only update parameters if the request has changed
                        if (mLastRequest == null || mLastRequest.captureRequest != request) {

                            // The intermediate buffer is sometimes null, but we always need
                            // the Camera1 API configured preview size
                            Size previewSize = ParameterUtils.convertSize(mParams.getPreviewSize());

                            LegacyRequest legacyRequest = new LegacyRequest(mCharacteristics,
                                    request, previewSize, mParams); // params are copied


                            // Parameters are mutated as a side-effect
                            LegacyMetadataMapper.convertRequestMetadata(/*inout*/legacyRequest);

                            // If the parameters have changed, set them in the Camera1 API.
                            if (!mParams.same(legacyRequest.parameters)) {
                                try {
                                    mCamera.setParameters(legacyRequest.parameters);
                                } catch (RuntimeException e) {
                                    // If setting the parameters failed, report a request error to
                                    // the camera client, and skip any further work for this request
                                    Log.e(TAG, "Exception while setting camera parameters: ", e);
                                    holder.failRequest();
                                    mDeviceState.setCaptureStart(holder, /*timestamp*/0,
                                            CameraDeviceImpl.CameraDeviceCallbacks.
                                                    ERROR_CAMERA_REQUEST);
                                    continue;
                                }
                                paramsChanged = true;
                                mParams = legacyRequest.parameters;
                            }

                            mLastRequest = legacyRequest;
                        }

                        try {
                            boolean success = mCaptureCollector.queueRequest(holder,
                                    mLastRequest, JPEG_FRAME_TIMEOUT, TimeUnit.MILLISECONDS);

                            if (!success) {
                                // Report a request error if we timed out while queuing this.
                                Log.e(TAG, "Timed out while queueing capture request.");
                                holder.failRequest();
                                mDeviceState.setCaptureStart(holder, /*timestamp*/0,
                                        CameraDeviceImpl.CameraDeviceCallbacks.
                                                ERROR_CAMERA_REQUEST);
                                continue;
                            }

                            // Starting the preview needs to happen before enabling
                            // face detection or auto focus
                            if (holder.hasPreviewTargets()) {
                                doPreviewCapture(holder);
                            }
                            if (holder.hasJpegTargets()) {
                                while(!mCaptureCollector.waitForPreviewsEmpty(PREVIEW_FRAME_TIMEOUT,
                                        TimeUnit.MILLISECONDS)) {
                                    // Fail preview requests until the queue is empty.
                                    Log.e(TAG, "Timed out while waiting for preview requests to " +
                                            "complete.");
                                    mCaptureCollector.failNextPreview();
                                }
                                mReceivedJpeg.close();
                                doJpegCapturePrepare(holder);
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
                                    Log.e(TAG, "Hit timeout for jpeg callback!");
                                    mCaptureCollector.failNextJpeg();
                                }
                            }

                        } catch (IOException e) {
                            Log.e(TAG, "Received device exception during capture call: ", e);
                            mDeviceState.setError(
                                    CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
                            break;
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Interrupted during capture: ", e);
                            mDeviceState.setError(
                                    CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
                            break;
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Received device exception during capture call: ", e);
                            mDeviceState.setError(
                                    CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
                            break;
                        }

                        if (paramsChanged) {
                            if (DEBUG) {
                                Log.d(TAG, "Params changed -- getting new Parameters from HAL.");
                            }
                            try {
                                mParams = mCamera.getParameters();
                            } catch (RuntimeException e) {
                                Log.e(TAG, "Received device exception: ", e);
                                mDeviceState.setError(
                                    CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
                                break;
                            }

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
                                mCaptureCollector.failAll();
                            }
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Interrupted waiting for request completion: ", e);
                            mDeviceState.setError(
                                    CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
                            break;
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

                        if (!holder.requestFailed()) {
                            mDeviceState.setCaptureResult(holder, result,
                                    CameraDeviceState.NO_CAPTURE_ERROR);
                        }
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
                            mCaptureCollector.failAll();
                        }
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Interrupted while waiting for requests to complete: ", e);
                        mDeviceState.setError(
                                CameraDeviceImpl.CameraDeviceCallbacks.ERROR_CAMERA_DEVICE);
                    }
                    if (mGLThreadManager != null) {
                        mGLThreadManager.quit();
                        mGLThreadManager = null;
                    }
                    if (mCamera != null) {
                        mCamera.release();
                        mCamera = null;
                    }
                    resetJpegSurfaceFormats(mCallbackOutputs);
                    break;
                case RequestHandlerThread.MSG_POKE_IDLE_HANDLER:
                    // OK: Ignore message.
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
        mCamera.setErrorCallback(mErrorCallback);
    }

    /**
     * Start the request thread.
     */
    public void start() {
        mRequestThread.start();
    }

    /**
     * Flush any pending requests.
     *
     * @return the last frame number.
     */
    public long flush() {
        Log.i(TAG, "Flushing all pending requests.");
        long lastFrame = mRequestQueue.stopRepeating();
        mCaptureCollector.failAll();
        return lastFrame;
    }

    /**
     * Quit the request thread, and clean up everything.
     */
    public void quit() {
        if (!mQuit.getAndSet(true)) {  // Avoid sending messages on dead thread's handler.
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
        int ret;
        synchronized (mIdleLock) {
            ret = mRequestQueue.submit(requests, repeating, frameNumber);
            handler.sendEmptyMessage(MSG_SUBMIT_CAPTURE_REQUEST);
        }
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
    public void configure(Collection<Pair<Surface, Size>> outputs) {
        Handler handler = mRequestThread.waitAndGetHandler();
        final ConditionVariable condition = new ConditionVariable(/*closed*/false);
        ConfigureHolder holder = new ConfigureHolder(condition, outputs);
        handler.sendMessage(handler.obtainMessage(MSG_CONFIGURE_OUTPUTS, 0, 0, holder));
        condition.block();
    }
}
