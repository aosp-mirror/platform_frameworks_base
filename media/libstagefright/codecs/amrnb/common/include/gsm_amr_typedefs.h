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

 Pathname: codecs/audio/gsm_amr/gsm_two_way/c/include/gsm_amr_typedefs.h

------------------------------------------------------------------------------
 REVISION HISTORY


 Description: Removed unused defintions and corrected ifdef, that depended on
              incorrect typedef

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file contains the definition of the amr codec types.

------------------------------------------------------------------------------
*/
#ifndef GSM_AMR_TYPEDEFS_H
#define GSM_AMR_TYPEDEFS_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include <stdint.h>

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

typedef int8_t        Word8;
typedef uint8_t       UWord8;


/*----------------------------------------------------------------------------
; Define 16 bit signed and unsigned words
----------------------------------------------------------------------------*/

typedef int16_t       Word16;
typedef uint16_t      UWord16;

/*----------------------------------------------------------------------------
; Define 32 bit signed and unsigned words
----------------------------------------------------------------------------*/

typedef int32_t       Word32;
typedef uint32_t      UWord32;


/*----------------------------------------------------------------------------
; Define boolean type
----------------------------------------------------------------------------*/
typedef int     Bool;

#ifndef FALSE
#define FALSE       0
#endif

#ifndef TRUE
#define TRUE        1
#endif

#ifndef OFF
#define OFF     0
#endif

#ifndef ON
#define ON      1
#endif

#ifndef NO
#define NO      0
#endif

#ifndef YES
#define YES     1
#endif

#ifndef SUCCESS
#define SUCCESS     0
#endif

#ifndef  NULL
#define  NULL       0
#endif

typedef int32_t     Flag;

#endif   /*  GSM_AMR_TYPEDEFS_H */
