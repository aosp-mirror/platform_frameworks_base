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

 Filename: get_sbr_bitstream.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    INPUT

    SBRDECODER self,
    SBRBITSTREAM * stream,
    float *timeData,
    int numChannels

    OUTPUT

    errorCode, noError if successful

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        sbr decoder processing, set up SBR decoder phase 2 in case of
        different cotrol data

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

#include "get_sbr_bitstream.h"
#include "pv_audio_type_defs.h"
#include    "sbr_crc_check.h"

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

void get_sbr_bitstream(SBRBITSTREAM *sbrBitStream, BITS *pInputStream)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/

    Int32 count;
    Int32 esc_count;
    Int32 Extention_Type;
    Int32 i;

    count = get9_n_lessbits(LEN_F_CNT, pInputStream);
    if (count == 15)
    {
        esc_count = get9_n_lessbits(LEN_F_ESC, pInputStream);
        count = esc_count + 14;
    }



    Extention_Type = get9_n_lessbits(LEN_F_CNT, pInputStream);


    if (((Extention_Type == SBR_EXTENSION) || (Extention_Type == SBR_EXTENSION_CRC))
            && (count < MAXSBRBYTES) && (count) && (sbrBitStream->NrElements < MAXNRELEMENTS))
    {

        sbrBitStream->sbrElement[sbrBitStream->NrElements].ExtensionType = Extention_Type;
        sbrBitStream->sbrElement[sbrBitStream->NrElements].Payload       = count;
        sbrBitStream->sbrElement[sbrBitStream->NrElements].Data[0]       = (UChar) get9_n_lessbits(LEN_F_CNT, pInputStream);
        for (i = 1 ; i < count ; i++)
        {
            sbrBitStream->sbrElement[sbrBitStream->NrElements].Data[i] = (UChar) get9_n_lessbits(8, pInputStream);
        }

        sbrBitStream->NrElements += 1;

    }
    else
    {
        pInputStream->usedBits += (count - 1) * LEN_BYTE;
        pInputStream->usedBits += 4;        /* compenste for LEN_F_CNT (=4) bits read for Extention_Type */

    }
}

#endif
