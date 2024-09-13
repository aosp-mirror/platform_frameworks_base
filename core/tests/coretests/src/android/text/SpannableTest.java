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

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.test.MoreAsserts;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public abstract class SpannableTest {

    protected abstract Spannable newSpannableWithText(String text);

    @Test
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

    @Test
    public void testRemoveSpanWithIntermediateFlag() {
        Spannable spannable = newSpannableWithText("abcdef");
        Object emptySpan = new Object();
        spannable.setSpan(emptySpan, 1, 1, 0);
        Object unemptySpan = new Object();
        spannable.setSpan(unemptySpan, 1, 2, 0);

        final CountDownLatch latch = new CountDownLatch(1);
        SpanWatcher watcher = new SpanWatcher() {
            @Override
            public void onSpanAdded(Spannable text, Object what, int start, int end) {}

            @Override
            public void onSpanRemoved(Spannable text, Object what, int start, int end) {
                latch.countDown();
            }

            @Override
            public void onSpanChanged(Spannable text, Object what, int ostart, int oend, int nstart,
                    int nend) {}
        };
        spannable.setSpan(watcher, 0, 2, 0);

        spannable.removeSpan(emptySpan, Spanned.SPAN_INTERMEDIATE);
        assertEquals(1, latch.getCount());
        spannable.removeSpan(unemptySpan);
        assertEquals(0, latch.getCount());
    }
}
