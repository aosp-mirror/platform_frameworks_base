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

 Filename: pv_log2.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

    Int32 x             32-bit integer input


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement the logarithm base 2 of a number
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


#include "pv_log2.h"
#include "fxp_mul32.h"

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

#define R_SHIFT     20
#define Q_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

const Int32 log_table[9] =
{
    Q_fmt(-0.00879832091331F),  Q_fmt(0.12022974263833F),
    Q_fmt(-0.72883958314294F),  Q_fmt(2.57909824242332F),
    Q_fmt(-5.90041216630330F),  Q_fmt(9.15023342527264F),
    Q_fmt(-9.90616619500413F),  Q_fmt(8.11228968755409F),
    Q_fmt(-3.41763474309898F)
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



Int32 pv_log2(Int32 z)
{
    const Int32 *pt_table = log_table;
    Int32 y;
    Int32 i;

    Int32 int_log2 = 0;

    if (z > Q_fmt(2.0f))
    {
        while (z > Q_fmt(2.0f))
        {
            z >>= 1;
            int_log2++;
        }
    }
    else if (z < Q_fmt(1.0f))
    {
        {
            while (z < Q_fmt(1.0f))
            {
                z <<= 1;
                int_log2--;
            }
        }
    }

    /*
     *  at this point, input limited to 1<x<2
     */

    if (z != Q_fmt(1.0f))
    {
        y  = fxp_mul32_Q20(*(pt_table++), z);

        for (i = 7; i != 0; i--)
        {
            y += *(pt_table++);
            y  = fxp_mul32_Q20(y, z);
        }

        y += *(pt_table++);
    }
    else
    {
        y = 0;
    }


    return (y + (int_log2 << 20));         /* Q20 */
}


#endif

