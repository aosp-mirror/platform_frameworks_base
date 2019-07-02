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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.platform.test.annotations.Presubmit;
import android.text.Layout.Alignment;
import android.text.style.StrikethroughSpan;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LayoutTest {
    private static final int LINE_COUNT = 5;
    private static final int LINE_HEIGHT = 12;
    private static final int LINE_DESCENT = 4;
    private static final CharSequence LAYOUT_TEXT = "alwei\t;sdfs\ndf @";

    private SpannableString mSpannedText;

    private int mWidth;
    private Layout.Alignment mAlign;
    private float mSpacingMult;
    private float mSpacingAdd;
    private TextPaint mTextPaint;

    @Before
    public void setup() {
        mTextPaint = new TextPaint();
        mSpannedText = new SpannableString(LAYOUT_TEXT);
        mSpannedText.setSpan(new StrikethroughSpan(), 0, 1, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
        mWidth = 11;
        mAlign = Alignment.ALIGN_CENTER;
        mSpacingMult = 1;
        mSpacingAdd = 2;
    }

    @Test
    public void testConstructor() {
        new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth, mAlign, mSpacingMult, mSpacingAdd);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNull() {
        new MockLayout(null, null, -1, null, 0, 0);
    }

    @Test
    public void testGetText() {
        CharSequence text = "test case 1";
        Layout layout = new MockLayout(text, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(text, layout.getText());

        layout = new MockLayout(null, mTextPaint, mWidth, mAlign, mSpacingMult, mSpacingAdd);
        assertNull(layout.getText());
    }

    @Test
    public void testGetPaint() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);

        assertSame(mTextPaint, layout.getPaint());

        layout = new MockLayout(LAYOUT_TEXT, null, mWidth, mAlign, mSpacingMult, mSpacingAdd);
        assertNull(layout.getPaint());
    }

    @Test
    public void testGetWidth() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, 10,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(10,  layout.getWidth());

        layout = new MockLayout(LAYOUT_TEXT, mTextPaint, 0, mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(0,  layout.getWidth());
    }

    @Test
    public void testGetEllipsizedWidth() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, 15,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(15, layout.getEllipsizedWidth());

        layout = new MockLayout(LAYOUT_TEXT, mTextPaint, 0, mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(0,  layout.getEllipsizedWidth());
    }

    @Test
    public void testIncreaseWidthTo() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        int oldWidth = layout.getWidth();

        layout.increaseWidthTo(oldWidth);
        assertEquals(oldWidth, layout.getWidth());

        try {
            layout.increaseWidthTo(oldWidth - 1);
            fail("should throw runtime exception attempted to reduce Layout width");
        } catch (RuntimeException e) {
        }

        layout.increaseWidthTo(oldWidth + 1);
        assertEquals(oldWidth + 1, layout.getWidth());
    }

    @Test
    public void testGetHeight() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(60, layout.getHeight());
    }

    @Test
    public void testGetAlignment() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertSame(mAlign, layout.getAlignment());

        layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth, null, mSpacingMult, mSpacingAdd);
        assertNull(layout.getAlignment());
    }

    @Test
    public void testGetSpacingMultiplier() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth, mAlign, -1, mSpacingAdd);
        assertEquals(-1.0f, layout.getSpacingMultiplier(), 0.0f);

        layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth, mAlign, 5, mSpacingAdd);
        assertEquals(5.0f, layout.getSpacingMultiplier(), 0.0f);
    }

    @Test
    public void testGetSpacingAdd() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth, mAlign, mSpacingMult, -1);
        assertEquals(-1.0f, layout.getSpacingAdd(), 0.0f);

        layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth, mAlign, mSpacingMult, 20);
        assertEquals(20.0f, layout.getSpacingAdd(), 0.0f);
    }

    @Test
    public void testGetLineBounds() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        Rect bounds = new Rect();

        assertEquals(32, layout.getLineBounds(2, bounds));
        assertEquals(0, bounds.left);
        assertEquals(mWidth, bounds.right);
        assertEquals(24, bounds.top);
        assertEquals(36, bounds.bottom);
    }

    @Test
    public void testGetLineForVertical() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(0, layout.getLineForVertical(-1));
        assertEquals(0, layout.getLineForVertical(0));
        assertEquals(0, layout.getLineForVertical(LINE_COUNT));
        assertEquals(LINE_COUNT - 1, layout.getLineForVertical(1000));
    }

    @Test
    public void testGetLineForOffset() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(0, layout.getLineForOffset(-1));
        assertEquals(1, layout.getLineForOffset(1));
        assertEquals(LINE_COUNT - 1, layout.getLineForOffset(LINE_COUNT - 1));
        assertEquals(LINE_COUNT - 1, layout.getLineForOffset(1000));
    }

    @Test
    public void testGetLineEnd() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(2, layout.getLineEnd(1));
    }

    @Test
    public void testGetLineExtra_returnsZeroByDefault() {
        final String text = "a\nb\nc\n";
        final Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, 100 /* spacingMult*/, 100 /*spacingAdd*/);
        final int lineCount = text.split("\n").length;
        for (int i = 0; i < lineCount; i++) {
            assertEquals(0, layout.getLineExtra(i));
        }
    }

    @Test
    public void testGetLineVisibleEnd() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);

        assertEquals(2, layout.getLineVisibleEnd(1));
        assertEquals(LINE_COUNT, layout.getLineVisibleEnd(LINE_COUNT - 1));
        assertEquals(LAYOUT_TEXT.length(), layout.getLineVisibleEnd(LAYOUT_TEXT.length() - 1));
        try {
            layout.getLineVisibleEnd(LAYOUT_TEXT.length());
            fail("should throw .StringIndexOutOfBoundsException here");
        } catch (StringIndexOutOfBoundsException e) {
        }
    }

    @Test
    public void testGetLineBottom() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(LINE_HEIGHT, layout.getLineBottom(0));
    }

    @Test
    public void testGetLineBaseline() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(8, layout.getLineBaseline(0));
    }

    @Test
    public void testGetLineAscent() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(-8, layout.getLineAscent(0));
    }

    @Test
    public void testGetParagraphAlignment() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertSame(mAlign, layout.getParagraphAlignment(0));

        layout = new MockLayout(mSpannedText, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertSame(mAlign, layout.getParagraphAlignment(0));
        assertSame(mAlign, layout.getParagraphAlignment(1));
    }

    @Test
    public void testGetParagraphLeft() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(0, layout.getParagraphLeft(0));
    }

    @Test
    public void testGetParagraphRight() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertEquals(mWidth, layout.getParagraphRight(0));
    }

    @Test
    public void testGetSelectionWithEmptySelection() {
        final Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);

        /*
         * When the selection is empty (i.e. the start and the end index are the same), we do not
         * expect any rectangles to be generated.
         */

        layout.getSelection(5 /* startIndex */, 5 /* endIndex */,
                (left, top, right, bottom, textSelectionLayout) -> fail(
                        String.format(Locale.getDefault(),
                        "Did not expect any rectangles, got a rectangle with (left: %f,"
                                + " top: %f), (right: %f, bottom: %f)",
                        left, top, right, bottom)));
    }

    @Test
    public void testGetSelectionWithASingleLineSelection() {
        final Layout layout = new StaticLayout("abc", mTextPaint, Integer.MAX_VALUE,
                Alignment.ALIGN_LEFT, mSpacingMult, mSpacingAdd, false);

        final List<RectF> rectangles = new ArrayList<>();

        layout.getSelection(0 /* startIndex */, 1 /* endIndex */,
                (left, top, right, bottom, textSelectionLayout) -> rectangles.add(
                        new RectF(left, top, right, bottom)));

        /*
         * The selection we expect will only cover the letter "a". Hence, we expect one rectangle
         * to be generated and this rectangle should start at the top left of the canvas and should
         * end somewhere to the right and down.
         *
         * | a | b c
         *
         */

        assertEquals(1, rectangles.size());

        final RectF rectangle = rectangles.get(0);

        assertEquals(0, rectangle.left, 0.0f);
        assertEquals(0, rectangle.top, 0.0f);
        assertTrue(rectangle.right > 0);
        assertTrue(rectangle.bottom > 0);
    }

    @Test
    public void
            testGetSelectionWithMultilineSelection_secondLineSelectionEndsBeforeFirstCharacter() {
        final Layout layout = new StaticLayout("a\nb\nc", mTextPaint, Integer.MAX_VALUE,
                Alignment.ALIGN_LEFT, mSpacingMult, mSpacingAdd, false);

        final List<RectF> rectangles = new ArrayList<>();

        layout.getSelection(0 /* startIndex */, 2 /* endIndex */,
                (left, top, right, bottom, textSelectionLayout) -> rectangles.add(
                        new RectF(left, top, right, bottom)));

        /*
         * The selection that will be selected is "a\n" - the selection starts at the beginning
         * of the first line and ends at the start of the second line. This means the selection
         * highlight will span from the beginning of the first line to the end of the first line
         * and will appear as a zero width line at the beginning of the second line.
         *
         * Hence, we expect three rectangles - one that will select the "a" on the first line,
         * one that will extend the selection from the "a" to the end of the first line and one
         * that will prepare the selection for the second line.
         *
         * | a | *topToEndOfLineRectangle* |
         * | b
         *   c
         */

        assertEquals(3, rectangles.size());

        final RectF topRectangle = rectangles.get(0);
        final RectF topToEndOfLineRectangle = rectangles.get(1);
        final RectF bottomLineStartRectangle = rectangles.get(2);

        assertFalse(topRectangle.intersect(bottomLineStartRectangle));
        assertTrue(topRectangle.top < bottomLineStartRectangle.top);
        assertTrue(topRectangle.left == bottomLineStartRectangle.left);

        assertFalse(topRectangle.intersect(topToEndOfLineRectangle));
        assertEquals(Integer.MAX_VALUE, topToEndOfLineRectangle.right, 1);
        assertTrue(topRectangle.top == topToEndOfLineRectangle.top);
        assertTrue(topRectangle.right == topToEndOfLineRectangle.left);
        assertTrue(topRectangle.bottom == topToEndOfLineRectangle.bottom);

        assertEquals(0, bottomLineStartRectangle.left, 0.0f);
        assertEquals(0, bottomLineStartRectangle.right, 0.0f);
    }

    @Test
    public void testGetSelectionWithMultilineSelection_secondLineSelectionEndsAfterACharacter() {
        final Layout layout = new StaticLayout("a\nb\nc", mTextPaint, Integer.MAX_VALUE,
                Alignment.ALIGN_LEFT, mSpacingMult, mSpacingAdd, false);

        final List<RectF> rectangles = new ArrayList<>();

        layout.getSelection(0 /* startIndex */, 3 /* endIndex */,
                (left, top, right, bottom, textSelectionLayout) -> rectangles.add(
                        new RectF(left, top, right, bottom)));

        /*
         * The selection that will be selected is "a\nb" - the selection starts at the beginning
         * of the first line and ends at the end of the letter "b". This means the selection
         * highlight will span from the beginning of the first line to the end of the first line
         * and will also cover the letter "b" on the second line.
         *
         * We expect four rectangles - one that will select the "a" on the first line,
         * one that will extend the selection from the "a" to the end of the first line the one
         * from the previous case that will prepare the selection for the second line and finally
         * one that will select the letter b.
         *
         *  | a | *topToEndOfLineRectangle* |
         * || b |
         *    c
         */

        assertEquals(4, rectangles.size());

        final RectF topRectangle = rectangles.get(0);
        final RectF topToEndOfLineRectangle = rectangles.get(1);
        final RectF bottomRectangle = rectangles.get(2);
        final RectF bottomLineStartRectangle = rectangles.get(3);

        assertTrue(topRectangle.top == topToEndOfLineRectangle.top);
        assertTrue(bottomLineStartRectangle.top == bottomRectangle.top);
        assertTrue(bottomLineStartRectangle.bottom == bottomRectangle.bottom);
        assertEquals(0, bottomLineStartRectangle.left, 0.0f);
        assertEquals(0, bottomLineStartRectangle.right, 0.0f);
        assertEquals(0, bottomRectangle.left, 0.0f);
        assertTrue(bottomRectangle.right > 0);
    }

    @Test
    public void testGetSelectionPathWithASingleLineSelection() {
        final Layout layout = new StaticLayout("abc", mTextPaint, Integer.MAX_VALUE,
                Alignment.ALIGN_LEFT, mSpacingMult, mSpacingAdd, false);

        final List<RectF> rectangles = new ArrayList<>();

        layout.getSelection(0 /* startIndex */, 1 /* endIndex */,
                (left, top, right, bottom, textSelectionLayout) -> rectangles.add(
                        new RectF(left, top, right, bottom)));

        /*
         * In the single line selection case, we expect that only one rectangle covering the letter
         * "a" will be generated. Hence, we expect that the generated path will only consist of
         * that rectangle as well.
         *
         * | a | b c
         *
         */

        assertEquals(1, rectangles.size());

        final RectF rectangle = rectangles.get(0);

        final Path generatedPath = new Path();
        layout.getSelectionPath(0 /* startIndex */, 1 /* endIndex */, generatedPath);

        final RectF pathRectangle = new RectF();

        assertTrue(generatedPath.isRect(pathRectangle));
        assertEquals(rectangle, pathRectangle);
    }

    @Test
    public void testGetSelection_latinTextDirection() {
        final Layout layout = new StaticLayout("abc", mTextPaint, Integer.MAX_VALUE,
                Alignment.ALIGN_LEFT, mSpacingMult, mSpacingAdd, false);

        layout.getSelection(0 /* startIndex */, 2 /* endIndex */,
                (left, top, right, bottom, textSelectionLayout) ->
                        assertEquals(Layout.TEXT_SELECTION_LAYOUT_LEFT_TO_RIGHT,
                                textSelectionLayout));
    }

    @Test
    public void testGetSelection_arabicTextDirection() {
        final Layout layout = new StaticLayout("غينيا", mTextPaint, Integer.MAX_VALUE,
                Alignment.ALIGN_LEFT, mSpacingMult, mSpacingAdd, false);

        layout.getSelection(0 /* startIndex */, 2 /* endIndex */,
                (left, top, right, bottom, textSelectionLayout) ->
                        assertEquals(Layout.TEXT_SELECTION_LAYOUT_RIGHT_TO_LEFT,
                                textSelectionLayout));
    }

    @Test
    public void testGetSelection_mixedLatinAndArabicTextDirection() {
        final Layout layout = new StaticLayout("abcغينيا", mTextPaint, Integer.MAX_VALUE,
                Alignment.ALIGN_LEFT, mSpacingMult, mSpacingAdd, false);

        final List<Integer> layouts = new ArrayList<>(2);

        layout.getSelection(0 /* startIndex */, 6 /* endIndex */,
                (left, top, right, bottom, textSelectionLayout) -> layouts.add(
                        textSelectionLayout));

        assertEquals(2, layouts.size());
        assertEquals(Layout.TEXT_SELECTION_LAYOUT_LEFT_TO_RIGHT, (long) layouts.get(0));
        assertEquals(Layout.TEXT_SELECTION_LAYOUT_RIGHT_TO_LEFT, (long) layouts.get(1));
    }

    @Test
    public void testIsSpanned() {
        MockLayout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        // default is not spanned text
        assertFalse(layout.mockIsSpanned());

        // try to create a spanned text
        layout = new MockLayout(mSpannedText, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        assertTrue(layout.mockIsSpanned());
    }

    private static final class MockLayout extends Layout {
        MockLayout(CharSequence text, TextPaint paint, int width,
                Alignment align, float spacingmult, float spacingadd) {
            super(text, paint, width, align, spacingmult, spacingadd);
        }

        protected boolean mockIsSpanned() {
            return super.isSpanned();
        }

        @Override
        public int getBottomPadding() {
            return 0;
        }

        @Override
        public int getEllipsisCount(int line) {
            return 0;
        }

        @Override
        public int getEllipsisStart(int line) {
            return 0;
        }

        @Override
        public boolean getLineContainsTab(int line) {
            return false;
        }

        @Override
        public int getLineCount() {
            return LINE_COUNT;
        }

        @Override
        public int getLineDescent(int line) {
            return LINE_DESCENT;
        }

        @Override
        public Directions getLineDirections(int line) {
            return Layout.DIRS_ALL_LEFT_TO_RIGHT;
        }

        @Override
        public int getLineStart(int line) {
            if (line < 0) {
                return 0;
            }
            return line;
        }

        @Override
        public int getLineTop(int line) {
            if (line < 0) {
                return 0;
            }
            return LINE_HEIGHT * (line);
        }

        @Override
        public int getParagraphDirection(int line) {
            return 0;
        }

        @Override
        public int getTopPadding() {
            return 0;
        }
    }

    @Test
    public void testGetLineWidth() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        for (int i = 0; i < LINE_COUNT; i++) {
            int start = layout.getLineStart(i);
            int end = layout.getLineEnd(i);
            String text = LAYOUT_TEXT.toString().substring(start, end);
            assertEquals(mTextPaint.measureText(text), layout.getLineWidth(i), 1.0f);
        }
    }

    @Test
    public void testGetCursorPath() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        Path path = new Path();
        final float epsilon = 1.0f;
        for (int i = 0; i < LINE_COUNT; i++) {
            layout.getCursorPath(i, path, LAYOUT_TEXT);
            RectF bounds = new RectF();
            path.computeBounds(bounds, false);
            assertTrue(bounds.top >= layout.getLineTop(i) - epsilon);
            assertTrue(bounds.bottom <= layout.getLineBottom(i) + epsilon);
        }
    }

    @Test
    public void testDraw() {
        Layout layout = new MockLayout(LAYOUT_TEXT, mTextPaint, mWidth,
                mAlign, mSpacingMult, mSpacingAdd);
        final int width = 256;
        final int height = 256;
        MockCanvas c = new MockCanvas(width, height);
        layout.draw(c);
        List<MockCanvas.DrawCommand> drawCommands = c.getDrawCommands();
        assertEquals(LINE_COUNT, drawCommands.size());
        for (int i = 0; i < LINE_COUNT; i++) {
            MockCanvas.DrawCommand drawCommand = drawCommands.get(i);
            int start = layout.getLineStart(i);
            int end = layout.getLineEnd(i);
            assertEquals(LAYOUT_TEXT.toString().substring(start, end), drawCommand.text);
            float expected_y = (i + 1) * LINE_HEIGHT - LINE_DESCENT;
            assertEquals(expected_y, drawCommand.y, 0.0f);
        }
    }

    private final class MockCanvas extends Canvas {

        class DrawCommand {
            public final String text;
            public final float x;
            public final float y;

            DrawCommand(String text, float x, float y) {
                this.text = text;
                this.x = x;
                this.y = y;
            }
        }

        List<DrawCommand> mDrawCommands;

        MockCanvas(int width, int height) {
            super();
            mDrawCommands = new ArrayList<>();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            setBitmap(bitmap);
        }

        // Drawing text with either drawText or drawTextRun is valid; we don't care which.
        // We also don't care which of the string representations is used.

        @Override
        public void drawText(String text, int start, int end, float x, float y, Paint p) {
            mDrawCommands.add(new DrawCommand(text.substring(start, end), x, y));
        }

        @Override
        public void drawText(CharSequence text, int start, int end, float x, float y, Paint p) {
            drawText(text.toString(), start, end, x, y, p);
        }

        @Override
        public void drawText(char[] text, int index, int count, float x, float y, Paint p) {
            mDrawCommands.add(new DrawCommand(new String(text, index, count), x, y));
        }

        @Override
        public void drawTextRun(CharSequence text, int start, int end, int contextStart,
                int contextEnd, float x, float y, boolean isRtl, Paint paint) {
            drawText(text, start, end, x, y, paint);
        }

        @Override
        public void drawTextRun(char[] text, int index, int count, int contextIndex,
                int contextCount, float x, float y, boolean isRtl, Paint paint) {
            drawText(text, index, count, x, y, paint);
        }

        List<DrawCommand> getDrawCommands() {
            return mDrawCommands;
        }
    }

    private static final String LTR = "a";
    private static final String RTL = "\u05D0";  // HEBREW LETTER ALEF
    private static final String LTR_SP = "\uD801\uDCB0";  // OSAGE CAPITAL LETTER A
    private static final String RTL_SP = "\uD83A\uDD00";  // ADLAM CAPITAL LETTER ALIF

    private static final String LRI = "\u2066";  // LEFT-TO-RIGHT ISOLATE
    private static final String RLI = "\u2067";  // RIGHT-TO-LEFT ISOLATE
    private static final String PDI = "\u2069";  // POP DIRECTIONAL ISOLATE

    private static void assertPrimaryIsTrailingPrevious(String input, boolean[] expected) {
        assertEquals(input.length() + 1, expected.length);

        boolean[] actual = new boolean[expected.length];
        TextPaint paint = new TextPaint();
        paint.setTextSize(16.0f);
        Layout layout = StaticLayout.Builder.obtain(
                input, 0, input.length(), paint, Integer.MAX_VALUE).build();
        for (int i = 0; i <= input.length(); ++i) {
            actual[i] = layout.primaryIsTrailingPrevious(i);
        }
        assertArrayEquals(expected, actual);
        assertArrayEquals(actual, layout.primaryIsTrailingPreviousAllLineOffsets(0));
    }

    @Test
    public void testPrimaryIsTrailingPrevious() {
        assertPrimaryIsTrailingPrevious(
                LTR + " " + LTR + LTR + " " + LTR + LTR + LTR,
                new boolean[]{false, false, false, false, false, false, false, false, false});
        assertPrimaryIsTrailingPrevious(
                RTL + " " + RTL + RTL + " " + RTL + RTL + RTL,
                new boolean[]{false, false, false, false, false, false, false, false, false});
        assertPrimaryIsTrailingPrevious(
                LTR + RTL + LTR + RTL + LTR,
                new boolean[]{false, true, false, true, false, false});
        assertPrimaryIsTrailingPrevious(
                RTL + LTR + RTL + LTR + RTL,
                new boolean[]{false, true, false, true, false, false});
        assertPrimaryIsTrailingPrevious(
                RTL_SP + LTR_SP + RTL_SP + LTR_SP + RTL_SP,
                new boolean[]{
                        false, false, true, false, false, false, true, false, false, false, false});
        assertPrimaryIsTrailingPrevious(
                LTR_SP + RTL_SP + LTR_SP + RTL_SP + LTR_SP,
                new boolean[]{
                        false, false, true, false, false, false, true, false, false, false, false});
        assertPrimaryIsTrailingPrevious(
                LTR + RLI + LTR + RTL + PDI + LTR,
                new boolean[]{false, false, true, false, false, false, false});
        assertPrimaryIsTrailingPrevious(
                RTL + LRI + RTL + LTR + PDI + RTL,
                new boolean[]{false, false, true, false, false, false, false});
        assertPrimaryIsTrailingPrevious(
                "",
                new boolean[]{false});
    }
}

