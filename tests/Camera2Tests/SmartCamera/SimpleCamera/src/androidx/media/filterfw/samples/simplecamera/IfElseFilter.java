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
import androidx.media.filterfw.FrameBuffer2D;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;


public class IfElseFilter extends Filter {

    private static final String TAG = "IfElseFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

    public IfElseFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        FrameType videoIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        FrameType imageOut = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.WRITE_GPU);

        return new Signature().addInputPort("falseResult", Signature.PORT_REQUIRED, imageIn)
                .addInputPort("trueResult", Signature.PORT_REQUIRED, videoIn)
                .addInputPort("condition", Signature.PORT_REQUIRED, FrameType.single(boolean.class))
                .addOutputPort("output", Signature.PORT_REQUIRED, imageOut)
                .disallowOtherPorts();
    }

    @Override
    protected void onProcess() {
        OutputPort outPort = getConnectedOutputPort("output");
        FrameImage2D trueFrame = getConnectedInputPort("trueResult").pullFrame().asFrameImage2D();
        FrameImage2D falseFrame = getConnectedInputPort("falseResult").pullFrame().asFrameImage2D();
        FrameValue boolFrameValue = getConnectedInputPort("condition").pullFrame().asFrameValue();
        boolean condition = (Boolean) boolFrameValue.getValue();
        FrameBuffer2D outputFrame;
        // If the condition is true, then we want to use the camera, else use the gallery
        if (condition) {
            outputFrame = trueFrame;
        } else {
            outputFrame = falseFrame;
        }
        outPort.pushFrame(outputFrame);

    }

}
