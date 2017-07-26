/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.text;

import static android.text.Layout.Alignment.ALIGN_NORMAL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.style.ReplacementSpan;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DynamicLayoutTest {
    private static final int WIDTH = 10000;

    @Test
    public void testGetBlocksAlwaysNeedToBeRedrawn_en() {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        final DynamicLayout layout = new DynamicLayout(builder, new TextPaint(), WIDTH,
                ALIGN_NORMAL, 0, 0, false);

        assertNull(layout.getBlocksAlwaysNeedToBeRedrawn());

        builder.append("abcd efg\n");
        builder.append("hijk lmn\n");
        assertNull(layout.getBlocksAlwaysNeedToBeRedrawn());

        builder.delete(0, builder.length());
        assertNull(layout.getBlocksAlwaysNeedToBeRedrawn());
    }

    private class MockReplacementSpan extends ReplacementSpan {
        public int getSize(Paint paint, CharSequence text, int start, int end,
                Paint.FontMetricsInt fm) {
            return 10;
        }

        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
                int y, int bottom, Paint paint) { }
    }

    @Test
    public void testGetBlocksAlwaysNeedToBeRedrawn_replacementSpan() {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        final DynamicLayout layout = new DynamicLayout(builder, new TextPaint(), WIDTH,
                ALIGN_NORMAL, 0, 0, false);

        assertNull(layout.getBlocksAlwaysNeedToBeRedrawn());

        builder.append("abcd efg\n");
        builder.append("hijk lmn\n");
        assertNull(layout.getBlocksAlwaysNeedToBeRedrawn());

        builder.setSpan(new MockReplacementSpan(), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertNotNull(layout.getBlocksAlwaysNeedToBeRedrawn());
        assertTrue(layout.getBlocksAlwaysNeedToBeRedrawn().contains(0));

        builder.setSpan(new MockReplacementSpan(), 9, 13, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertTrue(layout.getBlocksAlwaysNeedToBeRedrawn().contains(0));
        assertTrue(layout.getBlocksAlwaysNeedToBeRedrawn().contains(1));

        builder.delete(9, 13);
        assertTrue(layout.getBlocksAlwaysNeedToBeRedrawn().contains(0));
        assertFalse(layout.getBlocksAlwaysNeedToBeRedrawn().contains(1));

        builder.delete(0, 4);
        assertFalse(layout.getBlocksAlwaysNeedToBeRedrawn().contains(0));
        assertTrue(layout.getBlocksAlwaysNeedToBeRedrawn().isEmpty());
    }

    @Test
    public void testGetBlocksAlwaysNeedToBeRedrawn_thai() {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        final DynamicLayout layout = new DynamicLayout(builder, new TextPaint(), WIDTH,
                ALIGN_NORMAL, 0, 0, false);

        assertNull(layout.getBlocksAlwaysNeedToBeRedrawn());

        builder.append("\u0E22\u0E34\u0E19\u0E14\u0E35\u0E15\u0E49\u0E2D\u0E19\u0E23\u0E31\u0E1A");
        builder.append("\u0E2A\u0E39\u0E48");
        assertNull(layout.getBlocksAlwaysNeedToBeRedrawn());

        builder.append("\u0E48\u0E48\u0E48\u0E48\u0E48");
        assertNotNull(layout.getBlocksAlwaysNeedToBeRedrawn());
        assertTrue(layout.getBlocksAlwaysNeedToBeRedrawn().contains(0));

        builder.delete(builder.length() -5, builder.length());
        assertFalse(layout.getBlocksAlwaysNeedToBeRedrawn().contains(0));
        assertTrue(layout.getBlocksAlwaysNeedToBeRedrawn().isEmpty());
    }

    @Test
    public void testGetLineExtra_withoutLinespacing() {
        final SpannableStringBuilder text = new SpannableStringBuilder("a\nb\nc");
        final TextPaint textPaint = new TextPaint();

        // create a StaticLayout to check against
        final StaticLayout staticLayout = StaticLayout.Builder.obtain(text, 0,
                text.length(), textPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .build();

        // create the DynamicLayout
        final DynamicLayout dynamicLayout = new DynamicLayout(text,
                textPaint,
                WIDTH,
                ALIGN_NORMAL,
                1f /*spacingMultiplier*/,
                0 /*spacingAdd*/,
                false /*includepad*/);

        final int lineCount = staticLayout.getLineCount();
        assertEquals(lineCount, dynamicLayout.getLineCount());
        for (int i = 0; i < lineCount; i++) {
            assertEquals(staticLayout.getLineExtra(i), dynamicLayout.getLineExtra(i));
        }
    }

    @Test
    public void testGetLineExtra_withLinespacing() {
        final SpannableStringBuilder text = new SpannableStringBuilder("a\nb\nc");
        final TextPaint textPaint = new TextPaint();
        final float spacingMultiplier = 2f;
        final float spacingAdd = 4;

        // create a StaticLayout to check against
        final StaticLayout staticLayout = StaticLayout.Builder.obtain(text, 0,
                text.length(), textPaint, WIDTH)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(spacingAdd, spacingMultiplier)
                .build();

        // create the DynamicLayout
        final DynamicLayout dynamicLayout = new DynamicLayout(text,
                textPaint,
                WIDTH,
                ALIGN_NORMAL,
                spacingMultiplier,
                spacingAdd,
                false /*includepad*/);

        final int lineCount = staticLayout.getLineCount();
        assertEquals(lineCount, dynamicLayout.getLineCount());
        for (int i = 0; i < lineCount - 1; i++) {
            assertEquals(staticLayout.getLineExtra(i), dynamicLayout.getLineExtra(i));
        }
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetLineExtra_withNegativeValue() {
        final DynamicLayout layout = new DynamicLayout("", new TextPaint(), 10 /*width*/,
                ALIGN_NORMAL, 1.0f /*spacingMultiplier*/, 0f /*spacingAdd*/, false /*includepad*/);
        layout.getLineExtra(-1);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetLineExtra_withParamGreaterThanLineCount() {
        final DynamicLayout layout = new DynamicLayout("", new TextPaint(), 10 /*width*/,
                ALIGN_NORMAL, 1.0f /*spacingMultiplier*/, 0f /*spacingAdd*/, false /*includepad*/);
        layout.getLineExtra(100);
    }
}
