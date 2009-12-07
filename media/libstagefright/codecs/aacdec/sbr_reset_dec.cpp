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

 Filename: sbr_reset_dec.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    resets sbr decoder structure
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

#include    "sbr_dec.h"

#include    "pv_log2.h"
#include    "fxp_mul32.h"


#include    "sbr_reset_dec.h"
#include    "sbr_find_start_andstop_band.h"
#include    "sbr_update_freq_scale.h"
#include    "sbr_downsample_lo_res.h"

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

SBR_ERROR sbr_reset_dec(SBR_FRAME_DATA * hFrameData,
                        SBR_DEC * sbrDec,
                        Int32 upsampleFac)
{

    SBR_ERROR err = SBRDEC_OK;
    Int lsbM;
    Int lsb;
    Int usb;
    Int32 i;
    Int32 tmp_q1;

    SBR_HEADER_DATA *headerData  = &(hFrameData->sbr_header);
    Int32           samplingFreq = sbrDec->outSampleRate;

    hFrameData->reset_flag = 1;

    /*Calculate master frequency function */
    err = sbr_find_start_andstop_band(samplingFreq,
                                      headerData->startFreq,
                                      headerData->stopFreq,
                                      &lsbM,
                                      &usb);

    if (err != SBRDEC_OK)
    {
        return err;
    }

    /* Calculate new v_k_master if needed */
    if (headerData->masterStatus == MASTER_RESET)
    {
        sbr_update_freq_scale(sbrDec->V_k_master,
                              &(sbrDec->Num_Master),
                              lsbM,
                              usb,
                              headerData->freqScale,
                              headerData->alterScale,
                              0);

    }

    /*Derive Hiresolution from master frequency function*/

    sbrDec->NSfb[HI] = sbrDec->Num_Master - headerData->xover_band;

    for (i = headerData->xover_band; i <= sbrDec->Num_Master; i++)
    {
        sbrDec->FreqBandTable[HI][i-headerData->xover_band] = (Int)sbrDec->V_k_master[i];
    }


    if ((sbrDec->NSfb[HI] & 0x01) == 0) /* if even number of hires bands */
    {

        sbrDec->NSfb[LO] = sbrDec->NSfb[HI] >> 1;
        /* Use every second lo-res=hi-res[0,2,4...] */
        for (i = 0; i <= sbrDec->NSfb[LO]; i++)
        {
            sbrDec->FreqBandTable[LO][i] = sbrDec->FreqBandTable[HI][(i<<1)];
        }
    }
    else
    {            /* odd number of hi-res which means xover is odd */

        sbrDec->NSfb[LO] = (sbrDec->NSfb[HI] + 1) >> 1;
        /* Use lo-res=hi-res[0,1,3,5 ...] */
        sbrDec->FreqBandTable[LO][0] = sbrDec->FreqBandTable[HI][0];
        for (i = 1; i <= sbrDec->NSfb[LO]; i++)
        {
            sbrDec->FreqBandTable[LO][i] = sbrDec->FreqBandTable[HI][(i<<1)-1];
        }

    }

    lsb = sbrDec->FreqBandTable[LOW_RES][0];
    usb = sbrDec->FreqBandTable[LOW_RES][sbrDec->NSfb[LOW_RES]];

    sbrDec->lowSubband  = lsb;
    sbrDec->highSubband = usb;
    sbrDec->noSubbands  = usb - lsb;

    if ((lsb > 32) || (sbrDec->noSubbands <= 0))
    {
        return SBRDEC_ILLEGAL_SCFACTORS;   /* invalid bands */
    }

    /* Calculate number of noise bands */
    if (headerData->noise_bands == 0)
    {
        sbrDec->NoNoiseBands = 1;
    }
    else /* Calculate number of noise bands 1,2 or 3 bands/octave */
    {

        if (! lsb)
        {
            return SBRDEC_ILLEGAL_SCFACTORS;   /* avoid div by 0 */
        }

        tmp_q1 = pv_log2((usb << 20) / lsb);

        tmp_q1 = fxp_mul32_Q15(headerData->noise_bands, tmp_q1);

        sbrDec->NoNoiseBands = (tmp_q1 + 16) >> 5;

        if (sbrDec->NoNoiseBands == 0)
        {
            sbrDec->NoNoiseBands = 1;
        }
    }

    headerData->noNoiseBands = sbrDec->NoNoiseBands;

    /* Get noise bands */
    sbr_downsample_lo_res(sbrDec->FreqBandTableNoise,
                          sbrDec->NoNoiseBands,
                          sbrDec->FreqBandTable[LO],
                          sbrDec->NSfb[LO]);

    sbrDec->sbStopCodec = sbrDec->lowSubband;

    if (sbrDec->sbStopCodec > (upsampleFac << 5))
    {
        sbrDec->sbStopCodec = (upsampleFac << 5);
    }

    hFrameData->nSfb[LO] = sbrDec->NSfb[LO];
    hFrameData->nSfb[HI] = sbrDec->NSfb[HI];
    hFrameData->nNfb     = hFrameData->sbr_header.noNoiseBands;
    hFrameData->offset   = ((hFrameData->nSfb[LO]) << 1) - hFrameData->nSfb[HI];

    return (SBRDEC_OK);
}

#endif

