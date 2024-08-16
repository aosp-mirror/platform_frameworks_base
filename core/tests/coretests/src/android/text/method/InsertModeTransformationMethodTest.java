/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.text.method;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.View;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.text.flags.Flags;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class InsertModeTransformationMethodTest {
    private static View sView;
    private static final String TEXT = "abc def";

    @Rule
    public CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @BeforeClass
    public static void setupClass() {
        final Context context = InstrumentationRegistry.getTargetContext();
        sView = new View(context);
    }

    @Test
    public void transformedText_charAt() {
        for (int offset = 0; offset < TEXT.length(); ++offset) {
            final InsertModeTransformationMethod transformationMethod =
                    new InsertModeTransformationMethod(offset, false, null);
            final CharSequence transformedText =
                    transformationMethod.getTransformation(TEXT, sView);
            final CharSequence expected =
                    TEXT.substring(0, offset) + "\n\n" + TEXT.substring(offset);

            assertCharSequence(transformedText, expected);
        }
    }

    @Test
    public void transformedText_charAt_singleLine() {
        for (int offset = 0; offset < TEXT.length(); ++offset) {
            final InsertModeTransformationMethod transformationMethod =
                    new InsertModeTransformationMethod(offset, true, null);
            final CharSequence transformedText =
                    transformationMethod.getTransformation(TEXT, sView);
            final CharSequence expected =
                    TEXT.substring(0, offset) + "\uFFFD" + TEXT.substring(offset);

            assertCharSequence(transformedText, expected);
        }
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_charAt_editing() {
        transformedText_charAt_editing(false, "\n\n");
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_charAt_singleLine_editing() {
        transformedText_charAt_editing(true, "\uFFFD");
    }

    public void transformedText_charAt_editing(boolean singleLine, String placeholder) {
        final SpannableStringBuilder text = new SpannableStringBuilder(TEXT);
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, singleLine, null);
        final CharSequence transformedText = transformationMethod.getTransformation(text, sView);
        // TransformationMethod is set on the original text as a TextWatcher in the TextView.
        text.setSpan(transformationMethod, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        assertCharSequence(transformedText,  "abc" + placeholder + " def");

        // original text is "abcxx def" after insertion.
        text.insert(3, "xx");
        assertCharSequence(transformedText, "abcxx" + placeholder + " def");

        // original text is "abcxx vvdef" after insertion.
        text.insert(6, "vv");
        assertCharSequence(transformedText, "abcxx" + placeholder + " vvdef");

        // original text is "abc vvdef" after deletion.
        text.delete(3, 5);
        assertCharSequence(transformedText, "abc" + placeholder + " vvdef");

        // original text is "abc def" after deletion.
        text.delete(4, 6);
        assertCharSequence(transformedText, "abc" + placeholder + " def");

        // original text is "abdef" after deletion.
        // the placeholder is now inserted at index 2, since the deletion covers the index 3.
        text.delete(2, 4);
        assertCharSequence(transformedText, "ab" + placeholder + "def");

        // original text is "axxdef" after replace.
        text.replace(1, 2, "xx");
        assertCharSequence(transformedText, "axx" + placeholder + "def");

        // original text is "axxvvf" after replace.
        text.replace(3, 5, "vv");
        assertCharSequence(transformedText, "axx" + placeholder + "vvf");

        // original text is "abc def" after replace.
        // the placeholder is inserted at index 6 after the insertion, since the replacement covers
        // the index 3.
        text.replace(1, 5, "bc de");
        assertCharSequence(transformedText, "abc de" + placeholder + "f");
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_charAt_editing_stickyHighlightRange() {
        transformedText_charAt_editing_stickyHighlightRange(false, "\n\n");
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_charAt_singleLine_editing_stickyHighlightRange() {
        transformedText_charAt_editing_stickyHighlightRange(true, "\uFFFD");
    }

    private void transformedText_charAt_editing_stickyHighlightRange(boolean singleLine,
            String placeholder) {
        final SpannableStringBuilder text = new SpannableStringBuilder(TEXT);
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, singleLine, null);
        final CharSequence transformedText = transformationMethod.getTransformation(text, sView);
        // TransformationMethod is set on the original text as a TextWatcher in the TextView.
        text.setSpan(transformationMethod, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        assertCharSequence(transformedText,  "abc" + placeholder + " def");

        // original text is "abcxx def" after insertion.
        text.insert(3, "xx");
        assertCharSequence(transformedText, "abcxx" + placeholder + " def");

        // original text is "abcxx vvdef" after insertion.
        text.insert(6, "vv");
        assertCharSequence(transformedText, "abcxx" + placeholder + " vvdef");

        // original text is "abc vvdef" after deletion.
        text.delete(3, 5);
        assertCharSequence(transformedText, "abc" + placeholder + " vvdef");

        // original text is "abc def" after deletion.
        text.delete(4, 6);
        assertCharSequence(transformedText, "abc" + placeholder + " def");

        // original text is "abdef" after deletion.
        // deletion range covers the placeholder's insertion point. It'll try to stay the same,
        // which is still at index 3.
        text.delete(2, 4);
        assertCharSequence(transformedText, "abd" + placeholder + "ef");

        // original text is "axxdef" after replace.
        // this time the replaced range is ahead of the placeholder's insertion point. It updates to
        // index 4.
        text.replace(1, 2, "xx");
        assertCharSequence(transformedText, "axxd" + placeholder + "ef");

        // original text is "ax" after replace.
        // the deleted range covers the placeholder's insertion point. It tries to stay at index 4.
        // However, 4 out of bounds now. So placeholder is inserted at the end of the string.
        text.delete(2, 6);
        assertCharSequence(transformedText, "ax" + placeholder);
    }

    @Test
    public void transformedText_subSequence() {
        for (int offset = 0; offset < TEXT.length(); ++offset) {
            final InsertModeTransformationMethod transformationMethod =
                    new InsertModeTransformationMethod(offset, false, null);
            final CharSequence transformedText =
                    transformationMethod.getTransformation(TEXT, sView);
            final CharSequence expected =
                    TEXT.substring(0, offset) + "\n\n" + TEXT.substring(offset);

            for (int start = 0; start < transformedText.length(); ++start) {
                for (int end = start; end <= transformedText.length(); ++end) {
                    assertCharSequence(transformedText.subSequence(start, end),
                            expected.subSequence(start, end));
                }
            }
        }
    }

    @Test
    public void transformedText_subSequence_singleLine() {
        for (int offset = 0; offset < TEXT.length(); ++offset) {
            final InsertModeTransformationMethod transformationMethod =
                    new InsertModeTransformationMethod(offset, true, null);
            final CharSequence transformedText =
                    transformationMethod.getTransformation(TEXT, sView);
            final CharSequence expected =
                    TEXT.substring(0, offset) + "\uFFFD" + TEXT.substring(offset);

            for (int start = 0; start < transformedText.length(); ++start) {
                for (int end = start; end <= transformedText.length(); ++end) {
                    assertCharSequence(transformedText.subSequence(start, end),
                            expected.subSequence(start, end));
                }
            }
        }
    }

    @Test
    public void transformedText_toString() {
        for (int offset = 0; offset < TEXT.length(); ++offset) {
            final InsertModeTransformationMethod transformationMethod =
                    new InsertModeTransformationMethod(offset, false, null);
            final CharSequence transformedText =
                    transformationMethod.getTransformation(TEXT, sView);
            final String expected =
                    TEXT.substring(0, offset) + "\n\n" + TEXT.substring(offset);

            assertThat(transformedText.toString()).isEqualTo(expected);
        }
    }

    @Test
    public void transformedText_toString_singleLine() {
        for (int offset = 0; offset < TEXT.length(); ++offset) {
            final InsertModeTransformationMethod transformationMethod =
                    new InsertModeTransformationMethod(offset, true, null);
            final CharSequence transformedText =
                    transformationMethod.getTransformation(TEXT, sView);
            final String expected =
                    TEXT.substring(0, offset) + "\uFFFD" + TEXT.substring(offset);

            assertThat(transformedText.toString()).isEqualTo(expected);
        }
    }


    @Test
    public void transformedText_getSpans() {
        final SpannableString text = new SpannableString(TEXT);
        final TestSpan span1 = new TestSpan();
        final TestSpan span2 = new TestSpan();
        final TestSpan span3 = new TestSpan();

        text.setSpan(span1, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span2, 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span3, 4, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // In the transformedText "abc\n\n def", the new ranges of the spans are:
        // span1: [0, 3)
        // span2: [2, 6)
        // span3: [6, 7)
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, false, null);
        final Spanned transformedText =
                (Spanned) transformationMethod.getTransformation(text, sView);

        // only span1 is in the range of [0, 2).
        final TestSpan[] spans0to2 = transformedText.getSpans(0, 2, TestSpan.class);
        assertThat(spans0to2.length).isEqualTo(1);
        assertThat(spans0to2[0]).isEqualTo(span1);

        // only span2 is in the range of [3, 4).
        // note: span1 [0, 3) is not in the range because [3, 4) is not collapsed.
        final TestSpan[] spans3to4 = transformedText.getSpans(3, 4, TestSpan.class);
        assertThat(spans3to4.length).isEqualTo(1);
        assertThat(spans3to4[0]).isEqualTo(span2);

        // span1 and span2 are in the range of [1, 6).
        final TestSpan[] spans1to6 = transformedText.getSpans(1, 6, TestSpan.class);
        assertThat(spans1to6.length).isEqualTo(2);
        assertThat(spans1to6[0]).isEqualTo(span1);
        assertThat(spans1to6[1]).isEqualTo(span2);

        // only span2 is in the range of [4, 6).
        final TestSpan[] spans4to6 = transformedText.getSpans(4, 6, TestSpan.class);
        assertThat(spans4to6.length).isEqualTo(1);
        assertThat(spans4to6[0]).isEqualTo(span2);

        // span2 and span3 are in the range of [4, 7).
        final TestSpan[] spans4to7 = transformedText.getSpans(4, 7, TestSpan.class);
        assertThat(spans4to7.length).isEqualTo(2);
        assertThat(spans4to7[0]).isEqualTo(span2);
        assertThat(spans4to7[1]).isEqualTo(span3);

        // only span3 is in the range of [6, 7).
        final TestSpan[] spans6to7 = transformedText.getSpans(6, 7, TestSpan.class);
        assertThat(spans6to7.length).isEqualTo(1);
        assertThat(spans6to7[0]).isEqualTo(span3);

        // there is no span in the range of [7, 9).
        final TestSpan[] spans7to9 = transformedText.getSpans(7, 9, TestSpan.class);
        assertThat(spans7to9.length).isEqualTo(0);
    }

    @Test
    public void transformedText_getSpans_singleLine() {
        final SpannableString text = new SpannableString(TEXT);
        final TestSpan span1 = new TestSpan();
        final TestSpan span2 = new TestSpan();
        final TestSpan span3 = new TestSpan();

        text.setSpan(span1, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span2, 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span3, 4, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // In the transformedText "abc\uFFFD def", the new ranges of the spans are:
        // span1: [0, 3)
        // span2: [2, 5)
        // span3: [5, 6)
        // There should also be a ReplacementSpan in the range [3, 4).
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, true, null);
        final Spanned transformedText =
                (Spanned) transformationMethod.getTransformation(text, sView);

        // only span1 is in the range of [0, 2).
        final TestSpan[] spans0to2 = transformedText.getSpans(0, 2, TestSpan.class);
        assertThat(spans0to2.length).isEqualTo(1);
        assertThat(spans0to2[0]).isEqualTo(span1);

        // only span2 is in the range of [3, 4).
        // note: span1 [0, 3) is not in the range because [3, 4) is not collapsed.
        final TestSpan[] spans3to4 = transformedText.getSpans(3, 4, TestSpan.class);
        assertThat(spans3to4.length).isEqualTo(1);
        assertThat(spans3to4[0]).isEqualTo(span2);

        // span1 and span2 are in the range of [1, 5).
        final TestSpan[] spans1to4 = transformedText.getSpans(1, 4, TestSpan.class);
        assertThat(spans1to4.length).isEqualTo(2);
        assertThat(spans1to4[0]).isEqualTo(span1);
        assertThat(spans1to4[1]).isEqualTo(span2);

        // only span2 is in the range of [3, 5).
        final TestSpan[] spans3to5 = transformedText.getSpans(3, 5, TestSpan.class);
        assertThat(spans3to5.length).isEqualTo(1);
        assertThat(spans3to5[0]).isEqualTo(span2);

        // span2 and span3 are in the range of [3, 6).
        final TestSpan[] spans3to6 = transformedText.getSpans(3, 6, TestSpan.class);
        assertThat(spans3to6.length).isEqualTo(2);
        assertThat(spans3to6[0]).isEqualTo(span2);
        assertThat(spans3to6[1]).isEqualTo(span3);

        // only span3 is in the range of [5, 6).
        final TestSpan[] spans5to6 = transformedText.getSpans(5, 6, TestSpan.class);
        assertThat(spans5to6.length).isEqualTo(1);
        assertThat(spans5to6[0]).isEqualTo(span3);

        // there is no span in the range of [6, 8)
        final TestSpan[] spans6to8 = transformedText.getSpans(6, 8, TestSpan.class);
        assertThat(spans6to8.length).isEqualTo(0);

        // When it's singleLine, there should be a ReplacementSpan in the range [3, 4)
        final ReplacementSpan[] replacementSpans3to4 =
                transformedText.getSpans(3, 4, ReplacementSpan.class);
        assertThat(replacementSpans3to4.length).isEqualTo(1);

        final ReplacementSpan[] replacementSpans0to3 =
                transformedText.getSpans(0, 3, ReplacementSpan.class);
        assertThat(replacementSpans0to3.length).isEqualTo(0);

        final ReplacementSpan[] replacementSpans4to8 =
                transformedText.getSpans(4, 8, ReplacementSpan.class);
        assertThat(replacementSpans4to8.length).isEqualTo(0);
    }

    @Test
    public void transformedText_getSpans_collapsedRange() {
        final SpannableString text = new SpannableString(TEXT);
        final TestSpan span1 = new TestSpan();
        final TestSpan span2 = new TestSpan();
        final TestSpan span3 = new TestSpan();

        text.setSpan(span1, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span2, 3, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span3, 3, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // In the transformedText "abc\n\n def", the new ranges of the spans are:
        // span1: [0, 3)
        // span2: [3, 3)
        // span3: [5, 6)
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, false, null);
        final Spanned transformedText =
                (Spanned) transformationMethod.getTransformation(text, sView);

        // only span1 is in the range of [0, 0).
        final TestSpan[] spans0to0 = transformedText.getSpans(0, 0, TestSpan.class);
        assertThat(spans0to0.length).isEqualTo(1);
        assertThat(spans0to0[0]).isEqualTo(span1);

        // span1 and span 2 are in the range of [3, 3).
        final TestSpan[] spans3to3 = transformedText.getSpans(3, 3, TestSpan.class);
        assertThat(spans3to3.length).isEqualTo(2);
        assertThat(spans3to3[0]).isEqualTo(span1);
        assertThat(spans3to3[1]).isEqualTo(span2);

        // only the span2 with collapsed range is in the range of [3, 4).
        final TestSpan[] spans3to4 = transformedText.getSpans(3, 4, TestSpan.class);
        assertThat(spans3to4.length).isEqualTo(1);
        assertThat(spans3to4[0]).isEqualTo(span2);

        // no span is in the range of [4, 5). (span2 is not mistakenly included.)
        final TestSpan[] spans4to5 = transformedText.getSpans(4, 5, TestSpan.class);
        assertThat(spans4to5).isEmpty();

        // only span3 is in the range of [4, 6). (span2 is not mistakenly included.)
        final TestSpan[] spans4to6 = transformedText.getSpans(4, 6, TestSpan.class);
        assertThat(spans4to6.length).isEqualTo(1);
        assertThat(spans4to6[0]).isEqualTo(span3);

        // no span is in the range of [4, 4).
        final TestSpan[] spans4to4 = transformedText.getSpans(4, 4, TestSpan.class);
        assertThat(spans4to4.length).isEqualTo(0);

        // span3 is in the range of [5, 5).
        final TestSpan[] spans5to5 = transformedText.getSpans(5, 5, TestSpan.class);
        assertThat(spans5to5.length).isEqualTo(1);
        assertThat(spans5to5[0]).isEqualTo(span3);

        // span3 is in the range of [6, 6).
        final TestSpan[] spans6to6 = transformedText.getSpans(6, 6, TestSpan.class);
        assertThat(spans6to6.length).isEqualTo(1);
        assertThat(spans6to6[0]).isEqualTo(span3);
    }

    @Test
    public void transformedText_getSpans_collapsedRange_singleLine() {
        final SpannableString text = new SpannableString(TEXT);
        final TestSpan span1 = new TestSpan();
        final TestSpan span2 = new TestSpan();
        final TestSpan span3 = new TestSpan();

        text.setSpan(span1, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span2, 3, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span3, 3, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // In the transformedText "abc\uFFFD def", the new ranges of the spans are:
        // span1: [0, 3)
        // span2: [3, 3)
        // span3: [4, 5)
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, true, null);
        final Spanned transformedText =
                (Spanned) transformationMethod.getTransformation(text, sView);

        // only span1 is in the range of [0, 0).
        final TestSpan[] spans0to0 = transformedText.getSpans(0, 0, TestSpan.class);
        assertThat(spans0to0.length).isEqualTo(1);
        assertThat(spans0to0[0]).isEqualTo(span1);

        // span1 and span2 are in the range of [3, 3).
        final TestSpan[] spans3to3 = transformedText.getSpans(3, 3, TestSpan.class);
        assertThat(spans3to3.length).isEqualTo(2);
        assertThat(spans3to3[0]).isEqualTo(span1);
        assertThat(spans3to3[1]).isEqualTo(span2);

        // only the span2 with collapsed range is in the range of [3, 4).
        final TestSpan[] spans3to4 = transformedText.getSpans(3, 4, TestSpan.class);
        assertThat(spans3to4.length).isEqualTo(1);
        assertThat(spans3to4[0]).isEqualTo(span2);

        // span3 is in the range of [4, 5). (span2 is not mistakenly included.)
        final TestSpan[] spans4to5 = transformedText.getSpans(4, 5, TestSpan.class);
        assertThat(spans4to5.length).isEqualTo(1);
        assertThat(spans4to5[0]).isEqualTo(span3);

        // only span3 is in the range of [4, 6). (span2 is not mistakenly included.)
        final TestSpan[] spans4to6 = transformedText.getSpans(4, 6, TestSpan.class);
        assertThat(spans4to6.length).isEqualTo(1);
        assertThat(spans4to6[0]).isEqualTo(span3);

        // span3 is in the range of [4, 4).
        final TestSpan[] spans4to4 = transformedText.getSpans(4, 4, TestSpan.class);
        assertThat(spans4to4.length).isEqualTo(1);
        assertThat(spans4to4[0]).isEqualTo(span3);

        // span3 is in the range of [5, 5).
        final TestSpan[] spans5to5 = transformedText.getSpans(5, 5, TestSpan.class);
        assertThat(spans5to5.length).isEqualTo(1);
        assertThat(spans5to5[0]).isEqualTo(span3);
    }

    @Test
    public void transformedText_getSpanStartAndEnd() {
        final SpannableString text = new SpannableString(TEXT);
        final TestSpan span1 = new TestSpan();
        final TestSpan span2 = new TestSpan();
        final TestSpan span3 = new TestSpan();
        final TestSpan span4 = new TestSpan();
        final TestSpan span5 = new TestSpan();

        text.setSpan(span1, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span2, 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span3, 4, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span4, 3, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span5, 3, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // In the transformedText, the new ranges of the spans are:
        // span1: [0, 3)
        // span2: [2, 6)
        // span3: [6, 7)
        // span4: [3, 3)
        // span5: [5, 6)
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, false, null);
        final Spanned transformedText =
                (Spanned) transformationMethod.getTransformation(text, sView);

        assertThat(transformedText.getSpanStart(span1)).isEqualTo(0);
        assertThat(transformedText.getSpanEnd(span1)).isEqualTo(3);

        assertThat(transformedText.getSpanStart(span2)).isEqualTo(2);
        assertThat(transformedText.getSpanEnd(span2)).isEqualTo(6);

        assertThat(transformedText.getSpanStart(span3)).isEqualTo(6);
        assertThat(transformedText.getSpanEnd(span3)).isEqualTo(7);

        assertThat(transformedText.getSpanStart(span4)).isEqualTo(3);
        assertThat(transformedText.getSpanEnd(span4)).isEqualTo(3);

        assertThat(transformedText.getSpanStart(span5)).isEqualTo(5);
        assertThat(transformedText.getSpanEnd(span5)).isEqualTo(6);
    }

    @Test
    public void transformedText_getSpanStartAndEnd_singleLine() {
        final SpannableString text = new SpannableString(TEXT);
        final TestSpan span1 = new TestSpan();
        final TestSpan span2 = new TestSpan();
        final TestSpan span3 = new TestSpan();
        final TestSpan span4 = new TestSpan();
        final TestSpan span5 = new TestSpan();

        text.setSpan(span1, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span2, 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span3, 4, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span4, 3, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span5, 3, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // In the transformedText, the new ranges of the spans are:
        // span1: [0, 3)
        // span2: [2, 5)
        // span3: [5, 6)
        // span4: [3. 3)
        // span5: [4, 5)
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, true, null);
        final Spanned transformedText =
                (Spanned) transformationMethod.getTransformation(text, sView);

        assertThat(transformedText.getSpanStart(span1)).isEqualTo(0);
        assertThat(transformedText.getSpanEnd(span1)).isEqualTo(3);

        assertThat(transformedText.getSpanStart(span2)).isEqualTo(2);
        assertThat(transformedText.getSpanEnd(span2)).isEqualTo(5);

        assertThat(transformedText.getSpanStart(span3)).isEqualTo(5);
        assertThat(transformedText.getSpanEnd(span3)).isEqualTo(6);

        assertThat(transformedText.getSpanStart(span4)).isEqualTo(3);
        assertThat(transformedText.getSpanEnd(span4)).isEqualTo(3);

        assertThat(transformedText.getSpanStart(span5)).isEqualTo(4);
        assertThat(transformedText.getSpanEnd(span5)).isEqualTo(5);

        final ReplacementSpan[] replacementSpans =
                transformedText.getSpans(0, 8, ReplacementSpan.class);
        assertThat(transformedText.getSpanStart(replacementSpans[0])).isEqualTo(3);
        assertThat(transformedText.getSpanEnd(replacementSpans[0])).isEqualTo(4);
    }

    @Test
    public void transformedText_getSpanFlag() {
        transformedText_getSpanFlag(false);
    }

    @Test
    public void transformedText_getSpanFlag_singleLine() {
        transformedText_getSpanFlag(true);
    }

    public void transformedText_getSpanFlag(boolean singleLine) {
        final SpannableString text = new SpannableString(TEXT);
        final TestSpan span1 = new TestSpan();
        final TestSpan span2 = new TestSpan();
        final TestSpan span3 = new TestSpan();

        text.setSpan(span1, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span2, 2, 4, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        text.setSpan(span3, 4, 5, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, singleLine, null);
        final Spanned transformedText =
                (Spanned) transformationMethod.getTransformation(text, sView);

        assertThat(transformedText.getSpanFlags(span1)).isEqualTo(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertThat(transformedText.getSpanFlags(span2)).isEqualTo(Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        assertThat(transformedText.getSpanFlags(span3)).isEqualTo(Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
    }

    @Test
    public void transformedText_nextSpanTransition() {
        final SpannableString text = new SpannableString(TEXT);
        final TestSpan span1 = new TestSpan();
        final TestSpan span2 = new TestSpan();
        final TestSpan span3 = new TestSpan();


        text.setSpan(span1, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span2, 1, 4, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        text.setSpan(span3, 4, 5, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        // In the transformedText, the new ranges of the spans are:
        // span1: [0, 3)
        // span2: [1, 6)
        // span3: [6, 7)
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, false, null);
        final Spanned transformedText =
                (Spanned) transformationMethod.getTransformation(text, sView);
        final int[] expectedTransitions = new int[] { 0, 1, 3, 6, 7 };
        assertNextSpanTransition(transformedText, expectedTransitions, TestSpan.class);
    }

    @Test
    public void transformedText_nextSpanTransition_singleLine() {
        final SpannableString text = new SpannableString(TEXT);
        final TestSpan span1 = new TestSpan();
        final TestSpan span2 = new TestSpan();
        final TestSpan span3 = new TestSpan();


        text.setSpan(span1, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(span2, 1, 4, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        text.setSpan(span3, 4, 5, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        // In the transformedText, the new ranges of the spans are:
        // span1: [0, 3)
        // span2: [1, 5)
        // span3: [5, 6)
        // there is also a ReplacementSpan at range [3, 4)
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, true, null);
        final Spanned transformedText =
                (Spanned) transformationMethod.getTransformation(text, sView);
        final int[] expectedTransitions = new int[] { 0, 1, 3, 4, 5, 6 };
        assertNextSpanTransition(transformedText, expectedTransitions, Object.class);
    }

    @Test
    public void transformedText_originalToTransformed() {
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(2, false, null);
        final OffsetMapping transformedText =
                (OffsetMapping) transformationMethod.getTransformation(TEXT, sView);

        // "abc def" is transformed to "ab\n\nc def".
        final int[] mappedCharacterOffsets = new int[] { 0, 1, 4, 5, 6, 7, 8 };
        assertOriginalToTransformed(transformedText, OffsetMapping.MAP_STRATEGY_CHARACTER,
                mappedCharacterOffsets);

        // "abc def" is transformed to "ab\n\nc def".
        // the cursor before 'c' is mapped to index position before "\n\n".
        final int[] mappedCursorOffsets = new int[] { 0, 1, 2, 5, 6, 7, 8 };
        assertOriginalToTransformed(transformedText, OffsetMapping.MAP_STRATEGY_CURSOR,
                mappedCursorOffsets);
    }

    @Test
    public void transformedText_originalToTransformed_singleLine() {
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(2, true, null);
        final OffsetMapping transformedText =
                (OffsetMapping) transformationMethod.getTransformation(TEXT, sView);

        // "abc def" is transformed to "ab\uFFFDc def".
        final int[] mappedCharacterOffsets = new int[] { 0, 1, 3, 4, 5, 6, 7 };
        assertOriginalToTransformed(transformedText, OffsetMapping.MAP_STRATEGY_CHARACTER,
                mappedCharacterOffsets);

        // "abc def" is transformed to "ab\uFFFDc def".
        // the cursor before 'c' is mapped to index position before "\uFFFD".
        final int[] mappedCursorOffsets = new int[] { 0, 1, 3, 4, 5, 6, 7 };
        assertOriginalToTransformed(transformedText, OffsetMapping.MAP_STRATEGY_CHARACTER,
                mappedCursorOffsets);
    }

    @Test
    public void transformedText_transformedToOriginal() {
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(2, false, null);
        final OffsetMapping transformedText =
                (OffsetMapping) transformationMethod.getTransformation(TEXT, sView);

        // "abc def" is transformed to "ab\n\nc def".
        // the two '\n' characters have no corresponding character; map them to 'c'.
        final int[] mappedCharacterOffsets = new int[] { 0, 1, 2, 2, 2, 3, 4, 5, 6 };
        assertTransformedToOriginal(transformedText, OffsetMapping.MAP_STRATEGY_CHARACTER,
                mappedCharacterOffsets);

        // offset 2 and 3 (cursor positions before the two '\n' characters) are mapped to index 2
        // (cursor position before 'c' in the original text)
        final int[] mappedCursorOffsets = new int[] { 0, 1, 2, 2, 2, 3, 4, 5, 6 };
        assertTransformedToOriginal(transformedText, OffsetMapping.MAP_STRATEGY_CURSOR,
                mappedCursorOffsets);
    }

    @Test
    public void transformedText_transformedToOriginal_singleLine() {
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(2, true, null);
        final OffsetMapping transformedText =
                (OffsetMapping) transformationMethod.getTransformation(TEXT, sView);

        // "abc def" is transformed to "ab\uFFFDc def".
        // '\uFFFD' has no corresponding character; map it to 'c'.
        final int[] mappedCharacterOffsets = new int[] { 0, 1, 2, 2, 3, 4, 5, 6 };
        assertTransformedToOriginal(transformedText, OffsetMapping.MAP_STRATEGY_CHARACTER,
                mappedCharacterOffsets);

        // offset 2 (cursor positions before '\uFFFD') is mapped to index 2 (cursor position before
        // 'c' in the original text)
        final int[] mappedCursorOffsets = new int[] { 0, 1, 2, 2, 3, 4, 5, 6 };
        assertTransformedToOriginal(transformedText, OffsetMapping.MAP_STRATEGY_CHARACTER,
                mappedCursorOffsets);
    }

    @Test
    public void transformedText_getHighlightStartAndEnd_insertion() {
        transformedText_getHighlightStartAndEnd_insertion(false, "\n\n");
    }

    @Test
    public void transformedText_getHighlightStartAndEnd_singleLine_insertion() {
        transformedText_getHighlightStartAndEnd_insertion(true, "\uFDDD");
    }

    public void transformedText_getHighlightStartAndEnd_insertion(boolean singleLine,
            String placeholder) {
        final SpannableStringBuilder text = new SpannableStringBuilder(TEXT);
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, singleLine, null);
        final InsertModeTransformationMethod.TransformedText transformedText =
                (InsertModeTransformationMethod.TransformedText) transformationMethod
                        .getTransformation(text, sView);
        // TransformationMethod is set on the original text as a TextWatcher in the TextView.
        text.setSpan(transformationMethod, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        // note: the placeholder text is also highlighted.
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(3 + placeholder.length());

        // original text is "abcxx def" after insertion.
        // the placeholder is now inserted at index 5.
        // the highlight start is still 3.
        // the highlight end now is 5 + placeholder.length(), including the newly inserted text.
        text.insert(3, "xx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(5 + placeholder.length());

        // original text is "abcxxvv def" after insertion.
        // the placeholder is now inserted at index 7.
        // the highlight start is still 3.
        // the highlight end now is 7 + placeholder.length(), including the newly inserted text.
        text.insert(5, "vv");
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(7 + placeholder.length());

        // original text is "abzzcxxvv def" after insertion.
        // the placeholder is now inserted at index 9.
        // the highlight start is 5, since the insertion happens before the highlight range.
        // the highlight end now is 9 + placeholder.length().
        text.insert(2, "zz");
        assertThat(transformedText.getHighlightStart()).isEqualTo(5);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(9 + placeholder.length());

        // original text is "abzzcxxvv iidef" after insertion.
        // the placeholder is still inserted at index 9.
        // the highlight start is still 5, since the insertion happens after the highlight range.
        // the highlight end now is 9 + placeholder.length().
        text.insert(10, "ii");
        assertThat(transformedText.getHighlightStart()).isEqualTo(5);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(9 + placeholder.length());

    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_getHighlightStartAndEnd_deletion() {
        transformedText_getHighlightStartAndEnd_deletion(false, "\n\n");
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_getHighlightStartAndEnd_singleLine_deletion() {
        transformedText_getHighlightStartAndEnd_deletion(true, "\uFDDD");
    }

    private void transformedText_getHighlightStartAndEnd_deletion(boolean singleLine,
            String placeholder) {
        final SpannableStringBuilder text = new SpannableStringBuilder(TEXT);
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, singleLine, null);
        final InsertModeTransformationMethod.TransformedText transformedText =
                (InsertModeTransformationMethod.TransformedText) transformationMethod
                        .getTransformation(text, sView);
        // TransformationMethod is set on the original text as a TextWatcher in the TextView.
        text.setSpan(transformationMethod, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        // note: the placeholder text is also highlighted.
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(3 + placeholder.length());

        // original text is "abcxxxxxx def" after insertion.
        // the placeholder is now inserted at index 9.
        // the highlight start is still 3.
        // the highlight end now is 9 + placeholder.length().
        text.insert(3, "xxxxxx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(9 + placeholder.length());

        // original text is "abxxxxxx def" after deletion.
        // the placeholder is now inserted at index 6.
        // the highlight start is 2, since the deletion happens before the highlight range.
        // the highlight end now is 8 + placeholder.length().
        text.delete(2, 3);
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(8 + placeholder.length());

        // original text is "abxxx def" after deletion.
        // the placeholder is now inserted at index 5.
        // the highlight start is still 2, since the deletion happens in the highlight range.
        // the highlight end now is 5 + placeholder.length().
        text.delete(2, 5);
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(5 + placeholder.length());

        // original text is "abxxx d" after deletion.
        // the placeholder is now inserted at index 5.
        // the highlight start is still 2, since the deletion happens after the highlight range.
        // the highlight end now is still 5 + placeholder.length().
        text.delete(7, 9);
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(5 + placeholder.length());

        // original text is "af" after deletion.
        // the placeholder is now inserted at index 1.
        // the highlight start is 1, since the deletion covers highlight range.
        // the highlight end is 1 + placeholder.length().
        text.delete(1, 5);
        assertThat(transformedText.getHighlightStart()).isEqualTo(1);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(1 + placeholder.length());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_getHighlightStartAndEnd_deletion_stickyHighlightRange() {
        transformedText_getHighlightStartAndEnd_deletion_stickyHighlightRange(false, "\n\n");
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_getHighlightStartAndEnd_singleLine_deletion_stickyHighlightRange() {
        transformedText_getHighlightStartAndEnd_deletion_stickyHighlightRange(true, "\uFDDD");
    }

    private void transformedText_getHighlightStartAndEnd_deletion_stickyHighlightRange(
            boolean singleLine, String placeholder) {
        final SpannableStringBuilder text = new SpannableStringBuilder(TEXT);
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, singleLine, null);
        final InsertModeTransformationMethod.TransformedText transformedText =
                (InsertModeTransformationMethod.TransformedText) transformationMethod
                        .getTransformation(text, sView);
        // TransformationMethod is set on the original text as a TextWatcher in the TextView.
        text.setSpan(transformationMethod, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        // note: the placeholder text is also highlighted.
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(3 + placeholder.length());

        // original text is "abcxxxxxx def" after insertion.
        // the placeholder is now inserted at index 9.
        // the highlight start is still 3.
        // the highlight end now is 9 + placeholder.length().
        text.insert(3, "xxxxxx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(9 + placeholder.length());

        // original text is "abxxxxxx def" after deletion.
        // the placeholder is now inserted at index 6.
        // the highlight start is 2, since the deletion happens before the highlight range.
        // the highlight end now is 8 + placeholder.length().
        text.delete(2, 3);
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(8 + placeholder.length());

        // original text is "abxxx def" after deletion.
        // the placeholder is now inserted at index 5.
        // the highlight start is still 2, since the deletion happens in the highlight range.
        // the highlight end now is 5 + placeholder.length().
        text.delete(2, 5);
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(5 + placeholder.length());

        // original text is "abxxx d" after deletion.
        // the placeholder is now inserted at index 5.
        // the highlight start is still 2, since the deletion happens after the highlight range.
        // the highlight end now is still 5 + placeholder.length().
        text.delete(7, 9);
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(5 + placeholder.length());

        // original text is "axx d" after deletion.
        // the placeholder is now inserted at index 3.
        // the highlight start is at 2, since the deletion range covers the start.
        // the highlight end is 3 + placeholder.length().
        text.delete(1, 3);
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(3 + placeholder.length());

        // original text is "ax" after deletion.
        // the placeholder is now inserted at index 2.
        // the highlight start is at 2.
        // the highlight end is 2 + placeholder.length(). It wants to stay at 3, but it'll be out
        // of bounds, so it'll be 2 instead.
        text.delete(2, 5);
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(2 + placeholder.length());
    }


    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_getHighlightStartAndEnd_replace() {
        transformedText_getHighlightStartAndEnd_replace(false, "\n\n");
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_getHighlightStartAndEnd_singleLine_replace() {
        transformedText_getHighlightStartAndEnd_replace(true, "\uFDDD");
    }

    public void transformedText_getHighlightStartAndEnd_replace(boolean singleLine,
            String placeholder) {
        final SpannableStringBuilder text = new SpannableStringBuilder(TEXT);
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, singleLine, null);
        final InsertModeTransformationMethod.TransformedText transformedText =
                (InsertModeTransformationMethod.TransformedText) transformationMethod
                        .getTransformation(text, sView);
        // TransformationMethod is set on the original text as a TextWatcher in the TextView.
        text.setSpan(transformationMethod, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        // note: the placeholder text is also highlighted.
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(3 + placeholder.length());

        // original text is "abcxxxxxx def" after insertion.
        // the placeholder is now inserted at index 9.
        // the highlight start is still 3.
        // the highlight end now is 9 + placeholder.length().
        text.insert(3, "xxxxxx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(9 + placeholder.length());

        // original text is "abvvxxxxxx def" after replace.
        // the replacement happens before the highlight range; highlight range is offset by 1
        // the placeholder is now inserted at index 10,
        // the highlight start is 4.
        // the highlight end is 10 + placeholder.length().
        text.replace(2, 3, "vv");
        assertThat(transformedText.getHighlightStart()).isEqualTo(4);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(10 + placeholder.length());

        // original text is "abvvxxx def" after replace.
        // the replacement happens in the highlight range; highlight end is offset by -3
        // the placeholder is now inserted at index 7,
        // the highlight start is still 4.
        // the highlight end is 7 + placeholder.length().
        text.replace(5, 9, "x");
        assertThat(transformedText.getHighlightStart()).isEqualTo(4);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(7 + placeholder.length());

        // original text is "abvvxxxvvv" after replace.
        // the replacement happens after the highlight range; highlight is not changed
        // the placeholder is now inserted at index 7,
        // the highlight start is still 4.
        // the highlight end is 7 + placeholder.length().
        text.replace(7, 11, "vvv");
        assertThat(transformedText.getHighlightStart()).isEqualTo(4);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(7 + placeholder.length());

        // original text is "abxxxxvvv" after replace.
        // the replacement happens covers the highlight start; highlight start extends to the
        // replacement start; highlight end is offset by -1
        // the placeholder is now inserted at index 6,
        // the highlight start is 2.
        // the highlight end is 6 + placeholder.length().
        text.replace(2, 5, "xx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(6 + placeholder.length());

        // original text is "abxxxxxvv" after replace.
        // the replacement happens covers the highlight end; highlight end extends to the
        // replacement end; highlight start stays the same
        // the placeholder is now inserted at index 7,
        // the highlight start is 2.
        // the highlight end is 7 + placeholder.length().
        text.replace(5, 7, "xx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(2);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(7 + placeholder.length());

        // original text is "axxv" after replace.
        // the replacement happens covers the highlight range; highlight start is set to the
        // replacement start; highlight end is set to the replacement end
        // the placeholder is now inserted at index 3,
        // the highlight start is 1.
        // the highlight end is 3 + placeholder.length().
        text.replace(1, 8, "xx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(1);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(3 + placeholder.length());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_getHighlightStartAndEnd_replace_stickyHighlightRange() {
        transformedText_getHighlightStartAndEnd_replace_stickyHighlightRange(false, "\n\n");
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_INSERT_MODE_HIGHLIGHT_RANGE)
    public void transformedText_getHighlightStartAndEnd_singleLine_replace_stickyHighlightRange() {
        transformedText_getHighlightStartAndEnd_replace_stickyHighlightRange(true, "\uFDDD");
    }

    private void transformedText_getHighlightStartAndEnd_replace_stickyHighlightRange(
            boolean singleLine, String placeholder) {
        final SpannableStringBuilder text = new SpannableStringBuilder(TEXT);
        final InsertModeTransformationMethod transformationMethod =
                new InsertModeTransformationMethod(3, singleLine, null);
        final InsertModeTransformationMethod.TransformedText transformedText =
                (InsertModeTransformationMethod.TransformedText) transformationMethod
                        .getTransformation(text, sView);
        // TransformationMethod is set on the original text as a TextWatcher in the TextView.
        text.setSpan(transformationMethod, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        // note: the placeholder text is also highlighted.
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(3 + placeholder.length());

        // original text is "abcxxxxxx def" after insertion.
        // the placeholder is now inserted at index 9.
        // the highlight start is still 3.
        // the highlight end now is 9 + placeholder.length().
        text.insert(3, "xxxxxx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(3);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(9 + placeholder.length());

        // original text is "abvvxxxxxx def" after replace.
        // the replacement happens before the highlight range; highlight range is offset by 1
        // the placeholder is now inserted at index 10,
        // the highlight start is 4.
        // the highlight end is 10 + placeholder.length().
        text.replace(2, 3, "vv");
        assertThat(transformedText.getHighlightStart()).isEqualTo(4);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(10 + placeholder.length());

        // original text is "abvvxxx def" after replace.
        // the replacement happens in the highlight range; highlight end is offset by -3
        // the placeholder is now inserted at index 7,
        // the highlight start is still 4.
        // the highlight end is 7 + placeholder.length().
        text.replace(5, 9, "x");
        assertThat(transformedText.getHighlightStart()).isEqualTo(4);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(7 + placeholder.length());

        // original text is "abvvxxxvvv" after replace.
        // the replacement happens after the highlight range; highlight is not changed
        // the placeholder is now inserted at index 7,
        // the highlight start is still 4.
        // the highlight end is 7 + placeholder.length().
        text.replace(7, 11, "vvv");
        assertThat(transformedText.getHighlightStart()).isEqualTo(4);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(7 + placeholder.length());

        // original text is "abxxxxvvv" after replace.
        // the replacement covers the highlight start; highlight start stays the same;
        // highlight end is offset by -1
        // the placeholder is now inserted at index 6,
        // the highlight start is 4.
        // the highlight end is 6 + placeholder.length().
        text.replace(2, 5, "xx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(4);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(6 + placeholder.length());

        // original text is "abxxxxxvv" after replace.
        // the replacement covers the highlight end; highlight end stays the same;
        // highlight start stays the same
        // the placeholder is now inserted at index 6,
        // the highlight start is 2.
        // the highlight end is 6 + placeholder.length().
        text.replace(5, 7, "xx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(4);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(6 + placeholder.length());

        // original text is "axxv" after replace.
        // the replacement covers the highlight range; highlight start stays the same.
        // highlight end shrink to the text length.
        // the placeholder is now inserted at index 3,
        // the highlight start is 2.
        // the highlight end is 4 + placeholder.length().
        text.replace(1, 8, "xx");
        assertThat(transformedText.getHighlightStart()).isEqualTo(4);
        assertThat(transformedText.getHighlightEnd()).isEqualTo(4 + placeholder.length());
    }

    private static  <T> void assertNextSpanTransition(Spanned spanned, int[] transitions,
            Class<T> type) {
        int currentTransition = 0;
        for (int transition : transitions) {
            assertThat(currentTransition).isEqualTo(transition);
            currentTransition =
                    spanned.nextSpanTransition(currentTransition, spanned.length(), type);
        }

        // Make sure there is no transition after the currentTransition.
        assertThat(currentTransition).isEqualTo(spanned.length());
    }

    private static void assertCharSequence(CharSequence actual, CharSequence expected) {
        assertThat(actual.length()).isEqualTo(expected.length());
        for (int index = 0; index < actual.length(); ++index) {
            assertThat(actual.charAt(index)).isEqualTo(expected.charAt(index));
        }
    }

    private static void assertOriginalToTransformed(OffsetMapping transformedText, int strategy,
            int[] expected) {
        for (int offset = 0; offset < expected.length; ++offset) {
            assertThat(transformedText.originalToTransformed(offset, strategy))
                    .isEqualTo(expected[offset]);
        }
    }

    private static void assertTransformedToOriginal(OffsetMapping transformedText, int strategy,
            int[] expected) {
        for (int offset = 0; offset < expected.length; ++offset) {
            assertThat(transformedText.transformedToOriginal(offset, strategy))
                    .isEqualTo(expected[offset]);
        }
    }

    private static class TestSpan { }
}
