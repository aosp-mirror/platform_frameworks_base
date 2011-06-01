; Copyright (C) 2009 The Android Open Source Project
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;      http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

;-------------------------------------------------------------------------------
;--
;-- Abstract : ARMv6 optimized version of h264bsdInterpolateChromaHorVer 
;--            function
;--
;-------------------------------------------------------------------------------


    IF  :DEF: H264DEC_WINASM
        ;// We dont use REQUIRE8 and PRESERVE8 for winasm
    ELSE
        REQUIRE8
        PRESERVE8
    ENDIF

    AREA    |.text|, CODE


;// h264bsdInterpolateChromaHorVer register allocation

ref     RN 0
ptrA    RN 0

mb      RN 1
block   RN 1

x0      RN 2
count   RN 2

y0      RN 3
valY    RN 3

width   RN 4

tmp4    RN 5
height  RN 5

tmp1    RN 6

tmp2    RN 7

tmp3    RN 8

valX    RN 9

tmp5    RN 10
chrPW   RN 10

tmp6    RN 11
chrPH   RN 11

xFrac   RN 12

c32     RN 14
yFrac   RN 14

;// function exports and imports

    IMPORT  h264bsdFillBlock

    EXPORT  h264bsdInterpolateChromaHorVer

;//  Function arguments
;//
;//  u8 *ref,                   : 0xc4
;//  u8 *predPartChroma,        : 0xc8
;//  i32 x0,                    : 0xcc
;//  i32 y0,                    : 0xd0
;//  u32 width,                 : 0xf8
;//  u32 height,                : 0xfc
;//  u32 xFrac,                 : 0x100
;//  u32 yFrac,                 : 0x104
;//  u32 chromaPartWidth,       : 0x108
;//  u32 chromaPartHeight       : 0x10c

h264bsdInterpolateChromaHorVer
    STMFD   sp!, {r0-r11,lr}
    SUB     sp, sp, #0xc4

    LDR     chrPW, [sp, #0x108]     ;// chromaPartWidth
    LDR     xFrac, [sp, #0x100]     ;// xFrac
    LDR     width, [sp, #0xf8]      ;// width
    CMP     x0, #0
    BLT     do_fill

    ADD     tmp1, x0, chrPW         ;// tmp1 = x0+ chromaPartWidth
    ADD     tmp1, tmp1, #1          ;// tmp1 = x0+ chromaPartWidth+1
    CMP     tmp1, width             ;// x0+chromaPartWidth+1 > width
    BHI     do_fill

    CMP     y0, #0
    BLT     do_fill
    LDR     chrPH, [sp, #0x10c]     ;// chromaPartHeight
    LDR     height, [sp, #0xfc]     ;// height
    ADD     tmp1, y0, chrPH         ;// tmp1 = y0 + chromaPartHeight
    ADD     tmp1, tmp1, #1          ;// tmp1 = y0 + chromaPartHeight + 1
    CMP     tmp1, height
    BLS     skip_fill

do_fill
    LDR     chrPH, [sp, #0x10c]     ;// chromaPartHeight
    LDR     height, [sp, #0xfc]     ;// height
    ADD     tmp3, chrPW, #1         ;// tmp3 = chromaPartWidth+1
    ADD     tmp1, chrPW, #1         ;// tmp1 = chromaPartWidth+1
    ADD     tmp2, chrPH, #1         ;// tmp2 = chromaPartHeight+1
    STMIA   sp,{width,height,tmp1,tmp2,tmp3}
    ADD     block, sp, #0x1c        ;// block
    BL      h264bsdFillBlock

    LDR     x0, [sp, #0xcc]
    LDR     y0, [sp, #0xd0]
    LDR     ref, [sp, #0xc4]        ;// ref
    STMIA   sp,{width,height,tmp1,tmp2,tmp3}
    ADD     block, sp, #0x1c        ;// block
    MLA     ref, height, width, ref ;// ref += width * height; 
    MLA     block, tmp2, tmp1, block;// block + (chromaPW+1)*(chromaPH+1)
    BL      h264bsdFillBlock

    MOV     x0, #0                  ;// x0 = 0
    MOV     y0, #0                  ;// y0 = 0
    STR     x0, [sp, #0xcc]
    STR     y0, [sp, #0xd0]
    ADD     ref, sp, #0x1c          ;// ref = block
    STR     ref, [sp, #0xc4]        ;// ref

    STR     tmp2, [sp, #0xfc]       ;// height
    STR     tmp1, [sp, #0xf8]       ;// width
    MOV     width, tmp1

skip_fill
    MLA     tmp3, y0, width, x0     ;// tmp3 = y0*width+x0
    LDR     yFrac, [sp, #0x104]     ;// yFrac
    LDR     xFrac, [sp, #0x100]
    ADD     ptrA, ref, tmp3         ;// ptrA = ref + y0*width+x0
    RSB     valX, xFrac, #8         ;// valX = 8-xFrac
    RSB     valY, yFrac, #8         ;// valY = 8-yFrac

    LDR     mb, [sp, #0xc8]         ;// predPartChroma


    ;// pack values to count register
    ;// [31:28] loop_x (chromaPartWidth-1)
    ;// [27:24] loop_y (chromaPartHeight-1)
    ;// [23:20] chromaPartWidth-1
    ;// [19:16] chromaPartHeight-1
    ;// [15:00] nothing

    SUB     tmp2, chrPH, #1             ;// chromaPartHeight-1
    SUB     tmp1, chrPW, #1             ;// chromaPartWidth-1
    ADD     count, count, tmp2, LSL #16 ;// chromaPartHeight-1
    ADD     count, count, tmp2, LSL #24 ;// loop_y
    ADD     count, count, tmp1, LSL #20 ;// chromaPartWidth-1
    AND     tmp2, count, #0x00F00000    ;// loop_x
    PKHBT   valY, valY, yFrac, LSL #16  ;// |yFrac|valY |
    MOV     c32, #32


    ;///////////////////////////////////////////////////////////////////////////
    ;// Cb
    ;///////////////////////////////////////////////////////////////////////////

    ;// 2x2 pels per iteration
    ;// bilinear vertical and horizontal interpolation

loop1_y
    LDRB    tmp1, [ptrA]
    LDRB    tmp3, [ptrA, width]
    LDRB    tmp5, [ptrA, width, LSL #1]

    PKHBT   tmp1, tmp1, tmp3, LSL #16   ;// |t3|t1|
    PKHBT   tmp3, tmp3, tmp5, LSL #16   ;// |t5|t3|

    SMUAD   tmp1, tmp1, valY            ;// t1=(t1*valY + t3*yFrac)
    SMUAD   tmp3, tmp3, valY            ;// t3=(t3*valY + t5*yFrac)

    ADD     count, count, tmp2, LSL #8
loop1_x
    ;// first
    LDRB    tmp2, [ptrA, #1]!
    LDRB    tmp4, [ptrA, width]
    LDRB    tmp6, [ptrA, width, LSL #1]

    PKHBT   tmp2, tmp2, tmp4, LSL #16   ;// |t4|t2|
    PKHBT   tmp4, tmp4, tmp6, LSL #16   ;// |t6|t4|

    SMUAD   tmp2, tmp2, valY            ;// t2=(t2*valY + t4*yFrac)
    MLA     tmp5, tmp1, valX, c32       ;// t5=t1*valX+32
    MLA     tmp5, tmp2, xFrac, tmp5     ;// t5=t2*xFrac+t5

    SMUAD   tmp4, tmp4, valY            ;// t4=(t4*valY + t6*yFrac)
    MLA     tmp6, tmp3, valX, c32       ;// t3=t3*valX+32
    MLA     tmp6, tmp4, xFrac, tmp6     ;// t6=t4*xFrac+t6

    MOV     tmp6, tmp6, LSR #6          ;// scale down
    STRB    tmp6, [mb, #8]              ;// store pixel
    MOV     tmp5, tmp5, LSR #6          ;// scale down
    STRB    tmp5, [mb], #1              ;// store pixel

    ;// second
    LDRB    tmp1, [ptrA, #1]!
    LDRB    tmp3, [ptrA, width]
    LDRB    tmp5, [ptrA, width, LSL #1]

    PKHBT   tmp1, tmp1, tmp3, LSL #16   ;// |t3|t1|
    PKHBT   tmp3, tmp3, tmp5, LSL #16   ;// |t5|t3|

    SMUAD   tmp1, tmp1, valY            ;// t1=(t1*valY + t3*yFrac)
    MLA     tmp5, tmp1, xFrac, c32      ;// t1=t1*xFrac+32
    MLA     tmp5, tmp2, valX, tmp5      ;// t5=t2*valX+t5

    SMUAD   tmp3, tmp3, valY            ;// t3=(t3*valY + t5*yFrac)
    MLA     tmp6, tmp3, xFrac, c32      ;// t3=t3*xFrac+32
    MLA     tmp6, tmp4, valX, tmp6      ;// t6=t4*valX+t6

    MOV     tmp6, tmp6, LSR #6          ;// scale down
    STRB    tmp6, [mb, #8]              ;// store pixel
    MOV     tmp5, tmp5, LSR #6          ;// scale down
    STRB    tmp5, [mb], #1              ;// store pixel

    SUBS    count, count, #2<<28
    BCS     loop1_x

    AND     tmp2, count, #0x00F00000

    ADDS    mb, mb, #16
    SBC     mb, mb, tmp2, LSR #20
    ADD     ptrA, ptrA, width, LSL #1
    SBC     ptrA, ptrA, tmp2, LSR #20

    ADDS    count, count, #0xE << 24
    BGE     loop1_y

    ;///////////////////////////////////////////////////////////////////////////
    ;// Cr
    ;///////////////////////////////////////////////////////////////////////////
    LDR     height, [sp,#0xfc]          ;// height
    LDR     ref, [sp, #0xc4]            ;// ref
    LDR     tmp1, [sp, #0xd0]           ;// y0
    LDR     tmp2, [sp, #0xcc]           ;// x0
    LDR     mb, [sp, #0xc8]             ;// predPartChroma

    ADD     tmp1, height, tmp1
    MLA     tmp3, tmp1, width, tmp2
    ADD     ptrA, ref, tmp3
    ADD     mb, mb, #64

    AND     count, count, #0x00FFFFFF
    AND     tmp1, count, #0x000F0000
    ADD     count, count, tmp1, LSL #8
    AND     tmp2, count, #0x00F00000

    ;// 2x2 pels per iteration
    ;// bilinear vertical and horizontal interpolation
loop2_y
    LDRB    tmp1, [ptrA]
    LDRB    tmp3, [ptrA, width]
    LDRB    tmp5, [ptrA, width, LSL #1]

    PKHBT   tmp1, tmp1, tmp3, LSL #16   ;// |t3|t1|
    PKHBT   tmp3, tmp3, tmp5, LSL #16   ;// |t5|t3|

    SMUAD   tmp1, tmp1, valY            ;// t1=(t1*valY + t3*yFrac)
    SMUAD   tmp3, tmp3, valY            ;// t3=(t3*valY + t5*yFrac)

    ADD     count, count, tmp2, LSL #8
loop2_x
    ;// first
    LDRB    tmp2, [ptrA, #1]!
    LDRB    tmp4, [ptrA, width]
    LDRB    tmp6, [ptrA, width, LSL #1]

    PKHBT   tmp2, tmp2, tmp4, LSL #16   ;// |t4|t2|
    PKHBT   tmp4, tmp4, tmp6, LSL #16   ;// |t6|t4|

    SMUAD   tmp2, tmp2, valY            ;// t2=(t2*valY + t4*yFrac)
    MLA     tmp5, tmp1, valX, c32       ;// t5=t1*valX+32
    MLA     tmp5, tmp2, xFrac, tmp5     ;// t5=t2*xFrac+t5

    SMUAD   tmp4, tmp4, valY            ;// t4=(t4*valY + t6*yFrac)
    MLA     tmp6, tmp3, valX, c32       ;// t3=t3*valX+32
    MLA     tmp6, tmp4, xFrac, tmp6     ;// t6=t4*xFrac+t6

    MOV     tmp6, tmp6, LSR #6          ;// scale down
    STRB    tmp6, [mb, #8]              ;// store pixel
    MOV     tmp5, tmp5, LSR #6          ;// scale down
    STRB    tmp5, [mb], #1              ;// store pixel

    ;// second 
    LDRB    tmp1, [ptrA, #1]!
    LDRB    tmp3, [ptrA, width]
    LDRB    tmp5, [ptrA, width, LSL #1]

    PKHBT   tmp1, tmp1, tmp3, LSL #16   ;// |t3|t1|
    PKHBT   tmp3, tmp3, tmp5, LSL #16   ;// |t5|t3|

    SMUAD   tmp1, tmp1, valY            ;// t1=(t1*valY + t3*yFrac)
    MLA     tmp5, tmp1, xFrac, c32      ;// t1=t1*xFrac+32
    MLA     tmp5, tmp2, valX, tmp5      ;// t5=t2*valX+t5

    SMUAD   tmp3, tmp3, valY            ;// t3=(t3*valY + t5*yFrac)
    MLA     tmp6, tmp3, xFrac, c32      ;// t3=t3*xFrac+32
    MLA     tmp6, tmp4, valX, tmp6      ;// t6=t4*valX+t6

    MOV     tmp6, tmp6, LSR #6          ;// scale down
    STRB    tmp6, [mb, #8]              ;// store pixel
    MOV     tmp5, tmp5, LSR #6          ;// scale down
    STRB    tmp5, [mb], #1              ;// store pixel

    SUBS    count, count, #2<<28
    BCS     loop2_x

    AND     tmp2, count, #0x00F00000

    ADDS    mb, mb, #16
    SBC     mb, mb, tmp2, LSR #20
    ADD     ptrA, ptrA, width, LSL #1
    SBC     ptrA, ptrA, tmp2, LSR #20

    ADDS    count, count, #0xE << 24
    BGE     loop2_y

    ADD     sp,sp,#0xd4
    LDMFD   sp!,{r4-r11,pc}

    END
