/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global omxVCM4P10_FilterDeblockingChroma_VerEdge_I
    .func   omxVCM4P10_FilterDeblockingChroma_VerEdge_I
omxVCM4P10_FilterDeblockingChroma_VerEdge_I:
    PUSH     {r4-r12,lr}
    VPUSH    {d8-d15}
    VLD1.8   {d0[]},[r2]!
    SUB      r0,r0,#4
    VLD1.8   {d2[]},[r3]!
    LDR      r4,[sp,#0x6c]
    LDR      r5,[sp,#0x68]
    LDR      r8, =0x4040404
    LDR      r9, =0x3030303
    VMOV.I8  d14,#0
    VMOV.I8  d15,#0x1
    VMOV.I16 d1,#0x4
    MOV      r7,#0x40000000
L0x34:
    LDR      r6,[r4],#8
    ADD      r10,r0,r1
    ADD      lr,r1,r1
    VLD1.8   {d7},[r0],lr
    VLD1.8   {d8},[r10],lr
    VLD1.8   {d5},[r0],lr
    VLD1.8   {d10},[r10],lr
    VLD1.8   {d6},[r0],lr
    VLD1.8   {d9},[r10],lr
    VLD1.8   {d4},[r0],lr
    VLD1.8   {d11},[r10],lr
    VZIP.8   d7,d8
    VZIP.8   d5,d10
    VZIP.8   d6,d9
    VZIP.8   d4,d11
    VZIP.16  d7,d5
    VZIP.16  d8,d10
    VZIP.16  d6,d4
    VZIP.16  d9,d11
    VTRN.32  d7,d6
    VTRN.32  d5,d4
    VTRN.32  d10,d11
    VTRN.32  d8,d9
    CMP      r6,#0
    VABD.U8  d19,d6,d4
    VABD.U8  d13,d4,d8
    BEQ      L0x170
    VABD.U8  d12,d5,d4
    VABD.U8  d18,d9,d8
    VMOV.32  d26[0],r6
    VCGT.U8  d16,d0,d13
    VMAX.U8  d12,d18,d12
    VMOVL.U8 q13,d26
    VABD.U8  d17,d10,d8
    VCGT.S16 d27,d26,#0
    VCGT.U8  d12,d2,d12
    VCGT.U8  d19,d2,d19
    VAND     d16,d16,d27
    TST      r6,r9
    VCGT.U8  d17,d2,d17
    VAND     d16,d16,d12
    VAND     d12,d16,d17
    VAND     d17,d16,d19
    BLNE     armVCM4P10_DeblockingChromabSLT4_unsafe
    TST      r6,r8
    SUB      r0,r0,r1,LSL #3
    VTST.16  d26,d26,d1
    BLNE     armVCM4P10_DeblockingChromabSGE4_unsafe
    VBIT     d29,d13,d26
    VBIT     d24,d31,d26
    ADD      r10,r0,#3
    VBIF     d29,d4,d16
    ADD      r12,r10,r1
    ADD      lr,r1,r1
    VBIF     d24,d8,d16
    ADDS     r7,r7,r7
    VST1.8   {d29[0]},[r10],lr
    VST1.8   {d29[1]},[r12],lr
    VST1.8   {d29[2]},[r10],lr
    VST1.8   {d29[3]},[r12],lr
    VST1.8   {d29[4]},[r10],lr
    VST1.8   {d29[5]},[r12],lr
    VST1.8   {d29[6]},[r10],lr
    VST1.8   {d29[7]},[r12],lr
    ADD      r12,r0,#4
    ADD      r10,r12,r1
    VST1.8   {d24[0]},[r12],lr
    VST1.8   {d24[1]},[r10],lr
    VST1.8   {d24[2]},[r12],lr
    VST1.8   {d24[3]},[r10],lr
    VST1.8   {d24[4]},[r12],lr
    VST1.8   {d24[5]},[r10],lr
    VST1.8   {d24[6]},[r12],lr
    VST1.8   {d24[7]},[r10],lr
    ADD      r0,r0,#4
    BNE      L0x34
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
L0x170:
    VLD1.8   {d0[]},[r2]
    ADD      r0,r0,#4
    SUB      r0,r0,r1,LSL #3
    ADDS     r7,r7,r7
    VLD1.8   {d2[]},[r3]
    ADD      r5,r5,#4
    BNE      L0x34
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
    .endfunc

    .end

