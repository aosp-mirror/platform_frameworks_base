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

 Filename: pv_sine.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 x             32-bit integer angle (in Q30) between 0 and pi/2


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Find the sine of a number between 0 and pi/2
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

#ifdef AAC_PLUS

#ifdef PARAMETRICSTEREO

#include "pv_audio_type_defs.h"
#include "fxp_mul32.h"
#include "pv_sine.h"

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

#define R_SHIFT     30

#define Q_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

const Int32 sin_table[9] =
{
    Q_fmt(0.00001724684028), Q_fmt(-0.00024606242846),
    Q_fmt(0.00007297328923), Q_fmt(0.00826706596417),
    Q_fmt(0.00003585160465), Q_fmt(-0.16667772526248),
    Q_fmt(0.00000174197440), Q_fmt(0.99999989138797),
    Q_fmt(0.00000000110513)
};


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


Int32 pv_sine(Int32 z)
{
    Int32 sine;
    Int32 i;
    const Int32 *pt_table = sin_table;
    Int32 sign = 0;

    if (z < 0)
    {
        z = -z;
        sign = 1;
    }

    if (z > Q_fmt(0.0015))
    {
        sine  = fxp_mul32_Q30(*(pt_table++), z);

        for (i = 7; i != 0; i--)
        {
            sine += *(pt_table++);
            sine  = fxp_mul32_Q30(sine, z);
        }

    }
    else
    {
        sine = z;  /*  better approximation in this range */
    }

    if (sign)
    {
        sine = -sine;
    }

    return sine;
}



Int32 pv_cosine(Int32 z)
{
    Int32 cosine;

    if (z < 0)
    {
        z = -z;     /* sign does not play a role in cosine */
    }

    if (z > Q_fmt(0.0015))
    {
        z = Q_fmt(1.57079632679490) - z;   /* pi/2 - z */

        cosine  = pv_sine(z);
    }
    else
    {   /*  better approximation in this range  */
        cosine = Q_fmt(0.99999999906868) - (fxp_mul32_Q30(z, z) >> 1);
    }

    return cosine;
}

#endif

#endif


