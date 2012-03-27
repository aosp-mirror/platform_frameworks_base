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
 * @hide
 */
public class AlphaBlendFilter extends ImageCombineFilter {

    private final String mAlphaBlendShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform sampler2D tex_sampler_2;\n" +
            "uniform float weight;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 colorL = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  vec4 colorR = texture2D(tex_sampler_1, v_texcoord);\n" +
            "  float blend = texture2D(tex_sampler_2, v_texcoord).r * weight;\n" +
            "  gl_FragColor = colorL * (1.0 - blend) + colorR * blend;\n" +
            "}\n";

    public AlphaBlendFilter(String name) {
        super(name, new String[] { "source", "overlay", "mask" }, "blended", "weight");
    }

    @Override
    protected Program getNativeProgram(FilterContext context) {
        throw new RuntimeException("TODO: Write native implementation for AlphaBlend!");
    }

    @Override
    protected Program getShaderProgram(FilterContext context) {
        return new ShaderProgram(context, mAlphaBlendShader);
    }

}
