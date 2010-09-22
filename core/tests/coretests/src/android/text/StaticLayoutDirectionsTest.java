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

import android.text.Layout.Directions;
import android.text.StaticLayoutTest.LayoutBuilder;

import java.util.Arrays;
import java.util.Formatter;

import junit.framework.TestCase;

public class StaticLayoutDirectionsTest extends TestCase {
    private static final char ALEF = '\u05d0';

    private static Directions dirs(int ... dirs) {
        return new Directions(dirs);
    }

    // constants from Layout that are package-protected
    private static final int RUN_LENGTH_MASK = 0x03ffffff;
    private static final int RUN_LEVEL_SHIFT = 26;
    private static final int RUN_LEVEL_MASK = 0x3f;
    private static final int RUN_RTL_FLAG = 1 << RUN_LEVEL_SHIFT;

    private static final Directions DIRS_ALL_LEFT_TO_RIGHT =
        new Directions(new int[] { 0, RUN_LENGTH_MASK });
    private static final Directions DIRS_ALL_RIGHT_TO_LEFT =
        new Directions(new int[] { 0, RUN_LENGTH_MASK | RUN_RTL_FLAG });

    private static final int LVL1_1 = 1 | (1 << RUN_LEVEL_SHIFT);
    private static final int LVL2_1 = 1 | (2 << RUN_LEVEL_SHIFT);
    private static final int LVL2_2 = 2 | (2 << RUN_LEVEL_SHIFT);

    private static String[] texts = {
        "",
        " ",
        "a",
        "a1",
        "aA",
        "a1b",
        "a1A",
        "aA1",
        "aAb",
        "aA1B",
        "aA1B2",

        // rtl
        "A",
        "A1",
        "Aa",
        "A1B",
        "A1a",
        "Aa1",
        "AaB"
    };

    // Expected directions are an array of start/length+level pairs,
    // in visual order from the leading margin.
    private static Directions[] expected = {
        DIRS_ALL_LEFT_TO_RIGHT,
        DIRS_ALL_LEFT_TO_RIGHT,
        DIRS_ALL_LEFT_TO_RIGHT,
        DIRS_ALL_LEFT_TO_RIGHT,
        dirs(0, 1, 1, LVL1_1),
        DIRS_ALL_LEFT_TO_RIGHT,
        dirs(0, 2, 2, LVL1_1),
        dirs(0, 1, 2, LVL2_1, 1, LVL1_1),
        dirs(0, 1, 1, LVL1_1, 2, 1),
        dirs(0, 1, 3, LVL1_1, 2, LVL2_1, 1, LVL1_1),
        dirs(0, 1, 4, LVL2_1, 3, LVL1_1, 2, LVL2_1, 1, LVL1_1),

        // rtl
        DIRS_ALL_RIGHT_TO_LEFT,
        dirs(0, LVL1_1, 1, LVL2_1),
        dirs(0, LVL1_1, 1, LVL2_1),
        dirs(0, LVL1_1, 1, LVL2_1, 2, LVL1_1),
        dirs(0, LVL1_1, 1, LVL2_2),
        dirs(0, LVL1_1, 1, LVL2_2),
        dirs(0, LVL1_1, 1, LVL2_1, 2, LVL1_1),
    };

    private static String pseudoBidiToReal(String src) {
        char[] chars = src.toCharArray();
        for (int j = 0; j < chars.length; ++j) {
            char c = chars[j];
            if (c >= 'A' && c <= 'D') {
                chars[j] = (char)(ALEF + c - 'A');
            }
        }

        return new String(chars, 0, chars.length);
    }

    // @SmallTest
    public void testDirections() {
        StringBuilder buf = new StringBuilder("\n");
        Formatter f = new Formatter(buf);

        LayoutBuilder b = StaticLayoutTest.builder();
        for (int i = 0; i < texts.length; ++i) {
            b.setText(pseudoBidiToReal(texts[i]));
            checkDirections(b.build(), i, b.text, expected, f);
        }
        if (buf.length() > 1) {
            fail(buf.toString());
        }
    }

    // @SmallTest
    public void testTrailingWhitespace() {
        LayoutBuilder b = StaticLayoutTest.builder();
        b.setText(pseudoBidiToReal("Ab   c"));
        float width = b.paint.measureText(b.text, 0, 5);  // exclude 'c'
        b.setWidth(Math.round(width));
        Layout l = b.build();
        if (l.getLineCount() != 2) {
            throw new RuntimeException("expected 2 lines, got: " + l.getLineCount());
        }
        Directions result = l.getLineDirections(0);
        Directions expected = dirs(0, LVL1_1, 1, LVL2_1, 2, 3 | (1 << Layout.RUN_LEVEL_SHIFT));
        expectDirections("split line", expected, result);
    }

    public void testNextToRightOf() {
        LayoutBuilder b = StaticLayoutTest.builder();
        b.setText(pseudoBidiToReal("aA1B2"));
        // visual a2B1A positions 04321
        // 0: |a2B1A, strong is sol, after -> 0
        // 1: a|2B1A, strong is a, after ->, 1
        // 2: a2|B1A, strong is B, after -> 4
        // 3: a2B|1A, strong is B, before -> 3
        // 4: a2B1|A, strong is A, after -> 2
        // 5: a2B1A|, strong is eol, before -> 5
        int[] expected = { 0, 1, 4, 3, 2, 5 };
        Layout l = b.build();
        int n = 0;
        for (int i = 1; i < expected.length; ++i) {
            int t = l.getOffsetToRightOf(n);
            if (t != expected[i]) {
                fail("offset[" + i + "] to right of: " + n + " expected: " +
                        expected[i] + " got: " + t);
            }
            n = t;
        }
    }

    public void testNextToLeftOf() {
        LayoutBuilder b = StaticLayoutTest.builder();
        b.setText(pseudoBidiToReal("aA1B2"));
        int[] expected = { 0, 1, 4, 3, 2, 5 };
        Layout l = b.build();
        int n = 5;
        for (int i = expected.length - 1; --i >= 0;) {
            int t = l.getOffsetToLeftOf(n);
            if (t != expected[i]) {
                fail("offset[" + i + "] to left of: " + n + " expected: " +
                        expected[i] + " got: " + t);
            }
            n = t;
        }
    }

    // utility, not really a test
    /*
    public void testMeasureText1() {
        LayoutBuilder b = StaticLayoutTest.builder();
        String text = "ABC"; // "abAB"
        b.setText(pseudoBidiToReal(text));
        Layout l = b.build();
        Directions directions = l.getLineDirections(0);

        TextPaint workPaint = new TextPaint();

        int dir = -1; // LEFT_TO_RIGHT
        boolean trailing = true;
        boolean alt = true;
        do {
            dir = -dir;
            do {
                trailing = !trailing;
                for (int offset = 0, end = b.text.length(); offset <= end; ++offset) {
                    float width = Layout.measureText(b.paint,
                            workPaint,
                            b.text,
                            0, offset, end,
                            dir, directions,
                            trailing, false,
                            null);
                    Log.i("BIDI", "dir: " + dir + " trail: " + trailing +
                            " offset: " + offset + " width: " + width);
                }
            } while (!trailing);
        } while (dir > 0);
    }
    */

    // utility for displaying arrays in hex
    private static String hexArray(int[] array) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i : array) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(Integer.toHexString(i));
        }
        sb.append('}');
        return sb.toString();
    }

    private void checkDirections(Layout l, int i, String text,
            Directions[] expectedDirs, Formatter f) {
        Directions expected = expectedDirs[i];
        Directions result = l.getLineDirections(0);
        if (!Arrays.equals(expected.mDirections, result.mDirections)) {
            f.format("%n[%2d] '%s', %s != %s", i, text,
                    hexArray(expected.mDirections),
                    hexArray(result.mDirections));
        }
    }

    private void expectDirections(String msg, Directions expected, Directions result) {
        if (!Arrays.equals(expected.mDirections, result.mDirections)) {
            fail("expected: " + hexArray(expected.mDirections) +
                    " got: " + hexArray(result.mDirections));
        }
    }
}
