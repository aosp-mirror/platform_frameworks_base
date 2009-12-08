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

   Filename: pvmp3_crc.cpp

   Functions:
        getbits_crc
        calculate_crc

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

getbits_crc

Input
    tbits *inputStream,     bit stream structure
    int32 neededBits,       number of bits to read from the bit stream
    uint32 *crc,            memory location holding calculated crc value
    uint32 crc_enabled      flag to enable/disable crc checking

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

calculate_crc

Input
    uint32 data,            data vector
    uint32 length,          number of element upon the crc will be calculated
    uint32 *crc,            memory location holding calculated crc value

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
#include "pvmp3_crc.h"

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

uint32 getbits_crc(tmp3Bits *inputStream,  /* bit stream structure */
                   int32 neededBits, /* number of bits to read from the bit stream */
                   uint32 *crc,
                   uint32 crc_enabled)
{
    uint32 bits = getNbits(inputStream, neededBits);

    if (crc_enabled)
    {
        calculate_crc(bits, neededBits, crc);
    }
    return(bits);
}

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void calculate_crc(uint32 data,
                   uint32 length,
                   uint32 *crc)
{
    uint32  carry;
    uint32  masking = 1 << length;

    while ((masking >>= 1))
    {
        carry = *crc & 0x8000;
        *crc <<= 1;
        if (!carry ^ !(data & masking))
        {
            *crc ^= CRC16_POLYNOMIAL;
        }
    }
    *crc &= 0xffff;
}



