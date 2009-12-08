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

 Pathname: long_term_prediction.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Modified prototype with array size passed in per review
              comments.

 Description: Changed prototype with "weight_index" instead of "weight".

 Description: Removed some passed in buffer size variables since they are
              not being used for long window.

 Description: Temporarily define LTP_Q_FORMAT for current release.
              Need to change function prototype and pass out Q_format
              information later.

 Description: Updated function prototype to reflect the usage of a
 circular buffer by LTP.

 Description:  Updated function interface with new return type

 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file includes function prototype declaration for long_term_prediction().

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef LONG_TERM_PREDICTION_H
#define LONG_TERM_PREDICTION_H

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
#define LTP_Q_FORMAT    (15)

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


    Int long_term_prediction(
        WINDOW_SEQUENCE     win_seq,
        const Int           weight_index,
        const Int           delay[],
        const Int16         buffer[],
        const Int           buffer_offset,
        const Int32         time_quant[],
        Int32               predicted_samples[],    /* Q15 */
        const Int           frame_length);

#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


