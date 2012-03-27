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
import android.filterfw.core.GLFrame;
import android.filterfw.core.NativeProgram;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.geometry.Quad;
import android.filterfw.format.ImageFormat;
import android.filterfw.format.ObjectFormat;

import android.opengl.GLES20;

/**
 * @hide
 */
public class DrawOverlayFilter extends Filter {

    private ShaderProgram mProgram;

    public DrawOverlayFilter(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        FrameFormat imageFormatMask = ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                         FrameFormat.TARGET_GPU);
        addMaskedInputPort("source", imageFormatMask);
        addMaskedInputPort("overlay", imageFormatMask);
        addMaskedInputPort("box", ObjectFormat.fromClass(Quad.class, FrameFormat.TARGET_SIMPLE));
        addOutputBasedOnInput("image", "source");
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return inputFormat;
    }

    @Override
    public void prepare(FilterContext context) {
        mProgram = ShaderProgram.createIdentity(context);
    }

    @Override
    public void process(FilterContext env) {
        // Get input frame
        Frame sourceFrame = pullInput("source");
        Frame overlayFrame = pullInput("overlay");
        Frame boxFrame = pullInput("box");

        // Get the box
        Quad box = (Quad)boxFrame.getObjectValue();
        box = box.translated(1.0f, 1.0f).scaled(2.0f);

        mProgram.setTargetRegion(box);

        // Create output frame with copy of input
        Frame output = env.getFrameManager().newFrame(sourceFrame.getFormat());
        output.setDataFromFrame(sourceFrame);

        // Draw onto output
        mProgram.process(overlayFrame, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }
}
