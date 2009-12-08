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

  Filename: sbr_applied.c

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


#include    "sbr_applied.h"
#include    "sbr_read_data.h"

#include    "sbr_decode_envelope.h"
#include    "decode_noise_floorlevels.h"
#include    "sbr_requantize_envelope_data.h"
#include    "sbr_envelope_unmapping.h"
#include    "sbr_dec.h"
#include    "e_sbr_element_id.h"
#include    "aac_mem_funcs.h"

#ifdef PARAMETRICSTEREO
#include    "ps_bstr_decoding.h"
#include    "ps_allocate_decoder.h"

#endif

#include    "init_sbr_dec.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define LEFT  (0)
#define RIGHT (1)


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

SBR_ERROR  sbr_applied(SBRDECODER_DATA * self,
                       SBRBITSTREAM * stream,
                       Int16 *ch_left,
                       Int16 *ch_right,
                       Int16 *timeData,
                       SBR_DEC *sbrDec,
                       tDec_Int_File  *pVars,
                       Int32 numChannels)
{
    SBR_ERROR err = SBRDEC_OK ;

    Int32 eleChannels = 0;

    SBR_CHANNEL *SbrChannel = self->SbrChannel;

    /* Get SBR or PS Data only when available */
    if (stream->NrElements)
    {
        /* read frame data from bitstream */

        err = sbr_read_data(self,
                            sbrDec,
                            stream);

        if (err != SBRDEC_OK)
        {
            /*
             * This error condition disables any further SBR processing
             */
            self->SbrChannel[LEFT].syncState = UPSAMPLING;
            if (eleChannels == 2)
            {
                self->SbrChannel[RIGHT].syncState = UPSAMPLING;
            }
        }

        /*
         *  Setting bistream and decoding type is only done once,
         */
        if (SbrChannel[LEFT].syncState == SBR_ACTIVE && self->setStreamType)
        {
            self->setStreamType = 0;  /* Disable Lock for AAC stream type setting  */

#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO

            Int sbrEnablePS = self->hParametricStereoDec->psDetected;

            pVars->mc_info.psPresentFlag  = sbrEnablePS;

            if (sbrEnablePS)   /* Initialize PS arrays */
            {
                pVars->mc_info.ExtendedAudioObjectType = MP4AUDIO_PS;
                ps_allocate_decoder(self, 32);

                /* Disable LC (or Enable HQ)  if PS is detected */
                sbrDec->LC_aacP_DecoderFlag = OFF;
            }
            else
            {
                /*
                 *  Do not downgrade stream type from eaac+, if it has been explicitly declared
                 */
                if (pVars->mc_info.ExtendedAudioObjectType != MP4AUDIO_PS)
                {
                    pVars->mc_info.ExtendedAudioObjectType = MP4AUDIO_SBR;

                    if (pVars->mc_info.nch > 1)
                    {
                        sbrDec->LC_aacP_DecoderFlag = ON;    /* Enable LC for stereo */
                    }
                    else
                    {
                        sbrDec->LC_aacP_DecoderFlag = OFF;    /* Disable LC, Enable HQ for mono */
                    }
                }
                else
                {
                    sbrEnablePS = 1;  /* Force this condition as it was explicititly declared */
                    pVars->mc_info.psPresentFlag  = sbrEnablePS;

                }
            }
#else

            pVars->mc_info.ExtendedAudioObjectType = MP4AUDIO_SBR;

            if (pVars->mc_info.nch > 1)
            {
                sbrDec->LC_aacP_DecoderFlag = ON;    /* Enable LC for stereo */
            }
            else
            {
                sbrDec->LC_aacP_DecoderFlag = OFF;    /* Disable LC, Enable HQ for mono */
            }
#endif

#else
            pVars->mc_info.ExtendedAudioObjectType = MP4AUDIO_SBR;

            sbrDec->LC_aacP_DecoderFlag = ON;       /* Enable LC for all sbr decoding */

#endif

        }   /*   (SbrChannel[LEFT].syncState == SBR_ACTIVE && lock)  */
        else
        {
            /*
             *  Default setting for upsampler
             */
            if (pVars->mc_info.ExtendedAudioObjectType == MP4AUDIO_AAC_LC)
            {
                /*
                 *  Change only in implicit signalling, otherwise keep original declaration
                 */
                pVars->mc_info.ExtendedAudioObjectType = MP4AUDIO_SBR;
            }

#ifdef HQ_SBR
            if (pVars->mc_info.nch > 1)
            {
                sbrDec->LC_aacP_DecoderFlag = ON;    /* Enable LC for stereo */
            }
            else
            {
                sbrDec->LC_aacP_DecoderFlag = OFF;    /* Disable LC, Enable HQ for mono */
            }
#else
            sbrDec->LC_aacP_DecoderFlag = ON;       /* Enable LC for all sbr decoding */

#endif
            /* mask error and let upsampler run */
            err = SBRDEC_OK;

        }

        /* decoding */
        eleChannels = (stream->sbrElement [LEFT].ElementID == SBR_ID_CPE) ? 2 : 1;

        if (SbrChannel[LEFT].syncState == SBR_ACTIVE)
        {

            sbr_decode_envelope(&(SbrChannel[LEFT].frameData));

            decode_noise_floorlevels(&(SbrChannel[LEFT].frameData));

            if (! SbrChannel[LEFT].frameData.coupling)
            {
                sbr_requantize_envelope_data(&(SbrChannel[LEFT].frameData));
            }

            if (eleChannels == 2)
            {

                sbr_decode_envelope(&(SbrChannel[RIGHT].frameData));

                decode_noise_floorlevels(&(SbrChannel[RIGHT].frameData));

                if (SbrChannel[RIGHT].frameData.coupling)
                {
                    sbr_envelope_unmapping(&(SbrChannel[ LEFT].frameData),
                                           &(SbrChannel[RIGHT].frameData));
                }
                else
                {
                    sbr_requantize_envelope_data(&(SbrChannel[RIGHT].frameData));
                }
            }
        }
        else            /* enable upsampling until valid SBR is obtained */
        {
            /*
             *  Incomplete sbr frame, or disabled SBR section
             *  Set the decoder to act as a regular upsampler
             */

            init_sbr_dec((sbrDec->outSampleRate >> 1),
                         pVars->mc_info.upsamplingFactor,
                         sbrDec,
                         &(self->SbrChannel[LEFT].frameData));

            if ((eleChannels == 2) && (SbrChannel[RIGHT].syncState != SBR_ACTIVE))
            {
                init_sbr_dec((sbrDec->outSampleRate >> 1),
                             pVars->mc_info.upsamplingFactor,
                             sbrDec,
                             &(self->SbrChannel[RIGHT].frameData));

            }

        }

    }


#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO
    if (pVars->mc_info.ExtendedAudioObjectType == MP4AUDIO_PS)
    {
        ps_bstr_decoding(self->hParametricStereoDec);
        /* allocate pointer for rigth channel qmf filter history  */
        Int16 *tempInt16Ptr = (Int16 *)SbrChannel[RIGHT].frameData.V;
        self->hParametricStereoDec->R_ch_qmf_filter_history = (Int32 *)tempInt16Ptr;


        /*
         * 1824 (48*38) Int32 needed by each matrix sbrQmfBufferReal, sbrQmfBufferImag
         * pVars->share.predictedSamples  has 2048 available
         * pVars->fxpCoef[1]  has 2048 available
         */
        SbrChannel[LEFT].frameData.sbrQmfBufferReal = pVars->share.predictedSamples;
        SbrChannel[LEFT].frameData.sbrQmfBufferImag = &pVars->fxpCoef[0][920];

        sbr_dec(ch_left,
                timeData,
                &(SbrChannel[LEFT].frameData),
                (SbrChannel[LEFT].syncState == SBR_ACTIVE),
                sbrDec,
                &timeData[RIGHT],
                self->hParametricStereoDec,
                pVars);
    }
    else
    {
#endif
#endif

        SbrChannel[LEFT].frameData.sbrQmfBufferReal = pVars->fxpCoef[LEFT];
#ifdef HQ_SBR
        SbrChannel[LEFT].frameData.sbrQmfBufferImag = pVars->fxpCoef[RIGHT];
#endif

        sbr_dec(ch_left,
                timeData,
                &(SbrChannel[LEFT].frameData),
                (SbrChannel[LEFT].syncState == SBR_ACTIVE),
                sbrDec,
#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO
                NULL,
                NULL,
#endif
#endif
                pVars);

        if (numChannels == 2)
        {
            SbrChannel[RIGHT].frameData.sbrQmfBufferReal = pVars->fxpCoef[LEFT];
#ifdef HQ_SBR
            SbrChannel[RIGHT].frameData.sbrQmfBufferImag = pVars->fxpCoef[RIGHT];
#endif

            sbr_dec(ch_right,
                    &timeData[RIGHT],
                    &(SbrChannel[RIGHT].frameData),
                    (SbrChannel[RIGHT].syncState == SBR_ACTIVE),
                    sbrDec,
#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO
                    NULL,
                    NULL,
#endif
#endif
                    pVars);

        }


#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO
    }
#endif
#endif

    return err;
}


#endif

