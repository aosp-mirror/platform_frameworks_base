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

import android.util.Log;

public class CrossProcessFilter extends Filter {

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;

    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private final String mCrossProcessShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  vec3 ncolor = vec3(0.0, 0.0, 0.0);\n" +
            "  float value;\n" +
            "  if (color.r < 0.5) {\n" +
            "    value = color.r;\n" +
            "  } else {\n" +
            "    value = 1.0 - color.r;\n" +
            "  }\n" +
            "  float red = 4.0 * value * value * value;\n" +
            "  if (color.r < 0.5) {\n" +
            "    ncolor.r = red;\n" +
            "  } else {\n" +
            "    ncolor.r = 1.0 - red;\n" +
            "  }\n" +
            "  if (color.g < 0.5) {\n" +
            "    value = color.g;\n" +
            "  } else {\n" +
            "    value = 1.0 - color.g;\n" +
            "  }\n" +
            "  float green = 2.0 * value * value;\n" +
            "  if (color.g < 0.5) {\n" +
            "    ncolor.g = green;\n" +
            "  } else {\n" +
            "    ncolor.g = 1.0 - green;\n" +
            "  }\n" +
            "  ncolor.b = color.b * 0.5 + 0.25;\n" +
            "  gl_FragColor = vec4(ncolor.rgb, color.a);\n" +
            "}\n";

    public CrossProcessFilter(String name) {
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
                ShaderProgram shaderProgram = new ShaderProgram(context, mCrossProcessShader);
                shaderProgram.setMaximumTileSize(mTileSize);
                mProgram = shaderProgram;
                break;

            default:
                throw new RuntimeException("Filter CrossProcess does not support frames of " +
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
