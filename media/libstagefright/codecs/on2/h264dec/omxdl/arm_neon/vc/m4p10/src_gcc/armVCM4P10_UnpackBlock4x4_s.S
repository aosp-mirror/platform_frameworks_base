/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_UnpackBlock4x4
    .func   armVCM4P10_UnpackBlock4x4
armVCM4P10_UnpackBlock4x4:
    PUSH     {r4-r8,lr}
    LDR      r2,[r0,#0]
    MOV      r7,#0x1f
    MOV      r4,#0
    MOV      r5,#0
    LDRB     r3,[r2],#1
    STRD     r4,r5,[r1,#0]
    STRD     r4,r5,[r1,#8]
    STRD     r4,r5,[r1,#0x10]
    STRD     r4,r5,[r1,#0x18]
unpackLoop:
    TST      r3,#0x10
    LDRNESB  r5,[r2,#1]
    LDRNEB   r4,[r2],#2
    AND      r6,r7,r3,LSL #1
    LDREQSB  r4,[r2],#1
    ORRNE    r4,r4,r5,LSL #8
    TST      r3,#0x20
    LDREQB   r3,[r2],#1
    STRH     r4,[r1,r6]
    BEQ      unpackLoop
    STR      r2,[r0,#0]
    POP      {r4-r8,pc}
    .endfunc
    .end
