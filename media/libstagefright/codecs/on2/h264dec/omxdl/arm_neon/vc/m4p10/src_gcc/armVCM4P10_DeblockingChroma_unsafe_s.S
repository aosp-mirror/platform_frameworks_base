/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_DeblockingChromabSLT4_unsafe
    .func   armVCM4P10_DeblockingChromabSLT4_unsafe
armVCM4P10_DeblockingChromabSLT4_unsafe:
    VLD1.32  {d18[0]},[r5]!
    VSUBL.U8 q11,d5,d9
    VMOV     d28,d18
    VSUBL.U8 q10,d8,d4
    VSHR.S16 q11,q11,#2
    VZIP.8   d18,d28
    VBIF     d18,d14,d16
    VRHADD.S16 q10,q11,q10
    VADD.I8  d31,d18,d15
    VQMOVN.S16 d20,q10
    VLD1.8   {d0[]},[r2]
    VMIN.S8  d20,d20,d31
    VNEG.S8  d31,d31
    VLD1.8   {d2[]},[r3]
    VMAX.S8  d20,d20,d31
    VMOVL.U8 q14,d4
    VMOVL.U8 q12,d8
    VADDW.S8 q14,q14,d20
    VSUBW.S8 q12,q12,d20
    VQMOVUN.S16 d29,q14
    VQMOVUN.S16 d24,q12
    BX       lr
    .endfunc

    .global armVCM4P10_DeblockingChromabSGE4_unsafe
    .func   armVCM4P10_DeblockingChromabSGE4_unsafe
armVCM4P10_DeblockingChromabSGE4_unsafe:
    VHADD.U8 d13,d4,d9
    VHADD.U8 d31,d8,d5
    VLD1.8   {d0[]},[r2]
    ADD      r5,r5,#4
    VLD1.8   {d2[]},[r3]
    VRHADD.U8 d13,d13,d5
    VRHADD.U8 d31,d31,d9
    BX       lr
    .endfunc

    .end

