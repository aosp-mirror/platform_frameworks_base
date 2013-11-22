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

// Extract histogram from image.

package androidx.media.filterpacks.histogram;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameBuffer2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.InputPort;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * ChromaHistogramFilter takes in an image in HSVA format and computes a 2-D histogram with a
 * 2 dimensional chroma histogram based on hue (column) and saturation (row) at the top and
 * a 1-D value histogram in the last row. The number of bin in the value histogram equals to
 * the number of bins in hue.
 */
public final class NewChromaHistogramFilter extends Filter {

    private int mHueBins = 6;
    private int mSaturationBins = 3;
    private int mValueBins;

    private int mSaturationThreshold = 26; // 255 * 0.1
    private int mValueThreshold = 51; // 255 * 0.2

    public NewChromaHistogramFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_CPU);
        FrameType dataOut = FrameType.buffer2D(FrameType.ELEMENT_FLOAT32);

        return new Signature()
            .addInputPort("image", Signature.PORT_REQUIRED, imageIn)
            .addInputPort("huebins", Signature.PORT_OPTIONAL, FrameType.single(int.class))
            .addInputPort("saturationbins", Signature.PORT_OPTIONAL, FrameType.single(int.class))
            .addInputPort("saturationthreshold", Signature.PORT_OPTIONAL,
                    FrameType.single(int.class))
            .addInputPort("valuethreshold", Signature.PORT_OPTIONAL, FrameType.single(int.class))
            .addOutputPort("histogram", Signature.PORT_REQUIRED, dataOut)
            .disallowOtherPorts();
    }

    @Override
    public void onInputPortOpen(InputPort port) {
        if (port.getName().equals("huebins")) {
            port.bindToFieldNamed("mHueBins");
            port.setAutoPullEnabled(true);
        } else if (port.getName().equals("saturationbins")) {
            port.bindToFieldNamed("mSaturationBins");
            port.setAutoPullEnabled(true);
        } else if (port.getName().equals("saturationthreshold")) {
            port.bindToFieldNamed("mSaturationThreshold");
            port.setAutoPullEnabled(true);
        } else if (port.getName().equals("valuethreshold")) {
            port.bindToFieldNamed("mValueThreshold");
            port.setAutoPullEnabled(true);
        }
    }

    @Override
    protected void onProcess() {
        FrameBuffer2D imageFrame = getConnectedInputPort("image").pullFrame().asFrameImage2D();
        OutputPort outPort = getConnectedOutputPort("histogram");

        mValueBins = mHueBins;
        int[] outDims = new int[] {mHueBins, mSaturationBins + 1};
        FrameBuffer2D histogramFrame = outPort.fetchAvailableFrame(outDims).asFrameBuffer2D();

        ByteBuffer imageBuffer  = imageFrame.lockBytes(Frame.MODE_READ);
        ByteBuffer histogramBuffer = histogramFrame.lockBytes(Frame.MODE_READ);
        histogramBuffer.order(ByteOrder.nativeOrder());
        FloatBuffer floatHistogram = histogramBuffer.asFloatBuffer();

        // Run native method
        extractChromaHistogram(imageBuffer, floatHistogram, mHueBins, mSaturationBins, mValueBins,
                mSaturationThreshold, mValueThreshold);

        imageFrame.unlock();
        histogramFrame.unlock();

        outPort.pushFrame(histogramFrame);
    }

    private static native void extractChromaHistogram(ByteBuffer imageBuffer,
            FloatBuffer histogramBuffer, int hueBins, int saturationBins, int valueBins,
            int saturationThreshold, int valueThreshold);

    static {
        System.loadLibrary("smartcamera_jni");
    }
}
