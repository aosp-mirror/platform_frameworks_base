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

package androidx.media.filterpacks.base;

import androidx.media.filterfw.*;

// TODO: Rename back to ValueSource? Seems to make more sense even if we use it as a Variable
// in some contexts.
public final class VariableSource extends Filter {

    private Object mValue = null;
    private OutputPort mOutputPort = null;

    public VariableSource(MffContext context, String name) {
        super(context, name);
    }

    public synchronized void setValue(Object value) {
        mValue = value;
    }

    public synchronized Object getValue() {
        return mValue;
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addOutputPort("value", Signature.PORT_REQUIRED, FrameType.single())
            .disallowOtherPorts();
    }

    @Override
    protected void onPrepare() {
        mOutputPort = getConnectedOutputPort("value");
    }

    @Override
    protected synchronized void onProcess() {
        FrameValue frame = mOutputPort.fetchAvailableFrame(null).asFrameValue();
        frame.setValue(mValue);
        mOutputPort.pushFrame(frame);
    }

}

