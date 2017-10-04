/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Typeface;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LineBreakingOverhangsTest {
    private static final int EM = 100;  // Make 1em == 100px.
    private static final TextPaint sTextPaint = new TextPaint();

    static {
        // The test font has following coverage and overhangs.
        // All the characters have a width of 1em.
        // space: no overhangs
        // R: 4em overhang on the right
        // a: no overhang
        // y: 1.5em overhang on the left
        sTextPaint.setTypeface(Typeface.createFromAsset(
                  InstrumentationRegistry.getTargetContext().getAssets(),
                  "fonts/LineBreakingOverhangsTestFont.ttf"));
        sTextPaint.setTextSize(EM);
    }

    private static void layout(@NonNull CharSequence source, @NonNull int[] breaks, double width,
            @Nullable int[] leftPadding, @Nullable int[] rightPadding) {
        layout(source, breaks, width, leftPadding, rightPadding, null /* indents */);
    }

    private static void layout(@NonNull CharSequence source, @NonNull int[] breaks, double width,
            @Nullable int[] leftPadding, @Nullable int[] rightPadding, @Nullable int[] indents) {
        final StaticLayout staticLayout = StaticLayout.Builder
                .obtain(source, 0, source.length(), sTextPaint, (int) width)
                .setAvailablePaddings(leftPadding, rightPadding)
                .setIndents(indents, indents)
                .build();

        final int lineCount = breaks.length + 1;
        assertEquals("Number of lines", lineCount, staticLayout.getLineCount());

        for (int line = 0; line < lineCount; line++) {
            final int lineStart = staticLayout.getLineStart(line);
            final int lineEnd = staticLayout.getLineEnd(line);

            if (line == 0) {
                assertEquals("Line start for first line", 0, lineStart);
            } else {
                assertEquals("Line start for line " + line, breaks[line - 1], lineStart);
            }

            if (line == lineCount - 1) {
                assertEquals("Line end for last line", source.length(), lineEnd);
            } else {
                assertEquals("Line end for line " + line, breaks[line], lineEnd);
            }
        }
    }

    private static final int[] NO_BREAK = new int[] {};

    private static final int[] NO_PADDING = null;
    // Maximum needed for left side of 'y'.
    private static final int[] FULL_LEFT_PADDING = new int[] {(int) (1.5 * EM)};
    // Maximum padding needed for right side of 'R'.
    private static final int[] FULL_RIGHT_PADDING = new int[] {4 * EM};

    private static final int[] ONE_EM_PADDING = new int[] {1 * EM};
    private static final int[] HALF_EM_PADDING = new int[] {(int) (0.5 * EM)};
    private static final int[] QUARTER_EM_PADDING = new int[] {(int) (0.25 * EM)};

    @Test
    public void testRightOverhang() {
        // The advance of "aaa R" is 5em, but the right-side overhang of 'R' would need 4em more, so
        // we break the line if there's not enough overhang.

        // Enough right padding, so the whole line fits in 5em.
        layout("aaa R", NO_BREAK, 5 * EM, NO_PADDING, FULL_RIGHT_PADDING);

        // No right padding, so we'd need 9em to fit the advance and the right padding of 'R'.
        layout("aaa R", new int[] {4}, 8.9 * EM, NO_PADDING, NO_PADDING);
        layout("aaa R", NO_BREAK, 9 * EM, NO_PADDING, NO_PADDING);

        // 1em of right padding means we can fit the string in 8em.
        layout("aaa R", new int[] {4}, 7.9 * EM, NO_PADDING, ONE_EM_PADDING);
        layout("aaa R", NO_BREAK, 8 * EM, NO_PADDING, ONE_EM_PADDING);
    }

    @Test
    public void testLeftOverhang() {
        // The advance of "y a" is 3em, but the left-side overhang of 'y' would need 1.5em more, so
        // we break the line if there's not enough overhang.

        // Enough left padding, so the whole line fits in 3em.
        layout("y a", NO_BREAK, 3 * EM, FULL_LEFT_PADDING, NO_PADDING);

        // No right padding, so we'd need 4.5em to fit the advance and the left padding of 'y'.
        layout("y a", new int[] {2}, 4.4 * EM, NO_PADDING, NO_PADDING);
        layout("y a", NO_BREAK, 4.5 * EM, NO_PADDING, NO_PADDING);

        // 1em of left padding means we can fit the string in 3.5em.
        layout("y a", new int[] {2}, 3.4 * EM, ONE_EM_PADDING, NO_PADDING);
        layout("y a", NO_BREAK, 3.5 * EM, ONE_EM_PADDING, NO_PADDING);
    }

    @Test
    public void testBothSidesOverhang() {
        // The advance of "y a R" is 5em, but the left-side overhang of 'y' would need 1.5em more,
        // and the right side overhang or 'R' would need 4em more, so we break the line if there's
        // not enough overhang.

        // Enough padding, so the whole line fits in 5em.
        layout("y a R", NO_BREAK, 5 * EM, FULL_LEFT_PADDING, FULL_RIGHT_PADDING);

        // No padding, so we'd need 10.5em to fit the advance and the paddings.
        layout("y a R", new int[] {4}, 10.4 * EM, NO_PADDING, NO_PADDING);
        layout("y a R", NO_BREAK, 10.5 * EM, NO_PADDING, NO_PADDING);

        // 1em of padding on each side means we can fit the string in 8.5em.
        layout("y a R", new int[] {4}, 8.4 * EM, ONE_EM_PADDING, ONE_EM_PADDING);
        layout("y a R", NO_BREAK, 8.5 * EM, ONE_EM_PADDING, ONE_EM_PADDING);
    }

    @Test
    public void testIndentsDontAffectPaddings() {
        // This is identical to the previous test, except that it applies wide indents of 4em on
        // each side and thus needs an extra 8em of width. This test makes sure that indents and
        // paddings are independent.
        final int[] indents = new int[] {4 * EM};
        final int indentAdj = 8 * EM;

        // Enough padding, so the whole line fits in 5em.
        layout("y a R", NO_BREAK, 5 * EM + indentAdj, FULL_LEFT_PADDING, FULL_RIGHT_PADDING,
                indents);

        // No padding, so we'd need 10.5em to fit the advance and the paddings.
        layout("y a R", new int[] {4}, 10.4 * EM + indentAdj, NO_PADDING, NO_PADDING, indents);
        layout("y a R", NO_BREAK, 10.5 * EM + indentAdj, NO_PADDING, NO_PADDING, indents);

        // 1em of padding on each side means we can fit the string in 8.5em.
        layout("y a R", new int[] {4}, 8.4 * EM + indentAdj, ONE_EM_PADDING, ONE_EM_PADDING,
                indents);
        layout("y a R", NO_BREAK, 8.5 * EM + indentAdj, ONE_EM_PADDING, ONE_EM_PADDING, indents);
    }
}
