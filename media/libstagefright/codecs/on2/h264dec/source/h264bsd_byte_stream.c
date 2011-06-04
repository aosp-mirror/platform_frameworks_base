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
          ExtractNalUnit

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_byte_stream.h"
#include "h264bsd_util.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

#define BYTE_STREAM_ERROR  0xFFFFFFFF

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------

    Function name: ExtractNalUnit

        Functional description:
            Extracts one NAL unit from the byte stream buffer. Removes
            emulation prevention bytes if present. The original stream buffer
            is used directly and is therefore modified if emulation prevention
            bytes are present in the stream.

            Stream buffer is assumed to contain either exactly one NAL unit
            and nothing else, or one or more NAL units embedded in byte
            stream format described in the Annex B of the standard. Function
            detects which one is used based on the first bytes in the buffer.

        Inputs:
            pByteStream     pointer to byte stream buffer
            len             length of the stream buffer (in bytes)

        Outputs:
            pStrmData       stream information is stored here
            readBytes       number of bytes "consumed" from the stream buffer

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      error in byte stream

------------------------------------------------------------------------------*/

u32 h264bsdExtractNalUnit(u8 *pByteStream, u32 len, strmData_t *pStrmData,
    u32 *readBytes)
{

/* Variables */

    u32 i, tmp;
    u32 byteCount,initByteCount;
    u32 zeroCount;
    u8  byte;
    u32 hasEmulation = HANTRO_FALSE;
    u32 invalidStream = HANTRO_FALSE;
    u8 *readPtr, *writePtr;

/* Code */

    ASSERT(pByteStream);
    ASSERT(len);
    ASSERT(len < BYTE_STREAM_ERROR);
    ASSERT(pStrmData);

    /* byte stream format if starts with 0x000001 or 0x000000 */
    if (len > 3 && pByteStream[0] == 0x00 && pByteStream[1] == 0x00 &&
        (pByteStream[2]&0xFE) == 0x00)
    {
        /* search for NAL unit start point, i.e. point after first start code
         * prefix in the stream */
        zeroCount = byteCount = 2;
        readPtr = pByteStream + 2;
        /*lint -e(716) while(1) used consciously */
        while (1)
        {
            byte = *readPtr++;
            byteCount++;

            if (byteCount == len)
            {
                /* no start code prefix found -> error */
                *readBytes = len;
                return(HANTRO_NOK);
            }

            if (!byte)
                zeroCount++;
            else if ((byte == 0x01) && (zeroCount >= 2))
                break;
            else
                zeroCount = 0;
        }

        initByteCount = byteCount;

        /* determine size of the NAL unit. Search for next start code prefix
         * or end of stream and ignore possible trailing zero bytes */
        zeroCount = 0;
        /*lint -e(716) while(1) used consciously */
        while (1)
        {
            byte = *readPtr++;
            byteCount++;
            if (!byte)
                zeroCount++;

            if ( (byte == 0x03) && (zeroCount == 2) )
            {
                hasEmulation = HANTRO_TRUE;
            }

            if ( (byte == 0x01) && (zeroCount >= 2 ) )
            {
                pStrmData->strmBuffSize =
                    byteCount - initByteCount - zeroCount - 1;
                zeroCount -= MIN(zeroCount, 3);
                break;
            }
            else if (byte)
            {
                if (zeroCount >= 3)
                    invalidStream = HANTRO_TRUE;
                zeroCount = 0;
            }

            if (byteCount == len)
            {
                pStrmData->strmBuffSize = byteCount - initByteCount - zeroCount;
                break;
            }

        }
    }
    /* separate NAL units as input -> just set stream params */
    else
    {
        initByteCount = 0;
        zeroCount = 0;
        pStrmData->strmBuffSize = len;
        hasEmulation = HANTRO_TRUE;
    }

    pStrmData->pStrmBuffStart    = pByteStream + initByteCount;
    pStrmData->pStrmCurrPos      = pStrmData->pStrmBuffStart;
    pStrmData->bitPosInWord      = 0;
    pStrmData->strmBuffReadBits  = 0;

    /* return number of bytes "consumed" */
    *readBytes = pStrmData->strmBuffSize + initByteCount + zeroCount;

    if (invalidStream)
    {
        return(HANTRO_NOK);
    }

    /* remove emulation prevention bytes before rbsp processing */
    if (hasEmulation)
    {
        tmp = pStrmData->strmBuffSize;
        readPtr = writePtr = pStrmData->pStrmBuffStart;
        zeroCount = 0;
        for (i = tmp; i--;)
        {
            if ((zeroCount == 2) && (*readPtr == 0x03))
            {
                /* emulation prevention byte shall be followed by one of the
                 * following bytes: 0x00, 0x01, 0x02, 0x03. This implies that
                 * emulation prevention 0x03 byte shall not be the last byte
                 * of the stream. */
                if ( (i == 0) || (*(readPtr+1) > 0x03) )
                    return(HANTRO_NOK);

                /* do not write emulation prevention byte */
                readPtr++;
                zeroCount = 0;
            }
            else
            {
                /* NAL unit shall not contain byte sequences 0x000000,
                 * 0x000001 or 0x000002 */
                if ( (zeroCount == 2) && (*readPtr <= 0x02) )
                    return(HANTRO_NOK);

                if (*readPtr == 0)
                    zeroCount++;
                else
                    zeroCount = 0;

                *writePtr++ = *readPtr++;
            }
        }

        /* (readPtr - writePtr) indicates number of "removed" emulation
         * prevention bytes -> subtract from stream buffer size */
        pStrmData->strmBuffSize -= (u32)(readPtr - writePtr);
    }

    return(HANTRO_OK);

}

