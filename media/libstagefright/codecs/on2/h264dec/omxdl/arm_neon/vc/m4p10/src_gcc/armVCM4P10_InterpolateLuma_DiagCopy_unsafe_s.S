/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_InterpolateLuma_HorDiagCopy_unsafe
    .func   armVCM4P10_InterpolateLuma_HorDiagCopy_unsafe
armVCM4P10_InterpolateLuma_HorDiagCopy_unsafe:
    PUSH     {r4-r6,lr}
    MOV      lr,#4
    LDR      r6, =0xfe00fe0
    LDR      r12, =0xff00ff
LoopStart1:
    LDR      r11,[r0,#0xc]
    LDR      r10,[r0,#8]
    LDR      r5,[r0,#4]
    LDR      r4,[r0],r1
    UQSUB16  r11,r11,r6
    UQSUB16  r10,r10,r6
    UQSUB16  r5,r5,r6
    UQSUB16  r4,r4,r6
    USAT16   r11,#13,r11
    USAT16   r10,#13,r10
    USAT16   r5,#13,r5
    USAT16   r4,#13,r4
    AND      r11,r12,r11,LSR #5
    AND      r10,r12,r10,LSR #5
    AND      r5,r12,r5,LSR #5
    AND      r4,r12,r4,LSR #5
    ORR      r11,r10,r11,LSL #8
    ORR      r10,r4,r5,LSL #8
    SUBS     lr,lr,#1
    STRD     r10,r11,[r7],#8
    BGT      LoopStart1
    SUB      r0,r7,#0x20
    MOV      r1,#8
    POP      {r4-r6,pc}
    .endfunc

    .global armVCM4P10_InterpolateLuma_VerDiagCopy_unsafe
    .func   armVCM4P10_InterpolateLuma_VerDiagCopy_unsafe
armVCM4P10_InterpolateLuma_VerDiagCopy_unsafe:
    PUSH     {r4-r6,lr}
    LDR      r6, =0xfe00fe0
    LDR      r12, =0xff00ff
    MOV      lr,#2
LoopStart:
    LDR      r11,[r0,#0xc]
    LDR      r10,[r0,#8]
    LDR      r5,[r0,#4]
    LDR      r4,[r0],r1
    UQSUB16  r11,r11,r6
    UQSUB16  r10,r10,r6
    UQSUB16  r5,r5,r6
    UQSUB16  r4,r4,r6
    USAT16   r11,#13,r11
    USAT16   r10,#13,r10
    USAT16   r5,#13,r5
    USAT16   r4,#13,r4
    AND      r11,r12,r11,LSR #5
    AND      r10,r12,r10,LSR #5
    AND      r5,r12,r5,LSR #5
    AND      r4,r12,r4,LSR #5
    ORR      r11,r10,r11,LSL #8
    ORR      r10,r4,r5,LSL #8
    PKHBT    r4,r10,r11,LSL #16
    STR      r4,[r7],#8
    PKHTB    r5,r11,r10,ASR #16
    STR      r5,[r7],#-4
    LDR      r11,[r0,#0xc]
    LDR      r10,[r0,#8]
    LDR      r5,[r0,#4]
    LDR      r4,[r0],r1
    UQSUB16  r11,r11,r6
    UQSUB16  r10,r10,r6
    UQSUB16  r5,r5,r6
    UQSUB16  r4,r4,r6
    USAT16   r11,#13,r11
    USAT16   r10,#13,r10
    USAT16   r5,#13,r5
    USAT16   r4,#13,r4
    AND      r11,r12,r11,LSR #5
    AND      r10,r12,r10,LSR #5
    AND      r5,r12,r5,LSR #5
    AND      r4,r12,r4,LSR #5
    ORR      r11,r10,r11,LSL #8
    ORR      r10,r4,r5,LSL #8
    PKHBT    r4,r10,r11,LSL #16
    SUBS     lr,lr,#1
    STR      r4,[r7],#8
    PKHTB    r5,r11,r10,ASR #16
    STR      r5,[r7],#4
    BGT      LoopStart
    SUB      r0,r7,#0x18
    MOV      r1,#4
    POP      {r4-r6,pc}
    .endfunc

    .end

