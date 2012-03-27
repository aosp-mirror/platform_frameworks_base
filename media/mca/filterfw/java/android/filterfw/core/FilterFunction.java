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


package android.filterfw.core;

import java.util.Map.Entry;

/**
 * @hide
 */
public class FilterFunction {

    private Filter mFilter;
    private FilterContext mFilterContext;
    private boolean mFilterIsSetup = false;
    private FrameHolderPort[] mResultHolders;

    private class FrameHolderPort extends StreamPort {
        public FrameHolderPort() {
            super(null, "holder");
        }
    }

    public FilterFunction(FilterContext context, Filter filter) {
        mFilterContext = context;
        mFilter = filter;
    }

    public Frame execute(KeyValueMap inputMap) {
        int filterOutCount = mFilter.getNumberOfOutputs();

        // Sanity checks
        if (filterOutCount > 1) {
            throw new RuntimeException("Calling execute on filter " + mFilter + " with multiple "
                + "outputs! Use executeMulti() instead!");
        }

        // Setup filter
        if (!mFilterIsSetup) {
            connectFilterOutputs();
            mFilterIsSetup = true;
        }

        // Make sure GL environment is active
        boolean didActivateGLEnv = false;
        GLEnvironment glEnv = mFilterContext.getGLEnvironment();
        if (glEnv != null && !glEnv.isActive()) {
            glEnv.activate();
            didActivateGLEnv = true;
        }

        // Setup the inputs
        for (Entry<String, Object> entry : inputMap.entrySet()) {
            if (entry.getValue() instanceof Frame) {
                mFilter.pushInputFrame(entry.getKey(), (Frame)entry.getValue());
            } else {
                mFilter.pushInputValue(entry.getKey(), entry.getValue());
            }
        }

        // Process the filter
        if (mFilter.getStatus() != Filter.STATUS_PROCESSING) {
            mFilter.openOutputs();
        }

        mFilter.performProcess(mFilterContext);

        // Create result handle
        Frame result = null;
        if (filterOutCount == 1 && mResultHolders[0].hasFrame()) {
            result = mResultHolders[0].pullFrame();
        }

        // Deactivate GL environment if activated
        if (didActivateGLEnv) {
            glEnv.deactivate();
        }

        return result;
    }

    public Frame executeWithArgList(Object... inputs) {
        return execute(KeyValueMap.fromKeyValues(inputs));
    }

    public void close() {
        mFilter.performClose(mFilterContext);
    }

    public FilterContext getContext() {
        return mFilterContext;
    }

    public Filter getFilter() {
        return mFilter;
    }

    public void setInputFrame(String input, Frame frame) {
        mFilter.setInputFrame(input, frame);
    }

    public void setInputValue(String input, Object value) {
        mFilter.setInputValue(input, value);
    }

    public void tearDown() {
        mFilter.performTearDown(mFilterContext);
        mFilter = null;
    }

    @Override
    public String toString() {
        return mFilter.getName();
    }

    private void connectFilterOutputs() {
        int  i = 0;
        mResultHolders = new FrameHolderPort[mFilter.getNumberOfOutputs()];
        for (OutputPort outputPort : mFilter.getOutputPorts()) {
            mResultHolders[i] = new FrameHolderPort();
            outputPort.connectTo(mResultHolders[i]);
            ++i;
        }
    }
}
