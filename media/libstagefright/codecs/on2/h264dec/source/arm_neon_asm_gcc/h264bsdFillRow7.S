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

#define ref     r0
#define fill    r1
#define left    r2
#define tmp2    r2
#define center  r3
#define right   r4
#define tmp1    r5

/* -- NEON registers -- */

#define qTmp0     Q0.U8
#define qTmp1     Q1.U8
#define dTmp0     D0.U8
#define dTmp1     D1.U8
#define dTmp2     D2.U8
#define dTmp3     D3.U8

/*
void h264bsdFillRow7(const u8 * ref, u8 * fill, i32 left, i32 center,
                     i32 right);
*/

function h264bsdFillRow7, export=1

        PUSH     {r4-r6,lr}
        CMP      left, #0
        LDR      right, [sp,#0x10]
        BEQ      switch_center
        LDRB     tmp1, [ref,#0]

loop_left:
        SUBS     left, left, #1
        STRB     tmp1, [fill], #1
        BNE      loop_left

switch_center:
        ASR      tmp2,center,#2
        CMP      tmp2,#9
        ADDCC    pc,pc,tmp2,LSL #2
        B        loop_center
        B        loop_center
        B        case_1
        B        case_2
        B        case_3
        B        case_4
        B        case_5
        B        case_6
        B        case_7
        B        case_8

case_8:
        VLD1    {qTmp0, qTmp1}, [ref]!
        SUB     center, center, #32
        VST1    {qTmp0}, [fill]!
        VST1    {qTmp1}, [fill]!
        B       loop_center
case_7:
        VLD1    {dTmp0,dTmp1,dTmp2}, [ref]!
        SUB     center, center, #28
        LDR     tmp2, [ref], #4
        VST1    {dTmp0,dTmp1,dTmp2}, [fill]!
        STR     tmp2, [fill],#4
        B       loop_center
case_6:
        VLD1    {dTmp0,dTmp1,dTmp2}, [ref]!
        SUB     center, center, #24
        VST1    {dTmp0,dTmp1,dTmp2}, [fill]!
        B       loop_center
case_5:
        VLD1    {qTmp0}, [ref]!
        SUB     center, center, #20
        LDR     tmp2, [ref], #4
        VST1    {qTmp0}, [fill]!
        STR     tmp2, [fill],#4
        B       loop_center
case_4:
        VLD1    {qTmp0}, [ref]!
        SUB     center, center, #16
        VST1    {qTmp0}, [fill]!
        B       loop_center
case_3:
        VLD1    {dTmp0}, [ref]!
        SUB     center, center, #12
        LDR     tmp2, [ref], #4
        VST1    dTmp0, [fill]!
        STR     tmp2, [fill],#4
        B       loop_center
case_2:
        LDR      tmp2, [ref],#4
        SUB      center, center, #4
        STR      tmp2, [fill], #4
case_1:
        LDR      tmp2, [ref],#4
        SUB      center, center, #4
        STR      tmp2, [fill], #4

loop_center:
        CMP      center, #0
        BEQ      jump
        LDRB     tmp2, [ref], #1
        SUB      center, center, #1
        STRB     tmp2, [fill], #1
        BNE      loop_center
jump:
        CMP      right,#0
        POPEQ    {r4-r6,pc}
        LDRB     tmp2, [ref,#-1]

loop_right:
        STRB     tmp2, [fill], #1
        SUBS     right, right, #1
        BNE      loop_right

        POP      {r4-r6,pc}

endfunction


