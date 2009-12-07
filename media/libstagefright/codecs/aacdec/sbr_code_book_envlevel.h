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

 Pathname: sbr_code_book_envlevel.h

------------------------------------------------------------------------------
 REVISION HISTORY


------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 this file declares the scalefactor bands for all sampling rates

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef SBR_CODE_BOOK_ENVLEVEL_H
#define SBR_CODE_BOOK_ENVLEVEL_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/
    extern const Char bookSbrEnvLevel10T[120][2];
    extern const Char bookSbrEnvLevel10F[120][2];
    extern const Char bookSbrEnvBalance10T[48][2];
    extern const Char bookSbrEnvBalance10F[48][2];
    extern const Char bookSbrEnvLevel11T[62][2];
    extern const Char bookSbrEnvLevel11F[62][2];
    extern const Char bookSbrEnvBalance11T[24][2];
    extern const Char bookSbrEnvBalance11F[24][2];
    extern const Char bookSbrNoiseLevel11T[62][2];
    extern const Char bookSbrNoiseBalance11T[24][2];

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

#ifdef __cplusplus
}
#endif



#endif


