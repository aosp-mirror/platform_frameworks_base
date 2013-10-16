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


package androidx.media.filterpacks.performance;

import android.util.Log;
import android.os.SystemClock;

import androidx.media.filterfw.*;

public class ThroughputFilter extends Filter {

    private int mPeriod = 3;
    private long mLastTime = 0;
    private int mTotalFrameCount = 0;
    private int mPeriodFrameCount = 0;

    public ThroughputFilter(MffContext context, String name) {
        super(context, name);
    }


    @Override
    public Signature getSignature() {
        FrameType throughputType = FrameType.single(Throughput.class);
        return new Signature()
            .addInputPort("frame", Signature.PORT_REQUIRED, FrameType.any())
            .addOutputPort("throughput", Signature.PORT_REQUIRED, throughputType)
            .addOutputPort("frame", Signature.PORT_REQUIRED, FrameType.any())
            .addInputPort("period", Signature.PORT_OPTIONAL, FrameType.single(int.class))
            .disallowOtherPorts();
    }

    @Override
    public void onInputPortOpen(InputPort port) {
        if (port.getName().equals("period")) {
            port.bindToFieldNamed("mPeriod");
        } else {
            port.attachToOutputPort(getConnectedOutputPort("frame"));
        }
    }

    @Override
    protected void onOpen() {
        mTotalFrameCount = 0;
        mPeriodFrameCount = 0;
        mLastTime = 0;
    }

    @Override
    protected synchronized void onProcess() {
        Frame inputFrame = getConnectedInputPort("frame").pullFrame();

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
            Log.i("Thru", "It is time!");
            OutputPort tpPort = getConnectedOutputPort("throughput");
            Throughput throughput = new Throughput(mTotalFrameCount,
                                                   mPeriodFrameCount,
                                                   curTime - mLastTime,
                                                   inputFrame.getElementCount());
            FrameValue throughputFrame = tpPort.fetchAvailableFrame(null).asFrameValue();
            throughputFrame.setValue(throughput);
            tpPort.pushFrame(throughputFrame);
            mLastTime = curTime;
            mPeriodFrameCount = 0;
        }

        getConnectedOutputPort("frame").pushFrame(inputFrame);
    }
}
