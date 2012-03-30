/*
 * Copyright (C) 2012 The Android Open Source Project
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


package com.example.android.rs.sto;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.List;

public class CameraCapture {

    public interface CameraFrameListener {
        public void onNewCameraFrame();
    }

    static final int FRAMES_PER_SEC = 30;

    private Camera mCamera;
    private SurfaceTexture mSurfaceTexture;

    private int mProgram;

    private int mCameraTransformHandle;
    private int mTexSamplerHandle;
    private int mTexCoordHandle;
    private int mPosCoordHandle;

    private float[] mCameraTransform = new float[16];

    private int mCameraId = 0;
    private int mWidth;
    private int mHeight;

    private long mStartCaptureTime = 0;

    private boolean mNewFrameAvailable = false;
    private boolean mIsOpen = false;

    private CameraFrameListener mListener;

    public synchronized void beginCapture(int cameraId, int width, int height,
                                          SurfaceTexture st) {
        mCameraId = cameraId;
        mSurfaceTexture = st;

        // Open the camera
        openCamera(width, height);

        // Start the camera
        mStartCaptureTime = SystemClock.elapsedRealtime();
        mCamera.startPreview();
        mIsOpen = true;
    }

    public void getCurrentFrame() {
        if (checkNewFrame()) {
            if (mStartCaptureTime > 0 && SystemClock.elapsedRealtime() - mStartCaptureTime > 2000) {
                // Lock white-balance and exposure for effects
                Log.i("CC", "Locking white-balance and exposure!");
                Camera.Parameters params = mCamera.getParameters();
                params.setAutoWhiteBalanceLock(true);
                params.setAutoExposureLock(true);
                //mCamera.setParameters(params);
                mStartCaptureTime = 0;
            }

            mSurfaceTexture.updateTexImage();
            mSurfaceTexture.getTransformMatrix(mCameraTransform);

            // display it here
        }
    }

    public synchronized boolean hasNewFrame() {
        return mNewFrameAvailable;
    }

    public synchronized void endCapture() {
        mIsOpen = false;
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
            mSurfaceTexture = null;
        }
    }

    public synchronized boolean isOpen() {
        return mIsOpen;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public void setCameraFrameListener(CameraFrameListener listener) {
        mListener = listener;
    }

    private void openCamera(int width, int height) {
        // Setup camera
        mCamera = Camera.open(mCameraId);
        mCamera.setParameters(calcCameraParameters(width, height));

        // Create camera surface texture
        try {
            mCamera.setPreviewTexture(mSurfaceTexture);
        } catch (IOException e) {
            throw new RuntimeException("Could not bind camera surface texture: " +
                                       e.getMessage() + "!");
        }

        // Connect SurfaceTexture to callback
        mSurfaceTexture.setOnFrameAvailableListener(onCameraFrameAvailableListener);
    }

    private Camera.Parameters calcCameraParameters(int width, int height) {
        Camera.Parameters params = mCamera.getParameters();
        params.setPreviewSize(mWidth, mHeight);

        // Find closest size
        int closestSize[] = findClosestSize(width, height, params);
        mWidth = closestSize[0];
        mHeight = closestSize[1];
        params.setPreviewSize(mWidth, mHeight);

        // Find closest FPS
        int closestRange[] = findClosestFpsRange(FRAMES_PER_SEC, params);

        params.setPreviewFpsRange(closestRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                                  closestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);

        return params;
    }

    private int[] findClosestSize(int width, int height, Camera.Parameters parameters) {
        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        int closestWidth = -1;
        int closestHeight = -1;
        int smallestWidth = previewSizes.get(0).width;
        int smallestHeight =  previewSizes.get(0).height;
        for (Camera.Size size : previewSizes) {
            // Best match defined as not being larger in either dimension than
            // the requested size, but as close as possible. The below isn't a
            // stable selection (reording the size list can give different
            // results), but since this is a fallback nicety, that's acceptable.
            if ( size.width <= width &&
                 size.height <= height &&
                 size.width >= closestWidth &&
                 size.height >= closestHeight) {
                closestWidth = size.width;
                closestHeight = size.height;
            }
            if ( size.width < smallestWidth &&
                 size.height < smallestHeight) {
                smallestWidth = size.width;
                smallestHeight = size.height;
            }
        }
        if (closestWidth == -1) {
            // Requested size is smaller than any listed size; match with smallest possible
            closestWidth = smallestWidth;
            closestHeight = smallestHeight;
        }
        int[] closestSize = {closestWidth, closestHeight};
        return closestSize;
    }

    private int[] findClosestFpsRange(int fps, Camera.Parameters params) {
        List<int[]> supportedFpsRanges = params.getSupportedPreviewFpsRange();
        int[] closestRange = supportedFpsRanges.get(0);
        int fpsk = fps * 1000;
        int minDiff = 1000000;
        for (int[] range : supportedFpsRanges) {
            int low = range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int high = range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            if (low <= fpsk && high >= fpsk) {
                int diff = (fpsk - low) + (high - fpsk);
                if (diff < minDiff) {
                    closestRange = range;
                    minDiff = diff;
                }
            }
        }
        Log.i("CC", "Found closest range: "
            + closestRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX] + " - "
            + closestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
        return closestRange;
    }

    private synchronized void signalNewFrame() {
        mNewFrameAvailable = true;
        if (mListener != null) {
            mListener.onNewCameraFrame();
        }
    }

    private synchronized boolean checkNewFrame() {
        if (mNewFrameAvailable) {
            mNewFrameAvailable = false;
            return true;
        }
        return false;
    }

    private SurfaceTexture.OnFrameAvailableListener onCameraFrameAvailableListener =
            new SurfaceTexture.OnFrameAvailableListener() {
        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            signalNewFrame();
        }
    };
}
