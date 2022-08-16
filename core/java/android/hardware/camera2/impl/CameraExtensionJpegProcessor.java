/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.hardware.camera2.impl.CameraExtensionUtils.JPEG_DEFAULT_QUALITY;
import static android.hardware.camera2.impl.CameraExtensionUtils.JPEG_DEFAULT_ROTATION;

import android.annotation.NonNull;
import android.graphics.ImageFormat;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.extension.CaptureBundle;
import android.hardware.camera2.extension.ICaptureProcessorImpl;
import android.hardware.camera2.extension.IProcessResultImpl;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

// Jpeg compress input YUV and queue back in the client target surface.
public class CameraExtensionJpegProcessor implements ICaptureProcessorImpl {
    public final static String TAG = "CameraExtensionJpeg";
    private final static int JPEG_QUEUE_SIZE = 1;

    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private final ICaptureProcessorImpl mProcessor;

    private ImageReader mYuvReader = null;
    private android.hardware.camera2.extension.Size mResolution = null;
    private int mFormat = -1;
    private Surface mOutputSurface = null;
    private ImageWriter mOutputWriter = null;

    private static final class JpegParameters {
        public HashSet<Long> mTimeStamps = new HashSet<>();
        public int mRotation = JPEG_DEFAULT_ROTATION; // CW multiple of 90 degrees
        public int mQuality = JPEG_DEFAULT_QUALITY; // [0..100]
    }

    private ConcurrentLinkedQueue<JpegParameters> mJpegParameters = new ConcurrentLinkedQueue<>();

    public CameraExtensionJpegProcessor(@NonNull ICaptureProcessorImpl processor) {
        mProcessor = processor;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    public void close() {
        mHandlerThread.quitSafely();

        if (mOutputWriter != null) {
            mOutputWriter.close();
            mOutputWriter = null;
        }

        if (mYuvReader != null) {
            mYuvReader.close();
            mYuvReader = null;
        }
    }

    private static JpegParameters getJpegParameters(List<CaptureBundle> captureBundles) {
        JpegParameters ret = new JpegParameters();
        if (!captureBundles.isEmpty()) {
            // The quality and orientation settings must be equal for requests in a burst

            Byte jpegQuality = captureBundles.get(0).captureResult.get(CaptureResult.JPEG_QUALITY);
            if (jpegQuality != null) {
                ret.mQuality = jpegQuality;
            } else {
                Log.w(TAG, "No jpeg quality set, using default: " + JPEG_DEFAULT_QUALITY);
            }

            Integer orientation = captureBundles.get(0).captureResult.get(
                    CaptureResult.JPEG_ORIENTATION);
            if (orientation != null) {
                // The jpeg encoder expects CCW rotation, convert from CW
                ret.mRotation = (360 - (orientation % 360)) / 90;
            } else {
                Log.w(TAG, "No jpeg rotation set, using default: " + JPEG_DEFAULT_ROTATION);
            }

            for (CaptureBundle bundle : captureBundles) {
                Long timeStamp = bundle.captureResult.get(CaptureResult.SENSOR_TIMESTAMP);
                if (timeStamp != null) {
                    ret.mTimeStamps.add(timeStamp);
                } else {
                    Log.e(TAG, "Capture bundle without valid sensor timestamp!");
                }
            }
        }

        return ret;
    }

    /**
     * Compresses a YCbCr image to jpeg, applying a crop and rotation.
     * <p>
     * The input is defined as a set of 3 planes of 8-bit samples, one plane for
     * each channel of Y, Cb, Cr.<br>
     * The Y plane is assumed to have the same width and height of the entire
     * image.<br>
     * The Cb and Cr planes are assumed to be downsampled by a factor of 2, to
     * have dimensions (floor(width / 2), floor(height / 2)).<br>
     * Each plane is specified by a direct java.nio.ByteBuffer, a pixel-stride,
     * and a row-stride. So, the sample at coordinate (x, y) can be retrieved
     * from byteBuffer[x * pixel_stride + y * row_stride].
     * <p>
     * The pre-compression transformation is applied as follows:
     * <ol>
     * <li>The image is cropped to the rectangle from (cropLeft, cropTop) to
     * (cropRight - 1, cropBottom - 1). So, a cropping-rectangle of (0, 0) -
     * (width, height) is a no-op.</li>
     * <li>The rotation is applied counter-clockwise relative to the coordinate
     * space of the image, so a CCW rotation will appear CW when the image is
     * rendered in scanline order. Only rotations which are multiples of
     * 90-degrees are suppored, so the parameter 'rot90' specifies which
     * multiple of 90 to rotate the image.</li>
     * </ol>
     *
     * @param width          the width of the image to compress
     * @param height         the height of the image to compress
     * @param yBuf           the buffer containing the Y component of the image
     * @param yPStride       the stride between adjacent pixels in the same row in
     *                       yBuf
     * @param yRStride       the stride between adjacent rows in yBuf
     * @param cbBuf          the buffer containing the Cb component of the image
     * @param cbPStride      the stride between adjacent pixels in the same row in
     *                       cbBuf
     * @param cbRStride      the stride between adjacent rows in cbBuf
     * @param crBuf          the buffer containing the Cr component of the image
     * @param crPStride      the stride between adjacent pixels in the same row in
     *                       crBuf
     * @param crRStride      the stride between adjacent rows in crBuf
     * @param outBuf         a direct java.nio.ByteBuffer to hold the compressed jpeg.
     *                       This must have enough capacity to store the result, or an
     *                       error code will be returned.
     * @param outBufCapacity the capacity of outBuf
     * @param quality        the jpeg-quality (1-100) to use
     * @param cropLeft       left-edge of the bounds of the image to crop to before
     *                       rotation
     * @param cropTop        top-edge of the bounds of the image to crop to before
     *                       rotation
     * @param cropRight      right-edge of the bounds of the image to crop to before
     *                       rotation
     * @param cropBottom     bottom-edge of the bounds of the image to crop to
     *                       before rotation
     * @param rot90          the multiple of 90 to rotate the image CCW (after cropping)
     */
    private static native int compressJpegFromYUV420pNative(
            int width, int height,
            ByteBuffer yBuf, int yPStride, int yRStride,
            ByteBuffer cbBuf, int cbPStride, int cbRStride,
            ByteBuffer crBuf, int crPStride, int crRStride,
            ByteBuffer outBuf, int outBufCapacity,
            int quality,
            int cropLeft, int cropTop, int cropRight, int cropBottom,
            int rot90);

    @Override
    public void process(List<CaptureBundle> captureBundle, IProcessResultImpl captureCallback)
            throws RemoteException {
        JpegParameters jpegParams = getJpegParameters(captureBundle);
        try {
            mJpegParameters.add(jpegParams);
            mProcessor.process(captureBundle, captureCallback);
        } catch (Exception e) {
            mJpegParameters.remove(jpegParams);
            throw e;
        }
    }

    public void onOutputSurface(Surface surface, int format) throws RemoteException {
        if (format != ImageFormat.JPEG) {
            Log.e(TAG, "Unsupported output format: " + format);
            return;
        }
        mOutputSurface = surface;
        initializePipeline();
    }

    @Override
    public void onResolutionUpdate(android.hardware.camera2.extension.Size size)
            throws RemoteException {
        mResolution = size;
        initializePipeline();
    }

    public void onImageFormatUpdate(int format) throws RemoteException {
        if (format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unsupported input format: " + format);
            return;
        }
        mFormat = format;
        initializePipeline();
    }

    private void initializePipeline() throws RemoteException {
        if ((mFormat != -1) && (mOutputSurface != null) && (mResolution != null) &&
                (mYuvReader == null)) {
            // Jpeg/blobs are expected to be configured with (w*h)x1
            mOutputWriter = ImageWriter.newInstance(mOutputSurface, 1 /*maxImages*/,
                    ImageFormat.JPEG, mResolution.width * mResolution.height, 1);
            mYuvReader = ImageReader.newInstance(mResolution.width, mResolution.height, mFormat,
                    JPEG_QUEUE_SIZE);
            mYuvReader.setOnImageAvailableListener(new YuvCallback(), mHandler);
            mProcessor.onOutputSurface(mYuvReader.getSurface(), mFormat);
            mProcessor.onResolutionUpdate(mResolution);
            mProcessor.onImageFormatUpdate(mFormat);
        }
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException("Binder IPC not supported!");
    }

    private class YuvCallback implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image yuvImage = null;
            Image jpegImage = null;
            try {
                yuvImage = mYuvReader.acquireNextImage();
                jpegImage = mOutputWriter.dequeueInputImage();
            } catch (IllegalStateException e) {
                if (yuvImage != null) {
                    yuvImage.close();
                }
                if (jpegImage != null) {
                    jpegImage.close();
                }
                Log.e(TAG, "Failed to acquire processed yuv image or jpeg image!");
                return;
            }

            ByteBuffer jpegBuffer = jpegImage.getPlanes()[0].getBuffer();
            jpegBuffer.clear();
            // Jpeg/blobs are expected to be configured with (w*h)x1
            int jpegCapacity = jpegImage.getWidth();

            Plane lumaPlane = yuvImage.getPlanes()[0];
            Plane crPlane = yuvImage.getPlanes()[1];
            Plane cbPlane = yuvImage.getPlanes()[2];

            Iterator<JpegParameters> jpegIter = mJpegParameters.iterator();
            JpegParameters jpegParams = null;
            while(jpegIter.hasNext()) {
                JpegParameters currentParams = jpegIter.next();
                if (currentParams.mTimeStamps.contains(yuvImage.getTimestamp())) {
                    jpegParams = currentParams;
                    jpegIter.remove();
                    break;
                }
            }
            if (jpegParams == null) {
                if (mJpegParameters.isEmpty()) {
                    Log.w(TAG, "Empty jpeg settings queue! Using default jpeg orientation"
                            + " and quality!");
                    jpegParams = new JpegParameters();
                    jpegParams.mRotation = JPEG_DEFAULT_ROTATION;
                    jpegParams.mQuality = JPEG_DEFAULT_QUALITY;
                } else {
                    Log.w(TAG, "No jpeg settings found with matching timestamp for current"
                            + " processed input!");
                    Log.w(TAG, "Using values from the top of the queue!");
                    jpegParams = mJpegParameters.poll();
                }
            }

            compressJpegFromYUV420pNative(
                    yuvImage.getWidth(), yuvImage.getHeight(),
                    lumaPlane.getBuffer(), lumaPlane.getPixelStride(), lumaPlane.getRowStride(),
                    crPlane.getBuffer(), crPlane.getPixelStride(), crPlane.getRowStride(),
                    cbPlane.getBuffer(), cbPlane.getPixelStride(), cbPlane.getRowStride(),
                    jpegBuffer, jpegCapacity, jpegParams.mQuality,
                    0, 0, yuvImage.getWidth(), yuvImage.getHeight(),
                    jpegParams.mRotation);
            jpegImage.setTimestamp(yuvImage.getTimestamp());
            yuvImage.close();

            try {
                mOutputWriter.queueInputImage(jpegImage);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to queue encoded result!");
            } finally {
                jpegImage.close();
            }
        }
    }
}
