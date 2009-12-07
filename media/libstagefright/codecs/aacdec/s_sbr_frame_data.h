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

 Filename: s_sbr_frame_data.h
 Funtions:

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:
------------------------------------------------------------------------------


----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_SBR_FRAME_DATA_H
#define S_SBR_FRAME_DATA_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "s_sbr_header_data.h"
#include    "e_invf_mode.h"
#include    "e_coupling_mode.h"
#include    "sbr_constants.h"
#include    "s_patch.h"
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
    Int32 nScaleFactors;            /* total number of scalefactors in frame */
    Int32 nNoiseFactors;
    Int32 crcCheckSum;
    Int32 frameClass;
    Int32 frameInfo[LENGTH_FRAME_INFO];
    Int32 nSfb[2];
    Int32 nNfb;
    Int32 offset;
    Int32 ampRes;
    Int32 nNoiseFloorEnvelopes;
    Int32 p;
    Int32 prevEnvIsShort;

    Int32 reset_flag;


    SBR_HEADER_DATA sbr_header;


    /* dynamic control signals */
    Int32 domain_vec1[MAX_ENVELOPES];
    Int32 domain_vec2[MAX_ENVELOPES];


    INVF_MODE sbr_invf_mode[MAX_NUM_NOISE_VALUES];
    INVF_MODE sbr_invf_mode_prev[MAX_NUM_NOISE_VALUES];

    COUPLING_MODE coupling;               /*  3 possibilities: off, level, pan */


    Int32 addHarmonics[MAX_NUM_ENVELOPE_VALUES];

    /* Used by calc_sbr_envelope */
    Int32 hFp[64];
    Int32 harm_index;
    Int32 phase_index;
    Int32 sUp;

    /*
     *    envelope data
     */

    Int32 iEnvelope_man[MAX_NUM_ENVELOPE_VALUES]; /* mantissa */
    Int32 iEnvelope_exp[MAX_NUM_ENVELOPE_VALUES]; /* exponent */
    Int32 sfb_nrg_prev_man[MAX_FREQ_COEFFS];      /* mantissa */


    /*
     *    noise data
     */

    Int32 sbrNoiseFloorLevel_man[MAX_NUM_NOISE_VALUES]; /* mantissa */
    Int32 sbrNoiseFloorLevel_exp[MAX_NUM_NOISE_VALUES]; /* exponent */
    Int32 prevNoiseLevel_man[MAX_NUM_NOISE_VALUES]; /* mantissa */

    Int32  BwVector[MAX_NUM_PATCHES];
    Int32  BwVectorOld[MAX_NUM_PATCHES];
    /* Both implement a pseudo circular buffer  */

    /*
     * 40 ==  Biggest of  autoCorrLength(38) + sbrDec->bufReadOffs (2)  and
     *    sbrDec->noCols (32) + sbrDec->bufWriteOffs  (6)
     */
    Int32 codecQmfBufferReal[40][32];
    Int32 *sbrQmfBufferReal;
    Int32 HistsbrQmfBufferReal[6*SBR_NUM_BANDS];
#ifdef HQ_SBR
    Int32 codecQmfBufferImag[40][32];
    Int32 *sbrQmfBufferImag;
    Int32 HistsbrQmfBufferImag[6*SBR_NUM_BANDS];
#endif
    Int16  V[1152];     /* Used by calc_sbr_synfilterbank as freq. history buffer */


    Int32 degreeAlias[64];


#ifdef HQ_SBR

    Int32 fBuffer_man[5][64];        /* smoothing history buffers */
    Int32 fBufferN_man[5][64];
    Int32 fBuffer_exp[5][64];        /* smoothing history buffers */
    Int32 fBufferN_exp[5][64];

    Int32 *fBuf_man[64];        /* pointer to smoothing history buffers */
    Int32 *fBuf_exp[64];        /* pointer to smoothing history buffers */
    Int32 *fBufN_man[64];
    Int32 *fBufN_exp[64];


#endif

}
SBR_FRAME_DATA;


/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


