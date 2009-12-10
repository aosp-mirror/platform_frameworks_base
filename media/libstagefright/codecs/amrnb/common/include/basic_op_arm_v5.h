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
 Pathname: ./include/basic_op_arm_v5.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file includes all the ARM-V5 based basicop.c functions.

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef BASIC_OP_ARM_V5_H
#define BASIC_OP_ARM_V5_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "basicop_malloc.h"

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

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/


    /*
    ------------------------------------------------------------------------------
     FUNCTION NAME: L_add
    ------------------------------------------------------------------------------
     INPUT AND OUTPUT DEFINITIONS

     Inputs:
        L_var1 = 32 bit long signed integer (Word32) whose value falls
                 in the range : 0x8000 0000 <= L_var1 <= 0x7fff ffff.

        L_var2 = 32 bit long signed integer (Word32) whose value falls
                 in the range : 0x8000 0000 <= L_var1 <= 0x7fff ffff.

        pOverflow = pointer to overflow (Flag)

     Outputs:
        pOverflow -> 1 if the 32 bit add operation resulted in overflow

     Returns:
        L_sum = 32-bit sum of L_var1 and L_var2 (Word32)
    */

    __inline Word32 L_add(register Word32 L_var1, register Word32 L_var2, Flag *pOverflow)
    {
        Word32 result;

        OSCL_UNUSED_ARG(pOverflow);
        __asm
        {
            QADD result, L_var1, L_var2
        }
        return(result);
    }

    /*
    ------------------------------------------------------------------------------
     FUNCTION NAME: L_sub
    ------------------------------------------------------------------------------
     INPUT AND OUTPUT DEFINITIONS

     Inputs:
        L_var1 = 32 bit long signed integer (Word32) whose value falls
                 in the range : 0x8000 0000 <= L_var1 <= 0x7fff ffff.

        L_var2 = 32 bit long signed integer (Word32) whose value falls
                 in the range : 0x8000 0000 <= L_var1 <= 0x7fff ffff.

        pOverflow = pointer to overflow (Flag)

     Outputs:
        pOverflow -> 1 if the 32 bit add operation resulted in overflow

     Returns:
        L_diff = 32-bit difference of L_var1 and L_var2 (Word32)
    */
    __inline Word32 L_sub(Word32 L_var1, Word32 L_var2, Flag *pOverflow)
    {
        Word32 result;

        OSCL_UNUSED_ARG(pOverflow);

        __asm
        {
            QSUB result, L_var1, L_var2
        }

        return(result);

    }


    /*
    ------------------------------------------------------------------------------
     FUNCTION NAME: L_mac
    ------------------------------------------------------------------------------
     INPUT AND OUTPUT DEFINITIONS

     Inputs:
        L_var3 = 32 bit long signed integer (Word32) whose value falls
                 in the range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.
        var1 = 16 bit short signed integer (Word16) whose value falls in
               the range : 0xffff 8000 <= var1 <= 0x0000 7fff.
        var2 = 16 bit short signed integer (Word16) whose value falls in
               the range : 0xffff 8000 <= var2 <= 0x0000 7fff.

        pOverflow = pointer to overflow (Flag)

     Outputs:
        pOverflow -> 1 if the 32 bit add operation resulted in overflow

     Returns:
        result = 32-bit result of L_var3 + (var1 * var2)(Word32)
    */
    __inline Word32 L_mac(Word32 L_var3, Word16 var1, Word16 var2, Flag *pOverflow)
    {
        Word32 result;
        Word32 L_sum;

        OSCL_UNUSED_ARG(pOverflow);

        __asm {SMULBB result, var1, var2}
        __asm {QDADD L_sum, L_var3, result}
        return (L_sum);
    }

    /*
    ------------------------------------------------------------------------------
     FUNCTION NAME: L_mult
    ------------------------------------------------------------------------------
     INPUT AND OUTPUT DEFINITIONS

     Inputs:
        L_var1 = 16 bit short signed integer (Word16) whose value falls in
               the range : 0xffff 8000 <= var1 <= 0x0000 7fff.

        L_var2 = 16 bit short signed integer (Word16) whose value falls in
               the range : 0xffff 8000 <= var1 <= 0x0000 7fff.

        pOverflow = pointer to overflow (Flag)

     Outputs:
        pOverflow -> 1 if the 32 bit add operation resulted in overflow

     Returns:
        L_product = 32-bit product of L_var1 and L_var2 (Word32)
    */
    __inline Word32 L_mult(Word16 var1, Word16 var2, Flag *pOverflow)
    {
        Word32 result;
        Word32 product;

        OSCL_UNUSED_ARG(pOverflow);

        __asm
        {
            SMULBB product, var1, var2
            QADD   result, product, product
        }

        return (result);
    }


    /*
    ------------------------------------------------------------------------------
     FUNCTION NAME: L_msu
    ------------------------------------------------------------------------------
     INPUT AND OUTPUT DEFINITIONS

     Inputs:
        L_var3 = 32 bit long signed integer (Word32) whose value falls
                 in the range : 0x8000 0000 <= L_var3 <= 0x7fff ffff.

        var1 = 16 bit short signed integer (Word16) whose value falls in
               the range : 0xffff 8000 <= var1 <= 0x0000 7fff.
        var2 = 16 bit short signed integer (Word16) whose value falls in
               the range : 0xffff 8000 <= var2 <= 0x0000 7fff.

        pOverflow = pointer to overflow (Flag)

     Outputs:
        pOverflow -> 1 if the 32 bit operation resulted in overflow

     Returns:
        result = 32-bit result of L_var3 - (var1 * var2)
    */
    __inline Word32 L_msu(Word32 L_var3, Word16 var1, Word16 var2, Flag *pOverflow)
    {
        Word32 product;
        Word32 result;

        OSCL_UNUSED_ARG(pOverflow);

        __asm
        {
            SMULBB product, var1, var2
            QDSUB  result, L_var3, product
        }

        return (result);
    }

    /*
    ------------------------------------------------------------------------------
     FUNCTION NAME: Mpy_32
    ------------------------------------------------------------------------------
     INPUT AND OUTPUT DEFINITIONS

     Inputs:
        L_var1_hi = most significant word of first input (Word16).
        L_var1_lo = least significant word of first input (Word16).
        L_var2_hi = most significant word of second input (Word16).
        L_var2_lo = least significant word of second input (Word16).

        pOverflow = pointer to overflow (Flag)

     Outputs:
        pOverflow -> 1 if the 32 bit multiply operation resulted in overflow

     Returns:
        L_product = 32-bit product of L_var1 and L_var2 (Word32)
    */
    __inline Word32 Mpy_32(Word16 L_var1_hi, Word16 L_var1_lo, Word16 L_var2_hi,
                           Word16 L_var2_lo, Flag   *pOverflow)

    {

        Word32 L_product;
        Word32 L_sum;
        Word32 product32;

        OSCL_UNUSED_ARG(pOverflow);

        __asm
        {
            SMULBB L_product, L_var1_hi, L_var2_hi
            QDADD L_product, 0, L_product
            SMULBB product32, L_var1_hi, L_var2_lo
        }
        product32 >>= 15;
        __asm
        {
            QDADD L_sum, L_product, product32
        }
        L_product = L_sum;
        __asm
        {
            SMULBB product32, L_var1_lo, L_var2_hi
        }
        product32 >>= 15;
        __asm
        {
            QDADD L_sum, L_product, product32
        }
        return (L_sum);
    }

    /*
    ------------------------------------------------------------------------------
     FUNCTION NAME: Mpy_32_16
    ------------------------------------------------------------------------------
     INPUT AND OUTPUT DEFINITIONS

     Inputs:
        L_var1_hi = most significant 16 bits of 32-bit input (Word16).
        L_var1_lo = least significant 16 bits of 32-bit input (Word16).
        var2  = 16-bit signed integer (Word16).

        pOverflow = pointer to overflow (Flag)

     Outputs:
        pOverflow -> 1 if the 32 bit product operation resulted in overflow

     Returns:
        product = 32-bit product of the 32-bit L_var1 and 16-bit var1 (Word32)
    */
    __inline Word32 Mpy_32_16(Word16 L_var1_hi,
                              Word16 L_var1_lo,
                              Word16 var2,
                              Flag *pOverflow)
    {

        Word32 L_product;
        Word32 L_sum;
        Word32 result;

        OSCL_UNUSED_ARG(pOverflow);

        __asm {SMULBB L_product, L_var1_hi, var2}
        __asm {QDADD L_product, 0, L_product}
        __asm {SMULBB result, L_var1_lo, var2}
        result >>= 15;
        __asm {QDADD L_sum, L_product, result}
        return (L_sum);
    }

    /*
    ------------------------------------------------------------------------------
     FUNCTION NAME: mult
    ------------------------------------------------------------------------------
     INPUT AND OUTPUT DEFINITIONS

     Inputs:
        var1 = 16 bit short signed integer (Word16) whose value falls in
               the range : 0xffff 8000 <= var1 <= 0x0000 7fff.

        var2 = 16 bit short signed integer (Word16) whose value falls in
               the range : 0xffff 8000 <= var2 <= 0x0000 7fff.

        pOverflow = pointer to overflow (Flag)

     Outputs:
        pOverflow -> 1 if the add operation resulted in overflow

     Returns:
        product = 16-bit limited product of var1 and var2 (Word16)
    */
    __inline Word16 mult(Word16 var1, Word16 var2, Flag *pOverflow)
    {
        Word32 product;

        OSCL_UNUSED_ARG(pOverflow);

        __asm
        {
            SMULBB product, var1, var2
            MOV    product, product, ASR #15
            CMP    product, 0x7FFF
            MOVGE  product, 0x7FFF
        }

        return ((Word16) product);
    }

    __inline Word32 amrnb_fxp_mac_16_by_16bb(Word32 L_var1, Word32 L_var2, Word32 L_var3)
    {
        Word32 result;
        __asm
        {
            smlabb result, L_var1, L_var2, L_var3
        }
        return result;
    }

    __inline Word32 amrnb_fxp_msu_16_by_16bb(Word32 L_var1, Word32 L_var2, Word32 L_var3)
    {
        Word32 result;
        __asm
        {
            rsb L_var1, L_var1, #0
            smlabb result, L_var1, L_var2, L_var3
        }
        return result;
    }


    /*----------------------------------------------------------------------------
    ; END
    ----------------------------------------------------------------------------*/
#ifdef __cplusplus
}
#endif

#endif /* BASIC_OP_ARM_V5_H */
