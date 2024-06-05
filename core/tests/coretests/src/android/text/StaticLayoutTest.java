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

import static android.text.Layout.Alignment.ALIGN_NORMAL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.os.LocaleList;
import android.platform.test.annotations.Presubmit;
import android.text.Layout.Alignment;
import android.text.method.EditorState;
import android.text.style.LocaleSpan;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tests StaticLayout vertical metrics behavior.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StaticLayoutTest {
    private static final float SPACE_MULTI = 1.0f;
    private static final float SPACE_ADD = 0.0f;
    private static final int DEFAULT_OUTER_WIDTH = 150;

    private static final CharSequence LAYOUT_TEXT = "CharSe\tq\nChar"
            + "Sequence\nCharSequence\nHelllo\n, world\nLongLongLong";
    private static final CharSequence LAYOUT_TEXT_SINGLE_LINE = "CharSequence";

    private static final Alignment DEFAULT_ALIGN = Alignment.ALIGN_CENTER;
    private static final int ELLIPSIZE_WIDTH = 8;

    private StaticLayout mDefaultLayout;
    private TextPaint mDefaultPaint;

    @Before
    public void setup() {
        mDefaultPaint = new TextPaint();
        mDefaultLayout = createDefaultStaticLayout();
    }

    private StaticLayout createDefaultStaticLayout() {
        return new StaticLayout(LAYOUT_TEXT, mDefaultPaint,
                DEFAULT_OUTER_WIDTH, DEFAULT_ALIGN, SPACE_MULTI, SPACE_ADD, true);
    }

    @Test
    public void testBuilder_textDirection() {
        {
            // Obtain.
            final StaticLayout.Builder builder = StaticLayout.Builder.obtain(LAYOUT_TEXT, 0,
                    LAYOUT_TEXT.length(), mDefaultPaint, DEFAULT_OUTER_WIDTH);
            final StaticLayout layout = builder.build();
            // Check default value.
            assertEquals(TextDirectionHeuristics.FIRSTSTRONG_LTR,
                    layout.getTextDirectionHeuristic());
        }
        {
            // setTextDirection.
            final StaticLayout.Builder builder = StaticLayout.Builder.obtain(LAYOUT_TEXT, 0,
                    LAYOUT_TEXT.length(), mDefaultPaint, DEFAULT_OUTER_WIDTH);
            builder.setTextDirection(TextDirectionHeuristics.RTL);
            final StaticLayout layout = builder.build();
            assertEquals(TextDirectionHeuristics.RTL,
                    layout.getTextDirectionHeuristic());
        }
    }

    /**
     * Basic test showing expected behavior and relationship between font
     * metrics and line metrics.
     */
    @Test
    public void testGetters1() {
        LayoutBuilder b = builder();
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        // check default paint
        Log.i("TG1:paint", fmi.toString());

        Layout l = b.build();
        assertVertMetrics(l, 0, 0,
                new int[][]{{fmi.ascent, fmi.descent, 0}});

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
    @Test
    public void testLineMetrics_withPadding() {
        LayoutBuilder b = builder()
            .setIncludePad(true);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                new int[][]{{fmi.top, fmi.bottom, 0}});
    }

    /**
     * Basic test showing effect of includePad = true wrapping to 2 lines.
     * Ascent of top line and descent of bottom line are affected.
     */
    @Test
    public void testLineMetrics_withPaddingAndWidth() {
        LayoutBuilder b = builder()
            .setIncludePad(true)
            .setWidth(50);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                new int[][]{
                        {fmi.top, fmi.descent, 0},
                        {fmi.ascent, fmi.bottom, 0}
                });
    }

    /**
     * Basic test showing effect of includePad = true wrapping to 3 lines.
     * First line ascent is top, bottom line descent is bottom.
     */
    @Test
    public void testLineMetrics_withThreeLines() {
        LayoutBuilder b = builder()
            .setText("This is a longer test")
            .setIncludePad(true)
            .setWidth(50);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                new int[][]{
                        {fmi.top, fmi.descent, 0},
                        {fmi.ascent, fmi.descent, 0},
                        {fmi.ascent, fmi.bottom, 0}
                });
    }

    /**
     * Basic test showing effect of includePad = true wrapping to 3 lines and
     * large text. See effect of leading. Currently, we don't expect there to
     * even be non-zero leading.
     */
    @Test
    public void testLineMetrics_withLargeText() {
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
                new int[][]{
                        {fmi.top, fmi.descent, 0},
                        {fmi.ascent, fmi.descent, 0},
                        {fmi.ascent, fmi.bottom, 0}
                });
    }

    /**
     * Basic test showing effect of includePad = true, spacingAdd = 2, wrapping
     * to 3 lines.
     */
    @Test
    public void testLineMetrics_withSpacingAdd() {
        int spacingAdd = 2; // int so expressions return int
        LayoutBuilder b = builder()
            .setText("This is a longer test")
            .setIncludePad(true)
            .setWidth(50)
            .setSpacingAdd(spacingAdd);
        FontMetricsInt fmi = b.paint.getFontMetricsInt();

        Layout l = b.build();
        assertVertMetrics(l, fmi.top - fmi.ascent, fmi.bottom - fmi.descent,
                new int[][]{
                        {fmi.top, fmi.descent + spacingAdd, spacingAdd},
                        {fmi.ascent, fmi.descent + spacingAdd, spacingAdd},
                        {fmi.ascent, fmi.bottom, 0}
                });
    }

    /**
     * Basic test showing effect of includePad = true, spacingAdd = 2,
     * spacingMult = 1.5, wrapping to 3 lines.
     */
    @Test
    public void testLineMetrics_withSpacingMult() {
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
                new int[][]{
                        {fmi.top, fmi.descent + s.scale(fmi.descent - fmi.top),
                                s.scale(fmi.descent - fmi.top)},
                        {fmi.ascent, fmi.descent + s.scale(fmi.descent - fmi.ascent),
                                s.scale(fmi.descent - fmi.ascent)},
                        {fmi.ascent, fmi.bottom, 0}
                });
    }

    /**
     * Basic test showing effect of includePad = true, spacingAdd = 0,
     * spacingMult = 0.8 when wrapping to 3 lines.
     */
    @Test
    public void testLineMetrics_withUnitIntervalSpacingMult() {
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
                new int[][]{
                        {fmi.top, fmi.descent + s.scale(fmi.descent - fmi.top),
                                s.scale(fmi.descent - fmi.top)},
                        {fmi.ascent, fmi.descent + s.scale(fmi.descent - fmi.ascent),
                                s.scale(fmi.descent - fmi.ascent)},
                        {fmi.ascent, fmi.bottom, 0}
                });
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetLineExtra_withNegativeValue() {
        final Layout layout = builder().build();
        layout.getLineExtra(-1);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetLineExtra_withParamGreaterThanLineCount() {
        final Layout layout = builder().build();
        layout.getLineExtra(100);
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

    /**
     * Assert vertical metrics such as top, bottom, ascent, descent.
     * @param l layout instance
     * @param topPad top padding
     * @param botPad bottom padding
     * @param values values for each line where first is ascent, second is descent, and last one is
     *               extra
     */
    private void assertVertMetrics(Layout l, int topPad, int botPad, int[][] values) {
        assertTopBotPadding(l, topPad, botPad);
        assertLinesMetrics(l, values);
    }

    /**
     * Check given expected values against the Layout values.
     * @param l layout instance
     * @param values values for each line where first is ascent, second is descent, and last one is
     *               extra
     */
    private void assertLinesMetrics(Layout l, int[][] values) {
        final int lines = values.length;
        assertEquals(lines, l.getLineCount());

        int t = 0;
        for (int i = 0, n = 0; i < lines; ++i, n += 3) {
            if (values[i].length != 3) {
                throw new IllegalArgumentException(String.valueOf(values.length));
            }
            int a = values[i][0];
            int d = values[i][1];
            int extra = values[i][2];
            int h = -a + d;
            assertLineMetrics(l, i, t, a, d, h, extra);
            t += h;
        }

        assertEquals(t, l.getHeight());
    }

    private void assertLineMetrics(Layout l, int line,
            int top, int ascent, int descent, int height, int extra) {
        String info = "line " + line;
        assertEquals(info, top, l.getLineTop(line));
        assertEquals(info, ascent, l.getLineAscent(line));
        assertEquals(info, descent, l.getLineDescent(line));
        assertEquals(info, height, l.getLineBottom(line) - top);
        assertEquals(info, extra, l.getLineExtra(line));
    }

    private void assertTopBotPadding(Layout l, int topPad, int botPad) {
        assertEquals(topPad, l.getTopPadding());
        assertEquals(botPad, l.getBottomPadding());
    }

    private void moveCursorToRightCursorableOffset(EditorState state, TextPaint paint) {
        assertEquals("The editor has selection", state.mSelectionStart, state.mSelectionEnd);
        final Layout layout = builder().setText(state.mText.toString()).setPaint(paint).build();
        final int newOffset = layout.getOffsetToRightOf(state.mSelectionStart);
        state.mSelectionStart = state.mSelectionEnd = newOffset;
    }

    private void moveCursorToLeftCursorableOffset(EditorState state, TextPaint paint) {
        assertEquals("The editor has selection", state.mSelectionStart, state.mSelectionEnd);
        final Layout layout = builder().setText(state.mText.toString()).setPaint(paint).build();
        final int newOffset = layout.getOffsetToLeftOf(state.mSelectionStart);
        state.mSelectionStart = state.mSelectionEnd = newOffset;
    }

    /**
     * Tests for keycap, variation selectors, flags are in CTS.
     * See {@link android.text.cts.StaticLayoutTest}.
     */
    @Test
    public void testEmojiOffset() {
        EditorState state = new EditorState();
        TextPaint paint = new TextPaint();

        // Odd numbered regional indicator symbols.
        // U+1F1E6 is REGIONAL INDICATOR SYMBOL LETTER A, U+1F1E8 is REGIONAL INDICATOR SYMBOL
        // LETTER C.
        state.setByString("| U+1F1E6 U+1F1E8 U+1F1E6 U+1F1E8 U+1F1E6");
        moveCursorToRightCursorableOffset(state, paint);
        state.setByString("U+1F1E6 U+1F1E8 | U+1F1E6 U+1F1E8 U+1F1E6");
        moveCursorToRightCursorableOffset(state, paint);
        state.setByString("U+1F1E6 U+1F1E8 U+1F1E6 U+1F1E8 | U+1F1E6");
        moveCursorToRightCursorableOffset(state, paint);
        state.setByString("U+1F1E6 U+1F1E8 U+1F1E6 U+1F1E8 U+1F1E6 |");
        moveCursorToRightCursorableOffset(state, paint);
        state.setByString("U+1F1E6 U+1F1E8 U+1F1E6 U+1F1E8 U+1F1E6 |");
        moveCursorToLeftCursorableOffset(state, paint);
        state.setByString("U+1F1E6 U+1F1E8 U+1F1E6 U+1F1E8 | U+1F1E6");
        moveCursorToLeftCursorableOffset(state, paint);
        state.setByString("U+1F1E6 U+1F1E8 | U+1F1E6 U+1F1E8 U+1F1E6");
        moveCursorToLeftCursorableOffset(state, paint);
        state.setByString("| U+1F1E6 U+1F1E8 U+1F1E6 U+1F1E8 U+1F1E6");
        moveCursorToLeftCursorableOffset(state, paint);
        state.setByString("| U+1F1E6 U+1F1E8 U+1F1E6 U+1F1E8 U+1F1E6");
        moveCursorToLeftCursorableOffset(state, paint);

        // Zero width sequence
        final String zwjSequence = "U+1F468 U+200D U+2764 U+FE0F U+200D U+1F468";
        state.setByString("| " + zwjSequence + " " + zwjSequence + " " + zwjSequence);
        moveCursorToRightCursorableOffset(state, paint);
        state.assertEquals(zwjSequence + " | " + zwjSequence + " " + zwjSequence);
        moveCursorToRightCursorableOffset(state, paint);
        state.assertEquals(zwjSequence + " " + zwjSequence + " | " + zwjSequence);
        moveCursorToRightCursorableOffset(state, paint);
        state.assertEquals(zwjSequence + " " + zwjSequence + " " + zwjSequence + " |");
        moveCursorToRightCursorableOffset(state, paint);
        state.assertEquals(zwjSequence + " " + zwjSequence + " " + zwjSequence + " |");
        moveCursorToLeftCursorableOffset(state, paint);
        state.assertEquals(zwjSequence + " " + zwjSequence + " | " + zwjSequence);
        moveCursorToLeftCursorableOffset(state, paint);
        state.assertEquals(zwjSequence + " | " + zwjSequence + " " + zwjSequence);
        moveCursorToLeftCursorableOffset(state, paint);
        state.assertEquals("| " + zwjSequence + " " + zwjSequence + " " + zwjSequence);
        moveCursorToLeftCursorableOffset(state, paint);
        state.assertEquals("| " + zwjSequence + " " + zwjSequence + " " + zwjSequence);
        moveCursorToLeftCursorableOffset(state, paint);

        // Emoji modifiers
        // U+261D is WHITE UP POINTING INDEX, U+1F3FB is EMOJI MODIFIER FITZPATRICK TYPE-1-2.
        state.setByString("| U+261D U+1F3FB U+261D U+1F3FB U+261D U+1F3FB");
        moveCursorToRightCursorableOffset(state, paint);
        state.setByString("U+261D U+1F3FB | U+261D U+1F3FB U+261D U+1F3FB");
        moveCursorToRightCursorableOffset(state, paint);
        state.setByString("U+261D U+1F3FB U+261D U+1F3FB | U+261D U+1F3FB");
        moveCursorToRightCursorableOffset(state, paint);
        state.setByString("U+261D U+1F3FB U+261D U+1F3FB U+261D U+1F3FB |");
        moveCursorToRightCursorableOffset(state, paint);
        state.setByString("U+261D U+1F3FB U+261D U+1F3FB U+261D U+1F3FB |");
        moveCursorToLeftCursorableOffset(state, paint);
        state.setByString("U+261D U+1F3FB U+261D U+1F3FB | U+261D U+1F3FB");
        moveCursorToLeftCursorableOffset(state, paint);
        state.setByString("U+261D U+1F3FB | U+261D U+1F3FB U+261D U+1F3FB");
        moveCursorToLeftCursorableOffset(state, paint);
        state.setByString("| U+261D U+1F3FB U+261D U+1F3FB U+261D U+1F3FB");
        moveCursorToLeftCursorableOffset(state, paint);
        state.setByString("| U+261D U+1F3FB U+261D U+1F3FB U+261D U+1F3FB");
        moveCursorToLeftCursorableOffset(state, paint);
    }

    private StaticLayout createEllipsizeStaticLayout(CharSequence text,
            TextUtils.TruncateAt ellipsize, int maxLines) {
        return new StaticLayout(text, 0, text.length(),
                mDefaultPaint, DEFAULT_OUTER_WIDTH, DEFAULT_ALIGN,
                TextDirectionHeuristics.FIRSTSTRONG_LTR,
                SPACE_MULTI, SPACE_ADD, true /* include pad */,
                ellipsize,
                ELLIPSIZE_WIDTH,
                maxLines);
    }

    @Test
    public void testEllipsis_singleLine() {
        {
            // Single line case and TruncateAt.END so that we have some ellipsis
            StaticLayout layout = createEllipsizeStaticLayout(LAYOUT_TEXT_SINGLE_LINE,
                    TextUtils.TruncateAt.END, 1);
            assertTrue(layout.getEllipsisCount(0) > 0);
        }
        {
            // Single line case and TruncateAt.MIDDLE so that we have some ellipsis
            StaticLayout layout = createEllipsizeStaticLayout(LAYOUT_TEXT_SINGLE_LINE,
                    TextUtils.TruncateAt.MIDDLE, 1);
            assertTrue(layout.getEllipsisCount(0) > 0);
        }
        {
            // Single line case and TruncateAt.END so that we have some ellipsis
            StaticLayout layout = createEllipsizeStaticLayout(LAYOUT_TEXT_SINGLE_LINE,
                    TextUtils.TruncateAt.END, 1);
            assertTrue(layout.getEllipsisCount(0) > 0);
        }
        {
            // Single line case and TruncateAt.MARQUEE so that we have NO ellipsis
            StaticLayout layout = createEllipsizeStaticLayout(LAYOUT_TEXT_SINGLE_LINE,
                    TextUtils.TruncateAt.MARQUEE, 1);
            assertTrue(layout.getEllipsisCount(0) == 0);
        }
        {
            final String text = "\u3042" // HIRAGANA LETTER A
                    + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
            final float textWidth = mDefaultPaint.measureText(text);
            final int halfWidth = (int) (textWidth / 2.0f);
            {
                StaticLayout layout = new StaticLayout(text, 0, text.length(), mDefaultPaint,
                        halfWidth, DEFAULT_ALIGN, TextDirectionHeuristics.FIRSTSTRONG_LTR,
                        SPACE_MULTI, SPACE_ADD, false, TextUtils.TruncateAt.END, halfWidth, 1);
                assertTrue(layout.getEllipsisCount(0) > 0);
                assertTrue(layout.getEllipsisStart(0) > 0);
            }
            {
                StaticLayout layout = new StaticLayout(text, 0, text.length(), mDefaultPaint,
                        halfWidth, DEFAULT_ALIGN, TextDirectionHeuristics.FIRSTSTRONG_LTR,
                        SPACE_MULTI, SPACE_ADD, false, TextUtils.TruncateAt.START, halfWidth, 1);
                assertTrue(layout.getEllipsisCount(0) > 0);
                assertEquals(0, mDefaultLayout.getEllipsisStart(0));
            }
            {
                StaticLayout layout = new StaticLayout(text, 0, text.length(), mDefaultPaint,
                        halfWidth, DEFAULT_ALIGN, TextDirectionHeuristics.FIRSTSTRONG_LTR,
                        SPACE_MULTI, SPACE_ADD, false, TextUtils.TruncateAt.MIDDLE, halfWidth, 1);
                assertTrue(layout.getEllipsisCount(0) > 0);
                assertTrue(layout.getEllipsisStart(0) > 0);
            }
            {
                StaticLayout layout = new StaticLayout(text, 0, text.length(), mDefaultPaint,
                        halfWidth, DEFAULT_ALIGN, TextDirectionHeuristics.FIRSTSTRONG_LTR,
                        SPACE_MULTI, SPACE_ADD, false, TextUtils.TruncateAt.MARQUEE, halfWidth, 1);
                assertEquals(0, layout.getEllipsisCount(0));
            }
        }

        {
            // The white spaces in this text will be trailing if maxLines is larger than 1, but
            // width of the trailing white spaces must not be ignored if ellipsis is applied.
            final String text = "abc                                             def";
            final float textWidth = mDefaultPaint.measureText(text);
            final int halfWidth = (int) (textWidth / 2.0f);
            {
                StaticLayout layout = new StaticLayout(text, 0, text.length(), mDefaultPaint,
                        halfWidth, DEFAULT_ALIGN, TextDirectionHeuristics.FIRSTSTRONG_LTR,
                        SPACE_MULTI, SPACE_ADD, false, TextUtils.TruncateAt.END, halfWidth, 1);
                assertTrue(layout.getEllipsisCount(0) > 0);
                assertTrue(layout.getEllipsisStart(0) > 0);
            }
        }

        {
            // 2 family emojis (11 code units + 11 code units).
            final String text = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
                    + "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66";
            final float textWidth = mDefaultPaint.measureText(text);

            final TextUtils.TruncateAt[] kinds = {TextUtils.TruncateAt.START,
                    TextUtils.TruncateAt.MIDDLE, TextUtils.TruncateAt.END};
            for (final TextUtils.TruncateAt kind : kinds) {
                for (int i = 0; i <= 8; i++) {
                    int avail = (int) (textWidth * i / 7.0f);
                    StaticLayout layout = new StaticLayout(text, 0, text.length(), mDefaultPaint,
                            avail, DEFAULT_ALIGN, TextDirectionHeuristics.FIRSTSTRONG_LTR,
                            SPACE_MULTI, SPACE_ADD, false, kind, avail, 1);

                    assertTrue(layout.getEllipsisCount(0) == text.length()
                                    || layout.getEllipsisCount(0) == text.length() / 2
                                    || layout.getEllipsisCount(0) == 0);
                }
            }
        }
    }

    // String wrapper for testing not well known implementation of CharSequence.
    private class FakeCharSequence implements CharSequence {
        private String mStr;

        FakeCharSequence(String str) {
            mStr = str;
        }

        @Override
        public char charAt(int index) {
            return mStr.charAt(index);
        }

        @Override
        public int length() {
            return mStr.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return mStr.subSequence(start, end);
        }

        @Override
        public String toString() {
            return mStr;
        }
    };

    private List<CharSequence> buildTestCharSequences(String testString, Normalizer.Form[] forms) {
        List<CharSequence> result = new ArrayList<>();

        List<String> normalizedStrings = new ArrayList<>();
        for (Normalizer.Form form: forms) {
            normalizedStrings.add(Normalizer.normalize(testString, form));
        }

        for (String str: normalizedStrings) {
            result.add(str);
            result.add(new SpannedString(str));
            result.add(new SpannableString(str));
            result.add(new SpannableStringBuilder(str));  // as a GraphicsOperations implementation.
            result.add(new FakeCharSequence(str));  // as a not well known implementation.
        }
        return result;
    }

    private String buildTestMessage(CharSequence seq) {
        String normalized;
        if (Normalizer.isNormalized(seq, Normalizer.Form.NFC)) {
            normalized = "NFC";
        } else if (Normalizer.isNormalized(seq, Normalizer.Form.NFD)) {
            normalized = "NFD";
        } else if (Normalizer.isNormalized(seq, Normalizer.Form.NFKC)) {
            normalized = "NFKC";
        } else if (Normalizer.isNormalized(seq, Normalizer.Form.NFKD)) {
            normalized = "NFKD";
        } else {
            throw new IllegalStateException("Normalized form is not NFC/NFD/NFKC/NFKD");
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < seq.length(); ++i) {
            builder.append(String.format("0x%04X ", Integer.valueOf(seq.charAt(i))));
        }

        return "testString: \"" + seq.toString() + "\"[" + builder.toString() + "]"
                + ", class: " + seq.getClass().getName()
                + ", Normalization: " + normalized;
    }

    @Test
    public void testGetOffset_UNICODE_Hebrew() {
        String testString = "\u05DE\u05E1\u05E2\u05D3\u05D4"; // Hebrew Characters
        for (CharSequence seq: buildTestCharSequences(testString, Normalizer.Form.values())) {
            StaticLayout.Builder b = StaticLayout.Builder.obtain(
                    seq, 0, seq.length(), mDefaultPaint, DEFAULT_OUTER_WIDTH)
                    .setAlignment(DEFAULT_ALIGN)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setLineSpacing(SPACE_ADD, SPACE_MULTI)
                    .setIncludePad(true);
            StaticLayout layout = b.build();

            String testLabel = buildTestMessage(seq);

            assertEquals(testLabel, 1, layout.getOffsetToLeftOf(0));
            assertEquals(testLabel, 2, layout.getOffsetToLeftOf(1));
            assertEquals(testLabel, 3, layout.getOffsetToLeftOf(2));
            assertEquals(testLabel, 4, layout.getOffsetToLeftOf(3));
            assertEquals(testLabel, 5, layout.getOffsetToLeftOf(4));
            assertEquals(testLabel, 5, layout.getOffsetToLeftOf(5));

            assertEquals(testLabel, 0, layout.getOffsetToRightOf(0));
            assertEquals(testLabel, 0, layout.getOffsetToRightOf(1));
            assertEquals(testLabel, 1, layout.getOffsetToRightOf(2));
            assertEquals(testLabel, 2, layout.getOffsetToRightOf(3));
            assertEquals(testLabel, 3, layout.getOffsetToRightOf(4));
            assertEquals(testLabel, 4, layout.getOffsetToRightOf(5));
        }
    }

    @Test
    public void testLocaleSpanAffectsHyphenation() {
        TextPaint paint = new TextPaint();
        paint.setTextLocale(Locale.US);
        // Private use language, with no hyphenation rules.
        final Locale privateLocale = Locale.forLanguageTag("qaa");

        final String longWord = "philanthropic";
        final float wordWidth = paint.measureText(longWord);
        // Wide enough that words get hyphenated by default.
        final int paraWidth = Math.round(wordWidth * 1.8f);
        final String sentence = longWord + " " + longWord + " " + longWord + " " + longWord + " "
                + longWord + " " + longWord;

        final int numEnglishLines = StaticLayout.Builder
                .obtain(sentence, 0, sentence.length(), paint, paraWidth)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .build()
                .getLineCount();

        {
            final SpannableString text = new SpannableString(sentence);
            text.setSpan(new LocaleSpan(privateLocale), 0, text.length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            final int numPrivateLocaleLines = StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, paraWidth)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .build()
                    .getLineCount();

            // Since the paragraph set to English gets hyphenated, the number of lines would be
            // smaller than the number of lines when there is a span setting a language that
            // doesn't get hyphenated.
            assertTrue(numEnglishLines < numPrivateLocaleLines);
        }
        {
            // Same as the above test, except that the locale span now uses a locale list starting
            // with the private non-hyphenating locale.
            final SpannableString text = new SpannableString(sentence);
            final LocaleList locales = new LocaleList(privateLocale, Locale.US);
            text.setSpan(new LocaleSpan(locales), 0, text.length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            final int numPrivateLocaleLines = StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, paraWidth)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .build()
                    .getLineCount();

            assertTrue(numEnglishLines < numPrivateLocaleLines);
        }
        {
            final SpannableString text = new SpannableString(sentence);
            // Apply the private LocaleSpan only to the first word, which is not getting hyphenated
            // anyway.
            text.setSpan(new LocaleSpan(privateLocale), 0, longWord.length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            final int numPrivateLocaleLines = StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, paraWidth)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .build()
                    .getLineCount();

            // Since the first word is not hyphenated anyway (there's enough width), the LocaleSpan
            // should not affect the layout.
            assertEquals(numEnglishLines, numPrivateLocaleLines);
        }
    }

    @Test
    public void testLayoutDoesntModifyPaint() {
        final TextPaint paint = new TextPaint();
        paint.setStartHyphenEdit(Paint.START_HYPHEN_EDIT_INSERT_HYPHEN);
        paint.setEndHyphenEdit(Paint.END_HYPHEN_EDIT_INSERT_HYPHEN);
        final StaticLayout layout = StaticLayout.Builder.obtain("", 0, 0, paint, 100).build();
        final Canvas canvas = new Canvas();
        layout.drawText(canvas, 0, 0);
        assertEquals(Paint.START_HYPHEN_EDIT_INSERT_HYPHEN, paint.getStartHyphenEdit());
        assertEquals(Paint.END_HYPHEN_EDIT_INSERT_HYPHEN, paint.getEndHyphenEdit());
    }

    @Test
    public void testFallbackLineSpacing() {
        // All glyphs in the fonts are 1em wide.
        final String[] testFontFiles = {
            // ascent == 1em, descent == 2em, only supports 'a' and space
            "ascent1em-descent2em.ttf",
            // ascent == 3em, descent == 4em, only supports 'b'
            "ascent3em-descent4em.ttf"
        };
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>ascent1em-descent2em.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>ascent3em-descent4em.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>ascent10em-descent10em.ttf</font>"
                + "  </family>"
                + "</familyset>";

        try (FontFallbackSetup setup =
                new FontFallbackSetup("StaticLayout", testFontFiles, xml)) {
            final TextPaint paint = setup.getPaintFor("sans-serif");
            final int textSize = 100;
            paint.setTextSize(textSize);
            assertEquals(-textSize, paint.ascent(), 0.0f);
            assertEquals(2 * textSize, paint.descent(), 0.0f);

            final int paraWidth = 5 * textSize;
            final String text = "aaaaa\naabaa\naaaaa\n"; // This should result in three lines.

            // Old line spacing. All lines should get their ascent and descents from the first font.
            StaticLayout layout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, paraWidth)
                    .setIncludePad(false)
                    .setUseLineSpacingFromFallbacks(false)
                    .build();
            assertEquals(4, layout.getLineCount());
            assertEquals(-textSize, layout.getLineAscent(0));
            assertEquals(2 * textSize, layout.getLineDescent(0));
            assertEquals(-textSize, layout.getLineAscent(1));
            assertEquals(2 * textSize, layout.getLineDescent(1));
            assertEquals(-textSize, layout.getLineAscent(2));
            assertEquals(2 * textSize, layout.getLineDescent(2));
            // The last empty line spacing should be the default line spacing.
            // Maybe good to be a previous line spacing?
            assertEquals(-textSize, layout.getLineAscent(3));
            assertEquals(2 * textSize, layout.getLineDescent(3));

            // New line spacing. The second line has a 'b', so it needs more ascent and descent.
            layout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, paraWidth)
                    .setIncludePad(false)
                    .setUseLineSpacingFromFallbacks(true)
                    .build();
            assertEquals(4, layout.getLineCount());
            assertEquals(-textSize, layout.getLineAscent(0));
            assertEquals(2 * textSize, layout.getLineDescent(0));
            assertEquals(-3 * textSize, layout.getLineAscent(1));
            assertEquals(4 * textSize, layout.getLineDescent(1));
            assertEquals(-textSize, layout.getLineAscent(2));
            assertEquals(2 * textSize, layout.getLineDescent(2));
            assertEquals(-textSize, layout.getLineAscent(3));
            assertEquals(2 * textSize, layout.getLineDescent(3));

            // The default is the old line spacing, for backward compatibility.
            layout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, paraWidth)
                    .setIncludePad(false)
                    .build();
            assertEquals(4, layout.getLineCount());
            assertEquals(-textSize, layout.getLineAscent(0));
            assertEquals(2 * textSize, layout.getLineDescent(0));
            assertEquals(-textSize, layout.getLineAscent(1));
            assertEquals(2 * textSize, layout.getLineDescent(1));
            assertEquals(-textSize, layout.getLineAscent(2));
            assertEquals(2 * textSize, layout.getLineDescent(2));
            assertEquals(-textSize, layout.getLineAscent(3));
            assertEquals(2 * textSize, layout.getLineDescent(3));

            layout = StaticLayout.Builder
                    .obtain("\n", 0, 1, paint, textSize)
                    .setIncludePad(false)
                    .setUseLineSpacingFromFallbacks(false)
                    .build();
            assertEquals(2, layout.getLineCount());
            assertEquals(-textSize, layout.getLineAscent(0));
            assertEquals(2 * textSize, layout.getLineDescent(0));
            assertEquals(-textSize, layout.getLineAscent(1));
            assertEquals(2 * textSize, layout.getLineDescent(1));

            layout = StaticLayout.Builder
                    .obtain("\n", 0, 1, paint, textSize)
                    .setIncludePad(false)
                    .setUseLineSpacingFromFallbacks(true)
                    .build();
            assertEquals(2, layout.getLineCount());
            assertEquals(-textSize, layout.getLineAscent(0));
            assertEquals(2 * textSize, layout.getLineDescent(0));
            assertEquals(-textSize, layout.getLineAscent(1));
            assertEquals(2 * textSize, layout.getLineDescent(1));
        }
    }

    @Test
    public void testGetHeight_zeroMaxLines() {
        final String text = "a\nb";
        final TextPaint paint = new TextPaint();
        final StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint,
                Integer.MAX_VALUE).setMaxLines(0).build();

        assertEquals(0, layout.getHeight(true));
        assertEquals(2, layout.getLineCount());
    }
}
