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

/**
 * @hide
 */
public class FrameSource extends Filter {

    @GenerateFinalPort(name = "format")
    private FrameFormat mFormat;

    @GenerateFieldPort(name = "frame", hasDefault = true)
    private Frame mFrame = null;

    @GenerateFieldPort(name = "repeatFrame", hasDefault = true)
    private boolean mRepeatFrame = false;

    public FrameSource(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addOutputPort("frame", mFormat);
    }

    @Override
    public void process(FilterContext context) {
        if (mFrame != null) {
            // Push output
            pushOutput("frame", mFrame);
        }

        if (!mRepeatFrame) {
            // Close output port as we are done here
            closeOutputPort("frame");
        }
    }

}
