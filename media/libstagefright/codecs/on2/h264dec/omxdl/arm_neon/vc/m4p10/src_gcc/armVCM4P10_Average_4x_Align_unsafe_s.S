/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_Average_4x4_Align0_unsafe
    .func   armVCM4P10_Average_4x4_Align0_unsafe
armVCM4P10_Average_4x4_Align0_unsafe:
    PUSH     {r4-r6,lr}
    LDR      r7, =0x80808080
    LDR      r12,[r2,#0]
    LDR      r10,[r0],r1
    LDR      lr,[r2,r3]
    LDR      r11,[r0],r1
    MVN      r12,r12
    MVN      lr,lr
    UHSUB8   r5,r10,r12
    UHSUB8   r4,r11,lr
    EOR      r5,r5,r7
    STR      r5,[r2],r3
    EOR      r4,r4,r7
    STR      r4,[r2],r3
    LDR      r10,[r0],r1
    LDR      r12,[r2,#0]
    LDR      r11,[r0],r1
    LDR      lr,[r2,r3]
    MVN      r12,r12
    UHSUB8   r5,r10,r12
    MVN      lr,lr
    UHSUB8   r4,r11,lr
    EOR      r5,r5,r7
    STR      r5,[r2],r3
    EOR      r4,r4,r7
    STR      r4,[r2],r3
    POP      {r4-r6,pc}
    .endfunc

    .global armVCM4P10_Average_4x4_Align2_unsafe
    .func   armVCM4P10_Average_4x4_Align2_unsafe
armVCM4P10_Average_4x4_Align2_unsafe:
    PUSH     {r4-r6,lr}
    LDR      r7, =0x80808080
    LDR      r4,[r0,#4]
    LDR      r10,[r0],r1
    LDR      r12,[r2,#0]
    LDR      lr,[r2,r3]
    LDR      r5,[r0,#4]
    LDR      r11,[r0],r1
    MVN      r12,r12
    MVN      lr,lr
    LSR      r10,r10,#16
    ORR      r10,r10,r4,LSL #16
    LSR      r11,r11,#16
    ORR      r11,r11,r5,LSL #16
    UHSUB8   r5,r10,r12
    UHSUB8   r4,r11,lr
    EOR      r5,r5,r7
    STR      r5,[r2],r3
    EOR      r4,r4,r7
    STR      r4,[r2],r3
    LDR      r4,[r0,#4]
    LDR      r10,[r0],r1
    LDR      r12,[r2,#0]
    LDR      lr,[r2,r3]
    LDR      r5,[r0,#4]
    LDR      r11,[r0],r1
    MVN      r12,r12
    MVN      lr,lr
    LSR      r10,r10,#16
    ORR      r10,r10,r4,LSL #16
    LSR      r11,r11,#16
    ORR      r11,r11,r5,LSL #16
    UHSUB8   r5,r10,r12
    UHSUB8   r4,r11,lr
    EOR      r5,r5,r7
    STR      r5,[r2],r3
    EOR      r4,r4,r7
    STR      r4,[r2],r3
    POP      {r4-r6,pc}
    .endfunc

    .global armVCM4P10_Average_4x4_Align3_unsafe
    .func   armVCM4P10_Average_4x4_Align3_unsafe
armVCM4P10_Average_4x4_Align3_unsafe:
    PUSH     {r4-r6,lr}
    LDR      r7, =0x80808080
    LDR      r4,[r0,#4]
    LDR      r10,[r0],r1
    LDR      r12,[r2,#0]
    LDR      lr,[r2,r3]
    LDR      r5,[r0,#4]
    LDR      r11,[r0],r1
    MVN      r12,r12
    MVN      lr,lr
    LSR      r10,r10,#24
    ORR      r10,r10,r4,LSL #8
    LSR      r11,r11,#24
    ORR      r11,r11,r5,LSL #8
    UHSUB8   r5,r10,r12
    UHSUB8   r4,r11,lr
    EOR      r5,r5,r7
    STR      r5,[r2],r3
    EOR      r4,r4,r7
    STR      r4,[r2],r3
    LDR      r4,[r0,#4]
    LDR      r10,[r0],r1
    LDR      r12,[r2,#0]
    LDR      lr,[r2,r3]
    LDR      r5,[r0,#4]
    LDR      r11,[r0],r1
    MVN      r12,r12
    MVN      lr,lr
    LSR      r10,r10,#24
    ORR      r10,r10,r4,LSL #8
    LSR      r11,r11,#24
    ORR      r11,r11,r5,LSL #8
    UHSUB8   r5,r10,r12
    UHSUB8   r4,r11,lr
    EOR      r5,r5,r7
    STR      r5,[r2],r3
    EOR      r4,r4,r7
    STR      r4,[r2],r3
    POP      {r4-r6,pc}
    .endfunc

    .end

