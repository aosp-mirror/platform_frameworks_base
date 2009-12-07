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
------------------------------------------------------------------------------
   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pvmp3_normalize.cpp

     Date: 10/02/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    Int32 x             32-bit integer non-zero input
Returns
    Int32 i             number of leading zeros on x


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Returns number of leading zeros on the non-zero input

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_audio_type_defs.h"
#include "pvmp3_normalize.h"

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

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

#if (defined(PV_ARM_V5)||defined(PV_ARM_V4))
#elif (defined(PV_ARM_GCC_V5)||defined(PV_ARM_GCC_V4))


/* function is inlined in header file */


#else

int32 pvmp3_normalize(int32 x)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int32 i;


    if (x > 0x0FFFFFFF)
    {
        i = 0;  /* most likely case */
    }
    else if (x > 0x00FFFFFF)
    {
        i = 3;  /* second most likely case */
    }
    else if (x > 0x0000FFFF)
    {
        i  = x > 0x000FFFFF ?  7 :  11;
    }
    else
    {
        if (x > 0x000000FF)
        {
            i  = x > 0x00000FFF ?  15 :  19;
        }
        else
        {
            i  = x > 0x0000000F ?  23 :  27;
        }
    }


    x <<= i;

    switch (x & 0x78000000)
    {
        case 0x08000000:
            i += 3;
            break;

        case 0x18000000:
        case 0x10000000:
            i += 2;
            break;
        case 0x28000000:
        case 0x20000000:
        case 0x38000000:
        case 0x30000000:
            i++;

        default:
            ;
    }

    return i;

}

#endif

