/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.text;

import android.test.InstrumentationTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.MediumTest;
import android.text.Spannable;

public abstract class SpannableTest extends InstrumentationTestCase {

    protected abstract Spannable newSpannableWithText(String text);

    @MediumTest
    public void testGetSpans() {
        Spannable spannable = newSpannableWithText("abcdef");
        Object emptySpan = new Object();
        spannable.setSpan(emptySpan, 1, 1, 0);
        Object unemptySpan = new Object();
        spannable.setSpan(unemptySpan, 1, 2, 0);

        Object[] spans;

        // Empty spans are included when they merely abut the query region
        // but other spans are not, unless the query region is empty, in
        // in which case any abutting spans are returned.
        spans = spannable.getSpans(0, 1, Object.class);
        MoreAsserts.assertEquals(new Object[]{emptySpan}, spans);
        spans = spannable.getSpans(0, 2, Object.class);
        MoreAsserts.assertEquals(new Object[]{emptySpan, unemptySpan}, spans);
        spans = spannable.getSpans(1, 2, Object.class);
        MoreAsserts.assertEquals(new Object[]{emptySpan, unemptySpan}, spans);
        spans = spannable.getSpans(2, 2, Object.class);
        MoreAsserts.assertEquals(new Object[]{unemptySpan}, spans);
    }
}
