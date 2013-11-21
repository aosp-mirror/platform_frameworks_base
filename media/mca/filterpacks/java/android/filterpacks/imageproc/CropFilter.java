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
import android.filterfw.geometry.Quad;
import android.filterfw.format.ImageFormat;
import android.filterfw.format.ObjectFormat;

/**
 * @hide
 */
public class CropFilter extends Filter {

    private Program mProgram;
    private FrameFormat mLastFormat = null;

    @GenerateFieldPort(name = "owidth")
    private int mOutputWidth = -1;

    @GenerateFieldPort(name = "oheight")
    private int mOutputHeight = -1;

    @GenerateFieldPort(name = "fillblack")
    private boolean mFillBlack = false;

    public CropFilter(String name) {
        super(name);
    }

    private final String mFragShader =
      "precision mediump float;\n" +
      "uniform sampler2D tex_sampler_0;\n" +
      "varying vec2 v_texcoord;\n" +
      "void main() {\n" +
      "  const vec2 lo = vec2(0.0, 0.0);\n" +
      "  const vec2 hi = vec2(1.0, 1.0);\n" +
      "  const vec4 black = vec4(0.0, 0.0, 0.0, 1.0);\n" +
      "  bool out_of_bounds =\n" +
      "    any(lessThan(v_texcoord, lo)) ||\n" +
      "    any(greaterThan(v_texcoord, hi));\n" +
      "  if (out_of_bounds) {\n" +
      "    gl_FragColor = black;\n" +
      "  } else {\n" +
      "    gl_FragColor = texture2D(tex_sampler_0, v_texcoord);\n" +
      "  }\n" +
      "}\n";

    @Override
    public void setupPorts() {
        addMaskedInputPort("image", ImageFormat.create(ImageFormat.COLORSPACE_RGBA));
        addMaskedInputPort("box", ObjectFormat.fromClass(Quad.class, FrameFormat.TARGET_SIMPLE));
        addOutputBasedOnInput("image", "image");
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        // Make sure output size is set to unspecified, as we do not know what we will be resizing
        // to.
        MutableFrameFormat outputFormat = inputFormat.mutableCopy();
        outputFormat.setDimensions(FrameFormat.SIZE_UNSPECIFIED, FrameFormat.SIZE_UNSPECIFIED);
        return outputFormat;
    }

    protected void createProgram(FilterContext context, FrameFormat format) {
        // TODO: Add CPU version
        if (mLastFormat != null && mLastFormat.getTarget() == format.getTarget()) return;
        mLastFormat = format;
        mProgram = null;
        switch (format.getTarget()) {
            case FrameFormat.TARGET_GPU:
              if(mFillBlack)
                mProgram = new ShaderProgram(context, mFragShader);
              else
                mProgram = ShaderProgram.createIdentity(context);

                break;
        }
        if (mProgram == null) {
            throw new RuntimeException("Could not create a program for crop filter " + this + "!");
        }
    }

    @Override
    public void process(FilterContext env) {
        // Get input frame
        Frame imageFrame = pullInput("image");
        Frame boxFrame = pullInput("box");

        createProgram(env, imageFrame.getFormat());

        // Get the box
        Quad box = (Quad)boxFrame.getObjectValue();

        // Create output format
        MutableFrameFormat outputFormat = imageFrame.getFormat().mutableCopy();
        outputFormat.setDimensions(mOutputWidth == -1 ? outputFormat.getWidth() : mOutputWidth,
                                   mOutputHeight == -1 ? outputFormat.getHeight() : mOutputHeight);

        // Create output frame
        Frame output = env.getFrameManager().newFrame(outputFormat);

        // Set the program parameters
        if (mProgram instanceof ShaderProgram) {
            ShaderProgram shaderProgram = (ShaderProgram)mProgram;
            shaderProgram.setSourceRegion(box);
        }

        mProgram.process(imageFrame, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }


}
