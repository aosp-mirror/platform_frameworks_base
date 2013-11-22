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

package androidx.media.filterpacks.image;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.ImageShader;
import androidx.media.filterfw.InputPort;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

public class BrightnessFilter extends Filter {

    private float mBrightness = 1.0f;
    private ImageShader mShader;

    private static final String mBrightnessShader =
            "precision mediump float;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform float brightness;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  if (brightness < 0.5) {\n" +
            "    gl_FragColor = color * (2.0 * brightness);\n" +
            "  } else {\n" +
            "    vec4 diff = 1.0 - color;\n" +
            "    gl_FragColor = color + diff * (2.0 * (brightness - 0.5));\n" +
            "  }\n" +
            "}\n";


    public BrightnessFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        FrameType imageOut = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.WRITE_GPU);
        return new Signature()
            .addInputPort("image", Signature.PORT_REQUIRED, imageIn)
            .addInputPort("brightness", Signature.PORT_OPTIONAL, FrameType.single(float.class))
            .addOutputPort("image", Signature.PORT_REQUIRED, imageOut)
            .disallowOtherPorts();
    }

    @Override
    public void onInputPortOpen(InputPort port) {
        if (port.getName().equals("brightness")) {
            port.bindToFieldNamed("mBrightness");
            port.setAutoPullEnabled(true);
        }
    }

    @Override
    protected void onPrepare() {
        mShader = new ImageShader(mBrightnessShader);
    }

    @Override
    protected void onProcess() {
        OutputPort outPort = getConnectedOutputPort("image");
        FrameImage2D inputImage = getConnectedInputPort("image").pullFrame().asFrameImage2D();
        int[] dim = inputImage.getDimensions();
        FrameImage2D outputImage = outPort.fetchAvailableFrame(dim).asFrameImage2D();
        mShader.setUniformValue("brightness", mBrightness);
        mShader.process(inputImage, outputImage);
        outPort.pushFrame(outputImage);
    }
}

