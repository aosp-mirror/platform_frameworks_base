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

 Pathname: ./include/get_ics_info.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Contains the declaration for the function get_ics_info()

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef GET_ICS_INFO_H
#define GET_ICS_INFO_H

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_tmp4audioobjecttype.h"
#include "s_bits.h"
#include "e_window_sequence.h"
#include "e_window_shape.h"
#include "s_frameinfo.h"
#include "s_lt_pred_status.h"

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
    Int get_ics_info(
        const tMP4AudioObjectType  audioObjectType,
        BITS                      *pInputStream,
        const Bool                 common_window,
        WINDOW_SEQUENCE           *p_wnd,
        WINDOW_SHAPE              *pWindowShape,
        Int                        group[],
        Int                       *p_max_sfb,
        FrameInfo                 *p_winmap[],
        LT_PRED_STATUS            *pFirstLTPStatus,
        LT_PRED_STATUS            *pSecondLTPStatus);


#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif /* GET_ICS_INFO_H */


