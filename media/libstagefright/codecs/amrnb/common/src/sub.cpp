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

 Filename: /audio/gsm_amr/c/src/sub.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Created separate file for the sub function. Sync'ed up with the
          current template and fixed tabs.

 Description: Changed all occurrences of L_diff to diff, deleted "short" in
          the definition of var1 and var2, and fixed the range values.

 Description: Changed function prototype passing in a pointer to overflow flag
              instead of using global data.

 Description: Changes made per formal review comments.
              1. Changed the parameter name fron "overflow" to "pOverflow"
              2. Updated template
              3. Updated reference section

 Description: Removed conditional code that updates WMOPS counter

 Description:
              1. Modified if-else structure to save cycles by processing
                 the most common case faster.

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 MODULE DESCRIPTION

 Subtraction function with overflow control

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "basic_op.h"

/*----------------------------------------------------------------------------
; MACROS
; [Define module specific macros here]
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; [Include all pre-processor statements here. Include conditional
; compile variables also.]
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; [List function prototypes here]
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL VARIABLE DEFINITIONS
; [Variable declaration - defined here and used outside this module]
----------------------------------------------------------------------------*/


/*
------------------------------------------------------------------------------
 FUNCTION NAME: sub
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    var1 = 16 bit short signed integer (Word16) whose value falls in
           the range : 0xffff 8000 <= var1 <= 0x0000 7fff.

    var2 = 16 bit short signed integer (Word16) whose value falls in
           the range : 0xffff 8000 <= var2 <= 0x0000 7fff.

    pOverflow = pointer to overflow (Flag)

 Outputs:
    pOverflow -> 1 if the subtract operation resulted in overflow

 Returns:
    diff = 16-bit limited difference between var1 and var2 (Word16)

 Global Variables Used:
    None

 Local Variables Needed:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function performs the subtraction (var1-var2) with overflow control and
 saturation; the 16 bit result is set at +32767 when overflow occurs or at
 -32768 when underflow occurs.

------------------------------------------------------------------------------
 REQUIREMENTS

 None

------------------------------------------------------------------------------
 REFERENCES

 [1] sub() function in basicop2.c, UMTS GSM AMR speech codec, R99 -
 Version 3.2.0, March 2, 2001

------------------------------------------------------------------------------

 PSEUDO-CODE

 Word16 sub (Word16 var1, Word16 var2)
 {
    Word16 var_out;
    Word32 diff;

    diff = (Word32) var1 - var2;

* The reference ETSI code uses a global flag for Overflow inside the function
* saturate(). In the actual implementation a pointer to Overflow flag is passed
* in as a parameter to the function

    var_out = saturate (diff);

 #if (WMOPS)
    multiCounter[currCounter].sub++;
 #endif

    return (var_out);
 }

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

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

Word16 sub(Word16 var1, Word16 var2, Flag *pOverflow)
{

    Word32 diff;

    diff = (Word32) var1 - var2;

    /* Saturate result (if necessary). */
    /* Replaced function call with in-line code             */
    /*  to conserve MIPS, i.e., var_out = saturate (diff)  */


    if ((UWord32)(diff - 0xFFFF8000L) > 0x000FFFF)
    {
        if (diff > (Word32) 0x0007FFFL)
        {
            diff = MAX_16;
        }
        else
        {
            diff = MIN_16;
        }

        *pOverflow = 1;
    }


    return ((Word16) diff);
}
