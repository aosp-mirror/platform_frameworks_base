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
import android.filterfw.format.ObjectFormat;

/**
 * @hide
 */
public class ObjectSource extends Filter {

    @GenerateFieldPort(name = "object")
    private Object mObject;

    @GenerateFinalPort(name = "format", hasDefault = true)
    private FrameFormat mOutputFormat = FrameFormat.unspecified();

    @GenerateFieldPort(name = "repeatFrame", hasDefault = true)
    boolean mRepeatFrame = false;

    private Frame mFrame;

    public ObjectSource(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addOutputPort("frame", mOutputFormat);
    }

    @Override
    public void process(FilterContext context) {
        // If no frame has been created, create one now.
        if (mFrame == null) {
            if (mObject == null) {
                throw new NullPointerException("ObjectSource producing frame with no object set!");
            }
            FrameFormat outputFormat = ObjectFormat.fromObject(mObject, FrameFormat.TARGET_SIMPLE);
            mFrame = context.getFrameManager().newFrame(outputFormat);
            mFrame.setObjectValue(mObject);
            mFrame.setTimestamp(Frame.TIMESTAMP_UNKNOWN);
        }

        // Push output
        pushOutput("frame", mFrame);

        // Wait for free output
        if (!mRepeatFrame) {
            closeOutputPort("frame");
        }
    }

    @Override
    public void tearDown(FilterContext context) {
        mFrame.release();
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        // Release our internal frame, so that it is regenerated on the next call to process().
        if (name.equals("object")) {
            if (mFrame != null) {
                mFrame.release();
                mFrame = null;
            }
        }
    }
}
