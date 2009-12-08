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

 Filename: sbr_get_envelope.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Arguments:     h_frame_data - handle to struct SBR_FRAME_DATA
                hBitBuf      - handle to struct BIT_BUF
                channel      - channel number

 Return:        void


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

          Reads envelope data from bitstream

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


#include    "sbr_get_envelope.h"
#include    "s_huffman.h"
#include    "e_coupling_mode.h"
#include    "sbr_code_book_envlevel.h"
#include    "buf_getbits.h"
#include    "sbr_decode_huff_cw.h"


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

void sbr_get_envelope(SBR_FRAME_DATA * h_frame_data,
                      BIT_BUFFER * hBitBuf)
{
    Int32   i;
    Int32   j;
    Int32   tmp;
    Int32   no_band[MAX_ENVELOPES];
    Int32   delta = 0;
    Int32   offset = 0;
    Int32   ampRes;
    Int32   envDataTableCompFactor;
    Int32   start_bits;
    Int32   start_bits_balance;
    SbrHuffman    hcb_t;
    SbrHuffman    hcb_f;
    COUPLING_MODE coupling = h_frame_data->coupling;

    h_frame_data->nScaleFactors = 0;

    if ((h_frame_data->frameClass   == FIXFIX) &&
            (h_frame_data->frameInfo[0] == 1))
    {
        h_frame_data->ampRes = SBR_AMP_RES_1_5;
    }
    else
    {
        h_frame_data->ampRes = h_frame_data->sbr_header.ampResolution;
    }

    ampRes = h_frame_data->ampRes;

    /*
     *    Set number of bits for first value depending on amplitude resolution
     */
    if (ampRes == SBR_AMP_RES_3_0)
    {
        start_bits         = SI_SBR_START_ENV_BITS_AMP_RES_3_0;
        start_bits_balance = SI_SBR_START_ENV_BITS_BALANCE_AMP_RES_3_0;
    }
    else
    {
        start_bits         = SI_SBR_START_ENV_BITS_AMP_RES_1_5;
        start_bits_balance = SI_SBR_START_ENV_BITS_BALANCE_AMP_RES_1_5;
    }

    /*
     *    Calculate number of values for each envelope and alltogether
     */
    for (i = 0; i < h_frame_data->frameInfo[0]; i++)
    {
        no_band[i] =
            h_frame_data->nSfb[h_frame_data->frameInfo[h_frame_data->frameInfo[0] + 2 + i]];
        h_frame_data->nScaleFactors += no_band[i];
    }


    /*
     *    Select huffman codebook depending on coupling mode and amplitude resolution
     */
    if (coupling == COUPLING_BAL)
    {
        envDataTableCompFactor = 1;
        if (ampRes == SBR_AMP_RES_1_5)
        {
            hcb_t = bookSbrEnvBalance10T;
            hcb_f = bookSbrEnvBalance10F;
        }
        else
        {
            hcb_t = bookSbrEnvBalance11T;
            hcb_f = bookSbrEnvBalance11F;
        }
    }
    else
    {
        envDataTableCompFactor = 0;
        if (ampRes == SBR_AMP_RES_1_5)
        {
            hcb_t = bookSbrEnvLevel10T;
            hcb_f = bookSbrEnvLevel10F;
        }
        else
        {
            hcb_t = bookSbrEnvLevel11T;
            hcb_f = bookSbrEnvLevel11F;
        }
    }

    /*
     *    Now read raw envelope data
     */
    for (j = 0; j < h_frame_data->frameInfo[0]; j++)
    {
        if (h_frame_data->domain_vec1[j] == FREQ)
        {
            if (coupling == COUPLING_BAL)
            {
                tmp = buf_getbits(hBitBuf, start_bits_balance);
                h_frame_data->iEnvelope_man[offset] = tmp << envDataTableCompFactor;
            }
            else
            {
                tmp = buf_getbits(hBitBuf, start_bits);
                h_frame_data->iEnvelope_man[offset] = tmp;
            }
        }

        for (i = (1 - h_frame_data->domain_vec1[j]); i < no_band[j]; i++)
        {

            if (h_frame_data->domain_vec1[j] == FREQ)
            {
                delta = sbr_decode_huff_cw(hcb_f, hBitBuf);
            }
            else
            {
                delta = sbr_decode_huff_cw(hcb_t, hBitBuf);
            }

            h_frame_data->iEnvelope_man[offset + i] = delta << envDataTableCompFactor;
        }
        offset += no_band[j];
    }

}

#endif

