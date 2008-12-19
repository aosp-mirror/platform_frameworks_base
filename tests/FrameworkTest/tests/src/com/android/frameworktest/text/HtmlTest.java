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

package com.android.frameworktest.text;

import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.text.Html;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

public class HtmlTest extends InstrumentationTestCase {

    @MediumTest
    public void testSingleTagOnWhileString() {
        Spanned spanned = Html.fromHtml("<b>hello</b>");
        Object[] spans = spanned.getSpans(-1, 100, Object.class);
        assertEquals(1, spans.length);
        Object span = spans[0];
        assertEquals(0, spanned.getSpanStart(span));
        assertEquals(5, spanned.getSpanEnd(span));
    }

    @MediumTest
    public void testEmptyFontTag() {
        Spanned spanned = Html.fromHtml("Hello <font color=\"#ff00ff00\"></font>");
        Object[] spans = spanned.getSpans(0, 100, Object.class);
        // TODO: figure out what the spans should be after the crashes are fixed and assert them.
    }

    /** Tests that the parser can handle mal-formed HTML. */
    @MediumTest
    public void testBadHtml() {
        Spanned spanned = Html.fromHtml("Hello <b>b<i>bi</b>i</i>");
        Object[] spans = spanned.getSpans(0, 100, Object.class);
        assertEquals(Typeface.ITALIC, ((StyleSpan) spans[0]).getStyle());
        assertEquals(7, spanned.getSpanStart(spans[0]));
        assertEquals(9, spanned.getSpanEnd(spans[0]));
        assertEquals(Typeface.BOLD, ((StyleSpan) spans[1]).getStyle());
        assertEquals(6, spanned.getSpanStart(spans[1]));
        assertEquals(9, spanned.getSpanEnd(spans[1]));
        assertEquals(Typeface.ITALIC, ((StyleSpan) spans[2]).getStyle());
        assertEquals(9, spanned.getSpanStart(spans[2]));
        assertEquals(10, spanned.getSpanEnd(spans[2]));
    }

    @MediumTest
    public void testSymbols() {
        String spanned = Html.fromHtml("&copy; &gt; &lt").toString();
        assertEquals("\u00a9 > <", spanned);
    }
}
