/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .text

    .global armVCM4P10_DecodeCoeffsToPair
    .func   armVCM4P10_DecodeCoeffsToPair
armVCM4P10_DecodeCoeffsToPair:
    PUSH     {r4-r12,lr}
    SUB      sp,sp,#0x40
    LDR      r10,[r0,#0]
    LDR      r12,[r1,#0]
    LDR      r6, =armVCM4P10_CAVLCCoeffTokenTables
    LDR      r4,[sp,#0x68]
    LDRB     r9,[r10,#2]
    LDRB     r8,[r10,#1]
    LDRB     r11,[r10],#3
    ADD      r12,r12,#8
    LDR      r6,[r6,r4,LSL #2]
    ORR      r9,r9,r8,LSL #8
    ORR      r11,r9,r11,LSL #16
    LSLS     r8,r11,r12
    MOVS     r7,#0x1e
    AND      r7,r7,r8,LSR #27
    SUBS     r12,r12,#8
L0x44:
    BCC      L1
    LDRB     r8,[r10],#1
L1:
    LDRH     r7,[r6,r7]
    ADDCC    r12,r12,#8
    ADD      r12,r12,#4
    ORRCS    r11,r8,r11,LSL #8
    LSRS     r8,r7,#1
    BCS      L0x74
    LSLS     r8,r11,r12
    SUBS     r12,r12,#0xa
    ADD      r7,r7,r8,LSR #29
    BIC      r7,r7,#1
    B        L0x44
L0x74:
    SUB      r12,r12,r7,LSR #13
    BIC      r7,r8,#0xf000
    LSRS     r5,r7,#2
    STRB     r5,[r2,#0]
    BEQ      L0x344
    CMP      r7,#0x44
    BGE      L0x33c
    STR      r0,[sp,#0]
    STR      r1,[sp,#4]
    STR      r3,[sp,#8]
    ANDS     r1,r7,#3
    ADD      r2,sp,#0xc
    BEQ      L0xd8
    MOV      r0,r1
L0xac:
    LSLS     r7,r11,r12
    SUBS     r12,r12,#7
    BCC      L2
    LDRB     r8,[r10],#1
L2:
    ADDCC    r12,r12,#8
    LSR      r7,r7,#31
    ORRCS    r11,r8,r11,LSL #8
    SUBS     r0,r0,#1
    MOV      r8,#1
    SUB      r8,r8,r7,LSL #1
    STRH     r8,[r2],#2
    BGT      L0xac
L0xd8:
    SUBS     r0,r5,r1
    BEQ      L0x1b8
    MOV      r4,#1
    CMP      r5,#0xa
    MOVLE    r4,#0
    CMP      r1,#3
    MOVLT    r1,#4
    MOVGE    r1,#2
    MOVGE    r4,#0
L0xfc:
    LSLS     r7,r11,r12
    CLZ      r7,r7
    ADD      r12,r12,r7
    SUBS     r12,r12,#7
    BCC      L3
    LDRB     r8,[r10],#1
    ORR      r11,r8,r11,LSL #8
    SUBS     r12,r12,#8
    BCC      L3
    LDRB     r8,[r10],#1
L3:
    ADDCC    r12,r12,#8
    ORRCS    r11,r8,r11,LSL #8
    CMP      r7,#0x10
    BGE      L0x33c
    MOVS     lr,r4
    TEQEQ    r7,#0xe
    MOVEQ    lr,#4
    TEQ      r7,#0xf
    MOVEQ    lr,#0xc
    TEQEQ    r4,#0
    ADDEQ    r7,r7,#0xf
    TEQ      lr,#0
    BEQ      L0x184
    LSL      r3,r11,r12
    ADD      r12,r12,lr
    SUBS     r12,r12,#8
    RSB      r9,lr,#0x20
    BCC      L4
    LDRB     r8,[r10],#1
    ORR      r11,r8,r11,LSL #8
    SUBS     r12,r12,#8
    BCC      L4
    LDRB     r8,[r10],#1
L4:
    ADDCC    r12,r12,#8
    LSR      r3,r3,r9
    ORRCS    r11,r8,r11,LSL #8
    LSL      r7,r7,r4
    ADD      r7,r3,r7
L0x184:
    ADD      r7,r7,r1
    MOV      r1,#2
    LSRS     r8,r7,#1
    RSBCS    r8,r8,#0
    STRH     r8,[r2],#2
    LDR      r9, =armVCM4P10_SuffixToLevel
    LDRSB    r8,[r9,r4]
    TEQ      r4,#0
    MOVEQ    r4,#1
    CMP      r7,r8
    ADDCS    r4,r4,#1
    SUBS     r0,r0,#1
    BGT      L0xfc
L0x1b8:
    LDR      r8,[sp,#0x6c]
    SUB      r0,r5,#1
    SUBS     r1,r8,r5
    ADD      r4,sp,#0x2c
    MOV      lr,r5
    SUB      lr,lr,#1
    BEQ      L0x2b0
    TEQ      r8,#4
    LDREQ    r6, =(armVCM4P10_CAVLCTotalZeros2x2Tables - 4)
    LDRNE    r6, =(armVCM4P10_CAVLCTotalZeroTables - 4)
    LDR      r6,[r6,r5,LSL #2]
    LSLS     r8,r11,r12
    MOVS     r7,#0x1e
    AND      r7,r7,r8,LSR #27
    SUBS     r12,r12,#8
L0x1f4:
    BCC      L5
    LDRB     r8,[r10],#1
L5:
    LDRH     r7,[r6,r7]
    ADDCC    r12,r12,#8
    ADD      r12,r12,#4
    ORRCS    r11,r8,r11,LSL #8
    LSRS     r8,r7,#1
    BCS      L0x224
    LSLS     r8,r11,r12
    SUBS     r12,r12,#0xa
    ADD      r7,r7,r8,LSR #29
    BIC      r7,r7,#1
    B        L0x1f4
L0x224:
    SUB      r12,r12,r7,LSR #13
    BIC      r7,r8,#0xf000
    CMP      r7,#0x10
    BGE      L0x33c
    LDR      r3, =(armVCM4P10_CAVLCRunBeforeTables - 4)
    ADD      r4,sp,#0x2c
    MOVS     r1,r7
    ADD      lr,lr,r1
    BEQ      L0x2b0
L0x248:
    SUBS     r0,r0,#1
    LDR      r6,[r3,r1,LSL #2]
    BLT      L0x2bc
    LSLS     r8,r11,r12
    MOVS     r7,#0xe
    AND      r7,r7,r8,LSR #28
    SUBS     r12,r12,#8
L0x264:
    BCC      L6
    LDRB     r8,[r10],#1
L6:
    LDRH     r7,[r6,r7]
    ADDCC    r12,r12,#8
    ADD      r12,r12,#3
    ORRCS    r11,r8,r11,LSL #8
    LSRS     r8,r7,#1
    BCS      L0x294
    LSLS     r8,r11,r12
    SUBS     r12,r12,#9
    ADD      r7,r7,r8,LSR #29
    BIC      r7,r7,#1
    B        L0x264
L0x294:
    SUB      r12,r12,r7,LSR #13
    BIC      r7,r8,#0xf000
    CMP      r7,#0xf
    BGE      L0x33c
    SUBS     r1,r1,r7
    STRB     r7,[r4],#1
    BGT      L0x248
L0x2b0:
    SUBS     r0,r0,#1
    BLT      L7
    STRB     r1,[r4],#1
L7:
    BGT      L0x2b0
L0x2bc:
    STRB     r1,[r4],#1
    LDR      r8,[sp,#0x6c]
    TEQ      r8,#0xf
    ADDEQ    lr,lr,#1
    SUB      r4,r4,r5
    SUB      r2,r2,r5
    SUB      r2,r2,r5
    LDR      r3,[sp,#8]
    LDR      r0,[r3,#0]
    TEQ      r8,#4
    LDREQ    r6, =armVCM4P10_ZigZag_2x2
    LDRNE    r6, =armVCM4P10_ZigZag_4x4
L0x2ec:
    LDRB     r9,[r4],#1
    LDRB     r8,[r6,lr]
    SUB      lr,lr,#1
    SUB      lr,lr,r9
    LDRSH    r9,[r2],#2
    SUBS     r5,r5,#1
    ORREQ    r8,r8,#0x20
    ADD      r1,r9,#0x80
    CMP      r1,#0x100
    ORRCS    r8,r8,#0x10
    TEQ      r5,#0
    STRB     r8,[r0],#1
    STRB     r9,[r0],#1
    LSR      r9,r9,#8
    BCC      L8
    STRB     r9,[r0],#1
L8:
    BNE      L0x2ec
    STR      r0,[r3,#0]
    LDR      r0,[sp,#0]
    LDR      r1,[sp,#4]
    B        L0x344
L0x33c:
    MVN      r0,#1
    B        L0x35c
L0x344:
    ADD      r10,r10,r12,LSR #3
    AND      r12,r12,#7
    SUB      r10,r10,#4
    STR      r12,[r1,#0]
    STR      r10,[r0,#0]
    MOV      r0,#0
L0x35c:
    ADD      sp,sp,#0x40
    POP      {r4-r12,pc}
    .endfunc

    .end

