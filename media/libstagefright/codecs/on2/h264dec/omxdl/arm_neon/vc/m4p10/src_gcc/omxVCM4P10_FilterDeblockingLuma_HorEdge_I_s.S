/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global omxVCM4P10_FilterDeblockingLuma_HorEdge_I
    .func   omxVCM4P10_FilterDeblockingLuma_HorEdge_I
omxVCM4P10_FilterDeblockingLuma_HorEdge_I:
    PUSH     {r4-r12,lr}
    VPUSH    {d8-d15}
    ADD      r7,r2,#1
    ADD      r8,r3,#1
    VLD1.8   {d0[]},[r2]
    SUB      r0,r0,r1,LSL #2
    VLD1.8   {d2[]},[r3]
    LDR      r4,[sp,#0x6c]
    LDR      r5,[sp,#0x68]
    MOV      r11,#0
    VMOV.I8  d14,#0
    VMOV.I8  d15,#0x1
    ADD      r10,r1,r1
    MOV      r9,#0x55000000
L0x38:
    LDRH     r12,[r4],#2
    ADD      r6,r0,r1
    CMP      r12,#0
    BEQ      L0xe4
    VLD1.8   {d7},[r0],r10
    VLD1.8   {d6},[r6],r10
    VLD1.8   {d5},[r0],r10
    VLD1.8   {d4},[r6],r10
    VLD1.8   {d8},[r0],r10
    VABD.U8  d12,d4,d5
    VLD1.8   {d9},[r6]
    VABD.U8  d13,d8,d4
    VLD1.8   {d10},[r0],r1
    VABD.U8  d18,d9,d8
    VABD.U8  d19,d6,d4
    VCGT.U8  d16,d0,d13
    TST      r12,#0xff
    VMAX.U8  d12,d18,d12
    VABD.U8  d17,d10,d8
    VMOVEQ.32 d16[0],r11
    TST      r12,#0xff00
    VCGT.U8  d19,d2,d19
    VCGT.U8  d12,d2,d12
    VMOVEQ.32 d16[1],r11
    VCGT.U8  d17,d2,d17
    VLD1.8   {d11},[r0]
    VAND     d16,d16,d12
    TST      r12,#4
    VAND     d12,d16,d17
    VAND     d17,d16,d19
    BNE      L0xf8
    SUB      r0,r0,r1,LSL #2
    SUB      r0,r0,r1
    BL       armVCM4P10_DeblockingLumabSLT4_unsafe
    VST1.8   {d30},[r0],r1
    VST1.8   {d29},[r0],r1
    SUB      r6,r0,r1,LSL #2
    VST1.8   {d24},[r0],r1
    ADDS     r9,r9,r9
    VST1.8   {d25},[r0]
    ADD      r0,r6,#8
    BCC      L0x38
    B        L0x130
L0xe4:
    ADD      r0,r0,#8
    ADDS     r9,r9,r9
    ADD      r5,r5,#2
    BCC      L0x38
    B        L0x130
L0xf8:
    SUB      r0,r0,r1,LSL #2
    SUB      r0,r0,r1,LSL #1
    BL       armVCM4P10_DeblockingLumabSGE4_unsafe
    VST1.8   {d31},[r0],r1
    VST1.8   {d30},[r0],r1
    VST1.8   {d29},[r0],r1
    SUB      r6,r0,r1,LSL #2
    VST1.8   {d24},[r0],r1
    ADDS     r9,r9,r9
    VST1.8   {d25},[r0],r1
    ADD      r5,r5,#2
    VST1.8   {d28},[r0]
    ADD      r0,r6,#8
    BCC      L0x38
L0x130:
    SUB      r0,r0,#0x10
    VLD1.8   {d0[]},[r7]
    ADD      r0,r0,r1,LSL #2
    VLD1.8   {d2[]},[r8]
    BNE      L0x38
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
    .endfunc

    .end

