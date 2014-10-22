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

import android.util.Log;

public class FillLightFilter extends Filter {

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    @GenerateFieldPort(name = "strength", hasDefault = true)
    private float mBacklight = 0f;

    private Program mProgram;

    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private final String mFillLightShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform float mult;\n" +
            "uniform float igamma;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main()\n" +
            "{\n" +
            "  const vec3 color_weights = vec3(0.25, 0.5, 0.25);\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  float lightmask = dot(color.rgb, color_weights);\n" +
            "  float backmask = (1.0 - lightmask);\n" +
            "  vec3 ones = vec3(1.0, 1.0, 1.0);\n" +
            "  vec3 diff = pow(mult * color.rgb, igamma * ones) - color.rgb;\n" +
            "  diff = min(diff, 1.0);\n" +
            "  vec3 new_color = min(color.rgb + diff * backmask, 1.0);\n" +
            "  gl_FragColor = vec4(new_color, color.a);\n" +
            "}\n";

    public FillLightFilter(String name) {
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
                ShaderProgram shaderProgram = new ShaderProgram(context, mFillLightShader);
                Log.e("FillLight", "tile size: " + mTileSize);
                shaderProgram.setMaximumTileSize(mTileSize);
                mProgram = shaderProgram;
                break;

            default:
                throw new RuntimeException("Filter FillLight does not support frames of " +
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
            updateParameters();
        }

        // Process
        mProgram.process(input, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }


    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        if (mProgram != null) {
            updateParameters();
        }
    }

    private void updateParameters() {
        float fade_gamma = 0.3f;
        float amt = 1.0f - mBacklight;
        float mult = 1.0f / (amt * 0.7f + 0.3f);
        float faded = fade_gamma + (1.0f -fade_gamma) *mult;
        float igamma = 1.0f / faded;

        mProgram.setHostValue("mult", mult);
        mProgram.setHostValue("igamma", igamma);
    }
}
