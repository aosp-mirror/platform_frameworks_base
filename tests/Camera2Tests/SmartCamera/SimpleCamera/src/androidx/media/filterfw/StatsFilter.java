/*
 * Copyright (C) 2012 The Android Open Source Project
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

// Calculates the mean and standard deviation of the values in the input image.
// It takes in an RGBA image, but assumes that r, g, b, a are all the same values.

package androidx.media.filterpacks.numeric;

import android.util.Log;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameBuffer2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.InputPort;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;
import androidx.media.filterfw.geometry.Quad;

import java.nio.ByteBuffer;

/**
 * Get the sample mean and variance of a 2-D buffer of bytes over a given rectangle.
 * TODO: Add more statistics as needed.
 * TODO: Check if crop rectangle is necessary to be included in this filter.
 */
public class StatsFilter extends Filter {

    private static final int MEAN_INDEX = 0;
    private static final int STDEV_INDEX = 1;

    private final float[] mStats = new float[2];

    private Quad mCropRect = Quad.fromRect(0f, 0f, 1f, 1f);
    private static final String TAG = "StatsFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

    /**
     * @param context
     * @param name
     */
    public StatsFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType inputFrame = FrameType.buffer2D(FrameType.ELEMENT_INT8);
        FrameType floatT = FrameType.single(float.class);
        return new Signature()
                .addInputPort("buffer", Signature.PORT_REQUIRED, inputFrame)
                .addInputPort("cropRect", Signature.PORT_OPTIONAL, FrameType.single(Quad.class))
                .addOutputPort("mean", Signature.PORT_REQUIRED, floatT)
                .addOutputPort("stdev", Signature.PORT_REQUIRED, floatT)
                .disallowOtherPorts();
    }

    @Override
    public void onInputPortOpen(InputPort port) {
        if (port.getName().equals("cropRect")) {
            port.bindToFieldNamed("mCropRect");
            port.setAutoPullEnabled(true);
        }
    }

    private void calcMeanAndStd(ByteBuffer pixelBuffer, int width, int height, Quad quad) {
        // Native
        pixelBuffer.rewind();
        regionscore(pixelBuffer, width, height, quad.topLeft().x, quad.topLeft().y,
                quad.bottomRight().x, quad.bottomRight().y, mStats);
        if (mLogVerbose) {
            Log.v(TAG, "Native calc stats: Mean = " + mStats[MEAN_INDEX] + ", Stdev = "
                    + mStats[STDEV_INDEX]);
        }
    }

    /**
     * @see androidx.media.filterfw.Filter#onProcess()
     */
    @Override
    protected void onProcess() {
        FrameBuffer2D inputFrame = getConnectedInputPort("buffer").pullFrame().asFrameImage2D();
        ByteBuffer pixelBuffer = inputFrame.lockBytes(Frame.MODE_READ);

        calcMeanAndStd(pixelBuffer, inputFrame.getWidth(), inputFrame.getHeight(), mCropRect);
        inputFrame.unlock();

        OutputPort outPort = getConnectedOutputPort("mean");
        FrameValue outFrame = outPort.fetchAvailableFrame(null).asFrameValue();
        outFrame.setValue(mStats[MEAN_INDEX]);
        outPort.pushFrame(outFrame);

        OutputPort outPortStdev = getConnectedOutputPort("stdev");
        FrameValue outFrameStdev = outPortStdev.fetchAvailableFrame(null).asFrameValue();
        outFrameStdev.setValue(mStats[STDEV_INDEX]);
        outPortStdev.pushFrame(outFrameStdev);
    }

    private native void regionscore(ByteBuffer imageBuffer, int width, int height, float left,
            float top, float right, float bottom, float[] statsArray);

    static {
        System.loadLibrary("smartcamera_jni");
    }
}
