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
import android.util.Log;

/**
 * @hide
 */
public class CropRectFilter extends Filter {

    @GenerateFieldPort(name = "xorigin")
    private int mXorigin;

    @GenerateFieldPort(name = "yorigin")
    private int mYorigin;

    @GenerateFieldPort(name = "width")
    private int mOutputWidth;

    @GenerateFieldPort(name = "height")
    private int mOutputHeight;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;

    private int mWidth = 0;
    private int mHeight = 0;

    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    public CropRectFilter(String name) {
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
            updateSourceRect(mWidth, mHeight);
        }
    }

    @Override
    public void process(FilterContext context) {
        // Get input frame
        Frame input = pullInput("image");
        FrameFormat inputFormat = input.getFormat();

        // Create output frame
        FrameFormat outputFormat = ImageFormat.create(mOutputWidth, mOutputHeight,
                                                      ImageFormat.COLORSPACE_RGBA,
                                                      FrameFormat.TARGET_GPU);
        Frame output = context.getFrameManager().newFrame(outputFormat);

        // Create program if not created already
        if (mProgram == null || inputFormat.getTarget() != mTarget) {
            initProgram(context, inputFormat.getTarget());
        }

        // Check if the frame size has changed
        if (inputFormat.getWidth() != mWidth || inputFormat.getHeight() != mHeight) {
            updateSourceRect(inputFormat.getWidth(), inputFormat.getHeight());
        }

        // Process
        mProgram.process(input, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }

    void updateSourceRect(int width, int height) {
        mWidth = width;
        mHeight = height;

        /*
        Log.e("CropFilter", mWidth + ", " + mHeight + ", " +
                            (float) mXorigin / mWidth + ", " +
                            (float) mYorigin / mHeight + ", " +
                            (float) mOutputWidth / mWidth + ", " +
                            (float) mOutputHeight / mHeight);
        */

        ((ShaderProgram) mProgram).setSourceRect((float) mXorigin / mWidth,
                                                 (float) mYorigin / mHeight,
                                                 (float) mOutputWidth / mWidth,
                                                 (float) mOutputHeight / mHeight);
    }
}
