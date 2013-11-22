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

package androidx.media.filterpacks.text;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

public class ToStringFilter extends Filter {

    public ToStringFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addInputPort("object", Signature.PORT_REQUIRED, FrameType.single())
            .addOutputPort("string", Signature.PORT_REQUIRED, FrameType.single(String.class))
            .disallowOtherPorts();
    }

    @Override
    protected void onProcess() {
        FrameValue objectFrame = getConnectedInputPort("object").pullFrame().asFrameValue();
        String outStr = objectFrame.getValue().toString();
        OutputPort outPort = getConnectedOutputPort("string");
        FrameValue stringFrame = outPort.fetchAvailableFrame(null).asFrameValue();
        stringFrame.setValue(outStr);
        outPort.pushFrame(stringFrame);
    }

}

