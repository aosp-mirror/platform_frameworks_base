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

 Filename: s_sbr_dec.h
 Funtions:
    get_dse

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:
------------------------------------------------------------------------------
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

 $Id: ct_envcalc.h,v 1.3 2002/11/29 16:11:49 kaehleof Exp $
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_SBR_DEC_H
#define S_SBR_DEC_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include    "s_sbr_frame_data.h"
#include    "pv_audio_type_defs.h"
#include    "s_patch.h"
#include    "e_blockswitching.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; SIMPLE TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; ENUMERATED TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; STRUCTURES TYPEDEF'S
----------------------------------------------------------------------------*/
typedef struct
{
    Int32 outSampleRate;
    Int32 LC_aacP_DecoderFlag;  /* Low Complexity decoder flag  */

    Int32 startIndexCodecQmf;
    Int32 lowBandAddSamples;
    Int32 noCols;
    Int32 qmfBufLen;
    Int32 bufWriteOffs;
    Int32 bufReadOffs;

    Int32 sbStopCodec;
    Int   lowSubband;
    Int   prevLowSubband;
    Int32 highSubband;
    Int32 noSubbands;

    Int   FreqBandTable[2][MAX_FREQ_COEFFS + 1];
    Int32 FreqBandTableNoise[MAX_NOISE_COEFFS + 1];
    Int32 V_k_master[MAX_FREQ_COEFFS + 1];         /* Master BandTable which freqBandTable is derived from*/
    Int32 NSfb[2];
    Int32 NoNoiseBands;                            /* Number of noisebands */
    Int32 Num_Master;                              /* Number of bands in V_k_master*/

    struct PATCH Patch;                         /* Used by sbr_generate_high_freq */
    /* Used by calc_sbr_envelope */
    Int32 gateMode[4];
    Int32 limSbc[4][12 + 1];                            /* Limiting bands */

    Int32 sqrt_cache[8][4];                     /* cache memory for repeated sqrt() calculations */

} SBR_DEC;



/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


