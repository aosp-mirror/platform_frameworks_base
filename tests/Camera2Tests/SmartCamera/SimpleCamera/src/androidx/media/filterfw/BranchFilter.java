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

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.InputPort;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

public final class BranchFilter extends Filter {

    private boolean mSynchronized = true;

    public BranchFilter(MffContext context, String name) {
        super(context, name);
    }

    public BranchFilter(MffContext context, String name, boolean synced) {
        super(context, name);
        mSynchronized = synced;
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addInputPort("input", Signature.PORT_REQUIRED, FrameType.any())
            .addInputPort("synchronized", Signature.PORT_OPTIONAL,FrameType.single(boolean.class))
            .disallowOtherInputs();
    }

    @Override
    public void onInputPortOpen(InputPort port) {
        if (port.getName().equals("input")) {
            for (OutputPort outputPort : getConnectedOutputPorts()) {
                port.attachToOutputPort(outputPort);
            }
        } else if (port.getName().equals("synchronized")) {
            port.bindToFieldNamed("mSynchronized");
            port.setAutoPullEnabled(true);
        }
    }

    @Override
    protected void onOpen() {
        updateSynchronization();
    }

    @Override
    protected void onProcess() {
        Frame inputFrame = getConnectedInputPort("input").pullFrame();
        for (OutputPort outputPort : getConnectedOutputPorts()) {
            if (outputPort.isAvailable()) {
                outputPort.pushFrame(inputFrame);
            }
        }
    }

    private void updateSynchronization() {
        if (mSynchronized) {
            for (OutputPort port : getConnectedOutputPorts()) {
                port.setWaitsUntilAvailable(true);
            }
        } else {
            for (OutputPort port : getConnectedOutputPorts()) {
                port.setWaitsUntilAvailable(false);
            }
        }
    }

}

