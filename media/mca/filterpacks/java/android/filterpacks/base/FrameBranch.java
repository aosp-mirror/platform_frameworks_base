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
import android.filterfw.core.GenerateFinalPort;
import android.filterfw.core.KeyValueMap;

/**
 * @hide
 */
public class FrameBranch extends Filter {

    @GenerateFinalPort(name = "outputs", hasDefault = true)
    private int mNumberOfOutputs = 2;

    public FrameBranch(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addInputPort("in");
        for (int i = 0; i < mNumberOfOutputs; ++i) {
            addOutputBasedOnInput("out" + i, "in");
        }
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return inputFormat;
    }

    public void process(FilterContext context) {
        // Get input frame
        Frame input = pullInput("in");

        // Push output
        for (int i = 0; i < mNumberOfOutputs; ++i) {
            pushOutput("out" + i, input);
        }
    }

}
