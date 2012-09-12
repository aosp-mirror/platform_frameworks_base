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
import android.filterfw.core.NativeProgram;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.Log;

/**
 * @hide
 */
public class RedEyeFilter extends Filter {

    private static final float RADIUS_RATIO = 0.06f;
    private static final float MIN_RADIUS = 10.0f;
    private static final float DEFAULT_RED_INTENSITY = 1.30f;

    @GenerateFieldPort(name = "centers")
    private float[] mCenters;

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    private Frame mRedEyeFrame;
    private Bitmap mRedEyeBitmap;

    private final Canvas mCanvas = new Canvas();
    private final Paint mPaint = new Paint();

    private float mRadius;

    private int mWidth = 0;
    private int mHeight = 0;

    private Program mProgram;
    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private final String mRedEyeShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform float intensity;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  vec4 mask = texture2D(tex_sampler_1, v_texcoord);\n" +
            "  if (mask.a > 0.0) {\n" +
            "    float green_blue = color.g + color.b;\n" +
            "    float red_intensity = color.r / green_blue;\n" +
            "    if (red_intensity > intensity) {\n" +
            "      color.r = 0.5 * green_blue;\n" +
            "    }\n" +
            "  }\n" +
            "  gl_FragColor = color;\n" +
            "}\n";

    public RedEyeFilter(String name) {
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
                ShaderProgram shaderProgram = new ShaderProgram(context, mRedEyeShader);
                shaderProgram.setMaximumTileSize(mTileSize);
                mProgram = shaderProgram;
                mProgram.setHostValue("intensity", DEFAULT_RED_INTENSITY);
                break;
            default:
                throw new RuntimeException("Filter RedEye does not support frames of " +
                    "target " + target + "!");
        }
        mTarget = target;
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

        // Check if the frame size has changed
        if (inputFormat.getWidth() != mWidth || inputFormat.getHeight() != mHeight) {
            mWidth = inputFormat.getWidth();
            mHeight = inputFormat.getHeight();
        }
        createRedEyeFrame(context);

        // Process
        Frame[] inputs = {input, mRedEyeFrame};
        mProgram.process(inputs, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();

        // Release unused frame
        mRedEyeFrame.release();
        mRedEyeFrame = null;
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
         if (mProgram != null) {
            updateProgramParams();
        }
    }

    private void createRedEyeFrame(FilterContext context) {
        int bitmapWidth = mWidth / 2;
        int bitmapHeight = mHeight / 2;

        Bitmap redEyeBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        mCanvas.setBitmap(redEyeBitmap);
        mPaint.setColor(Color.WHITE);
        mRadius = Math.max(MIN_RADIUS, RADIUS_RATIO * Math.min(bitmapWidth, bitmapHeight));

        for (int i = 0; i < mCenters.length; i += 2) {
            mCanvas.drawCircle(mCenters[i] * bitmapWidth, mCenters[i + 1] * bitmapHeight,
                               mRadius, mPaint);
        }

        FrameFormat format = ImageFormat.create(bitmapWidth, bitmapHeight,
                                                ImageFormat.COLORSPACE_RGBA,
                                                FrameFormat.TARGET_GPU);
        mRedEyeFrame = context.getFrameManager().newFrame(format);
        mRedEyeFrame.setBitmap(redEyeBitmap);
        redEyeBitmap.recycle();
    }

    private void updateProgramParams() {
        if ( mCenters.length % 2 == 1) {
            throw new RuntimeException("The size of center array must be even.");
        }
    }
}
