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
*****************************************************************************
*
*      GSM AMR-NB speech codec   R98   Version 7.5.0   March 2, 2001
*                                R99   Version 3.2.0
*                                REL-4 Version 4.0.0
*
*****************************************************************************
*
*      File             : bits2prm.h
*      Purpose          : Retrieves the vector of encoder parameters from
*                       : the received serial bits in a frame.
*
*****************************************************************************
*/
#ifndef bits2prm_h
#define bits2prm_h "$Id $"

/*
*****************************************************************************
*                         INCLUDE FILES
*****************************************************************************
*/
#include "typedef.h"
#include "mode.h"

#ifdef __cplusplus
extern "C"
{
#endif

    /*
    *****************************************************************************
    *                         DEFINITION OF DATA TYPES
    *****************************************************************************
    */

    /*
    *****************************************************************************
    *                         DECLARATION OF PROTOTYPES
    *****************************************************************************
    */
    /*
    **************************************************************************
    *
    *  Function    : Bits2prm
    *  Purpose     : Retrieves the vector of encoder parameters from
    *                the received serial bits in a frame.
    *  Returns     : void
    *
    **************************************************************************
    */
    OSCL_IMPORT_REF void Bits2prm(
        enum Mode mode,
        Word16 bits[],   /* input : serial bits, (244 + bfi)               */
        Word16 prm[]     /* output: analysis parameters, (57+1 parameters) */
    );

#ifdef __cplusplus
}
#endif

#endif
