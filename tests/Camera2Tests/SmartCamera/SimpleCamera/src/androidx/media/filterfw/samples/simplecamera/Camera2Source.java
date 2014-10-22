/*
 * Copyright 2013 The Android Open Source Project
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

package androidx.media.filterfw.samples.simplecamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.view.Surface;
import com.android.ex.camera2.blocking.BlockingCameraManager;
import com.android.ex.camera2.blocking.BlockingCameraManager.BlockingOpenException;
import com.android.ex.camera2.blocking.BlockingSessionCallback;
import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

import java.util.ArrayList;
import java.util.List;

public class Camera2Source extends Filter implements Allocation.OnBufferAvailableListener {

    private boolean mNewFrameAvailable = false;
    private FrameType mOutputType;
    private static final String TAG = "Camera2Source";
    private CameraManager mCameraManager;
    private CameraDevice mCamera;
    private CameraCaptureSession mCameraSession;
    private RenderScript mRS;
    private Surface mSurface;
    private CameraCharacteristics mProperties;
    private CameraTestThread mLooperThread;
    private int mHeight = 480;
    private int mWidth = 640;
    private Allocation mAllocationIn;
    private ScriptIntrinsicYuvToRGB rgbConverter;
    private Allocation mAllocationOut;
    private Bitmap mBitmap;

    private static final long SESSION_TIMEOUT_MS = 2000;

    class MyCameraListener extends CameraManager.AvailabilityCallback {

        @Override
        public void onCameraAvailable(String cameraId) {
            // TODO Auto-generated method stub
            Log.v(TAG, "camera available to open");
        }

        @Override
        public void onCameraUnavailable(String cameraId) {
            // TODO Auto-generated method stub
            Log.v(TAG, "camera unavailable to open");
        }

    }

    class MyCaptureCallback extends CameraCaptureSession.CaptureCallback {

        @Override
        public void onCaptureCompleted(CameraCaptureSession camera, CaptureRequest request,
                TotalCaptureResult result) {
            // TODO Auto-generated method stub
            Log.v(TAG, "in onCaptureComplete");

        }

        @Override
        public void onCaptureFailed(CameraCaptureSession camera, CaptureRequest request,
                CaptureFailure failure) {
            // TODO Auto-generated method stub
            Log.v(TAG, "onCaptureFailed is being called");
        }

    }

    public Camera2Source(MffContext context, String name) {
        super(context, name);
        mOutputType = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.WRITE_GPU);

        Context ctx = context.getApplicationContext();
        mCameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);

        mRS = RenderScript.create(context.getApplicationContext());
    }

    @Override
    public Signature getSignature() {
        return new Signature()
                .addOutputPort("timestamp", Signature.PORT_OPTIONAL, FrameType.single(long.class))
                .addOutputPort("video", Signature.PORT_REQUIRED, mOutputType)
                .addOutputPort("orientation", Signature.PORT_REQUIRED,
                        FrameType.single(float.class))
                .disallowOtherPorts();
    }

    @Override
    protected void onClose() {
        Log.v(TAG, "onClose being called");
        try {
            mCamera.close();
            mSurface.release();
            mLooperThread.close();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Override
    protected void onOpen() {
        mLooperThread = new CameraTestThread();
        Handler mHandler;
        try {
            mHandler = mLooperThread.start();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        try {
            String backCameraId = "0";
            BlockingCameraManager blkManager = new BlockingCameraManager(mCameraManager);
            mCamera = blkManager.openCamera(backCameraId, /*listener*/null, mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        } catch (BlockingOpenException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        Element ele = Element.createPixel(mRS, Element.DataType.UNSIGNED_8,
                Element.DataKind.PIXEL_YUV);

        rgbConverter = ScriptIntrinsicYuvToRGB.create(mRS,ele);
        Type.Builder yuvBuilder = new Type.Builder(mRS,ele);

        yuvBuilder.setYuvFormat(ImageFormat.YUV_420_888);
        yuvBuilder.setX(mWidth);
        yuvBuilder.setY(mHeight);
        mAllocationIn = Allocation.createTyped(mRS, yuvBuilder.create(),
                Allocation.USAGE_SCRIPT | Allocation.USAGE_IO_INPUT);
        mSurface = mAllocationIn.getSurface();
        mAllocationIn.setOnBufferAvailableListener(this);
        rgbConverter.setInput(mAllocationIn);

        mBitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
        mAllocationOut = Allocation.createFromBitmap(mRS, mBitmap);


        Log.v(TAG, "mcamera: " + mCamera);

        List<Surface> surfaces = new ArrayList<Surface>();
        surfaces.add(mSurface);
        CaptureRequest.Builder mCaptureRequest = null;
        try {
            BlockingSessionCallback blkSession = new BlockingSessionCallback();

            mCamera.createCaptureSession(surfaces, blkSession, mHandler);
            mCaptureRequest = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequest.addTarget(mSurface);

            mCameraSession = blkSession.waitAndGetSession(SESSION_TIMEOUT_MS);

        } catch (CameraAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        try {
            mCameraSession.setRepeatingRequest(mCaptureRequest.build(), new MyCaptureCallback(),
                    mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        mProperties = null;
        try {
            mProperties = mCameraManager.getCameraCharacteristics(mCamera.getId());
        } catch (CameraAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

    }

    @Override
    protected void onProcess() {
        Log.v(TAG, "on Process");
        if (nextFrame()) {
            OutputPort outPort = getConnectedOutputPort("video");

            // Create a 2D frame that will hold the output
            int[] dims = new int[] {
                    mWidth, mHeight
            };
            FrameImage2D outputFrame = Frame.create(mOutputType, dims).asFrameImage2D();
            rgbConverter.forEach(mAllocationOut);
            mAllocationOut.copyTo(mBitmap);
            outputFrame.setBitmap(mBitmap);
            outPort.pushFrame(outputFrame);
            outputFrame.release();

            OutputPort orientationPort = getConnectedOutputPort("orientation");
            FrameValue orientationFrame = orientationPort.fetchAvailableFrame(null).asFrameValue();

            // FIXME: Hardcoded value because ORIENTATION returns null, Qualcomm
            // bug
            Integer orientation = mProperties.get(CameraCharacteristics.SENSOR_ORIENTATION);
            float temp;
            if (orientation != null) {
                temp = orientation.floatValue();
            } else {
                temp = 90.0f;
            }
            orientationFrame.setValue(temp);
            orientationPort.pushFrame(orientationFrame);
        }
    }

    private synchronized boolean nextFrame() {
        boolean frameAvailable = mNewFrameAvailable;
        if (frameAvailable) {
            mNewFrameAvailable = false;
        } else {
            enterSleepState();
        }
        return frameAvailable;
    }

    public void onBufferAvailable(Allocation a) {
        Log.v(TAG, "on Buffer Available");
        a.ioReceive();
        synchronized (this) {
            mNewFrameAvailable = true;
        }
        wakeUp();
    }


}
