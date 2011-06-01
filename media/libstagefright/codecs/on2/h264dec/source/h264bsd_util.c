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
          h264bsdCountLeadingZeros
          h264bsdRbspTrailingBits
          h264bsdMoreRbspData
          h264bsdNextMbAddress
          h264bsdSetCurrImageMbPointers

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_util.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/* look-up table for expected values of stuffing bits */
static const u32 stuffingTable[8] = {0x1,0x2,0x4,0x8,0x10,0x20,0x40,0x80};

/* look-up table for chroma quantization parameter as a function of luma QP */
const u32 h264bsdQpC[52] = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,
    20,21,22,23,24,25,26,27,28,29,29,30,31,32,32,33,34,34,35,35,36,36,37,37,37,
    38,38,38,39,39,39,39};

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------

   5.1  Function: h264bsdCountLeadingZeros

        Functional description:
            Count leading zeros in a code word. Code word is assumed to be
            right-aligned, last bit of the code word in the lsb of the value.

        Inputs:
            value   code word
            length  number of bits in the code word

        Outputs:
            none

        Returns:
            number of leading zeros in the code word

------------------------------------------------------------------------------*/
#ifndef H264DEC_NEON
u32 h264bsdCountLeadingZeros(u32 value, u32 length)
{

/* Variables */

    u32 zeros = 0;
    u32 mask = 1 << (length - 1);

/* Code */

    ASSERT(length <= 32);

    while (mask && !(value & mask))
    {
        zeros++;
        mask >>= 1;
    }
    return(zeros);

}
#endif
/*------------------------------------------------------------------------------

   5.2  Function: h264bsdRbspTrailingBits

        Functional description:
            Check Raw Byte Stream Payload (RBSP) trailing bits, i.e. stuffing.
            Rest of the current byte (whole byte if allready byte aligned)
            in the stream buffer shall contain a '1' bit followed by zero or
            more '0' bits.

        Inputs:
            pStrmData   pointer to stream data structure

        Outputs:
            none

        Returns:
            HANTRO_OK      RBSP trailing bits found
            HANTRO_NOK     otherwise

------------------------------------------------------------------------------*/

u32 h264bsdRbspTrailingBits(strmData_t *pStrmData)
{

/* Variables */

    u32 stuffing;
    u32 stuffingLength;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pStrmData->bitPosInWord < 8);

    stuffingLength = 8 - pStrmData->bitPosInWord;

    stuffing = h264bsdGetBits(pStrmData, stuffingLength);
    if (stuffing == END_OF_STREAM)
        return(HANTRO_NOK);

    if (stuffing != stuffingTable[stuffingLength - 1])
        return(HANTRO_NOK);
    else
        return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

   5.3  Function: h264bsdMoreRbspData

        Functional description:
            Check if there is more data in the current RBSP. The standard
            defines this function so that there is more data if
                -more than 8 bits left or
                -last bits are not RBSP trailing bits

        Inputs:
            pStrmData   pointer to stream data structure

        Outputs:
            none

        Returns:
            HANTRO_TRUE    there is more data
            HANTRO_FALSE   no more data

------------------------------------------------------------------------------*/

u32 h264bsdMoreRbspData(strmData_t *pStrmData)
{

/* Variables */

    u32 bits;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pStrmData->strmBuffReadBits <= 8 * pStrmData->strmBuffSize);

    bits = pStrmData->strmBuffSize * 8 - pStrmData->strmBuffReadBits;

    if (bits == 0)
        return(HANTRO_FALSE);

    if ( (bits > 8) ||
         ((h264bsdShowBits32(pStrmData)>>(32-bits)) != (1 << (bits-1))) )
        return(HANTRO_TRUE);
    else
        return(HANTRO_FALSE);

}

/*------------------------------------------------------------------------------

   5.4  Function: h264bsdNextMbAddress

        Functional description:
            Get address of the next macroblock in the current slice group.

        Inputs:
            pSliceGroupMap      slice group for each macroblock
            picSizeInMbs        size of the picture
            currMbAddr          where to start

        Outputs:
            none

        Returns:
            address of the next macroblock
            0   if none of the following macroblocks belong to same slice
                group as currMbAddr

------------------------------------------------------------------------------*/

u32 h264bsdNextMbAddress(u32 *pSliceGroupMap, u32 picSizeInMbs, u32 currMbAddr)
{

/* Variables */

    u32 i, sliceGroup, tmp;

/* Code */

    ASSERT(pSliceGroupMap);
    ASSERT(picSizeInMbs);
    ASSERT(currMbAddr < picSizeInMbs);

    sliceGroup = pSliceGroupMap[currMbAddr];

    i = currMbAddr + 1;
    tmp = pSliceGroupMap[i];
    while ((i < picSizeInMbs) && (tmp != sliceGroup))
    {
        i++;
        tmp = pSliceGroupMap[i];
    }

    if (i == picSizeInMbs)
        i = 0;

    return(i);

}


/*------------------------------------------------------------------------------

   5.5  Function: h264bsdSetCurrImageMbPointers

        Functional description:
            Set luma and chroma pointers in image_t for current MB

        Inputs:
            image       Current image
            mbNum       number of current MB

        Outputs:
            none

        Returns:
            none
------------------------------------------------------------------------------*/
void h264bsdSetCurrImageMbPointers(image_t *image, u32 mbNum)
{
    u32 width, height;
    u32 picSize;
    u32 row, col;
    u32 tmp;

    width = image->width;
    height = image->height;
    row = mbNum / width;
    col = mbNum % width;

    tmp = row * width;
    picSize = width * height;

    image->luma = (u8*)(image->data + col * 16 + tmp * 256);
    image->cb = (u8*)(image->data + picSize * 256 + tmp * 64 + col * 8);
    image->cr = (u8*)(image->cb + picSize * 64);
}


