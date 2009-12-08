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

 Pathname: s_frameinfo.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Changed name of bk_sfb_top to frame_sfb_top.
 Included "interface.h" for defintion of MAX_WIN.  This
 will hopefully be simplified when interface.h is broken up into smaller
 include files.

 Description: Eliminated the never used array, group_offs[8]

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

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file defines the structure, FrameInfo

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_FRAMEINFO_H
#define S_FRAMEINFO_H

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_blockswitching.h"

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
        Int     islong;                 /* true if long block */
        Int     num_win;                /* sub-blocks (SB) per block */
        Int     coef_per_frame;         /* coef's per block */
        Int     sfb_per_frame;          /* sfb per block */
        Int     coef_per_win[MAX_WIN];  /* coef's per SB */
        Int     sfb_per_win[MAX_WIN];   /* sfb per SB */
        Int     sectbits[MAX_WIN];
        Int16   *win_sfb_top[MAX_WIN];  /* top coef per sfb per SB */
        Int     *sfb_width_128;         /* sfb width for short blocks */

        Int     frame_sfb_top[MAXBANDS];    /* Only used in calc_gsfb_table() -
                                      it is simply a cum version of
                                      the above information */
        Int     num_groups;
        Int     group_len[8];

    } FrameInfo;

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

#ifdef __cplusplus
}
#endif

#endif /* S_FRAMEINFO_H */
