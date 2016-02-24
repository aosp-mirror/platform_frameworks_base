/*
 * Copyright (C) 2016 The Android Open Source Project
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

import java.util.Arrays;

/**
 * An utility class for Emoji.
 * @hide
 */
public class Emoji {
    // See http://www.unicode.org/Public/emoji/3.0/emoji-data.txt
    private static int[] EMOJI_MODIFIER_BASE = {
        0x261D, 0x26F9, 0x270A, 0x270B, 0x270C, 0x270D, 0x1F385, 0x1F3C3, 0x1F3C4, 0x1F3CA,
        0x1F3CB, 0x1F442, 0x1F443, 0x1F446, 0x1F447, 0x1F448, 0x1F449, 0x1F44A, 0x1F44B, 0x1F44C,
        0x1F44D, 0x1F44E, 0x1F44F, 0x1F450, 0x1F466, 0x1F467, 0x1F468, 0x1F469, 0x1F46E, 0x1F470,
        0x1F471, 0x1F472, 0x1F473, 0x1F474, 0x1F475, 0x1F476, 0x1F477, 0x1F478, 0x1F47C, 0x1F481,
        0x1F482, 0x1F483, 0x1F485, 0x1F486, 0x1F487, 0x1F4AA, 0x1F575, 0x1F57A, 0x1F590, 0x1F595,
        0x1F596, 0x1F645, 0x1F646, 0x1F647, 0x1F64B, 0x1F64C, 0x1F64D, 0x1F64E, 0x1F64F, 0x1F6A3,
        0x1F6B4, 0x1F6B5, 0x1F6B6, 0x1F6C0, 0x1F918, 0x1F919, 0x1F91A, 0x1F91B, 0x1F91C, 0x1F91D,
        0x1F91E, 0x1F926, 0x1F930, 0x1F933, 0x1F934, 0x1F935, 0x1F936, 0x1F937, 0x1F938, 0x1F939,
        0x1F93B, 0x1F93C, 0x1F93D, 0x1F93E
    };

    // See http://www.unicode.org/emoji/charts/emoji-zwj-sequences.html
    private static int[] ZWJ_EMOJI = {
        0x2764, 0x1F441, 0x1F466, 0x1F467, 0x1F468, 0x1F469, 0x1F48B, 0x1F5E8
    };

    public static int COMBINING_ENCLOSING_KEYCAP = 0x20E3;

    public static int ZERO_WIDTH_JOINER = 0x200D;

    public static int VARIATION_SELECTOR_16 = 0xFE0F;

    // Returns true if the given code point is regional indicator symbol.
    public static boolean isRegionalIndicatorSymbol(int codepoint) {
        return 0x1F1E6 <= codepoint && codepoint <= 0x1F1FF;
    }

    // Returns true if the given code point is emoji modifier.
    public static boolean isEmojiModifier(int codepoint) {
        return 0x1F3FB <= codepoint && codepoint <= 0x1F3FF;
    }

    // Returns true if the given code point is emoji modifier base.
    public static boolean isEmojiModifierBase(int codePoint) {
        return Arrays.binarySearch(EMOJI_MODIFIER_BASE, codePoint) >= 0;
    }

    // Returns true if the character appears before or after zwj in a zwj emoji sequence.
    public static boolean isZwjEmoji(int codePoint) {
        return Arrays.binarySearch(ZWJ_EMOJI, codePoint) >= 0;
    }

    // Returns true if the character can be a base character of COMBINING ENCLOSING KEYCAP.
    public static boolean isKeycapBase(int codePoint) {
        return ('0' <= codePoint && codePoint <= '9') || codePoint == '#' || codePoint == '*';
    }
}
