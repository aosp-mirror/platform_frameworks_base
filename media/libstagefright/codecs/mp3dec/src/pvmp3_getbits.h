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

   Filename: pvmp3_getbits.h

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
#ifndef PVMP3_GETBITS_H
#define PVMP3_GETBITS_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pvmp3_dec_defs.h"
#include "s_mp3bits.h"
#include "pvmp3_audio_type_defs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/
#define INBUF_ARRAY_INDEX_SHIFT  (3)
#define INBUF_BIT_WIDTH         (1<<(INBUF_ARRAY_INDEX_SHIFT))
#define INBUF_BIT_MODULO_MASK   ((INBUF_BIT_WIDTH)-1)


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
#ifdef __cplusplus
extern "C"
{
#endif

    uint32 getNbits(tmp3Bits *pMainData,
    int32 neededBits);

    uint16 getUpTo9bits(tmp3Bits *pMainData,
                        int32 neededBits);

    uint32 getUpTo17bits(tmp3Bits *pMainData,
                         int32 neededBits);

    uint8 get1bit(tmp3Bits *pMainData);

#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/

#endif

