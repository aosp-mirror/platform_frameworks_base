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


import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValues;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.MffFilterTestCase;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import android.hardware.Camera;
import android.hardware.Camera.Face;
import android.graphics.Rect;


public class FaceSquareFilterTest extends MffFilterTestCase {

    private AssetManager assetMgr = null;
    @Override
    protected Filter createFilter(MffContext mffContext) {
        assetMgr = mffContext.getApplicationContext().getAssets();
        return new FaceSquareFilter(mffContext, "faceSquareFilter");
    }

    public void testFaceSquareFilter() throws Exception{
        final int INPUT_WIDTH = 1536;
        final int INPUT_HEIGHT = 2048;
        FrameImage2D image =
                createFrame(FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_CPU),
                        new int[] {INPUT_WIDTH,INPUT_HEIGHT}).asFrameImage2D();

        FrameValues facesFrame = createFrame(FrameType.array(Camera.Face.class), new int[] {1,1}).
                asFrameValues();

        Bitmap bitmap = BitmapFactory.decodeStream(assetMgr.open("XZZ019.jpg"));
        image.setBitmap(bitmap);
        injectInputFrame("image", image);

        Face face = new Face();
        Rect faceRect = new Rect();
        // These are the values for image 141 with 1 face
        faceRect.set(-533, -453, 369, 224);
        face.rect = faceRect;
        Face[] faces = new Face[1];
        faces[0] = face;
        facesFrame.setValue(faces);
        injectInputFrame("faces", facesFrame);
        process();

        // ensure the output image has the rectangle in the right place
        FrameImage2D outputImage = getOutputFrame("image").asFrameImage2D();
        int[] pixels = new int[bitmap.getByteCount()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(),
                bitmap.getHeight());

        final int FACE_X_RANGE = 2000;
        final int WIDTH_OFFSET = 1000;
        final int HEIGHT_OFFSET = 1000;

        int top = (faceRect.top+HEIGHT_OFFSET)*bitmap.getHeight()/FACE_X_RANGE;
        int bottom = (faceRect.bottom+HEIGHT_OFFSET)*bitmap.getHeight()/FACE_X_RANGE;
        int left = (faceRect.left+WIDTH_OFFSET)*bitmap.getWidth()/FACE_X_RANGE;
        int right = (faceRect.right+WIDTH_OFFSET)*bitmap.getWidth()/FACE_X_RANGE;

        if (top < 0) {
            top = 0;
        } else if (top > bitmap.getHeight()) {
            top = bitmap.getHeight();
        }
        if (left < 0) {
            left = 0;
        } else if (left > bitmap.getWidth()) {
            left = bitmap.getWidth();
        }
        if (bottom > bitmap.getHeight()) {
            bottom = bitmap.getHeight();
        } else if (bottom < 0) {
            bottom = 0;
        }
        if (right > bitmap.getWidth()) {
            right = bitmap.getWidth();
        } else if (right < 0) {
            right = 0;
        }

        for (int j = 0; j < (bottom - top); j++) {
            // Left edge
            if (left > 0 && top > 0) {
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * (top + j) + left) +
                       ImageConstants.RED_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * (top + j) + left) +
                       ImageConstants.GREEN_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * (top + j) + left) +
                       ImageConstants.BLUE_OFFSET] = (byte) ImageConstants.MAX_BYTE;
            }

            // Right edge
            if (right > 0 && top > 0) {
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * (top + j) + right) +
                       ImageConstants.RED_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * (top + j) + right) +
                       ImageConstants.GREEN_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * (top + j) + right) +
                       ImageConstants.BLUE_OFFSET] = (byte) ImageConstants.MAX_BYTE;
            }

        }
        for (int k = 0; k < (right - left); k++) {
            // Top edge
            if (top < bitmap.getHeight()) {
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * top + left + k) +
                       ImageConstants.RED_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * top + left + k) +
                       ImageConstants.GREEN_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * top + left + k) +
                       ImageConstants.BLUE_OFFSET] = (byte) ImageConstants.MAX_BYTE;

            }
            // Bottom edge
            if (bottom < bitmap.getHeight()) {
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * bottom + left + k) +
                       ImageConstants.RED_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * bottom + left + k) +
                       ImageConstants.GREEN_OFFSET] = (byte) ImageConstants.MAX_BYTE;
                pixels[ImageConstants.PIX_CHANNELS * (bitmap.getWidth() * bottom + left + k) +
                       ImageConstants.BLUE_OFFSET] = (byte) ImageConstants.MAX_BYTE;
            }
        }

        Bitmap outputBitmap = outputImage.toBitmap();
        int[] outputPixels = new int[outputBitmap.getByteCount()];
        outputBitmap.getPixels(outputPixels, 0, outputBitmap.getWidth(), 0, 0,
                outputBitmap.getWidth(), outputBitmap.getHeight());
        int equalCount = 0;
        for ( int i = 0; i < outputBitmap.getByteCount(); i++) {
            if (pixels[i] == outputPixels[i])
                equalCount++;
        }

        if (equalCount + (0.05f*outputBitmap.getByteCount()) < outputBitmap.getByteCount()) {
            // Assertion will fail if condition is true
            assertEquals(equalCount, outputBitmap.getByteCount());
        }
    }
}