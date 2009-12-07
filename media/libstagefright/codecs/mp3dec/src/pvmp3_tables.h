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
   Filename: pvmp3_tables.h

   Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef PVMP3_TABLES_H
#define PVMP3_TABLES_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_dec_defs.h"
#include "pv_mp3_huffman.h"

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
#define Qfmt_28(a) (int32(double(0x10000000)*a))

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
    int16 l[23];
    int16 s[14];
} mp3_scaleFactorBandIndex;



/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

#ifdef __cplusplus
extern "C"
{
#endif

    extern const int32    mp3_s_freq[4][4];
    extern const int32    inv_sfreq[4];
    extern const int16    mp3_bitrate[3][15];
    extern const int32    power_one_third[513];

    extern const  mp3_scaleFactorBandIndex mp3_sfBandIndex[9];
    extern const int32 mp3_shortwindBandWidths[9][13];
    extern const int32 pqmfSynthWin[(HAN_SIZE/2) + 8];


    extern const uint16 huffTable_1[];
    extern const uint16 huffTable_2[];
    extern const uint16 huffTable_3[];
    extern const uint16 huffTable_5[];
    extern const uint16 huffTable_6[];
    extern const uint16 huffTable_7[];
    extern const uint16 huffTable_8[];
    extern const uint16 huffTable_9[];
    extern const uint16 huffTable_10[];
    extern const uint16 huffTable_11[];
    extern const uint16 huffTable_12[];
    extern const uint16 huffTable_13[];
    extern const uint16 huffTable_15[];
    extern const uint16 huffTable_16[];
    extern const uint16 huffTable_24[];
    extern const uint16 huffTable_32[];
    extern const uint16 huffTable_33[];


#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/

#endif
