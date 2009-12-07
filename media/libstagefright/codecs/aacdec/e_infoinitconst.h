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

 Pathname: ./include/e_infoinitConst.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 enum for Infoinit related constants

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef E_INFOINIT_CONST_H
#define E_INFOINIT_CONST_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "chans.h"

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
typedef enum
{
    /* block switch windows for single channels or channel pairs */
    Winds   = Chans,

    /* average channel block length, bytes */
    Avjframe    = 341,

    TEXP    = 128,      /* size of exp cache table */
    MAX_IQ_TBL  = 128,      /* size of inv quant table */
    MAXFFT  = LN4

} infoinitConst;


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
