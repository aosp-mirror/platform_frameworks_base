/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global omxVCM4P10_TransformDequantChromaDCFromPair
    .func   omxVCM4P10_TransformDequantChromaDCFromPair
omxVCM4P10_TransformDequantChromaDCFromPair:
    push    {r4-r10, lr}
    ldr     r9, [r0,#0]
    vmov.i16    d0, #0
    mov     r8, #0x1f
    vst1.16    {d0}, [r1]
    ldrb    r6, [r9], #1
unpackLoop:
    tst     r6, #0x10
    ldrnesb r5, [r9, #1]
    ldrneb  r4, [r9], #2
    and     r7, r8, r6, lsl #1
    ldreqsb r4, [r9], #1
    orrne   r4, r4, r5, lsl #8
    tst     r6, #0x20
    ldreqb  r6, [r9], #1
    strh    r4, [r1, r7]
    beq     unpackLoop
    ldmia   r1, {r3, r4}
    str     r9, [r0, #0]
    ldr     r5, =armVCM4P10_QPDivTable
    ldr     r6, =armVCM4P10_VMatrixQPModTable
    saddsubx        r3, r3, r3
    saddsubx        r4, r4, r4
    ldrsb   r9, [r5, r2]
    ldrsb   r2, [r6, r2]
    sadd16  r5, r3, r4
    ssub16  r6, r3, r4
    lsl     r2, r2, r9
    vmov    d0, r5, r6
    vrev32.16  d0, d0
    vdup.16    d1, r2
    vmull.s16   q1, d0, d1
    vshrn.i32   d2, q1, #1
    vst1.16    {d2}, [r1]
    mov     r0, #0
    pop     {r4-r10, pc}
    .endfunc

    .end
