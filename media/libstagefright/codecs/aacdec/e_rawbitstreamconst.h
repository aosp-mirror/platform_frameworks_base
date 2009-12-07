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

   Pathname: e_RawBitstreamConst.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 enum for the Raw Bitstream related constants

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef E_RAW_BITSTREAM_CONST_H
#define E_RAW_BITSTREAM_CONST_H

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
    LEN_SE_ID       = 3,
    LEN_TAG     = 4,
    LEN_COM_WIN     = 1,
    LEN_ICS_RESERV  = 1,
    LEN_WIN_SEQ     = 2,
    LEN_WIN_SH      = 1,
    LEN_MAX_SFBL    = 6,
    LEN_MAX_SFBS    = 4,
    LEN_CB          = 4,
    LEN_SCL_PCM     = 8,
    LEN_PRED_PRES   = 1,
    LEN_PRED_RST    = 1,
    LEN_PRED_RSTGRP = 5,
    LEN_PRED_ENAB   = 1,
    LEN_MASK_PRES   = 2,
    LEN_MASK        = 1,
    LEN_PULSE_PRES  = 1,
    LEN_TNS_PRES    = 1,
    LEN_GAIN_PRES   = 1,

    LEN_PULSE_NPULSE    = 2,
    LEN_PULSE_ST_SFB    = 6,
    LEN_PULSE_POFF      = 5,
    LEN_PULSE_PAMP      = 4,
    NUM_PULSE_LINES     = 4,
    PULSE_OFFSET_AMP    = 4,

    LEN_IND_CCE_FLG = 1,
    LEN_NCC         = 3,
    LEN_IS_CPE      = 1,
    LEN_CC_LR       = 1,
    LEN_CC_DOM      = 1,
    LEN_CC_SGN      = 1,
    LEN_CCH_GES     = 2,
    LEN_CCH_CGP     = 1,

    LEN_D_ALIGN     = 1,
    LEN_D_CNT       = 8,
    LEN_D_ESC       = 8,
    LEN_F_CNT       = 4,
    LEN_F_ESC       = 8,
    LEN_BYTE        = 8,
    LEN_PAD_DATA    = 8,

    LEN_PC_COMM     = 9

} eRawBitstreamConst;

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
