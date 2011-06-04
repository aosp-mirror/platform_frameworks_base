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

    .arm
    .text

/* Input / output registers */
#define pStrmData       r0
#define numBits         r1
#define readBits        r2
#define strmBuffSize    r3
#define pStrmBuffStart  r1
#define pStrmCurrPos    r2
#define bitPosInWord    r1

/* Input / output registers */



/*------------------------------------------------------------------------------

    Function: h264bsdFlushBits

        Functional description:

        Inputs:

        Outputs:

        Returns:

------------------------------------------------------------------------------*/
function h264bsdFlushBits, export=1

    LDR readBits, [pStrmData, #0x10]
    LDR strmBuffSize, [pStrmData, #0xC]

    ADD readBits, readBits, numBits
    AND bitPosInWord, readBits, #7

    STR readBits, [pStrmData, #0x10]
    STR bitPosInWord, [pStrmData, #0x8]

    LDR pStrmBuffStart, [pStrmData, #0x0]

    CMP readBits, strmBuffSize, LSL #3

    BHI end_of_stream

    ADD pStrmCurrPos, pStrmBuffStart, readBits, LSR #3
    STR pStrmCurrPos, [pStrmData, #0x4]
    MOV r0, #0
    BX  lr

end_of_stream:
    MVN r0, #0
    BX  lr

endfunction


