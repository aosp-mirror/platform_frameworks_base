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
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;
import android.filterfw.geometry.Point;
import android.filterfw.geometry.Quad;

/**
 * The FixedRotationFilter rotates the input image clockwise, it only accepts
 * 4 rotation angles: 0, 90, 180, 270
 * @hide
 */
public class FixedRotationFilter extends Filter {

    @GenerateFieldPort(name = "rotation", hasDefault = true)
    private int mRotation = 0;

    private ShaderProgram mProgram = null;

    public FixedRotationFilter(String name) {
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
        return inputFormat;
    }

    @Override
    public void process(FilterContext context) {
        Frame input = pullInput("image");
        if (mRotation == 0) {
            pushOutput("image", input);
            return;
        }
        FrameFormat inputFormat = input.getFormat();

        // Create program if not created already
        if (mProgram == null) {
            mProgram = ShaderProgram.createIdentity(context);
        }
        MutableFrameFormat outputFormat = inputFormat.mutableCopy();
        int width = inputFormat.getWidth();
        int height = inputFormat.getHeight();
        Point p1 = new Point(0.0f, 0.0f);
        Point p2 = new Point(1.0f, 0.0f);
        Point p3 = new Point(0.0f, 1.0f);
        Point p4 = new Point(1.0f, 1.0f);
        Quad sourceRegion;
        switch (((int)Math.round(mRotation / 90f)) % 4) {
            case 1:
                sourceRegion = new Quad(p3,p1,p4,p2);
                outputFormat.setDimensions(height, width);
                break;
            case 2:
                sourceRegion = new Quad(p4,p3,p2,p1);
                break;
            case 3:
                sourceRegion = new Quad(p2,p4,p1,p3);
                outputFormat.setDimensions(height, width);
                break;
            case 0:
            default:
                sourceRegion = new Quad(p1,p2,p3,p4);
                break;
        }
        // Create output frame
        Frame output = context.getFrameManager().newFrame(outputFormat);

        // Set the source region
        mProgram.setSourceRegion(sourceRegion);

        // Process
        mProgram.process(input, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }
}
