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

public class AutoFixFilter extends Filter {

    @GenerateFieldPort(name = "tile_size", hasDefault = true)
    private int mTileSize = 640;

    @GenerateFieldPort(name = "scale")
    private float mScale;

    private static final int normal_cdf[] = {
        9, 33, 50, 64, 75, 84, 92, 99, 106, 112, 117, 122, 126, 130, 134, 138, 142,
        145, 148, 150, 154, 157, 159, 162, 164, 166, 169, 170, 173, 175, 177, 179,
        180, 182, 184, 186, 188, 189, 190, 192, 194, 195, 197, 198, 199, 200, 202,
        203, 205, 206, 207, 208, 209, 210, 212, 213, 214, 215, 216, 217, 218, 219,
        220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 229, 230, 231, 232, 233,
        234, 235, 236, 236, 237, 238, 239, 239, 240, 240, 242, 242, 243, 244, 245,
        245, 246, 247, 247, 248, 249, 249, 250, 250, 251, 252, 253, 253, 254, 255,
        255, 256, 256, 257, 258, 258, 259, 259, 259, 260, 261, 262, 262, 263, 263,
        264, 264, 265, 265, 266, 267, 267, 268, 268, 269, 269, 269, 270, 270, 271,
        272, 272, 273, 273, 274, 274, 275, 275, 276, 276, 277, 277, 277, 278, 278,
        279, 279, 279, 280, 280, 281, 282, 282, 282, 283, 283, 284, 284, 285, 285,
        285, 286, 286, 287, 287, 288, 288, 288, 289, 289, 289, 290, 290, 290, 291,
        292, 292, 292, 293, 293, 294, 294, 294, 295, 295, 296, 296, 296, 297, 297,
        297, 298, 298, 298, 299, 299, 299, 299, 300, 300, 301, 301, 302, 302, 302,
        303, 303, 304, 304, 304, 305, 305, 305, 306, 306, 306, 307, 307, 307, 308,
        308, 308, 309, 309, 309, 309, 310, 310, 310, 310, 311, 312, 312, 312, 313,
        313, 313, 314, 314, 314, 315, 315, 315, 315, 316, 316, 316, 317, 317, 317,
        318, 318, 318, 319, 319, 319, 319, 319, 320, 320, 320, 321, 321, 322, 322,
        322, 323, 323, 323, 323, 324, 324, 324, 325, 325, 325, 325, 326, 326, 326,
        327, 327, 327, 327, 328, 328, 328, 329, 329, 329, 329, 329, 330, 330, 330,
        330, 331, 331, 332, 332, 332, 333, 333, 333, 333, 334, 334, 334, 334, 335,
        335, 335, 336, 336, 336, 336, 337, 337, 337, 337, 338, 338, 338, 339, 339,
        339, 339, 339, 339, 340, 340, 340, 340, 341, 341, 342, 342, 342, 342, 343,
        343, 343, 344, 344, 344, 344, 345, 345, 345, 345, 346, 346, 346, 346, 347,
        347, 347, 347, 348, 348, 348, 348, 349, 349, 349, 349, 349, 349, 350, 350,
        350, 350, 351, 351, 352, 352, 352, 352, 353, 353, 353, 353, 354, 354, 354,
        354, 355, 355, 355, 355, 356, 356, 356, 356, 357, 357, 357, 357, 358, 358,
        358, 358, 359, 359, 359, 359, 359, 359, 359, 360, 360, 360, 360, 361, 361,
        362, 362, 362, 362, 363, 363, 363, 363, 364, 364, 364, 364, 365, 365, 365,
        365, 366, 366, 366, 366, 366, 367, 367, 367, 367, 368, 368, 368, 368, 369,
        369, 369, 369, 369, 369, 370, 370, 370, 370, 370, 371, 371, 372, 372, 372,
        372, 373, 373, 373, 373, 374, 374, 374, 374, 374, 375, 375, 375, 375, 376,
        376, 376, 376, 377, 377, 377, 377, 378, 378, 378, 378, 378, 379, 379, 379,
        379, 379, 379, 380, 380, 380, 380, 381, 381, 381, 382, 382, 382, 382, 383,
        383, 383, 383, 384, 384, 384, 384, 385, 385, 385, 385, 385, 386, 386, 386,
        386, 387, 387, 387, 387, 388, 388, 388, 388, 388, 389, 389, 389, 389, 389,
        389, 390, 390, 390, 390, 391, 391, 392, 392, 392, 392, 392, 393, 393, 393,
        393, 394, 394, 394, 394, 395, 395, 395, 395, 396, 396, 396, 396, 396, 397,
        397, 397, 397, 398, 398, 398, 398, 399, 399, 399, 399, 399, 399, 400, 400,
        400, 400, 400, 401, 401, 402, 402, 402, 402, 403, 403, 403, 403, 404, 404,
        404, 404, 405, 405, 405, 405, 406, 406, 406, 406, 406, 407, 407, 407, 407,
        408, 408, 408, 408, 409, 409, 409, 409, 409, 409, 410, 410, 410, 410, 411,
        411, 412, 412, 412, 412, 413, 413, 413, 413, 414, 414, 414, 414, 415, 415,
        415, 415, 416, 416, 416, 416, 417, 417, 417, 417, 418, 418, 418, 418, 419,
        419, 419, 419, 419, 419, 420, 420, 420, 420, 421, 421, 422, 422, 422, 422,
        423, 423, 423, 423, 424, 424, 424, 425, 425, 425, 425, 426, 426, 426, 426,
        427, 427, 427, 427, 428, 428, 428, 429, 429, 429, 429, 429, 429, 430, 430,
        430, 430, 431, 431, 432, 432, 432, 433, 433, 433, 433, 434, 434, 434, 435,
        435, 435, 435, 436, 436, 436, 436, 437, 437, 437, 438, 438, 438, 438, 439,
        439, 439, 439, 439, 440, 440, 440, 441, 441, 442, 442, 442, 443, 443, 443,
        443, 444, 444, 444, 445, 445, 445, 446, 446, 446, 446, 447, 447, 447, 448,
        448, 448, 449, 449, 449, 449, 449, 450, 450, 450, 451, 451, 452, 452, 452,
        453, 453, 453, 454, 454, 454, 455, 455, 455, 456, 456, 456, 457, 457, 457,
        458, 458, 458, 459, 459, 459, 459, 460, 460, 460, 461, 461, 462, 462, 462,
        463, 463, 463, 464, 464, 465, 465, 465, 466, 466, 466, 467, 467, 467, 468,
        468, 469, 469, 469, 469, 470, 470, 470, 471, 472, 472, 472, 473, 473, 474,
        474, 474, 475, 475, 476, 476, 476, 477, 477, 478, 478, 478, 479, 479, 479,
        480, 480, 480, 481, 482, 482, 483, 483, 484, 484, 484, 485, 485, 486, 486,
        487, 487, 488, 488, 488, 489, 489, 489, 490, 490, 491, 492, 492, 493, 493,
        494, 494, 495, 495, 496, 496, 497, 497, 498, 498, 499, 499, 499, 500, 501,
        502, 502, 503, 503, 504, 504, 505, 505, 506, 507, 507, 508, 508, 509, 509,
        510, 510, 511, 512, 513, 513, 514, 515, 515, 516, 517, 517, 518, 519, 519,
        519, 520, 521, 522, 523, 524, 524, 525, 526, 526, 527, 528, 529, 529, 530,
        531, 532, 533, 534, 535, 535, 536, 537, 538, 539, 539, 540, 542, 543, 544,
        545, 546, 547, 548, 549, 549, 550, 552, 553, 554, 555, 556, 558, 559, 559,
        561, 562, 564, 565, 566, 568, 569, 570, 572, 574, 575, 577, 578, 579, 582,
        583, 585, 587, 589, 590, 593, 595, 597, 599, 602, 604, 607, 609, 612, 615,
        618, 620, 624, 628, 631, 635, 639, 644, 649, 654, 659, 666, 673, 680, 690,
        700, 714 };

    private final String mAutoFixShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform sampler2D tex_sampler_2;\n" +
            "uniform float scale;\n" +
            "uniform float shift_scale;\n" +
            "uniform float hist_offset;\n" +
            "uniform float hist_scale;\n" +
            "uniform float density_offset;\n" +
            "uniform float density_scale;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  const vec3 weights = vec3(0.33333, 0.33333, 0.33333);\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  float energy = dot(color.rgb, weights);\n" +
            "  float mask_value = energy - 0.5;\n" +
            "  float alpha;\n" +
            "  if (mask_value > 0.0) {\n" +
            "    alpha = (pow(2.0 * mask_value, 1.5) - 1.0) * scale + 1.0;\n" +
            "  } else { \n" +
            "    alpha = (pow(2.0 * mask_value, 2.0) - 1.0) * scale + 1.0;\n" +
            "  }\n" +
            "  float index = energy * hist_scale + hist_offset;\n" +
            "  vec4 temp = texture2D(tex_sampler_1, vec2(index, 0.5));\n" +
            "  float value = temp.g + temp.r * shift_scale;\n" +
            "  index = value * density_scale + density_offset;\n" +
            "  temp = texture2D(tex_sampler_2, vec2(index, 0.5));\n" +
            "  value = temp.g + temp.r * shift_scale;\n" +
            "  float dst_energy = energy * alpha + value * (1.0 - alpha);\n" +
            "  float max_energy = energy / max(color.r, max(color.g, color.b));\n" +
            "  if (dst_energy > max_energy) {\n" +
            "    dst_energy = max_energy;\n" +
            "  }\n" +
            "  if (energy == 0.0) {\n" +
            "    gl_FragColor = color;\n" +
            "  } else {\n" +
            "    gl_FragColor = vec4(color.rgb * dst_energy / energy, color.a);\n" +
            "  }\n" +
            "}\n";

    private Program mShaderProgram;
    private Program mNativeProgram;

    private int mWidth = 0;
    private int mHeight = 0;
    private int mTarget = FrameFormat.TARGET_UNSPECIFIED;

    private Frame mHistFrame;
    private Frame mDensityFrame;

    public AutoFixFilter(String name) {
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
                ShaderProgram shaderProgram = new ShaderProgram(context, mAutoFixShader);
                shaderProgram.setMaximumTileSize(mTileSize);
                mShaderProgram = shaderProgram;
                break;

            default:
                throw new RuntimeException("Filter Sharpen does not support frames of " +
                    "target " + target + "!");
        }
        mTarget = target;
    }

    private void initParameters() {
        mShaderProgram.setHostValue("shift_scale", 1.0f / 256f);
        mShaderProgram.setHostValue("hist_offset", 0.5f / 766f);
        mShaderProgram.setHostValue("hist_scale", 765f / 766f);
        mShaderProgram.setHostValue("density_offset", 0.5f / 1024f);
        mShaderProgram.setHostValue("density_scale", 1023f / 1024f);
        mShaderProgram.setHostValue("scale", mScale);
    }

    @Override
    protected void prepare(FilterContext context) {
        int densityDim = 1024;
        int histDim = 255 * 3 + 1;
        long precision = (256l * 256l - 1l);

        int[] densityTable = new int[densityDim];
        for (int i = 0; i < densityDim; ++i) {
            long temp = normal_cdf[i] * precision / histDim;
            densityTable[i] = (int) temp;
        }

        FrameFormat densityFormat = ImageFormat.create(densityDim, 1,
                                                       ImageFormat.COLORSPACE_RGBA,
                                                       FrameFormat.TARGET_GPU);
        mDensityFrame = context.getFrameManager().newFrame(densityFormat);
        mDensityFrame.setInts(densityTable);
    }

    @Override
    public void tearDown(FilterContext context) {
        if (mDensityFrame != null) {
            mDensityFrame.release();
            mDensityFrame = null;
        }

        if (mHistFrame != null) {
            mHistFrame.release();
            mHistFrame = null;
        }
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        if (mShaderProgram != null) {
            mShaderProgram.setHostValue("scale", mScale);
        }
    }

    @Override
    public void process(FilterContext context) {
        // Get input frame
        Frame input = pullInput("image");
        FrameFormat inputFormat = input.getFormat();

        // Create program if not created already
        if (mShaderProgram == null || inputFormat.getTarget() != mTarget) {
            initProgram(context, inputFormat.getTarget());
            initParameters();
        }

        // Check if the frame size has changed
        if (inputFormat.getWidth() != mWidth || inputFormat.getHeight() != mHeight) {
            mWidth = inputFormat.getWidth();
            mHeight = inputFormat.getHeight();
            createHistogramFrame(context, mWidth, mHeight, input.getInts());
        }

        // Create output frame
        Frame output = context.getFrameManager().newFrame(inputFormat);

        // Process
        Frame[] inputs = {input, mHistFrame, mDensityFrame};
        mShaderProgram.process(inputs, output);

        // Push output
        pushOutput("image", output);

        // Release pushed frame
        output.release();
    }

    private void createHistogramFrame(FilterContext context, int width, int height, int[] data) {
        int histDims = 255 * 3 + 1;
        int[] histArray = new int[histDims];

        float border_thickness_ratio = 0.05f;
        int y_border_thickness = (int) (height * border_thickness_ratio);
        int x_border_thickness = (int) (width * border_thickness_ratio);
        int pixels = (width - 2 * x_border_thickness) * (height - 2 * y_border_thickness);

        float count = 0f;
        for (int y = y_border_thickness; y < height - y_border_thickness; ++y) {
            for (int x = x_border_thickness; x < width - x_border_thickness; ++x) {
                int index = y * width + x;
                int energy = (data[index] & 0xFF) + ((data[index] >> 8) & 0xFF) +
                    ((data[index] >> 16) & 0xFF);
                histArray[energy] ++;
            }
        }

        for (int i = 1; i < histDims; i++) {
            histArray[i] += histArray[i-1];
        }

        for (int i = 0; i < histDims; i++) {
            long temp = (256 * 256 - 1l) * histArray[i] / pixels;
            histArray[i] =  (int) temp;
        }

        FrameFormat shaderHistFormat = ImageFormat.create(histDims, 1,
                                                          ImageFormat.COLORSPACE_RGBA,
                                                          FrameFormat.TARGET_GPU);
        if (mHistFrame != null)
            mHistFrame.release();

        mHistFrame = context.getFrameManager().newFrame(shaderHistFormat);
        mHistFrame.setInts(histArray);
    }
}
