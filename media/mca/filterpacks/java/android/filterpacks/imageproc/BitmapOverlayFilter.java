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
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;
import android.graphics.Bitmap;

/**
 * @hide
 */
public class BitmapOverlayFilter extends Filter {

    @GenerateFieldPort(name = "bitmap")
    private Bitmap mBitmap;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Program mProgram;
    private Frame mFrame;

    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private final String mOverlayShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 original = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  vec4 mask = texture2D(tex_sampler_1, v_texcoord);\n" +
            "  gl_FragColor = vec4(original.rgb * (1.0 - mask.a) + mask.rgb, 1.0);\n" +
            "}\n";

    public BitmapOverlayFilter(String name) {
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

    public void initProgram(FilterContext context, int target) {
        switch (target) {
            case FrameFormat.TARGET_GPU:
                ShaderProgram shaderProgram = new ShaderProgram(context, mOverlayShader);
                shaderProgram.setMaximumTileSize(mTileSize);
                mProgram = shaderProgram;
                break;

            default:
                throw new RuntimeException("Filter FisheyeFilter does not support frames of " +
                    "target " + target + "!");
        }
        mTarget = target;
    }

    @Override
    public void tearDown(FilterContext context) {
        if (mFrame != null) {
            mFrame.release();
            mFrame = null;
        }
    }

    @Override
    public void process(FilterContext context) {
        // Get input frame
        Frame input = pullInput("image");
        FrameFormat inputFormat = input.getFormat();

        // Create output frame
        Frame output = context.getFrameManager().newFrame(inputFormat);

        // Create program if not created already
        if (mProgram == null || inputFormat.getTarget() != mTarget) {
            initProgram(context, inputFormat.getTarget());
        }

        if (mBitmap != null) {
            Frame frame = createBitmapFrame(context);
            // Process
            Frame[] inputs = {input, frame};
            mProgram.process(inputs, output);

            frame.release();
        } else {
            output.setDataFromFrame(input);
        }

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }

    private Frame createBitmapFrame(FilterContext context) {
        FrameFormat format = ImageFormat.create(mBitmap.getWidth(),
                                                mBitmap.getHeight(),
                                                ImageFormat.COLORSPACE_RGBA,
                                                FrameFormat.TARGET_GPU);

        Frame frame = context.getFrameManager().newFrame(format);
        frame.setBitmap(mBitmap);

        mBitmap.recycle();
        mBitmap = null;

        return frame;
    }
}
