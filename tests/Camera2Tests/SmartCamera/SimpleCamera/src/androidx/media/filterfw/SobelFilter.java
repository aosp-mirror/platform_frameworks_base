/*
 * Copyright 2013 The Android Open Source Project
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

package androidx.media.filterpacks.image;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.ImageShader;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

import java.nio.ByteBuffer;

public class SobelFilter extends Filter {

    private static final String mGradientXSource =
              "precision mediump float;\n"
            + "uniform sampler2D tex_sampler_0;\n"
            + "uniform vec2 pix;\n"
            + "varying vec2 v_texcoord;\n"
            + "void main() {\n"
            + "  vec4 a1 = -1.0 * texture2D(tex_sampler_0, v_texcoord + vec2(-pix.x, -pix.y));\n"
            + "  vec4 a2 = -2.0 * texture2D(tex_sampler_0, v_texcoord + vec2(-pix.x, 0.0));\n"
            + "  vec4 a3 = -1.0 * texture2D(tex_sampler_0, v_texcoord + vec2(-pix.x, +pix.y));\n"
            + "  vec4 b1 = +1.0 * texture2D(tex_sampler_0, v_texcoord + vec2(+pix.x, -pix.y));\n"
            + "  vec4 b2 = +2.0 * texture2D(tex_sampler_0, v_texcoord + vec2(+pix.x, 0.0));\n"
            + "  vec4 b3 = +1.0 * texture2D(tex_sampler_0, v_texcoord + vec2(+pix.x, +pix.y));\n"
            + "  gl_FragColor = 0.5 + (a1 + a2 + a3 + b1 + b2 + b3) / 8.0;\n"
            + "}\n";

    private static final String mGradientYSource =
              "precision mediump float;\n"
            + "uniform sampler2D tex_sampler_0;\n"
            + "uniform vec2 pix;\n"
            + "varying vec2 v_texcoord;\n"
            + "void main() {\n"
            + "  vec4 a1 = -1.0 * texture2D(tex_sampler_0, v_texcoord + vec2(-pix.x, -pix.y));\n"
            + "  vec4 a2 = -2.0 * texture2D(tex_sampler_0, v_texcoord + vec2(0.0,    -pix.y));\n"
            + "  vec4 a3 = -1.0 * texture2D(tex_sampler_0, v_texcoord + vec2(+pix.x, -pix.y));\n"
            + "  vec4 b1 = +1.0 * texture2D(tex_sampler_0, v_texcoord + vec2(-pix.x, +pix.y));\n"
            + "  vec4 b2 = +2.0 * texture2D(tex_sampler_0, v_texcoord + vec2(0.0,    +pix.y));\n"
            + "  vec4 b3 = +1.0 * texture2D(tex_sampler_0, v_texcoord + vec2(+pix.x, +pix.y));\n"
            + "  gl_FragColor = 0.5 + (a1 + a2 + a3 + b1 + b2 + b3) / 8.0;\n"
            + "}\n";

    private static final String mMagnitudeSource =
            "precision mediump float;\n"
          + "uniform sampler2D tex_sampler_0;\n"
          + "uniform sampler2D tex_sampler_1;\n"
          + "varying vec2 v_texcoord;\n"
          + "void main() {\n"
          + "  vec4 gx = 2.0 * texture2D(tex_sampler_0, v_texcoord) - 1.0;\n"
          + "  vec4 gy = 2.0 * texture2D(tex_sampler_1, v_texcoord) - 1.0;\n"
          + "  gl_FragColor = vec4(sqrt(gx.rgb * gx.rgb + gy.rgb * gy.rgb), 1.0);\n"
          + "}\n";

    private static final String mDirectionSource =
            "precision mediump float;\n"
          + "uniform sampler2D tex_sampler_0;\n"
          + "uniform sampler2D tex_sampler_1;\n"
          + "varying vec2 v_texcoord;\n"
          + "void main() {\n"
          + "  vec4 gy = 2.0 * texture2D(tex_sampler_1, v_texcoord) - 1.0;\n"
          + "  vec4 gx = 2.0 * texture2D(tex_sampler_0, v_texcoord) - 1.0;\n"
          + "  gl_FragColor = vec4((atan(gy.rgb, gx.rgb) + 3.14) / (2.0 * 3.14), 1.0);\n"
          + "}\n";

    private ImageShader mGradientXShader;
    private ImageShader mGradientYShader;
    private ImageShader mMagnitudeShader;
    private ImageShader mDirectionShader;

    private FrameType mImageType;

    public SobelFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        // TODO: we will address the issue of READ_GPU / WRITE_GPU when using CPU filters later.
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        FrameType imageOut = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.WRITE_GPU);
        return new Signature().addInputPort("image", Signature.PORT_REQUIRED, imageIn)
                .addOutputPort("direction", Signature.PORT_OPTIONAL, imageOut)
                .addOutputPort("magnitude", Signature.PORT_OPTIONAL, imageOut).disallowOtherPorts();
    }

    @Override
    protected void onPrepare() {
        if (isOpenGLSupported()) {
            mGradientXShader = new ImageShader(mGradientXSource);
            mGradientYShader = new ImageShader(mGradientYSource);
            mMagnitudeShader = new ImageShader(mMagnitudeSource);
            mDirectionShader = new ImageShader(mDirectionSource);
            mImageType = FrameType.image2D(
                    FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU | FrameType.WRITE_GPU);
        }
    }

    @Override
    protected void onProcess() {
        OutputPort magnitudePort = getConnectedOutputPort("magnitude");
        OutputPort directionPort = getConnectedOutputPort("direction");
        FrameImage2D inputImage = getConnectedInputPort("image").pullFrame().asFrameImage2D();
        int[] inputDims = inputImage.getDimensions();

        FrameImage2D magImage = (magnitudePort != null) ?
                magnitudePort.fetchAvailableFrame(inputDims).asFrameImage2D() : null;
        FrameImage2D dirImage = (directionPort != null) ?
                directionPort.fetchAvailableFrame(inputDims).asFrameImage2D() : null;
        if (isOpenGLSupported()) {
            FrameImage2D gxFrame = Frame.create(mImageType, inputDims).asFrameImage2D();
            FrameImage2D gyFrame = Frame.create(mImageType, inputDims).asFrameImage2D();
            mGradientXShader.setUniformValue("pix", new float[] {1f/inputDims[0], 1f/inputDims[1]});
            mGradientYShader.setUniformValue("pix", new float[] {1f/inputDims[0], 1f/inputDims[1]});
            mGradientXShader.process(inputImage, gxFrame);
            mGradientYShader.process(inputImage, gyFrame);
            FrameImage2D[] gradientFrames = new FrameImage2D[] { gxFrame, gyFrame };
            if (magnitudePort != null) {
                mMagnitudeShader.processMulti(gradientFrames, magImage);
            }
            if (directionPort != null) {
                mDirectionShader.processMulti(gradientFrames, dirImage);
            }
            gxFrame.release();
            gyFrame.release();
        } else {
            ByteBuffer inputBuffer  = inputImage.lockBytes(Frame.MODE_READ);
            ByteBuffer magBuffer  = (magImage != null) ?
                    magImage.lockBytes(Frame.MODE_WRITE) : null;
            ByteBuffer dirBuffer  = (dirImage != null) ?
                    dirImage.lockBytes(Frame.MODE_WRITE) : null;
            sobelOperator(inputImage.getWidth(), inputImage.getHeight(),
                    inputBuffer, magBuffer, dirBuffer);
            inputImage.unlock();
            if (magImage != null) {
                magImage.unlock();
            }
            if (dirImage != null) {
                dirImage.unlock();
            }
        }
        if (magImage != null) {
            magnitudePort.pushFrame(magImage);
        }
        if (dirImage != null) {
            directionPort.pushFrame(dirImage);
        }
    }

    private static native boolean sobelOperator(int width, int height,
            ByteBuffer imageBuffer, ByteBuffer magBuffer, ByteBuffer dirBudder);

    static {
        System.loadLibrary("smartcamera_jni");
    }
}
