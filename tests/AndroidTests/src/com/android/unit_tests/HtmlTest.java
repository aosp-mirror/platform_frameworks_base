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

package com.android.unit_tests;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.QuoteSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;

import junit.framework.TestCase;

/**
 * HtmlTest tests the Spanned-to-HTML converter
 */
public class HtmlTest extends TestCase {
    @MediumTest
    public void testColor() throws Exception {
        Spanned s;
        ForegroundColorSpan[] colors;

        s = Html.fromHtml("<font color=\"#00FF00\">something</font>");
        colors = s.getSpans(0, s.length(), ForegroundColorSpan.class);
        assertEquals(1, colors.length);
        assertEquals(0xFF00FF00, colors[0].getForegroundColor());

        s = Html.fromHtml("<font color=\"navy\">something</font>");
        colors = s.getSpans(0, s.length(), ForegroundColorSpan.class);
        assertEquals(1, colors.length);
        assertEquals(0xFF000080, colors[0].getForegroundColor());

        s = Html.fromHtml("<font color=\"gibberish\">something</font>");
        colors = s.getSpans(0, s.length(), ForegroundColorSpan.class);
        assertEquals(0, colors.length);
    }

    @MediumTest
    public void testResourceColor() throws Exception {
        ColorStateList c =
                Resources.getSystem().getColorStateList(android.R.color.primary_text_dark);
        Spanned s;
        TextAppearanceSpan[] colors;

        s = Html.fromHtml("<font color=\"@android:color/primary_text_dark\">something</font>");
        colors = s.getSpans(0, s.length(), TextAppearanceSpan.class);
        assertEquals(1, colors.length);
        assertEquals(c.toString(), colors[0].getTextColor().toString());

        s = Html.fromHtml("<font color=\"@android:primary_text_dark\">something</font>");
        colors = s.getSpans(0, s.length(), TextAppearanceSpan.class);
        assertEquals(1, colors.length);
        assertEquals(c.toString(), colors[0].getTextColor().toString());

        s = Html.fromHtml("<font color=\"@color/primary_text_dark\">something</font>");
        colors = s.getSpans(0, s.length(), TextAppearanceSpan.class);
        assertEquals(1, colors.length);
        assertEquals(c.toString(), colors[0].getTextColor().toString());

        s = Html.fromHtml("<font color=\"@primary_text_dark\">something</font>");
        colors = s.getSpans(0, s.length(), TextAppearanceSpan.class);
        assertEquals(1, colors.length);
        assertEquals(c.toString(), colors[0].getTextColor().toString());

        s = Html.fromHtml("<font color=\"@" + android.R.color.primary_text_dark
                + "\">something</font>");
        colors = s.getSpans(0, s.length(), TextAppearanceSpan.class);
        assertEquals(1, colors.length);
        assertEquals(c.toString(), colors[0].getTextColor().toString());

        s = Html.fromHtml("<font color=\"gibberish\">something</font>");
        colors = s.getSpans(0, s.length(), TextAppearanceSpan.class);
        assertEquals(colors.length, 0);
    }

    @SmallTest
    public void testParagraphs() throws Exception {
        SpannableString s;

        s = new SpannableString("Hello world");
        assertEquals(Html.toHtml(s), "<p>Hello world</p>\n");

        s = new SpannableString("Hello world\nor something");
        assertEquals(Html.toHtml(s), "<p>Hello world<br>\nor something</p>\n");

        s = new SpannableString("Hello world\n\nor something");
        assertEquals(Html.toHtml(s), "<p>Hello world</p>\n<p>or something</p>\n");

        s = new SpannableString("Hello world\n\n\nor something");
        assertEquals(Html.toHtml(s), "<p>Hello world<br></p>\n<p>or something</p>\n");

        assertEquals("foo\nbar", Html.fromHtml("foo<br>bar").toString());
        assertEquals("foo\nbar", Html.fromHtml("foo<br>\nbar").toString());
        assertEquals("foo\nbar", Html.fromHtml("foo<br>\n \nbar").toString());
    }

    @SmallTest
    public void testBlockquote() throws Exception {
        SpannableString s;

        s = new SpannableString("Hello world");
        s.setSpan(new QuoteSpan(), 0, s.length(), Spannable.SPAN_PARAGRAPH);
        assertEquals(Html.toHtml(s), "<blockquote><p>Hello world</p>\n</blockquote>\n");

        s = new SpannableString("Hello\n\nworld");
        s.setSpan(new QuoteSpan(), 0, 7, Spannable.SPAN_PARAGRAPH);
        assertEquals(Html.toHtml(s), "<blockquote><p>Hello</p>\n</blockquote>\n<p>world</p>\n");
    }

    @SmallTest
    public void testEntities() throws Exception {
        SpannableString s;

        s = new SpannableString("Hello <&> world");
        assertEquals(Html.toHtml(s), "<p>Hello &lt;&amp;&gt; world</p>\n");

        s = new SpannableString("Hello \u03D5 world");
        assertEquals(Html.toHtml(s), "<p>Hello &#981; world</p>\n");

        s = new SpannableString("Hello  world");
        assertEquals(Html.toHtml(s), "<p>Hello&nbsp; world</p>\n");
    }

    @SmallTest
    public void testMarkup() throws Exception {
        SpannableString s;

        s = new SpannableString("Hello bold world");
        s.setSpan(new StyleSpan(Typeface.BOLD), 6, s.length() - 6,
                  Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        assertEquals(Html.toHtml(s), "<p>Hello <b>bold</b> world</p>\n");

        s = new SpannableString("Hello italic world");
        s.setSpan(new StyleSpan(Typeface.ITALIC), 6, s.length() - 6,
                  Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        assertEquals(Html.toHtml(s), "<p>Hello <i>italic</i> world</p>\n");

        s = new SpannableString("Hello monospace world");
        s.setSpan(new TypefaceSpan("monospace"), 6, s.length() - 6,
                  Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        assertEquals(Html.toHtml(s), "<p>Hello <tt>monospace</tt> world</p>\n");

        s = new SpannableString("Hello superscript world");
        s.setSpan(new SuperscriptSpan(), 6, s.length() - 6,
                  Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        assertEquals(Html.toHtml(s), "<p>Hello <sup>superscript</sup> world</p>\n");

        s = new SpannableString("Hello subscript world");
        s.setSpan(new SubscriptSpan(), 6, s.length() - 6,
                  Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        assertEquals(Html.toHtml(s), "<p>Hello <sub>subscript</sub> world</p>\n");

        s = new SpannableString("Hello underline world");
        s.setSpan(new UnderlineSpan(), 6, s.length() - 6,
                  Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        assertEquals(Html.toHtml(s), "<p>Hello <u>underline</u> world</p>\n");

        s = new SpannableString("Hello struck world");
        s.setSpan(new StrikethroughSpan(), 6, s.length() - 6,
                  Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        assertEquals(Html.toHtml(s), "<p>Hello <strike>struck</strike> world</p>\n");

        s = new SpannableString("Hello linky world");
        s.setSpan(new URLSpan("http://www.google.com"), 6, s.length() - 6,
                  Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        assertEquals(Html.toHtml(s),
                     "<p>Hello <a href=\"http://www.google.com\">linky</a> world</p>\n");
    }

    @SmallTest
    public void testImg() throws Exception {
        Spanned s;

        s = Html.fromHtml("yes<img src=\"http://example.com/foo.gif\">no");

        assertEquals("<p>yes<img src=\"http://example.com/foo.gif\">no</p>\n",
                     Html.toHtml(s));
    }

    @SmallTest
    public void testUtf8() throws Exception {
        Spanned s;

        s = Html.fromHtml("<p>\u0124\u00eb\u0142\u0142o, world!</p>");
        assertEquals("<p>&#292;&#235;&#322;&#322;o, world!</p>\n", Html.toHtml(s));
    }
}
