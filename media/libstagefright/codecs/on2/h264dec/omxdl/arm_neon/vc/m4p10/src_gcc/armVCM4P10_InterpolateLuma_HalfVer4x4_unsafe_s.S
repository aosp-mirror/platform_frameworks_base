/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
    .func   armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe:
    PUSH     {r4-r12,lr}
    VLD1.8   {d7},[r0],r1
    ADD      r12,r0,r1,LSL #2
    VLD1.8   {d8},[r0],r1
    VLD1.8   {d12},[r12],r1
    VLD1.8   {d9},[r0],r1
    VADDL.U8 q0,d7,d12
    VLD1.8   {d10},[r0],r1
    VLD1.8   {d13},[r12],r1
    VLD1.8   {d11},[r0],r1
    VLD1.8   {d14},[r12],r1
    VADDL.U8 q8,d8,d11
    VADDL.U8 q9,d9,d10
    VLD1.8   {d15},[r12],r1
    VMLS.I16 d0,d16,d30
    VMUL.I16 d20,d18,d31
    VADDL.U8 q8,d9,d12
    VADDL.U8 q9,d10,d11
    VADDL.U8 q1,d8,d13
    VMLS.I16 d2,d16,d30
    VMUL.I16 d21,d18,d31
    VADDL.U8 q8,d10,d13
    VADDL.U8 q9,d11,d12
    VADDL.U8 q2,d9,d14
    VMLS.I16 d4,d16,d30
    VMUL.I16 d22,d18,d31
    VADDL.U8 q8,d11,d14
    VADDL.U8 q3,d10,d15
    VADDL.U8 q9,d12,d13
    VMLS.I16 d6,d16,d30
    VADD.I16 d0,d0,d20
    VADD.I16 d2,d2,d21
    VADD.I16 d4,d4,d22
    VMLA.I16 d6,d18,d31
    VQRSHRUN.S16 d0,q0,#5
    VQRSHRUN.S16 d2,q1,#5
    VQRSHRUN.S16 d4,q2,#5
    VQRSHRUN.S16 d6,q3,#5
    POP      {r4-r12,pc}
    .endfunc

    .end

