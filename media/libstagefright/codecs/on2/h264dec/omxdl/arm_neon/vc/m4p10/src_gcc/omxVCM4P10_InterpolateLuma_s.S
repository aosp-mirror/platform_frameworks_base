/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global omxVCM4P10_InterpolateLuma
    .func   omxVCM4P10_InterpolateLuma
omxVCM4P10_InterpolateLuma:
    PUSH     {r4-r12,lr}
    VPUSH    {d8-d15}
    SUB      sp,sp,#0x10
    LDR      r6,[sp,#0x78]
    LDR      r7,[sp,#0x7c]
    LDR      r5,[sp,#0x80]
    LDR      r4,[sp,#0x84]
    ADD      r6,r6,r7,LSL #2
    ADD      r11,sp,#0
    VMOV.I16 d31,#0x14
    VMOV.I16 d30,#0x5
L0x2c:
    STM      r11,{r0-r3}
    ADD      pc,pc,r6,LSL #2
    B        L0x3f0
    B        L0x78
    B        L0xa8
    B        L0xdc
    B        L0x100
    B        L0x134
    B        L0x168
    B        L0x1a8
    B        L0x1f0
    B        L0x234
    B        L0x258
    B        L0x2b0
    B        L0x2d8
    B        L0x330
    B        L0x364
    B        L0x3a8
    B        L0x3f0
L0x78:
    ADD      r12,r0,r1,LSL #1
    VLD1.8   {d9},[r0],r1
    VLD1.8   {d11},[r12],r1
    VLD1.8   {d10},[r0]
    VLD1.8   {d12},[r12]
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d9[0]},[r2],r3
    VST1.32  {d11[0]},[r12],r3
    VST1.32  {d10[0]},[r2]
    VST1.32  {d12[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0xa8:
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
    VRHADD.U8 d22,d22,d14
    VRHADD.U8 d26,d26,d18
    VRHADD.U8 d24,d24,d16
    VRHADD.U8 d28,d28,d20
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d22[0]},[r2],r3
    VST1.32  {d26[0]},[r12],r3
    VST1.32  {d24[0]},[r2]
    VST1.32  {d28[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0xdc:
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d22[0]},[r2],r3
    VST1.32  {d26[0]},[r12],r3
    VST1.32  {d24[0]},[r2]
    VST1.32  {d28[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x100:
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
    VRHADD.U8 d22,d22,d15
    VRHADD.U8 d26,d26,d19
    VRHADD.U8 d24,d24,d17
    VRHADD.U8 d28,d28,d21
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d22[0]},[r2],r3
    VST1.32  {d26[0]},[r12],r3
    VST1.32  {d24[0]},[r2]
    VST1.32  {d28[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x134:
    SUB      r0,r0,r1,LSL #1
    BL       armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
    VRHADD.U8 d0,d0,d9
    VRHADD.U8 d4,d4,d11
    VRHADD.U8 d2,d2,d10
    VRHADD.U8 d6,d6,d12
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d0[0]},[r2],r3
    VST1.32  {d4[0]},[r12],r3
    VST1.32  {d2[0]},[r2]
    VST1.32  {d6[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x168:
    MOV      r8,r0
    SUB      r0,r0,r1,LSL #1
    BL       armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
    SUB      r0,r8,#2
    BL       armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
    VRHADD.U8 d22,d22,d0
    VRHADD.U8 d26,d26,d4
    VRHADD.U8 d24,d24,d2
    VRHADD.U8 d28,d28,d6
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d22[0]},[r2],r3
    VST1.32  {d26[0]},[r12],r3
    VST1.32  {d24[0]},[r2]
    VST1.32  {d28[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x1a8:
    SUB      r0,r0,r1,LSL #1
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
    VQRSHRUN.S16 d14,q7,#5
    VQRSHRUN.S16 d16,q8,#5
    VQRSHRUN.S16 d18,q9,#5
    VQRSHRUN.S16 d20,q10,#5
    VRHADD.U8 d0,d0,d14
    VRHADD.U8 d4,d4,d18
    VRHADD.U8 d2,d2,d16
    VRHADD.U8 d6,d6,d20
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d0[0]},[r2],r3
    VST1.32  {d4[0]},[r12],r3
    VST1.32  {d2[0]},[r2]
    VST1.32  {d6[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x1f0:
    MOV      r8,r0
    ADD      r0,r0,#1
    SUB      r0,r0,r1,LSL #1
    BL       armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
    SUB      r0,r8,#2
    BL       armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
    VRHADD.U8 d22,d22,d0
    VRHADD.U8 d26,d26,d4
    VRHADD.U8 d24,d24,d2
    VRHADD.U8 d28,d28,d6
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d22[0]},[r2],r3
    VST1.32  {d26[0]},[r12],r3
    VST1.32  {d24[0]},[r2]
    VST1.32  {d28[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x234:
    SUB      r0,r0,r1,LSL #1
    BL       armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d0[0]},[r2],r3
    VST1.32  {d4[0]},[r12],r3
    VST1.32  {d2[0]},[r2]
    VST1.32  {d6[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x258:
    SUB      r0,r0,r1,LSL #1
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
    VEXT.8   d18,d18,d19,#4
    VEXT.8   d20,d20,d21,#4
    VEXT.8   d22,d22,d23,#4
    VEXT.8   d24,d24,d25,#4
    VQRSHRUN.S16 d14,q9,#5
    VQRSHRUN.S16 d16,q10,#5
    VQRSHRUN.S16 d18,q11,#5
    VQRSHRUN.S16 d20,q12,#5
    VRHADD.U8 d0,d0,d14
    VRHADD.U8 d4,d4,d18
    VRHADD.U8 d2,d2,d16
    VRHADD.U8 d6,d6,d20
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d0[0]},[r2],r3
    VST1.32  {d4[0]},[r12],r3
    VST1.32  {d2[0]},[r2]
    VST1.32  {d6[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x2b0:
    SUB      r0,r0,r1,LSL #1
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d0[0]},[r2],r3
    VST1.32  {d4[0]},[r12],r3
    VST1.32  {d2[0]},[r2]
    VST1.32  {d6[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x2d8:
    SUB      r0,r0,r1,LSL #1
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
    VEXT.8   d18,d18,d19,#6
    VEXT.8   d20,d20,d21,#6
    VEXT.8   d22,d22,d23,#6
    VEXT.8   d24,d24,d25,#6
    VQRSHRUN.S16 d14,q9,#5
    VQRSHRUN.S16 d16,q10,#5
    VQRSHRUN.S16 d18,q11,#5
    VQRSHRUN.S16 d20,q12,#5
    VRHADD.U8 d0,d0,d14
    VRHADD.U8 d4,d4,d18
    VRHADD.U8 d2,d2,d16
    VRHADD.U8 d6,d6,d20
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d0[0]},[r2],r3
    VST1.32  {d4[0]},[r12],r3
    VST1.32  {d2[0]},[r2]
    VST1.32  {d6[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x330:
    SUB      r0,r0,r1,LSL #1
    BL       armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
    VRHADD.U8 d0,d0,d10
    VRHADD.U8 d4,d4,d12
    VRHADD.U8 d2,d2,d11
    VRHADD.U8 d6,d6,d13
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d0[0]},[r2],r3
    VST1.32  {d4[0]},[r12],r3
    VST1.32  {d2[0]},[r2]
    VST1.32  {d6[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x364:
    MOV      r8,r0
    SUB      r0,r0,r1,LSL #1
    BL       armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
    ADD      r0,r8,r1
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
    VRHADD.U8 d22,d22,d0
    VRHADD.U8 d26,d26,d4
    VRHADD.U8 d24,d24,d2
    VRHADD.U8 d28,d28,d6
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d22[0]},[r2],r3
    VST1.32  {d26[0]},[r12],r3
    VST1.32  {d24[0]},[r2]
    VST1.32  {d28[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x3a8:
    SUB      r0,r0,r1,LSL #1
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
    VQRSHRUN.S16 d14,q8,#5
    VQRSHRUN.S16 d16,q9,#5
    VQRSHRUN.S16 d18,q10,#5
    VQRSHRUN.S16 d20,q11,#5
    VRHADD.U8 d0,d0,d14
    VRHADD.U8 d4,d4,d18
    VRHADD.U8 d2,d2,d16
    VRHADD.U8 d6,d6,d20
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d0[0]},[r2],r3
    VST1.32  {d4[0]},[r12],r3
    VST1.32  {d2[0]},[r2]
    VST1.32  {d6[0]},[r12]
    ADD      r11,sp,#0
    B        L0x434
L0x3f0:
    MOV      r8,r0
    ADD      r0,r0,#1
    SUB      r0,r0,r1,LSL #1
    BL       armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
    ADD      r0,r8,r1
    SUB      r0,r0,#2
    BL       armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
    VRHADD.U8 d22,d22,d0
    VRHADD.U8 d26,d26,d4
    VRHADD.U8 d24,d24,d2
    VRHADD.U8 d28,d28,d6
    ADD      r12,r2,r3,LSL #1
    VST1.32  {d22[0]},[r2],r3
    VST1.32  {d26[0]},[r12],r3
    VST1.32  {d24[0]},[r2]
    VST1.32  {d28[0]},[r12]
    ADD      r11,sp,#0
L0x434:
    LDM      r11,{r0-r3}
    SUBS     r5,r5,#4
    ADD      r0,r0,#4
    ADD      r2,r2,#4
    BGT      L0x2c
    SUBS     r4,r4,#4
    LDR      r5,[sp,#0x80]
    ADD      r11,sp,#0
    ADD      r0,r0,r1,LSL #2
    ADD      r2,r2,r3,LSL #2
    SUB      r0,r0,r5
    SUB      r2,r2,r5
    BGT      L0x2c
    MOV      r0,#0
    ADD      sp,sp,#0x10
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
    .endfunc

    .end

