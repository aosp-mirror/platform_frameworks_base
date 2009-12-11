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



 Pathname: ./audio/gsm-amr/c/src/q_gain_c.c
 Functions: q_gain_code

     Date: 02/05/2002

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Updated template used to PV coding template.
 Changed to accept the pOverflow flag for EPOC compatibility.

 Description:
 (1) Removed optimization -- mult(i, 3, pOverflow) is NOT the same as adding
     i to itself 3 times.  The reason is because the mult function does a
     right shift by 15, which will obliterate smaller numbers.

 Description:
              1. Eliminated unused include files.
              2. Eliminated math operations that unnecessary checked for
                 saturation by evaluating the operands

 Description:  Replaced "int" and/or "char" with OSCL defined types.

 Description: Added #ifdef __cplusplus around extern'ed table.

 Description:

------------------------------------------------------------------------------
 MODULE DESCRIPTION

    Scalar quantization of the innovative codebook gain.

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "q_gain_c.h"
#include "mode.h"
#include "oper_32b.h"
#include "basic_op.h"
#include "log2.h"
#include "pow2.h"

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
#define NB_QUA_CODE 32

    /*----------------------------------------------------------------------------
    ; LOCAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; LOCAL VARIABLE DEFINITIONS
    ; Variable declaration - defined here and used outside this module
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/
    extern const Word16 qua_gain_code[NB_QUA_CODE*3];

    /*--------------------------------------------------------------------------*/
#ifdef __cplusplus
}
#endif

/*
------------------------------------------------------------------------------
 FUNCTION NAME: q_gain_code
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    mode -- enum Mode -- AMR mode
    exp_gcode0 -- Word16 -- predicted CB gain (exponent),  Q0
    frac_gcode0 -- Word16 -- predicted CB gain (fraction),  Q15
    gain -- Pointer to Word16 -- quantized fixed codebook gain, Q1

 Outputs:
    gain -- Pointer to Word16 -- quantized fixed codebook gain, Q1

    qua_ener_MR122 -- Pointer to Word16 -- quantized energy error, Q10
                                           (for MR122 MA predictor update)

    qua_ener -- Pointer to Word16 -- quantized energy error,        Q10
                                     (for other MA predictor update)

    pOverflow -- Pointer to Flag -- overflow indicator
 Returns:
    quantization index -- Word16 -- Q0

 Global Variables Used:
    qua_gain_code[]

 Local Variables Needed:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Scalar quantization of the innovative codebook gain.

------------------------------------------------------------------------------
 REQUIREMENTS

 None

------------------------------------------------------------------------------
 REFERENCES

 q_gain_c.c, UMTS GSM AMR speech codec, R99 - Version 3.2.0, March 2, 2001

------------------------------------------------------------------------------
 PSEUDO-CODE


------------------------------------------------------------------------------
 RESOURCES USED [optional]

 When the code is written for a specific target processor the
 the resources used should be documented below.

 HEAP MEMORY USED: x bytes

 STACK MEMORY USED: x bytes

 CLOCK CYCLES: (cycle count equation for this function) + (variable
                used to represent cycle count for each subroutine
                called)
     where: (cycle count variable) = cycle count for [subroutine
                                     name]

------------------------------------------------------------------------------
 CAUTION [optional]
 [State any special notes, constraints or cautions for users of this function]

------------------------------------------------------------------------------
*/

Word16 q_gain_code(         /* o  : quantization index,            Q0  */
    enum Mode mode,         /* i  : AMR mode                           */
    Word16 exp_gcode0,      /* i  : predicted CB gain (exponent),  Q0  */
    Word16 frac_gcode0,     /* i  : predicted CB gain (fraction),  Q15 */
    Word16 *gain,           /* i/o: quantized fixed codebook gain, Q1  */
    Word16 *qua_ener_MR122, /* o  : quantized energy error,        Q10 */
    /*      (for MR122 MA predictor update)    */
    Word16 *qua_ener,       /* o  : quantized energy error,        Q10 */
    /*      (for other MA predictor update)    */
    Flag   *pOverflow
)
{
    const Word16 *p;
    Word16 i;
    Word16 index;
    Word16 gcode0;
    Word16 err;
    Word16 err_min;
    Word16 g_q0;
    Word16 temp;

    if (mode == MR122)
    {
        g_q0 = *gain >> 1; /* Q1 -> Q0 */
    }
    else
    {
        g_q0 = *gain;
    }

    /*-------------------------------------------------------------------*
     *  predicted codebook gain                                          *
     *  ~~~~~~~~~~~~~~~~~~~~~~~                                          *
     *  gc0     = Pow2(int(d)+frac(d))                                   *
     *          = 2^exp + 2^frac                                         *
     *                                                                   *
     *-------------------------------------------------------------------*/

    gcode0 = (Word16) Pow2(exp_gcode0, frac_gcode0, pOverflow);  /* predicted gain */

    if (mode == MR122)
    {
        gcode0 = shl(gcode0, 4, pOverflow);
    }
    else
    {
        gcode0 = shl(gcode0, 5, pOverflow);
    }

    /*-------------------------------------------------------------------*
     *                   Search for best quantizer                        *
     *-------------------------------------------------------------------*/

    p = &qua_gain_code[0];
    err_min = ((Word32)gcode0 * *(p++)) >> 15;
    err_min =  g_q0 - err_min;
    if (err_min < 0)
    {
        err_min = -err_min;
    }

    p += 2;                                  /* skip quantized energy errors */
    index = 0;

    for (i = 1; i < NB_QUA_CODE; i++)
    {
        err = ((Word32)gcode0 * *(p++)) >> 15;
        err =  g_q0 - err;

        if (err < 0)
        {
            err = -err;
        }

        p += 2;                              /* skip quantized energy error */

        if (err < err_min)
        {
            err_min = err;
            index = i;
        }
    }

    temp = index + (index << 1);

    p = &qua_gain_code[temp];

    temp  = (gcode0 * *(p++)) >> 15;
    if (mode == MR122)
    {
        *gain =  temp << 1;
    }
    else
    {
        *gain = temp;
    }

    /* quantized error energies (for MA predictor update) */
    *qua_ener_MR122 = *p++;
    *qua_ener = *p;

    return index;
}
