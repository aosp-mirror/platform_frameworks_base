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


 Pathname: .audio/gsm-amr/c/src/dec_input_format_tab.c

     Date: 03/01/2002

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Renamed BytesThisFrame to WmfBytesPerFrame, changed its type
              from 'const short' to 'const int'. Added If2BytesPerFrame
              table for IF2 input format. Updated copyright year and I/O
              definition sections, and added reference document for IF2.

 Description: Renamed WmfBytesPerFrame to WmfDecBytesPerFrame, and
              If2BytesPerFrame to If2DecBytesPerFrame.

 Description: Added #ifdef __cplusplus and removed "extern" from table
              definition.

 Description: Put "extern" back.

 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    None

 Outputs:
    None

 Returns:
    None

 Global Variables Used:
    None

 Local Variables Needed:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This file contains the tables of the number of data bytes per codec mode in
 both WMF and IF2 input formats.

------------------------------------------------------------------------------
 REQUIREMENTS

 None

------------------------------------------------------------------------------
 REFERENCES

 [1] AMR Speech Codec Frame Structure, 3GPP TS 26.101 version 4.1.0 Release 4,
     June 2001

------------------------------------------------------------------------------
 PSEUDO-CODE


------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE: [stack count for this module] + [variable to represent
          stack usage for each subroutine called]

     where: [stack usage variable] = stack usage for [subroutine
         name] (see [filename].ext)

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES: [cycle count equation for this module] + [variable
           used to represent cycle count for each subroutine
           called]

     where: [cycle count variable] = cycle count for [subroutine
        name] (see [filename].ext)

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "typedef.h"
#include "amrdecode.h"

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
    ; Include all pre-processor statements here. Include conditional
    ; compile variables also.
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; LOCAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/


    /*----------------------------------------------------------------------------
    ; LOCAL STORE/BUFFER/POINTER DEFINITIONS
    ; Variable declaration - defined here and used outside this module
    ----------------------------------------------------------------------------*/
    /* Table containing the number of core AMR data bytes for                */
    /* each codec mode for WMF input format(number excludes frame type byte) */
    const Word16 WmfDecBytesPerFrame[16] =
    {
        12, /* 4.75 */
        13, /* 5.15 */
        15, /* 5.90 */
        17, /* 6.70 */
        19, /* 7.40 */
        20, /* 7.95 */
        26, /* 10.2 */
        31, /* 12.2 */
        5, /* GsmAmr comfort noise */
        6, /* Gsm-Efr comfort noise */
        5, /* IS-641 comfort noise */
        5, /* Pdc-Efr comfort noise */
        0, /* future use */
        0, /* future use */
        0, /* future use */
        0 /* No transmission */
    };

    /* Table containing the number of core AMR data bytes for   */
    /* each codec mode for IF2 input format.                    */
    const Word16 If2DecBytesPerFrame[16] =
    {
        13, /* 4.75 */
        14, /* 5.15 */
        16, /* 5.90 */
        18, /* 6.70 */
        19, /* 7.40 */
        21, /* 7.95 */
        26, /* 10.2 */
        31, /* 12.2 */
        6, /* GsmAmr comfort noise */
        6, /* Gsm-Efr comfort noise */
        6, /* IS-641 comfort noise */
        6, /* Pdc-Efr comfort noise */
        0, /* future use */
        0, /* future use */
        0, /* future use */
        1 /* No transmission */
    };

    /*----------------------------------------------------------------------------
    ; EXTERNAL FUNCTION REFERENCES
    ; Declare functions defined elsewhere and referenced in this module
    ----------------------------------------------------------------------------*/


    /*----------------------------------------------------------------------------
    ; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/


    /*--------------------------------------------------------------------------*/
#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; Define all local variables
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; Function body here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; Return nothing or data or data pointer
----------------------------------------------------------------------------*/


