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

package com.example.android.camera2.cameratoo;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A basic demonstration of how to write a point-and-shoot camera app against the new
 * android.hardware.camera2 API.
 */
public class CameraTooActivity extends Activity {
    /** Output files will be saved as /sdcard/Pictures/cameratoo*.jpg */
    static final String CAPTURE_FILENAME_PREFIX = "cameratoo";
    /** Tag to distinguish log prints. */
    static final String TAG = "CameraToo";

    /** An additional thread for running tasks that shouldn't block the UI. */
    HandlerThread mBackgroundThread;
    /** Handler for running tasks in the background. */
    Handler mBackgroundHandler;
    /** Handler for running tasks on the UI thread. */
    Handler mForegroundHandler;
    /** View for displaying the camera preview. */
    SurfaceView mSurfaceView;
    /** Used to retrieve the captured image when the user takes a snapshot. */
    ImageReader mCaptureBuffer;
    /** Handle to the Android camera services. */
    CameraManager mCameraManager;
    /** The specific camera device that we're using. */
    CameraDevice mCamera;
    /** Our image capture session. */
    CameraCaptureSession mCaptureSession;

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values.
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width The minimum desired width
     * @param height The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    static Size chooseBigEnoughSize(Size[] choices, int width, int height) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<Size>();
        for (Size option : choices) {
            if (option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    /**
     * Called when our {@code Activity} gains focus. <p>Starts initializing the camera.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();

        // Start a background thread to manage camera requests
        mBackgroundThread = new HandlerThread("background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mForegroundHandler = new Handler(getMainLooper());

        mCameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        // Inflate the SurfaceView, set it as the main layout, and attach a listener
        View layout = getLayoutInflater().inflate(R.layout.mainactivity, null);
        mSurfaceView = (SurfaceView) layout.findViewById(R.id.mainSurfaceView);
        mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
        setContentView(mSurfaceView);

        // Control flow continues in mSurfaceHolderCallback.surfaceChanged()
    }

    /**
     * Called when our {@code Activity} loses focus. <p>Tears everything back down.</p>
     */
    @Override
    protected void onPause() {
        super.onPause();

        try {
            // Ensure SurfaceHolderCallback#surfaceChanged() will run again if the user returns
            mSurfaceView.getHolder().setFixedSize(/*width*/0, /*height*/0);

            // Cancel any stale preview jobs
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
        } finally {
            if (mCamera != null) {
                mCamera.close();
                mCamera = null;
            }
        }

        // Finish processing posted messages, then join on the handling thread
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
        } catch (InterruptedException ex) {
            Log.e(TAG, "Background worker thread was interrupted while joined", ex);
        }

        // Close the ImageReader now that the background thread has stopped
        if (mCaptureBuffer != null) mCaptureBuffer.close();
    }

    /**
     * Called when the user clicks on our {@code SurfaceView}, which has ID {@code mainSurfaceView}
     * as defined in the {@code mainactivity.xml} layout file. <p>Captures a full-resolution image
     * and saves it to permanent storage.</p>
     */
    public void onClickOnSurfaceView(View v) {
        if (mCaptureSession != null) {
            try {
                CaptureRequest.Builder requester =
                        mCamera.createCaptureRequest(mCamera.TEMPLATE_STILL_CAPTURE);
                requester.addTarget(mCaptureBuffer.getSurface());
                try {
                    // This handler can be null because we aren't actually attaching any callback
                    mCaptureSession.capture(requester.build(), /*listener*/null, /*handler*/null);
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Failed to file actual capture request", ex);
                }
            } catch (CameraAccessException ex) {
                Log.e(TAG, "Failed to build actual capture request", ex);
            }
        } else {
            Log.e(TAG, "User attempted to perform a capture outside our session");
        }

        // Control flow continues in mImageCaptureListener.onImageAvailable()
    }

    /**
     * Callbacks invoked upon state changes in our {@code SurfaceView}.
     */
    final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        /** The camera device to use, or null if we haven't yet set a fixed surface size. */
        private String mCameraId;

        /** Whether we received a change callback after setting our fixed surface size. */
        private boolean mGotSecondCallback;

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            // This is called every time the surface returns to the foreground
            Log.i(TAG, "Surface created");
            mCameraId = null;
            mGotSecondCallback = false;
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "Surface destroyed");
            holder.removeCallback(this);
            // We don't stop receiving callbacks forever because onResume() will reattach us
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // On the first invocation, width and height were automatically set to the view's size
            if (mCameraId == null) {
                // Find the device's back-facing camera and set the destination buffer sizes
                try {
                    for (String cameraId : mCameraManager.getCameraIdList()) {
                        CameraCharacteristics cameraCharacteristics =
                                mCameraManager.getCameraCharacteristics(cameraId);
                        if (cameraCharacteristics.get(cameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_BACK) {
                            Log.i(TAG, "Found a back-facing camera");
                            StreamConfigurationMap info = cameraCharacteristics
                                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                            // Bigger is better when it comes to saving our image
                            Size largestSize = Collections.max(
                                    Arrays.asList(info.getOutputSizes(ImageFormat.JPEG)),
                                    new CompareSizesByArea());

                            // Prepare an ImageReader in case the user wants to capture images
                            Log.i(TAG, "Capture size: " + largestSize);
                            mCaptureBuffer = ImageReader.newInstance(largestSize.getWidth(),
                                    largestSize.getHeight(), ImageFormat.JPEG, /*maxImages*/2);
                            mCaptureBuffer.setOnImageAvailableListener(
                                    mImageCaptureListener, mBackgroundHandler);

                            // Danger, W.R.! Attempting to use too large a preview size could
                            // exceed the camera bus' bandwidth limitation, resulting in
                            // gorgeous previews but the storage of garbage capture data.
                            Log.i(TAG, "SurfaceView size: " +
                                    mSurfaceView.getWidth() + 'x' + mSurfaceView.getHeight());
                            Size optimalSize = chooseBigEnoughSize(
                                    info.getOutputSizes(SurfaceHolder.class), width, height);

                            // Set the SurfaceHolder to use the camera's largest supported size
                            Log.i(TAG, "Preview size: " + optimalSize);
                            SurfaceHolder surfaceHolder = mSurfaceView.getHolder();
                            surfaceHolder.setFixedSize(optimalSize.getWidth(),
                                    optimalSize.getHeight());

                            mCameraId = cameraId;
                            return;

                            // Control flow continues with this method one more time
                            // (since we just changed our own size)
                        }
                    }
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Unable to list cameras", ex);
                }

                Log.e(TAG, "Didn't find any back-facing cameras");
            // This is the second time the method is being invoked: our size change is complete
            } else if (!mGotSecondCallback) {
                if (mCamera != null) {
                    Log.e(TAG, "Aborting camera open because it hadn't been closed");
                    return;
                }

                // Open the camera device
                try {
                    mCameraManager.openCamera(mCameraId, mCameraStateCallback,
                            mBackgroundHandler);
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Failed to configure output surface", ex);
                }
                mGotSecondCallback = true;

                // Control flow continues in mCameraStateCallback.onOpened()
            }
        }};

    /**
     * Calledbacks invoked upon state changes in our {@code CameraDevice}. <p>These are run on
     * {@code mBackgroundThread}.</p>
     */
    final CameraDevice.StateCallback mCameraStateCallback =
            new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.i(TAG, "Successfully opened camera");
            mCamera = camera;
            try {
                List<Surface> outputs = Arrays.asList(
                        mSurfaceView.getHolder().getSurface(), mCaptureBuffer.getSurface());
                camera.createCaptureSession(outputs, mCaptureSessionListener,
                        mBackgroundHandler);
            } catch (CameraAccessException ex) {
                Log.e(TAG, "Failed to create a capture session", ex);
            }

            // Control flow continues in mCaptureSessionListener.onConfigured()
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.e(TAG, "Camera was disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "State error on device '" + camera.getId() + "': code " + error);
        }};

    /**
     * Callbacks invoked upon state changes in our {@code CameraCaptureSession}. <p>These are run on
     * {@code mBackgroundThread}.</p>
     */
    final CameraCaptureSession.StateCallback mCaptureSessionListener =
            new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            Log.i(TAG, "Finished configuring camera outputs");
            mCaptureSession = session;

            SurfaceHolder holder = mSurfaceView.getHolder();
            if (holder != null) {
                try {
                    // Build a request for preview footage
                    CaptureRequest.Builder requestBuilder =
                            mCamera.createCaptureRequest(mCamera.TEMPLATE_PREVIEW);
                    requestBuilder.addTarget(holder.getSurface());
                    CaptureRequest previewRequest = requestBuilder.build();

                    // Start displaying preview images
                    try {
                        session.setRepeatingRequest(previewRequest, /*listener*/null,
                                /*handler*/null);
                    } catch (CameraAccessException ex) {
                        Log.e(TAG, "Failed to make repeating preview request", ex);
                    }
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Failed to build preview request", ex);
                }
            }
            else {
                Log.e(TAG, "Holder didn't exist when trying to formulate preview request");
            }
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            mCaptureSession = null;
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "Configuration error on device '" + mCamera.getId());
        }};

    /**
     * Callback invoked when we've received a JPEG image from the camera.
     */
    final ImageReader.OnImageAvailableListener mImageCaptureListener =
            new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            // Save the image once we get a chance
            mBackgroundHandler.post(new CapturedImageSaver(reader.acquireNextImage()));

            // Control flow continues in CapturedImageSaver#run()
        }};

    /**
     * Deferred processor responsible for saving snapshots to disk. <p>This is run on
     * {@code mBackgroundThread}.</p>
     */
    static class CapturedImageSaver implements Runnable {
        /** The image to save. */
        private Image mCapture;

        public CapturedImageSaver(Image capture) {
            mCapture = capture;
        }

        @Override
        public void run() {
            try {
                // Choose an unused filename under the Pictures/ directory
                File file = File.createTempFile(CAPTURE_FILENAME_PREFIX, ".jpg",
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES));
                try (FileOutputStream ostream = new FileOutputStream(file)) {
                    Log.i(TAG, "Retrieved image is" +
                            (mCapture.getFormat() == ImageFormat.JPEG ? "" : "n't") + " a JPEG");
                    ByteBuffer buffer = mCapture.getPlanes()[0].getBuffer();
                    Log.i(TAG, "Captured image size: " +
                            mCapture.getWidth() + 'x' + mCapture.getHeight());

                    // Write the image out to the chosen file
                    byte[] jpeg = new byte[buffer.remaining()];
                    buffer.get(jpeg);
                    ostream.write(jpeg);
                } catch (FileNotFoundException ex) {
                    Log.e(TAG, "Unable to open output file for writing", ex);
                } catch (IOException ex) {
                    Log.e(TAG, "Failed to write the image to the output file", ex);
                }
            } catch (IOException ex) {
                Log.e(TAG, "Unable to create a new output file", ex);
            } finally {
                mCapture.close();
            }
        }
    }
}
