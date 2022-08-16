/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.extension.IPreviewImageProcessorImpl;
import android.hardware.camera2.extension.IProcessResultImpl;
import android.hardware.camera2.extension.ParcelImage;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.annotation.NonNull;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

// Wrap a given 'PreviewImageProcessorImpl' so that the processed output can
// be redirected to a given surface or looped back in the internal intermediate surface.
public class CameraExtensionForwardProcessor {
    public final static String TAG = "CameraExtensionForward";
    private final static int FORWARD_QUEUE_SIZE = 3;

    private final IPreviewImageProcessorImpl mProcessor;
    private final long mOutputSurfaceUsage;
    private final int mOutputSurfaceFormat;
    private final Handler mHandler;

    private ImageReader mIntermediateReader = null;
    private Surface mIntermediateSurface = null;
    private Size mResolution = null;
    private Surface mOutputSurface = null;
    private ImageWriter mOutputWriter = null;
    private boolean mOutputAbandoned = false;

    public CameraExtensionForwardProcessor(@NonNull IPreviewImageProcessorImpl processor,
            int format, long surfaceUsage, @NonNull Handler handler) {
        mProcessor = processor;
        mOutputSurfaceUsage = surfaceUsage;
        mOutputSurfaceFormat = format;
        mHandler = handler;
    }

    public void close() {
        if (mOutputWriter != null) {
            mOutputWriter.close();
            mOutputWriter = null;
        }

        if (mIntermediateReader != null) {
            mIntermediateReader.close();
            mIntermediateReader = null;
        }
    }

    public void onOutputSurface(Surface surface, int format) {
        mOutputSurface = surface;
        try {
            initializePipeline();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to initialize forward processor, extension service does not"
                    + " respond!");
        }
    }

    public void onResolutionUpdate(Size size) {
        mResolution = size;
    }

    public void onImageFormatUpdate(int format) {
        if (format != CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT) {
            Log.e(TAG, "Unsupported input format: " + format);
        }
    }

    @SuppressLint("WrongConstant")
    private void initializePipeline() throws RemoteException {
        if (mOutputWriter != null) {
            mOutputWriter.close();
            mOutputWriter = null;
        }

        if (mIntermediateReader == null) {
            mIntermediateReader = ImageReader.newInstance(mResolution.getWidth(),
                    mResolution.getHeight(), CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT,
                    FORWARD_QUEUE_SIZE, mOutputSurfaceUsage);
            mIntermediateSurface = mIntermediateReader.getSurface();
            mIntermediateReader.setOnImageAvailableListener(new ForwardCallback(), mHandler);

            mProcessor.onOutputSurface(mIntermediateSurface, mOutputSurfaceFormat);
            // PreviewImageProcessorImpl always expect the extension processing format as input
            mProcessor.onImageFormatUpdate(CameraExtensionCharacteristics.PROCESSING_INPUT_FORMAT);
            android.hardware.camera2.extension.Size sz =
                    new android.hardware.camera2.extension.Size();
            sz.width = mResolution.getWidth();
            sz.height = mResolution.getHeight();
            mProcessor.onResolutionUpdate(sz);
        }
    }

    public void process(ParcelImage image, TotalCaptureResult totalCaptureResult,
            IProcessResultImpl resultCallback) throws RemoteException {
        if ((mIntermediateSurface != null) && (mIntermediateSurface.isValid()) &&
                !mOutputAbandoned) {
            mProcessor.process(image, totalCaptureResult.getNativeMetadata(),
                    totalCaptureResult.getSequenceId(), resultCallback);
        }
    }

    private class ForwardCallback implements ImageReader.OnImageAvailableListener {
        @Override public void onImageAvailable(ImageReader reader) {
            Image processedImage = null;
            try {
                processedImage = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to acquire processed image!");
                return;
            }
            if (processedImage == null) {
                Log.e(TAG, "Invalid image");
                return;
            }

            if (mOutputSurface != null && mOutputSurface.isValid() && !mOutputAbandoned) {
                if (mOutputWriter == null) {
                    mOutputWriter = ImageWriter.newInstance(mOutputSurface, FORWARD_QUEUE_SIZE,
                            processedImage.getFormat());
                }
                try {
                    mOutputWriter.queueInputImage(processedImage);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Failed to queue processed buffer!");
                    processedImage.close();
                    mOutputAbandoned = true;
                }
            } else {
                processedImage.close();
            }
        }
    }
}
