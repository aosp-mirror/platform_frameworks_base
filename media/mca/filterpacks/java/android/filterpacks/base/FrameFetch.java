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
public class FrameFetch extends Filter {

    @GenerateFinalPort(name = "format", hasDefault = true)
    private FrameFormat mFormat;

    @GenerateFieldPort(name = "key")
    private String mKey;

    @GenerateFieldPort(name = "repeatFrame", hasDefault = true)
    private boolean mRepeatFrame = false;

    public FrameFetch(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addOutputPort("frame", mFormat == null ? FrameFormat.unspecified() : mFormat);
    }

    public void process(FilterContext context) {
        Frame output = context.fetchFrame(mKey);
        if (output != null) {
            pushOutput("frame", output);
            if (!mRepeatFrame) {
                closeOutputPort("frame");
            }
        } else {
            delayNextProcess(250);
        }
    }

}
