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
import android.filterfw.core.NativeProgram;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;

/**
 * @hide
 */
public class Invert extends SimpleImageFilter {

    private static final String mInvertShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  gl_FragColor.r = 1.0 - color.r;\n" +
            "  gl_FragColor.g = 1.0 - color.g;\n" +
            "  gl_FragColor.b = 1.0 - color.b;\n" +
            "  gl_FragColor.a = color.a;\n" +
            "}\n";

    public Invert(String name) {
        super(name, null);
    }

    @Override
    protected Program getNativeProgram(FilterContext context) {
        return new NativeProgram("filterpack_imageproc", "invert");
    }

    @Override
    protected Program getShaderProgram(FilterContext context) {
        return new ShaderProgram(context, mInvertShader);
    }

}
