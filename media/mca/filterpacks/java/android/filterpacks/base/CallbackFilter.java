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


package android.filterpacks.base;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.core.GenerateFinalPort;
import android.filterfw.core.KeyValueMap;
import android.filterfw.core.NativeProgram;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.Program;
import android.os.Handler;
import android.os.Looper;

import java.lang.Runnable;

/**
 * @hide
 */
public class CallbackFilter extends Filter {

    @GenerateFieldPort(name = "listener", hasDefault = true)
    private FilterContext.OnFrameReceivedListener mListener;

    @GenerateFieldPort(name = "userData", hasDefault = true)
    private Object mUserData;

    @GenerateFinalPort(name = "callUiThread", hasDefault = true)
    private boolean mCallbacksOnUiThread = true;

    private Handler mUiThreadHandler;

    private class CallbackRunnable implements Runnable {
        private Filter mFilter;
        private Frame mFrame;
        private Object mUserData;
        private FilterContext.OnFrameReceivedListener mListener;

        public CallbackRunnable(FilterContext.OnFrameReceivedListener listener, Filter filter, Frame frame, Object userData) {
            mListener = listener;
            mFilter = filter;
            mFrame = frame;
            mUserData = userData;
        }

        public void run() {
            mListener.onFrameReceived(mFilter, mFrame, mUserData);
            mFrame.release();
        }
    }

    public CallbackFilter(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addInputPort("frame");
    }

    public void prepare(FilterContext context) {
        if (mCallbacksOnUiThread) {
            mUiThreadHandler = new Handler(Looper.getMainLooper());
        }
    }

    public void process(FilterContext context) {
        // Get frame and forward to listener
        final Frame input = pullInput("frame");
        if (mListener != null) {
            if (mCallbacksOnUiThread) {
                input.retain();
                CallbackRunnable uiRunnable = new CallbackRunnable(mListener, this, input, mUserData);
                if (!mUiThreadHandler.post(uiRunnable)) {
                    throw new RuntimeException("Unable to send callback to UI thread!");
                }
            } else {
                mListener.onFrameReceived(this, input, mUserData);
            }
        } else {
            throw new RuntimeException("CallbackFilter received frame, but no listener set!");
        }
    }

}
