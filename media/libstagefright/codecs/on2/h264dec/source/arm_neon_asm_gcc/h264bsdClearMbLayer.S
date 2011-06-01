@
@ Copyright (C) 2009 The Android Open Source Project
@
@ Licensed under the Apache License, Version 2.0 (the "License");
@ you may not use this file except in compliance with the License.
@ You may obtain a copy of the License at
@
@      http://www.apache.org/licenses/LICENSE-2.0
@
@ Unless required by applicable law or agreed to in writing, software
@ distributed under the License is distributed on an "AS IS" BASIS,
@ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@ See the License for the specific language governing permissions and
@ limitations under the License.
@

#include "asm_common.S"

    preserve8

    .fpu neon
    .text

/* Input / output registers */
#define pMbLayer    r0
#define size        r1
#define pTmp        r2
#define step        r3

/* -- NEON registers -- */

#define qZero   Q0.U8

/*------------------------------------------------------------------------------

    Function: h264bsdClearMbLayer

        Functional description:

        Inputs:

        Outputs:

        Returns:

------------------------------------------------------------------------------*/

function h264bsdClearMbLayer, export=1

    VMOV    qZero, #0
    ADD     pTmp, pMbLayer, #16
    MOV     step, #32
    SUBS    size, size, #64

loop:
    VST1    {qZero}, [pMbLayer], step
    SUBS    size, size, #64
    VST1    {qZero}, [pTmp], step
    VST1    {qZero}, [pMbLayer], step
    VST1    {qZero}, [pTmp], step
    BCS     loop

    BX      lr

endfunction



