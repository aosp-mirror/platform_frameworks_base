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

 Pathname: pns_intensity_right.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Add hasmask parameter

 Description: Changed name to from right_ch_sfb_tools_ms to intensity_right
 to more correct reflect the purpose of the function.

 Who:                                  Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file contains the function declaration for
 pns_intensity_right.c

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef PNS_INTENSITY_RIGHT_H
#define PNS_INTENSITY_RIGHT_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "s_frameinfo.h"

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

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/
void pns_intensity_right(
    const Int        hasmask,
    const FrameInfo * const pFrameInfo,
    const Int        group[],
    const Bool       mask_map[],
    const Int        codebook_map[],
    const Int        factorsL[],
    const Int        factorsR[],
    Int        sfb_prediction_used[],
    const Bool       ltp_data_present,
    Int32      spectralCoefLeft[],
    Int32      spectralCoefRight[],
    Int        q_formatLeft[MAXBANDS],
    Int        q_formatRight[MAXBANDS],
    Int32     * const pCurrentSeed);

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


