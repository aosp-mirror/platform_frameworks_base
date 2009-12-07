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

 Pathname:   chans.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Placed file in the correct template format.

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef CHANS_H
#define CHANS_H

#ifdef __cplusplus
extern "C"
{
#endif

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
    /* #define is required in order to use these args in #if () directive */
#define ICChans 0
#define DCChans 0
#define XCChans 0
#define CChans  0

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
    enum
    {
        /*
         * channels for 5.1 main profile configuration
         * (modify for any desired decoder configuration)
         */
        FChans  = 2,    /* front channels: left, center, right */
        FCenter = 0,    /* 1 if decoder has front center channel */
        SChans  = 0,    /* side channels: */
        BChans  = 0,    /* back channels: left surround, right surround */
        BCenter = 0,    /* 1 if decoder has back center channel */
        LChans  = 0,    /* LFE channels */
        XChans  = 0,    /* scratch space for parsing unused channels */

        Chans   = FChans + SChans + BChans + LChans + XChans
    };
    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/


    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

#ifdef __cplusplus
}
#endif

#endif /* CHANS_H */

