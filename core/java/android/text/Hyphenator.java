/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides constants and pack/unpack methods for the HyphenEdit.
 *
 * Hyphenator provides constant values for start of line and end of line modification.
 * For example, by passing {@link #END_HYPHEN_EDIT_INSERT_HYPHEN} like as follows, HYPHEN(U+2010)
 * character is appended at the end of line.
 *
 * <pre>
 * <code>
 *   Paint paint = new Paint();
 *   paint.setHyphenEdit(Hyphenator.packHyphenEdit(
 *       Hyphenator.START_HYPHEN_EDIT_NO_EDIT,
 *       Hyphenator.END_HYPHEN_EDIT_INSERT_HYPHEN));
 *   paint.measureText("abc", 0, 3);  // Returns the width of "abc‐"
 *   Canvas.drawText("abc", 0, 3, 0f, 0f, paint);  // Draws "abc‐"
 * </code>
 * </pre>
 *
 * @see android.graphics.Paint#setHyphenEdit(int)
 */
public class Hyphenator {
    private Hyphenator() {}

    /** @hide */
    @IntDef(prefix = { "START_HYPHEN_EDIT_" }, value = {
        START_HYPHEN_EDIT_NO_EDIT,
        START_HYPHEN_EDIT_INSERT_HYPHEN,
        START_HYPHEN_EDIT_INSERT_ZWJ
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StartHyphenEdit {}

    /**
     * An integer representing the starting of the line has no modification for hyphenation.
     */
    public static final int START_HYPHEN_EDIT_NO_EDIT = 0x00;

    /**
     * An integer representing the starting of the line has normal hyphen character (U+002D).
     */
    public static final int START_HYPHEN_EDIT_INSERT_HYPHEN = 0x01;

    /**
     * An integer representing the starting of the line has Zero-Width-Joiner (U+200D).
     */
    public static final int START_HYPHEN_EDIT_INSERT_ZWJ = 0x02;

    /** @hide */
    @IntDef(prefix = { "END_HYPHEN_EDIT_" }, value = {
        END_HYPHEN_EDIT_NO_EDIT,
        END_HYPHEN_EDIT_REPLACE_WITH_HYPHEN,
        END_HYPHEN_EDIT_INSERT_HYPHEN,
        END_HYPHEN_EDIT_INSERT_ARMENIAN_HYPHEN,
        END_HYPHEN_EDIT_INSERT_MAQAF,
        END_HYPHEN_EDIT_INSERT_UCAS_HYPHEN,
        END_HYPHEN_EDIT_INSERT_ZWJ_AND_HYPHEN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EndHyphenEdit {}

    /**
     * An integer representing the end of the line has no modification for hyphenation.
     */
    public static final int END_HYPHEN_EDIT_NO_EDIT = 0x00;

    /**
     * An integer representing the character at the end of the line is replaced with hyphen
     * character (U+002D).
     */
    public static final int END_HYPHEN_EDIT_REPLACE_WITH_HYPHEN = 0x01;

    /**
     * An integer representing the end of the line has normal hyphen character (U+002D).
     */
    public static final int END_HYPHEN_EDIT_INSERT_HYPHEN = 0x02;

    /**
     * An integer representing the end of the line has Armentian hyphen (U+058A).
     */
    public static final int END_HYPHEN_EDIT_INSERT_ARMENIAN_HYPHEN = 0x03;

    /**
     * An integer representing the end of the line has maqaf (Hebrew hyphen, U+05BE).
     */
    public static final int END_HYPHEN_EDIT_INSERT_MAQAF = 0x04;

    /**
     * An integer representing the end of the line has Canadian Syllabics hyphen (U+1400).
     */
    public static final int END_HYPHEN_EDIT_INSERT_UCAS_HYPHEN = 0x05;

    /**
     * An integer representing the end of the line has Zero-Width-Joiner (U+200D) followed by normal
     * hyphen character (U+002D).
     */
    public static final int END_HYPHEN_EDIT_INSERT_ZWJ_AND_HYPHEN = 0x06;

    // Following three constants are used for packing start hyphen edit and end hyphen edit into
    // single integer. Following encodings must be the same as the minikin's one.
    // See frameworks/minikin/include/Hyphenator.h for more details.
    private static final int END_HYPHEN_EDIT_MASK = 0x07;  // 0b00111
    private static final int START_HYPHEN_EDIT_MASK = 0x18;  // 0b11000
    private static final int START_HYPHEN_EDIT_SHIFT = 0x03;

    /**
     * Extract start hyphen edit from packed value.
     */
    public static @StartHyphenEdit int unpackStartHyphenEdit(int hyphenEdit) {
        return (hyphenEdit & START_HYPHEN_EDIT_MASK) >> START_HYPHEN_EDIT_SHIFT;
    }

    /**
     * Extract end hyphen edit from packed value.
     */
    public static @EndHyphenEdit int unpackEndHyphenEdit(int hyphenEdit) {
        return hyphenEdit & END_HYPHEN_EDIT_MASK;
    }

    /**
     * Pack the start hyphen edit and end hyphen edit into single integer.
     */
    public static int packHyphenEdit(@StartHyphenEdit int startHyphenEdit,
            @EndHyphenEdit int endHyphenEdit) {
        return ((startHyphenEdit << START_HYPHEN_EDIT_SHIFT) & START_HYPHEN_EDIT_MASK)
                | (endHyphenEdit & END_HYPHEN_EDIT_MASK);
    }


    /**
     * @hide
     */
    public static void init() {
        nInit();
    }

    private static native void nInit();
}
