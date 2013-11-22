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

package androidx.media.filterpacks.transform;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.ImageShader;
import androidx.media.filterfw.InputPort;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;
import androidx.media.filterfw.geometry.Quad;

public class RotateFilter extends Filter {

    private Quad mSourceRect = Quad.fromRect(0f, 0f, 1f, 1f);
    private float mRotateAngle = 0;
    private ImageShader mShader;

    public RotateFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        FrameType imageOut = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.WRITE_GPU);
        return new Signature()
            .addInputPort("image", Signature.PORT_REQUIRED, imageIn)
            .addInputPort("rotateAngle", Signature.PORT_REQUIRED, FrameType.single(float.class))
            .addInputPort("sourceRect", Signature.PORT_OPTIONAL, FrameType.single(Quad.class))
            .addOutputPort("image", Signature.PORT_REQUIRED, imageOut)
            .disallowOtherPorts();
    }

    @Override
    public void onInputPortOpen(InputPort port) {
        if (port.getName().equals("rotateAngle")) {
            port.bindToFieldNamed("mRotateAngle");
            port.setAutoPullEnabled(true);
        } else if (port.getName().equals("sourceRect")) {
            port.bindToFieldNamed("mSourceRect");
            port.setAutoPullEnabled(true);
        }
    }

    @Override
    protected void onPrepare() {
        mShader = ImageShader.createIdentity();
    }

    @Override
    protected void onProcess() {
        OutputPort outPort = getConnectedOutputPort("image");
        FrameImage2D inputImage = getConnectedInputPort("image").pullFrame().asFrameImage2D();
        int[] inDims = inputImage.getDimensions();

        FrameImage2D outputImage = outPort.fetchAvailableFrame(inDims).asFrameImage2D();
        mShader.setSourceQuad(mSourceRect);
        Quad targetQuad = mSourceRect.rotated((float) (mRotateAngle / 180 * Math.PI));
        mShader.setTargetQuad(targetQuad);
        mShader.process(inputImage, outputImage);
        outPort.pushFrame(outputImage);
    }
}
