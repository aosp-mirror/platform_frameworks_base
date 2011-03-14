/* ------------------------------------------------------------------
 * Copyright (C) 1998-2010 PacketVideo
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

 Filename: sbr_dec.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    sbr decoder core function

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


#include    "s_sbr_frame_data.h"
#include    "calc_sbr_synfilterbank.h"
#include    "calc_sbr_anafilterbank.h"
#include    "calc_sbr_envelope.h"
#include    "sbr_generate_high_freq.h"
#include    "sbr_dec.h"
#include    "decode_noise_floorlevels.h"
#include    "aac_mem_funcs.h"
#include    "fxp_mul32.h"

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
#include "pv_audio_type_defs.h"

#ifdef PARAMETRICSTEREO

#include   "ps_applied.h"
#include   "ps_init_stereo_mixing.h"

#endif

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void sbr_dec(Int16 *inPcmData,
             Int16 *ftimeOutPtr,
             SBR_FRAME_DATA * hFrameData,
             int32_t applyProcessing,
             SBR_DEC *sbrDec,
#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO
             Int16 * ftimeOutPtrPS,
             HANDLE_PS_DEC hParametricStereoDec,
#endif
#endif
             tDec_Int_File  *pVars)
{
    int32_t   i;
    int32_t   j;
    int32_t   m;

    int32_t  *frameInfo = hFrameData->frameInfo;
    Int  num_qmf_bands;

#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO

    int32_t env;

    int32_t *qmf_PS_generated_Real;
    int32_t *qmf_PS_generated_Imag;

    int32_t *Sr_x;
    int32_t *Si_x;


#endif
#endif

    int32_t(*scratch_mem)[64];
    Int16 *circular_buffer_s;

    int32_t   k;
    int32_t *Sr;
    int32_t *Si;
    int32_t *ptr_tmp1;
    int32_t *ptr_tmp2;
    scratch_mem = pVars->scratch.scratch_mem;


    if (applyProcessing)
    {
        num_qmf_bands = sbrDec->lowSubband;
    }
    else
    {
        num_qmf_bands = 32;     /* becomes a resampler by 2  */
    }

    /* -------------------------------------------------- */
    /*
     *    Re-Load Buffers
     */
    pv_memmove(&hFrameData->sbrQmfBufferReal[0],
               &hFrameData->HistsbrQmfBufferReal[0],
               6*SBR_NUM_BANDS*sizeof(*hFrameData->sbrQmfBufferReal));
#ifdef HQ_SBR


    if (sbrDec->LC_aacP_DecoderFlag == OFF)
    {
        pv_memmove(&hFrameData->sbrQmfBufferImag[0],
                   &hFrameData->HistsbrQmfBufferImag[0],
                   6*SBR_NUM_BANDS*sizeof(*hFrameData->sbrQmfBufferImag));
    }
#endif
    /* -------------------------------------------------- */


    /*
     *    low band codec signal subband filtering
     */

    for (i = 0; i < 32; i++)
    {

        if (sbrDec->LC_aacP_DecoderFlag == ON)
        {

            calc_sbr_anafilterbank_LC(hFrameData->codecQmfBufferReal[sbrDec->bufWriteOffs + i],
                                      &inPcmData[319] + (i << 5),
                                      scratch_mem,
                                      num_qmf_bands);

        }
#ifdef HQ_SBR
        else
        {

            calc_sbr_anafilterbank(hFrameData->codecQmfBufferReal[sbrDec->bufWriteOffs + i],
                                   hFrameData->codecQmfBufferImag[sbrDec->bufWriteOffs + i],
                                   &inPcmData[319] + (i << 5),
                                   scratch_mem,
                                   num_qmf_bands);
        }
#endif

    }

    if (pVars->ltp_buffer_state)
    {
        pv_memcpy(&inPcmData[-1024-288], &inPcmData[1024], 288*sizeof(*inPcmData));
    }
    else
    {
        pv_memcpy(&inPcmData[1024 + 288], &inPcmData[1024], 288*sizeof(*inPcmData));
    }


    if (applyProcessing)
    {

        /*
         *  Inverse filtering of lowband + HF generation
         */

        if (sbrDec->LC_aacP_DecoderFlag == ON)
        {

            sbr_generate_high_freq((int32_t(*)[32])(hFrameData->codecQmfBufferReal + sbrDec->bufReadOffs),
                                   NULL,
                                   (int32_t *)(hFrameData->sbrQmfBufferReal),
                                   NULL,
                                   hFrameData->sbr_invf_mode,
                                   hFrameData->sbr_invf_mode_prev,
                                   &(sbrDec->FreqBandTableNoise[1]),
                                   sbrDec->NoNoiseBands,
                                   sbrDec->lowSubband,
                                   sbrDec->V_k_master,
                                   sbrDec->Num_Master,
                                   sbrDec->outSampleRate,
                                   frameInfo,
                                   hFrameData->degreeAlias,
                                   scratch_mem,
                                   hFrameData->BwVector,/* */
                                   hFrameData->BwVectorOld,
                                   &(sbrDec->Patch),
                                   sbrDec->LC_aacP_DecoderFlag,
                                   &(sbrDec->highSubband));


            /*
             *      Adjust envelope of current frame.
             */

            calc_sbr_envelope(hFrameData,
                              (int32_t *)(hFrameData->sbrQmfBufferReal),
                              NULL,
                              sbrDec->FreqBandTable,
                              sbrDec->NSfb,
                              sbrDec->FreqBandTableNoise,
                              sbrDec->NoNoiseBands,
                              hFrameData->reset_flag,
                              hFrameData->degreeAlias,
                              &(hFrameData->harm_index),
                              &(hFrameData->phase_index),
                              hFrameData->hFp,
                              &(hFrameData->sUp),
                              sbrDec->limSbc,
                              sbrDec->gateMode,
#ifdef HQ_SBR
                              NULL,
                              NULL,
                              NULL,
                              NULL,
#endif
                              scratch_mem,
                              sbrDec->Patch,
                              sbrDec->sqrt_cache,
                              sbrDec->LC_aacP_DecoderFlag);
        }
#ifdef HQ_SBR
        else
        {

            sbr_generate_high_freq((int32_t(*)[32])(hFrameData->codecQmfBufferReal + sbrDec->bufReadOffs),
                                   (int32_t(*)[32])(hFrameData->codecQmfBufferImag + sbrDec->bufReadOffs),
                                   (int32_t *)(hFrameData->sbrQmfBufferReal),
                                   (int32_t *)(hFrameData->sbrQmfBufferImag),
                                   hFrameData->sbr_invf_mode,
                                   hFrameData->sbr_invf_mode_prev,
                                   &(sbrDec->FreqBandTableNoise[1]),
                                   sbrDec->NoNoiseBands,
                                   sbrDec->lowSubband,
                                   sbrDec->V_k_master,
                                   sbrDec->Num_Master,
                                   sbrDec->outSampleRate,
                                   frameInfo,
                                   NULL,
                                   scratch_mem,
                                   hFrameData->BwVector,
                                   hFrameData->BwVectorOld,
                                   &(sbrDec->Patch),
                                   sbrDec->LC_aacP_DecoderFlag,
                                   &(sbrDec->highSubband));

            /*
             *      Adjust envelope of current frame.
             */

            calc_sbr_envelope(hFrameData,
                              (int32_t *)(hFrameData->sbrQmfBufferReal),
                              (int32_t *)(hFrameData->sbrQmfBufferImag),
                              sbrDec->FreqBandTable,
                              sbrDec->NSfb,
                              sbrDec->FreqBandTableNoise,
                              sbrDec->NoNoiseBands,
                              hFrameData->reset_flag,
                              NULL,
                              &(hFrameData->harm_index),
                              &(hFrameData->phase_index),
                              hFrameData->hFp,
                              &(hFrameData->sUp),
                              sbrDec->limSbc,
                              sbrDec->gateMode,
                              hFrameData->fBuf_man,
                              hFrameData->fBuf_exp,
                              hFrameData->fBufN_man,
                              hFrameData->fBufN_exp,
                              scratch_mem,
                              sbrDec->Patch,
                              sbrDec->sqrt_cache,
                              sbrDec->LC_aacP_DecoderFlag);

        }
#endif


    }
    else   /*  else for applyProcessing */
    {
        /* no sbr, set high band buffers to zero */

        for (i = 0; i < SBR_NUM_COLUMNS; i++)
        {
            pv_memset((void *)&hFrameData->sbrQmfBufferReal[i*SBR_NUM_BANDS],
                      0,
                      SBR_NUM_BANDS*sizeof(*hFrameData->sbrQmfBufferReal));

#ifdef HQ_SBR
            pv_memset((void *)&hFrameData->sbrQmfBufferImag[i*SBR_NUM_BANDS],
                      0,
                      SBR_NUM_BANDS*sizeof(*hFrameData->sbrQmfBufferImag));

#endif
        }

    }


    /*
     *  Synthesis subband filtering.
     */

#ifdef HQ_SBR

#ifdef PARAMETRICSTEREO


    /*
     * psPresentFlag set implies hParametricStereoDec !=NULL, second condition is
     * is just here to prevent CodeSonar warnings.
     */
    if ((pVars->mc_info.psPresentFlag) && (applyProcessing) &&
            (hParametricStereoDec != NULL))
    {

        /*
         *  qmfBufferReal uses the rigth aac channel ( perChan[1] is not used)
         *  followed by the buffer fxpCoef[2][2048]  which makes a total of
         *  2349 + 2048*2 = 6445
         *  These  2 matrices (qmfBufferReal & qmfBufferImag) are
         *  [2][38][64] == 4864 int32_t
         */


        tDec_Int_Chan *tmpx = &pVars->perChan[1];
        /*
         *  dereferencing type-punned pointer avoid
         *  breaking strict-aliasing rules
         */
        int32_t *tmp = (int32_t *)tmpx;
        hParametricStereoDec->qmfBufferReal = (int32_t(*)[64]) tmp;

        tmp = (int32_t *) & hParametricStereoDec->qmfBufferReal[38][0];
        hParametricStereoDec->qmfBufferImag = (int32_t(*)[64]) tmp;

        for (i = 0; i < 32; i++)
        {
            Int   xoverBand;

            if (i < ((hFrameData->frameInfo[1]) << 1))
            {
                xoverBand = sbrDec->prevLowSubband;
            }
            else
            {
                xoverBand = sbrDec->lowSubband;
            }

            if (xoverBand > sbrDec->highSubband)
            {
                /*
                 * error condition, default to upsampling mode
                 * and make sure that the number of bands for xover does
                 * not exceed the number of high freq bands.
                 */
                xoverBand = (sbrDec->highSubband > 32)? 32: sbrDec->highSubband;
            }

            m = sbrDec->bufReadOffs + i;    /*  2 + i */

            Sr_x = hParametricStereoDec->qmfBufferReal[i];
            Si_x = hParametricStereoDec->qmfBufferImag[i];



            for (int32_t j = 0; j < xoverBand; j++)
            {
                Sr_x[j] = shft_lft_1(hFrameData->codecQmfBufferReal[m][j]);
                Si_x[j] = shft_lft_1(hFrameData->codecQmfBufferImag[m][j]);
            }




            pv_memcpy(&Sr_x[xoverBand],
                      &hFrameData->sbrQmfBufferReal[i*SBR_NUM_BANDS],
                      (sbrDec->highSubband - xoverBand)*sizeof(*Sr_x));

            pv_memcpy(&Si_x[xoverBand],
                      &hFrameData->sbrQmfBufferImag[i*SBR_NUM_BANDS],
                      (sbrDec->highSubband - xoverBand)*sizeof(*Si_x));

            pv_memset((void *)&Sr_x[sbrDec->highSubband],
                      0,
                      (64 - sbrDec->highSubband)*sizeof(*Sr_x));

            pv_memset((void *)&Si_x[sbrDec->highSubband],
                      0,
                      (64 - sbrDec->highSubband)*sizeof(*Si_x));


        }

        for (i = 32; i < 32 + 6; i++)
        {
            m = sbrDec->bufReadOffs + i;     /*  2 + i */

            for (int32_t j = 0; j < 5; j++)
            {
                hParametricStereoDec->qmfBufferReal[i][j] = shft_lft_1(hFrameData->codecQmfBufferReal[m][j]);
                hParametricStereoDec->qmfBufferImag[i][j] = shft_lft_1(hFrameData->codecQmfBufferImag[m][j]);
            }

        }


        /*
         *    Update Buffers
         */
        for (i = 0; i < sbrDec->bufWriteOffs; i++)     /* sbrDec->bufWriteOffs set to 8 and unchanged */
        {
            j = sbrDec->noCols + i;                    /* sbrDec->noCols set to 32 and unchanged */

            pv_memmove(hFrameData->codecQmfBufferReal[i],         /* to    */
                       hFrameData->codecQmfBufferReal[j],        /* from  */
                       sizeof(*hFrameData->codecQmfBufferReal[i]) << 5);

            pv_memmove(hFrameData->codecQmfBufferImag[i],
                       hFrameData->codecQmfBufferImag[j],
                       sizeof(*hFrameData->codecQmfBufferImag[i]) << 5);
        }


        pv_memmove(&hFrameData->HistsbrQmfBufferReal[0],
                   &hFrameData->sbrQmfBufferReal[32*SBR_NUM_BANDS],
                   6*SBR_NUM_BANDS*sizeof(*hFrameData->sbrQmfBufferReal));

        pv_memmove(&hFrameData->HistsbrQmfBufferImag[0],
                   &hFrameData->sbrQmfBufferImag[32*SBR_NUM_BANDS],
                   6*SBR_NUM_BANDS*sizeof(*hFrameData->sbrQmfBufferImag));


        /*
         *   Needs whole QMF matrix formed before applying
         *   Parametric stereo processing.
         */

        qmf_PS_generated_Real = scratch_mem[0];
        qmf_PS_generated_Imag = scratch_mem[1];
        env = 0;

        /*
         *  Set circular buffer for Left channel
         */

        circular_buffer_s = (Int16 *)scratch_mem[7];


        if (pVars->mc_info.bDownSampledSbr)
        {
            pv_memmove(&circular_buffer_s[2048],
                       hFrameData->V,
                       640*sizeof(*circular_buffer_s));
        }
        else
        {
            pv_memmove(&circular_buffer_s[4096],
                       hFrameData->V,
                       1152*sizeof(*circular_buffer_s));

        }


        /*
         *  Set Circular buffer for PS hybrid analysis
         */

        int32_t *pt_temp = &scratch_mem[2][32];

        for (i = 0, j = 0; i < 3; i++)
        {

            pv_memmove(&pt_temp[ j],
                       hParametricStereoDec->hHybrid->mQmfBufferReal[i],
                       HYBRID_FILTER_LENGTH_m_1*sizeof(*hParametricStereoDec->hHybrid->mQmfBufferReal));
            pv_memmove(&pt_temp[ j + 44],
                       hParametricStereoDec->hHybrid->mQmfBufferImag[i],
                       HYBRID_FILTER_LENGTH_m_1*sizeof(*hParametricStereoDec->hHybrid->mQmfBufferImag));
            j += 88;
        }


        pv_memset((void *)&qmf_PS_generated_Real[hParametricStereoDec->usb],
                  0,
                  (64 - hParametricStereoDec->usb)*sizeof(*qmf_PS_generated_Real));

        pv_memset((void *)&qmf_PS_generated_Imag[hParametricStereoDec->usb],
                  0,
                  (64 - hParametricStereoDec->usb)*sizeof(*qmf_PS_generated_Imag));


        for (i = 0; i < 32; i++)
        {
            if (i == (Int)hParametricStereoDec-> aEnvStartStop[env])
            {
                ps_init_stereo_mixing(hParametricStereoDec, env, sbrDec->highSubband);
                env++;
            }


            ps_applied(hParametricStereoDec,
                       &hParametricStereoDec->qmfBufferReal[i],
                       &hParametricStereoDec->qmfBufferImag[i],
                       qmf_PS_generated_Real,
                       qmf_PS_generated_Imag,
                       scratch_mem[2],
                       i);

            /* Create time samples for regular mono channel */

            if (pVars->mc_info.bDownSampledSbr)
            {
                calc_sbr_synfilterbank(hParametricStereoDec->qmfBufferReal[i],  /* realSamples  */
                                       hParametricStereoDec->qmfBufferImag[i], /* imagSamples  */
                                       ftimeOutPtr + (i << 6),
                                       &circular_buffer_s[1984 - (i<<6)],
                                       pVars->mc_info.bDownSampledSbr);
            }
            else
            {
                calc_sbr_synfilterbank(hParametricStereoDec->qmfBufferReal[i],  /* realSamples  */
                                       hParametricStereoDec->qmfBufferImag[i], /* imagSamples  */
                                       ftimeOutPtr + (i << 7),
                                       &circular_buffer_s[3968 - (i<<7)],
                                       pVars->mc_info.bDownSampledSbr);

            }

            pv_memmove(hParametricStereoDec->qmfBufferReal[i], qmf_PS_generated_Real, 64*sizeof(*qmf_PS_generated_Real));
            pv_memmove(hParametricStereoDec->qmfBufferImag[i], qmf_PS_generated_Imag, 64*sizeof(*qmf_PS_generated_Real));

        }


        /*
         *  Save Circular buffer history used on PS hybrid analysis
         */


        pt_temp = &scratch_mem[2][64];

        for (i = 0, j = 0; i < 3; i++)
        {
            pv_memmove(hParametricStereoDec->hHybrid->mQmfBufferReal[i],
                       &pt_temp[ j],
                       HYBRID_FILTER_LENGTH_m_1*sizeof(*hParametricStereoDec->hHybrid->mQmfBufferReal));

            pv_memmove(hParametricStereoDec->hHybrid->mQmfBufferImag[i],
                       &pt_temp[ j + 44],
                       HYBRID_FILTER_LENGTH_m_1*sizeof(*hParametricStereoDec->hHybrid->mQmfBufferImag));

            j += 88;
        }


        pv_memmove(hFrameData->V, &circular_buffer_s[0], 1152*sizeof(*circular_buffer_s));

        /*
         *  Set circular buffer for Right channel
         */

        circular_buffer_s = (Int16 *)scratch_mem[5];

        if (pVars->mc_info.bDownSampledSbr)
        {
            pv_memmove(&circular_buffer_s[2048],
                       (int32_t *)hParametricStereoDec->R_ch_qmf_filter_history,
                       640*sizeof(*circular_buffer_s));
        }
        else
        {
            pv_memmove(&circular_buffer_s[4096],
                       (int32_t *)hParametricStereoDec->R_ch_qmf_filter_history,
                       1152*sizeof(*circular_buffer_s));

        }


        for (i = 0; i < 32; i++)
        {
            if (pVars->mc_info.bDownSampledSbr)
            {

                calc_sbr_synfilterbank(hParametricStereoDec->qmfBufferReal[i],  /* realSamples  */
                                       hParametricStereoDec->qmfBufferImag[i], /* imagSamples  */
                                       ftimeOutPtrPS + (i << 6),
                                       &circular_buffer_s[1984 - (i<<6)],
                                       pVars->mc_info.bDownSampledSbr);
            }
            else
            {
                calc_sbr_synfilterbank(hParametricStereoDec->qmfBufferReal[i],  /* realSamples  */
                                       hParametricStereoDec->qmfBufferImag[i], /* imagSamples  */
                                       ftimeOutPtrPS + (i << 7),
                                       &circular_buffer_s[3968 - (i<<7)],
                                       pVars->mc_info.bDownSampledSbr);
            }

        }

        if (pVars->mc_info.bDownSampledSbr)
        {
            pv_memmove((int32_t *)hParametricStereoDec->R_ch_qmf_filter_history, &circular_buffer_s[0], 640*sizeof(*circular_buffer_s));
        }
        else
        {
            pv_memmove((int32_t *)hParametricStereoDec->R_ch_qmf_filter_history, &circular_buffer_s[0], 1152*sizeof(*circular_buffer_s));
        }





    }
    else    /*  else -- sbrEnablePS  */
    {

#endif      /*   PARAMETRICSTEREO */
#endif      /*   HQ_SBR */

        /*
         *  Use shared aac memory as continuous buffer
         */


        Sr  = scratch_mem[0];
        Si  = scratch_mem[1];

        circular_buffer_s = (Int16*)scratch_mem[2];

        if (pVars->mc_info.bDownSampledSbr)
        {

            pv_memmove(&circular_buffer_s[2048],
                       hFrameData->V,
                       640*sizeof(*circular_buffer_s));
        }
        else
        {
            pv_memmove(&circular_buffer_s[4096],
                       hFrameData->V,
                       1152*sizeof(*circular_buffer_s));
        }

        for (i = 0; i < 32; i++)
        {
            Int   xoverBand;

            if (applyProcessing)
            {
                if (i < ((hFrameData->frameInfo[1]) << 1))
                {
                    xoverBand = sbrDec->prevLowSubband;

                }
                else
                {
                    xoverBand = sbrDec->lowSubband;
                }

                if (xoverBand > sbrDec->highSubband)
                {
                    /*
                     * error condition, default to upsampling mode
                     * and make sure that the number of bands for xover does
                     * not exceed the number of high freq bands.
                     */
                    xoverBand = (sbrDec->highSubband > 32)? 32: sbrDec->highSubband;
                }
            }
            else
            {
                xoverBand = 32;
                sbrDec->highSubband = 32;
            }


            m = sbrDec->bufReadOffs + i;    /* sbrDec->bufReadOffs == 2 */


            ptr_tmp1 = (hFrameData->codecQmfBufferReal[m]);
            ptr_tmp2 = Sr;

            if (sbrDec->LC_aacP_DecoderFlag == ON)
            {

                for (k = (xoverBand >> 1); k != 0; k--)
                {
                    *(ptr_tmp2++) = (*(ptr_tmp1++)) >> 9;
                    *(ptr_tmp2++) = (*(ptr_tmp1++)) >> 9;
                }
                if (xoverBand & 1)
                {
                    *(ptr_tmp2++) = (*(ptr_tmp1)) >> 9;
                }

                ptr_tmp1 = &hFrameData->sbrQmfBufferReal[i*SBR_NUM_BANDS];


                for (k = xoverBand; k < sbrDec->highSubband; k++)
                {
                    *(ptr_tmp2++) = (*(ptr_tmp1++)) << 1;
                }

                pv_memset((void *)ptr_tmp2,
                          0,
                          (64 - sbrDec->highSubband)*sizeof(*ptr_tmp2));


                if (pVars->mc_info.bDownSampledSbr)
                {
                    calc_sbr_synfilterbank_LC(Sr,               /* realSamples  */
                                              ftimeOutPtr + (i << 6),
                                              &circular_buffer_s[1984 - (i<<6)],
                                              pVars->mc_info.bDownSampledSbr);
                }
                else
                {
                    calc_sbr_synfilterbank_LC(Sr,               /* realSamples  */
                                              ftimeOutPtr + (i << 7),
                                              &circular_buffer_s[3968 - (i<<7)],
                                              pVars->mc_info.bDownSampledSbr);
                }
            }
#ifdef HQ_SBR
            else
            {

                for (k = xoverBand; k != 0; k--)
                {
                    *(ptr_tmp2++) = shft_lft_1(*(ptr_tmp1++));
                }

                ptr_tmp1 = &hFrameData->sbrQmfBufferReal[i*SBR_NUM_BANDS];
                ptr_tmp2 = &Sr[xoverBand];


                for (k = xoverBand; k < sbrDec->highSubband; k++)
                {
                    *(ptr_tmp2++) = (*(ptr_tmp1++));
                }

                pv_memset((void *)ptr_tmp2,
                          0,
                          (64 - sbrDec->highSubband)*sizeof(*ptr_tmp2));


                ptr_tmp1 = (hFrameData->codecQmfBufferImag[m]);
                ptr_tmp2 = Si;

                for (k = (xoverBand >> 1); k != 0; k--)
                {
                    *(ptr_tmp2++) = shft_lft_1(*(ptr_tmp1++));
                    *(ptr_tmp2++) = shft_lft_1(*(ptr_tmp1++));
                }
                if (xoverBand & 1)
                {
                    *(ptr_tmp2) = shft_lft_1(*(ptr_tmp1));
                }

                ptr_tmp1 = &hFrameData->sbrQmfBufferImag[i*SBR_NUM_BANDS];
                ptr_tmp2 = &Si[xoverBand];

                for (k = xoverBand; k < sbrDec->highSubband; k++)
                {
                    *(ptr_tmp2++) = (*(ptr_tmp1++));
                }

                pv_memset((void *)ptr_tmp2,
                          0,
                          (64 - sbrDec->highSubband)*sizeof(*ptr_tmp2));


                if (pVars->mc_info.bDownSampledSbr)
                {
                    calc_sbr_synfilterbank(Sr,              /* realSamples  */
                                           Si,             /* imagSamples  */
                                           ftimeOutPtr + (i << 6),
                                           &circular_buffer_s[1984 - (i<<6)],
                                           pVars->mc_info.bDownSampledSbr);
                }
                else
                {
                    calc_sbr_synfilterbank(Sr,              /* realSamples  */
                                           Si,             /* imagSamples  */
                                           ftimeOutPtr + (i << 7),
                                           &circular_buffer_s[3968 - (i<<7)],
                                           pVars->mc_info.bDownSampledSbr);
                }
            }
#endif

        }

        if (pVars->mc_info.bDownSampledSbr)
        {
            pv_memmove(hFrameData->V, &circular_buffer_s[0], 640*sizeof(*circular_buffer_s));
        }
        else
        {
            pv_memmove(hFrameData->V, &circular_buffer_s[0], 1152*sizeof(*circular_buffer_s));
        }




        /*
         *    Update Buffers
         */
        for (i = 0; i < sbrDec->bufWriteOffs; i++)     /* sbrDec->bufWriteOffs set to 8 and unchanged */
        {
            j = sbrDec->noCols + i;                    /* sbrDec->noCols set to 32 and unchanged */

            pv_memmove(hFrameData->codecQmfBufferReal[i],         /* to    */
                       hFrameData->codecQmfBufferReal[j],        /* from  */
                       sizeof(*hFrameData->codecQmfBufferReal[i]) << 5);
        }


        pv_memmove(&hFrameData->HistsbrQmfBufferReal[0],
                   &hFrameData->sbrQmfBufferReal[32*SBR_NUM_BANDS],
                   6*SBR_NUM_BANDS*sizeof(*hFrameData->sbrQmfBufferReal));

#ifdef HQ_SBR
        if (sbrDec->LC_aacP_DecoderFlag == OFF)
        {
            for (i = 0; i < sbrDec->bufWriteOffs; i++)     /* sbrDec->bufWriteOffs set to 6 and unchanged */
            {
                j = sbrDec->noCols + i;                    /* sbrDec->noCols set to 32 and unchanged */


                pv_memmove(hFrameData->codecQmfBufferImag[i],
                           hFrameData->codecQmfBufferImag[j],
                           sizeof(*hFrameData->codecQmfBufferImag[i]) << 5);

            }

            pv_memmove(&hFrameData->HistsbrQmfBufferImag[0],
                       &hFrameData->sbrQmfBufferImag[32*SBR_NUM_BANDS],
                       6*SBR_NUM_BANDS*sizeof(*hFrameData->sbrQmfBufferImag));
        }
#endif


#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO
    }
#endif
#endif


    hFrameData->reset_flag = 0;
    if (applyProcessing)
    {
        sbrDec->prevLowSubband = sbrDec->lowSubband;
    }

}


#endif      /*  AAC_PLUS */
