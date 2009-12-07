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
------------------------------------------------------------------------------
   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pvmp3_decode_huff_cw.h

   Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef PVMP3_DECODE_HUFF_CW_H
#define PVMP3_DECODE_HUFF_CW_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_audio_type_defs.h"
#include "pvmp3_dec_defs.h"

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

#ifdef __cplusplus
extern "C"
{
#endif

    uint16 pvmp3_decode_huff_cw_tab0(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab1(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab2(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab3(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab5(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab6(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab7(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab8(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab9(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab10(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab11(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab12(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab13(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab15(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab16(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab24(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab32(tmp3Bits *);
    uint16 pvmp3_decode_huff_cw_tab33(tmp3Bits *);
#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif

