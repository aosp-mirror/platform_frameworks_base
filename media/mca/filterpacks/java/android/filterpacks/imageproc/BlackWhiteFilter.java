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
import android.filterfw.core.NativeProgram;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;

import java.util.Random;

public class BlackWhiteFilter extends Filter {

    @GenerateFieldPort(name = "black", hasDefault = true)
    private float mBlack = 0f;

    @GenerateFieldPort(name = "white", hasDefault = true)
    private float mWhite = 1f;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;

    private int mWidth = 0;
    private int mHeight = 0;
    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private Frame mNoiseFrame = null;
    private Random mRandom;

    private final String mBlackWhiteShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform float black;\n" +
            "uniform float scale;\n" +
            "uniform float stepsize;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  float dither = texture2D(tex_sampler_1, v_texcoord).r;\n" +
            "  vec3 xform = clamp((color.rgb - black) * scale, 0.0, 1.0);\n" +
            "  vec3 temp = clamp((color.rgb + stepsize - black) * scale, 0.0, 1.0);\n" +
            "  vec3 new_color = clamp(xform + (temp - xform) * (dither - 0.5), 0.0, 1.0);\n" +
            "  gl_FragColor = vec4(new_color, color.a);\n" +
            "}\n";

    public BlackWhiteFilter(String name) {
        super(name);

        mRandom = new Random();
    }

    @Override
    public void setupPorts() {
        addMaskedInputPort("image", ImageFormat.create(ImageFormat.COLORSPACE_RGBA));
        addOutputBasedOnInput("image", "image");
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return inputFormat;
    }

    @Override
    public void tearDown(FilterContext context) {
        if (mNoiseFrame != null) {
            mNoiseFrame.release();
            mNoiseFrame = null;
        }
    }

    public void initProgram(FilterContext context, int target) {
        switch (target) {
            case FrameFormat.TARGET_GPU:
                ShaderProgram shaderProgram = new ShaderProgram(context, mBlackWhiteShader);
                shaderProgram.setMaximumTileSize(mTileSize);
                mProgram = shaderProgram;
                updateParameters();
                break;

            default:
                throw new RuntimeException("Filter Sharpen does not support frames of " +
                    "target " + target + "!");
        }
        mTarget = target;
    }

    private void updateParameters() {
        float scale = (mBlack != mWhite) ? 1.0f / (mWhite - mBlack) : 2000f;
        float stepsize = 1.0f / 255.0f;

        mProgram.setHostValue("black", mBlack);
        mProgram.setHostValue("scale", scale);
        mProgram.setHostValue("stepsize", stepsize);
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

        // Check if the frame size has changed
        if (inputFormat.getWidth() != mWidth || inputFormat.getHeight() != mHeight) {
            mWidth = inputFormat.getWidth();
            mHeight = inputFormat.getHeight();

            if (mNoiseFrame != null) {
                mNoiseFrame.release();
            }

            int[] buffer = new int[mWidth * mHeight];
            for (int i = 0; i < mWidth * mHeight; ++i) {
              buffer[i] = mRandom.nextInt(255);
            }
            FrameFormat format = ImageFormat.create(mWidth, mHeight,
                                                    ImageFormat.COLORSPACE_RGBA,
                                                    FrameFormat.TARGET_GPU);
            mNoiseFrame = context.getFrameManager().newFrame(format);
            mNoiseFrame.setInts(buffer);
        }

        if (mNoiseFrame != null && (mNoiseFrame.getFormat().getWidth() != mWidth ||
                                    mNoiseFrame.getFormat().getHeight() != mHeight)) {
            throw new RuntimeException("Random map and imput image size mismatch!");
        }

        // Create output frame
        Frame output = context.getFrameManager().newFrame(inputFormat);

        // Process
        Frame[] inputs = {input, mNoiseFrame};
        mProgram.process(inputs, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }
}
