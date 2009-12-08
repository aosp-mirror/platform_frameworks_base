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

 Filename: s_sbr_header_data.h
 Funtions:

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:
------------------------------------------------------------------------------


----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_SBR_HEADER_DATA_H
#define S_SBR_HEADER_DATA_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "e_sbr_header_status.h"
#include    "e_sbr_master_status.h"
#include    "e_sr_mode.h"
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
    SBR_HEADER_STATUS status;      /* the current status of the header     */
    SBR_MASTER_STATUS masterStatus;/* status of v_k_master freq table      */

    /* Changes in these variables indicates an error */
    Int32 crcEnable;
    SR_MODE sampleRateMode;
    Int32 ampResolution;

    /* Changes in these variables causes a reset of the decoder */
    Int32 startFreq;
    Int32 stopFreq;
    Int32 xover_band;
    Int32 freqScale;
    Int32 alterScale;
    Int32 noise_bands;               /* noise bands per octave, read from bitstream */

    /* Helper variable*/
    Int32 noNoiseBands;              /* actual number of noise bands to read from the bitstream */

    Int32 limiterBands;
    Int32 limiterGains;
    Int32 interpolFreq;
    Int32 smoothingLength;
}
SBR_HEADER_DATA;

typedef SBR_HEADER_DATA *HANDLE_SBR_HEADER_DATA;

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


