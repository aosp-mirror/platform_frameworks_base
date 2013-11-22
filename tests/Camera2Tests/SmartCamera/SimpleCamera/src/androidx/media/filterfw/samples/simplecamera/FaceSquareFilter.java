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
// Takes in an array, returns the size of the array

package androidx.media.filterfw.samples.simplecamera;

import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.Face;
import android.util.Log;
import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValues;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

import java.nio.ByteBuffer;

public class FaceSquareFilter extends Filter {

    private static final String TAG = "FaceSquareFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

    private static int FACE_X_RANGE = 2000;
    private static int WIDTH_OFFSET = 1000;
    private static int HEIGHT_OFFSET = 1000;

    public FaceSquareFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType imageType = FrameType.buffer2D(FrameType.ELEMENT_RGBA8888);
        FrameType facesType = FrameType.array(Camera.Face.class);
        return new Signature()
                .addInputPort("image", Signature.PORT_REQUIRED, imageType)
                .addInputPort("faces", Signature.PORT_REQUIRED, facesType)
                .addOutputPort("image", Signature.PORT_REQUIRED, imageType)
                .disallowOtherPorts();
    }

    /**
     * @see androidx.media.filterfw.Filter#onProcess()
     */
    @Override
    protected void onProcess() {
        // Get inputs
        FrameImage2D imageFrame = getConnectedInputPort("image").pullFrame().asFrameImage2D();
        FrameValues facesFrame = getConnectedInputPort("faces").pullFrame().asFrameValues();
        Face[] faces = (Face[]) facesFrame.getValues();
        int[] dims = imageFrame.getDimensions();
        ByteBuffer buffer = imageFrame.lockBytes(Frame.MODE_WRITE);
        byte[] pixels = buffer.array();

        // For every face in faces, draw a white rect around the
        // face following the rect member of the Face
        drawBoxes(pixels, faces, dims);

        imageFrame.unlock();

        OutputPort outPort = getConnectedOutputPort("image");
        outPort.pushFrame(imageFrame);
    }

    public void drawBoxes(byte[] pixels, Face[] faces, int[] dims) {
        for(int i = 0; i < faces.length; i++) {
            Rect tempRect = faces[i].rect;
            int top = (tempRect.top+HEIGHT_OFFSET)*dims[1]/FACE_X_RANGE;
            int bottom = (tempRect.bottom+HEIGHT_OFFSET)*dims[1]/FACE_X_RANGE;
            int left = (tempRect.left+WIDTH_OFFSET)*dims[0]/FACE_X_RANGE;
            int right = (tempRect.right+WIDTH_OFFSET)*dims[0]/FACE_X_RANGE;

            if (top < 0) {
                top = 0;
            } else if (top > dims[1]) {
                top = dims[1];
            }
            if (left < 0) {
                left = 0;
            } else if (left > dims[0]) {
                left = dims[0];
            }
            if (bottom > dims[1]) {
                bottom = dims[1];
            } else if (bottom < 0) {
                bottom = 0;
            }
            if (right > dims[0]) {
                right = dims[0];
            } else if (right < 0) {
                right = 0;
            }

            for (int j = 0; j < (bottom - top); j++) {
                // Left edge
                if (left > 0 && top > 0) {
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * (top + j) + left) +
                           ImageConstants.RED_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * (top + j) + left) +
                           ImageConstants.GREEN_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * (top + j) + left) +
                           ImageConstants.BLUE_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                }

                // Right edge
                if (right > 0 && top > 0) {
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * (top + j) + right) +
                           ImageConstants.RED_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * (top + j) + right) +
                           ImageConstants.GREEN_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * (top + j) + right) +
                           ImageConstants.BLUE_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                }

            }
            for (int k = 0; k < (right - left); k++) {
                // Top edge
                if (top < dims[1]) {
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * top + left + k) +
                           ImageConstants.RED_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * top + left + k) +
                           ImageConstants.GREEN_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * top + left + k) +
                           ImageConstants.BLUE_OFFSET] = (byte) ImageConstants.MAX_BYTE;

                }
                // Bottom edge
                if (bottom < dims[1]) {
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * bottom + left + k) +
                           ImageConstants.RED_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * bottom + left + k) +
                           ImageConstants.GREEN_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                    pixels[ImageConstants.PIX_CHANNELS * (dims[0] * bottom + left + k) +
                           ImageConstants.BLUE_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                }


            }

        }
    }
}
