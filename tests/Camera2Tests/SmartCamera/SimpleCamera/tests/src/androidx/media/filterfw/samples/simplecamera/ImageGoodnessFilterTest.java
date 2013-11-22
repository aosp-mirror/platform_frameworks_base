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

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.MffFilterTestCase;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class ImageGoodnessFilterTest extends MffFilterTestCase {

    String AWFUL_STRING = "Awful Picture";
    String BAD_STRING  = "Bad Picture";
    String OK_STRING = "Ok Picture";
    String GOOD_STRING = "Good Picture!";
    String GREAT_STRING = "Great Picture!";
    @Override
    protected Filter createFilter(MffContext mffContext) {
        return new ImageGoodnessFilter(mffContext, "goodnessFilter");
    }

    public void testAwfulPicture() throws Exception {
        FrameValue sharpnessFrame = createFrame(FrameType.single(), new int[] { 1 }).
                asFrameValue();
        sharpnessFrame.setValue(10f);
        FrameValue oEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        oEFrame.setValue(0.39f);
        FrameValue uEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        uEFrame.setValue(0.25f);
        FrameValue colorFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        colorFrame.setValue(2.1f);
        FrameValue contrastFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        contrastFrame.setValue(0.18f);
        FrameValue motionFrame = createFrame(FrameType.array(), new int[] { 1 }).asFrameValue();
        float[] motionFloatArray = { 9.0f, 3.0f, 2.0f };
        motionFrame.setValue(motionFloatArray);

        injectInputFrame("sharpness", sharpnessFrame);
        injectInputFrame("overExposure", oEFrame);
        injectInputFrame("underExposure", uEFrame);
        injectInputFrame("colorfulness", colorFrame);
        injectInputFrame("contrastRating", contrastFrame);
        injectInputFrame("motionValues", motionFrame);

        process();
        assertEquals("Awful Picture", (String) getOutputFrame("goodOrBadPic").asFrameValue().
                getValue());
    }

    public void testBadPicture() throws Exception {
        FrameValue sharpnessFrame = createFrame(FrameType.single(), new int[] { 1 }).
                asFrameValue();
        sharpnessFrame.setValue(10f);
        FrameValue oEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        oEFrame.setValue(0.39f);
        FrameValue uEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        uEFrame.setValue(0.25f);
        FrameValue colorFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        colorFrame.setValue(2.1f);
        FrameValue contrastFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        contrastFrame.setValue(0.18f);
        FrameValue motionFrame = createFrame(FrameType.array(), new int[] { 1 }).asFrameValue();
        float[] motionFloatArray = { 0.0f, 0.0f, 0.0f };
        motionFrame.setValue(motionFloatArray);

        injectInputFrame("sharpness", sharpnessFrame);
        injectInputFrame("overExposure", oEFrame);
        injectInputFrame("underExposure", uEFrame);
        injectInputFrame("colorfulness", colorFrame);
        injectInputFrame("contrastRating", contrastFrame);
        injectInputFrame("motionValues", motionFrame);

        process();
        assertEquals("Bad Picture", (String) getOutputFrame("goodOrBadPic").asFrameValue().
                getValue());
    }

    public void testOkPicture() throws Exception {
        FrameValue sharpnessFrame = createFrame(FrameType.single(), new int[] { 1 }).
                asFrameValue();
        sharpnessFrame.setValue(30f);
        FrameValue oEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        oEFrame.setValue(0.39f);
        FrameValue uEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        uEFrame.setValue(0.25f);
        FrameValue colorFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        colorFrame.setValue(2.1f);
        FrameValue contrastFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        contrastFrame.setValue(0.18f);
        FrameValue motionFrame = createFrame(FrameType.array(), new int[] { 1 }).asFrameValue();
        float[] motionFloatArray = { 0.0f, 0.0f, 0.0f };
        motionFrame.setValue(motionFloatArray);

        injectInputFrame("sharpness", sharpnessFrame);
        injectInputFrame("overExposure", oEFrame);
        injectInputFrame("underExposure", uEFrame);
        injectInputFrame("colorfulness", colorFrame);
        injectInputFrame("contrastRating", contrastFrame);
        injectInputFrame("motionValues", motionFrame);

        process();
        assertEquals("Ok Picture", (String) getOutputFrame("goodOrBadPic").asFrameValue().
                getValue());
    }

    public void testGoodPicture() throws Exception {
        FrameValue sharpnessFrame = createFrame(FrameType.single(), new int[] { 1 }).
                asFrameValue();
        sharpnessFrame.setValue(50f);
        FrameValue oEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        oEFrame.setValue(0.01f);
        FrameValue uEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        uEFrame.setValue(0.01f);
        FrameValue colorFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        colorFrame.setValue(2.1f);
        FrameValue contrastFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        contrastFrame.setValue(0.18f);
        FrameValue motionFrame = createFrame(FrameType.array(), new int[] { 1 }).asFrameValue();
        float[] motionFloatArray = { 0.0f, 0.0f, 0.0f };
        motionFrame.setValue(motionFloatArray);

        injectInputFrame("sharpness", sharpnessFrame);
        injectInputFrame("overExposure", oEFrame);
        injectInputFrame("underExposure", uEFrame);
        injectInputFrame("colorfulness", colorFrame);
        injectInputFrame("contrastRating", contrastFrame);
        injectInputFrame("motionValues", motionFrame);

        process();
        assertEquals("Good Picture!", (String) getOutputFrame("goodOrBadPic").asFrameValue().
                getValue());
    }

    public void testGreatPicture() throws Exception {
        FrameValue sharpnessFrame = createFrame(FrameType.single(), new int[] { 1 }).
                asFrameValue();
        sharpnessFrame.setValue(50f);
        FrameValue oEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        oEFrame.setValue(0.01f);
        FrameValue uEFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        uEFrame.setValue(0.02f);
        FrameValue colorFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        colorFrame.setValue(2.1f);
        FrameValue contrastFrame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        contrastFrame.setValue(0.25f);
        FrameValue motionFrame = createFrame(FrameType.array(), new int[] { 1 }).asFrameValue();
        float[] motionFloatArray = { 0.0f, 0.0f, 0.0f };
        motionFrame.setValue(motionFloatArray);

        injectInputFrame("sharpness", sharpnessFrame);
        injectInputFrame("overExposure", oEFrame);
        injectInputFrame("underExposure", uEFrame);
        injectInputFrame("colorfulness", colorFrame);
        injectInputFrame("contrastRating", contrastFrame);
        injectInputFrame("motionValues", motionFrame);

        process();
        assertEquals("Great Picture!", (String) getOutputFrame("goodOrBadPic").asFrameValue().
                getValue());
    }
}
