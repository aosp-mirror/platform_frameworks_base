/*
 * Copyright (C) 2009 The Android Open Source Project
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

/*------------------------------------------------------------------------------

    Table of contents

     1. Include headers
     2. External compiler flags
     3. Module defines
     4. Local function prototypes
     5. Functions
          h264bsdDecodeExpGolombUnsigned
          h264bsdDecodeExpGolombSigned
          h264bsdDecodeExpGolombMapped
          h264bsdDecodeExpGolombTruncated

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_vlc.h"
#include "basetype.h"
#include "h264bsd_stream.h"
#include "h264bsd_util.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/* definition of special code num, this along with the return value is used
 * to handle code num in the range [0, 2^32] in the DecodeExpGolombUnsigned
 * function */
#define BIG_CODE_NUM 0xFFFFFFFFU

/* Mapping tables for coded_block_pattern, used for decoding of mapped
 * Exp-Golomb codes */
static const u8 codedBlockPatternIntra4x4[48] = {
    47,31,15,0,23,27,29,30,7,11,13,14,39,43,45,46,16,3,5,10,12,19,21,26,28,35,
    37,42,44,1,2,4,8,17,18,20,24,6,9,22,25,32,33,34,36,40,38,41};

static const u8 codedBlockPatternInter[48] = {
    0,16,1,2,4,8,32,3,5,10,12,15,47,7,11,13,14,6,9,31,35,37,42,44,33,34,36,40,
    39,43,45,46,17,18,20,24,19,21,26,28,23,27,29,30,22,25,38,41};

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------

   5.1  Function: h264bsdDecodeExpGolombUnsigned

        Functional description:
            Decode unsigned Exp-Golomb code. This is the same as codeNum used
            in other Exp-Golomb code mappings. Code num (i.e. the decoded
            symbol) is determined as

                codeNum = 2^leadingZeros - 1 + GetBits(leadingZeros)

            Normal decoded symbols are in the range [0, 2^32 - 2]. Symbol
            2^32-1 is indicated by BIG_CODE_NUM with return value HANTRO_OK
            while symbol 2^32  is indicated by BIG_CODE_NUM with return value
            HANTRO_NOK.  These two symbols are special cases with code length
            of 65, i.e.  32 '0' bits, a '1' bit, and either 0 or 1 represented
            by 32 bits.

            Symbol 2^32 is out of unsigned 32-bit range but is needed for
            DecodeExpGolombSigned to express value -2^31.

        Inputs:
            pStrmData       pointer to stream data structure

        Outputs:
            codeNum         decoded code word is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      failure, no valid code word found, note exception
                            with BIG_CODE_NUM

------------------------------------------------------------------------------*/

u32 h264bsdDecodeExpGolombUnsigned(strmData_t *pStrmData, u32 *codeNum)
{

/* Variables */

    u32 bits, numZeros;

/* Code */

    ASSERT(pStrmData);
    ASSERT(codeNum);

    bits = h264bsdShowBits32(pStrmData);

    /* first bit is 1 -> code length 1 */
    if (bits >= 0x80000000)
    {
        h264bsdFlushBits(pStrmData, 1);
        *codeNum = 0;
        return(HANTRO_OK);
    }
    /* second bit is 1 -> code length 3 */
    else if (bits >= 0x40000000)
    {
        if (h264bsdFlushBits(pStrmData, 3) == END_OF_STREAM)
            return(HANTRO_NOK);
        *codeNum = 1 + ((bits >> 29) & 0x1);
        return(HANTRO_OK);
    }
    /* third bit is 1 -> code length 5 */
    else if (bits >= 0x20000000)
    {
        if (h264bsdFlushBits(pStrmData, 5) == END_OF_STREAM)
            return(HANTRO_NOK);
        *codeNum = 3 + ((bits >> 27) & 0x3);
        return(HANTRO_OK);
    }
    /* fourth bit is 1 -> code length 7 */
    else if (bits >= 0x10000000)
    {
        if (h264bsdFlushBits(pStrmData, 7) == END_OF_STREAM)
            return(HANTRO_NOK);
        *codeNum = 7 + ((bits >> 25) & 0x7);
        return(HANTRO_OK);
    }
    /* other code lengths */
    else
    {
#ifndef H264DEC_NEON
        numZeros = 4 + h264bsdCountLeadingZeros(bits, 28);
#else
        numZeros = h264bsdCountLeadingZeros(bits);
#endif
        /* all 32 bits are zero */
        if (numZeros == 32)
        {
            *codeNum = 0;
            h264bsdFlushBits(pStrmData,32);
            bits = h264bsdGetBits(pStrmData, 1);
            /* check 33rd bit, must be 1 */
            if (bits == 1)
            {
                /* cannot use h264bsdGetBits, limited to 31 bits */
                bits = h264bsdShowBits32(pStrmData);
                if (h264bsdFlushBits(pStrmData, 32) == END_OF_STREAM)
                    return(HANTRO_NOK);
                /* code num 2^32 - 1, needed for unsigned mapping */
                if (bits == 0)
                {
                    *codeNum = BIG_CODE_NUM;
                    return(HANTRO_OK);
                }
                /* code num 2^32, needed for unsigned mapping
                 * (results in -2^31) */
                else if (bits == 1)
                {
                    *codeNum = BIG_CODE_NUM;
                    return(HANTRO_NOK);
                }
            }
            /* if more zeros than 32, it is an error */
            return(HANTRO_NOK);
        }
        else
            h264bsdFlushBits(pStrmData,numZeros+1);

        bits = h264bsdGetBits(pStrmData, numZeros);
        if (bits == END_OF_STREAM)
            return(HANTRO_NOK);

        *codeNum = (1 << numZeros) - 1 + bits;

    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

   5.2  Function: h264bsdDecodeExpGolombSigned

        Functional description:
            Decode signed Exp-Golomb code. Code num is determined by
            h264bsdDecodeExpGolombUnsigned and then mapped to signed
            representation as

                symbol = (-1)^(codeNum+1) * (codeNum+1)/2

            Signed symbols shall be in the range [-2^31, 2^31 - 1]. Symbol
            -2^31 is obtained when codeNum is 2^32, which cannot be expressed
            by unsigned 32-bit value. This is signaled as a special case from
            the h264bsdDecodeExpGolombUnsigned by setting codeNum to
            BIG_CODE_NUM and returning HANTRO_NOK status.

        Inputs:
            pStrmData       pointer to stream data structure

        Outputs:
            value           decoded code word is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      failure, no valid code word found

------------------------------------------------------------------------------*/

u32 h264bsdDecodeExpGolombSigned(strmData_t *pStrmData, i32 *value)
{

/* Variables */

    u32 status, codeNum = 0;

/* Code */

    ASSERT(pStrmData);
    ASSERT(value);

    status = h264bsdDecodeExpGolombUnsigned(pStrmData, &codeNum);

    if (codeNum == BIG_CODE_NUM)
    {
        /* BIG_CODE_NUM and HANTRO_OK status means codeNum 2^32-1 which would
         * result in signed integer valued 2^31 (i.e. out of 32-bit signed
         * integer range) */
        if (status == HANTRO_OK)
            return(HANTRO_NOK);
        /* BIG_CODE_NUM and HANTRO_NOK status means codeNum 2^32 which results
         * in signed integer valued -2^31 */
        else
        {
            *value = (i32)(2147483648U);
            return (HANTRO_OK);
        }
    }
    else if (status == HANTRO_OK)
    {
        /* (-1)^(codeNum+1) results in positive sign if codeNum is odd,
         * negative when it is even. (codeNum+1)/2 is obtained as
         * (codeNum+1)>>1 when value is positive and as (-codeNum)>>1 for
         * negative value */
        /*lint -e702 */
        *value = (codeNum & 0x1) ? (i32)((codeNum + 1) >> 1) :
                                  -(i32)((codeNum + 1) >> 1);
        /*lint +e702 */
        return(HANTRO_OK);
    }

    return(HANTRO_NOK);

}

/*------------------------------------------------------------------------------

   5.3  Function: h264bsdDecodeExpGolombMapped

        Functional description:
            Decode mapped Exp-Golomb code. Code num is determined by
            h264bsdDecodeExpGolombUnsigned and then mapped to codedBlockPattern
            either for intra or inter macroblock. The mapping is implemented by
            look-up tables defined in the beginning of the file.

        Inputs:
            pStrmData       pointer to stream data structure
            isIntra         flag to indicate if intra or inter mapping is to
                            be used

        Outputs:
            value           decoded code word is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      failure, no valid code word found

------------------------------------------------------------------------------*/

u32 h264bsdDecodeExpGolombMapped(strmData_t *pStrmData, u32 *value,
    u32 isIntra)
{

/* Variables */

    u32 status, codeNum;

/* Code */

    ASSERT(pStrmData);
    ASSERT(value);

    status = h264bsdDecodeExpGolombUnsigned(pStrmData, &codeNum);

    if (status != HANTRO_OK)
        return (HANTRO_NOK);
    else
    {
        /* range of valid codeNums [0,47] */
        if (codeNum > 47)
            return (HANTRO_NOK);
        if (isIntra)
            *value = codedBlockPatternIntra4x4[codeNum];
        else
            *value = codedBlockPatternInter[codeNum];
        return(HANTRO_OK);
    }

}

/*------------------------------------------------------------------------------

   5.4  Function: h264bsdDecodeExpGolombTruncated

        Functional description:
            Decode truncated Exp-Golomb code. greaterThanOne flag indicates
            the range of the symbol to be decoded as follows:
                FALSE   ->  [0,1]
                TRUE    ->  [0,2^32-1]

            If flag is false the decoding is performed by reading one bit
            from the stream with h264bsdGetBits and mapping this to decoded
            symbol as
                symbol = bit ? 0 : 1

            Otherwise, i.e. when flag is TRUE, code num is determined by
            h264bsdDecodeExpGolombUnsigned and this is used as the decoded
            symbol.

        Inputs:
            pStrmData       pointer to stream data structure
            greaterThanOne  flag to indicate if range is wider than [0,1]

        Outputs:
            value           decoded code word is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      failure, no valid code word found

------------------------------------------------------------------------------*/

u32 h264bsdDecodeExpGolombTruncated(
  strmData_t *pStrmData,
  u32 *value,
  u32 greaterThanOne)
{

/* Variables */

/* Code */

    ASSERT(pStrmData);
    ASSERT(value);

    if (greaterThanOne)
    {
        return(h264bsdDecodeExpGolombUnsigned(pStrmData, value));
    }
    else
    {
        *value = h264bsdGetBits(pStrmData,1);
        if (*value == END_OF_STREAM)
            return (HANTRO_NOK);
        *value ^= 0x1;
    }

    return (HANTRO_OK);

}

