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

 Filename: ps_allocate_decoder.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Reuses AAC+ HQ right channel, which is not used when PS is enabled

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
Copyright (c) ISO/IEC 2003.

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS

#ifdef HQ_SBR

#ifdef PARAMETRICSTEREO

#include    "s_sbr_channel.h"
#include    "aac_mem_funcs.h"
#include    "ps_hybrid_filter_bank_allocation.h"
#include    "s_ps_dec.h"
#include    "ps_all_pass_filter_coeff.h"
#include    "ps_allocate_decoder.h"
/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

#define R_SHIFT     30
#define Q30_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
const Int32  aRevLinkDelaySer[] = {3,  4,  5};

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

Int32 ps_allocate_decoder(SBRDECODER_DATA *self,
                          UInt32  noSubSamples)
{
    Int32 i, j;
    Int32 status;

    Int32 *ptr1;
    Int32 *ptr2;
    Int32 *ptr3;
    Int32 *ptr4;
    Int32 *ptr5;
    Int32 *ptr6;
    Int32 *ptr7;

    const Int32 pHybridResolution[] = { HYBRID_8_CPLX,
                                        HYBRID_2_REAL,
                                        HYBRID_2_REAL
                                      };

    STRUCT_PS_DEC *h_ps_dec = self->hParametricStereoDec;

    /* initialisation */
    h_ps_dec->noSubSamples = noSubSamples;

    h_ps_dec->invNoSubSamples = Q30_fmt(1.0f) / noSubSamples;

    /*
     *  Reuse AAC+ HQ right channel, which is not used when PS is enabled
     */
    ptr1 = (Int32 *)(self->SbrChannel[1].frameData.codecQmfBufferReal[0]);   /*  reuse un-used right channel QMF_FILTER Synthesis buffer */

    ptr2 = (&ptr1[658]);  /*  reuse un-used right channel QMF_FILTER Synthesis buffer */
    /* 1162 - 658 = 504
     *            = NO_QMF_ALLPASS_CHANNELS*2 (Re&Im)*( 3 + 4 + 5) + ( 3 + 4 + 5)*2 (Re&Im)
     */

    ptr3 = (&ptr1[1162]);  /*  reuse un-used right channel QMF_FILTER Synthesis buffer */
    /* 1426 - 1162 = 264
     *            = SUBQMF_GROUPS*2 (Re&Im)*( 3 + 4 + 5) + ( 3 + 4 + 5)*2 (Re&Im)
     */

    ptr4 = (&ptr1[1426]);  /*  high freq generation buffers */

    ptr5 = (&ptr1[1490]);  /*  high freq generation buffers */

    ptr6 = (&ptr1[1618]);  /*  high freq generation buffers */

    ptr7 = (&ptr1[1810]);  /*  high freq generation buffers */

    /*  whole allocation requires 1871 words, sbrQmfBufferImag has 1920 words */


    h_ps_dec->aPeakDecayFast =  ptr1;
    ptr1 += NO_BINS;

    h_ps_dec->aPrevNrg =  ptr1;
    ptr1 += NO_BINS;

    h_ps_dec->aPrevPeakDiff = ptr1;
    ptr1 += NO_BINS;



    status = ps_hybrid_filter_bank_allocation(&h_ps_dec->hHybrid,
             NO_QMF_CHANNELS_IN_HYBRID,
             pHybridResolution,
             &ptr1);
    h_ps_dec->mHybridRealLeft = ptr1;
    ptr1 += SUBQMF_GROUPS;

    h_ps_dec->mHybridImagLeft = ptr1;
    ptr1 += SUBQMF_GROUPS;

    h_ps_dec->mHybridRealRight = ptr1;
    ptr1 += SUBQMF_GROUPS;

    h_ps_dec->mHybridImagRight = ptr1;
    ptr1 += SUBQMF_GROUPS;


    h_ps_dec->delayBufIndex   = 0;



    for (i = 0 ; i < NO_DELAY_CHANNELS ; i++)   /* 41  */
    {
        if (i < SHORT_DELAY_START)              /* 12  */
        {
            h_ps_dec->aNoSampleDelay[i] = LONG_DELAY;
        }
        else
        {
            h_ps_dec->aNoSampleDelay[i] = SHORT_DELAY;
        }
    }


    h_ps_dec->aaRealDelayBufferQmf = (Int32 **)ptr6;
    ptr6 += NO_QMF_ICC_CHANNELS * sizeof(Int32 *) / sizeof(Int32);

    h_ps_dec->aaImagDelayBufferQmf = (Int32 **)ptr7;
    ptr7 += NO_QMF_ICC_CHANNELS * sizeof(Int32 *) / sizeof(Int32);

    h_ps_dec->aaRealDelayBufferSubQmf = (Int32 **)ptr1;
    ptr1 += SUBQMF_GROUPS * sizeof(Int32 *) / sizeof(Int32);

    h_ps_dec->aaImagDelayBufferSubQmf = (Int32 **)ptr1;
    ptr1 += SUBQMF_GROUPS * sizeof(Int32 *) / sizeof(Int32);

    for (i = 0; i < NO_QMF_ICC_CHANNELS; i++)   /* 61 */
    {
        int delay;

        if (i < NO_QMF_ALLPASS_CHANNELS)    /* 20 */
        {
            delay = 2;
            h_ps_dec->aaRealDelayBufferQmf[i] = (Int32 *)ptr4;
            ptr4 += delay;

            h_ps_dec->aaImagDelayBufferQmf[i] = (Int32 *)ptr5;
            ptr5 += delay;
        }
        else
        {

            if (i >= (NO_QMF_ALLPASS_CHANNELS + SHORT_DELAY_START))
            {
                delay = SHORT_DELAY;
            }
            else
            {
                delay = LONG_DELAY;
            }

            h_ps_dec->aaRealDelayBufferQmf[i] = (Int32 *)ptr1;
            ptr1 += delay;

            h_ps_dec->aaImagDelayBufferQmf[i] = (Int32 *)ptr1;
            ptr1 += delay;
        }
    }

    for (i = 0; i < SUBQMF_GROUPS; i++)
    {
        h_ps_dec->aaRealDelayBufferSubQmf[i] = (Int32 *)ptr1;
        ptr1 += DELAY_ALLPASS;

        h_ps_dec->aaImagDelayBufferSubQmf[i] = (Int32 *)ptr1;
        ptr1 += DELAY_ALLPASS;

    }

    for (i = 0 ; i < NO_SERIAL_ALLPASS_LINKS ; i++) /*  NO_SERIAL_ALLPASS_LINKS == 3 */
    {

        h_ps_dec->aDelayRBufIndexSer[i] = 0;

        h_ps_dec->aaaRealDelayRBufferSerQmf[i] = (Int32 **)ptr2;
        ptr2 += aRevLinkDelaySer[i];

        h_ps_dec->aaaImagDelayRBufferSerQmf[i] = (Int32 **)ptr2;
        ptr2 += aRevLinkDelaySer[i];

        h_ps_dec->aaaRealDelayRBufferSerSubQmf[i] = (Int32 **)ptr3;
        ptr3 += aRevLinkDelaySer[i];

        h_ps_dec->aaaImagDelayRBufferSerSubQmf[i] = (Int32 **)ptr3;
        ptr3 += aRevLinkDelaySer[i];

        for (j = 0; j < aRevLinkDelaySer[i]; j++)
        {
            h_ps_dec->aaaRealDelayRBufferSerQmf[i][j] = ptr2;
            ptr2 += NO_QMF_ALLPASS_CHANNELS;    /* NO_QMF_ALLPASS_CHANNELS == 20 */

            h_ps_dec->aaaImagDelayRBufferSerQmf[i][j] = ptr2;
            ptr2 += NO_QMF_ALLPASS_CHANNELS;

            h_ps_dec->aaaRealDelayRBufferSerSubQmf[i][j] = ptr3;
            ptr3 += SUBQMF_GROUPS;

            h_ps_dec->aaaImagDelayRBufferSerSubQmf[i][j] = ptr3;
            ptr3 += SUBQMF_GROUPS;

        }
    }


    for (i = 0; i < NO_IID_GROUPS; i++)         /*  NO_IID_GROUPS == 22   */
    {
        h_ps_dec->h11Prev[i] = Q30_fmt(1.0f);
        h_ps_dec->h12Prev[i] = Q30_fmt(1.0f);
    }



    return status;
} /*END CreatePsDec*/
#endif

#endif


#endif

