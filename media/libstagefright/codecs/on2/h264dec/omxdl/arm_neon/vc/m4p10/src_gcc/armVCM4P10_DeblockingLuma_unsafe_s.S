/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_DeblockingLumabSLT4_unsafe
    .func   armVCM4P10_DeblockingLumabSLT4_unsafe
armVCM4P10_DeblockingLumabSLT4_unsafe:
    VSUBL.U8 q11,d5,d9
    VLD1.8   {d18[]},[r5]!
    VSUBL.U8 q10,d8,d4
    VLD1.8   {d19[]},[r5]!
    VSHR.S16 q11,q11,#2
    VEXT.8   d18,d18,d19,#4
    VAND     d19,d17,d15
    VBIF     d18,d14,d16
    VRHADD.S16 q10,q11,q10
    VRHADD.U8 d24,d4,d8
    VADD.I8  d31,d18,d19
    VAND     d19,d12,d15
    VQADD.U8 d23,d5,d18
    VQMOVN.S16 d20,q10
    VADD.I8  d31,d31,d19
    VQSUB.U8 d22,d5,d18
    VQADD.U8 d19,d9,d18
    VHADD.U8 d26,d24,d6
    VMIN.S8  d20,d20,d31
    VNEG.S8  d31,d31
    VQSUB.U8 d21,d9,d18
    VHADD.U8 d27,d24,d10
    VMAX.U8  d30,d26,d22
    VMAX.S8  d20,d20,d31
    VMOVL.U8 q14,d4
    VMOVL.U8 q12,d8
    VADDW.S8 q14,q14,d20
    VSUBW.S8 q12,q12,d20
    VQMOVUN.S16 d29,q14
    VQMOVUN.S16 d24,q12
    VMAX.U8  d25,d27,d21
    VMIN.U8  d30,d30,d23
    VMIN.U8  d25,d25,d19
    VBIF     d29,d4,d16
    VBIF     d30,d5,d17
    VBIF     d24,d8,d16
    VBIF     d25,d9,d12
    BX       lr
    .endfunc

    .global armVCM4P10_DeblockingLumabSGE4_unsafe
    .func   armVCM4P10_DeblockingLumabSGE4_unsafe
armVCM4P10_DeblockingLumabSGE4_unsafe:
    VSHR.U8  d19,d0,#2
    VADD.I8  d19,d19,d15
    VADDL.U8 q10,d8,d4
    VADD.I8  d19,d19,d15
    VADDL.U8 q11,d6,d9
    VADDW.U8 q12,q10,d5
    VCGT.U8  d19,d19,d13
    VSHR.U16 q11,q11,#1
    VHADD.U16 q11,q12,q11
    VADDW.U8 q12,q12,d6
    VADDL.U8 q13,d7,d6
    VAND     d17,d17,d19
    VHADD.U8 d28,d4,d9
    VSRA.U16 q13,q12,#1
    VAND     d12,d12,d19
    VQRSHRN.U16 d29,q11,#1
    VRHADD.U8 d28,d28,d5
    VQRSHRN.U16 d30,q12,#2
    VADDL.U8 q11,d10,d5
    VADDW.U8 q12,q10,d9
    VBIF     d29,d28,d17
    VQRSHRN.U16 d31,q13,#2
    VADDL.U8 q13,d11,d10
    VSHR.U16 q11,q11,#1
    VHADD.U16 q11,q12,q11
    VADDW.U8 q12,q12,d10
    VHADD.U8 d28,d8,d5
    VBIF     d29,d4,d16
    VBIF     d30,d5,d17
    VSRA.U16 q13,q12,#1
    VQRSHRN.U16 d25,q12,#2
    VQRSHRN.U16 d24,q11,#1
    VRHADD.U8 d22,d28,d9
    VBIF     d25,d9,d12
    VBIF     d31,d6,d17
    VBIF     d24,d22,d12
    VQRSHRN.U16 d28,q13,#2
    VBIF     d24,d8,d16
    VBIF     d28,d10,d12
    BX       lr
    .endfunc

    .end

