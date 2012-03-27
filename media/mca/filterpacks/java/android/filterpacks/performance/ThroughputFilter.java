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


package android.filterpacks.performance;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.format.ObjectFormat;
import android.os.SystemClock;

/**
 * @hide
 */
public class ThroughputFilter extends Filter {

    @GenerateFieldPort(name = "period", hasDefault = true)
    private int mPeriod = 5;

    private long mLastTime = 0;

    private int mTotalFrameCount = 0;
    private int mPeriodFrameCount = 0;

    private FrameFormat mOutputFormat;

    public ThroughputFilter(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        // Add input ports
        addInputPort("frame");

        // Add output ports
        mOutputFormat = ObjectFormat.fromClass(Throughput.class, FrameFormat.TARGET_SIMPLE);
        addOutputBasedOnInput("frame", "frame");
        addOutputPort("throughput", mOutputFormat);
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return inputFormat;
    }

    @Override
    public void open(FilterContext env) {
        mTotalFrameCount = 0;
        mPeriodFrameCount = 0;
        mLastTime = 0;
    }

    @Override
    public void process(FilterContext context) {
        // Pass through input frame
        Frame input = pullInput("frame");
        pushOutput("frame", input);

        // Update stats
        ++mTotalFrameCount;
        ++mPeriodFrameCount;

        // Check clock
        if (mLastTime == 0) {
            mLastTime = SystemClock.elapsedRealtime();
        }
        long curTime = SystemClock.elapsedRealtime();

        // Output throughput info if time period is up
        if ((curTime - mLastTime) >= (mPeriod * 1000)) {
            FrameFormat inputFormat = input.getFormat();
            int pixelCount = inputFormat.getWidth() * inputFormat.getHeight();
            Throughput throughput = new Throughput(mTotalFrameCount,
                                                   mPeriodFrameCount,
                                                   mPeriod,
                                                   pixelCount);
            Frame throughputFrame = context.getFrameManager().newFrame(mOutputFormat);
            throughputFrame.setObjectValue(throughput);
            pushOutput("throughput", throughputFrame);
            mLastTime = curTime;
            mPeriodFrameCount = 0;
        }
    }


}
