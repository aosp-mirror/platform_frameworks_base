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

armVCM4P10_pSwitchTable4x4:
    .word OMX_VC_4x4_VERT,     OMX_VC_4x4_HOR
    .word OMX_VC_4x4_DC,       OMX_VC_4x4_DIAG_DL
    .word OMX_VC_4x4_DIAG_DR,  OMX_VC_4x4_VR
    .word OMX_VC_4x4_HD,       OMX_VC_4x4_VL
    .word OMX_VC_4x4_HU

    .text

    .global omxVCM4P10_PredictIntra_4x4
    .func   omxVCM4P10_PredictIntra_4x4
omxVCM4P10_PredictIntra_4x4:
    PUSH     {r4-r12,lr}
    VPUSH    {d8-d12}
    LDR      r8, =armVCM4P10_pSwitchTable4x4
    LDRD     r6,r7,[sp,#0x58]
    LDRD     r4,r5,[sp,#0x50]
    LDR      pc,[r8,r6,LSL #2]
OMX_VC_4x4_HOR:
    ADD      r9,r0,r4
    ADD      r10,r4,r4
    VLD1.8   {d0[]},[r0],r10
    VLD1.8   {d1[]},[r9],r10
    VLD1.8   {d2[]},[r0]
    VLD1.8   {d3[]},[r9]
    ADD      r11,r3,r5
    ADD      r12,r5,r5
    VST1.32  {d0[0]},[r3],r12
    VST1.32  {d1[0]},[r11],r12
    VST1.32  {d2[0]},[r3]
    VST1.32  {d3[0]},[r11]
    B        L0x348
OMX_VC_4x4_VERT:
    VLD1.32  {d0[0]},[r1]
    ADD      r11,r3,r5
    ADD      r12,r5,r5
L0x58:
    VST1.32  {d0[0]},[r3],r12
    VST1.32  {d0[0]},[r11],r12
    VST1.32  {d0[0]},[r3]
    VST1.32  {d0[0]},[r11]
    B        L0x348
OMX_VC_4x4_DC:
    TST      r7,#2
    BEQ      L0xdc
    ADD      r9,r0,r4
    ADD      r10,r4,r4
    VLD1.8   {d0[0]},[r0],r10
    VLD1.8   {d0[1]},[r9],r10
    VLD1.8   {d0[2]},[r0]
    VLD1.8   {d0[3]},[r9]
    TST      r7,#1
    BEQ      L0xbc
    VLD1.32  {d0[1]},[r1]
    MOV      r0,#0
    VPADDL.U8 d1,d0
    VPADDL.U16 d1,d1
    VPADDL.U32 d1,d1
    VRSHR.U64 d1,d1,#3
    ADD      r11,r3,r5
    ADD      r12,r5,r5
    VDUP.8   d0,d1[0]
    B        L0x58
L0xbc:
    MOV      r0,#0
    VPADDL.U8 d1,d0
    VPADDL.U16 d1,d1
    VRSHR.U32 d1,d1,#2
    ADD      r11,r3,r5
    ADD      r12,r5,r5
    VDUP.8   d0,d1[0]
    B        L0x58
L0xdc:
    TST      r7,#1
    BEQ      L0x108
    VLD1.32  {d0[0]},[r1]
    MOV      r0,#0
    VPADDL.U8 d1,d0
    VPADDL.U16 d1,d1
    VRSHR.U32 d1,d1,#2
    ADD      r11,r3,r5
    ADD      r12,r5,r5
    VDUP.8   d0,d1[0]
    B        L0x58
L0x108:
    VMOV.I8  d0,#0x80
    MOV      r0,#0
    ADD      r11,r3,r5
    ADD      r12,r5,r5
    B        L0x58
OMX_VC_4x4_DIAG_DL:
    TST      r7,#0x40
    BEQ      L0x138
    VLD1.8   {d3},[r1]
    VDUP.8   d2,d3[7]
    VEXT.8   d4,d3,d2,#1
    VEXT.8   d5,d3,d2,#2
    B        L0x14c
L0x138:
    VLD1.32  {d0[1]},[r1]
    VDUP.8   d2,d0[7]
    VEXT.8   d3,d0,d2,#4
    VEXT.8   d4,d0,d2,#5
    VEXT.8   d5,d0,d2,#6
L0x14c:
    VHADD.U8 d6,d3,d5
    VRHADD.U8 d6,d6,d4
    VST1.32  {d6[0]},[r3],r5
    VEXT.8   d6,d6,d6,#1
    VST1.32  {d6[0]},[r3],r5
    VEXT.8   d6,d6,d6,#1
    VST1.32  {d6[0]},[r3],r5
    VEXT.8   d6,d6,d6,#1
    VST1.32  {d6[0]},[r3]
    B        L0x348
OMX_VC_4x4_DIAG_DR:
    VLD1.32  {d0[0]},[r1]
    VLD1.8   {d1[7]},[r2]
    ADD      r9,r0,r4
    ADD      r10,r4,r4
    ADD      r1,r3,r5
    VLD1.8   {d1[6]},[r0],r10
    VLD1.8   {d1[5]},[r9],r10
    VLD1.8   {d1[4]},[r0]
    VLD1.8   {d1[3]},[r9]
    VEXT.8   d3,d1,d0,#3
    ADD      r4,r1,r5
    VEXT.8   d4,d1,d0,#4
    ADD      r6,r4,r5
    VEXT.8   d5,d1,d0,#5
    VHADD.U8 d6,d3,d5
    VRHADD.U8 d6,d6,d4
    VST1.32  {d6[0]},[r6]
    VEXT.8   d6,d6,d6,#1
    VST1.32  {d6[0]},[r4]
    VEXT.8   d6,d6,d6,#1
    VST1.32  {d6[0]},[r1]
    VEXT.8   d6,d6,d6,#1
    VST1.32  {d6[0]},[r3]
    B        L0x348
OMX_VC_4x4_VR:
    VLD1.32  {d0[0]},[r1]
    VLD1.8   {d0[7]},[r2]
    VLD1.8   {d1[7]},[r0],r4
    VLD1.8   {d2[7]},[r0],r4
    VLD1.8   {d1[6]},[r0]
    VEXT.8   d12,d0,d0,#7
    VEXT.8   d3,d1,d12,#6
    VEXT.8   d4,d2,d12,#7
    VEXT.8   d5,d1,d0,#7
    VEXT.8   d6,d2,d0,#7
    VEXT.8   d11,d1,d12,#7
    VHADD.U8 d8,d6,d12
    VRHADD.U8 d8,d8,d11
    VHADD.U8 d7,d3,d5
    VRHADD.U8 d7,d7,d4
    VEXT.8   d10,d8,d8,#1
    ADD      r11,r3,r5
    ADD      r12,r5,r5
    VEXT.8   d9,d7,d7,#1
    VST1.32  {d10[0]},[r3],r12
    VST1.32  {d9[0]},[r11],r12
    VST1.32  {d8[0]},[r3],r12
    VST1.32  {d7[0]},[r11]
    B        L0x348
OMX_VC_4x4_HD:
    VLD1.8   {d0},[r1]
    VLD1.8   {d1[7]},[r2]
    ADD      r9,r0,r4
    ADD      r10,r4,r4
    VLD1.8   {d1[6]},[r0],r10
    VLD1.8   {d1[5]},[r9],r10
    VLD1.8   {d1[4]},[r0]
    VLD1.8   {d1[3]},[r9]
    VEXT.8   d3,d1,d0,#3
    VEXT.8   d4,d1,d0,#2
    VEXT.8   d5,d1,d0,#1
    VHADD.U8 d7,d3,d5
    VRHADD.U8 d7,d7,d4
    VRHADD.U8 d8,d4,d3
    VSHL.I64 d8,d8,#24
    VSHL.I64 d6,d7,#16
    VZIP.8   d8,d6
    VEXT.8   d7,d7,d7,#6
    VEXT.8   d8,d6,d7,#2
    ADD      r11,r3,r5
    ADD      r12,r5,r5
    VST1.32  {d8[1]},[r3],r12
    VST1.32  {d6[1]},[r11],r12
    VST1.32  {d8[0]},[r3]
    VST1.32  {d6[0]},[r11]
    B        L0x348
OMX_VC_4x4_VL:
    TST      r7,#0x40
    BEQ      L0x2b4
    VLD1.8   {d3},[r1]
    VEXT.8   d4,d3,d3,#1
    VEXT.8   d5,d4,d4,#1
    B        L0x2c8
L0x2b4:
    VLD1.32  {d0[1]},[r1]
    VDUP.8   d2,d0[7]
    VEXT.8   d3,d0,d2,#4
    VEXT.8   d4,d0,d2,#5
    VEXT.8   d5,d0,d2,#6
L0x2c8:
    VRHADD.U8 d7,d4,d3
    VHADD.U8 d10,d3,d5
    VRHADD.U8 d10,d10,d4
    VEXT.8   d8,d7,d7,#1
    ADD      r11,r3,r5
    ADD      r12,r5,r5
    VEXT.8   d9,d10,d8,#1
    VST1.32  {d7[0]},[r3],r12
    VST1.32  {d10[0]},[r11],r12
    VST1.32  {d8[0]},[r3]
    VST1.32  {d9[0]},[r11]
    B        L0x348
OMX_VC_4x4_HU:
    ADD      r9,r0,r4
    ADD      r10,r4,r4
    VLD1.8   {d1[4]},[r0],r10
    VLD1.8   {d1[5]},[r9],r10
    VLD1.8   {d1[6]},[r0]
    VLD1.8   {d1[7]},[r9]
    VDUP.8   d2,d1[7]
    VEXT.8   d3,d1,d2,#4
    VEXT.8   d4,d1,d2,#5
    VEXT.8   d5,d1,d2,#6
    VHADD.U8 d7,d3,d5
    VRHADD.U8 d7,d7,d4
    VRHADD.U8 d8,d4,d3
    VZIP.8   d8,d7
    VST1.32  {d8[0]},[r3],r5
    VEXT.8   d8,d8,d8,#2
    VST1.32  {d8[0]},[r3],r5
    VEXT.8   d8,d8,d8,#2
    VST1.32  {d8[0]},[r3],r5
    VST1.32  {d7[0]},[r3]
L0x348:
    MOV      r0,#0
    VPOP     {d8-d12}
    POP      {r4-r12,pc}
    .endfunc

    .end

