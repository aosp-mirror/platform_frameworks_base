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

package androidx.media.filterfw.samples.simplecamera;

import android.util.Log;
import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

import java.nio.ByteBuffer;

public class ContrastRatioFilter extends Filter {

    private static final String TAG = "ContrastRatioFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

    public ContrastRatioFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_CPU);
        FrameType floatT = FrameType.single(float.class);
        return new Signature().addInputPort("image", Signature.PORT_REQUIRED, imageIn)
                .addOutputPort("contrastRatingToGoodness", Signature.PORT_REQUIRED, floatT)
                .disallowOtherPorts();

    }

    @Override
    protected void onProcess() {
        FrameImage2D inputImage = getConnectedInputPort("image").pullFrame().asFrameImage2D();

        float contrastRatio;
        ByteBuffer inputBuffer  = inputImage.lockBytes(Frame.MODE_READ);

        contrastRatio = contrastOperator(inputImage.getWidth(), inputImage.getHeight(),
                    inputBuffer);

        inputImage.unlock();

        if (mLogVerbose) Log.v(TAG, "contrastRatio: " + contrastRatio);

        OutputPort contrastToGoodnessPort = getConnectedOutputPort("contrastRatingToGoodness");
        FrameValue contrastOutFrame2 =
                contrastToGoodnessPort.fetchAvailableFrame(null).asFrameValue();
        contrastOutFrame2.setValue(contrastRatio);
        contrastToGoodnessPort.pushFrame(contrastOutFrame2);


    }

    private static native float contrastOperator(int width, int height, ByteBuffer imageBuffer);

    static {
        System.loadLibrary("smartcamera_jni");
    }

}
