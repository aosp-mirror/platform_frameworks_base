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
import android.filterfw.core.KeyValueMap;
import android.filterfw.core.NativeProgram;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;

import java.util.Set;

/**
 * The filter linearly blends "left" and "right" frames. The blending weight is
 * the multiplication of parameter "blend" and the alpha value in "right" frame.
 * @hide
 */
public class BlendFilter extends ImageCombineFilter {

    private final String mBlendShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform float blend;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 colorL = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  vec4 colorR = texture2D(tex_sampler_1, v_texcoord);\n" +
            "  float weight = colorR.a * blend;\n" +
            "  gl_FragColor = mix(colorL, colorR, weight);\n" +
            "}\n";

    public BlendFilter(String name) {
        super(name, new String[] { "left", "right" }, "blended", "blend");
    }

    @Override
    protected Program getNativeProgram(FilterContext context) {
        throw new RuntimeException("TODO: Write native implementation for Blend!");
    }

    @Override
    protected Program getShaderProgram(FilterContext context) {
        return new ShaderProgram(context, mBlendShader);
    }
}
