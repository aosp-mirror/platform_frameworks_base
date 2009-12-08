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

 Filename: s_sbr_channel.h
 Funtions:

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:
------------------------------------------------------------------------------


----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_SBR_CHANNEL_H
#define S_SBR_CHANNEL_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include    "s_sbr_frame_data.h"
#include    "e_sbr_sync_state.h"

#ifdef PARAMETRICSTEREO
#include "s_ps_dec.h"

#endif
/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/
#define MAXNRELEMENTS 1
#define MAXNRSBRCHANNELS (MAXNRELEMENTS*2)

#ifdef PARAMETRICSTEREO
#define MAXNRQMFCHANNELS MAXNRSBRCHANNELS
#else
#define MAXNRQMFCHANNELS MAXNRSBRCHANNELS
#endif


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
    Int32 outFrameSize;
    SBR_SYNC_STATE syncState;
    SBR_FRAME_DATA frameData;

} SBR_CHANNEL;

typedef struct
{
    SBR_CHANNEL SbrChannel[MAXNRSBRCHANNELS];
    Int32 setStreamType;
#ifdef PARAMETRICSTEREO
    HANDLE_PS_DEC hParametricStereoDec;
    STRUCT_PS_DEC ParametricStereoDec;
#endif

} SBRDECODER_DATA;


/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


