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

 Pathname: s_MC_Info.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: (1) use enum type for audioObjectType (2) update revision history

 Who:                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file defines the structure, MC_Info

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_MC_INFO_H
#define S_MC_INFO_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_rawbitstreamconst.h"
#include "s_ch_info.h"
#include "chans.h"
#include "e_tmp4audioobjecttype.h"

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
    Int nch;        /* total number of audio channels */
    Int nfsce;      /* number of front SCE's pror to first front CPE */
    Int nfch;       /* number of front channels */
    Int nsch;       /* number of side channels */
    Int nbch;       /* number of back channels */
    Int nlch;       /* number of lfe channels */
    Int ncch;       /* number of valid coupling channels */
    tMP4AudioObjectType audioObjectType;    /* Should eventually be called object */
    Int sampling_rate_idx;

    Int implicit_channeling;
    Int  upsamplingFactor;
#ifdef AAC_PLUS
    bool bDownSampledSbr;
    Int HE_AAC_level;
#endif
    /* All AAC content should be aware of these flag */
    /*  AAC+ content Flag */
    Int sbrPresentFlag;
    /*  Enhanced AAC+ content Flag */
    Int psPresentFlag;
    tMP4AudioObjectType ExtendedAudioObjectType;    /* Should eventually be called object */

    Ch_Info ch_info[Chans];
} MC_Info;

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif

