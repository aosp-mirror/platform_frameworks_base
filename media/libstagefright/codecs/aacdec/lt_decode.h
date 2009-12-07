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

 Pathname: lt_decode.h


------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Changing to move pInputStream to 2nd parameter.

 Description: Replaced "e_WINDOW_TYPE.h" with "e_WINDOW_SEQUENCE.h"

 Who:                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file contains the global function declaration for lt_decode

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef LT_DECODE_H
#define LT_DECODE_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_window_sequence.h"
#include "s_lt_pred_status.h"
#include "s_bits.h"

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
void lt_decode(
    const WINDOW_SEQUENCE win_type,
    BITS           *pInputStream,
    const Int             max_sfb,
    LT_PRED_STATUS *pLt_pred);

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


