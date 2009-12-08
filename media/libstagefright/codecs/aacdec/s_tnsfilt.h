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

 Pathname: s_TNSfilt.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Added lpc, start, & size, so the data from
 tns_inv_subblock can be shared with tns_decode_subblock.

 Description: Removed lpc to save 2KB of memory (on 32-bit machines.)
 This change requires tns_decode_coef.c to perform calculations in place.

 Description: Removed start & size.  start_band and stop_band can simply
 take on a new meaning after this function.  (coef index, rather than
 scalefactor band index.)

 Description: Had to add "start_coef" and "stop_coef" in order to preserve
 values "start_band" and "stop_band."  This required a change to
 tns_setup_filter.c also.

 Description: Had to add element "q_lpc" to store the q-format of the lpc
 coefficients passed via "coef."

 Description: Moved lpc_coef array up to the s_TNS_frame_info.h structure.

 Description:
 (1) Modified to include the lines...

    #ifdef __cplusplus
    extern "C" {
    #endif

    #ifdef __cplusplus
    }
    #endif

 (2) Updated the copyright header.

 Who:                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file defines the structure, s_TNSfilt

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_TNSFILT_H
#define S_TNSFILT_H

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_tns_const.h"

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
        Int start_band;
        Int stop_band;
        Int start_coef;
        Int stop_coef;
        UInt order;
        Int direction;
        Int q_lpc;

    } TNSfilt;

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

#ifdef __cplusplus
}
#endif

#endif /* S_TNSFILT_H */
