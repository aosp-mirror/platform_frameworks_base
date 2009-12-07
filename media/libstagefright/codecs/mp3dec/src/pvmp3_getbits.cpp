/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/*
------------------------------------------------------------------------------

   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pvmp3_getbits.cpp


     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    tmp3Bits *inputStream,     structure holding the input stream parameters
    int32     neededBits       number of bits to read from the bit stream

 Outputs:

    word parsed from teh bitstream, with size neededBits-bits,

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION


------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES
 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension


------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pvmp3_getbits.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

uint32 getNbits(tmp3Bits *ptBitStream,
                int32 neededBits) /* number of bits to read from the bitstream (up to 25) */
{

    uint32    offset;
    uint32    bitIndex;
    uint8     Elem;         /* Needs to be same type as pInput->pBuffer */
    uint8     Elem1;
    uint8     Elem2;
    uint8     Elem3;
    uint32   returnValue = 0;

    if (!neededBits)
    {
        return (returnValue);
    }

    offset = (ptBitStream->usedBits) >> INBUF_ARRAY_INDEX_SHIFT;

    Elem  = *(ptBitStream->pBuffer + module(offset  , BUFSIZE));
    Elem1 = *(ptBitStream->pBuffer + module(offset + 1, BUFSIZE));
    Elem2 = *(ptBitStream->pBuffer + module(offset + 2, BUFSIZE));
    Elem3 = *(ptBitStream->pBuffer + module(offset + 3, BUFSIZE));


    returnValue = (((uint32)(Elem)) << 24) |
                  (((uint32)(Elem1)) << 16) |
                  (((uint32)(Elem2)) << 8) |
                  ((uint32)(Elem3));

    /* Remove extra high bits by shifting up */
    bitIndex = module(ptBitStream->usedBits, INBUF_BIT_WIDTH);

    /* This line is faster than to mask off the high bits. */
    returnValue <<= bitIndex;

    /* Move the field down. */
    returnValue >>= (32 - neededBits);

    ptBitStream->usedBits += neededBits;

    return (returnValue);
}

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

uint16 getUpTo9bits(tmp3Bits *ptBitStream,
                    int32 neededBits) /* number of bits to read from the bit stream 2 to 9 */
{

    uint32    offset;
    uint32    bitIndex;
    uint8    Elem;         /* Needs to be same type as pInput->pBuffer */
    uint8    Elem1;
    uint16   returnValue;

    offset = (ptBitStream->usedBits) >> INBUF_ARRAY_INDEX_SHIFT;

    Elem  = *(ptBitStream->pBuffer + module(offset  , BUFSIZE));
    Elem1 = *(ptBitStream->pBuffer + module(offset + 1, BUFSIZE));


    returnValue = (((uint16)(Elem)) << 8) |
                  ((uint16)(Elem1));

    /* Remove extra high bits by shifting up */
    bitIndex = module(ptBitStream->usedBits, INBUF_BIT_WIDTH);

    ptBitStream->usedBits += neededBits;
    /* This line is faster than to mask off the high bits. */
    returnValue = (returnValue << (bitIndex));

    /* Move the field down. */

    return (uint16)(returnValue >> (16 - neededBits));

}

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

uint32 getUpTo17bits(tmp3Bits *ptBitStream,
                     int32 neededBits) /* number of bits to read from the bit stream 2 to 8 */
{

    uint32    offset;
    uint32    bitIndex;
    uint8     Elem;         /* Needs to be same type as pInput->pBuffer */
    uint8     Elem1;
    uint8     Elem2;
    uint32   returnValue;

    offset = (ptBitStream->usedBits) >> INBUF_ARRAY_INDEX_SHIFT;

    Elem  = *(ptBitStream->pBuffer + module(offset  , BUFSIZE));
    Elem1 = *(ptBitStream->pBuffer + module(offset + 1, BUFSIZE));
    Elem2 = *(ptBitStream->pBuffer + module(offset + 2, BUFSIZE));


    returnValue = (((uint32)(Elem)) << 16) |
                  (((uint32)(Elem1)) << 8) |
                  ((uint32)(Elem2));

    /* Remove extra high bits by shifting up */
    bitIndex = module(ptBitStream->usedBits, INBUF_BIT_WIDTH);

    ptBitStream->usedBits += neededBits;
    /* This line is faster than to mask off the high bits. */
    returnValue = 0xFFFFFF & (returnValue << (bitIndex));

    /* Move the field down. */

    return (uint32)(returnValue >> (24 - neededBits));

}

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

uint8 get1bit(tmp3Bits *ptBitStream)  /* number of bits to read from the bit stream */
{

    uint32    offset;
    uint32    bitIndex;
    uint8   returnValue;

    offset = (ptBitStream->usedBits) >> INBUF_ARRAY_INDEX_SHIFT;

    returnValue  = *(ptBitStream->pBuffer + module(offset  , BUFSIZE));

    /* Remove extra high bits by shifting up */
    bitIndex = module(ptBitStream->usedBits, INBUF_BIT_WIDTH);
    ptBitStream->usedBits++;

    /* This line is faster than to mask off the high bits. */
    returnValue = (returnValue << (bitIndex));

    return (uint8)(returnValue >> 7);

}




