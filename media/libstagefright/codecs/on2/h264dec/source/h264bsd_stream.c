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
          h264bsdGetBits
          h264bsdShowBits32
          h264bsdFlushBits
          h264bsdIsByteAligned

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_util.h"
#include "h264bsd_stream.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------

    Function: h264bsdGetBits

        Functional description:
            Read and remove bits from the stream buffer.

        Input:
            pStrmData   pointer to stream data structure
            numBits     number of bits to read

        Output:
            none

        Returns:
            bits read from stream
            END_OF_STREAM if not enough bits left

------------------------------------------------------------------------------*/

u32 h264bsdGetBits(strmData_t *pStrmData, u32 numBits)
{

    u32 out;

    ASSERT(pStrmData);
    ASSERT(numBits < 32);

    out = h264bsdShowBits32(pStrmData) >> (32 - numBits);

    if (h264bsdFlushBits(pStrmData, numBits) == HANTRO_OK)
    {
        return(out);
    }
    else
    {
        return(END_OF_STREAM);
    }

}

/*------------------------------------------------------------------------------

    Function: h264bsdShowBits32

        Functional description:
            Read 32 bits from the stream buffer. Buffer is left as it is, i.e.
            no bits are removed. First bit read from the stream is the MSB of
            the return value. If there is not enough bits in the buffer ->
            bits beyong the end of the stream are set to '0' in the return
            value.

        Input:
            pStrmData   pointer to stream data structure

        Output:
            none

        Returns:
            bits read from stream

------------------------------------------------------------------------------*/

u32 h264bsdShowBits32(strmData_t *pStrmData)
{

    i32 bits, shift;
    u32 out;
    u8 *pStrm;

    ASSERT(pStrmData);
    ASSERT(pStrmData->pStrmCurrPos);
    ASSERT(pStrmData->bitPosInWord < 8);
    ASSERT(pStrmData->bitPosInWord ==
           (pStrmData->strmBuffReadBits & 0x7));

    pStrm = pStrmData->pStrmCurrPos;

    /* number of bits left in the buffer */
    bits = (i32)pStrmData->strmBuffSize*8 - (i32)pStrmData->strmBuffReadBits;

    /* at least 32-bits in the buffer */
    if (bits >= 32)
    {
        u32 bitPosInWord = pStrmData->bitPosInWord;
        out = ((u32)pStrm[0] << 24) | ((u32)pStrm[1] << 16) |
              ((u32)pStrm[2] <<  8) | ((u32)pStrm[3]);

        if (bitPosInWord)
        {
            u32 byte = (u32)pStrm[4];
            u32 tmp = (8-bitPosInWord);
            out <<= bitPosInWord;
            out |= byte>>tmp;
        }
        return (out);
    }
    /* at least one bit in the buffer */
    else if (bits > 0)
    {
        shift = (i32)(24 + pStrmData->bitPosInWord);
        out = (u32)(*pStrm++) << shift;
        bits -= (i32)(8 - pStrmData->bitPosInWord);
        while (bits > 0)
        {
            shift -= 8;
            out |= (u32)(*pStrm++) << shift;
            bits -= 8;
        }
        return (out);
    }
    else
        return (0);

}

/*------------------------------------------------------------------------------

    Function: h264bsdFlushBits

        Functional description:
            Remove bits from the stream buffer

        Input:
            pStrmData       pointer to stream data structure
            numBits         number of bits to remove

        Output:
            none

        Returns:
            HANTRO_OK       success
            END_OF_STREAM   not enough bits left

------------------------------------------------------------------------------*/
#ifndef H264DEC_NEON
u32 h264bsdFlushBits(strmData_t *pStrmData, u32 numBits)
{

    ASSERT(pStrmData);
    ASSERT(pStrmData->pStrmBuffStart);
    ASSERT(pStrmData->pStrmCurrPos);
    ASSERT(pStrmData->bitPosInWord < 8);
    ASSERT(pStrmData->bitPosInWord == (pStrmData->strmBuffReadBits & 0x7));

    pStrmData->strmBuffReadBits += numBits;
    pStrmData->bitPosInWord = pStrmData->strmBuffReadBits & 0x7;
    if ( (pStrmData->strmBuffReadBits ) <= (8*pStrmData->strmBuffSize) )
    {
        pStrmData->pStrmCurrPos = pStrmData->pStrmBuffStart +
            (pStrmData->strmBuffReadBits >> 3);
        return(HANTRO_OK);
    }
    else
        return(END_OF_STREAM);

}
#endif
/*------------------------------------------------------------------------------

    Function: h264bsdIsByteAligned

        Functional description:
            Check if current stream position is byte aligned.

        Inputs:
            pStrmData   pointer to stream data structure

        Outputs:
            none

        Returns:
            TRUE        stream is byte aligned
            FALSE       stream is not byte aligned

------------------------------------------------------------------------------*/

u32 h264bsdIsByteAligned(strmData_t *pStrmData)
{

/* Variables */

/* Code */

    if (!pStrmData->bitPosInWord)
        return(HANTRO_TRUE);
    else
        return(HANTRO_FALSE);

}

