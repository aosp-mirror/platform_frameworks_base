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

public class SaturateFilter extends Filter {

    @GenerateFieldPort(name = "scale", hasDefault = true)
    private float mScale = 0f;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mBenProgram;
    private Program mHerfProgram;
    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private final String mBenSaturateShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform float scale;\n" +
            "uniform float shift;\n" +
            "uniform vec3 weights;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  float kv = dot(color.rgb, weights) + shift;\n" +
            "  vec3 new_color = scale * color.rgb + (1.0 - scale) * kv;\n" +
            "  gl_FragColor = vec4(new_color, color.a);\n" +
            "}\n";

    private final String mHerfSaturateShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform vec3 weights;\n" +
            "uniform vec3 exponents;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  float de = dot(color.rgb, weights);\n" +
            "  float inv_de = 1.0 / de;\n" +
            "  vec3 new_color = de * pow(color.rgb * inv_de, exponents);\n" +
            "  float max_color = max(max(max(new_color.r, new_color.g), new_color.b), 1.0);\n" +
            "  gl_FragColor = vec4(new_color / max_color, color.a);\n" +
            "}\n";


    public SaturateFilter(String name) {
        super(name);
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
                ShaderProgram shaderProgram = new ShaderProgram(context, mBenSaturateShader);
                shaderProgram.setMaximumTileSize(mTileSize);
                mBenProgram = shaderProgram;

                shaderProgram = new ShaderProgram(context, mHerfSaturateShader);
                shaderProgram.setMaximumTileSize(mTileSize);
                mHerfProgram = shaderProgram;

                break;

            default:
                throw new RuntimeException("Filter Sharpen does not support frames of " +
                    "target " + target + "!");
        }
        mTarget = target;
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        if (mBenProgram != null && mHerfProgram != null) {
            updateParameters();
        }
    }

    @Override
    public void process(FilterContext context) {
        // Get input frame
        Frame input = pullInput("image");
        FrameFormat inputFormat = input.getFormat();

        // Create program if not created already
        if (mBenProgram == null || inputFormat.getTarget() != mTarget) {
            initProgram(context, inputFormat.getTarget());
            initParameters();
        }

        // Create output frame
        Frame output = context.getFrameManager().newFrame(inputFormat);

        // Process
        if (mScale > 0.0f) {
            mHerfProgram.process(input, output);
        } else {
            mBenProgram.process(input, output);
        }
         // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }

    private void initParameters() {
        float shift = 1.0f / 255.0f;
        float weights[] = { 2f/8f, 5f/8f, 1f/8f};

        mBenProgram.setHostValue("weights", weights);
        mBenProgram.setHostValue("shift", shift);

        mHerfProgram.setHostValue("weights", weights);

        updateParameters();
    }

    private void updateParameters() {

        if (mScale > 0.0f) {
            float exponents[] = new float[3];

            exponents[0] = (0.9f * mScale) + 1.0f;
            exponents[1] = (2.1f * mScale) + 1.0f;
            exponents[2] = (2.7f * mScale) + 1.0f;

            mHerfProgram.setHostValue("exponents", exponents);
        } else {
            mBenProgram.setHostValue("scale", 1.0f + mScale);
        }
    }

}
