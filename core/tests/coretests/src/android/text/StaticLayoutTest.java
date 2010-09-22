/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.graphics.Paint.FontMetricsInt;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.Layout.Alignment;
import static android.text.Layout.Alignment.*;
import android.util.Log;

import junit.framework.TestCase;

/**
 * Tests StaticLayout vertical metrics behavior.
 * 
 * Requires disabling access checks in the vm since this calls package-private
 * APIs.
 * 
 * @Suppress
 */
public class StaticLayoutTest extends TestCase {

    /**
     * Basic test showing expected behavior and relationship between font
     * metrics and line metrics.
     */
    //@SmallTest
    public void testGetters1() {
        LayoutBuilder b = builder();
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        // check default paint
        Log.i("TG1:paint", fmi.toString());

        Layout l = b.build();
        assertVertMetrics(l, 0, 0,
                fmi.ascent, fmi.descent);

        // other quick metrics
        assertEquals(0, l.getLineStart(0));
        assertEquals(Layout.DIR_LEFT_TO_RIGHT, l.getParagraphDirection(0));
        assertEquals(false, l.getLineContainsTab(0));
        assertEquals(Layout.DIRS_ALL_LEFT_TO_RIGHT, l.getLineDirections(0));
        assertEquals(0, l.getEllipsisCount(0));
        assertEquals(0, l.getEllipsisStart(0));
        assertEquals(b.width, l.getEllipsizedWidth());
    }

    /**
     * Basic test showing effect of includePad = true with 1 line.
     * Top and bottom padding are affected, as is the line descent and height.
     */
    //@SmallTest
    public void testGetters2() {
        LayoutBuilder b = builder()
            .setIncludePad(true);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                fmi.top, fmi.bottom);
    }

    /**
     * Basic test showing effect of includePad = true wrapping to 2 lines.
     * Ascent of top line and descent of bottom line are affected.
     */
    //@SmallTest
    public void testGetters3() {
        LayoutBuilder b = builder()
            .setIncludePad(true)
            .setWidth(50);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        Layout l =  b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
            fmi.top, fmi.descent,
            fmi.ascent, fmi.bottom);
    }

    /**
     * Basic test showing effect of includePad = true wrapping to 3 lines.
     * First line ascent is top, bottom line descent is bottom.
     */
    //@SmallTest
    public void testGetters4() {
        LayoutBuilder b = builder()
            .setText("This is a longer test")
            .setIncludePad(true)
            .setWidth(50);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                fmi.top, fmi.descent,
                fmi.ascent, fmi.descent,
                fmi.ascent, fmi.bottom);
    }

    /**
     * Basic test showing effect of includePad = true wrapping to 3 lines and
     * large text. See effect of leading. Currently, we don't expect there to
     * even be non-zero leading.
     */
    //@SmallTest
    public void testGetters5() {
        LayoutBuilder b = builder()
            .setText("This is a longer test")
            .setIncludePad(true)
            .setWidth(150);
        b.paint.setTextSize(36);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        if (fmi.leading == 0) { // nothing to test
            Log.i("TG5", "leading is 0, skipping test");
            return;
        }

        // So far, leading is not used, so this is the same as TG4.  If we start
        // using leading, this will fail.
        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                fmi.top, fmi.descent,
                fmi.ascent, fmi.descent,
                fmi.ascent, fmi.bottom);
    }

    /**
     * Basic test showing effect of includePad = true, spacingAdd = 2, wrapping
     * to 3 lines.
     */
    //@SmallTest
    public void testGetters6() {
        int spacingAdd = 2; // int so expressions return int
        LayoutBuilder b = builder()
            .setText("This is a longer test")
            .setIncludePad(true)
            .setWidth(50)
            .setSpacingAdd(spacingAdd);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                fmi.top, fmi.descent + spacingAdd,
                fmi.ascent, fmi.descent + spacingAdd,
                fmi.ascent, fmi.bottom + spacingAdd);
    }

    /**
     * Basic test showing effect of includePad = true, spacingAdd = 2,
     * spacingMult = 1.5, wrapping to 3 lines.
     */
    //@SmallTest
    public void testGetters7() {
        LayoutBuilder b = builder()
            .setText("This is a longer test")
            .setIncludePad(true)
            .setWidth(50)
            .setSpacingAdd(2)
            .setSpacingMult(1.5f);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();
        Scaler s = new Scaler(b.spacingMult, b.spacingAdd);

        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                fmi.top, fmi.descent + s.scale(fmi.descent - fmi.top),
                fmi.ascent, fmi.descent + s.scale(fmi.descent - fmi.ascent),
                fmi.ascent, fmi.bottom + s.scale(fmi.bottom - fmi.ascent));
    }

    /**
     * Basic test showing effect of includePad = true, spacingAdd = 0,
     * spacingMult = 0.8 when wrapping to 3 lines.
     */
    //@SmallTest
    public void testGetters8() {
        LayoutBuilder b = builder()
            .setText("This is a longer test")
            .setIncludePad(true)
            .setWidth(50)
            .setSpacingAdd(2)
            .setSpacingMult(.8f);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();
        Scaler s = new Scaler(b.spacingMult, b.spacingAdd);

        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                fmi.top, fmi.descent + s.scale(fmi.descent - fmi.top),
                fmi.ascent, fmi.descent + s.scale(fmi.descent - fmi.ascent),
                fmi.ascent, fmi.bottom + s.scale(fmi.bottom - fmi.ascent));
    }

    // ----- test utility classes and methods -----

    // Models the effect of the scale and add parameters.  I think the current
    // implementation misbehaves.
    private static class Scaler {
        private final float sMult;
        private final float sAdd;

        Scaler(float sMult, float sAdd) {
            this.sMult = sMult - 1;
            this.sAdd = sAdd;
        }

        public int scale(float height) {
            int altVal = (int)(height * sMult + sAdd + 0.5);
            int rndVal = Math.round(height * sMult + sAdd);
            if (altVal != rndVal) {
                Log.i("Scale", "expected scale: " + rndVal +
                        " != returned scale: " + altVal);
            }
            return rndVal;
        }
    }

    /* package */ static LayoutBuilder builder() {
        return new LayoutBuilder();
    }

    /* package */ static class LayoutBuilder {
        String text = "This is a test";
        TextPaint paint = new TextPaint(); // default
        int width = 100;
        Alignment align = ALIGN_NORMAL;
        float spacingMult = 1;
        float spacingAdd = 0;
        boolean includePad = false;

        LayoutBuilder setText(String text) {
            this.text = text;
            return this;
        }

        LayoutBuilder setPaint(TextPaint paint) {
            this.paint = paint;
            return this;
        }

        LayoutBuilder setWidth(int width) {
            this.width = width;
            return this;
        }

        LayoutBuilder setAlignment(Alignment align) {
            this.align = align;
            return this;
        }

        LayoutBuilder setSpacingMult(float spacingMult) {
            this.spacingMult = spacingMult;
            return this;
        }

        LayoutBuilder setSpacingAdd(float spacingAdd) {
            this.spacingAdd = spacingAdd;
            return this;
        }

        LayoutBuilder setIncludePad(boolean includePad) {
            this.includePad = includePad;
            return this;
        }

       Layout build() {
            return  new StaticLayout(text, paint, width, align, spacingMult,
                spacingAdd, includePad);
        }
    }

    private void assertVertMetrics(Layout l, int topPad, int botPad, int... values) {
        assertTopBotPadding(l, topPad, botPad);
        assertLinesMetrics(l, values);
    }

    private void assertLinesMetrics(Layout l, int... values) {
        // sanity check
        if ((values.length & 0x1) != 0) {
            throw new IllegalArgumentException(String.valueOf(values.length));
        }

        int lines = values.length >> 1;
        assertEquals(lines, l.getLineCount());

        int t = 0;
        for (int i = 0, n = 0; i < lines; ++i, n += 2) {
            int a = values[n];
            int d = values[n+1];
            int h = -a + d;
            assertLineMetrics(l, i, t, a, d, h);
            t += h;
        }

        assertEquals(t, l.getHeight());
    }

    private void assertLineMetrics(Layout l, int line,
            int top, int ascent, int descent, int height) {
        String info = "line " + line;
        assertEquals(info, top, l.getLineTop(line));
        assertEquals(info, ascent, l.getLineAscent(line));
        assertEquals(info, descent, l.getLineDescent(line));
        assertEquals(info, height, l.getLineBottom(line) - top);
    }

    private void assertTopBotPadding(Layout l, int topPad, int botPad) {
        assertEquals(topPad, l.getTopPadding());
        assertEquals(botPad, l.getBottomPadding());
    }
}
