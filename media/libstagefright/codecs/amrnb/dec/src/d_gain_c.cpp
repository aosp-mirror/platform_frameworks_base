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



 Pathname: ./audio/gsm-amr/c/src/d_gain_c.c
 Functions: d_gain_c

     Date: 01/29/2002

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Updated include files and intput/output section. Changed .tab
              files to .c files.

 Description:  Replaced OSCL mem type functions and eliminated include
               files that now are chosen by OSCL definitions

 Description:  Replaced "int" and/or "char" with OSCL defined types.

 Description: Added #ifdef __cplusplus around extern'ed table.

 Description:

 ------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pred_state  = pointer to sturcture type gc_predState. MA predictor state
    mode        = AMR mode (MR795 or MR122) of type enum Mode
    index       = received quantization index of type Word16
    code[]      = pointer to innovation codevector of type Word16
    pOverflow= pointer to value indicating existence of overflow (Flag)

 Outputs:
    pred_state  = pointer to sturcture type gc_predState. MA predictor state
    gain_code   = pointer to decoded innovation gain of type Word16
    pOverflow = 1 if there is an overflow else it is zero.

 Returns:
    None.

 Global Variables Used:
    None

 Local Variables Needed:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

  Function    : d_gain_code
  Purpose     : Decode the fixed codebook gain using the received index.

------------------------------------------------------------------------------
 REQUIREMENTS



------------------------------------------------------------------------------
 REFERENCES

 d_gain_c.c, UMTS GSM AMR speech codec, R99 - Version 3.2.0, March 2, 2001

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
#include "d_gain_c.h"
#include "typedef.h"
#include "mode.h"

#include "oper_32b.h"
#include "cnst.h"
#include "log2.h"
#include "pow2.h"
#include "gc_pred.h"

#include "basic_op.h"

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


    /*----------------------------------------------------------------------------
    ; EXTERNAL FUNCTION REFERENCES
    ; Declare functions defined elsewhere and referenced in this module
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/
    extern const Word16 qua_gain_code[];


    /*--------------------------------------------------------------------------*/
#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
void d_gain_code(
    gc_predState *pred_state, /* i/o : MA predictor state               */
    enum Mode mode,           /* i   : AMR mode (MR795 or MR122)        */
    Word16 index,             /* i   : received quantization index      */
    Word16 code[],            /* i   : innovation codevector            */
    Word16 *gain_code,        /* o   : decoded innovation gain          */
    Flag   *pOverflow
)
{
    Word16 gcode0, exp, frac;
    const Word16 *p;
    Word16 qua_ener_MR122, qua_ener;
    Word16 exp_inn_en;
    Word16 frac_inn_en;
    Word32 L_tmp;
    Word16 tbl_tmp;
    Word16 temp;
    /*-------------- Decode codebook gain ---------------*/

    /*-------------------------------------------------------------------*
     *  predict codebook gain                                            *
     *  ~~~~~~~~~~~~~~~~~~~~~                                            *
     *  gc0     = Pow2(int(d)+frac(d))                                   *
     *          = 2^exp + 2^frac                                         *
     *                                                                   *
     *-------------------------------------------------------------------*/

    gc_pred(pred_state, mode, code, &exp, &frac,
            &exp_inn_en, &frac_inn_en, pOverflow);

    index &= 31;                    /* index < 32, to avoid buffer overflow */
    tbl_tmp = index + (index << 1);

    p = &qua_gain_code[tbl_tmp];

    /* Different scalings between MR122 and the other modes */
    temp = sub((Word16)mode, (Word16)MR122, pOverflow);
    if (temp == 0)
    {
        gcode0 = (Word16)(Pow2(exp, frac, pOverflow));    /* predicted gain */
        gcode0 = shl(gcode0, 4, pOverflow);
        *gain_code = shl(mult(gcode0, *p++, pOverflow), 1, pOverflow);
    }
    else
    {
        gcode0 = (Word16)(Pow2(14, frac, pOverflow));
        L_tmp = L_mult(*p++, gcode0, pOverflow);
        L_tmp = L_shr(L_tmp, sub(9, exp, pOverflow), pOverflow);
        *gain_code = extract_h(L_tmp);          /* Q1 */
    }

    /*-------------------------------------------------------------------*
     *  update table of past quantized energies                          *
     *  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~                          *
     *-------------------------------------------------------------------*/
    qua_ener_MR122 = *p++;
    qua_ener = *p++;
    gc_pred_update(pred_state, qua_ener_MR122, qua_ener);

    return;
}




