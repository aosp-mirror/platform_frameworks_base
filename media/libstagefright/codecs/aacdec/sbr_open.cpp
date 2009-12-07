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

 Filename: sbr_open.c

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


#include    "sbr_open.h"
#include    "s_sbr_header_data.h"
#include    "init_sbr_dec.h"
#include    "e_sbr_error.h"
#include    "aac_mem_funcs.h"


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
const SBR_HEADER_DATA defaultHeader =
{
    HEADER_NOT_INITIALIZED,   /* status */
    MASTER_RESET,             /* masterStatus */
    0,                        /* crcEnable */
    UP_BY_2,                  /* sampleRateMode */
    SBR_AMP_RES_3_0,          /* ampResolution */
    5,                        /* startFreq */
    0,                        /* stopFreq */
    0,                        /* xover_band */
    SBR_FREQ_SCALE_DEFAULT,   /* freqScale */
    SBR_ALTER_SCALE_DEFAULT,  /* alterScale */
    SBR_NOISE_BANDS_DEFAULT,  /* noise_bands */
    0,                        /* noNoiseBands */
    SBR_LIMITER_BANDS_DEFAULT,
    SBR_LIMITER_GAINS_DEFAULT,
    SBR_INTERPOL_FREQ_DEFAULT,
    SBR_SMOOTHING_LENGTH_DEFAULT
};

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

void sbr_open(Int32 sampleRate,
              SBR_DEC *sbrDec,
              SBRDECODER_DATA * self,
              bool bDownSampledSbr)

{
    Int16 i ;

    SBR_CHANNEL *SbrChannel;


    SbrChannel = self->SbrChannel;

    for (i = 0; i < MAX_NUM_CHANNELS; i++)
    {
        pv_memset((void *)&(SbrChannel[i]),
                  0,
                  sizeof(SBR_CHANNEL));

        /* init a default header such that we can at least do upsampling later */

        pv_memcpy(&(SbrChannel[i].frameData.sbr_header),
                  &defaultHeader,
                  sizeof(SBR_HEADER_DATA));

        /* should be handled by sample rate mode bit */
        if (sampleRate > 24000 || bDownSampledSbr)
        {
            SbrChannel[i].frameData.sbr_header.sampleRateMode = SINGLE_RATE;
        }


        SbrChannel[i].outFrameSize =
            init_sbr_dec(sampleRate,
                         self->SbrChannel[0].frameData.sbr_header.sampleRateMode,
                         sbrDec,
                         &(SbrChannel[i].frameData));

        SbrChannel[i].syncState     = UPSAMPLING;

        SbrChannel[i].frameData.sUp = 1;        /* reset mode */
    }
}

#endif

