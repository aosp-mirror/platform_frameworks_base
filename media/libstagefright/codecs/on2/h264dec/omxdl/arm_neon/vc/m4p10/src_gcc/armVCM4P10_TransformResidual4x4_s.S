/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_TransformResidual4x4
    .func   armVCM4P10_TransformResidual4x4
armVCM4P10_TransformResidual4x4:
    VPUSH    {d8}
    VLD4.16  {d0,d1,d2,d3},[r1]
    VMOV.I16 d4,#0
    VADD.I16 d5,d0,d2
    VSUB.I16 d6,d0,d2
    VHADD.S16 d7,d1,d4
    VHADD.S16 d8,d3,d4
    VSUB.I16 d7,d7,d3
    VADD.I16 d8,d1,d8
    VADD.I16 d0,d5,d8
    VADD.I16 d1,d6,d7
    VSUB.I16 d2,d6,d7
    VSUB.I16 d3,d5,d8
    VTRN.16  d0,d1
    VTRN.16  d2,d3
    VTRN.32  q0,q1
    VADD.I16 d5,d0,d2
    VSUB.I16 d6,d0,d2
    VHADD.S16 d7,d1,d4
    VHADD.S16 d8,d3,d4
    VSUB.I16 d7,d7,d3
    VADD.I16 d8,d1,d8
    VADD.I16 d0,d5,d8
    VADD.I16 d1,d6,d7
    VSUB.I16 d2,d6,d7
    VSUB.I16 d3,d5,d8
    VRSHR.S16 d0,d0,#6
    VRSHR.S16 d1,d1,#6
    VRSHR.S16 d2,d2,#6
    VRSHR.S16 d3,d3,#6
    VST1.16  {d0,d1,d2,d3},[r0]
    VPOP     {d8}
    BX       lr
    .endfunc

    .end

