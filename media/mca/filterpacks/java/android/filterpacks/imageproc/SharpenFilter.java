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

import java.util.Set;

public class SharpenFilter extends Filter {

    @GenerateFieldPort(name = "scale", hasDefault = true)
    private float mScale = 0f;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;

    private int mWidth = 0;
    private int mHeight = 0;
    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private final String mSharpenShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform float scale;\n" +
            "uniform float stepsizeX;\n" +
            "uniform float stepsizeY;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec3 nbr_color = vec3(0.0, 0.0, 0.0);\n" +
            "  vec2 coord;\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  coord.x = v_texcoord.x - 0.5 * stepsizeX;\n" +
            "  coord.y = v_texcoord.y - stepsizeY;\n" +
            "  nbr_color += texture2D(tex_sampler_0, coord).rgb - color.rgb;\n" +
            "  coord.x = v_texcoord.x - stepsizeX;\n" +
            "  coord.y = v_texcoord.y + 0.5 * stepsizeY;\n" +
            "  nbr_color += texture2D(tex_sampler_0, coord).rgb - color.rgb;\n" +
            "  coord.x = v_texcoord.x + stepsizeX;\n" +
            "  coord.y = v_texcoord.y - 0.5 * stepsizeY;\n" +
            "  nbr_color += texture2D(tex_sampler_0, coord).rgb - color.rgb;\n" +
            "  coord.x = v_texcoord.x + stepsizeX;\n" +
            "  coord.y = v_texcoord.y + 0.5 * stepsizeY;\n" +
            "  nbr_color += texture2D(tex_sampler_0, coord).rgb - color.rgb;\n" +
            "  gl_FragColor = vec4(color.rgb - 2.0 * scale * nbr_color, color.a);\n" +
            "}\n";

    public SharpenFilter(String name) {
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
                ShaderProgram shaderProgram = new ShaderProgram(context, mSharpenShader);
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

        // Create output frame
        Frame output = context.getFrameManager().newFrame(inputFormat);

        // Create program if not created already
        if (mProgram == null || inputFormat.getTarget() != mTarget) {
            initProgram(context, inputFormat.getTarget());
        }

        // Check if the frame size has changed
        if (inputFormat.getWidth() != mWidth || inputFormat.getHeight() != mHeight) {
            updateFrameSize(inputFormat.getWidth(), inputFormat.getHeight());
        }

        // Process
        mProgram.process(input, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }

    private void updateFrameSize(int width, int height) {
        mWidth = width;
        mHeight = height;

        if (mProgram != null) {
            mProgram.setHostValue("stepsizeX", 1.0f / mWidth);
            mProgram.setHostValue("stepsizeY", 1.0f / mHeight);
            updateParameters();
        }
    }

    private void updateParameters() {
        mProgram.setHostValue("scale", mScale);
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        if (mProgram != null) {
            updateParameters();
        }
    }
}
