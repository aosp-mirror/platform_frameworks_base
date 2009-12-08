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

 Pathname: sfb.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: created to declare scalefactor bands for all sampling rates

 Description: Change short to Int16

 Description: Eliminated declaration of sfb_96_128 array, values are equal
              to array sfb_64_128

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 this file declares the scalefactor bands for all sampling rates

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef SFB_H
#define SFB_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "s_sr_info.h"
#include    "e_progconfigconst.h"

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
extern  const Int16 sfb_96_1024[];  /* 41 scfbands */

extern const Int16 sfb_64_1024[];  /* 41 scfbands 47 */

extern const Int16 sfb_64_128[];  /* 12 scfbands */


extern const Int16 sfb_48_1024[]; /* 49 scfbands */

extern const Int16 sfb_48_128[];  /* 14 scfbands */

extern const Int16 sfb_32_1024[];  /* 51 scfbands */

extern const Int16 sfb_24_1024[];  /* 47 scfbands */

extern const Int16 sfb_24_128[];  /* 15 scfbands */

extern const Int16 sfb_16_1024[];  /* 43 scfbands */

extern const Int16 sfb_16_128[];  /* 15 scfbands */

extern const Int16 sfb_8_1024[];  /* 40 scfbands */

extern const Int16 sfb_8_128[];  /* 15 scfbands */

extern const SR_Info samp_rate_info[12];

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

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


