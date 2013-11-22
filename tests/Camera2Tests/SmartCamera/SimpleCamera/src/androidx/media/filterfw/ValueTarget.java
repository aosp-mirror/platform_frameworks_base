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

import android.os.Handler;
import android.os.Looper;

import androidx.media.filterfw.*;

public final class ValueTarget extends Filter {

    public static interface ValueListener {
        public void onReceivedValue(Object value);
    }

    private ValueListener mListener = null;
    private Handler mHandler = null;

    public ValueTarget(MffContext context, String name) {
        super(context, name);
    }

    public void setListener(ValueListener listener, boolean onCallerThread) {
        if (isRunning()) {
            throw new IllegalStateException("Attempting to bind filter to callback while it is "
                + "running!");
        }
        mListener = listener;
        if (onCallerThread) {
            if (Looper.myLooper() == null) {
                throw new IllegalArgumentException("Attempting to set callback on thread which "
                    + "has no looper!");
            }
            mHandler = new Handler();
        }
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addInputPort("value", Signature.PORT_REQUIRED, FrameType.single())
            .disallowOtherPorts();
    }

    @Override
    protected void onProcess() {
        FrameValue valueFrame = getConnectedInputPort("value").pullFrame().asFrameValue();
        if (mListener != null) {
            if (mHandler != null) {
                postValueToUiThread(valueFrame.getValue());
            } else {
                mListener.onReceivedValue(valueFrame.getValue());
            }
        }
    }

    private void postValueToUiThread(final Object value) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onReceivedValue(value);
            }
        });
    }

}

