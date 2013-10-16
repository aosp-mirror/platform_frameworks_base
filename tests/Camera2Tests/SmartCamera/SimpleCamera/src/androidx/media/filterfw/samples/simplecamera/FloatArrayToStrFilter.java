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
// Takes in an array, returns the size of the array

package androidx.media.filterfw.samples.simplecamera;

import android.util.Log;
import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

import java.lang.reflect.Array;
import java.util.Arrays;

public class FloatArrayToStrFilter extends Filter {

    private static final String TAG = "FloatArrayToStrFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

    /**
     * @param context
     * @param name
     */
    public FloatArrayToStrFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType floatType = FrameType.array(float.class);

        return new Signature()
                .addInputPort("array", Signature.PORT_REQUIRED, floatType)
                .addOutputPort("string", Signature.PORT_REQUIRED, FrameType.single(String.class))
                .disallowOtherPorts();
    }

    /**
     * @see androidx.media.filterfw.Filter#onProcess()
     */
    @Override
    protected void onProcess() {
        FrameValue arrayFrame = getConnectedInputPort("array").pullFrame().asFrameValues();
        float[] array = (float[]) arrayFrame.getValue();
        String outstr = Arrays.toString(array);

        OutputPort outPort = getConnectedOutputPort("string");
        FrameValue stringFrame = outPort.fetchAvailableFrame(null).asFrameValue();
        stringFrame.setValue(outstr);
        outPort.pushFrame(stringFrame);

    }
}
