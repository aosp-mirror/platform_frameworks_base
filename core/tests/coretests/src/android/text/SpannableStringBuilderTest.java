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
import static org.junit.Assert.assertNotNull;

import android.platform.test.annotations.Presubmit;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.SubscriptSpan;
import android.text.style.UnderlineSpan;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SpannableStringBuilderTest extends SpannableTest {

    protected Spannable newSpannableWithText(String text) {
        return new SpannableStringBuilder(text);
    }

    @Test
    public void testGetSpans_sortsByPriorityEvenWhenSortParamIsFalse() {
        String text = "p_in_s";
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        Object first = new SubscriptSpan();
        Object second = new UnderlineSpan();
        Object third = new BulletSpan();
        Object fourth = new QuoteSpan();

        builder.setSpan(first, 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(second, 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(third, 2, text.length(), 1 << Spanned.SPAN_PRIORITY_SHIFT);
        builder.setSpan(fourth, 0, text.length(), 2 << Spanned.SPAN_PRIORITY_SHIFT);

        Object[] spans = builder.getSpans(0, text.length(), Object.class, false);

        assertNotNull(spans);
        assertEquals(4, spans.length);
        // priority spans are first
        assertEquals(fourth, spans[0]);
        assertEquals(third, spans[1]);
        // other spans should be there
        assertEquals(second, spans[2]);
        assertEquals(first, spans[3]);
    }
}
