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

import android.graphics.PointF;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
@ApiTest(apis = {"android.view.inputmethod.InsertGesture.Builder#setInsertionPoint",
    "android.view.inputmethod.InsertGesture.Builder#setTextToInsert",
    "android.view.inputmethod.InsertGesture.Builder#setFallbackText",
    "android.view.inputmethod.InsertGesture.Builder#build"})
public class InsertGestureTest {
    private static final PointF INSERTION_POINT = new PointF(1, 2);
    private static final String FALLBACK_TEXT = "fallback_text";
    private static final String TEXT_TO_INSERT = "text";

    @Test
    public void testBuilder() {
        InsertGesture.Builder builder = new InsertGesture.Builder();
        InsertGesture gesture = builder.setInsertionPoint(INSERTION_POINT)
                .setTextToInsert(TEXT_TO_INSERT)
                .setFallbackText(FALLBACK_TEXT).build();
        assertNotNull(gesture);
        assertEquals(INSERTION_POINT, gesture.getInsertionPoint());
        assertEquals(FALLBACK_TEXT, gesture.getFallbackText());
        assertEquals(TEXT_TO_INSERT, gesture.getTextToInsert());
    }
}
