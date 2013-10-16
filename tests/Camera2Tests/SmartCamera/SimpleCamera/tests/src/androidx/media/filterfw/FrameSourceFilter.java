/*
 * Copyright (C) 2013 The Android Open Source Project
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
package androidx.media.filterfw;

import java.util.LinkedList;
import java.util.Queue;

/**
 * A {@link Filter} that pushes out externally injected frames.
 * <p> When a frame is injected using {@link #injectFrame(Frame)}, this source will push it on its
 * output port and then sleep until another frame is injected.
 * <p> Multiple frames may be injected before any frame is pushed out. In this case they will be
 * queued and pushed in FIFO order.
 */
class FrameSourceFilter extends Filter {

    private final Queue<Frame> mFrames = new LinkedList<Frame>();

    FrameSourceFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        return new Signature()
                .addOutputPort("output", Signature.PORT_REQUIRED, FrameType.any())
                .disallowOtherPorts();
    }

    private synchronized Frame obtainFrame() {
        if (mFrames.isEmpty()) {
            enterSleepState();
            return null;
        } else {
            return mFrames.poll();
        }
    }

    /**
     * Call this method to inject a frame that will be pushed in a future execution of the filter.
     * <p> If multiple frames are injected then they will be pushed one per execution in FIFO order.
     */
    public synchronized void injectFrame(Frame frame) {
        mFrames.add(frame);
        wakeUp();
    }

    @Override
    protected void onProcess() {
        Frame frame = obtainFrame();
        if (frame != null) {
            getConnectedOutputPort("output").pushFrame(frame);
        }
    }

}
