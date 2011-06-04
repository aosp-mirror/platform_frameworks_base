/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global omxVCM4P10_FilterDeblockingLuma_VerEdge_I
    .func   omxVCM4P10_FilterDeblockingLuma_VerEdge_I
omxVCM4P10_FilterDeblockingLuma_VerEdge_I:
    PUSH     {r4-r12,lr}
    VPUSH    {d8-d15}
    ADD      r7,r2,#1
    ADD      r8,r3,#1
    VLD1.8   {d0[]},[r2]
    SUB      r0,r0,#4
    VLD1.8   {d2[]},[r3]
    LDR      r4,[sp,#0x6c]
    LDR      r5,[sp,#0x68]
    MOV      r6,#0
    VMOV.I8  d14,#0
    VMOV.I8  d15,#0x1
    MOV      r9,#0x11000000
    ADD      r11,r1,r1
L0x38:
    LDRH     r12,[r4],#4
    CMP      r12,#0
    BEQ      L0x160
    ADD      r10,r0,r1
    VLD1.8   {d7},[r0],r11
    VLD1.8   {d8},[r10],r11
    VLD1.8   {d5},[r0],r11
    VZIP.8   d7,d8
    VLD1.8   {d10},[r10],r11
    VLD1.8   {d6},[r0],r11
    VZIP.8   d5,d10
    VLD1.8   {d9},[r10],r11
    VLD1.8   {d4},[r0],r11
    VLD1.8   {d11},[r10],r11
    VZIP.8   d6,d9
    VZIP.16  d8,d10
    VZIP.8   d4,d11
    SUB      r0,r0,r1,LSL #3
    VZIP.16  d7,d5
    VZIP.16  d9,d11
    VZIP.16  d6,d4
    VTRN.32  d8,d9
    VTRN.32  d5,d4
    VTRN.32  d10,d11
    VTRN.32  d7,d6
    VABD.U8  d13,d4,d8
    VABD.U8  d12,d5,d4
    VABD.U8  d18,d9,d8
    VABD.U8  d19,d6,d4
    TST      r12,#0xff
    VCGT.U8  d16,d0,d13
    VMAX.U8  d12,d18,d12
    VABD.U8  d17,d10,d8
    VMOVEQ.32 d16[0],r6
    TST      r12,#0xff00
    VCGT.U8  d19,d2,d19
    VCGT.U8  d12,d2,d12
    VMOVEQ.32 d16[1],r6
    VCGT.U8  d17,d2,d17
    VAND     d16,d16,d12
    TST      r12,#4
    VAND     d12,d16,d17
    VAND     d17,d16,d19
    BNE      L0x17c
    BL       armVCM4P10_DeblockingLumabSLT4_unsafe
    VZIP.8   d7,d6
    VZIP.8   d30,d29
    VZIP.8   d24,d25
    VZIP.8   d10,d11
    VZIP.16  d7,d30
    ADD      r10,r0,r1
    VZIP.16  d24,d10
    VZIP.16  d25,d11
    VZIP.16  d6,d29
    VTRN.32  d7,d24
    VTRN.32  d30,d10
    VTRN.32  d6,d25
    VTRN.32  d29,d11
    VST1.8   {d7},[r0],r11
    VST1.8   {d24},[r10],r11
    VST1.8   {d30},[r0],r11
    VST1.8   {d10},[r10],r11
    VST1.8   {d6},[r0],r11
    VST1.8   {d25},[r10],r11
    ADDS     r9,r9,r9
    VST1.8   {d29},[r0],r11
    ADD      r5,r5,#2
    VST1.8   {d11},[r10],r1
    SUB      r0,r0,r1,LSL #3
    VLD1.8   {d0[]},[r7]
    ADD      r0,r0,#4
    VLD1.8   {d2[]},[r8]
    BCC      L0x38
    B        L0x1f0
L0x160:
    ADD      r0,r0,#4
    ADDS     r9,r9,r9
    VLD1.8   {d0[]},[r7]
    ADD      r5,r5,#4
    VLD1.8   {d2[]},[r8]
    BCC      L0x38
    B        L0x1f0
L0x17c:
    BL       armVCM4P10_DeblockingLumabSGE4_unsafe
    VZIP.8   d7,d31
    VZIP.8   d30,d29
    VZIP.8   d24,d25
    VZIP.8   d28,d11
    VZIP.16  d7,d30
    ADD      r10,r0,r1
    VZIP.16  d24,d28
    VZIP.16  d25,d11
    VZIP.16  d31,d29
    VTRN.32  d7,d24
    VTRN.32  d30,d28
    VTRN.32  d31,d25
    VTRN.32  d29,d11
    VST1.8   {d7},[r0],r11
    VST1.8   {d24},[r10],r11
    VST1.8   {d30},[r0],r11
    VST1.8   {d28},[r10],r11
    VST1.8   {d31},[r0],r11
    VST1.8   {d25},[r10],r11
    ADDS     r9,r9,r9
    VST1.8   {d29},[r0],r11
    ADD      r5,r5,#4
    VST1.8   {d11},[r10],r11
    SUB      r0,r0,r1,LSL #3
    VLD1.8   {d0[]},[r7]
    ADD      r0,r0,#4
    VLD1.8   {d2[]},[r8]
    BCC      L0x38
L0x1f0:
    SUB      r4,r4,#0xe
    SUB      r5,r5,#0xe
    SUB      r0,r0,#0x10
    VLD1.8   {d0[]},[r2]
    ADD      r0,r0,r1,LSL #3
    VLD1.8   {d2[]},[r3]
    BNE      L0x38
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
    .endfunc

    .end

