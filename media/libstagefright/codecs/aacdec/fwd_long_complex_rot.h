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

 Pathname: ./include/fwd_long_complex_rot.h

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                   Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Header file for functions fwd_long_complex_rot


------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef FWD_LONG_COMPLEX_ROT_H
#define FWD_LONG_COMPLEX_ROT_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/
#define FWD_LONG_CX_ROT_LENGTH              256
#define TWICE_FWD_LONG_CX_ROT_LENGTH        (FWD_LONG_CX_ROT_LENGTH<<1)
#define LONG_WINDOW_LENGTH                  1024
#define LONG_WINDOW_LENGTH_m_1              (LONG_WINDOW_LENGTH - 1)
#define TWICE_LONG_WINDOW_LENGTH_m_1        ((LONG_WINDOW_LENGTH<<1) - 1)

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



Int fwd_long_complex_rot(
    Int32 *Data_in,
    Int32 *Data_out,
    Int32  max);

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif  /* FWD_LONG_COMPLEX_ROT_H */
