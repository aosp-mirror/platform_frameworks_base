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

import android.graphics.Typeface;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.*;
import android.text.style.*;
import android.util.Log;

import junit.framework.TestCase;

/**
 * SpannedTest tests some features of Spanned
 */
public class SpannedTest extends TestCase {
    private int mExpect;

    @SmallTest
    public void testSpannableString() throws Exception {
        checkPriority(new SpannableString("the quick brown fox"));
    }

    @SmallTest
    public void testSpannableStringBuilder() throws Exception {
        checkPriority2(new SpannableStringBuilder("the quick brown fox"));
    }

    @SmallTest
    public void testAppend() throws Exception {
        Object o = new Object();
        SpannableString ss = new SpannableString("Test");
        ss.setSpan(o, 0, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append(ss);
        assertEquals(0, ssb.getSpanStart(o));
        assertEquals(4, ssb.getSpanEnd(o));
        assertEquals(1, ssb.getSpans(0, 4, Object.class).length);

        ssb.insert(0, ss);
        assertEquals(4, ssb.getSpanStart(o));
        assertEquals(8, ssb.getSpanEnd(o));
        assertEquals(0, ssb.getSpans(0, 4, Object.class).length);
        assertEquals(1, ssb.getSpans(4, 8, Object.class).length);
    }

    @SmallTest
    public void testWrapParcel() {
        SpannableString s = new SpannableString("Hello there world");
        CharacterStyle mark = new StyleSpan(Typeface.BOLD);
        s.setSpan(mark, 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(CharacterStyle.wrap(mark), 3, 7,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new TextAppearanceSpan("mono", 0, -1, null, null), 7, 8,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(CharacterStyle.wrap(new TypefaceSpan("mono")), 8, 9,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        Parcel p = Parcel.obtain();
        TextUtils.writeToParcel(s, p, 0);
        p.setDataPosition(0);

        Spanned s2 = (Spanned) TextUtils.CHAR_SEQUENCE_CREATOR.
                        createFromParcel(p);
        StyleSpan[] style;

        style = s2.getSpans(1, 2, StyleSpan.class);
        assertEquals(1, style.length);
        assertEquals(1, s2.getSpanStart(style[0]));
        assertEquals(2, s2.getSpanEnd(style[0]));

        style = s2.getSpans(3, 7, StyleSpan.class);
        assertEquals(1, style.length);
        assertEquals(3, s2.getSpanStart(style[0]));
        assertEquals(7, s2.getSpanEnd(style[0]));

        TextAppearanceSpan[] appearance = s2.getSpans(7, 8,
                                                TextAppearanceSpan.class);
        assertEquals(1, appearance.length);
        assertEquals(7, s2.getSpanStart(appearance[0]));
        assertEquals(8, s2.getSpanEnd(appearance[0]));

        TypefaceSpan[] tf = s2.getSpans(8, 9, TypefaceSpan.class);
        assertEquals(1, tf.length);
        assertEquals(8, s2.getSpanStart(tf[0]));
        assertEquals(9, s2.getSpanEnd(tf[0]));
    }

    private void checkPriority(Spannable s) {
        s.setSpan(new Object(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE |
                                      (5 << Spannable.SPAN_PRIORITY_SHIFT));
        s.setSpan(new Object(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE |
                                      (10 << Spannable.SPAN_PRIORITY_SHIFT));
        s.setSpan(new Object(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE |
                                      (0 << Spannable.SPAN_PRIORITY_SHIFT));
        s.setSpan(new Object(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE |
                                      (15 << Spannable.SPAN_PRIORITY_SHIFT));
        s.setSpan(new Object(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE |
                                      (3 << Spannable.SPAN_PRIORITY_SHIFT));
        s.setSpan(new Object(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE |
                                      (6 << Spannable.SPAN_PRIORITY_SHIFT));
        s.setSpan(new Object(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE |
                                      (0 << Spannable.SPAN_PRIORITY_SHIFT));

        Object[] spans = s.getSpans(0, s.length(), Object.class);

        for (int i = 0; i < spans.length - 1; i++) {
            assertEquals((s.getSpanFlags(spans[i]) & Spanned.SPAN_PRIORITY) >=
                         (s.getSpanFlags(spans[i + 1]) & Spanned.SPAN_PRIORITY),
                         true);
        }

        mExpect = 0;

        s.setSpan(new Watcher(2), 0, s.length(), 
                  Spannable.SPAN_INCLUSIVE_INCLUSIVE |
                  (2 << Spannable.SPAN_PRIORITY_SHIFT));
        s.setSpan(new Watcher(4), 0, s.length(), 
                  Spannable.SPAN_INCLUSIVE_INCLUSIVE |
                  (4 << Spannable.SPAN_PRIORITY_SHIFT));
        s.setSpan(new Watcher(1), 0, s.length(), 
                  Spannable.SPAN_INCLUSIVE_INCLUSIVE |
                  (1 << Spannable.SPAN_PRIORITY_SHIFT));
        s.setSpan(new Watcher(3), 0, s.length(), 
                  Spannable.SPAN_INCLUSIVE_INCLUSIVE |
                  (3 << Spannable.SPAN_PRIORITY_SHIFT));

        mExpect = 4;
        s.setSpan(new Object(), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertEquals(mExpect, 0);
    }

    private void checkPriority2(SpannableStringBuilder ssb) {
        checkPriority(ssb);

        mExpect = 4;
        ssb.insert(3, "something");
        assertEquals(mExpect, 0);
    }

    private class Watcher implements SpanWatcher, TextWatcher {
        private int mSequence;

        public Watcher(int sequence) {
            mSequence = sequence;
        }

        public void onSpanChanged(Spannable b, Object o, int s, int e,
                                  int st, int en) { }
        public void onSpanRemoved(Spannable b, Object o, int s, int e) { }

        public void onSpanAdded(Spannable b, Object o, int s, int e) {
            if (mExpect != 0) {
                assertEquals(mSequence, mExpect);
                mExpect = mSequence - 1;
            }
        }

        public void beforeTextChanged(CharSequence s, int start, int count,
                                      int after) { }
        public void onTextChanged(CharSequence s, int start, int before,
                                      int count) {
            if (mExpect != 0) {
                assertEquals(mSequence, mExpect);
                mExpect = mSequence - 1;
            }
        }

        public void afterTextChanged(Editable s) { }
    }
}
