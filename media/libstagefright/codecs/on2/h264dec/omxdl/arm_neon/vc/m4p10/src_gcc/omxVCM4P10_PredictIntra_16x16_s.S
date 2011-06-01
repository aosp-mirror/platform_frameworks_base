/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon

    .section .rodata
    .align 4
;//-------------------------------------------------------
;// This table for implementing switch case of C in asm by
;// the mehtod of two levels of indexing.
;//-------------------------------------------------------

armVCM4P10_pIndexTable16x16:
    .word  OMX_VC_16X16_VERT, OMX_VC_16X16_HOR
    .word  OMX_VC_16X16_DC,   OMX_VC_16X16_PLANE



armVCM4P10_MultiplierTable16x16:
    .hword   7,  6,  5,  4,  3,  2,  1,  8
    .hword   0,  1,  2,  3,  4,  5,  6,  7
    .hword   8,  9, 10, 11, 12, 13, 14, 15

    .text

    .global omxVCM4P10_PredictIntra_16x16
    .func   omxVCM4P10_PredictIntra_16x16
omxVCM4P10_PredictIntra_16x16:
    PUSH     {r4-r12,lr}
    VPUSH    {d8-d15}
    LDR      r9, =armVCM4P10_pIndexTable16x16
    LDR      r6,[sp,#0x70]
    LDR      r4,[sp,#0x68]
    LDR      r5,[sp,#0x6c]
    LDR      r7,[sp,#0x74]
    MOV      r12,#0x10
    LDR      pc,[r9,r6,LSL #2]
OMX_VC_16X16_VERT:
    VLD1.8   {d0,d1},[r1]
    ADD      r8,r3,r5
    ADD      r10,r5,r5
    VST1.8   {d0,d1},[r3],r10
    VST1.8   {d0,d1},[r8],r10
    VST1.8   {d0,d1},[r3],r10
    VST1.8   {d0,d1},[r8],r10
    VST1.8   {d0,d1},[r3],r10
    VST1.8   {d0,d1},[r8],r10
    VST1.8   {d0,d1},[r3],r10
    VST1.8   {d0,d1},[r8],r10
    VST1.8   {d0,d1},[r3],r10
    VST1.8   {d0,d1},[r8],r10
    VST1.8   {d0,d1},[r3],r10
    VST1.8   {d0,d1},[r8],r10
    VST1.8   {d0,d1},[r3],r10
    VST1.8   {d0,d1},[r8],r10
    VST1.8   {d0,d1},[r3]
    VST1.8   {d0,d1},[r8]
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
OMX_VC_16X16_HOR:
    ADD      r8,r0,r4
    ADD      r4,r4,r4
    ADD      r11,r3,r5
    ADD      r5,r5,r5
L0x8c:
    VLD1.8   {d2[],d3[]},[r0],r4
    VLD1.8   {d0[],d1[]},[r8],r4
    SUBS     r12,r12,#8
    VST1.8   {d2,d3},[r3],r5
    VST1.8   {d0,d1},[r11],r5
    VLD1.8   {d2[],d3[]},[r0],r4
    VLD1.8   {d0[],d1[]},[r8],r4
    VST1.8   {d2,d3},[r3],r5
    VST1.8   {d0,d1},[r11],r5
    VLD1.8   {d2[],d3[]},[r0],r4
    VLD1.8   {d0[],d1[]},[r8],r4
    VST1.8   {d2,d3},[r3],r5
    VST1.8   {d0,d1},[r11],r5
    VLD1.8   {d2[],d3[]},[r0],r4
    VLD1.8   {d0[],d1[]},[r8],r4
    VST1.8   {d2,d3},[r3],r5
    VST1.8   {d0,d1},[r11],r5
    BNE      L0x8c
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
OMX_VC_16X16_DC:
    MOV      r11,#0
    TST      r7,#2
    BEQ      L0x14c
    ADD      r8,r0,r4
    ADD      r10,r4,r4
    VLD1.8   {d2[0]},[r0],r10
    VLD1.8   {d2[1]},[r8],r10
    VLD1.8   {d2[2]},[r0],r10
    VLD1.8   {d2[3]},[r8],r10
    VLD1.8   {d2[4]},[r0],r10
    VLD1.8   {d2[5]},[r8],r10
    VLD1.8   {d2[6]},[r0],r10
    VLD1.8   {d2[7]},[r8],r10
    VLD1.8   {d3[0]},[r0],r10
    VLD1.8   {d3[1]},[r8],r10
    VLD1.8   {d3[2]},[r0],r10
    VLD1.8   {d3[3]},[r8],r10
    VLD1.8   {d3[4]},[r0],r10
    VLD1.8   {d3[5]},[r8],r10
    VLD1.8   {d3[6]},[r0],r10
    VLD1.8   {d3[7]},[r8]
    VPADDL.U8 q0,q1
    ADD      r11,r11,#1
    VPADD.I16 d0,d0,d1
    VPADDL.U16 d0,d0
    VPADDL.U32 d6,d0
    VRSHR.U64 d8,d6,#4
L0x14c:
    TST      r7,#1
    BEQ      L0x170
    VLD1.8   {d0,d1},[r1]
    ADD      r11,r11,#1
    VPADDL.U8 q0,q0
    VPADD.I16 d0,d0,d1
    VPADDL.U16 d0,d0
    VPADDL.U32 d7,d0
    VRSHR.U64 d8,d7,#4
L0x170:
    CMP      r11,#2
    BNE      L0x180
    VADD.I64 d8,d7,d6
    VRSHR.U64 d8,d8,#5
L0x180:
    VDUP.8   q3,d8[0]
    CMP      r11,#0
    ADD      r8,r3,r5
    ADD      r10,r5,r5
    BNE      L0x198
    VMOV.I8  q3,#0x80
L0x198:
    VST1.8   {d6,d7},[r3],r10
    VST1.8   {d6,d7},[r8],r10
    VST1.8   {d6,d7},[r3],r10
    VST1.8   {d6,d7},[r8],r10
    VST1.8   {d6,d7},[r3],r10
    VST1.8   {d6,d7},[r8],r10
    VST1.8   {d6,d7},[r3],r10
    VST1.8   {d6,d7},[r8],r10
    VST1.8   {d6,d7},[r3],r10
    VST1.8   {d6,d7},[r8],r10
    VST1.8   {d6,d7},[r3],r10
    VST1.8   {d6,d7},[r8],r10
    VST1.8   {d6,d7},[r3],r10
    VST1.8   {d6,d7},[r8],r10
    VST1.8   {d6,d7},[r3],r10
    VST1.8   {d6,d7},[r8],r10
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
OMX_VC_16X16_PLANE:
    LDR      r9, =armVCM4P10_MultiplierTable16x16
    VLD1.8   {d0,d1},[r1]
    VLD1.8   {d4[0]},[r2]
    ADD      r8,r0,r4
    ADD      r10,r4,r4
    VLD1.8   {d2[0]},[r0],r10
    VLD1.8   {d2[1]},[r8],r10
    VLD1.8   {d2[2]},[r0],r10
    VLD1.8   {d2[3]},[r8],r10
    VLD1.8   {d2[4]},[r0],r10
    VLD1.8   {d2[5]},[r8],r10
    VLD1.8   {d2[6]},[r0],r10
    VLD1.8   {d2[7]},[r8],r10
    VLD1.8   {d3[0]},[r0],r10
    VLD1.8   {d3[1]},[r8],r10
    VLD1.8   {d3[2]},[r0],r10
    VLD1.8   {d3[3]},[r8],r10
    VLD1.8   {d3[4]},[r0],r10
    VLD1.8   {d3[5]},[r8],r10
    VLD1.8   {d3[6]},[r0],r10
    VLD1.8   {d3[7]},[r8]
    VREV64.8 d5,d1
    VSUBL.U8 q3,d5,d4
    VSHR.U64 d5,d5,#8
    VSUBL.U8 q4,d5,d0
    VSHL.I64 d9,d9,#16
    VEXT.8   d9,d9,d6,#2
    VREV64.8 d12,d3
    VSUBL.U8 q7,d12,d4
    VSHR.U64 d12,d12,#8
    VSUBL.U8 q8,d12,d2
    VLD1.16  {d20,d21},[r9]!
    VSHL.I64 d17,d17,#16
    VEXT.8   d17,d17,d14,#2
    VMULL.S16 q11,d8,d20
    VMULL.S16 q12,d16,d20
    VMLAL.S16 q11,d9,d21
    VMLAL.S16 q12,d17,d21
    VPADD.I32 d22,d23,d22
    VPADD.I32 d23,d25,d24
    VPADDL.S32 q11,q11
    VSHL.I64 q12,q11,#2
    VADD.I64 q11,q11,q12
    VRSHR.S64 q11,q11,#6
    VSHL.I64 q12,q11,#3
    VSUB.I64 q12,q12,q11
    VLD1.16  {d20,d21},[r9]!
    VDUP.16  q6,d22[0]
    VDUP.16  q7,d23[0]
    VADDL.U8 q11,d1,d3
    VSHL.I16 q11,q11,#4
    VDUP.16  q11,d23[3]
    VADD.I64 d1,d24,d25
    VLD1.16  {d24,d25},[r9]
    VDUP.16  q13,d1[0]
    VSUB.I16 q13,q11,q13
    VMUL.I16 q5,q6,q10
    VMUL.I16 q6,q6,q12
    VADD.I16 q0,q5,q13
    VADD.I16 q1,q6,q13
L0x2d4:
    VQRSHRUN.S16 d6,q0,#5
    VQRSHRUN.S16 d7,q1,#5
    SUBS     r12,r12,#1
    VST1.8   {d6,d7},[r3],r5
    VADD.I16 q0,q0,q7
    VADD.I16 q1,q1,q7
    BNE      L0x2d4
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
    .endfunc

    .end

