/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global omxVCM4P10_DeblockLuma_I
    .func   omxVCM4P10_DeblockLuma_I
omxVCM4P10_DeblockLuma_I:
    PUSH     {r4-r9,lr}
    MOVS     r6,r0
    SUB      sp,sp,#0xc
    MOV      r9,r1
    MOV      r7,r2
    MOV      r8,r3
    LDR      r4,[sp,#0x28]
    LDR      r5,[sp,#0x2c]
    BEQ      L0x58
    TST      r6,#7
    TSTEQ    r9,#7
    BNE      L0x58
    CMP      r7,#0
    CMPNE    r8,#0
    CMPNE    r4,#0
    BEQ      L0x58
    TST      r4,#3
    BNE      L0x58
    CMP      r5,#0
    BEQ      L0x58
    TST      r5,#3
    BEQ      L0x64
L0x58:
    MVN      r0,#4
L0x5c:
    ADD      sp,sp,#0xc
    POP      {r4-r9,pc}
L0x64:
    STR      r4,[sp,#0]
    MOV      r3,r8
    STR      r5,[sp,#4]
    MOV      r2,r7
    MOV      r1,r9
    MOV      r0,r6
    BL       omxVCM4P10_FilterDeblockingLuma_VerEdge_I
    CMP      r0,#0
    BNE      L0x5c
    ADD      r3,r5,#0x10
    ADD      r2,r4,#0x10
    STR      r3,[sp,#4]
    STR      r2,[sp,#0]
    ADD      r3,r8,#2
    ADD      r2,r7,#2
    MOV      r1,r9
    MOV      r0,r6
    BL       omxVCM4P10_FilterDeblockingLuma_HorEdge_I
    ADD      sp,sp,#0xc
    POP      {r4-r9,pc}
    .endfunc

    .end

