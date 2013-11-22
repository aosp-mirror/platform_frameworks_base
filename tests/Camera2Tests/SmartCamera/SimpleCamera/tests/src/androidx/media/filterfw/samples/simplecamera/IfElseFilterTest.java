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
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.MffFilterTestCase;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class IfElseFilterTest extends MffFilterTestCase {
    private final static int BIG_INPUT_WIDTH = 1536;
    private final static int BIG_INPUT_HEIGHT = 2048;
    private final static int SMALL_INPUT_WIDTH = 480;
    private final static int SMALL_INPUT_HEIGHT = 640;

    private AssetManager assetMgr = null;
    @Override
    protected Filter createFilter(MffContext mffContext) {
        assetMgr = mffContext.getApplicationContext().getAssets();
        return new IfElseFilter(mffContext, "ifElseFilter");
    }

    public void testIfElseFilterTrue() throws Exception {
        FrameImage2D image =
                createFrame(FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_CPU),
                        new int[] {BIG_INPUT_WIDTH,BIG_INPUT_HEIGHT}).asFrameImage2D();
        FrameImage2D video =
                createFrame(FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_CPU),
                        new int[] {SMALL_INPUT_WIDTH,SMALL_INPUT_HEIGHT}).asFrameImage2D();

        // Image of legs
        Bitmap videoBitmap = BitmapFactory.decodeStream(assetMgr.open("0002_000390.jpg"));
        // Image of a face
        Bitmap imageBitmap = BitmapFactory.decodeStream(assetMgr.open("XZZ019.jpg"));

        image.setBitmap(imageBitmap);
        injectInputFrame("falseResult", image);
        video.setBitmap(videoBitmap);
        injectInputFrame("trueResult", video);

        FrameValue conditionFrame = createFrame(FrameType.single(boolean.class), new int[] {1}).
                asFrameValue();
        conditionFrame.setValue(true);
        injectInputFrame("condition", conditionFrame);

        process();

        // Ensure that for true, we use the video input
        FrameImage2D outputImage = getOutputFrame("output").asFrameImage2D();
        assertEquals(outputImage, video);
    }

    public void testIfElseFilterFalse() throws Exception {

        FrameImage2D image =
                createFrame(FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_CPU),
                        new int[] {BIG_INPUT_WIDTH,BIG_INPUT_HEIGHT}).asFrameImage2D();
        FrameImage2D video =
                createFrame(FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_CPU),
                        new int[] {SMALL_INPUT_WIDTH,SMALL_INPUT_HEIGHT}).asFrameImage2D();

        // Image of legs
        Bitmap videoBitmap = BitmapFactory.decodeStream(assetMgr.open("0002_000390.jpg"));
        // Image of a face
        Bitmap imageBitmap = BitmapFactory.decodeStream(assetMgr.open("XZZ019.jpg"));

        image.setBitmap(imageBitmap);
        injectInputFrame("falseResult", image);
        video.setBitmap(videoBitmap);
        injectInputFrame("trueResult", video);


        FrameValue conditionFrame = createFrame(FrameType.single(boolean.class), new int[] {1}).
                asFrameValue();
        conditionFrame.setValue(false);
        injectInputFrame("condition", conditionFrame);

        process();

        // Ensure that for true, we use the video input
        FrameImage2D outputImage = getOutputFrame("output").asFrameImage2D();
        assertEquals(outputImage, image);
    }
}
