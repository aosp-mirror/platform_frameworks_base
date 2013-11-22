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

public final class FrameSlotSource extends SlotFilter {

    public FrameSlotSource(MffContext context, String name, String slotName) {
        super(context, name, slotName);
    }

    @Override
    public Signature getSignature() {
        // TODO: It would be nice if we could return the slot type here. Not currently possible
        // as getSignature() is typically called before a FrameManager and its slots are setup.
        return new Signature()
            .addOutputPort("frame", Signature.PORT_REQUIRED, FrameType.any())
            .disallowOtherPorts();
    }

    @Override
    protected boolean canSchedule() {
        return super.canSchedule() && slotHasFrame();
    }

    @Override
    protected void onProcess() {
        Frame frame = getFrameManager().fetchFrame(mSlotName);
        getConnectedOutputPort("frame").pushFrame(frame);
        frame.release();
    }

}

