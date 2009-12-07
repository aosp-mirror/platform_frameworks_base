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

 Pathname: s_TNS_frame_info.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Modified to eliminate triple-nested structure, which wasted
 2 KB of memory.

 Description:
 (1) Modified to include the lines...

    #ifdef __cplusplus
    extern "C" {
    #endif

    #ifdef __cplusplus
    }
    #endif

 (2) Updated the copyright header.

 Description:
 (1) Changed hard coded size of arrays from "8" to "TNS_MAX_WIN"
 (2) Moved elements from s_TNSinfo.h up to the level of TNS_frame_info
 (3) Added Bool "tns_data_present" for future use.
 (4) Moved lpc_coef up from s_TNSfilt.h (allowed for use of smaller array.)

 Description:
 (1) Removed the array "coef_res", which is now a local variable on the
 stack inside get_tns.c

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file defines the structure, s_TNS_frame_info

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_TNS_FRAME_INFO_H
#define S_TNS_FRAME_INFO_H

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_tns_const.h"
#include "s_tnsfilt.h"

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
        Bool tns_data_present;

        /* Information about the number of filters for each window. */
        Int n_filt[TNS_MAX_WIN];

        /*
         * Filter Information
         *
         * For short windows, there is a maximum of
         * 1 filter per window (8 total)
         *
         * For long windows, there is a maximum of 3 filters
         *
         */
        TNSfilt filt[TNS_MAX_WIN];

        /*
         * For short windows, there is a maximum of 8 filters,
         * each of order 7 (requring 56 Ints)
         *
         * For long windows, there is a maximum of 3 filters,
         * each of order 20 (requiring 60 Ints)
         *
         * So, 3*TNS_MAX_ORDER declares an array of sufficient
         * size (60) for both cases.
         */
        Int32 lpc_coef[3*TNS_MAX_ORDER];

    } TNS_frame_info;

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

#ifdef __cplusplus
}
#endif

#endif /* S_TNS_FRAME_INFO_H */
