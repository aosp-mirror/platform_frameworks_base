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

 Pathname: ps_all_pass_filter_coeff.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                   Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Header file for all pass filter coefficients

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef PS_ALL_PASS_FILTER_COEFF_H
#define PS_ALL_PASS_FILTER_COEFF_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "s_ps_dec.h"

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


extern const Char groupBorders[NO_IID_GROUPS + 1];
extern const Int16 aRevLinkDecaySerCoeff[NO_ALLPASS_CHANNELS][NO_SERIAL_ALLPASS_LINKS];
extern const Int32  aRevLinkDelaySer[];
extern const Int16 aFractDelayPhaseFactorReQmf[NO_QMF_ALLPASS_CHANNELS];
extern const Int16 aFractDelayPhaseFactorImQmf[NO_QMF_ALLPASS_CHANNELS];
/* the old center frequencies (found in "else") were too small (factor 1/2) */
extern const Int16 aFractDelayPhaseFactorReSubQmf[SUBQMF_GROUPS];
extern const Int16 aFractDelayPhaseFactorImSubQmf[SUBQMF_GROUPS];
extern const Int32 aFractDelayPhaseFactorSubQmf[SUBQMF_GROUPS];
extern const Int32 aFractDelayPhaseFactor[NO_QMF_ALLPASS_CHANNELS];
extern const Int32 aaFractDelayPhaseFactorSerQmf[NO_QMF_ALLPASS_CHANNELS][3];
extern const Int32 aaFractDelayPhaseFactorSerSubQmf[SUBQMF_GROUPS][3];
extern const Char bins2groupMap[NO_IID_GROUPS];
extern const Int32 aaFractDelayPhaseFactorSerReQmf[NO_QMF_ALLPASS_CHANNELS][3];
extern const Int32 aaFractDelayPhaseFactorSerImQmf[NO_QMF_ALLPASS_CHANNELS][3];
extern const Int32 aaFractDelayPhaseFactorSerReSubQmf[SUBQMF_GROUPS][3];
extern const Int32 aaFractDelayPhaseFactorSerImSubQmf[SUBQMF_GROUPS][3];


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
#endif  /* PS_ALL_PASS_FILTER_COEFF_H */
