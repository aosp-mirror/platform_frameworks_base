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

import android.icu.lang.UCharacter;
import android.icu.lang.UProperty;

/**
 * An utility class for Emoji.
 * @hide
 */
public class Emoji {
    public static int COMBINING_ENCLOSING_KEYCAP = 0x20E3;

    public static int ZERO_WIDTH_JOINER = 0x200D;

    public static int VARIATION_SELECTOR_16 = 0xFE0F;

    public static int CANCEL_TAG = 0xE007F;

    /**
     * Returns true if the given code point is regional indicator symbol.
     */
    public static boolean isRegionalIndicatorSymbol(int codePoint) {
        return 0x1F1E6 <= codePoint && codePoint <= 0x1F1FF;
    }

    /**
     * Returns true if the given code point is emoji modifier.
     */
    public static boolean isEmojiModifier(int codePoint) {
        return UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_MODIFIER);
    }

    // Returns true if the given code point is emoji modifier base.
    public static boolean isEmojiModifierBase(int codePoint) {
        // These two characters were removed from Emoji_Modifier_Base in Emoji 4.0, but we need to
        // keep them as emoji modifier bases since there are fonts and user-generated text out there
        // that treats these as potential emoji bases.
        if (codePoint == 0x1F91D || codePoint == 0x1F93C) {
            return true;
        }
        // Emoji Modifier Base characters new in Unicode emoji 5.0.
        // From http://www.unicode.org/Public/emoji/5.0/emoji-data.txt
        // TODO: Remove once emoji-data.text 5.0 is in ICU or update to 6.0.
        if (codePoint == 0x1F91F
                || (0x1F931 <= codePoint && codePoint <= 0x1F932)
                || (0x1F9D1 <= codePoint && codePoint <= 0x1F9DD)) {
            return true;
        }
        return UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_MODIFIER_BASE);
    }

    /**
     * Returns true if the character is a new emoji still not supported in our version of ICU.
     */
    public static boolean isNewEmoji(int codePoint) {
        // Emoji characters new in Unicode emoji 5.0.
        // From http://www.unicode.org/Public/emoji/5.0/emoji-data.txt
        // TODO: Remove once emoji-data.text 5.0 is in ICU or update to 6.0.
        if (codePoint < 0x1F6F7 || codePoint > 0x1F9E6) {
            // Optimization for characters outside the new emoji range.
            return false;
        }
        return (0x1F6F7 <= codePoint && codePoint <= 0x1F6F8)
                || codePoint == 0x1F91F
                || (0x1F928 <= codePoint && codePoint <= 0x1F92F)
                || (0x1F931 <= codePoint && codePoint <= 0x1F932)
                || codePoint == 0x1F94C
                || (0x1F95F <= codePoint && codePoint <= 0x1F96B)
                || (0x1F992 <= codePoint && codePoint <= 0x1F997)
                || (0x1F9D0 <= codePoint && codePoint <= 0x1F9E6);
    }

    /**
     * Returns true if the character has Emoji property.
     */
    public static boolean isEmoji(int codePoint) {
        return isNewEmoji(codePoint) || UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI);
    }

    // Returns true if the character can be a base character of COMBINING ENCLOSING KEYCAP.
    public static boolean isKeycapBase(int codePoint) {
        return ('0' <= codePoint && codePoint <= '9') || codePoint == '#' || codePoint == '*';
    }

    /**
     * Returns true if the character can be a part of tag_spec in emoji tag sequence.
     *
     * Note that 0xE007F (CANCEL TAG) is not included.
     */
    public static boolean isTagSpecChar(int codePoint) {
        return 0xE0020 <= codePoint && codePoint <= 0xE007E;
    }
}
