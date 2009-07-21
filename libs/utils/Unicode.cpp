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

#include <utils/AndroidUnicode.h>
#include "CharacterData.h"

#define LOG_TAG "Unicode"
#include <utils/Log.h>

// ICU headers for using macros
#include <unicode/utf16.h>

#define MIN_RADIX 2
#define MAX_RADIX 36

#define TYPE_SHIFT 0
#define TYPE_MASK ((1<<5)-1)

#define DIRECTION_SHIFT (TYPE_SHIFT+5)
#define DIRECTION_MASK ((1<<5)-1)

#define MIRRORED_SHIFT (DIRECTION_SHIFT+5)
#define MIRRORED_MASK ((1<<1)-1)

#define TOUPPER_SHIFT (MIRRORED_SHIFT+1)
#define TOUPPER_MASK ((1<<6)-1)

#define TOLOWER_SHIFT (TOUPPER_SHIFT+6)
#define TOLOWER_MASK ((1<<6)-1)

#define TOTITLE_SHIFT (TOLOWER_SHIFT+6)
#define TOTITLE_MASK ((1<<2)-1)

#define MIRROR_SHIFT (TOTITLE_SHIFT+2)
#define MIRROR_MASK ((1<<5)-1)

#define NUMERIC_SHIFT (TOTITLE_SHIFT+2)
#define NUMERIC_MASK ((1<<7)-1)

#define DECOMPOSITION_SHIFT (11)
#define DECOMPOSITION_MASK ((1<<5)-1)

/*
 * Returns the value stored in the CharacterData tables that contains
 * an index into the packed data table and the decomposition type.
 */
static uint16_t findCharacterValue(UChar32 c)
{
    LOG_ASSERT(c >= 0 && c <= 0x10FFFF, "findCharacterValue received an invalid codepoint");
    if (c < 256)
        return CharacterData::LATIN1_DATA[c];

    // Rotate the bits because the tables are separated into even and odd codepoints
    c = (c >> 1) | ((c & 1) << 20);

    CharacterData::Range search = CharacterData::FULL_DATA[c >> 16];
    const uint32_t* array = search.array;
 
    // This trick is so that that compare in the while loop does not
    // need to shift the array entry down by 16
    c <<= 16;
    c |= 0xFFFF;

    int high = (int)search.length - 1;
    int low = 0;

    if (high < 0)
        return 0;
    
    while (low < high - 1)
    {
        int probe = (high + low) >> 1;

        // The entries contain the codepoint in the high 16 bits and the index
        // into PACKED_DATA in the low 16.
        if (array[probe] > (unsigned)c)
            high = probe;
        else
            low = probe;
    }

    LOG_ASSERT((array[low] <= (unsigned)c), "A suitable range was not found");
    return array[low] & 0xFFFF;
}

uint32_t android::Unicode::getPackedData(UChar32 c)
{
    // findCharacterValue returns a 16-bit value with the top 5 bits containing a decomposition type
    // and the remaining bits containing an index.
    return CharacterData::PACKED_DATA[findCharacterValue(c) & 0x7FF];
}

android::Unicode::CharType android::Unicode::getType(UChar32 c)
{
    if (c < 0 || c >= 0x10FFFF)
        return CHARTYPE_UNASSIGNED;
    return (CharType)((getPackedData(c) >> TYPE_SHIFT) & TYPE_MASK);
}

android::Unicode::DecompositionType android::Unicode::getDecompositionType(UChar32 c)
{
    // findCharacterValue returns a 16-bit value with the top 5 bits containing a decomposition type
    // and the remaining bits containing an index.
    return (DecompositionType)((findCharacterValue(c) >> DECOMPOSITION_SHIFT) & DECOMPOSITION_MASK);
}

int android::Unicode::getDigitValue(UChar32 c, int radix)
{
    if (radix < MIN_RADIX || radix > MAX_RADIX)
        return -1;

    int tempValue = radix;
    
    if (c >= '0' && c <= '9')
        tempValue = c - '0';
    else if (c >= 'a' && c <= 'z')
        tempValue = c - 'a' + 10;
    else if (c >= 'A' && c <= 'Z')
        tempValue = c - 'A' + 10;
    
    return tempValue < radix ? tempValue : -1;
}

int android::Unicode::getNumericValue(UChar32 c)
{
    if (isMirrored(c))
        return -1;
    
    return (int) CharacterData::NUMERICS[((getPackedData(c) >> NUMERIC_SHIFT) & NUMERIC_MASK)];
}

UChar32 android::Unicode::toLower(UChar32 c)
{
    return c + CharacterData::LCDIFF[(getPackedData(c) >> TOLOWER_SHIFT) & TOLOWER_MASK];
}

UChar32 android::Unicode::toUpper(UChar32 c)
{
    return c + CharacterData::UCDIFF[(getPackedData(c) >> TOUPPER_SHIFT) & TOUPPER_MASK];
}

android::Unicode::Direction android::Unicode::getDirectionality(UChar32 c)
{
    uint32_t data = getPackedData(c);

    if (0 == data)
        return DIRECTIONALITY_UNDEFINED;

    Direction d = (Direction) ((data >> DIRECTION_SHIFT) & DIRECTION_MASK);

    if (DIRECTION_MASK == d)
        return DIRECTIONALITY_UNDEFINED;
    
    return d;
}

bool android::Unicode::isMirrored(UChar32 c)
{
    return ((getPackedData(c) >> MIRRORED_SHIFT) & MIRRORED_MASK) != 0;
}

UChar32 android::Unicode::toMirror(UChar32 c)
{
    if (!isMirrored(c))
        return c;

    return c + CharacterData::MIRROR_DIFF[(getPackedData(c) >> MIRROR_SHIFT) & MIRROR_MASK];
}

UChar32 android::Unicode::toTitle(UChar32 c)
{
    int32_t diff = CharacterData::TCDIFF[(getPackedData(c) >> TOTITLE_SHIFT) & TOTITLE_MASK];

    if (TOTITLE_MASK == diff)
        return toUpper(c);
    
    return c + diff;
}


