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
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;
import android.filterfw.geometry.Quad;
import android.filterfw.geometry.Point;

/**
 * @hide
 */
public class RotateFilter extends Filter {

    @GenerateFieldPort(name = "angle")
    private int mAngle;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;

    private int mWidth = 0;
    private int mHeight = 0;
    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private int mOutputWidth;
    private int mOutputHeight;

    public RotateFilter(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addMaskedInputPort("image", ImageFormat.create(ImageFormat.COLORSPACE_RGBA));
        addOutputBasedOnInput("image", "image");
    }

    public void initProgram(FilterContext context, int target) {
        switch (target) {
            case FrameFormat.TARGET_GPU:
                ShaderProgram shaderProgram = ShaderProgram.createIdentity(context);
                shaderProgram.setMaximumTileSize(mTileSize);
                shaderProgram.setClearsOutput(true);
                mProgram = shaderProgram;
                break;

            default:
                throw new RuntimeException("Filter Sharpen does not support frames of " +
                    "target " + target + "!");
        }
        mTarget = target;
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        if (mProgram != null) {
            updateParameters();
        }
    }

    @Override
    public void process(FilterContext context) {
        // Get input frame
        Frame input = pullInput("image");
        FrameFormat inputFormat = input.getFormat();

        // Create program if not created already
        if (mProgram == null || inputFormat.getTarget() != mTarget) {
            initProgram(context, inputFormat.getTarget());
        }

        if (inputFormat.getWidth() != mWidth || inputFormat.getHeight() != mHeight) {
            mWidth = inputFormat.getWidth();
            mHeight = inputFormat.getHeight();
            mOutputWidth = mWidth;
            mOutputHeight = mHeight;

            updateParameters();
        }

        // Create output frame
        FrameFormat outputFormat = ImageFormat.create(mOutputWidth, mOutputHeight,
                                                      ImageFormat.COLORSPACE_RGBA,
                                                      FrameFormat.TARGET_GPU);

        Frame output = context.getFrameManager().newFrame(outputFormat);

        // Process
        mProgram.process(input, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }

    private void updateParameters() {
        float sinTheta;
        float cosTheta;

        if (mAngle % 90 == 0) {
            if (mAngle % 180 == 0) {
                sinTheta = 0f;
                cosTheta = (mAngle % 360 == 0) ? 1f:-1f;
            } else {
                cosTheta = 0f;
                sinTheta = ((mAngle + 90) % 360 == 0) ? -1f:1f;

                mOutputWidth = mHeight;
                mOutputHeight = mWidth;
            }
        } else {
            throw new RuntimeException("degree has to be multiply of 90.");
        }

        Point x0 = new Point(0.5f * (-cosTheta + sinTheta + 1f),
                             0.5f * (-sinTheta - cosTheta + 1f));
        Point x1 = new Point(0.5f * (cosTheta + sinTheta + 1f),
                             0.5f * (sinTheta - cosTheta + 1f));
        Point x2 = new Point(0.5f * (-cosTheta - sinTheta + 1f),
                             0.5f * (-sinTheta + cosTheta + 1f));
        Point x3 = new Point(0.5f * (cosTheta - sinTheta + 1f),
                             0.5f * (sinTheta + cosTheta + 1f));
        Quad quad = new Quad(x0, x1, x2, x3);
        ((ShaderProgram) mProgram).setTargetRegion(quad);
    }
}
