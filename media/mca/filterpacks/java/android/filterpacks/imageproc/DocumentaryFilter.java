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

public class DocumentaryFilter extends Filter {

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;

    private int mWidth = 0;
    private int mHeight = 0;
    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private Frame mNoiseFrame;
    private Random mRandom;

    private final String mDocumentaryShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform float stepsize;\n" +
            "uniform float inv_max_dist;\n" +
            "uniform vec2 center;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            // black white
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  float dither = texture2D(tex_sampler_1, v_texcoord).r;\n" +
            "  vec3 xform = clamp(2.0 * color.rgb, 0.0, 1.0);\n" +
            "  vec3 temp = clamp(2.0 * (color.rgb + stepsize), 0.0, 1.0);\n" +
            "  vec3 new_color = clamp(xform + (temp - xform) * (dither - 0.5), 0.0, 1.0);\n" +
            // grayscale
            "  float gray = dot(new_color, vec3(0.299, 0.587, 0.114));\n" +
            "  new_color = vec3(gray, gray, gray);\n" +
            // vignette
            "  float dist = distance(gl_FragCoord.xy, center);\n" +
            "  float lumen = 0.85 / (1.0 + exp((dist * inv_max_dist - 0.83) * 20.0)) + 0.15;\n" +
            "  gl_FragColor = vec4(new_color * lumen, color.a);\n" +
            "}\n";

    public DocumentaryFilter(String name) {
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
                ShaderProgram shaderProgram = new ShaderProgram(context, mDocumentaryShader);
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

            int[] buffer = new int[mWidth * mHeight];
            for (int i = 0; i < mWidth * mHeight; ++i) {
              buffer[i] = mRandom.nextInt(255);
            }
            FrameFormat format = ImageFormat.create(mWidth, mHeight,
                                                    ImageFormat.COLORSPACE_RGBA,
                                                    FrameFormat.TARGET_GPU);
            if (mNoiseFrame != null) {
                mNoiseFrame.release();
            }
            mNoiseFrame = context.getFrameManager().newFrame(format);
            mNoiseFrame.setInts(buffer);

            initParameters();
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

    private void initParameters() {
        if (mProgram != null) {
            float centerX = (float) (0.5 * mWidth);
            float centerY = (float) (0.5 * mHeight);
            float center[] = {centerX, centerY};
            float max_dist = (float) Math.sqrt(centerX * centerX + centerY * centerY);

            mProgram.setHostValue("center", center);
            mProgram.setHostValue("inv_max_dist", 1.0f / max_dist);
            mProgram.setHostValue("stepsize", 1.0f / 255.0f);
        }
    }

}
