/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global omxVCM4P10_FilterDeblockingChroma_HorEdge_I
    .func   omxVCM4P10_FilterDeblockingChroma_HorEdge_I
omxVCM4P10_FilterDeblockingChroma_HorEdge_I:
    PUSH     {r4-r10,lr}
    VPUSH    {d8-d15}
    VLD1.8   {d0[]},[r2]!
    SUB      r0,r0,r1,LSL #1
    SUB      r0,r0,r1
    VLD1.8   {d2[]},[r3]!
    LDR      r4,[sp,#0x64]
    LDR      r5,[sp,#0x60]
    LDR      r9, =0x3030303
    LDR      r8, =0x4040404
    VMOV.I8  d14,#0
    VMOV.I8  d15,#0x1
    VMOV.I16 d1,#0x4
    MOV      r7,#0x40000000
L0x38:
    LDR      r6,[r4],#8
    VLD1.8   {d6},[r0],r1
    VLD1.8   {d5},[r0],r1
    CMP      r6,#0
    VLD1.8   {d4},[r0],r1
    VLD1.8   {d8},[r0],r1
    VABD.U8  d19,d6,d4
    VLD1.8   {d9},[r0],r1
    VABD.U8  d13,d4,d8
    VLD1.8   {d10},[r0],r1
    BEQ      L0xe4
    VABD.U8  d12,d5,d4
    VABD.U8  d18,d9,d8
    VCGT.U8  d16,d0,d13
    VMOV.32  d26[0],r6
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
    SUB      r0,r0,r1,LSL #2
    VTST.16  d26,d26,d1
    BLNE     armVCM4P10_DeblockingChromabSGE4_unsafe
    VBIT     d29,d13,d26
    VBIT     d24,d31,d26
    VBIF     d29,d4,d16
    VBIF     d24,d8,d16
    VST1.8   {d29},[r0],r1
    ADDS     r7,r7,r7
    VST1.8   {d24},[r0],r1
    BNE      L0x38
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r10,pc}
L0xe4:
    VLD1.8   {d0[]},[r2]
    SUB      r0,r0,r1,LSL #1
    ADDS     r7,r7,r7
    VLD1.8   {d2[]},[r3]
    ADD      r5,r5,#4
    BNE      L0x38
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r10,pc}
    .endfunc

    .end

