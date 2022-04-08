/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.test.MoreAsserts;
import android.text.style.StyleSpan;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.view.View;

import androidx.test.filters.LargeTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.android.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TextUtilsTest tests {@link TextUtils}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextUtilsTest {

    @Test
    public void testBasic() {
        assertEquals("", TextUtils.concat());
        assertEquals("foo", TextUtils.concat("foo"));
        assertEquals("foobar", TextUtils.concat("foo", "bar"));
        assertEquals("foobarbaz", TextUtils.concat("foo", "bar", "baz"));

        SpannableString foo = new SpannableString("foo");
        foo.setSpan("foo", 1, 2, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);

        SpannableString bar = new SpannableString("bar");
        bar.setSpan("bar", 1, 2, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);

        SpannableString baz = new SpannableString("baz");
        baz.setSpan("baz", 1, 2, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);

        assertEquals("foo", TextUtils.concat(foo).toString());
        assertEquals("foobar", TextUtils.concat(foo, bar).toString());
        assertEquals("foobarbaz", TextUtils.concat(foo, bar, baz).toString());

        assertEquals(1, ((Spanned) TextUtils.concat(foo)).getSpanStart("foo"));

        assertEquals(1, ((Spanned) TextUtils.concat(foo, bar)).getSpanStart("foo"));
        assertEquals(4, ((Spanned) TextUtils.concat(foo, bar)).getSpanStart("bar"));

        assertEquals(1, ((Spanned) TextUtils.concat(foo, bar, baz)).getSpanStart("foo"));
        assertEquals(4, ((Spanned) TextUtils.concat(foo, bar, baz)).getSpanStart("bar"));
        assertEquals(7, ((Spanned) TextUtils.concat(foo, bar, baz)).getSpanStart("baz"));

        assertTrue(TextUtils.concat("foo", "bar") instanceof String);
        assertTrue(TextUtils.concat(foo, bar) instanceof SpannedString);
    }

    @Test
    public void testTemplateString() {
        CharSequence result;

        result = TextUtils.expandTemplate("This is a ^1 of the ^2 broadcast ^3.",
                                          "test", "emergency", "system");
        assertEquals("This is a test of the emergency broadcast system.",
                     result.toString());

        result = TextUtils.expandTemplate("^^^1^^^2^3^a^1^^b^^^c",
                                          "one", "two", "three");
        assertEquals("^one^twothree^aone^b^^c",
                     result.toString());

        result = TextUtils.expandTemplate("^");
        assertEquals("^", result.toString());

        result = TextUtils.expandTemplate("^^");
        assertEquals("^", result.toString());

        result = TextUtils.expandTemplate("^^^");
        assertEquals("^^", result.toString());

        result = TextUtils.expandTemplate("shorter ^1 values ^2.", "a", "");
        assertEquals("shorter a values .", result.toString());

        try {
            TextUtils.expandTemplate("Only ^1 value given, but ^2 used.", "foo");
            fail();
        } catch (IllegalArgumentException e) {
        }

        try {
            TextUtils.expandTemplate("^1 value given, and ^0 used.", "foo");
            fail();
        } catch (IllegalArgumentException e) {
        }

        result = TextUtils.expandTemplate("^1 value given, and ^9 used.",
                                          "one", "two", "three", "four", "five",
                                          "six", "seven", "eight", "nine");
        assertEquals("one value given, and nine used.", result.toString());

        try {
            TextUtils.expandTemplate("^1 value given, and ^10 used.",
                                     "one", "two", "three", "four", "five",
                                     "six", "seven", "eight", "nine", "ten");
            fail();
        } catch (IllegalArgumentException e) {
        }

        // putting carets in the values: expansion is not recursive.

        result = TextUtils.expandTemplate("^2", "foo", "^^");
        assertEquals("^^", result.toString());

        result = TextUtils.expandTemplate("^^2", "foo", "1");
        assertEquals("^2", result.toString());

        result = TextUtils.expandTemplate("^1", "value with ^2 in it", "foo");
        assertEquals("value with ^2 in it", result.toString());
    }

    /** Fail unless text+spans contains a span 'spanName' with the given start and end. */
    private void checkContains(Spanned text, String[] spans, String spanName,
                               int start, int end) {
        for (String i: spans) {
            if (i.equals(spanName)) {
                assertEquals(start, text.getSpanStart(i));
                assertEquals(end, text.getSpanEnd(i));
                return;
            }
        }
        fail();
    }

    @Test
    public void testTemplateSpan() {
        SpannableString template;
        Spanned result;
        String[] spans;

        // ordinary replacement

        template = new SpannableString("a^1b");
        template.setSpan("before", 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        template.setSpan("during", 1, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        template.setSpan("after", 3, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        template.setSpan("during+after", 1, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        result = (Spanned) TextUtils.expandTemplate(template, "foo");
        assertEquals(5, result.length());
        spans = result.getSpans(0, result.length(), String.class);

        // value is one character longer, so span endpoints should change.
        assertEquals(4, spans.length);
        checkContains(result, spans, "before", 0, 1);
        checkContains(result, spans, "during", 1, 4);
        checkContains(result, spans, "after", 4, 5);
        checkContains(result, spans, "during+after", 1, 5);


        // replacement with empty string

        result = (Spanned) TextUtils.expandTemplate(template, "");
        assertEquals(2, result.length());
        spans = result.getSpans(0, result.length(), String.class);

        // the "during" span should disappear.
        assertEquals(3, spans.length);
        checkContains(result, spans, "before", 0, 1);
        checkContains(result, spans, "after", 1, 2);
        checkContains(result, spans, "during+after", 1, 2);
    }

    @Test
    public void testStringSplitterSimple() {
        stringSplitterTestHelper("a,b,cde", new String[] {"a", "b", "cde"});
    }

    @Test
    public void testStringSplitterEmpty() {
        stringSplitterTestHelper("", new String[] {});
    }

    @Test
    public void testStringSplitterWithLeadingEmptyString() {
        stringSplitterTestHelper(",a,b,cde", new String[] {"", "a", "b", "cde"});
    }

    @Test
    public void testStringSplitterWithInternalEmptyString() {
        stringSplitterTestHelper("a,b,,cde", new String[] {"a", "b", "", "cde"});
    }

    @Test
    public void testStringSplitterWithTrailingEmptyString() {
        // A single trailing emtpy string should be ignored.
        stringSplitterTestHelper("a,b,cde,", new String[] {"a", "b", "cde"});
    }

    private void stringSplitterTestHelper(String string, String[] expectedStrings) {
        TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
        splitter.setString(string);
        List<String> strings = Lists.newArrayList();
        for (String s : splitter) {
            strings.add(s);
        }
        MoreAsserts.assertEquals(expectedStrings, strings.toArray(new String[]{}));
    }

    @Test
    public void testTrim() {
        String[] strings = { "abc", " abc", "  abc", "abc ", "abc  ",
                             " abc ", "  abc  ", "\nabc\n", "\nabc", "abc\n" };

        for (String s : strings) {
            assertEquals(s.trim().length(), TextUtils.getTrimmedLength(s));
        }
    }

    @Test
    public void testRfc822TokenizerFullAddress() {
        Rfc822Token[] tokens = Rfc822Tokenizer.tokenize("Foo Bar (something) <foo@google.com>");
        assertNotNull(tokens);
        assertEquals(1, tokens.length);
        assertEquals("foo@google.com", tokens[0].getAddress());
        assertEquals("Foo Bar", tokens[0].getName());
        assertEquals("something",tokens[0].getComment());
    }

    @Test
    public void testRfc822TokenizeItemWithError() {
        Rfc822Token[] tokens = Rfc822Tokenizer.tokenize("\"Foo Bar\\");
        assertNotNull(tokens);
        assertEquals(1, tokens.length);
        assertEquals("Foo Bar", tokens[0].getAddress());
    }

    @Test
    public void testRfc822FindToken() {
        Rfc822Tokenizer tokenizer = new Rfc822Tokenizer();
        //                0           1         2           3         4
        //                0 1234 56789012345678901234 5678 90123456789012345
        String address = "\"Foo\" <foo@google.com>, \"Bar\" <bar@google.com>";
        assertEquals(0, tokenizer.findTokenStart(address, 21));
        assertEquals(22, tokenizer.findTokenEnd(address, 21));
        assertEquals(24, tokenizer.findTokenStart(address, 25));
        assertEquals(46, tokenizer.findTokenEnd(address, 25));
    }

    @Test
    public void testRfc822FindTokenWithError() {
        assertEquals(9, new Rfc822Tokenizer().findTokenEnd("\"Foo Bar\\", 0));
    }

    @LargeTest
    @Test
    public void testEllipsize() {
        CharSequence s1 = "The quick brown fox jumps over \u00FEhe lazy dog.";
        CharSequence s2 = new Wrapper(s1);
        Spannable s3 = new SpannableString(s1);
        s3.setSpan(new StyleSpan(0), 5, 10, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextPaint p = new TextPaint();
        p.setFlags(p.getFlags() & ~p.DEV_KERN_TEXT_FLAG);

        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 3; j++) {
                TextUtils.TruncateAt kind = null;

                switch (j) {
                case 0:
                    kind = TextUtils.TruncateAt.START;
                    break;

                case 1:
                    kind = TextUtils.TruncateAt.END;
                    break;

                case 2:
                    kind = TextUtils.TruncateAt.MIDDLE;
                    break;
                }

                String out1 = TextUtils.ellipsize(s1, p, i, kind).toString();
                String out2 = TextUtils.ellipsize(s2, p, i, kind).toString();
                String out3 = TextUtils.ellipsize(s3, p, i, kind).toString();

                String keep1 = TextUtils.ellipsize(s1, p, i, kind, true, null).toString();
                String keep2 = TextUtils.ellipsize(s2, p, i, kind, true, null).toString();
                String keep3 = TextUtils.ellipsize(s3, p, i, kind, true, null).toString();

                String trim1 = keep1.replace("\uFEFF", "");

                // Are all normal output strings identical?
                assertEquals("wid " + i + " pass " + j, out1, out2);
                assertEquals("wid " + i + " pass " + j, out2, out3);

                // Are preserved output strings identical?
                assertEquals("wid " + i + " pass " + j, keep1, keep2);
                assertEquals("wid " + i + " pass " + j, keep2, keep3);

                // Does trimming padding from preserved yield normal?
                assertEquals("wid " + i + " pass " + j, out1, trim1);

                // Did preserved output strings preserve length?
                assertEquals("wid " + i + " pass " + j, keep1.length(), s1.length());

                // Does the output string actually fit in the space?
                assertTrue("wid " + i + " pass " + j, p.measureText(out1) <= i);

                // Is the padded output the same width as trimmed output?
                assertTrue("wid " + i + " pass " + j, p.measureText(keep1) == p.measureText(out1));
            }
        }
    }

    @Test
    public void testEllipsize_multiCodepoint() {
        final TextPaint paint = new TextPaint();
        final float wordWidth = paint.measureText("MMMM");

        // Establish the ground rules first, for single-codepoint cases.
        final String ellipsis = "."; // one full stop character
        assertEquals(
                "MM.\uFEFF",
                TextUtils.ellipsize("MMMM", paint, 0.7f * wordWidth,
                        TextUtils.TruncateAt.END, true /* preserve length */,
                        null /* no callback */, TextDirectionHeuristics.LTR,
                        ellipsis));
        assertEquals(
                "MM.",
                TextUtils.ellipsize("MMMM", paint, 0.7f * wordWidth,
                        TextUtils.TruncateAt.END, false /* preserve length */,
                        null /* no callback */, TextDirectionHeuristics.LTR,
                        ellipsis));
        assertEquals(
                "M.",
                TextUtils.ellipsize("MM", paint, 0.45f * wordWidth,
                        TextUtils.TruncateAt.END, true /* preserve length */,
                        null /* no callback */, TextDirectionHeuristics.LTR,
                        ellipsis));
        assertEquals(
                "M.",
                TextUtils.ellipsize("MM", paint, 0.45f * wordWidth,
                        TextUtils.TruncateAt.END, false /* preserve length */,
                        null /* no callback */, TextDirectionHeuristics.LTR,
                        ellipsis));

        // Now check the differences for multi-codepoint ellipsis.
        final String longEllipsis = ".."; // two full stop characters
        assertEquals(
                "MM..",
                TextUtils.ellipsize("MMMM", paint, 0.7f * wordWidth,
                        TextUtils.TruncateAt.END, true /* preserve length */,
                        null /* no callback */, TextDirectionHeuristics.LTR,
                        longEllipsis));
        assertEquals(
                "MM..",
                TextUtils.ellipsize("MMMM", paint, 0.7f * wordWidth,
                        TextUtils.TruncateAt.END, false /* preserve length */,
                        null /* no callback */, TextDirectionHeuristics.LTR,
                        longEllipsis));
        assertEquals(
                "M\uFEFF",
                TextUtils.ellipsize("MM", paint, 0.45f * wordWidth,
                        TextUtils.TruncateAt.END, true /* preserve length */,
                        null /* no callback */, TextDirectionHeuristics.LTR,
                        longEllipsis));
        assertEquals(
                "M..",
                TextUtils.ellipsize("MM", paint, 0.45f * wordWidth,
                        TextUtils.TruncateAt.END, false /* preserve length */,
                        null /* no callback */, TextDirectionHeuristics.LTR,
                        longEllipsis));
    }

    @Test
    public void testDelimitedStringContains() {
        assertFalse(TextUtils.delimitedStringContains("", ',', null));
        assertFalse(TextUtils.delimitedStringContains(null, ',', ""));
        // Whole match
        assertTrue(TextUtils.delimitedStringContains("gps", ',', "gps"));
        // At beginning.
        assertTrue(TextUtils.delimitedStringContains("gps,gpsx,network,mock", ',', "gps"));
        assertTrue(TextUtils.delimitedStringContains("gps,network,mock", ',', "gps"));
        // In middle, both without, before & after a false match.
        assertTrue(TextUtils.delimitedStringContains("network,gps,mock", ',', "gps"));
        assertTrue(TextUtils.delimitedStringContains("network,gps,gpsx,mock", ',', "gps"));
        assertTrue(TextUtils.delimitedStringContains("network,gpsx,gps,mock", ',', "gps"));
        // At the end.
        assertTrue(TextUtils.delimitedStringContains("network,mock,gps", ',', "gps"));
        assertTrue(TextUtils.delimitedStringContains("network,mock,gpsx,gps", ',', "gps"));
        // Not present (but with a false match)
        assertFalse(TextUtils.delimitedStringContains("network,mock,gpsx", ',', "gps"));
    }

    @Test
    public void testCharSequenceCreator() {
        Parcel p = Parcel.obtain();
        CharSequence text;
        try {
            TextUtils.writeToParcel(null, p, 0);
            text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(p);
            assertNull("null CharSequence should generate null from parcel", text);
        } finally {
            p.recycle();
        }
        p = Parcel.obtain();
        try {
            TextUtils.writeToParcel("test", p, 0);
            p.setDataPosition(0);
            text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(p);
            assertEquals("conversion to/from parcel failed", "test", text);
        } finally {
            p.recycle();
        }
    }

    @Test
    public void testCharSequenceCreatorNull() {
        Parcel p;
        CharSequence text;
        p = Parcel.obtain();
        try {
            TextUtils.writeToParcel(null, p, 0);
            p.setDataPosition(0);
            text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(p);
            assertNull("null CharSequence should generate null from parcel", text);
        } finally {
            p.recycle();
        }
    }

    @Test
    public void testCharSequenceCreatorSpannable() {
        Parcel p;
        CharSequence text;
        p = Parcel.obtain();
        try {
            TextUtils.writeToParcel(new SpannableString("test"), p, 0);
            p.setDataPosition(0);
            text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(p);
            assertEquals("conversion to/from parcel failed", "test", text.toString());
        } finally {
            p.recycle();
        }
    }

    @Test
    public void testCharSequenceCreatorString() {
        Parcel p;
        CharSequence text;
        p = Parcel.obtain();
        try {
            TextUtils.writeToParcel("test", p, 0);
            p.setDataPosition(0);
            text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(p);
            assertEquals("conversion to/from parcel failed", "test", text.toString());
        } finally {
            p.recycle();
        }
    }

    /**
     * CharSequence wrapper for testing the cases where text is copied into
     * a char array instead of working from a String or a Spanned.
     */
    private static class Wrapper implements CharSequence {
        private CharSequence mString;

        public Wrapper(CharSequence s) {
            mString = s;
        }

        @Override
        public int length() {
            return mString.length();
        }

        @Override
        public char charAt(int off) {
            return mString.charAt(off);
        }

        @Override
        public String toString() {
            return mString.toString();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Wrapper(mString.subSequence(start, end));
        }
    }

    @Test
    public void testRemoveEmptySpans() {
        MockSpanned spanned = new MockSpanned();

        spanned.test();
        spanned.addSpan().test();
        spanned.addSpan().test();
        spanned.addSpan().test();
        spanned.addEmptySpan().test();
        spanned.addSpan().test();
        spanned.addEmptySpan().test();
        spanned.addEmptySpan().test();
        spanned.addSpan().test();

        spanned.clear();
        spanned.addEmptySpan().test();
        spanned.addEmptySpan().test();
        spanned.addEmptySpan().test();
        spanned.addSpan().test();
        spanned.addEmptySpan().test();
        spanned.addSpan().test();

        spanned.clear();
        spanned.addSpan().test();
        spanned.addEmptySpan().test();
        spanned.addSpan().test();
        spanned.addEmptySpan().test();
        spanned.addSpan().test();
        spanned.addSpan().test();
    }

    protected static class MockSpanned implements Spanned {

        private List<Object> allSpans = new ArrayList<Object>();
        private List<Object> nonEmptySpans = new ArrayList<Object>();

        public void clear() {
            allSpans.clear();
            nonEmptySpans.clear();
        }

        public MockSpanned addSpan() {
            Object o = new Object();
            allSpans.add(o);
            nonEmptySpans.add(o);
            return this;
        }

        public MockSpanned addEmptySpan() {
            Object o = new Object();
            allSpans.add(o);
            return this;
        }

        public void test() {
            Object[] nonEmpty = TextUtils.removeEmptySpans(allSpans.toArray(), this, Object.class);
            assertEquals("Mismatched array size", nonEmptySpans.size(), nonEmpty.length);
            for (int i=0; i<nonEmpty.length; i++) {
                assertEquals("Span differ", nonEmptySpans.get(i), nonEmpty[i]);
            }
        }

        @Override
        public char charAt(int arg0) {
            return 0;
        }

        @Override
        public int length() {
            return 0;
        }

        @Override
        public CharSequence subSequence(int arg0, int arg1) {
            return null;
        }

        @Override
        public <T> T[] getSpans(int start, int end, Class<T> type) {
            return null;
        }

        @Override
        public int getSpanStart(Object tag) {
            return 0;
        }

        @Override
        public int getSpanEnd(Object tag) {
            return nonEmptySpans.contains(tag) ? 1 : 0;
        }

        @Override
        public int getSpanFlags(Object tag) {
            return 0;
        }

        @Override
        public int nextSpanTransition(int start, int limit, Class type) {
            return 0;
        }
    }

    @Test
    public void testGetLayoutDirectionFromLocale() {
        assertEquals(View.LAYOUT_DIRECTION_LTR, TextUtils.getLayoutDirectionFromLocale(null));
        assertEquals(View.LAYOUT_DIRECTION_LTR,
                TextUtils.getLayoutDirectionFromLocale(Locale.ROOT));
        assertEquals(View.LAYOUT_DIRECTION_LTR,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("en")));
        assertEquals(View.LAYOUT_DIRECTION_LTR,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("en-US")));
        assertEquals(View.LAYOUT_DIRECTION_LTR,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("az")));
        assertEquals(View.LAYOUT_DIRECTION_LTR,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("az-AZ")));
        assertEquals(View.LAYOUT_DIRECTION_LTR,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("az-Latn")));
        assertEquals(View.LAYOUT_DIRECTION_LTR,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("en-EG")));
        assertEquals(View.LAYOUT_DIRECTION_LTR,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("ar-Latn")));

        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("ar")));
        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("fa")));
        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("he")));
        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("iw")));
        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("ur")));
        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("dv")));
        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("az-Arab")));
        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("az-IR")));
        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("fa-US")));
        assertEquals(View.LAYOUT_DIRECTION_RTL,
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag("tr-Arab")));
    }

    @Test
    public void testToUpperCase() {
        {
            final CharSequence result = TextUtils.toUpperCase(null, "abc", false);
            assertEquals(StringBuilder.class, result.getClass());
            assertEquals("ABC", result.toString());
        }
        {
            final SpannableString str = new SpannableString("abc");
            Object span = new Object();
            str.setSpan(span, 1, 2, Spanned.SPAN_INCLUSIVE_INCLUSIVE);

            final CharSequence result = TextUtils.toUpperCase(null, str, true /* copySpans */);
            assertEquals(SpannableStringBuilder.class, result.getClass());
            assertEquals("ABC", result.toString());
            final Spanned spanned = (Spanned) result;
            final Object[] resultSpans = spanned.getSpans(0, result.length(), Object.class);
            assertEquals(1, resultSpans.length);
            assertSame(span, resultSpans[0]);
            assertEquals(1, spanned.getSpanStart(span));
            assertEquals(2, spanned.getSpanEnd(span));
            assertEquals(Spanned.SPAN_INCLUSIVE_INCLUSIVE, spanned.getSpanFlags(span));
        }
        {
            final Locale turkish = new Locale("tr", "TR");
            final CharSequence result = TextUtils.toUpperCase(turkish, "i", false);
            assertEquals(StringBuilder.class, result.getClass());
            assertEquals("İ", result.toString());
        }
        {
            final String str = "ABC";
            assertSame(str, TextUtils.toUpperCase(null, str, false));
        }
        {
            final SpannableString str = new SpannableString("ABC");
            str.setSpan(new Object(), 1, 2, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            assertSame(str, TextUtils.toUpperCase(null, str, true /* copySpans */));
        }
    }

    // Copied from cts/tests/tests/widget/src/android/widget/cts/TextViewTest.java and modified
    // for the TextUtils.toUpperCase method.
    @Test
    public void testToUpperCase_SpansArePreserved() {
        final Locale greek = new Locale("el", "GR");
        final String lowerString = "ι\u0301ριδα";  // ίριδα with first letter decomposed
        final String upperString = "ΙΡΙΔΑ";  // uppercased
        // expected lowercase to uppercase index map
        final int[] indexMap = {0, 1, 1, 2, 3, 4, 5};
        final int flags = Spanned.SPAN_INCLUSIVE_INCLUSIVE;

        final Spannable source = new SpannableString(lowerString);
        source.setSpan(new Object(), 0, 1, flags);
        source.setSpan(new Object(), 1, 2, flags);
        source.setSpan(new Object(), 2, 3, flags);
        source.setSpan(new Object(), 3, 4, flags);
        source.setSpan(new Object(), 4, 5, flags);
        source.setSpan(new Object(), 5, 6, flags);
        source.setSpan(new Object(), 0, 2, flags);
        source.setSpan(new Object(), 1, 3, flags);
        source.setSpan(new Object(), 2, 4, flags);
        source.setSpan(new Object(), 0, 6, flags);
        final Object[] sourceSpans = source.getSpans(0, source.length(), Object.class);

        final CharSequence uppercase = TextUtils.toUpperCase(greek, source, true /* copySpans */);
        assertEquals(SpannableStringBuilder.class, uppercase.getClass());
        final Spanned result = (Spanned) uppercase;

        assertEquals(upperString, result.toString());
        final Object[] resultSpans = result.getSpans(0, result.length(), Object.class);
        assertEquals(sourceSpans.length, resultSpans.length);
        for (int i = 0; i < sourceSpans.length; i++) {
            assertSame(sourceSpans[i], resultSpans[i]);
            final Object span = sourceSpans[i];
            assertEquals(indexMap[source.getSpanStart(span)], result.getSpanStart(span));
            assertEquals(indexMap[source.getSpanEnd(span)], result.getSpanEnd(span));
            assertEquals(source.getSpanFlags(span), result.getSpanFlags(span));
        }
    }

    @Test
    public void testTrimToSize() {
        final String testString = "a\uD800\uDC00a";
        assertEquals("Should return text as it is if size is longer than length",
                testString, TextUtils.trimToSize(testString, 5));
        assertEquals("Should return text as it is if size is equal to length",
                testString, TextUtils.trimToSize(testString, 4));
        assertEquals("Should trim text",
                "a\uD800\uDC00", TextUtils.trimToSize(testString, 3));
        assertEquals("Should trim surrogate pairs if size is in the middle of a pair",
                "a", TextUtils.trimToSize(testString, 2));
        assertEquals("Should trim text",
                "a", TextUtils.trimToSize(testString, 1));
        assertEquals("Should handle null",
                null, TextUtils.trimToSize(null, 1));

        assertEquals("Should trim high surrogate if invalid surrogate",
                "a\uD800", TextUtils.trimToSize("a\uD800\uD800", 2));
        assertEquals("Should trim low surrogate if invalid surrogate",
                "a\uDC00", TextUtils.trimToSize("a\uDC00\uDC00", 2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTrimToSizeThrowsExceptionForNegativeSize() {
        TextUtils.trimToSize("", -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTrimToSizeThrowsExceptionForZeroSize() {
        TextUtils.trimToSize("abc", 0);
    }

    @Test
    public void length() {
        assertEquals(0, TextUtils.length(null));
        assertEquals(0, TextUtils.length(""));
        assertEquals(2, TextUtils.length("  "));
        assertEquals(6, TextUtils.length("Hello!"));
    }

    @Test
    public void testTrimToLengthWithEllipsis() {
        assertEquals("ABC...", TextUtils.trimToLengthWithEllipsis("ABCDEF", 3));
        assertEquals("ABC", TextUtils.trimToLengthWithEllipsis("ABC", 3));
        assertEquals("", TextUtils.trimToLengthWithEllipsis("", 3));
    }
}
