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

 Pathname: long_term_synthesis.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: 1. Changed protoype with array size passed in per review
                 comments.
              2. Moved #define NUM_RECONSTRUCTED_SFB to ltp_common_internal.h

 Description: Modified prototype based on review comments for new version
          long_term_synthesis.c.

 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file includes function prototype declaration for long_term_synthesis().

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef LONG_TERM_SYNTHESIS_H
#define LONG_TERM_SYNTHESIS_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_window_sequence.h"

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
void long_term_synthesis(
    WINDOW_SEQUENCE     win_seq,
    Int                 sfb_per_win,
    Int16               win_sfb_top[],
    Int                 win_prediction_used[],
    Int                 sfb_prediction_used[],
    Int32               current_frame[],
    Int                 q_format[],
    Int32               predicted_spectral[],
    Int                 pred_q_format,
    Int                 coef_per_win,
    Int                 short_window_num,
    Int                 reconstruct_sfb_num);

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


