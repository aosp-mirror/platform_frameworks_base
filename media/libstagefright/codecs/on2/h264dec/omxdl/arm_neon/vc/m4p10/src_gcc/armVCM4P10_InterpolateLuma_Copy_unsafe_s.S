/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_InterpolateLuma_Copy4x4_unsafe
    .func   armVCM4P10_InterpolateLuma_Copy4x4_unsafe
armVCM4P10_InterpolateLuma_Copy4x4_unsafe:
    PUSH     {r4-r6,lr}
    AND      r12,r0,#3
    BIC      r0,r0,#3
    ADD      pc,pc,r12,LSL #2
    NOP
    B        Copy4x4Align0
    B        Copy4x4Align1
    B        Copy4x4Align2
    B        Copy4x4Align3
Copy4x4Align0:
    LDR      r4,[r0],r1
    LDR      r5,[r0],r1
    STR      r4,[r2],r3
    LDR      r8,[r0],r1
    STR      r5,[r2],r3
    LDR      r9,[r0],r1
    STR      r8,[r2],r3
    STR      r9,[r2],r3
    B        Copy4x4End
Copy4x4Align1:
    LDR      r5,[r0,#4]
    LDR      r4,[r0],r1
    LDR      r9,[r0,#4]
    LDR      r8,[r0],r1
    LSR      r4,r4,#8
    ORR      r4,r4,r5,LSL #24
    STR      r4,[r2],r3
    LSR      r8,r8,#8
    ORR      r8,r8,r9,LSL #24
    LDR      r5,[r0,#4]
    LDR      r4,[r0],r1
    STR      r8,[r2],r3
    LDR      r9,[r0,#4]
    LDR      r8,[r0],r1
    LSR      r4,r4,#8
    ORR      r4,r4,r5,LSL #24
    STR      r4,[r2],r3
    LSR      r8,r8,#8
    ORR      r8,r8,r9,LSL #24
    STR      r8,[r2],r3
    B        Copy4x4End
Copy4x4Align2:
    LDR      r5,[r0,#4]
    LDR      r4,[r0],r1
    LDR      r9,[r0,#4]
    LDR      r8,[r0],r1
    LSR      r4,r4,#16
    ORR      r4,r4,r5,LSL #16
    STR      r4,[r2],r3
    LSR      r8,r8,#16
    ORR      r8,r8,r9,LSL #16
    STR      r8,[r2],r3
    LDR      r5,[r0,#4]
    LDR      r4,[r0],r1
    LDR      r9,[r0,#4]
    LDR      r8,[r0],r1
    LSR      r4,r4,#16
    ORR      r4,r4,r5,LSL #16
    STR      r4,[r2],r3
    LSR      r8,r8,#16
    ORR      r8,r8,r9,LSL #16
    STR      r8,[r2],r3
    B        Copy4x4End
Copy4x4Align3:
    LDR      r5,[r0,#4]
    LDR      r4,[r0],r1
    LDR      r9,[r0,#4]
    LDR      r8,[r0],r1
    LSR      r4,r4,#24
    ORR      r4,r4,r5,LSL #8
    STR      r4,[r2],r3
    LSR      r8,r8,#24
    ORR      r8,r8,r9,LSL #8
    STR      r8,[r2],r3
    LDR      r5,[r0,#4]
    LDR      r4,[r0],r1
    LDR      r9,[r0,#4]
    LDR      r8,[r0],r1
    LSR      r4,r4,#24
    ORR      r4,r4,r5,LSL #8
    STR      r4,[r2],r3
    LSR      r8,r8,#24
    ORR      r8,r8,r9,LSL #8
    STR      r8,[r2],r3
Copy4x4End:
    POP      {r4-r6,pc}
    .endfunc

    .end

