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

 Filename: calc_auto_corr.h
 Funtions:

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:
------------------------------------------------------------------------------


 ----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef CALC_AUTO_CORR_H
#define CALC_AUTO_CORR_H

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


struct ACORR_COEFS
{
    Int32  r11r;
    Int32  r01r;
    Int32  r02r;
    Int32  r12r;
    Int32  r22r;
#ifdef HQ_SBR
    Int32  r01i;
    Int32  r02i;
    Int32  r12i;
#endif
    Int32  det;
};

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

#ifdef __cplusplus
extern "C"
{
#endif

    void calc_auto_corr_LC(struct ACORR_COEFS *ac,
    Int32  realBuf[][32],
    Int32  bd,
    Int32  len);


#ifdef HQ_SBR

    void calc_auto_corr(struct ACORR_COEFS *ac,
                        Int32  realBuf[][32],
                        Int32  imagBuf[][32],
                        Int32  bd,
                        Int32  len);

#endif


#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


