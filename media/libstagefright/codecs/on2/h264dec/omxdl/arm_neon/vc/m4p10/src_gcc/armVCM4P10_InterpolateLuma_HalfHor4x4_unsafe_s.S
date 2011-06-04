/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
    .func   armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe:
    PUSH     {r4-r12,lr}
    VLD1.8   {d22,d23},[r0],r1
    VEXT.8   d10,d22,d23,#5
    VEXT.8   d12,d22,d23,#1
    VEXT.8   d14,d22,d23,#2
    VEXT.8   d15,d22,d23,#3
    VEXT.8   d13,d22,d23,#4
    VADDL.U8 q11,d22,d10
    VADDL.U8 q4,d14,d15
    VADDL.U8 q6,d12,d13
    VLD1.8   {d24,d25},[r0],r1
    VMLA.I16 d22,d8,d31
    VMUL.I16 d8,d12,d30
    VEXT.8   d10,d24,d25,#5
    VEXT.8   d12,d24,d25,#1
    VEXT.8   d16,d24,d25,#2
    VEXT.8   d17,d24,d25,#3
    VEXT.8   d13,d24,d25,#4
    VADDL.U8 q12,d24,d10
    VSUB.I16 d22,d22,d8
    VADDL.U8 q4,d16,d17
    VADDL.U8 q6,d12,d13
    VLD1.8   {d26,d27},[r0],r1
    VMLA.I16 d24,d8,d31
    VMUL.I16 d8,d12,d30
    VEXT.8   d10,d26,d27,#5
    VEXT.8   d12,d26,d27,#1
    VEXT.8   d18,d26,d27,#2
    VEXT.8   d19,d26,d27,#3
    VEXT.8   d13,d26,d27,#4
    VADDL.U8 q13,d26,d10
    VSUB.I16 d24,d24,d8
    VADDL.U8 q4,d18,d19
    VADDL.U8 q6,d12,d13
    VLD1.8   {d28,d29},[r0],r1
    VMLA.I16 d26,d8,d31
    VMUL.I16 d8,d12,d30
    VEXT.8   d10,d28,d29,#5
    VEXT.8   d12,d28,d29,#1
    VEXT.8   d20,d28,d29,#2
    VEXT.8   d21,d28,d29,#3
    VEXT.8   d13,d28,d29,#4
    VADDL.U8 q14,d28,d10
    VSUB.I16 d26,d26,d8
    VADDL.U8 q4,d20,d21
    VADDL.U8 q6,d12,d13
    VMLA.I16 d28,d8,d31
    VMLS.I16 d28,d12,d30
    VQRSHRUN.S16 d22,q11,#5
    VQRSHRUN.S16 d24,q12,#5
    VQRSHRUN.S16 d26,q13,#5
    VQRSHRUN.S16 d28,q14,#5
    POP      {r4-r12,pc}
    .endfunc

    .end

