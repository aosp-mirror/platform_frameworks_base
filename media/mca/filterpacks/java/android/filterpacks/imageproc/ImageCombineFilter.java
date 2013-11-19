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
import android.filterfw.core.Program;
import android.filterfw.format.ImageFormat;

import java.lang.reflect.Field;

/**
 * @hide
 */
public abstract class ImageCombineFilter extends Filter {

    protected Program mProgram;
    protected String[] mInputNames;
    protected String mOutputName;
    protected String mParameterName;
    protected int mCurrentTarget = FrameFormat.TARGET_UNSPECIFIED;

    public ImageCombineFilter(String name,
                              String[] inputNames,
                              String outputName,
                              String parameterName) {
        super(name);
        mInputNames = inputNames;
        mOutputName = outputName;
        mParameterName = parameterName;
    }

    @Override
    public void setupPorts() {
        if (mParameterName != null) {
            try {
                Field programField = ImageCombineFilter.class.getDeclaredField("mProgram");
                addProgramPort(mParameterName, mParameterName, programField, float.class, false);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException("Internal Error: mProgram field not found!");
            }
        }
        for (String inputName : mInputNames) {
            addMaskedInputPort(inputName, ImageFormat.create(ImageFormat.COLORSPACE_RGBA));
        }
        addOutputBasedOnInput(mOutputName, mInputNames[0]);
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return inputFormat;
    }

    private void assertAllInputTargetsMatch() {
        int target = getInputFormat(mInputNames[0]).getTarget();
        for (String inputName : mInputNames) {
            if (target != getInputFormat(inputName).getTarget()) {
                throw new RuntimeException("Type mismatch of input formats in filter " + this
                    + ". All input frames must have the same target!");
            }
        }
    }

    @Override
    public void process(FilterContext context) {
        // Pull input frames
        int i = 0;
        Frame[] inputs = new Frame[mInputNames.length];
        for (String inputName : mInputNames) {
            inputs[i++] = pullInput(inputName);
        }

        // Create output frame
        Frame output = context.getFrameManager().newFrame(inputs[0].getFormat());

        // Make sure we have a program
        updateProgramWithTarget(inputs[0].getFormat().getTarget(), context);

        // Process
        mProgram.process(inputs, output);

        // Push output
        pushOutput(mOutputName, output);

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
                throw new RuntimeException("Could not create a program for image filter "
                    + this + "!");
            }
            initProgramInputs(mProgram, context);
            mCurrentTarget = target;
        }
    }

    protected abstract Program getNativeProgram(FilterContext context);

    protected abstract Program getShaderProgram(FilterContext context);
}
