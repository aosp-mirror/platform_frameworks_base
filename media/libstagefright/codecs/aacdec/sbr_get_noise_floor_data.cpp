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

 Filename: sbr_get_noise_floor_data.c


------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Arguments:     h_frame_data - handle to struct SBR_FRAME_DATA
                hBitBuf      - handle to struct BIT_BUF

 Return:        void


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Reads noise-floor-level data from bitstream

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


#include    "sbr_get_noise_floor_data.h"
#include    "e_coupling_mode.h"
#include    "buf_getbits.h"
#include    "sbr_code_book_envlevel.h"
#include    "s_huffman.h"
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

void sbr_get_noise_floor_data(SBR_FRAME_DATA * h_frame_data,
                              BIT_BUFFER * hBitBuf)
{
    Int32 i;
    Int32 j;
    Int32 k;
    Int32 tmp;
    Int32 delta;
    Int32 noNoiseBands = h_frame_data->nNfb;
    Int32 envDataTableCompFactor;

    COUPLING_MODE coupling = h_frame_data->coupling;

    SbrHuffman hcb_noiseF;
    SbrHuffman hcb_noise;


    if (coupling == COUPLING_BAL)
    {
        hcb_noise  = bookSbrNoiseBalance11T;
        hcb_noiseF = bookSbrEnvBalance11F;  /* "bookSbrNoiseBalance11F" */
        envDataTableCompFactor = 1;
    }
    else
    {
        hcb_noise  = bookSbrNoiseLevel11T;
        hcb_noiseF = bookSbrEnvLevel11F;  /* "bookSbrNoiseLevel11F" */
        envDataTableCompFactor = 0;
    }

    /*
     *  Calculate number of values alltogether
     */
    h_frame_data->nNoiseFactors = h_frame_data->frameInfo[((h_frame_data->frameInfo[0]) << 1) + 3] * noNoiseBands;


    for (i = 0; i < h_frame_data->nNoiseFloorEnvelopes; i++)
    {
        k = i * noNoiseBands;
        if (h_frame_data->domain_vec2[i] == FREQ)
        {
            if (coupling == COUPLING_BAL)
            {
                tmp = buf_getbits(hBitBuf, SI_SBR_START_NOISE_BITS_BALANCE_AMP_RES_3_0) << 1;  /*  max. 62  */
                h_frame_data->sbrNoiseFloorLevel_man[k] = tmp;
                h_frame_data->sbrNoiseFloorLevel_exp[k] =   0;
            }
            else
            {
                tmp = buf_getbits(hBitBuf, SI_SBR_START_NOISE_BITS_AMP_RES_3_0);  /*  max. 31  */
                h_frame_data->sbrNoiseFloorLevel_man[k] = tmp;
                h_frame_data->sbrNoiseFloorLevel_exp[k] =   0;
            }

            for (j = 1; j < noNoiseBands; j++)
            {
                delta = sbr_decode_huff_cw(hcb_noiseF, hBitBuf); /*
                                                                  *  -31 < delta < 31
                                                                  *  -24 < delta < 24   COUPLING_BAL (incl. <<1)
                                                                  */
                h_frame_data->sbrNoiseFloorLevel_man[k+j] = delta << envDataTableCompFactor;
                h_frame_data->sbrNoiseFloorLevel_exp[k+j] =   0;
            }
        }
        else
        {
            for (j = 0; j < noNoiseBands; j++)
            {
                delta = sbr_decode_huff_cw(hcb_noise, hBitBuf);  /*
                                                                  *  -31 < delta < 31
                                                                  *  -24 < delta < 24   COUPLING_BAL (incl. <<1)
                                                                  */
                h_frame_data->sbrNoiseFloorLevel_man[k+j] = delta << envDataTableCompFactor;
                h_frame_data->sbrNoiseFloorLevel_exp[k+j] =   0;
            }
        }
    }
}

#endif


