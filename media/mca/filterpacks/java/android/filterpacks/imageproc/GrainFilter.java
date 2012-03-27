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
import android.filterfw.geometry.Quad;
import android.filterfw.geometry.Point;

import java.util.Random;

public class GrainFilter extends Filter {

    private static final int RAND_THRESHOLD = 128;

    @GenerateFieldPort(name = "strength", hasDefault = true)
    private float mScale = 0f;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;

    private int mWidth = 0;
    private int mHeight = 0;
    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private Frame mNoiseFrame = null;
    private Random mRandom;

    private final String mGrainShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform float scale;\n" +
            "uniform float stepX;\n" +
            "uniform float stepY;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  float noise = texture2D(tex_sampler_1, v_texcoord + vec2(-stepX, -stepY)).r * 0.224;\n" +
            "  noise += texture2D(tex_sampler_1, v_texcoord + vec2(-stepX, stepY)).r * 0.224;\n" +
            "  noise += texture2D(tex_sampler_1, v_texcoord + vec2(stepX, -stepY)).r * 0.224;\n" +
            "  noise += texture2D(tex_sampler_1, v_texcoord + vec2(stepX, stepY)).r * 0.224;\n" +
            "  noise += 0.4448;\n" +
            "  noise *= scale;\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  float energy = 0.33333 * color.r + 0.33333 * color.g + 0.33333 * color.b;\n" +
            "  float mask = (1.0 - sqrt(energy));\n" +
            "  float weight = 1.0 - 1.333 * mask * noise;\n" +
            "  gl_FragColor = vec4(color.rgb * weight, color.a);\n" +
            "}\n";

    public GrainFilter(String name) {
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

    public void initProgram(FilterContext context, int target) {
        switch (target) {
            case FrameFormat.TARGET_GPU:
                ShaderProgram shaderProgram = new ShaderProgram(context, mGrainShader);
                shaderProgram.setMaximumTileSize(mTileSize);
                mProgram = shaderProgram;
                break;

            default:
                throw new RuntimeException("Filter Sharpen does not support frames of " +
                    "target " + target + "!");
        }
        mTarget = target;
    }

    private void updateParameters() {
        mProgram.setHostValue("scale", mScale);
    }

    private void updateFrameSize(int width, int height) {
        mWidth = width;
        mHeight = height;

        if (mProgram != null) {
            mProgram.setHostValue("stepX", 0.5f / mWidth);
            mProgram.setHostValue("stepY", 0.5f / mHeight);
            updateParameters();
        }
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        if (mProgram != null) {
            updateParameters();
        }
    }

    @Override
    public void tearDown(FilterContext context) {
        if (mNoiseFrame != null) {
            mNoiseFrame.release();
            mNoiseFrame = null;
        }
    }

    @Override
    public void process(FilterContext context) {
        // Get input frame
        Frame input = pullInput("image");
        FrameFormat inputFormat = input.getFormat();

        // Create output frame
        Frame output = context.getFrameManager().newFrame(inputFormat);

        // Create program if not created already
        if (mProgram == null || inputFormat.getTarget() != mTarget) {
            initProgram(context, inputFormat.getTarget());
            updateParameters();
        }

        // Check if the frame size has changed
        if (inputFormat.getWidth() != mWidth || inputFormat.getHeight() != mHeight) {
            updateFrameSize(inputFormat.getWidth(), inputFormat.getHeight());

            int[] buffer = new int[mWidth * mHeight];
            for (int i = 0; i < mWidth * mHeight; ++i) {
                buffer[i] = (mRandom.nextInt(256) < RAND_THRESHOLD) ?
                    mRandom.nextInt(256) : 0;
            }
            FrameFormat format = ImageFormat.create(mWidth, mHeight,
                                                    ImageFormat.COLORSPACE_RGBA,
                                                    FrameFormat.TARGET_GPU);
            if (mNoiseFrame != null) {
                mNoiseFrame.release();
            }
            mNoiseFrame = context.getFrameManager().newFrame(format);
            mNoiseFrame.setInts(buffer);
        }

        if (mNoiseFrame.getFormat().getWidth() != mWidth ||
            mNoiseFrame.getFormat().getHeight() != mHeight) {
            throw new RuntimeException("Random map and imput image size mismatch!");
        }

        // Process
        Frame[] inputs = {input, mNoiseFrame};
        mProgram.process(inputs, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }
}
