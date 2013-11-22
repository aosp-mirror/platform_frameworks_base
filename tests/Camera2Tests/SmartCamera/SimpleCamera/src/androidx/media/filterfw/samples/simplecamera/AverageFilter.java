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
// Takes sharpness scores in RT and averages them over time

package androidx.media.filterfw.samples.simplecamera;

import android.util.Log;
import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

public class AverageFilter extends Filter {

    private static final String TAG = "AverageFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

    private static final int NUM_FRAMES = 5;
    private int counter = 0;
    private float[] temp = new float[NUM_FRAMES];

    /**
     * @param context
     * @param name
     */
    public AverageFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType floatT = FrameType.single(float.class);
        return new Signature()
        .addInputPort("sharpness", Signature.PORT_REQUIRED, floatT)
        .addOutputPort("avg", Signature.PORT_REQUIRED, floatT)
        .disallowOtherPorts();
    }

    @Override
    protected void onProcess() {
        FrameValue inFrameValue = getConnectedInputPort("sharpness").pullFrame().asFrameValue();
        if (counter < NUM_FRAMES && counter >= 0) {
            temp[counter] = ((Float)inFrameValue.getValue()).floatValue();
        }

        counter = (counter + 1) % NUM_FRAMES;

        float output = (temp[0] + temp[1] + temp[2] + temp[3] + temp[4]) / NUM_FRAMES;
        if (mLogVerbose) Log.v(TAG, "Avg= " + output + "temp1= " + temp[0] + "temp2= " +
                temp[1] + "temp3= " + temp[2] + "temp4=" + temp[3] + "temp5=" + temp[4]);

        OutputPort outPort = getConnectedOutputPort("avg");
        FrameValue outFrame = outPort.fetchAvailableFrame(null).asFrameValue();
        outFrame.setValue(output);
        outPort.pushFrame(outFrame);
    }
}
