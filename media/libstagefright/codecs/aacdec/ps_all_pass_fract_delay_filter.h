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

 Pathname: ps_all_pass_fract_delay_filter.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                   Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Header file for function ps_all_pass_fract_delay_filter()

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef PS_ALL_PASS_FRACT_DELAY_FILTER_H
#define PS_ALL_PASS_FRACT_DELAY_FILTER_H



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
#define R_SHIFT     29
#define Q29_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

#define Qfmt15(x)   (Int16)(x*((Int32)1<<15) + (x>=0?0.5F:-0.5F))


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

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif


    void ps_all_pass_fract_delay_filter_type_I(UInt32 *delayBufIndex,
    Int32 sb_delay,
    const Int32 *ppFractDelayPhaseFactorSer,
    Int32 ***pppRealDelayRBufferSer,
    Int32 ***pppImagDelayRBufferSer,
    Int32 *rIn,
    Int32 *iIn);


    void ps_all_pass_fract_delay_filter_type_II(UInt32 *delayBufIndex,
            Int32 sb_delay,
            const Int32 *ppFractDelayPhaseFactorSer,
            Int32 ***pppRealDelayRBufferSer,
            Int32 ***pppImagDelayRBufferSer,
            Int32 *rIn,
            Int32 *iIn,
            Int32 decayScaleFactor);

#ifdef __cplusplus
}
#endif


/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif  /* PS_ALL_PASS_FRACT_DELAY_FILTER_H */
