/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
    .func   armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe:
    PUSH     {r4-r12,lr}
    VLD1.8   {d0,d1},[r0],r1
    VMOV.I16 d31,#0x14
    VMOV.I16 d30,#0x5
    VEXT.8   d4,d0,d1,#1
    VEXT.8   d2,d0,d1,#2
    VEXT.8   d3,d0,d1,#3
    VEXT.8   d5,d0,d1,#4
    VEXT.8   d1,d0,d1,#5
    VADDL.U8 q1,d2,d3
    VADDL.U8 q2,d4,d5
    VADDL.U8 q5,d0,d1
    VLD1.8   {d0,d1},[r0],r1
    VMLA.I16 d10,d2,d31
    VMUL.I16 d8,d4,d30
    VEXT.8   d4,d0,d1,#1
    VEXT.8   d2,d0,d1,#2
    VEXT.8   d3,d0,d1,#3
    VEXT.8   d5,d0,d1,#4
    VEXT.8   d1,d0,d1,#5
    VADDL.U8 q1,d2,d3
    VADDL.U8 q2,d4,d5
    VADDL.U8 q6,d0,d1
    VLD1.8   {d0,d1},[r0],r1
    VSUB.I16 d10,d10,d8
    VMLA.I16 d12,d2,d31
    VMUL.I16 d8,d4,d30
    VEXT.8   d4,d0,d1,#1
    VEXT.8   d2,d0,d1,#2
    VEXT.8   d3,d0,d1,#3
    VEXT.8   d5,d0,d1,#4
    VEXT.8   d1,d0,d1,#5
    VADDL.U8 q1,d2,d3
    VADDL.U8 q2,d4,d5
    VADDL.U8 q7,d0,d1
    VLD1.8   {d0,d1},[r0],r1
    VSUB.I16 d12,d12,d8
    VMLA.I16 d14,d2,d31
    VMUL.I16 d8,d4,d30
    VEXT.8   d4,d0,d1,#1
    VEXT.8   d2,d0,d1,#2
    VEXT.8   d3,d0,d1,#3
    VEXT.8   d5,d0,d1,#4
    VEXT.8   d1,d0,d1,#5
    VADDL.U8 q1,d2,d3
    VADDL.U8 q2,d4,d5
    VADDL.U8 q8,d0,d1
    VLD1.8   {d0,d1},[r0],r1
    VSUB.I16 d14,d14,d8
    VMLA.I16 d16,d2,d31
    VMUL.I16 d8,d4,d30
    VEXT.8   d4,d0,d1,#1
    VEXT.8   d2,d0,d1,#2
    VEXT.8   d3,d0,d1,#3
    VEXT.8   d5,d0,d1,#4
    VEXT.8   d1,d0,d1,#5
    VADDL.U8 q1,d2,d3
    VADDL.U8 q2,d4,d5
    VADDL.U8 q9,d0,d1
    VLD1.8   {d0,d1},[r0],r1
    VSUB.I16 d16,d16,d8
    VMLA.I16 d18,d2,d31
    VMUL.I16 d8,d4,d30
    VEXT.8   d4,d0,d1,#1
    VEXT.8   d2,d0,d1,#2
    VEXT.8   d3,d0,d1,#3
    VEXT.8   d5,d0,d1,#4
    VEXT.8   d1,d0,d1,#5
    VADDL.U8 q1,d2,d3
    VADDL.U8 q2,d4,d5
    VADDL.U8 q10,d0,d1
    VLD1.8   {d0,d1},[r0],r1
    VSUB.I16 d18,d18,d8
    VMLA.I16 d20,d2,d31
    VMUL.I16 d8,d4,d30
    VEXT.8   d4,d0,d1,#1
    VEXT.8   d2,d0,d1,#2
    VEXT.8   d3,d0,d1,#3
    VEXT.8   d5,d0,d1,#4
    VEXT.8   d1,d0,d1,#5
    VADDL.U8 q1,d2,d3
    VADDL.U8 q2,d4,d5
    VADDL.U8 q11,d0,d1
    VLD1.8   {d0,d1},[r0],r1
    VSUB.I16 d20,d20,d8
    VMLA.I16 d22,d2,d31
    VMUL.I16 d8,d4,d30
    VEXT.8   d4,d0,d1,#1
    VEXT.8   d2,d0,d1,#2
    VEXT.8   d3,d0,d1,#3
    VEXT.8   d5,d0,d1,#4
    VEXT.8   d1,d0,d1,#5
    VADDL.U8 q1,d2,d3
    VADDL.U8 q2,d4,d5
    VADDL.U8 q12,d0,d1
    VLD1.8   {d0,d1},[r0],r1
    VSUB.I16 d22,d22,d8
    VMLA.I16 d24,d2,d31
    VMUL.I16 d8,d4,d30
    VEXT.8   d4,d0,d1,#1
    VEXT.8   d2,d0,d1,#2
    VEXT.8   d3,d0,d1,#3
    VEXT.8   d5,d0,d1,#4
    VEXT.8   d1,d0,d1,#5
    VADDL.U8 q1,d2,d3
    VADDL.U8 q2,d4,d5
    VADDL.U8 q13,d0,d1
    VSUB.I16 d24,d24,d8
    VMLA.I16 d26,d2,d31
    VMUL.I16 d8,d4,d30
    VMOV.I32 q15,#0x14
    VMOV.I32 q14,#0x5
    VADDL.S16 q5,d10,d20
    VADDL.S16 q1,d14,d16
    VADDL.S16 q0,d12,d18
    VSUB.I16 d26,d26,d8
    VMLA.I32 q5,q1,q15
    VMUL.I32 q4,q0,q14
    VADDL.S16 q6,d12,d22
    VADDL.S16 q1,d16,d18
    VADDL.S16 q0,d14,d20
    VMLA.I32 q6,q1,q15
    VSUB.I32 q5,q5,q4
    VMUL.I32 q4,q0,q14
    VADDL.S16 q2,d14,d24
    VADDL.S16 q1,d18,d20
    VADDL.S16 q0,d16,d22
    VMLA.I32 q2,q1,q15
    VSUB.I32 q6,q6,q4
    VMUL.I32 q4,q0,q14
    VADDL.S16 q3,d16,d26
    VADDL.S16 q1,d20,d22
    VADDL.S16 q0,d18,d24
    VMLA.I32 q3,q1,q15
    VSUB.I32 q2,q2,q4
    VMLS.I32 q3,q0,q14
    VQRSHRUN.S32 d0,q5,#10
    VQRSHRUN.S32 d2,q6,#10
    VQRSHRUN.S32 d4,q2,#10
    VQRSHRUN.S32 d6,q3,#10
    VQMOVN.U16 d0,q0
    VQMOVN.U16 d2,q1
    VQMOVN.U16 d4,q2
    VQMOVN.U16 d6,q3
    POP      {r4-r12,pc}
    .endfunc

    .end

