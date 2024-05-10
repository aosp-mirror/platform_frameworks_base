/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.view.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.graphics.RectF;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
@ApiTest(apis = {"android.view.inputmethod.SelectGesture.Builder#setGranularity",
    "android.view.inputmethod.SelectGesture.Builder#setSelectionArea",
    "android.view.inputmethod.SelectGesture.Builder#setFallbackText",
    "android.view.inputmethod.SelectGesture.Builder#build"})
public class SelectGestureTest {
    private static final RectF SELECTION_RECTANGLE = new RectF(1, 2, 3, 4);
    private static final String FALLBACK_TEXT = "fallback_text";

    @Test
    public void testBuilder() {
        SelectGesture.Builder builder = new SelectGesture.Builder();
        SelectGesture gesture = builder.setGranularity(HandwritingGesture.GRANULARITY_WORD)
                .setSelectionArea(SELECTION_RECTANGLE)
                .setFallbackText(FALLBACK_TEXT).build();
        assertNotNull(gesture);
        assertEquals(HandwritingGesture.GRANULARITY_WORD, gesture.getGranularity());
        assertEquals(SELECTION_RECTANGLE, gesture.getSelectionArea());
        assertEquals(FALLBACK_TEXT, gesture.getFallbackText());
    }
}
