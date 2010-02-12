/*
 * Copyright (C) 2006 The Android Open Source Project
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

//

#ifndef ANDROID_UNICODE_H
#define ANDROID_UNICODE_H

#include <stdint.h>
#include <sys/types.h>

#define REPLACEMENT_CHAR (0xFFFD)

// this part of code is copied from umachine.h under ICU
/**
 * Define UChar32 as a type for single Unicode code points.
 * UChar32 is a signed 32-bit integer (same as int32_t).
 *
 * The Unicode code point range is 0..0x10ffff.
 * All other values (negative or >=0x110000) are illegal as Unicode code points.
 * They may be used as sentinel values to indicate "done", "error"
 * or similar non-code point conditions.
 *
 * @stable ICU 2.4
 */
typedef int32_t UChar32;

namespace android {

    class Encoding;
    /**
     * \class Unicode
     *
     * Helper class for getting properties of Unicode characters. Characters
     * can have one of the types listed in CharType and each character can have the
     * directionality of Direction.
     */
    class Unicode
    {
    public:
        /**
         * Directions specified in the Unicode standard. These directions map directly
         * to java.lang.Character.
         */
        enum Direction {
            DIRECTIONALITY_UNDEFINED = -1,
            DIRECTIONALITY_LEFT_TO_RIGHT,
            DIRECTIONALITY_RIGHT_TO_LEFT,
            DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            DIRECTIONALITY_EUROPEAN_NUMBER,
            DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
            DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR,
            DIRECTIONALITY_ARABIC_NUMBER,
            DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
            DIRECTIONALITY_NONSPACING_MARK,
            DIRECTIONALITY_BOUNDARY_NEUTRAL,
            DIRECTIONALITY_PARAGRAPH_SEPARATOR,
            DIRECTIONALITY_SEGMENT_SEPARATOR,
            DIRECTIONALITY_WHITESPACE,
            DIRECTIONALITY_OTHER_NEUTRALS,
            DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
            DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
            DIRECTIONALITY_POP_DIRECTIONAL_FORMAT
        };

        /**
         * Returns the packed data for java calls
         * @param c The unicode character.
         * @return The packed data for the character.
         *
         * Copied from java.lang.Character implementation:
         * 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1
         * F E D C B A 9 8 7 6 5 4 3 2 1 0 F E D C B A 9 8 7 6 5 4 3 2 1 0
         * 
         *                              31 types                 ---------
         *                   18 directionalities       ---------
         *                   2 mirroreds             -
         *                               -----------      56  toupper diffs
         *                   -----------                  48  tolower diffs
         *               ---                              4 totitlecase diffs
         * -------------                                 84 numeric values
         *     ---------                                 24 mirror char diffs
         */
        static uint32_t getPackedData(UChar32 c);
        
        /**
         * Get the directionality of the character.
         * @param c The unicode character.
         * @return The direction of the character or DIRECTIONALITY_UNDEFINED.
         */
        static Direction getDirectionality(UChar32 c);
            
        /**
         * Check if the character is a mirrored character. This means that the character
         * has an equivalent character that is the mirror image of itself.
         * @param c The unicode character.
         * @return True iff c has a mirror equivalent.
         */
        static bool isMirrored(UChar32 c);
         
        /**
         * Return the mirror of the given character.
         * @param c The unicode character.
         * @return The mirror equivalent of c. If c does not have a mirror equivalent,
         *         the original character is returned.
         * @see isMirrored
         */
        static UChar32 toMirror(UChar32 c);
   };

}

#endif
