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
/****************************************************************************************
Portions of this file are derived from the following 3GPP standard:

    3GPP TS 26.073
    ANSI-C code for the Adaptive Multi-Rate (AMR) speech codec
    Available from http://www.3gpp.org

(C) 2004, 3GPP Organizational Partners (ARIB, ATIS, CCSA, ETSI, TTA, TTC)
Permission to distribute, modify and use this file under the standard license
terms listed above has been obtained from the copyright holder.
****************************************************************************************/
/*
------------------------------------------------------------------------------



 Filename: /audio/gsm-amr/c/include/amrencode.h

     Date: 02/01/2002

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Removed hard tabs from file.

 Description: Added #define for WMF and IF2, and updated function prototype.

 Description: Renamed WMF to AMR_WMF, IF2 to AMR_IF2, and added AMR_ETS.

 Description: Changed output_type to output_format.

 Description: Added external reference to WmfEncBytesPerFrame and
              If2EncBytesPerFrame tables.

 Description: Updated function prototype for AMREncode(). Added function
              prototype for AMREncodeInit, AMREncodeReset, and AMREncodeExit.
              Added #defines for TX SID frame formatting.

 Description:  Replaced "int" and/or "char" with OSCL defined types.

 Description: Moved _cplusplus #ifdef after Include section.

 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file contains the function prototype of AMREncode.

------------------------------------------------------------------------------
*/

#ifndef _AMRENCODE_H_
#define _AMRENCODE_H_

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "typedef.h"
#include "mode.h"
#include "frame_type_3gpp.h"

/*--------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; MACROS
    ; [Define module specific macros here]
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; [Include all pre-processor statements here.]
    ----------------------------------------------------------------------------*/
#define NUM_AMRSID_TXMODE_BITS     3
#define AMRSID_TXMODE_BIT_OFFSET   36
#define AMRSID_TXTYPE_BIT_OFFSET   35

    /* Output format types */
#define AMR_TX_WMF 0
#define AMR_TX_IF2 1
#define AMR_TX_ETS 2

    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; [Declare variables used in this module but defined elsewhere]
    ----------------------------------------------------------------------------*/
    extern const Word16 WmfEncBytesPerFrame[];
    extern const Word16 If2EncBytesPerFrame[];

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
    ; [List function prototypes here]
    ----------------------------------------------------------------------------*/
    Word16 AMREncodeInit(
        void **pEncStructure,
        void **pSidSyncStructure,
        Flag dtx_enable);

    Word16 AMREncodeReset(
        void *pEncStructure,
        void *pSidSyncStructure);

    void AMREncodeExit(
        void **pEncStructure,
        void **pSidSyncStructure);

    Word16 AMREncode(
        void *pEncState,
        void *pSidSyncState,
        enum Mode mode,
        Word16 *pEncInput,
        UWord8 *pEncOutput,
        enum Frame_Type_3GPP *p3gpp_frame_type,
        Word16 output_format
    );

#ifdef __cplusplus
}
#endif

#endif  /* _AMRENCODE_H_ */


