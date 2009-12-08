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

 Filename: sbr_crc_check.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION


------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

SC 29 Software Copyright Licencing Disclaimer:

This software module was originally developed by
  Coding Technologies

and edited by
  -

in the course of development of the ISO/IEC 13818-7 and ISO/IEC 14496-3
standards for reference purposes and its performance may not have been
optimized. This software module is an implementation of one or more tools as
specified by the ISO/IEC 13818-7 and ISO/IEC 14496-3 standards.
ISO/IEC gives users free license to this software module or modifications
thereof for use in products claiming conformance to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International
Standards. ISO/IEC gives users the same free license to this software module or
modifications thereof for research purposes and further ISO/IEC standardisation.
Those intending to use this software module in products are advised that its
use may infringe existing patents. ISO/IEC have no liability for use of this
software module or modifications thereof. Copyright is not released for
products that do not conform to audiovisual and image-coding related ITU
Recommendations and/or ISO/IEC International Standards.
The original developer retains full right to modify and use the code for its
own purpose, assign or donate the code to a third party and to inhibit third
parties from using the code for products that do not conform to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International Standards.
This copyright notice must be included in all copies or derivative works.
Copyright (c) ISO/IEC 2002.

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS


#include "sbr_crc_check.h"
#include "s_crc_buffer.h"
#include "buf_getbits.h"
#include "sbr_constants.h"
#include "check_crc.h"




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

const unsigned short MAXCRCSTEP = 16;

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

Int32 sbr_crc_check(BIT_BUFFER * hBitBuf, UInt32 NrBits)
{
    Int32 crcResult = 1;
    BIT_BUFFER BitBufferCRC;
    UInt32 NrCrcBits;

    UInt32 crcCheckSum;

    Int32 i;
    CRC_BUFFER CrcBuf;
    UInt32 bValue;
    Int32 CrcStep;
    Int32 CrcNrBitsRest;

    crcCheckSum = buf_getbits(hBitBuf, SI_SBR_CRC_BITS);


    /*
     *    Copy Bit buffer State
     */

    BitBufferCRC.char_ptr       = hBitBuf->char_ptr;
    BitBufferCRC.buffer_word    = hBitBuf->buffer_word;
    BitBufferCRC.buffered_bits  = hBitBuf->buffered_bits;
    BitBufferCRC.nrBitsRead     = hBitBuf->nrBitsRead;
    BitBufferCRC.bufferLen      = hBitBuf->bufferLen;


    NrCrcBits = min(NrBits, BitBufferCRC.bufferLen - BitBufferCRC.nrBitsRead);


    CrcStep = NrCrcBits / MAXCRCSTEP;
    CrcNrBitsRest = (NrCrcBits - CrcStep * MAXCRCSTEP);

    CrcBuf.crcState = CRCSTART;
    CrcBuf.crcMask  = CRCMASK;
    CrcBuf.crcPoly  = CRCPOLY;

    for (i = 0; i < CrcStep; i++)
    {
        bValue = buf_getbits(&BitBufferCRC, MAXCRCSTEP);
        check_crc(&CrcBuf, bValue, MAXCRCSTEP);
    }

    bValue = buf_getbits(&BitBufferCRC, CrcNrBitsRest);
    check_crc(&CrcBuf, bValue, CrcNrBitsRest);

    if ((UInt32)(CrcBuf.crcState & CRCRANGE) != crcCheckSum)
    {
        crcResult = 0;
    }

    return (crcResult);
}

#endif


