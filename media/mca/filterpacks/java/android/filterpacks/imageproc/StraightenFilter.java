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
import android.filterfw.core.KeyValueMap;
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.NativeProgram;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;
import android.filterfw.geometry.Quad;
import android.filterfw.geometry.Point;
import android.util.Log;

/**
 * @hide
 */
public class StraightenFilter extends Filter {

    @GenerateFieldPort(name = "angle", hasDefault = true)
    private float mAngle = 0f;

    @GenerateFieldPort(name = "maxAngle", hasDefault = true)
    private float mMaxAngle = 45f;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;

    private int mWidth = 0;
    private int mHeight = 0;
    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private static final float DEGREE_TO_RADIAN = (float) Math.PI / 180.0f;

    public StraightenFilter(String name) {
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

        // Create output frame
        if (inputFormat.getWidth() != mWidth || inputFormat.getHeight() != mHeight) {
            mWidth = inputFormat.getWidth();
            mHeight = inputFormat.getHeight();
            updateParameters();
        }

        Frame output = context.getFrameManager().newFrame(inputFormat);

        // Process
        mProgram.process(input, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }

    private void updateParameters() {
        float cosTheta = (float) Math.cos(mAngle * DEGREE_TO_RADIAN);
        float sinTheta = (float) Math.sin(mAngle * DEGREE_TO_RADIAN);

        if (mMaxAngle <= 0)
            throw new RuntimeException("Max angle is out of range (0-180).");
        mMaxAngle = (mMaxAngle > 90) ? 90 : mMaxAngle;

        Point p0 = new Point(-cosTheta * mWidth + sinTheta * mHeight,
                             -sinTheta * mWidth - cosTheta * mHeight);

        Point p1 = new Point(cosTheta * mWidth + sinTheta * mHeight,
                             sinTheta * mWidth - cosTheta * mHeight);

        Point p2 = new Point(-cosTheta * mWidth - sinTheta * mHeight,
                             -sinTheta * mWidth + cosTheta * mHeight);

        Point p3 = new Point(cosTheta * mWidth - sinTheta * mHeight,
                             sinTheta * mWidth + cosTheta * mHeight);

        float maxWidth = (float) Math.max(Math.abs(p0.x), Math.abs(p1.x));
        float maxHeight = (float) Math.max(Math.abs(p0.y), Math.abs(p1.y));

        float scale = 0.5f * Math.min( mWidth / maxWidth,
                                       mHeight / maxHeight);

        p0.set(scale * p0.x / mWidth + 0.5f, scale * p0.y / mHeight + 0.5f);
        p1.set(scale * p1.x / mWidth + 0.5f, scale * p1.y / mHeight + 0.5f);
        p2.set(scale * p2.x / mWidth + 0.5f, scale * p2.y / mHeight + 0.5f);
        p3.set(scale * p3.x / mWidth + 0.5f, scale * p3.y / mHeight + 0.5f);

        Quad quad = new Quad(p0, p1, p2, p3);
        ((ShaderProgram) mProgram).setSourceRegion(quad);
    }
}
