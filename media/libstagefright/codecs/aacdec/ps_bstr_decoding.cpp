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

 Filename: ps_bstr_decoding.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        Decodes parametric stereo

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

#ifdef PARAMETRICSTEREO

#include "pv_audio_type_defs.h"
#include "aac_mem_funcs.h"
#include "ps_bstr_decoding.h"
#include "ps_decode_bs_utils.h"

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

const Int32 aNoIidBins[3] = {NO_LOW_RES_IID_BINS, NO_IID_BINS, NO_HI_RES_BINS};
const Int32 aNoIccBins[3] = {NO_LOW_RES_ICC_BINS, NO_ICC_BINS, NO_HI_RES_BINS};
const Int32 aFixNoEnvDecode[4] = {0, 1, 2, 4};

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

void ps_bstr_decoding(STRUCT_PS_DEC *ps_dec)
{
    UInt32 env;
    Int32 noIidSteps;

    if (!ps_dec->bPsDataAvail)
    {
        ps_dec->noEnv = 0;
    }

    noIidSteps = ps_dec->bFineIidQ ? NO_IID_STEPS_FINE : NO_IID_STEPS;

    for (env = 0; env < ps_dec->noEnv; env++)
    {
        Int32 *aPrevIidIndex;
        Int32 *aPrevIccIndex;
        if (env == 0)
        {
            aPrevIidIndex = ps_dec->aIidPrevFrameIndex;
            aPrevIccIndex = ps_dec->aIccPrevFrameIndex;
        }
        else
        {
            aPrevIidIndex = ps_dec->aaIidIndex[env-1];
            aPrevIccIndex = ps_dec->aaIccIndex[env-1];
        }

        /*
         * Differential Decoding of IID parameters over time/frequency
         */
        differential_Decoding(ps_dec->bEnableIid,
                              ps_dec->aaIidIndex[env],
                              aPrevIidIndex,
                              ps_dec->abIidDtFlag[env],
                              aNoIidBins[ps_dec->freqResIid],
                              (ps_dec->freqResIid) ? 1 : 2,
                              -noIidSteps,
                              noIidSteps);

        /*
         * Differential Decoding of ICC parameters over time/frequency
         */
        differential_Decoding(ps_dec->bEnableIcc,
                              ps_dec->aaIccIndex[env],
                              aPrevIccIndex,
                              ps_dec->abIccDtFlag[env],
                              aNoIccBins[ps_dec->freqResIcc],
                              (ps_dec->freqResIcc) ? 1 : 2,
                              0,
                              NO_ICC_STEPS - 1);


    }   /* for (env=0; env<ps_dec->noEnv; env++) */

    if (ps_dec->noEnv == 0)
    {
        ps_dec->noEnv = 1;

        if (ps_dec->bEnableIid)
        {   /*  NO_HI_RES_BINS == 34 */
            pv_memmove(ps_dec->aaIidIndex[ps_dec->noEnv-1],
                       ps_dec->aIidPrevFrameIndex,
                       NO_HI_RES_BINS*sizeof(*ps_dec->aIidPrevFrameIndex));

        }
        else
        {
            pv_memset((void *)ps_dec->aaIidIndex[ps_dec->noEnv-1],
                      0,
                      NO_HI_RES_BINS*sizeof(**ps_dec->aaIidIndex));
        }
        if (ps_dec->bEnableIcc)
        {
            pv_memmove(ps_dec->aaIccIndex[ps_dec->noEnv-1],
                       ps_dec->aIccPrevFrameIndex,
                       NO_HI_RES_BINS*sizeof(*ps_dec->aIccPrevFrameIndex));
        }
        else
        {
            pv_memset((void *)ps_dec->aaIccIndex[ps_dec->noEnv-1],
                      0,
                      NO_HI_RES_BINS*sizeof(**ps_dec->aaIccIndex));
        }
    }

    pv_memmove(ps_dec->aIidPrevFrameIndex,
               ps_dec->aaIidIndex[ps_dec->noEnv-1],
               NO_HI_RES_BINS*sizeof(*ps_dec->aIidPrevFrameIndex));

    pv_memmove(ps_dec->aIccPrevFrameIndex,
               ps_dec->aaIccIndex[ps_dec->noEnv-1],
               NO_HI_RES_BINS*sizeof(*ps_dec->aIccPrevFrameIndex));

    ps_dec->bPsDataAvail = 0;

    if (ps_dec->bFrameClass == 0)
    {
        Int32 shift;

        shift = ps_dec->noEnv >> 1;

        ps_dec->aEnvStartStop[0] = 0;

        for (env = 1; env < ps_dec->noEnv; env++)
        {
            ps_dec->aEnvStartStop[env] =
                (env * ps_dec->noSubSamples) >> shift;
        }

        ps_dec->aEnvStartStop[ps_dec->noEnv] = ps_dec->noSubSamples;
    }
    else
    {   /* if (ps_dec->bFrameClass != 0) */
        ps_dec->aEnvStartStop[0] = 0;

        if (ps_dec->aEnvStartStop[ps_dec->noEnv] < ps_dec->noSubSamples)
        {
            ps_dec->noEnv++;
            ps_dec->aEnvStartStop[ps_dec->noEnv] = ps_dec->noSubSamples;

            pv_memmove(ps_dec->aaIidIndex[ps_dec->noEnv],
                       ps_dec->aaIidIndex[ps_dec->noEnv-1],
                       NO_HI_RES_BINS*sizeof(**ps_dec->aaIidIndex));

            pv_memmove(ps_dec->aaIccIndex[ps_dec->noEnv],
                       ps_dec->aaIccIndex[ps_dec->noEnv-1],
                       NO_HI_RES_BINS*sizeof(**ps_dec->aaIccIndex));
        }

        for (env = 1; env < ps_dec->noEnv; env++)
        {
            UInt32 thr;
            thr = ps_dec->noSubSamples - ps_dec->noEnv + env;

            if (ps_dec->aEnvStartStop[env] > thr)
            {
                ps_dec->aEnvStartStop[env] = thr;
            }
            else
            {
                thr = ps_dec->aEnvStartStop[env-1] + 1;

                if (ps_dec->aEnvStartStop[env] < thr)
                {
                    ps_dec->aEnvStartStop[env] = thr;
                }
            }
        }
    }   /* if (ps_dec->bFrameClass == 0) ... else */

    for (env = 0; env < ps_dec->noEnv; env++)
    {
        if (ps_dec->freqResIid == 2)
        {
            map34IndexTo20(ps_dec->aaIidIndex[env]);
        }
        if (ps_dec->freqResIcc == 2)
        {
            map34IndexTo20(ps_dec->aaIccIndex[env]);
        }
    }


}

#endif


#endif

