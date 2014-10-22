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
import android.filterfw.core.ShaderProgram;
import android.filterfw.geometry.Quad;
import android.filterfw.format.ImageFormat;
import android.filterfw.format.ObjectFormat;

import android.opengl.GLES20;

/**
 * @hide
 */
public class DrawRectFilter extends Filter {

    @GenerateFieldPort(name = "colorRed",  hasDefault = true)
    private float mColorRed = 0.8f;

    @GenerateFieldPort(name = "colorGreen", hasDefault = true)
    private float mColorGreen = 0.8f;

    @GenerateFieldPort(name = "colorBlue", hasDefault = true)
    private float mColorBlue = 0.0f;

    private final String mVertexShader =
        "attribute vec4 aPosition;\n" +
        "void main() {\n" +
        "  gl_Position = aPosition;\n" +
        "}\n";

    private final String mFixedColorFragmentShader =
        "precision mediump float;\n" +
        "uniform vec4 color;\n" +
        "void main() {\n" +
        "  gl_FragColor = color;\n" +
        "}\n";

    private ShaderProgram mProgram;


    public DrawRectFilter(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addMaskedInputPort("image", ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                       FrameFormat.TARGET_GPU));
        addMaskedInputPort("box", ObjectFormat.fromClass(Quad.class, FrameFormat.TARGET_SIMPLE));
        addOutputBasedOnInput("image", "image");
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return inputFormat;
    }

    @Override
    public void prepare(FilterContext context) {
        mProgram = new ShaderProgram(context, mVertexShader, mFixedColorFragmentShader);
    }

    @Override
    public void process(FilterContext env) {
        // Get input frame
        Frame imageFrame = pullInput("image");
        Frame boxFrame = pullInput("box");

        // Get the box
        Quad box = (Quad)boxFrame.getObjectValue();
        box = box.scaled(2.0f).translated(-1.0f, -1.0f);

        // Create output frame with copy of input
        GLFrame output = (GLFrame)env.getFrameManager().duplicateFrame(imageFrame);

        // Draw onto output
        output.focus();
        renderBox(box);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }

    private void renderBox(Quad box) {
        final int FLOAT_SIZE = 4;

        // Get current values
        float[] color = {mColorRed, mColorGreen, mColorBlue, 1f};
        float[] vertexValues = { box.p0.x, box.p0.y,
                                 box.p1.x, box.p1.y,
                                 box.p3.x, box.p3.y,
                                 box.p2.x, box.p2.y };

        // Set the program variables
        mProgram.setHostValue("color", color);
        mProgram.setAttributeValues("aPosition", vertexValues, 2);
        mProgram.setVertexCount(4);

        // Draw
        mProgram.beginDrawing();
        GLES20.glLineWidth(1.0f);
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4);
    }
}
