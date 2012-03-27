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


package android.filterpacks.numeric;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.format.ObjectFormat;

import java.lang.Math;

/**
 * @hide
 */
public class SinWaveFilter extends Filter {

    @GenerateFieldPort(name = "stepSize", hasDefault = true)
    private float mStepSize = 0.05f;

    private float mValue = 0.0f;

    private FrameFormat mOutputFormat;

    public SinWaveFilter(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        mOutputFormat = ObjectFormat.fromClass(Float.class, FrameFormat.TARGET_SIMPLE);
        addOutputPort("value", mOutputFormat);
    }

    @Override
    public void open(FilterContext env) {
        mValue = 0.0f;
    }

    @Override
    public void process(FilterContext env) {
        Frame output = env.getFrameManager().newFrame(mOutputFormat);
        output.setObjectValue(((float)Math.sin(mValue) + 1.0f) / 2.0f);
        pushOutput("value", output);
        mValue += mStepSize;
        output.release();
    }


}
