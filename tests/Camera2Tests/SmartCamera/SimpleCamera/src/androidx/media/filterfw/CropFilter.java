/*
 * Copyright (C) 2011 The Android Open Source Project
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

package androidx.media.filterpacks.transform;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.ImageShader;
import androidx.media.filterfw.InputPort;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;
import androidx.media.filterfw.geometry.Quad;

public class CropFilter extends Filter {

    private Quad mCropRect = Quad.fromRect(0f, 0f, 1f, 1f);
    private int mOutputWidth = 0;
    private int mOutputHeight = 0;
    private ImageShader mShader;
    private boolean mUseMipmaps = false;
    private FrameImage2D mPow2Frame = null;

    public CropFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        FrameType imageOut = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.WRITE_GPU);
        return new Signature()
            .addInputPort("image", Signature.PORT_REQUIRED, imageIn)
            .addInputPort("cropRect", Signature.PORT_REQUIRED, FrameType.single(Quad.class))
            .addInputPort("outputWidth", Signature.PORT_OPTIONAL, FrameType.single(int.class))
            .addInputPort("outputHeight", Signature.PORT_OPTIONAL, FrameType.single(int.class))
            .addInputPort("useMipmaps", Signature.PORT_OPTIONAL, FrameType.single(boolean.class))
            .addOutputPort("image", Signature.PORT_REQUIRED, imageOut)
            .disallowOtherPorts();
    }

    @Override
    public void onInputPortOpen(InputPort port) {
        if (port.getName().equals("cropRect")) {
            port.bindToFieldNamed("mCropRect");
            port.setAutoPullEnabled(true);
        } else if (port.getName().equals("outputWidth")) {
            port.bindToFieldNamed("mOutputWidth");
            port.setAutoPullEnabled(true);
        } else if (port.getName().equals("outputHeight")) {
            port.bindToFieldNamed("mOutputHeight");
            port.setAutoPullEnabled(true);
        } else  if (port.getName().equals("useMipmaps")) {
            port.bindToFieldNamed("mUseMipmaps");
            port.setAutoPullEnabled(true);
        }
    }

    @Override
    protected void onPrepare() {
        if (isOpenGLSupported()) {
            mShader = ImageShader.createIdentity();
        }
    }

    @Override
    protected void onProcess() {
        OutputPort outPort = getConnectedOutputPort("image");

        // Pull input frame
        FrameImage2D inputImage = getConnectedInputPort("image").pullFrame().asFrameImage2D();
        int[] inDims = inputImage.getDimensions();
        int[] croppedDims = { (int)Math.ceil(mCropRect.xEdge().length() * inDims[0]),
                              (int)Math.ceil(mCropRect.yEdge().length() * inDims[1]) };
        int[] outDims = { getOutputWidth(croppedDims[0], croppedDims[1]),
                getOutputHeight(croppedDims[0], croppedDims[1]) };
        FrameImage2D outputImage = outPort.fetchAvailableFrame(outDims).asFrameImage2D();

        if (isOpenGLSupported()) {
            FrameImage2D sourceFrame;
            Quad sourceQuad = null;
            boolean scaleDown = (outDims[0] < croppedDims[0]) || (outDims[1] < croppedDims[1]);
            if (scaleDown && mUseMipmaps) {
                mPow2Frame = TransformUtils.makeMipMappedFrame(mPow2Frame, croppedDims);
                int[] extDims = mPow2Frame.getDimensions();
                float targetWidth = croppedDims[0] / (float)extDims[0];
                float targetHeight = croppedDims[1] / (float)extDims[1];
                Quad targetQuad = Quad.fromRect(0f, 0f, targetWidth, targetHeight);
                mShader.setSourceQuad(mCropRect);
                mShader.setTargetQuad(targetQuad);
                mShader.process(inputImage, mPow2Frame);
                TransformUtils.generateMipMaps(mPow2Frame);
                sourceFrame = mPow2Frame;
                sourceQuad = targetQuad;
            } else {
                sourceFrame = inputImage;
                sourceQuad = mCropRect;
            }

            mShader.setSourceQuad(sourceQuad);
            mShader.setTargetRect(0f, 0f, 1f, 1f);
            mShader.process(sourceFrame, outputImage);
        } else {
            // Convert quads to canvas coordinate space
            Quad sourceQuad = mCropRect.scale2(inDims[0], inDims[1]);
            Quad targetQuad = Quad.fromRect(0f, 0f, inDims[0], inDims[1]);

            // Calculate transform for crop
            Matrix transform = Quad.getTransform(sourceQuad, targetQuad);
            transform.postScale(outDims[0] / (float)inDims[0], outDims[1] / (float)inDims[1]);

            // Create target canvas
            Bitmap.Config config = Bitmap.Config.ARGB_8888;
            Bitmap cropped = Bitmap.createBitmap(outDims[0], outDims[1], config);
            Canvas canvas = new Canvas(cropped);

            // Draw source bitmap into target canvas
            Paint paint = new Paint();
            paint.setFilterBitmap(true);
            Bitmap sourceBitmap = inputImage.toBitmap();
            canvas.drawBitmap(sourceBitmap, transform, paint);

            // Assign bitmap to output frame
            outputImage.setBitmap(cropped);
        }

        outPort.pushFrame(outputImage);
    }

    @Override
    protected void onClose() {
        if (mPow2Frame != null){
            mPow2Frame.release();
            mPow2Frame = null;
        }
    }

    protected int getOutputWidth(int inWidth, int inHeight) {
        return mOutputWidth <= 0 ? inWidth : mOutputWidth;
    }

    protected int getOutputHeight(int inWidth, int inHeight) {
        return mOutputHeight <= 0 ? inHeight : mOutputHeight;
    }
}
