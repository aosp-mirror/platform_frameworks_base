/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
    .func   armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
armVCM4P10_InterpolateLuma_HorAlign9x_unsafe:
    MOV      r12,r8
    AND      r7,r0,#3
    BIC      r0,r0,#3
    ADD      pc,pc,r7,LSL #2
    NOP
    B        Copy0toAligned
    B        Copy1toAligned
    B        Copy2toAligned
    B        Copy3toAligned
Copy0toAligned:
    LDM      r0,{r7,r10,r11}
    SUBS     r9,r9,#1
    ADD      r0,r0,r1
    STM      r8!,{r7,r10,r11}
    BGT      Copy0toAligned
    B        CopyEnd
Copy1toAligned:
    LDM      r0,{r7,r10,r11}
    SUBS     r9,r9,#1
    ADD      r0,r0,r1
    LSR      r7,r7,#8
    ORR      r7,r7,r10,LSL #24
    LSR      r10,r10,#8
    ORR      r10,r10,r11,LSL #24
    LSR      r11,r11,#8
    STM      r8!,{r7,r10,r11}
    BGT      Copy1toAligned
    B        CopyEnd
Copy2toAligned:
    LDM      r0,{r7,r10,r11}
    SUBS     r9,r9,#1
    ADD      r0,r0,r1
    LSR      r7,r7,#16
    ORR      r7,r7,r10,LSL #16
    LSR      r10,r10,#16
    ORR      r10,r10,r11,LSL #16
    LSR      r11,r11,#16
    STM      r8!,{r7,r10,r11}
    BGT      Copy2toAligned
    B        CopyEnd
Copy3toAligned:
    LDM      r0,{r7,r10,r11}
    SUBS     r9,r9,#1
    ADD      r0,r0,r1
    LSR      r7,r7,#24
    ORR      r7,r7,r10,LSL #8
    LSR      r10,r10,#24
    ORR      r10,r10,r11,LSL #8
    LSR      r11,r11,#24
    STM      r8!,{r7,r10,r11}
    BGT      Copy3toAligned
CopyEnd:
    MOV      r0,r12
    MOV      r1,#0xc
    BX       lr
    .endfunc

    .global armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
    .func   armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
armVCM4P10_InterpolateLuma_VerAlign4x_unsafe:
    AND      r7,r0,#3
    BIC      r0,r0,#3
    ADD      pc,pc,r7,LSL #2
    NOP
    B        Copy0toVAligned
    B        Copy1toVAligned
    B        Copy2toVAligned
    B        Copy3toVAligned
Copy0toVAligned:
    LDR      r7,[r0],r1
    SUBS     r9,r9,#1
    STR      r7,[r8],#4
    BGT      Copy0toVAligned
    B        CopyVEnd
Copy1toVAligned:
    LDR      r10,[r0,#4]
    LDR      r7,[r0],r1
    SUBS     r9,r9,#1
    LSL      r10,r10,#24
    ORR      r7,r10,r7,LSR #8
    STR      r7,[r8],#4
    BGT      Copy1toVAligned
    B        CopyVEnd
Copy2toVAligned:
    LDR      r10,[r0,#4]
    LDR      r7,[r0],r1
    SUBS     r9,r9,#1
    LSL      r10,r10,#16
    ORR      r7,r10,r7,LSR #16
    STR      r7,[r8],#4
    BGT      Copy2toVAligned
    B        CopyVEnd
Copy3toVAligned:
    LDR      r10,[r0,#4]
    LDR      r7,[r0],r1
    SUBS     r9,r9,#1
    LSL      r10,r10,#8
    ORR      r7,r10,r7,LSR #24
    STR      r7,[r8],#4
    BGT      Copy3toVAligned
CopyVEnd:
    SUB      r0,r8,#0x1c
    MOV      r1,#4
    BX       lr
    .endfunc

    .end

