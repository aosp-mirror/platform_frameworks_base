/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.hardware.camera2.impl;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.view.Surface;

import java.util.concurrent.Executor;

/**
 * <p>An internal callback for tracking the progress of a {@link CaptureRequest}
 * submitted to the camera device.</p>
 */
public abstract class CaptureCallback {

    private Executor mExecutor;
    private CameraCaptureSession.CaptureCallback mCallback;

    public CaptureCallback(Executor executor, CameraCaptureSession.CaptureCallback callback) {
        mExecutor = executor;
        mCallback = callback;
    }

    /**
     * Retrieve callback executor
     *
     */
    public Executor getExecutor() {
        return mExecutor;
    }

    /**
     * Retrieve capture callback
     *
     */
    public CameraCaptureSession.CaptureCallback getSessionCallback() {
        return mCallback;
    }

    /**
     * This method is called when the camera device has started capturing
     * the output image for the request, at the beginning of image exposure.
     *
     * @see android.media.MediaActionSound
     */
    public abstract void onCaptureStarted(CameraDevice camera,
            CaptureRequest request, long timestamp, long frameNumber);

    /**
     * This method is called when some results from an image capture are
     * available.
     *
     * @hide
     */
    public abstract void onCapturePartial(CameraDevice camera,
            CaptureRequest request, CaptureResult result);

    /**
     * This method is called when an image capture makes partial forward progress; some
     * (but not all) results from an image capture are available.
     *
     */
    public abstract void onCaptureProgressed(CameraDevice camera,
            CaptureRequest request, CaptureResult partialResult);

    /**
     * This method is called when an image capture has fully completed and all the
     * result metadata is available.
     */
    public abstract void onCaptureCompleted(CameraDevice camera,
            CaptureRequest request, TotalCaptureResult result);

    /**
     * This method is called instead of {@link #onCaptureCompleted} when the
     * camera device failed to produce a {@link CaptureResult} for the
     * request.
     */
    public abstract void onCaptureFailed(CameraDevice camera,
            CaptureRequest request, CaptureFailure failure);

    /**
     * This method is called independently of the others in CaptureCallback,
     * when a capture sequence finishes and all {@link CaptureResult}
     * or {@link CaptureFailure} for it have been returned via this callback.
     */
    public abstract void onCaptureSequenceCompleted(CameraDevice camera,
            int sequenceId, long frameNumber);

    /**
     * This method is called independently of the others in CaptureCallback,
     * when a capture sequence aborts before any {@link CaptureResult}
     * or {@link CaptureFailure} for it have been returned via this callback.
     */
    public abstract void onCaptureSequenceAborted(CameraDevice camera,
            int sequenceId);

    /**
     * This method is called independently of the others in CaptureCallback, if an output buffer
     * is dropped for a particular capture request.
     *
     * Loss of metadata is communicated via onCaptureFailed, independently of any buffer loss.
     */
    public abstract void onCaptureBufferLost(CameraDevice camera,
            CaptureRequest request, Surface target, long frameNumber);
}
