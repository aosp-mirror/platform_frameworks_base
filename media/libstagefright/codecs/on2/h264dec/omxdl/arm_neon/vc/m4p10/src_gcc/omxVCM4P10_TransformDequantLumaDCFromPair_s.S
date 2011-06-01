/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_InvTransformDequantLumaDC4x4
    .func   armVCM4P10_InvTransformDequantLumaDC4x4
armVCM4P10_InvTransformDequantLumaDC4x4:
    PUSH     {r4-r6,lr}
    VPUSH    {d8-d13}
    VLD4.16  {d0,d1,d2,d3},[r0]
    LDR      r2, =armVCM4P10_QPDivTable
    LDR      r3, =armVCM4P10_VMatrixQPModTable
    VADD.I16 d4,d0,d1
    VADD.I16 d5,d2,d3
    VSUB.I16 d6,d0,d1
    LDRSB    r4,[r2,r1]
    VSUB.I16 d7,d2,d3
    LDRSB    r5,[r3,r1]
    VADD.I16 d0,d4,d5
    VSUB.I16 d1,d4,d5
    VSUB.I16 d2,d6,d7
    LSL      r5,r5,r4
    VADD.I16 d3,d6,d7
    VTRN.16  d0,d1
    VTRN.16  d2,d3
    VTRN.32  q0,q1
    VADD.I16 d4,d0,d1
    VADD.I16 d5,d2,d3
    VSUB.I16 d6,d0,d1
    VSUB.I16 d7,d2,d3
    VADD.I16 d0,d4,d5
    VSUB.I16 d1,d4,d5
    VSUB.I16 d2,d6,d7
    VADD.I16 d3,d6,d7
    VDUP.16  d5,r5
    VMOV.I32 q3,#0x2
    VMOV.I32 q4,#0x2
    VMOV.I32 q5,#0x2
    VMOV.I32 q6,#0x2
    VMLAL.S16 q3,d0,d5
    VMLAL.S16 q4,d1,d5
    VMLAL.S16 q5,d2,d5
    VMLAL.S16 q6,d3,d5
    VSHRN.I32 d0,q3,#2
    VSHRN.I32 d1,q4,#2
    VSHRN.I32 d2,q5,#2
    VSHRN.I32 d3,q6,#2
    VST1.16  {d0,d1,d2,d3},[r0]
    VPOP     {d8-d13}
    POP      {r4-r6,pc}
    .endfunc

.global omxVCM4P10_TransformDequantLumaDCFromPair
.func   omxVCM4P10_TransformDequantLumaDCFromPair
omxVCM4P10_TransformDequantLumaDCFromPair:
    PUSH     {r4-r6,lr}
    MOV      r4,r1
    MOV      r5,r2
    BL       armVCM4P10_UnpackBlock4x4
    MOV      r0,r4
    MOV      r1,r5
    BL       armVCM4P10_InvTransformDequantLumaDC4x4
    MOV      r0,#0
    POP      {r4-r6,pc}
    .endfunc

    .end

