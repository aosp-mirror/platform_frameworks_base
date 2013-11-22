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

/**
 * A {@link Filter} that consumes frames and allows to register a listener to observe when
 * a new frame has been consumed.
 */
class FrameTargetFilter extends Filter {

    interface Listener {
        /**
         * Called each time this filter receives a new frame. The implementer of this method is
         * responsible for releasing the frame.
         */
        void onFramePushed(String filterName, Frame frame);
    }

    private Listener mListener;

    FrameTargetFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        return new Signature()
                .addInputPort("input", Signature.PORT_REQUIRED, FrameType.any())
                .disallowOtherPorts();
    }

    public synchronized void setListener(Listener listener) {
        mListener = listener;
    }

    @Override
    protected synchronized void onProcess() {
        Frame frame = getConnectedInputPort("input").pullFrame();
        if (mListener != null) {
            frame.retain();
            mListener.onFramePushed(getName(), frame);
        }
    }

}
