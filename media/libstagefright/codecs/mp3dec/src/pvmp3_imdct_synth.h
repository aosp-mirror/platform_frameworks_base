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

   Filename: pvmp3_imdct_synth.h

   Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION


------------------------------------------------------------------------------
 REFERENCES

 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension

------------------------------------------------------------------------------
*/
/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/

#ifndef PVMP3_IMDCT_SYNTH_H
#define PVMP3_IMDCT_SYNTH_H


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_dec_defs.h"
#include "pvmp3_audio_type_defs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES AND SIMPLE TYPEDEF'S
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/


#ifdef __cplusplus
extern "C"
{
#endif

    void pvmp3_imdct_synth(int32 in[SUBBANDS_NUMBER*FILTERBANK_BANDS],
    int32 overlap[SUBBANDS_NUMBER*FILTERBANK_BANDS],
    uint32 blk_type,
    int16 mx_band,
    int32 used_freq_lines,
    int32 *Scratch_mem);

#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/

#endif



