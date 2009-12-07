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

 Pathname: ./include/e_HuffmanConst.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 enum for Huffman related constants

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef E_HUFFMAN_CONST_H
#define E_HUFFMAN_CONST_H

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
     * specify huffman tables as signed (1) or unsigned (0)
     */
    HUF1SGN     = 1,
    HUF2SGN     = 1,
    HUF3SGN     = 0,
    HUF4SGN     = 0,
    HUF5SGN     = 1,
    HUF6SGN     = 1,
    HUF7SGN     = 0,
    HUF8SGN     = 0,
    HUF9SGN     = 0,
    HUF10SGN        = 0,
    HUF11SGN        = 0,

    ZERO_HCB        = 0,
    BY4BOOKS        = 4,
    ESCBOOK     = 11,
    NSPECBOOKS      = ESCBOOK + 1,
    BOOKSCL     = NSPECBOOKS,
    NBOOKS      = NSPECBOOKS + 1,
    INTENSITY_HCB2  = 14,
    INTENSITY_HCB   = 15,
    NOISE_HCB       = 13,
    NOISE_HCB2      = 113,

    NOISE_PCM_BITS      = 9,
    NOISE_PCM_OFFSET    = (1 << (NOISE_PCM_BITS - 1)),

    NOISE_OFFSET        = 90,

    LONG_SECT_BITS  = 5,
    SHORT_SECT_BITS = 3
} eHuffmanConst;

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


