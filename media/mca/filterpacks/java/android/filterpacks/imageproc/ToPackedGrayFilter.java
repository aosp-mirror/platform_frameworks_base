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
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;

import java.lang.Math;
/**
 * @hide
 */
public class ToPackedGrayFilter extends Filter {

    @GenerateFieldPort(name = "owidth", hasDefault = true)
    private int mOWidth = FrameFormat.SIZE_UNSPECIFIED;
    @GenerateFieldPort(name = "oheight", hasDefault = true)
    private int mOHeight = FrameFormat.SIZE_UNSPECIFIED;
    @GenerateFieldPort(name = "keepAspectRatio", hasDefault = true)
    private boolean mKeepAspectRatio = false;

    private Program mProgram;

    private final String mColorToPackedGrayShader =
        "precision mediump float;\n" +
        "const vec4 coeff_y = vec4(0.299, 0.587, 0.114, 0);\n" +
        "uniform sampler2D tex_sampler_0;\n" +
        "uniform float pix_stride;\n" +
        "varying vec2 v_texcoord;\n" +
        "void main() {\n" +
        "  for (int i = 0; i < 4; ++i) {\n" +
        "    vec4 p = texture2D(tex_sampler_0,\n" +
        "                       v_texcoord + vec2(pix_stride * float(i), 0.0));\n" +
        "    gl_FragColor[i] = dot(p, coeff_y);\n" +
        "  }\n" +
        "}\n";

    public ToPackedGrayFilter(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addMaskedInputPort("image", ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                       FrameFormat.TARGET_GPU));
        addOutputBasedOnInput("image", "image");
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return convertInputFormat(inputFormat);
    }

    private void checkOutputDimensions(int outputWidth, int outputHeight) {
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new RuntimeException("Invalid output dimensions: " +
                                       outputWidth + " " + outputHeight);
        }
    }

    private FrameFormat convertInputFormat(FrameFormat inputFormat) {
        int ow = mOWidth;
        int oh = mOHeight;
        int w = inputFormat.getWidth();
        int h = inputFormat.getHeight();
        if (mOWidth == FrameFormat.SIZE_UNSPECIFIED) {
            ow = w;
        }
        if (mOHeight == FrameFormat.SIZE_UNSPECIFIED) {
            oh = h;
        }
        if (mKeepAspectRatio) {
            // if keep aspect ratio, use the bigger dimension to determine the
            // final output size
            if (w > h) {
                ow = Math.max(ow, oh);
                oh = ow * h / w;
            } else {
                oh = Math.max(ow, oh);
                ow = oh * w / h;
            }
        }
        ow = (ow > 0 && ow < 4) ? 4 : (ow / 4) * 4; // ensure width is multiple of 4
        return ImageFormat.create(ow, oh,
                                  ImageFormat.COLORSPACE_GRAY,
                                  FrameFormat.TARGET_NATIVE);
    }

    @Override
    public void prepare(FilterContext context) {
        mProgram = new ShaderProgram(context, mColorToPackedGrayShader);
    }

    @Override
    public void process(FilterContext context) {
        Frame input = pullInput("image");
        FrameFormat inputFormat = input.getFormat();
        FrameFormat outputFormat = convertInputFormat(inputFormat);
        int ow = outputFormat.getWidth();
        int oh = outputFormat.getHeight();
        checkOutputDimensions(ow, oh);
        mProgram.setHostValue("pix_stride", 1.0f / ow);

        // Do the RGBA to luminance conversion.
        MutableFrameFormat tempFrameFormat = inputFormat.mutableCopy();
        tempFrameFormat.setDimensions(ow / 4, oh);
        Frame temp = context.getFrameManager().newFrame(tempFrameFormat);
        mProgram.process(input, temp);

        // Read frame from GPU to CPU.
        Frame output = context.getFrameManager().newFrame(outputFormat);
        output.setDataFromFrame(temp);
        temp.release();

        // Push output and yield ownership.
        pushOutput("image", output);
        output.release();
    }

}
