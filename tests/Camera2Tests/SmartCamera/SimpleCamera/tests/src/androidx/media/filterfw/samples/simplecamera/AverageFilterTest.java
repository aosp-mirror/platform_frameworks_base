/*
 * Copyright (C) 2013 The Android Open Source Project
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


public class AverageFilterTest extends MffFilterTestCase {

    @Override
    protected Filter createFilter(MffContext mffContext) {
        return new AverageFilter(mffContext, "averageFilter");
    }

    public void testAverageFilter() throws Exception {
        FrameValue frame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        frame.setValue(5f);

        injectInputFrame("sharpness", frame);

        process();
        assertEquals(1f, ((Float) getOutputFrame("avg").asFrameValue().getValue()).floatValue(),
                0.001f);
    }

    public void testAverageFilter2() throws Exception{
        FrameValue frame = createFrame(FrameType.single(), new int[] { 1 }).asFrameValue();
        frame.setValue(4f);

        injectInputFrame("sharpness", frame);

        process();
        assertEquals(0.8f, ((Float) getOutputFrame("avg").asFrameValue().getValue()).floatValue(),
                0.001f);
    }
}
