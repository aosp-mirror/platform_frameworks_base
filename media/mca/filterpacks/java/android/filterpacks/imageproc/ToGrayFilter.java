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
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.NativeProgram;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;

import android.util.Log;

import java.lang.reflect.Field;

/**
 * @hide
 */
public class ToGrayFilter extends SimpleImageFilter {

    @GenerateFieldPort(name = "invertSource", hasDefault = true)
    private boolean mInvertSource = false;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private MutableFrameFormat mOutputFormat;

    private static final String mColorToGray4Shader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  float y = dot(color, vec4(0.299, 0.587, 0.114, 0));\n" +
            "  gl_FragColor = vec4(y, y, y, color.a);\n" +
            "}\n";

    public ToGrayFilter(String name) {
        super(name, null);
    }

    @Override
    public void setupPorts() {
        addMaskedInputPort("image", ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                       FrameFormat.TARGET_GPU));
        addOutputBasedOnInput("image", "image");
    }

    @Override
    protected Program getNativeProgram(FilterContext context) {
        throw new RuntimeException("Native toGray not implemented yet!");
    }

    @Override
    protected Program getShaderProgram(FilterContext context) {
        int inputChannels = getInputFormat("image").getBytesPerSample();
        if (inputChannels != 4) {
            throw new RuntimeException("Unsupported GL input channels: " +
                                       inputChannels + "! Channels must be 4!");
        }
        ShaderProgram program = new ShaderProgram(context, mColorToGray4Shader);
        program.setMaximumTileSize(mTileSize);
        if (mInvertSource)
            program.setSourceRect(0.0f, 1.0f, 1.0f, -1.0f);
        return program;
    }

}
