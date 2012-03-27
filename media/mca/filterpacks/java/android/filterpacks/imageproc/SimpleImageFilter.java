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

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * @hide
 */
public abstract class SimpleImageFilter extends Filter {

    protected int mCurrentTarget = FrameFormat.TARGET_UNSPECIFIED;
    protected Program mProgram;
    protected String mParameterName;

    public SimpleImageFilter(String name, String parameterName) {
        super(name);
        mParameterName = parameterName;
    }

    @Override
    public void setupPorts() {
        if (mParameterName != null) {
            try {
                Field programField = SimpleImageFilter.class.getDeclaredField("mProgram");
                addProgramPort(mParameterName, mParameterName, programField, float.class, false);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException("Internal Error: mProgram field not found!");
            }
        }
        addMaskedInputPort("image", ImageFormat.create(ImageFormat.COLORSPACE_RGBA));
        addOutputBasedOnInput("image", "image");
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return inputFormat;
    }

    @Override
    public void process(FilterContext context) {
        // Get input frame
        Frame input = pullInput("image");
        FrameFormat inputFormat = input.getFormat();

        // Create output frame
        Frame output = context.getFrameManager().newFrame(inputFormat);

        // Create program if not created already
        updateProgramWithTarget(inputFormat.getTarget(), context);

        // Process
        mProgram.process(input, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }

    protected void updateProgramWithTarget(int target, FilterContext context) {
        if (target != mCurrentTarget) {
            switch (target) {
                case FrameFormat.TARGET_NATIVE:
                    mProgram = getNativeProgram(context);
                    break;

                case FrameFormat.TARGET_GPU:
                    mProgram = getShaderProgram(context);
                    break;

                default:
                    mProgram = null;
                    break;
            }
            if (mProgram == null) {
                throw new RuntimeException("Could not create a program for image filter " + this + "!");
            }
            initProgramInputs(mProgram, context);
            mCurrentTarget = target;
        }
    }

    protected abstract Program getNativeProgram(FilterContext context);

    protected abstract Program getShaderProgram(FilterContext context);
}
