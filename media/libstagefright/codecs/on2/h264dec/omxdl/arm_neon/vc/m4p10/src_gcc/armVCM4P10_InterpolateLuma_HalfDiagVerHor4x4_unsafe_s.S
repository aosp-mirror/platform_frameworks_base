/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
    .func   armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe:
    PUSH     {r4-r12,lr}
    VLD1.8   {d0,d1},[r0],r1
    ADD      r12,r0,r1,LSL #2
    VMOV.I8  d30,#0x5
    VMOV.I8  d31,#0x14
    VLD1.8   {d10,d11},[r12],r1
    VLD1.8   {d2,d3},[r0],r1
    VLD1.8   {d12,d13},[r12],r1
    VADDL.U8 q9,d0,d10
    VLD1.8   {d4,d5},[r0],r1
    VADDL.U8 q0,d1,d11
    VLD1.8   {d6,d7},[r0],r1
    VADDL.U8 q10,d2,d12
    VLD1.8   {d8,d9},[r0],r1
    VMLAL.U8 q9,d4,d31
    VLD1.8   {d14,d15},[r12],r1
    VMLAL.U8 q0,d5,d31
    VLD1.8   {d16,d17},[r12],r1
    VMLAL.U8 q9,d6,d31
    VMLAL.U8 q10,d6,d31
    VMLSL.U8 q0,d3,d30
    VADDL.U8 q11,d4,d14
    VMLSL.U8 q9,d2,d30
    VADDL.U8 q1,d3,d13
    VMLAL.U8 q0,d7,d31
    VMLAL.U8 q10,d8,d31
    VMLSL.U8 q9,d8,d30
    VMLAL.U8 q1,d7,d31
    VMLSL.U8 q0,d9,d30
    VMLAL.U8 q11,d8,d31
    VMLSL.U8 q10,d4,d30
    VMLSL.U8 q1,d5,d30
    VADDL.U8 q2,d5,d15
    VMLAL.U8 q11,d10,d31
    VMLSL.U8 q10,d10,d30
    VMLAL.U8 q1,d9,d31
    VMLAL.U8 q2,d9,d31
    VADDL.U8 q12,d6,d16
    VMLSL.U8 q11,d6,d30
    VMLSL.U8 q1,d11,d30
    VMLSL.U8 q2,d7,d30
    VADDL.U8 q3,d7,d17
    VMLAL.U8 q12,d10,d31
    VMLSL.U8 q11,d12,d30
    VMLSL.U8 q2,d13,d30
    VMLAL.U8 q3,d11,d31
    VMLAL.U8 q12,d12,d31
    VEXT.8   d26,d18,d19,#2
    VMLAL.U8 q2,d11,d31
    VMLAL.U8 q3,d13,d31
    VMLSL.U8 q12,d8,d30
    VEXT.8   d27,d18,d19,#4
    VMOV.I16 d31,#0x14
    VMLSL.U8 q3,d9,d30
    VMLSL.U8 q12,d14,d30
    VEXT.8   d29,d19,d0,#2
    VEXT.8   d28,d18,d19,#6
    VMLSL.U8 q3,d15,d30
    VADDL.S16 q0,d18,d29
    VADD.I16 d27,d27,d28
    VMOV.I16 d30,#0x5
    VADD.I16 d26,d26,d19
    VMLAL.S16 q0,d27,d31
    VEXT.8   d27,d20,d21,#4
    VEXT.8   d28,d20,d21,#6
    VEXT.8   d29,d21,d2,#2
    VMLSL.S16 q0,d26,d30
    VEXT.8   d26,d20,d21,#2
    VADDL.S16 q1,d20,d29
    VADD.I16 d27,d27,d28
    VADD.I16 d26,d26,d21
    VEXT.8   d28,d22,d23,#6
    VMLAL.S16 q1,d27,d31
    VEXT.8   d29,d23,d4,#2
    VEXT.8   d27,d22,d23,#4
    VEXT.8   d8,d22,d23,#2
    VADDL.S16 q2,d22,d29
    VMLSL.S16 q1,d26,d30
    VADD.I16 d27,d27,d28
    VADD.I16 d26,d8,d23
    VEXT.8   d28,d24,d25,#6
    VMLAL.S16 q2,d27,d31
    VEXT.8   d27,d24,d25,#4
    VEXT.8   d29,d25,d6,#2
    VADD.I16 d27,d27,d28
    VEXT.8   d8,d24,d25,#2
    VADDL.S16 q3,d24,d29
    VMLSL.S16 q2,d26,d30
    VMLAL.S16 q3,d27,d31
    VADD.I16 d8,d8,d25
    VMLSL.S16 q3,d8,d30
    VQRSHRUN.S32 d0,q0,#10
    VQRSHRUN.S32 d2,q1,#10
    VQRSHRUN.S32 d4,q2,#10
    VQRSHRUN.S32 d6,q3,#10
    VQMOVN.U16 d0,q0
    VQMOVN.U16 d2,q1
    VQMOVN.U16 d4,q2
    VQMOVN.U16 d6,q3
    POP      {r4-r12,pc}
    .endfunc

    .end

