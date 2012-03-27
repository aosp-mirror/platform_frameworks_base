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
import android.filterfw.core.GLFrame;
import android.filterfw.core.KeyValueMap;
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.NativeProgram;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;

import android.opengl.GLES20;

/**
 * @hide
 */
public class ResizeFilter extends Filter {

    @GenerateFieldPort(name = "owidth")
    private int mOWidth;
    @GenerateFieldPort(name = "oheight")
    private int mOHeight;
    @GenerateFieldPort(name = "keepAspectRatio", hasDefault = true)
    private boolean mKeepAspectRatio = false;
    @GenerateFieldPort(name = "generateMipMap", hasDefault = true)
    private boolean mGenerateMipMap = false;

    private Program mProgram;
    private FrameFormat mLastFormat = null;

    private MutableFrameFormat mOutputFormat;
    private int mInputChannels;

    public ResizeFilter(String name) {
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

    protected void createProgram(FilterContext context, FrameFormat format) {
        if (mLastFormat != null && mLastFormat.getTarget() == format.getTarget()) return;
        mLastFormat = format;
        switch (format.getTarget()) {
            case FrameFormat.TARGET_NATIVE:
                throw new RuntimeException("Native ResizeFilter not implemented yet!");


            case FrameFormat.TARGET_GPU:
                ShaderProgram prog = ShaderProgram.createIdentity(context);
                mProgram = prog;
                break;

            default:
                throw new RuntimeException("ResizeFilter could not create suitable program!");
        }
    }
    @Override
    public void process(FilterContext env) {
        // Get input frame
        Frame input = pullInput("image");
        createProgram(env, input.getFormat());

        // Create output frame
        MutableFrameFormat outputFormat = input.getFormat().mutableCopy();
        if (mKeepAspectRatio) {
            FrameFormat inputFormat = input.getFormat();
            mOHeight = mOWidth * inputFormat.getHeight() / inputFormat.getWidth();
        }
        outputFormat.setDimensions(mOWidth, mOHeight);
        Frame output = env.getFrameManager().newFrame(outputFormat);

        // Process
        if (mGenerateMipMap) {
            GLFrame mipmapped = (GLFrame)env.getFrameManager().newFrame(input.getFormat());
            mipmapped.setTextureParameter(GLES20.GL_TEXTURE_MIN_FILTER,
                                          GLES20.GL_LINEAR_MIPMAP_NEAREST);
            mipmapped.setDataFromFrame(input);
            mipmapped.generateMipMap();
            mProgram.process(mipmapped, output);
            mipmapped.release();
        } else {
            mProgram.process(input, output);
        }

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }


}
