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

import java.util.Date;
import java.util.Random;

public class BlackWhiteFilter extends Filter {

    @GenerateFieldPort(name = "black", hasDefault = true)
    private float mBlack = 0f;

    @GenerateFieldPort(name = "white", hasDefault = true)
    private float mWhite = 1f;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;
    private Random mRandom;

    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private final String mBlackWhiteShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform vec2 seed;\n" +
            "uniform float black;\n" +
            "uniform float scale;\n" +
            "uniform float stepsize;\n" +
            "varying vec2 v_texcoord;\n" +
            "float rand(vec2 loc) {\n" +
            // Compute sin(theta), theta = 12.9898 x + 78.233y
            // because floating point has limited range, make theta = theta1 + theta2
            // where theta1 = 12x + 78y and theta2 = 0.9898x + 0.233y)
            // Note that theta1 and theta2 cover diffent range of theta.
            "  float theta1 = dot(loc, vec2(0.9898, 0.233));\n" +
            "  float theta2 = dot(loc, vec2(12.0, 78.0));\n" +
            // Use the property sin(theta) = cos(theta1)*sin(theta2)+sin(theta1)*cos(theta2)
            // this approach also increases the precisions of sin(theta)
            "  float value = cos(theta1) * sin(theta2) + sin(theta1) * cos(theta2);\n" +
            // fract(43758.5453 * x) = fract(43758 * x + 0.5453 * x)
            // keep value of part1 in range: (2^-14 to 2^14). Since 43758 = 117 * 374
            // fract(43758 * sin(theta)) = mod(221 * mod(198*sin(theta), 1.0), 1.0)
            // also to keep as much decimal digits, use the property
            // mod(mod(198*sin(theta)) = mod(mod(197*sin(theta) + sin(theta))
            "  float temp = mod(197.0 * value, 1.0) + value;\n" +
            "  float part1 = mod(220.0 * temp, 1.0) + temp;\n" +
            "  float part2 = value * 0.5453;\n" +
            "  float part3 = cos(theta1 + theta2) * 0.43758;\n" +
            "  return fract(part1 + part2 + part3);\n" +
            "}\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  float dither = rand(v_texcoord + seed);\n" +
            "  vec3 xform = clamp((color.rgb - black) * scale, 0.0, 1.0);\n" +
            "  vec3 temp = clamp((color.rgb + stepsize - black) * scale, 0.0, 1.0);\n" +
            "  vec3 new_color = clamp(xform + (temp - xform) * (dither - 0.5), 0.0, 1.0);\n" +
            "  gl_FragColor = vec4(new_color, color.a);\n" +
            "}\n";

    public BlackWhiteFilter(String name) {
        super(name);
        mRandom = new Random(new Date().getTime());
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

        float seed[] = { mRandom.nextFloat(), mRandom.nextFloat() };
        mProgram.setHostValue("seed", seed);
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
        Frame output = context.getFrameManager().newFrame(inputFormat);

        // Process
        mProgram.process(input, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }
}
