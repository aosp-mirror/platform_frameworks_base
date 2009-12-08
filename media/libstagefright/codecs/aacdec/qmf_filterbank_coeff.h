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

 Pathname: qmf_filterbank_coeff.h

------------------------------------------------------------------------------
 REVISION HISTORY


------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 this file declares the scalefactor bands for all sampling rates

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef QMF_FILTERBANK_COEFF_H
#define QMF_FILTERBANK_COEFF_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"

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

#define Qfmt(x)   (Int16)(x*(((Int32)1<<15)*1.11111111111111111F) + (x>=0?0.5F:-0.5F))


#define Qfmt30(x)   (Int32)(x*((Int32)1<<30) + (x>=0?0.5F:-0.5F))
#define Qfmt27(x)   (Int32)(x*(((Int32)1<<27)) + (x>=0?0.5F:-0.5F))

extern const Int32 sbrDecoderFilterbankCoefficients[155];


extern const Int32 sbrDecoderFilterbankCoefficients_down_smpl[160];
extern const Int32 sbrDecoderFilterbankCoefficients_an_filt_LC[155];

#ifdef HQ_SBR
extern const Int32 sbrDecoderFilterbankCoefficients_an_filt[155];
#endif

/*----------------------------------------------------------------------------
; SIMPLE TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; ENUMERATED TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; STRUCTURES TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


