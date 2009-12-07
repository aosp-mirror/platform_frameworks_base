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

 Filename: sbr_extract_extended_data.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    SBR_FRAME_DATA *hFrameData,     Destination for extracted data of left channel
    SBR_FRAME_DATA *hFrameDataRight Destination for extracted data of right channel
    BIT_BUFFER hBitBuf              pointer to bit buffer


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

  Reads extension data from the bitstream

  The bitstream format allows up to 4 kinds of extended data element.
  Extended data may contain several elements, each identified by a 2-bit-ID.

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


#include    "sbr_extract_extended_data.h"
#include    "buf_getbits.h"

#ifdef PARAMETRICSTEREO
#include    "ps_read_data.h"
#endif

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

void sbr_extract_extended_data(BIT_BUFFER * hBitBuf
#ifdef PARAMETRICSTEREO         /* Parametric Stereo Decoder */
                               , HANDLE_PS_DEC hParametricStereoDec
#endif
                              )
{
    Int32 extended_data;
    Int32 i;
    Int32 nBitsLeft;
    Int32 extension_id;

    extended_data = buf_get_1bit(hBitBuf);    /*  SI_SBR_EXTENDED_DATA_BITS  */

    if (extended_data)
    {
        Int32 cnt;

        cnt = buf_getbits(hBitBuf, SI_SBR_EXTENSION_SIZE_BITS);
        if (cnt == (1 << SI_SBR_EXTENSION_SIZE_BITS) - 1)
        {
            cnt += buf_getbits(hBitBuf, SI_SBR_EXTENSION_ESC_COUNT_BITS);
        }

        nBitsLeft = (cnt << 3);
        while (nBitsLeft > 7)
        {
            extension_id = buf_getbits(hBitBuf, SI_SBR_EXTENSION_ID_BITS);
            nBitsLeft -= SI_SBR_EXTENSION_ID_BITS;

            switch (extension_id)
            {
#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO

                    /*
                     *  Parametric Coding supports the Transient, Sinusoidal, Noise, and
                     *  Parametric Stereo tools (MPEG4).
                     *  3GPP use aac+ hq along with ps for enhanced aac+
                     *  The PS tool uses complex-value QMF data, therefore can not be used
                     *  with low power version of aac+
                     */
                case EXTENSION_ID_PS_CODING:

                    if (hParametricStereoDec != NULL)
                    {
                        if (!hParametricStereoDec->psDetected)
                        {
                            /* parametric stereo detected */
                            hParametricStereoDec->psDetected = 1;
                        }

                        nBitsLeft -= ps_read_data(hParametricStereoDec,
                                                  hBitBuf,
                                                  nBitsLeft);

                    }

                    break;
#endif
#endif
                case 0:

                default:
                    /*   An unknown extension id causes the remaining extension data
                     *   to be skipped
                     */
                    cnt = nBitsLeft >> 3; /* number of remaining bytes */

                    for (i = 0; i < cnt; i++)
                    {
                        buf_getbits(hBitBuf, 8);
                    }

                    nBitsLeft -= (cnt << 3);
            }
        }
        /* read fill bits for byte alignment */
        buf_getbits(hBitBuf, nBitsLeft);
    }
}


#endif



