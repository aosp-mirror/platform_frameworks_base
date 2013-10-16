/*
 * Copyright 2013 The Android Open Source Project
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

package androidx.media.filterfw.samples.simplecamera;

import android.util.Log;
import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

import java.nio.ByteBuffer;

public class ExposureFilter extends Filter {

    private FrameType mImageType;
    private static final String TAG = "ExposureFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);
    private static int OVER_EXPOSURE_TOLERANCE = 5;

    public ExposureFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        FrameType floatT = FrameType.single(float.class);
        return new Signature().addInputPort("image", Signature.PORT_REQUIRED, imageIn)
                .addOutputPort("overExposedNum", Signature.PORT_OPTIONAL, floatT)
                .addOutputPort("overExposureRating", Signature.PORT_REQUIRED, floatT)
                .addOutputPort("underExposedNum", Signature.PORT_OPTIONAL, floatT)
                .addOutputPort("underExposureRating", Signature.PORT_REQUIRED, floatT)
                .disallowOtherPorts();

    }

    @Override
    protected void onProcess() {
        FrameImage2D inputImage = getConnectedInputPort("image").pullFrame().asFrameImage2D();

        float overExposedPixels, underExposedPixels;
        ByteBuffer inputBuffer = inputImage.lockBytes(Frame.MODE_READ);

        overExposedPixels = overExposureOperator(inputImage.getWidth(),
                                                 inputImage.getHeight(),
                                                 inputBuffer);
        underExposedPixels = underExposureOperator(inputImage.getWidth(),
                                                   inputImage.getHeight(),
                                                   inputBuffer);
        inputImage.unlock();


        if (mLogVerbose) Log.v(TAG, "underExposedPixelCount: " + underExposedPixels);

        OutputPort underPort = getConnectedOutputPort("underExposedNum");
        if (underPort != null) {
            FrameValue underOutFrame = underPort.fetchAvailableFrame(null).asFrameValue();
            underOutFrame.setValue(underExposedPixels*inputImage.getWidth()*inputImage.getHeight());
            underPort.pushFrame(underOutFrame);
        }


        OutputPort underPort2 = getConnectedOutputPort("underExposureRating");
        FrameValue underOutFrame2 = underPort2.fetchAvailableFrame(null).asFrameValue();
        underOutFrame2.setValue(underExposedPixels);
        underPort2.pushFrame(underOutFrame2);

        if (mLogVerbose) Log.v(TAG, "overExposedPixelCount: " + overExposedPixels);

        OutputPort overPort = getConnectedOutputPort("overExposedNum");
        if (overPort != null) {
            FrameValue overOutFrame = overPort.fetchAvailableFrame(null).asFrameValue();
            overOutFrame.setValue(overExposedPixels*inputImage.getWidth()*inputImage.getHeight());
            overPort.pushFrame(overOutFrame);
        }


        OutputPort overPort2 = getConnectedOutputPort("overExposureRating");
        FrameValue overOutFrame2 = overPort2.fetchAvailableFrame(null).asFrameValue();
        overOutFrame2.setValue(overExposedPixels);
        overPort2.pushFrame(overOutFrame2);

    }

    private static native float overExposureOperator(int width, int height,
            ByteBuffer imageBuffer);
    private static native float underExposureOperator(int width, int height,
            ByteBuffer imageBuffer);

    static {
        System.loadLibrary("smartcamera_jni");
    }
}
