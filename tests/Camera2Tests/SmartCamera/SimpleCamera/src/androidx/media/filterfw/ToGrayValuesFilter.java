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
import androidx.media.filterfw.FrameBuffer2D;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.ImageShader;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.RenderTarget;
import androidx.media.filterfw.Signature;
import androidx.media.filterfw.geometry.Quad;

import java.nio.ByteBuffer;

public class ToGrayValuesFilter extends Filter {

    private final static String mGrayPackFragment =
        "precision mediump float;\n" +
        "const vec4 coeff_y = vec4(0.299, 0.587, 0.114, 0);\n" +
        "uniform sampler2D tex_sampler_0;\n" +
        "uniform float pix_stride;\n" +
        "varying vec2 v_texcoord;\n" +
        "void main() {\n" +
        "  for (int i = 0; i < 4; i++) {\n" +
        // Here is an example showing how this works:
        // Assuming the input texture is 1x4 while the output texture is 1x1
        // the coordinates of the 4 input pixels will be:
        // { (0.125, 0.5), (0.375, 0.5), (0.625, 0.5), (0.875, 0.5) }
        // and the coordinates of the 1 output pixels will be:
        // { (0.5, 0.5) }
        // the equation below locates the 4 input pixels from the coordinate of the output pixel
        "    vec4 p = texture2D(tex_sampler_0,\n" +
        "                       v_texcoord + vec2(pix_stride * (float(i) - 1.5), 0.0));\n" +
        "    gl_FragColor[i] = dot(p, coeff_y);\n" +
        "  }\n" +
        "}\n";

    private ImageShader mShader;

    private FrameType mImageInType;

    public ToGrayValuesFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        mImageInType = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        FrameType imageOut = FrameType.buffer2D(FrameType.ELEMENT_INT8);
        return new Signature()
            .addInputPort("image", Signature.PORT_REQUIRED, mImageInType)
            .addOutputPort("image", Signature.PORT_REQUIRED, imageOut)
            .disallowOtherPorts();
    }

    @Override
    protected void onPrepare() {
        if (isOpenGLSupported()) {
            mShader = new ImageShader(mGrayPackFragment);
        }
    }

    @Override
    protected void onProcess() {
        OutputPort outPort = getConnectedOutputPort("image");
        FrameImage2D inputImage = getConnectedInputPort("image").pullFrame().asFrameImage2D();
        int[] dim = inputImage.getDimensions();
        FrameBuffer2D outputFrame;
        ByteBuffer grayBuffer;

        if (isOpenGLSupported()) {
            // crop out the portion of inputImage that will be used to generate outputFrame.
            int modular = dim[0] % 4;
            int[] outDim = new int[] {dim[0] - modular, dim[1]};
            outputFrame = outPort.fetchAvailableFrame(outDim).asFrameBuffer2D();
            grayBuffer = outputFrame.lockBytes(Frame.MODE_WRITE);

            int[] targetDims = new int[] { outDim[0] / 4, outDim[1] };
            FrameImage2D targetFrame = Frame.create(mImageInType, targetDims).asFrameImage2D();
            mShader.setSourceQuad(Quad.fromRect(0f, 0f, ((float)outDim[0])/dim[0], 1f));
            mShader.setUniformValue("pix_stride", 1f / outDim[0]);
            mShader.process(inputImage, targetFrame);
            RenderTarget grayTarget = targetFrame.lockRenderTarget();
            grayTarget.readPixelData(grayBuffer, targetDims[0], targetDims[1]);
            targetFrame.unlock();
            targetFrame.release();
        } else {
            outputFrame = outPort.fetchAvailableFrame(dim).asFrameBuffer2D();
            grayBuffer = outputFrame.lockBytes(Frame.MODE_WRITE);
            ByteBuffer inputBuffer  = inputImage.lockBytes(Frame.MODE_READ);
            if (!toGrayValues(inputBuffer, grayBuffer)) {
                throw new RuntimeException(
                        "Native implementation encountered an error during processing!");
            }
            inputImage.unlock();
        }
        outputFrame.unlock();
        outPort.pushFrame(outputFrame);
    }

    private static native boolean toGrayValues(ByteBuffer imageBuffer, ByteBuffer grayBuffer);

    static {
        System.loadLibrary("smartcamera_jni");
    }
}
