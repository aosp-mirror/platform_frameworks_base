/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.graphics.text;

/** @hide */
public class GraphemeBreak {
    private GraphemeBreak() { }

    /**
     * Util method that checks if the offsets in given range are grapheme break.
     *
     * @param advances the advances of characters in the given text. It contains the font
     *                information used by the algorithm to determine the grapheme break. It's useful
     *                 when some character is missing in the font. For example, if the smile emoji
     *                 "0xD83D 0xDE0A" is not found in the font and is displayed as 2 characters.
     *                 We can't treat it as a single grapheme cluster.
     * @param text the text to be processed.
     * @param start the start offset of the queried range, inclusive.
     * @param end the end offset of the queried range, exclusive.
     * @param isGraphemeBreak the array to receive the result. The i-th element of the
     *                       array will be set to true if the offset (start + i) is a grapheme
     *                       break; otherwise, it will be set to false.
     */
    public static void isGraphemeBreak(float[] advances, char[] text, int start, int end,
            boolean[] isGraphemeBreak) {
        if (start > end) {
            throw new IllegalArgumentException("start is greater than end: start = " + start
                    + " end = " + end);
        }
        if (advances.length < end) {
            throw new IllegalArgumentException("the length of advances is less than end"
                    + "advances.length = " + advances.length
                    + " end = " + end);
        }
        if (isGraphemeBreak.length < end - start) {
            throw new IndexOutOfBoundsException("isGraphemeBreak doesn't have enough space to "
                    + "receive the result, isGraphemeBreak.length: " + isGraphemeBreak.length
                    + " needed space: " + (end - start));
        }
        nIsGraphemeBreak(advances, text, start, end, isGraphemeBreak);
    }

    private static native void nIsGraphemeBreak(float[] advances, char[] text, int start, int end,
            boolean[] isGraphemeBreak);
}
