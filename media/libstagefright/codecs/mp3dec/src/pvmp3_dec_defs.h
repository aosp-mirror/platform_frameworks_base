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

   Filename: pvmp3_dec_defs.h

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file has the mp3 decoder common defines.

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef PVMP3_DEC_DEFS_H
#define PVMP3_DEC_DEFS_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pvmp3_audio_type_defs.h"
#include "pvmp3decoder_api.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/
#define module(x, POW2)   ((x)&(POW2-1))

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/
#define BUFSIZE   8192   // big enough to hold 4608 bytes == biggest mp3 frame

#define CHAN           2
#define GRAN           2


#define SUBBANDS_NUMBER        32
#define FILTERBANK_BANDS       18
#define HAN_SIZE              512


/* MPEG Header Definitions - ID Bit Values */

#define MPEG_1              0
#define MPEG_2              1
#define MPEG_2_5            2
#define INVALID_VERSION     -1

/* MPEG Header Definitions - Mode Values */

#define MPG_MD_STEREO           0
#define MPG_MD_JOINT_STEREO     1
#define MPG_MD_DUAL_CHANNEL     2
#define MPG_MD_MONO             3



#define LEFT        0
#define RIGHT       1


#define SYNC_WORD         (int32)0x7ff
#define SYNC_WORD_LNGTH   11

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
#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /* Header Information Structure */

    typedef struct
    {
        int32 version_x;
        int32 layer_description;
        int32 error_protection;
        int32 bitrate_index;
        int32 sampling_frequency;
        int32 padding;
        int32 extension;
        int32 mode;
        int32 mode_ext;
        int32 copyright;
        int32 original;
        int32 emphasis;
    } mp3Header;


    /* Layer III side information. */

    typedef  struct
    {
        uint32 part2_3_length;
        uint32 big_values;
        int32 global_gain;
        uint32 scalefac_compress;
        uint32 window_switching_flag;
        uint32 block_type;
        uint32 mixed_block_flag;
        uint32 table_select[3];
        uint32 subblock_gain[3];
        uint32 region0_count;
        uint32 region1_count;
        uint32 preflag;
        uint32 scalefac_scale;
        uint32 count1table_select;

    } granuleInfo;

    typedef  struct
    {
        uint32      scfsi[4];
        granuleInfo gran[2];

    } channelInfo;

    /* Layer III side info. */

    typedef struct
    {
        uint32      main_data_begin;
        uint32      private_bits;
        channelInfo ch[2];

    } mp3SideInfo;

    /* Layer III scale factors. */
    typedef struct
    {
        int32 l[23];            /* [cb] */
        int32 s[3][13];         /* [window][cb] */

    } mp3ScaleFactors;


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



