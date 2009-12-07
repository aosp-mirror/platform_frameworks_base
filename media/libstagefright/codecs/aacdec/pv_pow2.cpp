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

 Filename: pv_pow2.c


------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    Int32 x             32-bit integer input  Q27

Output
    Int32               32-bit integer in Q25


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Implement the power base 2 for positive numbers lesser than 5.999999
------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/
#ifdef AAC_PLUS

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_pow2.h"
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
#define POW_2_TABLE_LENGTH          6
#define POW_2_TABLE_LENGTH_m_2      (POW_2_TABLE_LENGTH - 2)

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

#define R_SHIFT     29
#define Q_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

#define Q27fmt(x)   (Int32)(x*((Int32)1<<27) + (x>=0?0.5F:-0.5F))

const Int32 pow2_table[6] =
{
    Q_fmt(0.00224510927441F),   Q_fmt(0.00777943379416F),
    Q_fmt(0.05737929218747F),   Q_fmt(0.23918017179889F),
    Q_fmt(0.69345251849351F),   Q_fmt(0.99996347120248F)
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


/*
 *      z in Q27 format
 */

Int32 pv_pow2(Int32 z)
{
    const Int32 *pt_table = pow2_table;
    Int32 multiplier = 0;
    Int32 shift_factor;
    Int32 i;
    Int32 v_q;
    Int32 y;


    if (z > Q27fmt(1.0f))
    {
        v_q = z - (z & 0xF8000000);
        shift_factor =   z >> 27;
    }
    else
    {
        v_q = z;
        shift_factor = 0;
    }

    if (v_q < Q27fmt(0.5f))
    {
        v_q += Q27fmt(0.5f);
        multiplier = Q_fmt(0.70710678118655F);
    }

    v_q = v_q << 2;

    y  = fxp_mul32_Q29(*(pt_table++), v_q);

    for (i = POW_2_TABLE_LENGTH_m_2; i != 0; i--)
    {
        y += *(pt_table++);
        y  = fxp_mul32_Q29(y, v_q);
    }
    y += *(pt_table++);

    if (multiplier)
    {
        y = fxp_mul32_Q29(y, multiplier);
    }

    /*
     *  returns number on Q25
     */
    return (y >> (4 - shift_factor));

}

#endif
