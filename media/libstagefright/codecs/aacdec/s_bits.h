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

 Pathname: s_BITS.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Remove unused field.

 Description: Change buffer type from UInt to UInt32, makes API much easier
              to understand and describe, and getbits is faster on TI C55X
              if the buffer is 32 bits instead of 16.

 Description: Change buffer type from UInt32 to UChar.

 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file defines the structure, BITS

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef  S_BITS_H
#define  S_BITS_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; SIMPLE TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; ENUMERATED TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; STRUCTURES TYPEDEF'S
----------------------------------------------------------------------------*/
/*
 * Name: BITS
 * Description: Holds information for processing the input data buffer
 *    as a "stream". The data is in packed format.
 * Fields:
 *    pBuffer - pointer to the beginning of the buffer. If the data type of
 *        this changes, make sure to update the constants in ibstream.h
 *    usedBits - number of bits read thus far from the buffer. Bit 0 is
 *        the LSB of pBuffer[0].
 *    availableBits - number of bits available in the buffer.
 *    byteAlignOffset - used with ADTS in case sync word is not aligned
                        on a boundary.
 */
typedef struct
{
    UChar    *pBuffer;
    UInt      usedBits;      /* Keep this unsigned so can go to 65536 */
    UInt      availableBits; /* Ditto */
    UInt      inputBufferCurrentLength; /* Ditto */
    Int      byteAlignOffset; /* Used in ADTS.  See find_adts_syncword() */
} BITS;

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif

