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

 Filename: init_sbr_dec.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        initializes sbr decoder structure
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


#include    "init_sbr_dec.h"
#include    "aac_mem_funcs.h"
#include    "extractframeinfo.h"

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

Int32 init_sbr_dec(Int32 codecSampleRate,
                   Int   upsampleFac,
                   SBR_DEC *sbrDec,
                   SBR_FRAME_DATA *hFrameData)
{
    Int32 outFrameSize;
    Int32 coreCodecFrameSize = 1024;
#ifdef HQ_SBR
    Int32 i;
#endif


    sbrDec->sbStopCodec    =  upsampleFac << 5;
    sbrDec->prevLowSubband =  upsampleFac << 5;


    /* set sbr sampling frequency */
    sbrDec->outSampleRate = 2 * codecSampleRate;
    outFrameSize = upsampleFac * coreCodecFrameSize;

    hFrameData->nSfb[LO] = 0;    /* number of scale factor bands for high resp.low frequency resolution */
    hFrameData->nSfb[HI] = 0;
    hFrameData->offset   = 0;

    hFrameData->nNfb = hFrameData->sbr_header.noNoiseBands;
    hFrameData->prevEnvIsShort = -1;

    /* Initializes pointers */
#ifdef HQ_SBR
    for (i = 0; i < 5; i++)
    {
        hFrameData->fBuf_man[i]  = hFrameData->fBuffer_man[i];
        hFrameData->fBufN_man[i] = hFrameData->fBufferN_man[i];
        hFrameData->fBuf_exp[i]  = hFrameData->fBuffer_exp[i];
        hFrameData->fBufN_exp[i] = hFrameData->fBufferN_exp[i];
    }
#endif


    pv_memset((void *)hFrameData->sbr_invf_mode_prev,
              0,
              MAX_NUM_NOISE_VALUES*sizeof(INVF_MODE));

    /* Direct assignments */

    sbrDec->noCols = 32;

    sbrDec->bufWriteOffs = 6 + 2;
    sbrDec->bufReadOffs  = 2;
    sbrDec->qmfBufLen = sbrDec->noCols + sbrDec->bufWriteOffs;

    sbrDec->lowBandAddSamples = 288;

    sbrDec->startIndexCodecQmf = 0;

    sbrDec->lowSubband =  32;


    return outFrameSize;
}

#endif

