@ ------------------------------------------------------------------
@ Copyright (C) 1998-2009 PacketVideo
@
@ Licensed under the Apache License, Version 2.0 (the "License");
@ you may not use this file except in compliance with the License.
@ You may obtain a copy of the License at
@
@      http://www.apache.org/licenses/LICENSE-2.0
@
@ Unless required by applicable law or agreed to in writing, software
@ distributed under the License is distributed on an "AS IS" BASIS,
@ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
@ express or implied.
@ See the License for the specific language governing permissions
@ and limitations under the License.
@ -------------------------------------------------------------------

@
@
@   Filename: pvmp3_dct_16_gcc.s
@
@
@------------------------------------------------------------------------------
@ REVISION HISTORY
@
@
@ Who:                                   Date: MM/DD/YYYY
@ Description: 
@
@------------------------------------------------------------------------------

.arm

.align 4

.text

.extern  pvmp3_dct_16
.extern  pvmp3_merge_in_place_N32
.extern  pvmp3_split



@------------------------------------------------------------------------------

.global pvmp3_dct_16

pvmp3_dct_16:
        stmfd    sp!,{r0,r1,r4-r11,lr}
        ldr      r1,[r0]
        ldr      r3,[r0,#0x3c]
        ldr      r12,constant1
        sub      r2,r1,r3
        smull    lr,r2,r12,r2
        sub      sp,sp,#0x1c
        str      r2,[sp,#0x14]
        ldr      r2,[r0,#0x1c]
        ldr      r12,[r0,#0x20]
        add      r1,r1,r3
        sub      r3,r2,r12
        ldr      lr,constant2
        mov      r3,r3,lsl #3
        smull    r4,r3,lr,r3
        ldr      r6,constant5
        str      r3,[sp]
        add      r3,r2,r12
        sub      r2,r1,r3
        ldr      r12,constant3
        add      r3,r1,r3
        smull    lr,r2,r12,r2
        ldr      r12,[r0,#0x38]
        ldr      r1,[r0,#4]
        ldr      lr,constant4
        sub      r4,r1,r12
        add      r1,r1,r12
        ldr      r12,[r0,#0x18]
        smull    r4,r5,lr,r4
        ldr      lr,[r0,#0x24]
        ldr      r10,constant10
        sub      r4,r12,lr
        mov      r4,r4,lsl #1
        smull    r7,r4,r6,r4
        add      r12,r12,lr
        add      r7,r1,r12
        sub      r12,r1,r12
        ldr      r1,constant6
        str      r4,[sp,#4]
        smull    r12,r4,r1,r12
        ldr      r1,[r0,#8]
        ldr      r12,[r0,#0x34]
        ldr      r6,constant7
        sub      lr,r1,r12
        smull    r8,lr,r6,lr
        add      r1,r1,r12
        str      lr,[sp,#0x10]
        ldr      r12,[r0,#0x14]
        ldr      lr,[r0,#0x28]
        ldr      r8,constant8
        sub      r6,r12,lr
        mov      r6,r6,lsl #1
        smull    r9,r6,r8,r6
        add      r12,r12,lr
        ldr      r9,constant9
        add      r8,r1,r12
        sub      r12,r1,r12
        smull    r12,lr,r9,r12
        ldr      r12,[r0,#0x30]
        ldr      r1,[r0,#0xc]
        sub      r9,r1,r12
        smull    r11,r9,r10,r9
        add      r12,r1,r12
        str      r9,[sp,#0xc]
        ldr      r9,[r0,#0x10]
        ldr      r10,constant11
        str      r9,[sp,#0x18]
        ldr      r1,[r0,#0x2c]
        sub      r9,r9,r1
        smull    r11,r9,r10,r9
        ldr      r10,constant12
        str      r9,[sp,#8]
        ldr      r9,[sp,#0x18]
        ldr      r11,constant14
        add      r9,r9,r1
        add      r1,r12,r9
        sub      r12,r12,r9
        mov      r12,r12,lsl #2
        smull    r9,r12,r10,r12
        ldr      r10,constant13
        add      r9,r3,r1
        sub      r1,r3,r1
        smull    r1,r3,r10,r1
        sub      r1,r7,r8
        mov      r1,r1,lsl #1
        smull    r1,r10,r11,r1
        add      r1,r7,r8
        add      r8,r9,r1
        sub      r7,r9,r1
        mov      r8,r8,asr #1
        ldr      r1,constant15
        str      r8,[r0]
        smull    r7,r8,r1,r7
        sub      r7,r3,r10
        str      r8,[r0,#0x20]
        mov      r7,r7,lsl #1
        smull    r8,r7,r1,r7
        add      r3,r3,r10
        add      r3,r3,r7
        str      r3,[r0,#0x10]
        sub      r3,r2,r12
        str      r7,[r0,#0x30]
        add      r2,r2,r12
        ldr      r7,constant13
        sub      r12,r4,lr
        mov      r3,r3,lsl #1
        smull    r8,r3,r7,r3
        add      lr,r4,lr
        sub      r4,r2,lr
        mov      r12,r12,lsl #2
        smull    r7,r12,r11,r12
        add      lr,lr,r2
        sub      r2,r3,r12
        mov      r2,r2,lsl #1
        smull    r7,r2,r1,r2
        mov      r4,r4,lsl #1
        add      r12,r12,r2
        add      r3,r12,r3
        smull    r7,r4,r1,r4
        add      r12,r3,lr
        add      r3,r3,r4
        str      r3,[r0,#0x18]
        add      r3,r2,r4
        str      r2,[r0,#0x38]
        str      r3,[r0,#0x28]
        str      r12,[r0,#8]
        ldr      r2,[sp,#0x14]
        ldr      r3,[sp,#0]
        ldr      lr,[sp,#4]
        sub      r2,r2,r3
        ldr      r3,constant3
        mov      r2,r2,lsl #1
        smull    r12,r2,r3,r2
        ldr      r3,[sp,#0x14]
        ldr      r12,[sp,#0]
        ldr      r4,constant6
        add      r12,r3,r12
        ldr      r3,[sp,#4]
        sub      lr,r5,lr
        mov      lr,lr,lsl #1
        add      r3,r5,r3
        smull    r5,lr,r4,lr
        ldr      r4,[sp,#0x10]
        ldr      r5,[sp,#0x10]
        add      r4,r4,r6
        sub      r5,r5,r6
        ldr      r6,constant9
        mov      r5,r5,lsl #1
        smull    r7,r5,r6,r5
        ldr      r6,[sp,#8]
        ldr      r9,[sp,#0xc]
        ldr      r10,constant12
        sub      r6,r9,r6
        mov      r6,r6,lsl #3
        smull    r7,r6,r10,r6
        ldr      r8,[sp,#0x20]
        ldr      r7,[sp,#8]
        cmp      r8,#0
        add      r7,r9,r7

        bne      no_flag_proc
        rsb      r12,r12,#0
        rsb      r2,r2,#0
        rsb      r3,r3,#0
        rsb      lr,lr,#0
        rsb      r4,r4,#0
        rsb      r5,r5,#0
        rsb      r7,r7,#0
        rsb      r6,r6,#0
no_flag_proc:

        sub      r8,r2,r6
        add      r2,r6,r2
        sub      r6,r12,r7
        ldr      r9,constant13
        add      r12,r12,r7
        sub      r7,r3,r4
        mov      r6,r6,lsl #1
        mov      r8,r8,lsl #1
        smull    r10,r8,r9,r8
        add      r3,r3,r4
        smull    r10,r6,r9,r6
        sub      r4,lr,r5
        mov      r7,r7,lsl #2
        smull    r9,r7,r11,r7
        add      lr,lr,r5
        sub      r5,r6,r7
        add      r6,r6,r7
        sub      r7,r12,r3
        add      r3,r12,r3
        sub      r12,r2,lr
        mov      r4,r4,lsl #2
        smull    r9,r4,r11,r4
        add      lr,r2,lr
        sub      r2,r8,r4
        mov      r2,r2,lsl #1
        mov      r5,r5,lsl #1
        mov      r12,r12,lsl #1
        mov      r7,r7,lsl #1
        smull    r9,r5,r1,r5
        smull    r9,r2,r1,r2
        add      r6,r6,r5
        smull    r9,r7,r1,r7
        smull    r9,r12,r1,r12
        add      r1,r4,r2
        add      r1,r1,r8
        add      lr,lr,r1
        add      r3,r3,lr
        str      r3,[r0,#4]
        add      r3,r6,lr
        str      r3,[r0,#0xc]
        add      r1,r1,r12
        add      r3,r6,r1
        add      r1,r7,r1
        str      r1,[r0,#0x1c]
        str      r3,[r0,#0x14]
        add      r1,r12,r2
        add      r3,r7,r1
        add      r1,r5,r1
        str      r1,[r0,#0x2c]
        str      r3,[r0,#0x24]!
        add      r1,r5,r2
        str      r1,[r0,#0x10]
        str      r2,[r0,#0x18]
        add      sp,sp,#0x24
        ldmfd    sp!,{r4-r11,pc}



@------------------------------------------------------------------------------

.global pvmp3_merge_in_place_N32



pvmp3_merge_in_place_N32:
        stmfd    sp!,{r4,lr}
        ldr      r1,[r0,#0x1c]
        ldr      r2,[r0,#0x38]
        str      r1,[r0,#0x38]
        ldr      r1,[r0,#0x18]
        ldr      r3,[r0,#0x30]
        str      r1,[r0,#0x30]
        ldr      r12,[r0,#0x14]
        ldr      r1,[r0,#0x28]
        str      r12,[r0,#0x28]
        ldr      r12,[r0,#0x10]
        ldr      lr,[r0,#0x20]
        str      r12,[r0,#0x20]
        ldr      r12,[r0,#0xc]
        str      r12,[r0,#0x18]
        ldr      r12,[r0,#8]
        str      r12,[r0,#0x10]
        ldr      r12,[r0,#4]
        str      r12,[r0,#8]
        ldr      r4,[r0,#0x40]
        ldr      r12,[r0,#0x44]
        add      r4,r4,r12
        str      r4,[r0,#4]
        str      lr,[r0,#0x40]
        ldr      lr,[r0,#0x48]
        add      r12,lr,r12
        str      r12,[r0,#0xc]
        ldr      r12,[r0,#0x4c]
        add      lr,r12,lr
        str      lr,[r0,#0x14]
        ldr      lr,[r0,#0x24]
        str      lr,[r0,#0x48]
        ldr      lr,[r0,#0x50]
        add      r12,lr,r12
        str      r12,[r0,#0x1c]
        ldr      r12,[r0,#0x54]
        str      r1,[r0,#0x50]
        add      lr,r12,lr
        str      lr,[r0,#0x24]
        ldr      r1,[r0,#0x58]
        ldr      r4,[r0,#0x2c]
        ldr      lr,[r0,#0x34]
        add      r12,r1,r12
        str      r12,[r0,#0x2c]
        ldr      r12,[r0,#0x5c]
        add      r1,r12,r1
        str      r1,[r0,#0x34]
        str      r4,[r0,#0x58]
        ldr      r1,[r0,#0x60]
        ldr      r4,[r0,#0x3c]
        add      r12,r1,r12
        str      r12,[r0,#0x3c]
        ldr      r12,[r0,#0x64]
        add      r1,r12,r1
        str      r1,[r0,#0x44]
        ldr      r1,[r0,#0x68]
        add      r12,r1,r12
        str      r12,[r0,#0x4c]
        ldr      r12,[r0,#0x6c]
        add      r1,r12,r1
        str      r1,[r0,#0x54]
        ldr      r1,[r0,#0x70]
        str      r3,[r0,#0x60]
        add      r12,r1,r12
        str      r12,[r0,#0x5c]
        ldr      r3,[r0,#0x74]
        add      r1,r3,r1
        str      r1,[r0,#0x64]
        str      lr,[r0,#0x68]
        ldr      r1,[r0,#0x78]
        str      r2,[r0,#0x70]
        add      r3,r1,r3
        str      r3,[r0,#0x6c]
        ldr      r2,[r0,#0x7c]
        add      r1,r1,r2
        str      r1,[r0,#0x74]
        str      r4,[r0,#0x78]
        ldmfd    sp!,{r4,pc}


@------------------------------------------------------------------------------

.global pvmp3_split


pvmp3_split:
        stmfd    sp!,{r4,r5,lr}
        ldr      r2,constant16
        sub      r1,r0,#4
        mov      r3,#3
loop1:
        ldr      r12,[r0]
        ldr      lr,[r1]
        ldr      r4,[r2],#-4
        add      r5,lr,r12
        sub      r12,lr,r12
        smull    r12,lr,r4,r12
        str      r5,[r1],#-4
        mov      r12,r12,lsr #27
        add      r12,r12,lr,lsl #5
        str      r12,[r0],#4
        ldr      r12,[r0]
        ldr      lr,[r1]
        ldr      r4,[r2],#-4
        add      r5,lr,r12
        sub      r12,lr,r12
        smull    r12,lr,r4,r12
        str      r5,[r1],#-4
        mov      r12,r12,lsr #27
        add      r12,r12,lr,lsl #5
        str      r12,[r0],#4
        subs     r3,r3,#1
        bne      loop1
        mov      r3,#5
loop2:
        ldr      r12,[r0]
        ldr      lr,[r1]
        ldr      r4,[r2],#-4
        add      r5,lr,r12
        sub      r12,lr,r12
        mov      r12,r12,lsl #1
        smull    lr,r12,r4,r12
        str      r5,[r1],#-4
        str      r12,[r0],#4
        ldr      r12,[r0]
        ldr      lr,[r1]
        ldr      r4,[r2],#-4
        add      r5,lr,r12
        sub      r12,lr,r12
        mov      r12,r12,lsl #1
        smull    lr,r12,r4,r12
        str      r5,[r1],#-4
        str      r12,[r0],#4
        subs     r3,r3,#1
        bne      loop2
        ldmfd    sp!,{r4,r5,pc}
constant1:
        .word      0x404f4680
constant2:
        .word      0x519e4e00
constant3:
        .word      0x4140fb80
constant4:
        .word      0x42e13c00
constant5:
        .word      0x6e3c9300
constant6:
        .word      0x4cf8de80
constant7:
        .word      0x48919f80
constant8:
        .word      0x43e22480
constant9:
        .word      0x73326b80
constant10:
        .word      0x52cb0e80
constant11:
        .word      0x64e24000
constant12:
        .word      0x52036780
constant13:
        .word      0x4545ea00
constant14:
        .word      0x539eba80
constant15:
        .word      0x5a827980
constant16:
        .word      CosTable_dct32 + 60



CosTable_dct32:
        .word      0x4013c280
        .word      0x40b34580
        .word      0x41fa2d80
        .word      0x43f93400
        .word      0x46cc1c00
        .word      0x4a9d9d00
        .word      0x4fae3700
        .word      0x56601e80
        .word      0x5f4cf700
        .word      0x6b6fcf00
        .word      0x07c7d1d8
        .word      0x095b0350
        .word      0x0bdf91b0
        .word      0x107655e0
        .word      0x1b42c840
        .word      0x51852300


