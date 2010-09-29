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

 Pathname: ./audio/gsm-amr/c/include/frame_type_3gpp.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Updated to new PV C header template.

 Description: Added #ifdef __cplusplus after Include section.

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file contains the definition of the 3GPP frame types.

------------------------------------------------------------------------------
*/

#ifndef FRAME_TYPE_3GPP_H
#define FRAME_TYPE_3GPP_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/


/*--------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

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

    enum Frame_Type_3GPP
    {
        AMR_475 = 0,        /* 4.75 kbps    */
        AMR_515,            /* 5.15 kbps    */
        AMR_59,             /* 5.9 kbps     */
        AMR_67,             /* 6.7 kbps     */
        AMR_74,             /* 7.4 kbps     */
        AMR_795,            /* 7.95 kbps    */
        AMR_102,            /* 10.2 kbps    */
        AMR_122,            /* 12.2 kbps    */
        AMR_SID,            /* GSM AMR DTX  */
        GSM_EFR_SID,        /* GSM EFR DTX  */
        TDMA_EFR_SID,       /* TDMA EFR DTX */
        PDC_EFR_SID,        /* PDC EFR DTX  */
        FOR_FUTURE_USE1,    /* Unused 1     */
        FOR_FUTURE_USE2,    /* Unused 2     */
        FOR_FUTURE_USE3,    /* Unused 3     */
        AMR_NO_DATA         /* No data      */
    };

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/
#ifdef __cplusplus
}
#endif


#endif  /* _FRAME_TYPE_3GPP_H_ */


