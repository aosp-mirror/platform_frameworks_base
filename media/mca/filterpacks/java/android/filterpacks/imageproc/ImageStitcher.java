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

package android.filterpacks.imageproc;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;

public class ImageStitcher extends Filter {

    @GenerateFieldPort(name = "xSlices")
    private int mXSlices;

    @GenerateFieldPort(name = "ySlices")
    private int mYSlices;

    @GenerateFieldPort(name = "padSize")
    private int mPadSize;

    private Program mProgram;
    private Frame mOutputFrame;

    private int mInputWidth;
    private int mInputHeight;

    private int mImageWidth;
    private int mImageHeight;

    private int mSliceWidth;
    private int mSliceHeight;

    private int mSliceIndex;

    public ImageStitcher(String name) {
        super(name);
        mSliceIndex = 0;
    }

    @Override
    public void setupPorts() {
        addMaskedInputPort("image", ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                      FrameFormat.TARGET_GPU));
        addOutputBasedOnInput("image", "image");
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return inputFormat;
    }

    private FrameFormat calcOutputFormatForInput(FrameFormat format) {
        MutableFrameFormat outputFormat = format.mutableCopy();

        mInputWidth = format.getWidth();
        mInputHeight = format.getHeight();

        mSliceWidth = mInputWidth - 2 * mPadSize;
        mSliceHeight = mInputHeight - 2 * mPadSize;

        mImageWidth =  mSliceWidth * mXSlices;
        mImageHeight = mSliceHeight * mYSlices;

        outputFormat.setDimensions(mImageWidth, mImageHeight);
        return outputFormat;
    }

    @Override
    public void process(FilterContext context) {
        // Get input frame
        Frame input = pullInput("image");
        FrameFormat format = input.getFormat();

        // Create output frame
        if (mSliceIndex == 0) {
            mOutputFrame = context.getFrameManager().newFrame(calcOutputFormatForInput(format));
        } else {
            if ((format.getWidth() != mInputWidth) ||
                (format.getHeight() != mInputHeight)) {
                // CHECK input format here
                throw new RuntimeException("Image size should not change.");
            }
        }

        // Create the program if not created already
        if (mProgram == null) {
            mProgram = ShaderProgram.createIdentity(context);
        }

        // TODO(rslin) : not sure shifting by 0.5 is needed.
        float x0 = ((float) mPadSize) / mInputWidth;
        float y0 = ((float) mPadSize) / mInputHeight;

        int outputOffsetX = (mSliceIndex % mXSlices) * mSliceWidth;
        int outputOffsetY = (mSliceIndex / mXSlices) * mSliceHeight;

        float outputWidth = (float) Math.min(mSliceWidth, mImageWidth - outputOffsetX);
        float outputHeight = (float) Math.min(mSliceHeight, mImageHeight - outputOffsetY);

        // We need to set the source rect as well because the input are padded images.
        ((ShaderProgram) mProgram).setSourceRect(x0, y0,
                                                 outputWidth / mInputWidth,
                                                 outputHeight / mInputHeight);

        ((ShaderProgram) mProgram).setTargetRect(((float) outputOffsetX)/ mImageWidth,
                                                 ((float) outputOffsetY) / mImageHeight,
                                                 outputWidth / mImageWidth,
                                                 outputHeight / mImageHeight);

        // Process this tile
        mProgram.process(input, mOutputFrame);
        mSliceIndex++;

        // Push output
        if (mSliceIndex == mXSlices * mYSlices) {
            pushOutput("image", mOutputFrame);
            mOutputFrame.release();
            mSliceIndex = 0;
        }
    }
}
