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

 Filename: e_sbr_error.h
 Funtions:


------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:
------------------------------------------------------------------------------


----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef E_SBR_ERROR_H
#define E_SBR_ERROR_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/
#define HANDLE_ERROR_INFO Int32
#define noError 0


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
    SBRDEC_OK = 0,
    SBRDEC_NOSYNCH,
    SBRDEC_ILLEGAL_PROGRAM,
    SBRDEC_ILLEGAL_TAG,
    SBRDEC_ILLEGAL_CHN_CONFIG,
    SBRDEC_ILLEGAL_SECTION,
    SBRDEC_ILLEGAL_SCFACTORS,
    SBRDEC_ILLEGAL_PULSE_DATA,
    SBRDEC_MAIN_PROFILE_NOT_IMPLEMENTED,
    SBRDEC_GC_NOT_IMPLEMENTED,
    SBRDEC_ILLEGAL_PLUS_ELE_ID,
    SBRDEC_CREATE_ERROR,
    SBRDEC_NOT_INITIALIZED,
    SBRDEC_TOO_MANY_SBR_ENVELOPES,
    SBRDEC_INVALID_BITSTREAM
}
SBR_ERROR;

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


