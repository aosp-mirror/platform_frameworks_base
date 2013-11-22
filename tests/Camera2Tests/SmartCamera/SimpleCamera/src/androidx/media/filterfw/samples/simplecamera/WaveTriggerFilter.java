/*
 * Copyright (C) 2012 The Android Open Source Project
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

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.InputPort;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

// The Filter is used to generate the camera snap effect.
// The effect is to give the image a sudden white appearance.
public final class WaveTriggerFilter extends Filter {

    private boolean mTrigger = false;
    private boolean mInWaveMode = false;
    private float mTime = 0f;

    public WaveTriggerFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addOutputPort("value", Signature.PORT_REQUIRED, FrameType.single())
            .disallowOtherPorts();
    }

    public synchronized void trigger() {
        mTrigger = true;
    }

    @Override
    public void onInputPortOpen(InputPort port) {
        if (port.getName().equals("trigger")) {
            port.bindToFieldNamed("mTrigger");
            port.setAutoPullEnabled(true);
        }
    }

    @Override
    protected synchronized void onProcess() {
        // Check if we were triggered
        if (mTrigger) {
            mInWaveMode = true;
            mTrigger = false;
            mTime = 0.5f;
        }

        // Calculate output value
        float value = 0.5f;
        if (mInWaveMode) {
            value = -Math.abs(mTime - 1f) + 1f;
            mTime += 0.2f;
            if (mTime >= 2f) {
                mInWaveMode = false;
            }
        }

        // Push Value
        OutputPort outPort = getConnectedOutputPort("value");
        FrameValue frame = outPort.fetchAvailableFrame(null).asFrameValue();
        frame.setValue(value);
        outPort.pushFrame(frame);
    }
}
