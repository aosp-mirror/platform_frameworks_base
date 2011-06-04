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

armVCM4P10_pIndexTable8x8:
    .word  OMX_VC_CHROMA_DC,     OMX_VC_CHROMA_HOR
    .word  OMX_VC_CHROMA_VERT,   OMX_VC_CHROMA_PLANE

armVCM4P10_MultiplierTableChroma8x8:
    .hword   3, 2, 1,4
    .hword  -3,-2,-1,0
    .hword   1, 2, 3,4


    .text
    .global omxVCM4P10_PredictIntraChroma_8x8
    .func   omxVCM4P10_PredictIntraChroma_8x8
omxVCM4P10_PredictIntraChroma_8x8:
    PUSH     {r4-r10,lr}
    VPUSH    {d8-d15}
    LDR      r8, =armVCM4P10_pIndexTable8x8
    LDR      r6,[sp,#0x68]
    LDR      r4,[sp,#0x60]
    LDR      r5,[sp,#0x64]
    LDR      r7,[sp,#0x6c]
    LDR      pc,[r8,r6,LSL #2]
OMX_VC_CHROMA_DC:
    TST      r7,#2
    BEQ      L0xe8
    ADD      r9,r0,r4
    ADD      r10,r4,r4
    VLD1.8   {d1[0]},[r0],r10
    VLD1.8   {d1[1]},[r9],r10
    VLD1.8   {d1[2]},[r0],r10
    VLD1.8   {d1[3]},[r9],r10
    VLD1.8   {d1[4]},[r0],r10
    VLD1.8   {d1[5]},[r9],r10
    VLD1.8   {d1[6]},[r0],r10
    VLD1.8   {d1[7]},[r9]
    TST      r7,#1
    BEQ      L0xcc
    VLD1.8   {d0},[r1]
    MOV      r0,#0
    VPADDL.U8 d2,d0
    VPADDL.U16 d3,d2
    VPADDL.U8 d2,d1
    VPADDL.U16 d1,d2
    VADD.I32 d2,d3,d1
    VRSHR.U32 d2,d2,#3
    VRSHR.U32 d3,d3,#2
    VRSHR.U32 d1,d1,#2
    VMOV.I8  d5,#0xc
    VMOV.I8  d6,#0x4
    VSHL.I64 d5,d5,#32
    VSHR.U64 d6,d6,#32
    VADD.I8  d6,d6,d5
    VTBL.8   d0,{d2-d3},d5
    VTBL.8   d4,{d1-d2},d6
L0x9c:
    ADD      r9,r3,r5
    ADD      r10,r5,r5
    VST1.8   {d0},[r3],r10
    VST1.8   {d0},[r9],r10
    VST1.8   {d0},[r3],r10
    VST1.8   {d0},[r9],r10
    VST1.8   {d4},[r3],r10
    VST1.8   {d4},[r9],r10
    VST1.8   {d4},[r3],r10
    VST1.8   {d4},[r9]
    VPOP     {d8-d15}
    POP      {r4-r10,pc}
L0xcc:
    MOV      r0,#0
    VPADDL.U8 d2,d1
    VPADDL.U16 d1,d2
    VRSHR.U32 d1,d1,#2
    VDUP.8   d0,d1[0]
    VDUP.8   d4,d1[4]
    B        L0x9c
L0xe8:
    TST      r7,#1
    BEQ      L0x114
    VLD1.8   {d0},[r1]
    MOV      r0,#0
    VPADDL.U8 d2,d0
    VPADDL.U16 d3,d2
    VRSHR.U32 d3,d3,#2
    VMOV.I8  d5,#0x4
    VSHL.I64 d5,d5,#32
    VTBL.8   d0,{d3},d5
    B        L0x11c
L0x114:
    VMOV.I8  d0,#0x80
    MOV      r0,#0
L0x11c:
    ADD      r9,r3,r5
    ADD      r10,r5,r5
    VST1.8   {d0},[r3],r10
    VST1.8   {d0},[r9],r10
    VST1.8   {d0},[r3],r10
    VST1.8   {d0},[r9],r10
    VST1.8   {d0},[r3],r10
    VST1.8   {d0},[r9],r10
    VST1.8   {d0},[r3],r10
    VST1.8   {d0},[r9]
    VPOP     {d8-d15}
    POP      {r4-r10,pc}
OMX_VC_CHROMA_VERT:
    VLD1.8   {d0},[r1]
    MOV      r0,#0
    B        L0x11c
OMX_VC_CHROMA_HOR:
    ADD      r9,r0,r4
    ADD      r10,r4,r4
    VLD1.8   {d0[]},[r0],r10
    VLD1.8   {d1[]},[r9],r10
    VLD1.8   {d2[]},[r0],r10
    VLD1.8   {d3[]},[r9],r10
    VLD1.8   {d4[]},[r0],r10
    VLD1.8   {d5[]},[r9],r10
    VLD1.8   {d6[]},[r0],r10
    VLD1.8   {d7[]},[r9]
    B        L0x28c
OMX_VC_CHROMA_PLANE:
    ADD      r9,r0,r4
    ADD      r10,r4,r4
    VLD1.8   {d0},[r1]
    VLD1.8   {d2[0]},[r2]
    VLD1.8   {d1[0]},[r0],r10
    VLD1.8   {d1[1]},[r9],r10
    VLD1.8   {d1[2]},[r0],r10
    VLD1.8   {d1[3]},[r9],r10
    VLD1.8   {d1[4]},[r0],r10
    VLD1.8   {d1[5]},[r9],r10
    VLD1.8   {d1[6]},[r0],r10
    VLD1.8   {d1[7]},[r9]
    VREV64.8 d3,d0
    VSUBL.U8 q3,d3,d2
    VSHR.U64 d3,d3,#8
    VSUBL.U8 q2,d3,d0
    VREV64.8 d3,d1
    VSUBL.U8 q7,d3,d2
    VSHR.U64 d3,d3,#8
    VSUBL.U8 q6,d3,d1
    LDR      r2, =armVCM4P10_MultiplierTableChroma8x8
    VSHL.I64 d4,d4,#16
    VEXT.8   d9,d4,d6,#2
    VLD1.16  {d10},[r2]!
    VSHL.I64 d12,d12,#16
    VEXT.8   d16,d12,d14,#2
    VMUL.I16 d11,d9,d10
    VMUL.I16 d3,d16,d10
    VPADD.I16 d3,d11,d3
    VPADDL.S16 d3,d3
    VSHL.I32 d2,d3,#4
    VADD.I32 d3,d3,d2
    VLD1.16  {d10,d11},[r2]
    VRSHR.S32 d3,d3,#5
    VADDL.U8 q0,d0,d1
    VDUP.16  q0,d1[3]
    VSHL.I16 q0,q0,#4
    VDUP.16  q2,d3[0]
    VDUP.16  q3,d3[2]
    VMUL.I16 q2,q2,q5
    VMUL.I16 q3,q3,q5
    VADD.I16 q2,q2,q0
    VDUP.16  q0,d6[0]
    VDUP.16  q1,d6[1]
    VDUP.16  q4,d6[2]
    VDUP.16  q5,d6[3]
    VDUP.16  q6,d7[0]
    VDUP.16  q7,d7[1]
    VDUP.16  q8,d7[2]
    VDUP.16  q9,d7[3]
    VADD.I16 q0,q2,q0
    VADD.I16 q1,q2,q1
    VADD.I16 q4,q2,q4
    VADD.I16 q5,q2,q5
    VADD.I16 q6,q2,q6
    VADD.I16 q7,q2,q7
    VADD.I16 q8,q2,q8
    VADD.I16 q9,q2,q9
    VQRSHRUN.S16 d0,q0,#5
    VQRSHRUN.S16 d1,q1,#5
    VQRSHRUN.S16 d2,q4,#5
    VQRSHRUN.S16 d3,q5,#5
    VQRSHRUN.S16 d4,q6,#5
    VQRSHRUN.S16 d5,q7,#5
    VQRSHRUN.S16 d6,q8,#5
    VQRSHRUN.S16 d7,q9,#5
L0x28c:
    ADD      r9,r3,r5
    ADD      r10,r5,r5
    VST1.8   {d0},[r3],r10
    VST1.8   {d1},[r9],r10
    VST1.8   {d2},[r3],r10
    VST1.8   {d3},[r9],r10
    VST1.8   {d4},[r3],r10
    VST1.8   {d5},[r9],r10
    VST1.8   {d6},[r3],r10
    VST1.8   {d7},[r9]
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r10,pc}
    .endfunc

    .end

