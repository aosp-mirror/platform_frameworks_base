/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon

    .section .rodata
    .align 4

armVCM4P10_WidthBranchTableMVIsNotZero:
    .word   WidthIs2MVIsNotZero, WidthIs2MVIsNotZero
    .word   WidthIs4MVIsNotZero, WidthIs4MVIsNotZero
    .word   WidthIs8MVIsNotZero

armVCM4P10_WidthBranchTableMVIsZero:
    .word   WidthIs2MVIsZero, WidthIs2MVIsZero
    .word   WidthIs4MVIsZero, WidthIs4MVIsZero
    .word   WidthIs8MVIsZero

    .text

    .global armVCM4P10_Interpolate_Chroma
    .func   armVCM4P10_Interpolate_Chroma
armVCM4P10_Interpolate_Chroma:
    PUSH     {r4-r12,lr}
    VPUSH    {d8-d15}
    LDRD     r6,r7,[sp,#0x70]
    LDRD     r4,r5,[sp,#0x68]
    RSB      r8,r6,#8
    RSB      r9,r7,#8
    CMN      r6,r7
    MOV      r10,#1
    LDREQ    r11, =armVCM4P10_WidthBranchTableMVIsZero
    SUB      lr,r1,r10
    LDRNE    r11, =armVCM4P10_WidthBranchTableMVIsNotZero
    VLD1.8   {d0},[r0],r10
    SMULBB   r12,r8,r9
    SMULBB   r9,r6,r9
    VLD1.8   {d1},[r0],lr
    SMULBB   r8,r8,r7
    SMULBB   r6,r6,r7
    VDUP.8   d12,r12
    VDUP.8   d13,r9
    VDUP.8   d14,r8
    VDUP.8   d15,r6
    LDR      pc,[r11,r4,LSL #1]

WidthIs8MVIsNotZero:
    VLD1.8   {d2},[r0],r10
    VMULL.U8 q2,d0,d12
    VLD1.8   {d3},[r0],lr
    VMULL.U8 q3,d2,d12
    VLD1.8   {d16},[r0],r10
    VMLAL.U8 q2,d1,d13
    VLD1.8   {d17},[r0],lr
    VMULL.U8 q11,d16,d12
    VMLAL.U8 q3,d3,d13
    VLD1.8   {d18},[r0],r10
    VMLAL.U8 q2,d2,d14
    VMLAL.U8 q11,d17,d13
    VMULL.U8 q12,d18,d12
    VLD1.8   {d19},[r0],lr
    VMLAL.U8 q3,d16,d14
    VLD1.8   {d0},[r0],r10
    VMLAL.U8 q12,d19,d13
    VMLAL.U8 q11,d18,d14
    VMLAL.U8 q2,d3,d15
    VLD1.8   {d1},[r0],lr
    VMLAL.U8 q12,d0,d14
    VMLAL.U8 q3,d17,d15
    VMLAL.U8 q11,d19,d15
    SUBS     r5,r5,#4
    VMLAL.U8 q12,d1,d15
    VQRSHRN.U16 d8,q2,#6
    VQRSHRN.U16 d9,q3,#6
    VQRSHRN.U16 d20,q11,#6
    VST1.64  {d8},[r2],r3
    VQRSHRN.U16 d21,q12,#6
    VST1.64  {d9},[r2],r3
    VST1.64  {d20},[r2],r3
    VST1.64  {d21},[r2],r3
    BGT      WidthIs8MVIsNotZero
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}

WidthIs4MVIsNotZero:
    VLD1.8   {d2},[r0],r10
    VMULL.U8 q2,d0,d12
    VMULL.U8 q3,d2,d12
    VLD1.8   {d3},[r0],lr
    VMLAL.U8 q2,d1,d13
    VMLAL.U8 q3,d3,d13
    VLD1.8   {d0},[r0],r10
    VMLAL.U8 q2,d2,d14
    VMLAL.U8 q3,d0,d14
    VLD1.8   {d1},[r0],lr
    SUBS     r5,r5,#2
    VMLAL.U8 q3,d1,d15
    VMLAL.U8 q2,d3,d15
    VQRSHRN.U16 d9,q3,#6
    VQRSHRN.U16 d8,q2,#6
    VST1.32  {d8[0]},[r2],r3
    VST1.32  {d9[0]},[r2],r3
    BGT      WidthIs4MVIsNotZero
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}

WidthIs2MVIsNotZero:
    VLD1.8   {d2},[r0],r10
    VMULL.U8 q2,d0,d12
    VMULL.U8 q3,d2,d12
    VLD1.8   {d3},[r0],lr
    VMLAL.U8 q2,d1,d13
    VMLAL.U8 q3,d3,d13
    VLD1.8   {d0},[r0],r10
    VMLAL.U8 q2,d2,d14
    VMLAL.U8 q3,d0,d14
    VLD1.8   {d1},[r0],lr
    SUBS     r5,r5,#2
    VMLAL.U8 q3,d1,d15
    VMLAL.U8 q2,d3,d15
    VQRSHRN.U16 d9,q3,#6
    VQRSHRN.U16 d8,q2,#6
    VST1.16  {d8[0]},[r2],r3
    VST1.16  {d9[0]},[r2],r3
    BGT      WidthIs2MVIsNotZero
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}

WidthIs8MVIsZero:
    SUB      r0,r0,r1
WidthIs8LoopMVIsZero:
    VLD1.8   {d0},[r0],r1
    SUBS     r5,r5,#2
    VLD1.8   {d1},[r0],r1
    VST1.64  {d0},[r2],r3
    VST1.64  {d1},[r2],r3
    BGT      WidthIs8LoopMVIsZero
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}

WidthIs4MVIsZero:
    VLD1.8   {d1},[r0],r1
    SUBS     r5,r5,#2
    VST1.32  {d0[0]},[r2],r3
    VLD1.8   {d0},[r0],r1
    VST1.32  {d1[0]},[r2],r3
    BGT      WidthIs4MVIsZero
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}

WidthIs2MVIsZero:
    VLD1.8   {d1},[r0],r1
    SUBS     r5,r5,#2
    VST1.16  {d0[0]},[r2],r3
    VLD1.8   {d0},[r0],r1
    VST1.16  {d1[0]},[r2],r3
    BGT      WidthIs2MVIsZero
    MOV      r0,#0
    VPOP     {d8-d15}
    POP      {r4-r12,pc}
    .endfunc

    .end

