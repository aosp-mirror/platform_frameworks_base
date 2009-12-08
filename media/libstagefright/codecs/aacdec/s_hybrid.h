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

 Filename: s_hybrid.h
 Funtions:

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:
------------------------------------------------------------------------------


----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_HYBRID_H
#define S_HYBRID_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include    "ps_constants.h"
#include    "pv_audio_type_defs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/
#define HYBRID_FILTER_LENGTH        13
#define HYBRID_FILTER_LENGTH_m_1    12
#define HYBRID_FILTER_DELAY         6

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

    HYBRID_2_REAL = 2,
    HYBRID_4_CPLX = 4,
    HYBRID_8_CPLX = 8

} HYBRID_RES;

/*----------------------------------------------------------------------------
; STRUCTURES TYPEDEF'S
----------------------------------------------------------------------------*/
typedef struct
{
    Int32   nQmfBands;
    Int32   *pResolution;
    Int32   qmfBufferMove;

    Int32 **mQmfBufferReal;
    Int32 **mQmfBufferImag;
    Int32 *mTempReal;
    Int32 *mTempImag;


} HYBRID;

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/

#endif      /*  S_HYBRID_H */
