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

public class FloatArrayToSizeFilter extends Filter {

    private static final String TAG = "FloatArrayToSizeFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);
    /**
     * @param context
     * @param name
     */
    public FloatArrayToSizeFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType intT = FrameType.single(int.class);
        FrameType floatType = FrameType.array(float.class);

        return new Signature()
                .addInputPort("array", Signature.PORT_REQUIRED, floatType)
                .addOutputPort("size", Signature.PORT_REQUIRED, intT)
                .disallowOtherPorts();
    }

    /**
     * @see androidx.media.filterfw.Filter#onProcess()
     */
    @Override
    protected void onProcess() {
        FrameValue arrayFrame = getConnectedInputPort("array").pullFrame().asFrameValues();
        Object array = arrayFrame.getValue();
        int size = Array.getLength(array);

        OutputPort outPort = getConnectedOutputPort("size");
        FrameValue sizeFrame = outPort.fetchAvailableFrame(null).asFrameValue();
        sizeFrame.setValue(size);
        outPort.pushFrame(sizeFrame);

    }
}
