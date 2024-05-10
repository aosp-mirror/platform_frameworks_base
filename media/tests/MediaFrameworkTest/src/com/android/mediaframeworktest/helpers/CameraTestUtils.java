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

package com.android.mediaframeworktest.helpers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;

import com.android.ex.camera2.blocking.BlockingCameraManager;
import com.android.ex.camera2.blocking.BlockingCameraManager.BlockingOpenException;
import com.android.ex.camera2.blocking.BlockingSessionCallback;
import com.android.ex.camera2.blocking.BlockingStateCallback;
import com.android.ex.camera2.exceptions.TimeoutRuntimeException;

import junit.framework.Assert;

import org.mockito.Mockito;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A package private utility class for wrapping up the camera2 framework test common utility
 * functions
 */
/**
 * (non-Javadoc)
 * @see android.hardware.camera2.cts.CameraTestUtils
 */
public class CameraTestUtils extends Assert {
    private static final String TAG = "CameraTestUtils";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    public static final Size SIZE_BOUND_1080P = new Size(1920, 1088);
    public static final Size SIZE_BOUND_2160P = new Size(3840, 2160);
    // Only test the preview size that is no larger than 1080p.
    public static final Size PREVIEW_SIZE_BOUND = SIZE_BOUND_1080P;
    // Default timeouts for reaching various states
    public static final int CAMERA_OPEN_TIMEOUT_MS = 3000;
    public static final int CAMERA_CLOSE_TIMEOUT_MS = 3000;
    public static final int CAMERA_IDLE_TIMEOUT_MS = 3000;
    public static final int CAMERA_ACTIVE_TIMEOUT_MS = 1000;
    public static final int CAMERA_BUSY_TIMEOUT_MS = 1000;
    public static final int CAMERA_UNCONFIGURED_TIMEOUT_MS = 1000;
    public static final int CAMERA_CONFIGURE_TIMEOUT_MS = 3000;
    public static final int CAPTURE_RESULT_TIMEOUT_MS = 3000;
    public static final int CAPTURE_IMAGE_TIMEOUT_MS = 3000;

    public static final int SESSION_CONFIGURE_TIMEOUT_MS = 3000;
    public static final int SESSION_CLOSE_TIMEOUT_MS = 3000;
    public static final int SESSION_READY_TIMEOUT_MS = 3000;
    public static final int SESSION_ACTIVE_TIMEOUT_MS = 1000;

    public static final int MAX_READER_IMAGES = 5;

    private static final int EXIF_DATETIME_LENGTH = 19;
    private static final int EXIF_DATETIME_ERROR_MARGIN_SEC = 60;
    private static final float EXIF_FOCAL_LENGTH_ERROR_MARGIN = 0.001f;
    private static final float EXIF_EXPOSURE_TIME_ERROR_MARGIN_RATIO = 0.05f;
    private static final float EXIF_EXPOSURE_TIME_MIN_ERROR_MARGIN_SEC = 0.002f;
    private static final float EXIF_APERTURE_ERROR_MARGIN = 0.001f;

    private static final Location sTestLocation0 = new Location(LocationManager.GPS_PROVIDER);
    private static final Location sTestLocation1 = new Location(LocationManager.GPS_PROVIDER);
    private static final Location sTestLocation2 = new Location(LocationManager.NETWORK_PROVIDER);

    protected static final String DEBUG_FILE_NAME_BASE =
            InstrumentationRegistry.getInstrumentation().getTargetContext()
            .getExternalFilesDir(null).getPath();

    static {
        sTestLocation0.setTime(1199145600L);
        sTestLocation0.setLatitude(37.736071);
        sTestLocation0.setLongitude(-122.441983);
        sTestLocation0.setAltitude(21.0);

        sTestLocation1.setTime(1199145601L);
        sTestLocation1.setLatitude(0.736071);
        sTestLocation1.setLongitude(0.441983);
        sTestLocation1.setAltitude(1.0);

        sTestLocation2.setTime(1199145602L);
        sTestLocation2.setLatitude(-89.736071);
        sTestLocation2.setLongitude(-179.441983);
        sTestLocation2.setAltitude(100000.0);
    }

    // Exif test data vectors.
    public static final ExifTestData[] EXIF_TEST_DATA = {
            new ExifTestData(
                    /*gpsLocation*/ sTestLocation0,
                    /* orientation */90,
                    /* jpgQuality */(byte) 80,
                    /* thumbQuality */(byte) 75),
            new ExifTestData(
                    /*gpsLocation*/ sTestLocation1,
                    /* orientation */180,
                    /* jpgQuality */(byte) 90,
                    /* thumbQuality */(byte) 85),
            new ExifTestData(
                    /*gpsLocation*/ sTestLocation2,
                    /* orientation */270,
                    /* jpgQuality */(byte) 100,
                    /* thumbQuality */(byte) 100)
    };

    /**
     * Create an {@link ImageReader} object and get the surface.
     *
     * @param size The size of this ImageReader to be created.
     * @param format The format of this ImageReader to be created
     * @param maxNumImages The max number of images that can be acquired simultaneously.
     * @param listener The listener used by this ImageReader to notify callbacks.
     * @param handler The handler to use for any listener callbacks.
     */
    public static ImageReader makeImageReader(Size size, int format, int maxNumImages,
            ImageReader.OnImageAvailableListener listener, Handler handler) {
        ImageReader reader;
        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), format,
                maxNumImages);
        reader.setOnImageAvailableListener(listener, handler);
        if (VERBOSE) Log.v(TAG, "Created ImageReader size " + size);
        return reader;
    }

    /**
     * Create an ImageWriter and hook up the ImageListener.
     *
     * @param inputSurface The input surface of the ImageWriter.
     * @param maxImages The max number of Images that can be dequeued simultaneously.
     * @param listener The listener used by this ImageWriter to notify callbacks
     * @param handler The handler to post listener callbacks.
     * @return ImageWriter object created.
     */
    public static ImageWriter makeImageWriter(
            Surface inputSurface, int maxImages,
            ImageWriter.OnImageReleasedListener listener, Handler handler) {
        ImageWriter writer = ImageWriter.newInstance(inputSurface, maxImages);
        writer.setOnImageReleasedListener(listener, handler);
        return writer;
    }

    /**
     * Close pending images and clean up an {@link ImageReader} object.
     * @param reader an {@link ImageReader} to close.
     */
    public static void closeImageReader(ImageReader reader) {
        if (reader != null) {
            reader.close();
        }
    }

    /**
     * Close pending images and clean up an {@link ImageWriter} object.
     * @param writer an {@link ImageWriter} to close.
     */
    public static void closeImageWriter(ImageWriter writer) {
        if (writer != null) {
            writer.close();
        }
    }

    /**
     * Mock listener that release the image immediately once it is available.
     *
     * <p>
     * It can be used for the case where we don't care the image data at all.
     * </p>
     */
    public static class ImageDropperListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireNextImage();
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    }

    /**
     * Image listener that release the image immediately after validating the image
     */
    public static class ImageVerifierListener implements ImageReader.OnImageAvailableListener {
        private Size mSize;
        private int mFormat;

        public ImageVerifierListener(Size sz, int format) {
            mSize = sz;
            mFormat = format;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireNextImage();
            } finally {
                if (image != null) {
                    validateImage(image, mSize.getWidth(), mSize.getHeight(), mFormat, null);
                    image.close();
                }
            }
        }
    }

    public static class SimpleImageReaderListener
            implements ImageReader.OnImageAvailableListener {
        private final LinkedBlockingQueue<Image> mQueue =
                new LinkedBlockingQueue<Image>();
        // Indicate whether this listener will drop images or not,
        // when the queued images reaches the reader maxImages
        private final boolean mAsyncMode;
        // maxImages held by the queue in async mode.
        private final int mMaxImages;

        /**
         * Create a synchronous SimpleImageReaderListener that queues the images
         * automatically when they are available, no image will be dropped. If
         * the caller doesn't call getImage(), the producer will eventually run
         * into buffer starvation.
         */
        public SimpleImageReaderListener() {
            mAsyncMode = false;
            mMaxImages = 0;
        }

        /**
         * Create a synchronous/asynchronous SimpleImageReaderListener that
         * queues the images automatically when they are available. For
         * asynchronous listener, image will be dropped if the queued images
         * reach to maxImages queued. If the caller doesn't call getImage(), the
         * producer will not be blocked. For synchronous listener, no image will
         * be dropped. If the caller doesn't call getImage(), the producer will
         * eventually run into buffer starvation.
         *
         * @param asyncMode If the listener is operating at asynchronous mode.
         * @param maxImages The max number of images held by this listener.
         */
        /**
         *
         * @param asyncMode
         */
        public SimpleImageReaderListener(boolean asyncMode, int maxImages) {
            mAsyncMode = asyncMode;
            mMaxImages = maxImages;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            try {
                mQueue.put(reader.acquireNextImage());
                if (mAsyncMode && mQueue.size() >= mMaxImages) {
                    Image img = mQueue.poll();
                    img.close();
                }
            } catch (InterruptedException e) {
                throw new UnsupportedOperationException(
                        "Can't handle InterruptedException in onImageAvailable");
            }
        }

        /**
         * Get an image from the image reader.
         *
         * @param timeout Timeout value for the wait.
         * @return The image from the image reader.
         */
        public Image getImage(long timeout) throws InterruptedException {
            Image image = mQueue.poll(timeout, TimeUnit.MILLISECONDS);
            assertNotNull("Wait for an image timed out in " + timeout + "ms", image);
            return image;
        }

        /**
         * Drain the pending images held by this listener currently.
         *
         */
        public void drain() {
            while (!mQueue.isEmpty()) {
                Image image = mQueue.poll();
                assertNotNull("Unable to get an image", image);
                image.close();
            }
        }
    }

    public static class SimpleImageWriterListener implements ImageWriter.OnImageReleasedListener {
        private final Semaphore mImageReleasedSema = new Semaphore(0);
        private final ImageWriter mWriter;
        @Override
        public void onImageReleased(ImageWriter writer) {
            if (writer != mWriter) {
                return;
            }

            if (VERBOSE) {
                Log.v(TAG, "Input image is released");
            }
            mImageReleasedSema.release();
        }

        public SimpleImageWriterListener(ImageWriter writer) {
            if (writer == null) {
                throw new IllegalArgumentException("writer cannot be null");
            }
            mWriter = writer;
        }

        public void waitForImageReleased(long timeoutMs) throws InterruptedException {
            if (!mImageReleasedSema.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                fail("wait for image available timed out after " + timeoutMs + "ms");
            }
        }
    }

    public static class SimpleCaptureCallback extends CameraCaptureSession.CaptureCallback {
        private final LinkedBlockingQueue<TotalCaptureResult> mQueue =
                new LinkedBlockingQueue<TotalCaptureResult>();
        private final LinkedBlockingQueue<CaptureFailure> mFailureQueue =
                new LinkedBlockingQueue<>();
        // Pair<CaptureRequest, Long> is a pair of capture request and timestamp.
        private final LinkedBlockingQueue<Pair<CaptureRequest, Long>> mCaptureStartQueue =
                new LinkedBlockingQueue<>();

        private AtomicLong mNumFramesArrived = new AtomicLong(0);

        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                long timestamp, long frameNumber) {
            try {
                mCaptureStartQueue.put(new Pair(request, timestamp));
            } catch (InterruptedException e) {
                throw new UnsupportedOperationException(
                        "Can't handle InterruptedException in onCaptureStarted");
            }
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                TotalCaptureResult result) {
            try {
                mNumFramesArrived.incrementAndGet();
                mQueue.put(result);
            } catch (InterruptedException e) {
                throw new UnsupportedOperationException(
                        "Can't handle InterruptedException in onCaptureCompleted");
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                CaptureFailure failure) {
            try {
                mFailureQueue.put(failure);
            } catch (InterruptedException e) {
                throw new UnsupportedOperationException(
                        "Can't handle InterruptedException in onCaptureFailed");
            }
        }

        @Override
        public void onCaptureSequenceCompleted(CameraCaptureSession session, int sequenceId,
                long frameNumber) {
        }

        public long getTotalNumFrames() {
            return mNumFramesArrived.get();
        }

        public CaptureResult getCaptureResult(long timeout) {
            return getTotalCaptureResult(timeout);
        }

        public TotalCaptureResult getCaptureResult(long timeout, long timestamp) {
            try {
                long currentTs = -1L;
                TotalCaptureResult result;
                while (true) {
                    result = mQueue.poll(timeout, TimeUnit.MILLISECONDS);
                    if (result == null) {
                        throw new RuntimeException(
                                "Wait for a capture result timed out in " + timeout + "ms");
                    }
                    currentTs = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (currentTs == timestamp) {
                        return result;
                    }
                }

            } catch (InterruptedException e) {
                throw new UnsupportedOperationException("Unhandled interrupted exception", e);
            }
        }

        public TotalCaptureResult getTotalCaptureResult(long timeout) {
            try {
                TotalCaptureResult result = mQueue.poll(timeout, TimeUnit.MILLISECONDS);
                assertNotNull("Wait for a capture result timed out in " + timeout + "ms", result);
                return result;
            } catch (InterruptedException e) {
                throw new UnsupportedOperationException("Unhandled interrupted exception", e);
            }
        }

        /**
         * Get the {@link #CaptureResult capture result} for a given
         * {@link #CaptureRequest capture request}.
         *
         * @param myRequest The {@link #CaptureRequest capture request} whose
         *            corresponding {@link #CaptureResult capture result} was
         *            being waited for
         * @param numResultsWait Number of frames to wait for the capture result
         *            before timeout.
         * @throws TimeoutRuntimeException If more than numResultsWait results are
         *            seen before the result matching myRequest arrives, or each
         *            individual wait for result times out after
         *            {@value #CAPTURE_RESULT_TIMEOUT_MS}ms.
         */
        public CaptureResult getCaptureResultForRequest(CaptureRequest myRequest,
                int numResultsWait) {
            return getTotalCaptureResultForRequest(myRequest, numResultsWait);
        }

        /**
         * Get the {@link #TotalCaptureResult total capture result} for a given
         * {@link #CaptureRequest capture request}.
         *
         * @param myRequest The {@link #CaptureRequest capture request} whose
         *            corresponding {@link #TotalCaptureResult capture result} was
         *            being waited for
         * @param numResultsWait Number of frames to wait for the capture result
         *            before timeout.
         * @throws TimeoutRuntimeException If more than numResultsWait results are
         *            seen before the result matching myRequest arrives, or each
         *            individual wait for result times out after
         *            {@value #CAPTURE_RESULT_TIMEOUT_MS}ms.
         */
        public TotalCaptureResult getTotalCaptureResultForRequest(CaptureRequest myRequest,
                int numResultsWait) {
            ArrayList<CaptureRequest> captureRequests = new ArrayList<>(1);
            captureRequests.add(myRequest);
            return getTotalCaptureResultsForRequests(captureRequests, numResultsWait)[0];
        }

        /**
         * Get an array of {@link #TotalCaptureResult total capture results} for a given list of
         * {@link #CaptureRequest capture requests}. This can be used when the order of results
         * may not the same as the order of requests.
         *
         * @param captureRequests The list of {@link #CaptureRequest capture requests} whose
         *            corresponding {@link #TotalCaptureResult capture results} are
         *            being waited for.
         * @param numResultsWait Number of frames to wait for the capture results
         *            before timeout.
         * @throws TimeoutRuntimeException If more than numResultsWait results are
         *            seen before all the results matching captureRequests arrives.
         */
        public TotalCaptureResult[] getTotalCaptureResultsForRequests(
                List<CaptureRequest> captureRequests, int numResultsWait) {
            if (numResultsWait < 0) {
                throw new IllegalArgumentException("numResultsWait must be no less than 0");
            }
            if (captureRequests == null || captureRequests.size() == 0) {
                throw new IllegalArgumentException("captureRequests must have at least 1 request.");
            }

            // Create a request -> a list of result indices map that it will wait for.
            HashMap<CaptureRequest, ArrayList<Integer>> remainingResultIndicesMap = new HashMap<>();
            for (int i = 0; i < captureRequests.size(); i++) {
                CaptureRequest request = captureRequests.get(i);
                ArrayList<Integer> indices = remainingResultIndicesMap.get(request);
                if (indices == null) {
                    indices = new ArrayList<>();
                    remainingResultIndicesMap.put(request, indices);
                }
                indices.add(i);
            }

            TotalCaptureResult[] results = new TotalCaptureResult[captureRequests.size()];
            int i = 0;
            do {
                TotalCaptureResult result = getTotalCaptureResult(CAPTURE_RESULT_TIMEOUT_MS);
                CaptureRequest request = result.getRequest();
                ArrayList<Integer> indices = remainingResultIndicesMap.get(request);
                if (indices != null) {
                    results[indices.get(0)] = result;
                    indices.remove(0);

                    // Remove the entry if all results for this request has been fulfilled.
                    if (indices.isEmpty()) {
                        remainingResultIndicesMap.remove(request);
                    }
                }

                if (remainingResultIndicesMap.isEmpty()) {
                    return results;
                }
            } while (i++ < numResultsWait);

            throw new TimeoutRuntimeException("Unable to get the expected capture result after "
                    + "waiting for " + numResultsWait + " results");
        }

        /**
         * Get an array list of {@link #CaptureFailure capture failure} with maxNumFailures entries
         * at most. If it times out before maxNumFailures failures are received, return the failures
         * received so far.
         *
         * @param maxNumFailures The maximal number of failures to return. If it times out before
         *                       the maximal number of failures are received, return the received
         *                       failures so far.
         * @throws UnsupportedOperationException If an error happens while waiting on the failure.
         */
        public ArrayList<CaptureFailure> getCaptureFailures(long maxNumFailures) {
            ArrayList<CaptureFailure> failures = new ArrayList<>();
            try {
                for (int i = 0; i < maxNumFailures; i++) {
                    CaptureFailure failure = mFailureQueue.poll(CAPTURE_RESULT_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS);
                    if (failure == null) {
                        // If waiting on a failure times out, return the failures so far.
                        break;
                    }
                    failures.add(failure);
                }
            }  catch (InterruptedException e) {
                throw new UnsupportedOperationException("Unhandled interrupted exception", e);
            }

            return failures;
        }

        /**
         * Wait until the capture start of a request and expected timestamp arrives or it times
         * out after a number of capture starts.
         *
         * @param request The request for the capture start to wait for.
         * @param timestamp The timestamp for the capture start to wait for.
         * @param numCaptureStartsWait The number of capture start events to wait for before timing
         *                             out.
         */
        public void waitForCaptureStart(CaptureRequest request, Long timestamp,
                int numCaptureStartsWait) throws Exception {
            Pair<CaptureRequest, Long> expectedShutter = new Pair<>(request, timestamp);

            int i = 0;
            do {
                Pair<CaptureRequest, Long> shutter = mCaptureStartQueue.poll(
                        CAPTURE_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (shutter == null) {
                    throw new TimeoutRuntimeException("Unable to get any more capture start " +
                            "event after waiting for " + CAPTURE_RESULT_TIMEOUT_MS + " ms.");
                } else if (expectedShutter.equals(shutter)) {
                    return;
                }

            } while (i++ < numCaptureStartsWait);

            throw new TimeoutRuntimeException("Unable to get the expected capture start " +
                    "event after waiting for " + numCaptureStartsWait + " capture starts");
        }

        public boolean hasMoreResults()
        {
            return mQueue.isEmpty();
        }

        public void drain() {
            mQueue.clear();
            mNumFramesArrived.getAndSet(0);
            mFailureQueue.clear();
            mCaptureStartQueue.clear();
        }
    }

    /**
     * Block until the camera is opened.
     *
     * <p>Don't use this to test #onDisconnected/#onError since this will throw
     * an AssertionError if it fails to open the camera device.</p>
     *
     * @return CameraDevice opened camera device
     *
     * @throws IllegalArgumentException
     *            If the handler is null, or if the handler's looper is current.
     * @throws CameraAccessException
     *            If open fails immediately.
     * @throws BlockingOpenException
     *            If open fails after blocking for some amount of time.
     * @throws TimeoutRuntimeException
     *            If opening times out. Typically unrecoverable.
     */
    public static CameraDevice openCamera(CameraManager manager, String cameraId,
            CameraDevice.StateCallback listener, Handler handler) throws CameraAccessException,
            BlockingOpenException {

        /**
         * Although camera2 API allows 'null' Handler (it will just use the current
         * thread's Looper), this is not what we want for CTS.
         *
         * In Camera framework test the default looper is used only to process events
         * in between test runs,
         * so anything sent there would not be executed inside a test and the test would fail.
         *
         * In this case, BlockingCameraManager#openCamera performs the check for us.
         */
        return (new BlockingCameraManager(manager)).openCamera(cameraId, listener, handler);
    }


    /**
     * Block until the camera is opened.
     *
     * <p>Don't use this to test #onDisconnected/#onError since this will throw
     * an AssertionError if it fails to open the camera device.</p>
     *
     * @throws IllegalArgumentException
     *            If the handler is null, or if the handler's looper is current.
     * @throws CameraAccessException
     *            If open fails immediately.
     * @throws BlockingOpenException
     *            If open fails after blocking for some amount of time.
     * @throws TimeoutRuntimeException
     *            If opening times out. Typically unrecoverable.
     */
    public static CameraDevice openCamera(CameraManager manager, String cameraId, Handler handler)
            throws CameraAccessException,
            BlockingOpenException {
        return openCamera(manager, cameraId, /*listener*/null, handler);
    }

    /**
     * Configure a new camera session with output surfaces and initial session parameters.
     *
     * @param camera The CameraDevice to be configured.
     * @param outputSurfaces The surface list that used for camera output.
     * @param listener The callback CameraDevice will notify when session is available.
     * @param handler The handler used to notify callbacks.
     * @param initialRequest Initial request settings to use as session parameters.
     */
    public static CameraCaptureSession configureCameraSessionWithParameters(CameraDevice camera,
            List<Surface> outputSurfaces, BlockingSessionCallback listener,
            Handler handler, CaptureRequest initialRequest) throws CameraAccessException {
        List<OutputConfiguration> outConfigurations = new ArrayList<>(outputSurfaces.size());
        for (Surface surface : outputSurfaces) {
            outConfigurations.add(new OutputConfiguration(surface));
        }
        SessionConfiguration sessionConfig = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outConfigurations,
                new HandlerExecutor(handler), listener);
        sessionConfig.setSessionParameters(initialRequest);
        camera.createCaptureSession(sessionConfig);

        CameraCaptureSession session = listener.waitAndGetSession(SESSION_CONFIGURE_TIMEOUT_MS);
        assertFalse("Camera session should not be a reprocessable session",
                session.isReprocessable());
        assertFalse("Capture session type must be regular",
                CameraConstrainedHighSpeedCaptureSession.class.isAssignableFrom(
                        session.getClass()));

        return session;
    }

    /**
     * Configure a new camera session with output surfaces and type.
     *
     * @param camera The CameraDevice to be configured.
     * @param outputSurfaces The surface list that used for camera output.
     * @param listener The callback CameraDevice will notify when capture results are available.
     */
    public static CameraCaptureSession configureCameraSession(CameraDevice camera,
            List<Surface> outputSurfaces, boolean isHighSpeed,
            CameraCaptureSession.StateCallback listener, Handler handler)
            throws CameraAccessException {
        BlockingSessionCallback sessionListener = new BlockingSessionCallback(listener);
        if (isHighSpeed) {
            camera.createConstrainedHighSpeedCaptureSession(outputSurfaces,
                    sessionListener, handler);
        } else {
            camera.createCaptureSession(outputSurfaces, sessionListener, handler);
        }
        CameraCaptureSession session =
                sessionListener.waitAndGetSession(SESSION_CONFIGURE_TIMEOUT_MS);
        assertFalse("Camera session should not be a reprocessable session",
                session.isReprocessable());
        String sessionType = isHighSpeed ? "High Speed" : "Normal";
        assertTrue("Capture session type must be " + sessionType,
                isHighSpeed ==
                CameraConstrainedHighSpeedCaptureSession.class.isAssignableFrom(session.getClass()));

        return session;
    }

    /**
     * Configure a new camera session with output surfaces.
     *
     * @param camera The CameraDevice to be configured.
     * @param outputSurfaces The surface list that used for camera output.
     * @param listener The callback CameraDevice will notify when capture results are available.
     */
    public static CameraCaptureSession configureCameraSession(CameraDevice camera,
            List<Surface> outputSurfaces,
            CameraCaptureSession.StateCallback listener, Handler handler)
            throws CameraAccessException {

        return configureCameraSession(camera, outputSurfaces, /*isHighSpeed*/false,
                listener, handler);
    }

    public static CameraCaptureSession configureReprocessableCameraSession(CameraDevice camera,
            InputConfiguration inputConfiguration, List<Surface> outputSurfaces,
            CameraCaptureSession.StateCallback listener, Handler handler)
            throws CameraAccessException {
        BlockingSessionCallback sessionListener = new BlockingSessionCallback(listener);
        camera.createReprocessableCaptureSession(inputConfiguration, outputSurfaces,
                sessionListener, handler);

        Integer[] sessionStates = {BlockingSessionCallback.SESSION_READY,
                                   BlockingSessionCallback.SESSION_CONFIGURE_FAILED};
        int state = sessionListener.getStateWaiter().waitForAnyOfStates(
                Arrays.asList(sessionStates), SESSION_CONFIGURE_TIMEOUT_MS);

        assertTrue("Creating a reprocessable session failed.",
                state == BlockingSessionCallback.SESSION_READY);

        CameraCaptureSession session =
                sessionListener.waitAndGetSession(SESSION_CONFIGURE_TIMEOUT_MS);
        assertTrue("Camera session should be a reprocessable session", session.isReprocessable());

        return session;
    }

    public static <T> void assertArrayNotEmpty(T arr, String message) {
        assertTrue(message, arr != null && Array.getLength(arr) > 0);
    }

    /**
     * Check if the format is a legal YUV format camera supported.
     */
    public static void checkYuvFormat(int format) {
        if ((format != ImageFormat.YUV_420_888) &&
                (format != ImageFormat.NV21) &&
                (format != ImageFormat.YV12)) {
            fail("Wrong formats: " + format);
        }
    }

    /**
     * Check if image size and format match given size and format.
     */
    public static void checkImage(Image image, int width, int height, int format) {
        // Image reader will wrap YV12/NV21 image by YUV_420_888
        if (format == ImageFormat.NV21 || format == ImageFormat.YV12) {
            format = ImageFormat.YUV_420_888;
        }
        assertNotNull("Input image is invalid", image);
        assertEquals("Format doesn't match", format, image.getFormat());
        assertEquals("Width doesn't match", width, image.getWidth());
        assertEquals("Height doesn't match", height, image.getHeight());
    }

    /**
     * <p>Read data from all planes of an Image into a contiguous unpadded, unpacked
     * 1-D linear byte array, such that it can be write into disk, or accessed by
     * software conveniently. It supports YUV_420_888/NV21/YV12 and JPEG input
     * Image format.</p>
     *
     * <p>For YUV_420_888/NV21/YV12/Y8/Y16, it returns a byte array that contains
     * the Y plane data first, followed by U(Cb), V(Cr) planes if there is any
     * (xstride = width, ystride = height for chroma and luma components).</p>
     *
     * <p>For JPEG, it returns a 1-D byte array contains a complete JPEG image.</p>
     */
    public static byte[] getDataFromImage(Image image) {
        assertNotNull("Invalid image:", image);
        int format = image.getFormat();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride, pixelStride;
        byte[] data = null;

        // Read image data
        Plane[] planes = image.getPlanes();
        assertTrue("Fail to get image planes", planes != null && planes.length > 0);

        // Check image validity
        checkAndroidImageFormat(image);

        ByteBuffer buffer = null;
        // JPEG doesn't have pixelstride and rowstride, treat it as 1D buffer.
        // Same goes for DEPTH_POINT_CLOUD
        if (format == ImageFormat.JPEG || format == ImageFormat.DEPTH_POINT_CLOUD ||
                format == ImageFormat.DEPTH_JPEG || format == ImageFormat.RAW_PRIVATE) {
            buffer = planes[0].getBuffer();
            assertNotNull("Fail to get jpeg or depth ByteBuffer", buffer);
            data = new byte[buffer.remaining()];
            buffer.get(data);
            buffer.rewind();
            return data;
        }

        int offset = 0;
        data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        int maxRowSize = planes[0].getRowStride();
        for (int i = 0; i < planes.length; i++) {
            if (maxRowSize < planes[i].getRowStride()) {
                maxRowSize = planes[i].getRowStride();
            }
        }
        byte[] rowData = new byte[maxRowSize];
        if(VERBOSE) Log.v(TAG, "get data from " + planes.length + " planes");
        for (int i = 0; i < planes.length; i++) {
            buffer = planes[i].getBuffer();
            assertNotNull("Fail to get bytebuffer from plane", buffer);
            rowStride = planes[i].getRowStride();
            pixelStride = planes[i].getPixelStride();
            assertTrue("pixel stride " + pixelStride + " is invalid", pixelStride > 0);
            if (VERBOSE) {
                Log.v(TAG, "pixelStride " + pixelStride);
                Log.v(TAG, "rowStride " + rowStride);
                Log.v(TAG, "width " + width);
                Log.v(TAG, "height " + height);
            }
            // For multi-planar yuv images, assuming yuv420 with 2x2 chroma subsampling.
            int w = (i == 0) ? width : width / 2;
            int h = (i == 0) ? height : height / 2;
            assertTrue("rowStride " + rowStride + " should be >= width " + w , rowStride >= w);
            for (int row = 0; row < h; row++) {
                int bytesPerPixel = ImageFormat.getBitsPerPixel(format) / 8;
                int length;
                if (pixelStride == bytesPerPixel) {
                    // Special case: optimized read of the entire row
                    length = w * bytesPerPixel;
                    buffer.get(data, offset, length);
                    offset += length;
                } else {
                    // Generic case: should work for any pixelStride but slower.
                    // Use intermediate buffer to avoid read byte-by-byte from
                    // DirectByteBuffer, which is very bad for performance
                    length = (w - 1) * pixelStride + bytesPerPixel;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[offset++] = rowData[col * pixelStride];
                    }
                }
                // Advance buffer the remainder of the row stride
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
            if (VERBOSE) Log.v(TAG, "Finished reading data from plane " + i);
            buffer.rewind();
        }
        return data;
    }

    /**
     * <p>Check android image format validity for an image, only support below formats:</p>
     *
     * <p>YUV_420_888/NV21/YV12, can add more for future</p>
     */
    public static void checkAndroidImageFormat(Image image) {
        int format = image.getFormat();
        Plane[] planes = image.getPlanes();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                assertEquals("YUV420 format Images should have 3 planes", 3, planes.length);
                break;
            case ImageFormat.JPEG:
            case ImageFormat.RAW_SENSOR:
            case ImageFormat.RAW_PRIVATE:
            case ImageFormat.DEPTH16:
            case ImageFormat.DEPTH_POINT_CLOUD:
            case ImageFormat.DEPTH_JPEG:
                assertEquals("JPEG/RAW/depth Images should have one plane", 1, planes.length);
                break;
            default:
                fail("Unsupported Image Format: " + format);
        }
    }

    public static void dumpFile(String fileName, Bitmap data) {
        FileOutputStream outStream;
        try {
            Log.v(TAG, "output will be saved as " + fileName);
            outStream = new FileOutputStream(fileName);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to create debug output file " + fileName, ioe);
        }

        try {
            data.compress(Bitmap.CompressFormat.JPEG, /*quality*/90, outStream);
            outStream.close();
        } catch (IOException ioe) {
            throw new RuntimeException("failed writing data to file " + fileName, ioe);
        }
    }

    public static void dumpFile(String fileName, byte[] data) {
        FileOutputStream outStream;
        try {
            Log.v(TAG, "output will be saved as " + fileName);
            outStream = new FileOutputStream(fileName);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to create debug output file " + fileName, ioe);
        }

        try {
            outStream.write(data);
            outStream.close();
        } catch (IOException ioe) {
            throw new RuntimeException("failed writing data to file " + fileName, ioe);
        }
    }

    /**
     * Get the available output sizes for the user-defined {@code format}.
     *
     * <p>Note that implementation-defined/hidden formats are not supported.</p>
     */
    public static Size[] getSupportedSizeForFormat(int format, String cameraId,
            CameraManager cameraManager) throws CameraAccessException {
        CameraCharacteristics properties = cameraManager.getCameraCharacteristics(cameraId);
        assertNotNull("Can't get camera characteristics!", properties);
        if (VERBOSE) {
            Log.v(TAG, "get camera characteristics for camera: " + cameraId);
        }
        StreamConfigurationMap configMap =
                properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] availableSizes = configMap.getOutputSizes(format);
        assertArrayNotEmpty(availableSizes, "availableSizes should not be empty for format: "
                + format);
        Size[] highResAvailableSizes = configMap.getHighResolutionOutputSizes(format);
        if (highResAvailableSizes != null && highResAvailableSizes.length > 0) {
            Size[] allSizes = new Size[availableSizes.length + highResAvailableSizes.length];
            System.arraycopy(availableSizes, 0, allSizes, 0,
                    availableSizes.length);
            System.arraycopy(highResAvailableSizes, 0, allSizes, availableSizes.length,
                    highResAvailableSizes.length);
            availableSizes = allSizes;
        }
        if (VERBOSE) Log.v(TAG, "Supported sizes are: " + Arrays.deepToString(availableSizes));
        return availableSizes;
    }

    /**
     * Get the available output sizes for the given class.
     *
     */
    public static Size[] getSupportedSizeForClass(Class klass, String cameraId,
            CameraManager cameraManager) throws CameraAccessException {
        CameraCharacteristics properties = cameraManager.getCameraCharacteristics(cameraId);
        assertNotNull("Can't get camera characteristics!", properties);
        if (VERBOSE) {
            Log.v(TAG, "get camera characteristics for camera: " + cameraId);
        }
        StreamConfigurationMap configMap =
                properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] availableSizes = configMap.getOutputSizes(klass);
        assertArrayNotEmpty(availableSizes, "availableSizes should not be empty for class: "
                + klass);
        Size[] highResAvailableSizes = configMap.getHighResolutionOutputSizes(ImageFormat.PRIVATE);
        if (highResAvailableSizes != null && highResAvailableSizes.length > 0) {
            Size[] allSizes = new Size[availableSizes.length + highResAvailableSizes.length];
            System.arraycopy(availableSizes, 0, allSizes, 0,
                    availableSizes.length);
            System.arraycopy(highResAvailableSizes, 0, allSizes, availableSizes.length,
                    highResAvailableSizes.length);
            availableSizes = allSizes;
        }
        if (VERBOSE) Log.v(TAG, "Supported sizes are: " + Arrays.deepToString(availableSizes));
        return availableSizes;
    }

    /**
     * Size comparator that compares the number of pixels it covers.
     *
     * <p>If two the areas of two sizes are same, compare the widths.</p>
     */
    public static class SizeComparator implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return CameraUtils
                    .compareSizes(lhs.getWidth(), lhs.getHeight(), rhs.getWidth(), rhs.getHeight());
        }
    }

    /**
     * Get sorted size list in descending order. Remove the sizes larger than
     * the bound. If the bound is null, don't do the size bound filtering.
     */
    static public List<Size> getSupportedPreviewSizes(String cameraId,
            CameraManager cameraManager, Size bound) throws CameraAccessException {

        Size[] rawSizes = getSupportedSizeForClass(android.view.SurfaceHolder.class, cameraId,
                cameraManager);
        assertArrayNotEmpty(rawSizes,
                "Available sizes for SurfaceHolder class should not be empty");
        if (VERBOSE) {
            Log.v(TAG, "Supported sizes are: " + Arrays.deepToString(rawSizes));
        }

        if (bound == null) {
            return getAscendingOrderSizes(Arrays.asList(rawSizes), /*ascending*/false);
        }

        List<Size> sizes = new ArrayList<Size>();
        for (Size sz: rawSizes) {
            if (sz.getWidth() <= bound.getWidth() && sz.getHeight() <= bound.getHeight()) {
                sizes.add(sz);
            }
        }
        return getAscendingOrderSizes(sizes, /*ascending*/false);
    }

    /**
     * Get a sorted list of sizes from a given size list.
     *
     * <p>
     * The size is compare by area it covers, if the areas are same, then
     * compare the widths.
     * </p>
     *
     * @param sizeList The input size list to be sorted
     * @param ascending True if the order is ascending, otherwise descending order
     * @return The ordered list of sizes
     */
    static public List<Size> getAscendingOrderSizes(final List<Size> sizeList, boolean ascending) {
        if (sizeList == null) {
            throw new IllegalArgumentException("sizeList shouldn't be null");
        }

        Comparator<Size> comparator = new SizeComparator();
        List<Size> sortedSizes = new ArrayList<Size>();
        sortedSizes.addAll(sizeList);
        Collections.sort(sortedSizes, comparator);
        if (!ascending) {
            Collections.reverse(sortedSizes);
        }

        return sortedSizes;
    }

    /**
     * Get sorted (descending order) size list for given format. Remove the sizes larger than
     * the bound. If the bound is null, don't do the size bound filtering.
     */
    static public List<Size> getSortedSizesForFormat(String cameraId,
            CameraManager cameraManager, int format, Size bound) throws CameraAccessException {
        Comparator<Size> comparator = new SizeComparator();
        Size[] sizes = getSupportedSizeForFormat(format, cameraId, cameraManager);
        List<Size> sortedSizes = null;
        if (bound != null) {
            sortedSizes = new ArrayList<Size>(/*capacity*/1);
            for (Size sz : sizes) {
                if (comparator.compare(sz, bound) <= 0) {
                    sortedSizes.add(sz);
                }
            }
        } else {
            sortedSizes = Arrays.asList(sizes);
        }
        assertTrue("Supported size list should have at least one element",
                sortedSizes.size() > 0);

        Collections.sort(sortedSizes, comparator);
        // Make it in descending order.
        Collections.reverse(sortedSizes);
        return sortedSizes;
    }

    /**
     * Get supported video size list for a given camera device.
     *
     * <p>
     * Filter out the sizes that are larger than the bound. If the bound is
     * null, don't do the size bound filtering.
     * </p>
     */
    static public List<Size> getSupportedVideoSizes(String cameraId,
            CameraManager cameraManager, Size bound) throws CameraAccessException {

        Size[] rawSizes = getSupportedSizeForClass(android.media.MediaRecorder.class,
                cameraId, cameraManager);
        assertArrayNotEmpty(rawSizes,
                "Available sizes for MediaRecorder class should not be empty");
        if (VERBOSE) {
            Log.v(TAG, "Supported sizes are: " + Arrays.deepToString(rawSizes));
        }

        if (bound == null) {
            return getAscendingOrderSizes(Arrays.asList(rawSizes), /*ascending*/false);
        }

        List<Size> sizes = new ArrayList<Size>();
        for (Size sz: rawSizes) {
            if (sz.getWidth() <= bound.getWidth() && sz.getHeight() <= bound.getHeight()) {
                sizes.add(sz);
            }
        }
        return getAscendingOrderSizes(sizes, /*ascending*/false);
    }

    /**
     * Get supported video size list (descending order) for a given camera device.
     *
     * <p>
     * Filter out the sizes that are larger than the bound. If the bound is
     * null, don't do the size bound filtering.
     * </p>
     */
    static public List<Size> getSupportedStillSizes(String cameraId,
            CameraManager cameraManager, Size bound) throws CameraAccessException {
        return getSortedSizesForFormat(cameraId, cameraManager, ImageFormat.JPEG, bound);
    }

    static public Size getMinPreviewSize(String cameraId, CameraManager cameraManager)
            throws CameraAccessException {
        List<Size> sizes = getSupportedPreviewSizes(cameraId, cameraManager, null);
        return sizes.get(sizes.size() - 1);
    }

    /**
     * Get max supported preview size for a camera device.
     */
    static public Size getMaxPreviewSize(String cameraId, CameraManager cameraManager)
            throws CameraAccessException {
        return getMaxPreviewSize(cameraId, cameraManager, /*bound*/null);
    }

    /**
     * Get max preview size for a camera device in the supported sizes that are no larger
     * than the bound.
     */
    static public Size getMaxPreviewSize(String cameraId, CameraManager cameraManager, Size bound)
            throws CameraAccessException {
        List<Size> sizes = getSupportedPreviewSizes(cameraId, cameraManager, bound);
        return sizes.get(0);
    }

    /**
     * Get max depth size for a camera device.
     */
    static public Size getMaxDepthSize(String cameraId, CameraManager cameraManager)
            throws CameraAccessException {
        List<Size> sizes = getSortedSizesForFormat(cameraId, cameraManager, ImageFormat.DEPTH16,
                /*bound*/ null);
        return sizes.get(0);
    }

    /**
     * Get the largest size by area.
     *
     * @param sizes an array of sizes, must have at least 1 element
     *
     * @return Largest Size
     *
     * @throws IllegalArgumentException if sizes was null or had 0 elements
     */
    public static Size getMaxSize(Size... sizes) {
        if (sizes == null || sizes.length == 0) {
            throw new IllegalArgumentException("sizes was empty");
        }

        Size sz = sizes[0];
        for (Size size : sizes) {
            if (size.getWidth() * size.getHeight() > sz.getWidth() * sz.getHeight()) {
                sz = size;
            }
        }

        return sz;
    }

    /**
     * Returns true if the given {@code array} contains the given element.
     *
     * @param array {@code array} to check for {@code elem}
     * @param elem {@code elem} to test for
     * @return {@code true} if the given element is contained
     */
    public static boolean contains(int[] array, int elem) {
        if (array == null) return false;
        for (int i = 0; i < array.length; i++) {
            if (elem == array[i]) return true;
        }
        return false;
    }

    /**
     * Get object array from byte array.
     *
     * @param array Input byte array to be converted
     * @return Byte object array converted from input byte array
     */
    public static Byte[] toObject(byte[] array) {
        return convertPrimitiveArrayToObjectArray(array, Byte.class);
    }

    /**
     * Get object array from int array.
     *
     * @param array Input int array to be converted
     * @return Integer object array converted from input int array
     */
    public static Integer[] toObject(int[] array) {
        return convertPrimitiveArrayToObjectArray(array, Integer.class);
    }

    /**
     * Get object array from float array.
     *
     * @param array Input float array to be converted
     * @return Float object array converted from input float array
     */
    public static Float[] toObject(float[] array) {
        return convertPrimitiveArrayToObjectArray(array, Float.class);
    }

    /**
     * Get object array from double array.
     *
     * @param array Input double array to be converted
     * @return Double object array converted from input double array
     */
    public static Double[] toObject(double[] array) {
        return convertPrimitiveArrayToObjectArray(array, Double.class);
    }

    /**
     * Convert a primitive input array into its object array version (e.g. from int[] to Integer[]).
     *
     * @param array Input array object
     * @param wrapperClass The boxed class it converts to
     * @return Boxed version of primitive array
     */
    private static <T> T[] convertPrimitiveArrayToObjectArray(final Object array,
            final Class<T> wrapperClass) {
        // getLength does the null check and isArray check already.
        int arrayLength = Array.getLength(array);
        if (arrayLength == 0) {
            throw new IllegalArgumentException("Input array shouldn't be empty");
        }

        @SuppressWarnings("unchecked")
        final T[] result = (T[]) Array.newInstance(wrapperClass, arrayLength);
        for (int i = 0; i < arrayLength; i++) {
            Array.set(result, i, Array.get(array, i));
        }
        return result;
    }

    /**
     * Validate image based on format and size.
     *
     * @param image The image to be validated.
     * @param width The image width.
     * @param height The image height.
     * @param format The image format.
     * @param filePath The debug dump file path, null if don't want to dump to
     *            file.
     * @throws UnsupportedOperationException if calling with an unknown format
     */
    public static void validateImage(Image image, int width, int height, int format,
            String filePath) {
        checkImage(image, width, height, format);

        /**
         * TODO: validate timestamp:
         * 1. capture result timestamp against the image timestamp (need
         * consider frame drops)
         * 2. timestamps should be monotonically increasing for different requests
         */
        if(VERBOSE) Log.v(TAG, "validating Image");
        byte[] data = getDataFromImage(image);
        assertTrue("Invalid image data", data != null && data.length > 0);

        switch (format) {
            case ImageFormat.JPEG:
                validateJpegData(data, width, height, filePath);
                break;
            case ImageFormat.YUV_420_888:
            case ImageFormat.YV12:
                validateYuvData(data, width, height, format, image.getTimestamp(), filePath);
                break;
            case ImageFormat.RAW_SENSOR:
                validateRaw16Data(data, width, height, format, image.getTimestamp(), filePath);
                break;
            case ImageFormat.DEPTH16:
                validateDepth16Data(data, width, height, format, image.getTimestamp(), filePath);
                break;
            case ImageFormat.DEPTH_POINT_CLOUD:
                validateDepthPointCloudData(data, width, height, format, image.getTimestamp(), filePath);
                break;
            case ImageFormat.RAW_PRIVATE:
                validateRawPrivateData(data, width, height, image.getTimestamp(), filePath);
                break;
            case ImageFormat.DEPTH_JPEG:
                validateDepthJpegData(data, width, height, format, image.getTimestamp(), filePath);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported format for validation: "
                        + format);
        }
    }

    public static class HandlerExecutor implements Executor {
        private final Handler mHandler;

        public HandlerExecutor(Handler handler) {
            assertNotNull("handler must be valid", handler);
            mHandler = handler;
        }

        @Override
        public void execute(Runnable runCmd) {
            mHandler.post(runCmd);
        }
    }

    /**
     * Provide a mock for {@link CameraDevice.StateCallback}.
     *
     * <p>Only useful because mockito can't mock {@link CameraDevice.StateCallback} which is an
     * abstract class.</p>
     *
     * <p>
     * Use this instead of other classes when needing to verify interactions, since
     * trying to spy on {@link BlockingStateCallback} (or others) will cause unnecessary extra
     * interactions which will cause false test failures.
     * </p>
     *
     */
    public static class MockStateCallback extends CameraDevice.StateCallback {

        @Override
        public void onOpened(CameraDevice camera) {
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
        }

        @Override
        public void onError(CameraDevice camera, int error) {
        }

        private MockStateCallback() {}

        /**
         * Create a Mockito-ready mocked StateCallback.
         */
        public static MockStateCallback mock() {
            return Mockito.spy(new MockStateCallback());
        }
    }

    private static void validateJpegData(byte[] jpegData, int width, int height, String filePath) {
        BitmapFactory.Options bmpOptions = new BitmapFactory.Options();
        // DecodeBound mode: only parse the frame header to get width/height.
        // it doesn't decode the pixel.
        bmpOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length, bmpOptions);
        assertEquals(width, bmpOptions.outWidth);
        assertEquals(height, bmpOptions.outHeight);

        // Pixel decoding mode: decode whole image. check if the image data
        // is decodable here.
        assertNotNull("Decoding jpeg failed",
                BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length));
        if (DEBUG && filePath != null) {
            String fileName =
                    filePath + "/" + width + "x" + height + ".jpeg";
            dumpFile(fileName, jpegData);
        }
    }

    private static void validateYuvData(byte[] yuvData, int width, int height, int format,
            long ts, String filePath) {
        checkYuvFormat(format);
        if (VERBOSE) Log.v(TAG, "Validating YUV data");
        int expectedSize = width * height * ImageFormat.getBitsPerPixel(format) / 8;
        assertEquals("Yuv data doesn't match", expectedSize, yuvData.length);

        // TODO: Can add data validation for test pattern.

        if (DEBUG && filePath != null) {
            String fileName =
                    filePath + "/" + width + "x" + height + "_" + ts / 1e6 + ".yuv";
            dumpFile(fileName, yuvData);
        }
    }

    private static void validateRaw16Data(byte[] rawData, int width, int height, int format,
            long ts, String filePath) {
        if (VERBOSE) Log.v(TAG, "Validating raw data");
        int expectedSize = width * height * ImageFormat.getBitsPerPixel(format) / 8;
        assertEquals("Raw data doesn't match", expectedSize, rawData.length);

        // TODO: Can add data validation for test pattern.

        if (DEBUG && filePath != null) {
            String fileName =
                    filePath + "/" + width + "x" + height + "_" + ts / 1e6 + ".raw16";
            dumpFile(fileName, rawData);
        }

        return;
    }

    private static void validateRawPrivateData(byte[] rawData, int width, int height,
            long ts, String filePath) {
        if (VERBOSE) Log.v(TAG, "Validating private raw data");
        // Expect each RAW pixel should occupy at least one byte and no more than 2.5 bytes
        int expectedSizeMin = width * height;
        int expectedSizeMax = width * height * 5 / 2;

        assertTrue("Opaque RAW size " + rawData.length + "out of normal bound [" +
                expectedSizeMin + "," + expectedSizeMax + "]",
                expectedSizeMin <= rawData.length && rawData.length <= expectedSizeMax);

        if (DEBUG && filePath != null) {
            String fileName =
                    filePath + "/" + width + "x" + height + "_" + ts / 1e6 + ".rawPriv";
            dumpFile(fileName, rawData);
        }

        return;
    }

    private static void validateDepth16Data(byte[] depthData, int width, int height, int format,
            long ts, String filePath) {

        if (VERBOSE) Log.v(TAG, "Validating depth16 data");
        int expectedSize = width * height * ImageFormat.getBitsPerPixel(format) / 8;
        assertEquals("Depth data doesn't match", expectedSize, depthData.length);


        if (DEBUG && filePath != null) {
            String fileName =
                    filePath + "/" + width + "x" + height + "_" + ts / 1e6 + ".depth16";
            dumpFile(fileName, depthData);
        }

        return;

    }

    private static void validateDepthPointCloudData(byte[] depthData, int width, int height, int format,
            long ts, String filePath) {

        if (VERBOSE) Log.v(TAG, "Validating depth point cloud data");

        // Can't validate size since it is variable

        if (DEBUG && filePath != null) {
            String fileName =
                    filePath + "/" + width + "x" + height + "_" + ts / 1e6 + ".depth_point_cloud";
            dumpFile(fileName, depthData);
        }

        return;

    }

    private static void validateDepthJpegData(byte[] depthData, int width, int height, int format,
            long ts, String filePath) {

        if (VERBOSE) Log.v(TAG, "Validating depth jpeg data");

        // Can't validate size since it is variable

        if (DEBUG && filePath != null) {
            String fileName =
                    filePath + "/" + width + "x" + height + "_" + ts / 1e6 + ".jpg";
            dumpFile(fileName, depthData);
        }

        return;

    }

    public static <T> T getValueNotNull(CaptureResult result, CaptureResult.Key<T> key) {
        if (result == null) {
            throw new IllegalArgumentException("Result must not be null");
        }

        T value = result.get(key);
        assertNotNull("Value of Key " + key.getName() + "shouldn't be null", value);
        return value;
    }

    public static <T> T getValueNotNull(CameraCharacteristics characteristics,
            CameraCharacteristics.Key<T> key) {
        if (characteristics == null) {
            throw new IllegalArgumentException("Camera characteristics must not be null");
        }

        T value = characteristics.get(key);
        assertNotNull("Value of Key " + key.getName() + "shouldn't be null", value);
        return value;
    }

    /**
     * Get a crop region for a given zoom factor and center position.
     * <p>
     * The center position is normalized position in range of [0, 1.0], where
     * (0, 0) represents top left corner, (1.0. 1.0) represents bottom right
     * corner. The center position could limit the effective minimal zoom
     * factor, for example, if the center position is (0.75, 0.75), the
     * effective minimal zoom position becomes 2.0. If the requested zoom factor
     * is smaller than 2.0, a crop region with 2.0 zoom factor will be returned.
     * </p>
     * <p>
     * The aspect ratio of the crop region is maintained the same as the aspect
     * ratio of active array.
     * </p>
     *
     * @param zoomFactor The zoom factor to generate the crop region, it must be
     *            >= 1.0
     * @param center The normalized zoom center point that is in the range of [0, 1].
     * @param maxZoom The max zoom factor supported by this device.
     * @param activeArray The active array size of this device.
     * @return crop region for the given normalized center and zoom factor.
     */
    public static Rect getCropRegionForZoom(float zoomFactor, final PointF center,
            final float maxZoom, final Rect activeArray) {
        if (zoomFactor < 1.0) {
            throw new IllegalArgumentException("zoom factor " + zoomFactor + " should be >= 1.0");
        }
        if (center.x > 1.0 || center.x < 0) {
            throw new IllegalArgumentException("center.x " + center.x
                    + " should be in range of [0, 1.0]");
        }
        if (center.y > 1.0 || center.y < 0) {
            throw new IllegalArgumentException("center.y " + center.y
                    + " should be in range of [0, 1.0]");
        }
        if (maxZoom < 1.0) {
            throw new IllegalArgumentException("max zoom factor " + maxZoom + " should be >= 1.0");
        }
        if (activeArray == null) {
            throw new IllegalArgumentException("activeArray must not be null");
        }

        float minCenterLength = Math.min(Math.min(center.x, 1.0f - center.x),
                Math.min(center.y, 1.0f - center.y));
        float minEffectiveZoom =  0.5f / minCenterLength;
        if (minEffectiveZoom > maxZoom) {
            throw new IllegalArgumentException("Requested center " + center.toString() +
                    " has minimal zoomable factor " + minEffectiveZoom + ", which exceeds max"
                            + " zoom factor " + maxZoom);
        }

        if (zoomFactor < minEffectiveZoom) {
            Log.w(TAG, "Requested zoomFactor " + zoomFactor + " > minimal zoomable factor "
                    + minEffectiveZoom + ". It will be overwritten by " + minEffectiveZoom);
            zoomFactor = minEffectiveZoom;
        }

        int cropCenterX = (int)(activeArray.width() * center.x);
        int cropCenterY = (int)(activeArray.height() * center.y);
        int cropWidth = (int) (activeArray.width() / zoomFactor);
        int cropHeight = (int) (activeArray.height() / zoomFactor);

        return new Rect(
                /*left*/cropCenterX - cropWidth / 2,
                /*top*/cropCenterY - cropHeight / 2,
                /*right*/ cropCenterX + cropWidth / 2 - 1,
                /*bottom*/cropCenterY + cropHeight / 2 - 1);
    }

    /**
     * Calculate output 3A region from the intersection of input 3A region and cropped region.
     *
     * @param requestRegions The input 3A regions
     * @param cropRect The cropped region
     * @return expected 3A regions output in capture result
     */
    public static MeteringRectangle[] getExpectedOutputRegion(
            MeteringRectangle[] requestRegions, Rect cropRect){
        MeteringRectangle[] resultRegions = new MeteringRectangle[requestRegions.length];
        for (int i = 0; i < requestRegions.length; i++) {
            Rect requestRect = requestRegions[i].getRect();
            Rect resultRect = new Rect();
            assertTrue("Input 3A region must intersect cropped region",
                        resultRect.setIntersect(requestRect, cropRect));
            resultRegions[i] = new MeteringRectangle(
                    resultRect,
                    requestRegions[i].getMeteringWeight());
        }
        return resultRegions;
    }

    /**
     * Copy source image data to destination image.
     *
     * @param src The source image to be copied from.
     * @param dst The destination image to be copied to.
     * @throws IllegalArgumentException If the source and destination images have
     *             different format, or one of the images is not copyable.
     */
    public static void imageCopy(Image src, Image dst) {
        if (src == null || dst == null) {
            throw new IllegalArgumentException("Images should be non-null");
        }
        if (src.getFormat() != dst.getFormat()) {
            throw new IllegalArgumentException("Src and dst images should have the same format");
        }
        if (src.getFormat() == ImageFormat.PRIVATE ||
                dst.getFormat() == ImageFormat.PRIVATE) {
            throw new IllegalArgumentException("PRIVATE format images are not copyable");
        }

        // TODO: check the owner of the dst image, it must be from ImageWriter, other source may
        // not be writable. Maybe we should add an isWritable() method in image class.

        Plane[] srcPlanes = src.getPlanes();
        Plane[] dstPlanes = dst.getPlanes();
        ByteBuffer srcBuffer = null;
        ByteBuffer dstBuffer = null;
        for (int i = 0; i < srcPlanes.length; i++) {
            srcBuffer = srcPlanes[i].getBuffer();
            int srcPos = srcBuffer.position();
            srcBuffer.rewind();
            dstBuffer = dstPlanes[i].getBuffer();
            dstBuffer.rewind();
            dstBuffer.put(srcBuffer);
            srcBuffer.position(srcPos);
            dstBuffer.rewind();
        }
    }

    /**
     * <p>
     * Checks whether the two images are strongly equal.
     * </p>
     * <p>
     * Two images are strongly equal if and only if the data, formats, sizes,
     * and timestamps are same. For {@link ImageFormat#PRIVATE PRIVATE} format
     * images, the image data is not accessible thus the data comparison is
     * effectively skipped as the number of planes is zero.
     * </p>
     * <p>
     * Note that this method compares the pixel data even outside of the crop
     * region, which may not be necessary for general use case.
     * </p>
     *
     * @param lhsImg First image to be compared with.
     * @param rhsImg Second image to be compared with.
     * @return true if the two images are equal, false otherwise.
     * @throws IllegalArgumentException If either of image is null.
     */
    public static boolean isImageStronglyEqual(Image lhsImg, Image rhsImg) {
        if (lhsImg == null || rhsImg == null) {
            throw new IllegalArgumentException("Images should be non-null");
        }

        if (lhsImg.getFormat() != rhsImg.getFormat()) {
            Log.i(TAG, "lhsImg format " + lhsImg.getFormat() + " is different with rhsImg format "
                    + rhsImg.getFormat());
            return false;
        }

        if (lhsImg.getWidth() != rhsImg.getWidth()) {
            Log.i(TAG, "lhsImg width " + lhsImg.getWidth() + " is different with rhsImg width "
                    + rhsImg.getWidth());
            return false;
        }

        if (lhsImg.getHeight() != rhsImg.getHeight()) {
            Log.i(TAG, "lhsImg height " + lhsImg.getHeight() + " is different with rhsImg height "
                    + rhsImg.getHeight());
            return false;
        }

        if (lhsImg.getTimestamp() != rhsImg.getTimestamp()) {
            Log.i(TAG, "lhsImg timestamp " + lhsImg.getTimestamp()
                    + " is different with rhsImg timestamp " + rhsImg.getTimestamp());
            return false;
        }

        if (!lhsImg.getCropRect().equals(rhsImg.getCropRect())) {
            Log.i(TAG, "lhsImg crop rect " + lhsImg.getCropRect()
                    + " is different with rhsImg crop rect " + rhsImg.getCropRect());
            return false;
        }

        // Compare data inside of the image.
        Plane[] lhsPlanes = lhsImg.getPlanes();
        Plane[] rhsPlanes = rhsImg.getPlanes();
        ByteBuffer lhsBuffer = null;
        ByteBuffer rhsBuffer = null;
        for (int i = 0; i < lhsPlanes.length; i++) {
            lhsBuffer = lhsPlanes[i].getBuffer();
            rhsBuffer = rhsPlanes[i].getBuffer();
            if (!lhsBuffer.equals(rhsBuffer)) {
                Log.i(TAG, "byte buffers for plane " +  i + " don't matach.");
                return false;
            }
        }

        return true;
    }

    /**
     * Set jpeg related keys in a capture request builder.
     *
     * @param builder The capture request builder to set the keys inl
     * @param exifData The exif data to set.
     * @param thumbnailSize The thumbnail size to set.
     * @param collector The camera error collector to collect errors.
     */
    public static void setJpegKeys(CaptureRequest.Builder builder, ExifTestData exifData,
            Size thumbnailSize, CameraErrorCollector collector) {
        builder.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, thumbnailSize);
        builder.set(CaptureRequest.JPEG_GPS_LOCATION, exifData.gpsLocation);
        builder.set(CaptureRequest.JPEG_ORIENTATION, exifData.jpegOrientation);
        builder.set(CaptureRequest.JPEG_QUALITY, exifData.jpegQuality);
        builder.set(CaptureRequest.JPEG_THUMBNAIL_QUALITY,
                exifData.thumbnailQuality);

        // Validate request set and get.
        collector.expectEquals("JPEG thumbnail size request set and get should match",
                thumbnailSize, builder.get(CaptureRequest.JPEG_THUMBNAIL_SIZE));
        collector.expectTrue("GPS locations request set and get should match.",
                areGpsFieldsEqual(exifData.gpsLocation,
                builder.get(CaptureRequest.JPEG_GPS_LOCATION)));
        collector.expectEquals("JPEG orientation request set and get should match",
                exifData.jpegOrientation,
                builder.get(CaptureRequest.JPEG_ORIENTATION));
        collector.expectEquals("JPEG quality request set and get should match",
                exifData.jpegQuality, builder.get(CaptureRequest.JPEG_QUALITY));
        collector.expectEquals("JPEG thumbnail quality request set and get should match",
                exifData.thumbnailQuality,
                builder.get(CaptureRequest.JPEG_THUMBNAIL_QUALITY));
    }

    /**
     * Simple validation of JPEG image size and format.
     * <p>
     * Only validate the image object consistency. It is fast, but doesn't actually
     * check the buffer data. Assert is used here as it make no sense to
     * continue the test if the jpeg image captured has some serious failures.
     * </p>
     *
     * @param image The captured jpeg image
     * @param expectedSize Expected capture jpeg size
     */
    public static void basicValidateJpegImage(Image image, Size expectedSize) {
        Size imageSz = new Size(image.getWidth(), image.getHeight());
        assertTrue(
                String.format("Image size doesn't match (expected %s, actual %s) ",
                        expectedSize.toString(), imageSz.toString()), expectedSize.equals(imageSz));
        assertEquals("Image format should be JPEG", ImageFormat.JPEG, image.getFormat());
        assertNotNull("Image plane shouldn't be null", image.getPlanes());
        assertEquals("Image plane number should be 1", 1, image.getPlanes().length);

        // Jpeg decoding validate was done in ImageReaderTest, no need to duplicate the test here.
    }

    /**
     * Verify the JPEG EXIF and JPEG related keys in a capture result are expected.
     * - Capture request get values are same as were set.
     * - capture result's exif data is the same as was set by
     *   the capture request.
     * - new tags in the result set by the camera service are
     *   present and semantically correct.
     *
     * @param image The output JPEG image to verify.
     * @param captureResult The capture result to verify.
     * @param expectedSize The expected JPEG size.
     * @param expectedThumbnailSize The expected thumbnail size.
     * @param expectedExifData The expected EXIF data
     * @param staticInfo The static metadata for the camera device.
     * @param jpegFilename The filename to dump the jpeg to.
     * @param collector The camera error collector to collect errors.
     */
    public static void verifyJpegKeys(Image image, CaptureResult captureResult, Size expectedSize,
            Size expectedThumbnailSize, ExifTestData expectedExifData, StaticMetadata staticInfo,
            CameraErrorCollector collector) throws Exception {

        basicValidateJpegImage(image, expectedSize);

        byte[] jpegBuffer = getDataFromImage(image);
        // Have to dump into a file to be able to use ExifInterface
        String jpegFilename = DEBUG_FILE_NAME_BASE + "/verifyJpegKeys.jpeg";
        dumpFile(jpegFilename, jpegBuffer);
        ExifInterface exif = new ExifInterface(jpegFilename);

        if (expectedThumbnailSize.equals(new Size(0,0))) {
            collector.expectTrue("Jpeg shouldn't have thumbnail when thumbnail size is (0, 0)",
                    !exif.hasThumbnail());
        } else {
            collector.expectTrue("Jpeg must have thumbnail for thumbnail size " +
                    expectedThumbnailSize, exif.hasThumbnail());
        }

        // Validate capture result vs. request
        Size resultThumbnailSize = captureResult.get(CaptureResult.JPEG_THUMBNAIL_SIZE);
        int orientationTested = expectedExifData.jpegOrientation;
        // Legacy shim always doesn't rotate thumbnail size
        if ((orientationTested == 90 || orientationTested == 270) &&
                staticInfo.isHardwareLevelLimitedOrBetter()) {
            int exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    /*defaultValue*/-1);
            if (exifOrientation == ExifInterface.ORIENTATION_UNDEFINED) {
                // Device physically rotated image+thumbnail data
                // Expect thumbnail size to be also rotated
                resultThumbnailSize = new Size(resultThumbnailSize.getHeight(),
                        resultThumbnailSize.getWidth());
            }
        }

        collector.expectEquals("JPEG thumbnail size result and request should match",
                expectedThumbnailSize, resultThumbnailSize);
        if (collector.expectKeyValueNotNull(captureResult, CaptureResult.JPEG_GPS_LOCATION) !=
                null) {
            collector.expectTrue("GPS location result and request should match.",
                    areGpsFieldsEqual(expectedExifData.gpsLocation,
                    captureResult.get(CaptureResult.JPEG_GPS_LOCATION)));
        }
        collector.expectEquals("JPEG orientation result and request should match",
                expectedExifData.jpegOrientation,
                captureResult.get(CaptureResult.JPEG_ORIENTATION));
        collector.expectEquals("JPEG quality result and request should match",
                expectedExifData.jpegQuality, captureResult.get(CaptureResult.JPEG_QUALITY));
        collector.expectEquals("JPEG thumbnail quality result and request should match",
                expectedExifData.thumbnailQuality,
                captureResult.get(CaptureResult.JPEG_THUMBNAIL_QUALITY));

        // Validate other exif tags for all non-legacy devices
        if (!staticInfo.isHardwareLevelLegacy()) {
            verifyJpegExifExtraTags(exif, expectedSize, captureResult, staticInfo, collector);
        }
    }

    /**
     * Get the degree of an EXIF orientation.
     */
    private static int getExifOrientationInDegree(int exifOrientation,
            CameraErrorCollector collector) {
        switch (exifOrientation) {
            case ExifInterface.ORIENTATION_NORMAL:
                return 0;
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                collector.addMessage("It is impossible to get non 0, 90, 180, 270 degress exif" +
                        "info based on the request orientation range");
                return 0;
        }
    }

    /**
     * Validate and return the focal length.
     *
     * @param result Capture result to get the focal length
     * @return Focal length from capture result or -1 if focal length is not available.
     */
    private static float validateFocalLength(CaptureResult result, StaticMetadata staticInfo,
            CameraErrorCollector collector) {
        float[] focalLengths = staticInfo.getAvailableFocalLengthsChecked();
        Float resultFocalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH);
        if (collector.expectTrue("Focal length is invalid",
                resultFocalLength != null && resultFocalLength > 0)) {
            List<Float> focalLengthList =
                    Arrays.asList(CameraTestUtils.toObject(focalLengths));
            collector.expectTrue("Focal length should be one of the available focal length",
                    focalLengthList.contains(resultFocalLength));
            return resultFocalLength;
        }
        return -1;
    }

    /**
     * Validate and return the aperture.
     *
     * @param result Capture result to get the aperture
     * @return Aperture from capture result or -1 if aperture is not available.
     */
    private static float validateAperture(CaptureResult result, StaticMetadata staticInfo,
            CameraErrorCollector collector) {
        float[] apertures = staticInfo.getAvailableAperturesChecked();
        Float resultAperture = result.get(CaptureResult.LENS_APERTURE);
        if (collector.expectTrue("Capture result aperture is invalid",
                resultAperture != null && resultAperture > 0)) {
            List<Float> apertureList =
                    Arrays.asList(CameraTestUtils.toObject(apertures));
            collector.expectTrue("Aperture should be one of the available apertures",
                    apertureList.contains(resultAperture));
            return resultAperture;
        }
        return -1;
    }

    /**
     * Return the closest value in an array of floats.
     */
    private static float getClosestValueInArray(float[] values, float target) {
        int minIdx = 0;
        float minDistance = Math.abs(values[0] - target);
        for(int i = 0; i < values.length; i++) {
            float distance = Math.abs(values[i] - target);
            if (minDistance > distance) {
                minDistance = distance;
                minIdx = i;
            }
        }

        return values[minIdx];
    }

    /**
     * Return if two Location's GPS field are the same.
     */
    private static boolean areGpsFieldsEqual(Location a, Location b) {
        if (a == null || b == null) {
            return false;
        }

        return a.getTime() == b.getTime() && a.getLatitude() == b.getLatitude() &&
                a.getLongitude() == b.getLongitude() && a.getAltitude() == b.getAltitude() &&
                a.getProvider() == b.getProvider();
    }

    /**
     * Verify extra tags in JPEG EXIF
     */
    private static void verifyJpegExifExtraTags(ExifInterface exif, Size jpegSize,
            CaptureResult result, StaticMetadata staticInfo, CameraErrorCollector collector)
            throws ParseException {
        /**
         * TAG_IMAGE_WIDTH and TAG_IMAGE_LENGTH and TAG_ORIENTATION.
         * Orientation and exif width/height need to be tested carefully, two cases:
         *
         * 1. Device rotate the image buffer physically, then exif width/height may not match
         * the requested still capture size, we need swap them to check.
         *
         * 2. Device use the exif tag to record the image orientation, it doesn't rotate
         * the jpeg image buffer itself. In this case, the exif width/height should always match
         * the requested still capture size, and the exif orientation should always match the
         * requested orientation.
         *
         */
        int exifWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, /*defaultValue*/0);
        int exifHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, /*defaultValue*/0);
        Size exifSize = new Size(exifWidth, exifHeight);
        // Orientation could be missing, which is ok, default to 0.
        int exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                /*defaultValue*/-1);
        // Get requested orientation from result, because they should be same.
        if (collector.expectKeyValueNotNull(result, CaptureResult.JPEG_ORIENTATION) != null) {
            int requestedOrientation = result.get(CaptureResult.JPEG_ORIENTATION);
            final int ORIENTATION_MIN = ExifInterface.ORIENTATION_UNDEFINED;
            final int ORIENTATION_MAX = ExifInterface.ORIENTATION_ROTATE_270;
            boolean orientationValid = collector.expectTrue(String.format(
                    "Exif orientation must be in range of [%d, %d]",
                    ORIENTATION_MIN, ORIENTATION_MAX),
                    exifOrientation >= ORIENTATION_MIN && exifOrientation <= ORIENTATION_MAX);
            if (orientationValid) {
                /**
                 * Device captured image doesn't respect the requested orientation,
                 * which means it rotates the image buffer physically. Then we
                 * should swap the exif width/height accordingly to compare.
                 */
                boolean deviceRotatedImage = exifOrientation == ExifInterface.ORIENTATION_UNDEFINED;

                if (deviceRotatedImage) {
                    // Case 1.
                    boolean needSwap = (requestedOrientation % 180 == 90);
                    if (needSwap) {
                        exifSize = new Size(exifHeight, exifWidth);
                    }
                } else {
                    // Case 2.
                    collector.expectEquals("Exif orientation should match requested orientation",
                            requestedOrientation, getExifOrientationInDegree(exifOrientation,
                            collector));
                }
            }
        }

        /**
         * Ideally, need check exifSize == jpegSize == actual buffer size. But
         * jpegSize == jpeg decode bounds size(from jpeg jpeg frame
         * header, not exif) was validated in ImageReaderTest, no need to
         * validate again here.
         */
        collector.expectEquals("Exif size should match jpeg capture size", jpegSize, exifSize);

        // TAG_DATETIME, it should be local time
        long currentTimeInMs = System.currentTimeMillis();
        long currentTimeInSecond = currentTimeInMs / 1000;
        Date date = new Date(currentTimeInMs);
        String localDatetime = new SimpleDateFormat("yyyy:MM:dd HH:").format(date);
        String dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME);
        if (collector.expectTrue("Exif TAG_DATETIME shouldn't be null", dateTime != null)) {
            collector.expectTrue("Exif TAG_DATETIME is wrong",
                    dateTime.length() == EXIF_DATETIME_LENGTH);
            long exifTimeInSecond =
                    new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").parse(dateTime).getTime() / 1000;
            long delta = currentTimeInSecond - exifTimeInSecond;
            collector.expectTrue("Capture time deviates too much from the current time",
                    Math.abs(delta) < EXIF_DATETIME_ERROR_MARGIN_SEC);
            // It should be local time.
            collector.expectTrue("Exif date time should be local time",
                    dateTime.startsWith(localDatetime));
        }

        // TAG_FOCAL_LENGTH.
        float[] focalLengths = staticInfo.getAvailableFocalLengthsChecked();
        float exifFocalLength = (float)exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1);
        collector.expectEquals("Focal length should match",
                getClosestValueInArray(focalLengths, exifFocalLength),
                exifFocalLength, EXIF_FOCAL_LENGTH_ERROR_MARGIN);
        // More checks for focal length.
        collector.expectEquals("Exif focal length should match capture result",
                validateFocalLength(result, staticInfo, collector), exifFocalLength);

        // TAG_EXPOSURE_TIME
        // ExifInterface API gives exposure time value in the form of float instead of rational
        String exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
        collector.expectNotNull("Exif TAG_EXPOSURE_TIME shouldn't be null", exposureTime);
        if (staticInfo.areKeysAvailable(CaptureResult.SENSOR_EXPOSURE_TIME)) {
            if (exposureTime != null) {
                double exposureTimeValue = Double.parseDouble(exposureTime);
                long expTimeResult = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                double expected = expTimeResult / 1e9;
                double tolerance = expected * EXIF_EXPOSURE_TIME_ERROR_MARGIN_RATIO;
                tolerance = Math.max(tolerance, EXIF_EXPOSURE_TIME_MIN_ERROR_MARGIN_SEC);
                collector.expectEquals("Exif exposure time doesn't match", expected,
                        exposureTimeValue, tolerance);
            }
        }

        // TAG_APERTURE
        // ExifInterface API gives aperture value in the form of float instead of rational
        String exifAperture = exif.getAttribute(ExifInterface.TAG_APERTURE);
        collector.expectNotNull("Exif TAG_APERTURE shouldn't be null", exifAperture);
        if (staticInfo.areKeysAvailable(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)) {
            float[] apertures = staticInfo.getAvailableAperturesChecked();
            if (exifAperture != null) {
                float apertureValue = Float.parseFloat(exifAperture);
                collector.expectEquals("Aperture value should match",
                        getClosestValueInArray(apertures, apertureValue),
                        apertureValue, EXIF_APERTURE_ERROR_MARGIN);
                // More checks for aperture.
                collector.expectEquals("Exif aperture length should match capture result",
                        validateAperture(result, staticInfo, collector), apertureValue);
            }
        }

        /**
         * TAG_FLASH. TODO: For full devices, can check a lot more info
         * (http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/EXIF.html#Flash)
         */
        String flash = exif.getAttribute(ExifInterface.TAG_FLASH);
        collector.expectNotNull("Exif TAG_FLASH shouldn't be null", flash);

        /**
         * TAG_WHITE_BALANCE. TODO: For full devices, with the DNG tags, we
         * should be able to cross-check android.sensor.referenceIlluminant.
         */
        String whiteBalance = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE);
        collector.expectNotNull("Exif TAG_WHITE_BALANCE shouldn't be null", whiteBalance);

        // TAG_MAKE
        String make = exif.getAttribute(ExifInterface.TAG_MAKE);
        collector.expectEquals("Exif TAG_MAKE is incorrect", Build.MANUFACTURER, make);

        // TAG_MODEL
        String model = exif.getAttribute(ExifInterface.TAG_MODEL);
        collector.expectEquals("Exif TAG_MODEL is incorrect", Build.MODEL, model);


        // TAG_ISO
        int iso = exif.getAttributeInt(ExifInterface.TAG_ISO, /*defaultValue*/-1);
        if (staticInfo.areKeysAvailable(CaptureResult.SENSOR_SENSITIVITY)) {
            int expectedIso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            collector.expectEquals("Exif TAG_ISO is incorrect", expectedIso, iso);
        }

        // TAG_DATETIME_DIGITIZED (a.k.a Create time for digital cameras).
        String digitizedTime = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED);
        collector.expectNotNull("Exif TAG_DATETIME_DIGITIZED shouldn't be null", digitizedTime);
        if (digitizedTime != null) {
            String expectedDateTime = exif.getAttribute(ExifInterface.TAG_DATETIME);
            collector.expectNotNull("Exif TAG_DATETIME shouldn't be null", expectedDateTime);
            if (expectedDateTime != null) {
                collector.expectEquals("dataTime should match digitizedTime",
                        expectedDateTime, digitizedTime);
            }
        }

        /**
         * TAG_SUBSEC_TIME. Since the sub second tag strings are truncated to at
         * most 9 digits in ExifInterface implementation, use getAttributeInt to
         * sanitize it. When the default value -1 is returned, it means that
         * this exif tag either doesn't exist or is a non-numerical invalid
         * string. Same rule applies to the rest of sub second tags.
         */
        int subSecTime = exif.getAttributeInt(ExifInterface.TAG_SUBSEC_TIME, /*defaultValue*/-1);
        collector.expectTrue("Exif TAG_SUBSEC_TIME value is null or invalid!", subSecTime > 0);

        // TAG_SUBSEC_TIME_ORIG
        int subSecTimeOrig = exif.getAttributeInt(ExifInterface.TAG_SUBSEC_TIME_ORIG,
                /*defaultValue*/-1);
        collector.expectTrue("Exif TAG_SUBSEC_TIME_ORIG value is null or invalid!",
                subSecTimeOrig > 0);

        // TAG_SUBSEC_TIME_DIG
        int subSecTimeDig = exif.getAttributeInt(ExifInterface.TAG_SUBSEC_TIME_DIG,
                /*defaultValue*/-1);
        collector.expectTrue(
                "Exif TAG_SUBSEC_TIME_DIG value is null or invalid!", subSecTimeDig > 0);
    }


    /**
     * Immutable class wrapping the exif test data.
     */
    public static class ExifTestData {
        public final Location gpsLocation;
        public final int jpegOrientation;
        public final byte jpegQuality;
        public final byte thumbnailQuality;

        public ExifTestData(Location location, int orientation,
                byte jpgQuality, byte thumbQuality) {
            gpsLocation = location;
            jpegOrientation = orientation;
            jpegQuality = jpgQuality;
            thumbnailQuality = thumbQuality;
        }
    }

    public static Size getPreviewSizeBound(WindowManager windowManager, Size bound) {
        Display display = windowManager.getDefaultDisplay();

        int width = display.getWidth();
        int height = display.getHeight();

        if (height > width) {
            height = width;
            width = display.getHeight();
        }

        if (bound.getWidth() <= width &&
            bound.getHeight() <= height)
            return bound;
        else
            return new Size(width, height);
    }
}
