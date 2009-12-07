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

 Pathname: ./include/e_BlockSwitching.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 enum for BlockSwitching related constants

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef E_BLOCK_SWITCHING_H
#define E_BLOCK_SWITCHING_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

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
typedef enum
{
    /*
     * block switching
     */
    LN          = 2048,
    SN          = 256,
    LN2         = LN / 2,
    SN2         = SN / 2,
    LN4         = LN / 4,
    SN4         = SN / 4,
    NSHORT      = LN / SN,
    MAX_SBK     = NSHORT,
    MAX_WIN     = MAX_SBK,

    ONLY_LONG_WINDOW    = 0,
    LONG_START_WINDOW,
    EIGHT_SHORT_WINDOW,
    LONG_STOP_WINDOW,
    NUM_WIN_SEQ,

    WLONG       = ONLY_LONG_WINDOW,
    WSTART,
    WSHORT,
    WSTOP,

    MAXBANDS        = 16 * NSHORT,  /* max number of scale factor bands */
    MAXFAC      = 121,      /* maximum scale factor */
    MIDFAC      = (MAXFAC - 1) / 2,
    SF_OFFSET       = 100       /* global gain must be positive */
} eBlockSwitching;

/*----------------------------------------------------------------------------
; STRUCTURES TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif
