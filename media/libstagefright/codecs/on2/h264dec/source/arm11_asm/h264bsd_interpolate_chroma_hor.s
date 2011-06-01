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
;-- Abstract : ARMv6 optimized version of h264bsdInterpolateChromaHor function
;--
;-------------------------------------------------------------------------------


    IF  :DEF: H264DEC_WINASM
        ;// We dont use REQUIRE8 and PRESERVE8 for winasm
    ELSE
        REQUIRE8
        PRESERVE8
    ENDIF

    AREA    |.text|, CODE


;// h264bsdInterpolateChromaHor register allocation

ref     RN 0
ptrA    RN 0

mb      RN 1
block   RN 1

x0      RN 2
count   RN 2

y0      RN 3
valX    RN 3

width   RN 4

height  RN 5
tmp7    RN 5

chrPW   RN 6
tmp8    RN 6

tmp1    RN 7
chrPH   RN 7

tmp2    RN 8

tmp3    RN 9

tmp4    RN 10

tmp5    RN 11

tmp6    RN 12

c32     RN 14
xFrac   RN 14

;// Function exports and imports

    IMPORT  h264bsdFillBlock

    EXPORT  h264bsdInterpolateChromaHor

;//  Function arguments
;//
;//  u8 *ref,                   : 0xc4
;//  u8 *predPartChroma,        : 0xc8
;//  i32 x0,                    : 0xcc
;//  i32 y0,                    : 0xd0
;//  u32 width,                 : 0xf8
;//  u32 height,                : 0xfc
;//  u32 xFrac,                 : 0x100
;//  u32 chromaPartWidth,       : 0x104
;//  u32 chromaPartHeight       : 0x108

h264bsdInterpolateChromaHor
    STMFD   sp!, {r0-r11,lr}
    SUB     sp, sp, #0xc4

    LDR     chrPW, [sp, #0x104]     ;// chromaPartWidth
    LDR     width, [sp, #0xf8]      ;// width
    CMP     x0, #0
    BLT     do_fill

    ADD     tmp6, x0, chrPW         ;// tmp6 = x0+ chromaPartWidth
    ADD     tmp6, tmp6, #1          ;// tmp6 = x0 + chromaPartWidth + 1
    CMP     tmp6, width             ;// x0+chromaPartWidth+1 > width
    BHI     do_fill

    CMP     y0, #0
    BLT     do_fill
    LDR     chrPH, [sp, #0x108]     ;// chromaPartHeight
    LDR     height, [sp, #0xfc]     ;// height
    ADD     tmp6, y0, chrPH         ;// tmp6 = y0 + chromaPartHeight
    CMP     tmp6, height
    BLS     skip_fill

do_fill
    LDR     chrPH, [sp, #0x108]     ;// chromaPartHeight
    LDR     height, [sp, #0xfc]     ;// height
    ADD     tmp8, chrPW, #1         ;// tmp8 = chromaPartWidth+1
    MOV     tmp2, tmp8              ;// tmp2 = chromaPartWidth+1
    STMIA   sp,{width,height,tmp8,chrPH,tmp2}
    ADD     block, sp, #0x1c        ;// block
    BL      h264bsdFillBlock

    LDR     x0, [sp, #0xcc]
    LDR     y0, [sp, #0xd0]
    LDR     ref, [sp, #0xc4]        ;// ref
    STMIA   sp,{width,height,tmp8,chrPH,tmp2}
    ADD     block, sp, #0x1c        ;// block
    MLA     ref, height, width, ref ;// ref += width * height; 
    MLA     block, chrPH, tmp8, block;// block + (chromaPH)*(chromaPW+1)
    BL      h264bsdFillBlock

    MOV     x0, #0                  ;// x0 = 0
    MOV     y0, #0                  ;// y0 = 0
    STR     x0, [sp, #0xcc]
    STR     y0, [sp, #0xd0]
    ADD     ref, sp, #0x1c          ;// ref = block
    STR     ref, [sp, #0xc4]        ;// ref

    STR     chrPH, [sp, #0xfc]      ;// height
    STR     tmp8, [sp, #0xf8]       ;// width
    MOV     width, tmp8
    SUB     chrPW, chrPW, #1

skip_fill
    MLA     tmp3, y0, width, x0     ;// tmp3 = y0*width+x0
    LDR     xFrac, [sp, #0x100]     ;// xFrac
    ADD     ptrA, ref, tmp3         ;// ptrA = ref + y0*width+x0
    RSB     valX, xFrac, #8         ;// valX = 8-xFrac

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
    PKHBT   valX, valX, xFrac, LSL #16  ;// |xFrac|valX |
    MOV     valX, valX, LSL #3          ;// multiply by 8 in advance
    MOV     c32, #32


    ;///////////////////////////////////////////////////////////////////////////
    ;// Cb
    ;///////////////////////////////////////////////////////////////////////////

    ;// 2x2 pels per iteration
    ;// bilinear vertical interpolation

loop1_y
    ADD     count, count, tmp2, LSL #8
    LDRB    tmp1, [ptrA, width]
    LDRB    tmp2, [ptrA], #1

loop1_x
    LDRB    tmp3, [ptrA, width]
    LDRB    tmp4, [ptrA], #1

    PKHBT   tmp5, tmp1, tmp3, LSL #16
    PKHBT   tmp6, tmp2, tmp4, LSL #16

    LDRB    tmp1, [ptrA, width]
    LDRB    tmp2, [ptrA], #1

    SMLAD   tmp5, tmp5, valX, c32       ;// multiply
    SMLAD   tmp6, tmp6, valX, c32       ;// multiply

    PKHBT   tmp7, tmp3, tmp1, LSL #16
    PKHBT   tmp8, tmp4, tmp2, LSL #16

    SMLAD   tmp7, tmp7, valX, c32       ;// multiply
    SMLAD   tmp8, tmp8, valX, c32       ;// multiply

    MOV     tmp5, tmp5, LSR #6          ;// scale down
    STRB    tmp5, [mb,#8]               ;// store row 2 col 1

    MOV     tmp6, tmp6, LSR #6          ;// scale down
    STRB    tmp6, [mb],#1               ;// store row 1 col 1

    MOV     tmp7, tmp7, LSR #6          ;// scale down
    STRB    tmp7, [mb,#8]               ;// store row 2 col 2

    MOV     tmp8, tmp8, LSR #6          ;// scale down
    STRB    tmp8, [mb],#1               ;// store row 1 col 2

    SUBS    count, count, #2<<28
    BCS     loop1_x

    AND     tmp2, count, #0x00F00000

    ADDS    mb, mb, #16
    SBC     mb, mb, tmp2, LSR #20
    ADD     ptrA, ptrA, width, LSL #1
    SBC     ptrA, ptrA, tmp2, LSR #20
    SUB     ptrA, ptrA, #1

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
    ;// bilinear vertical interpolation
loop2_y
    ADD     count, count, tmp2, LSL #8
    LDRB    tmp1, [ptrA, width]
    LDRB    tmp2, [ptrA], #1

loop2_x
    LDRB    tmp3, [ptrA, width]
    LDRB    tmp4, [ptrA], #1

    PKHBT   tmp5, tmp1, tmp3, LSL #16
    PKHBT   tmp6, tmp2, tmp4, LSL #16

    LDRB    tmp1, [ptrA, width]
    LDRB    tmp2, [ptrA], #1

    SMLAD   tmp5, tmp5, valX, c32       ;// multiply
    SMLAD   tmp6, tmp6, valX, c32       ;// multiply

    PKHBT   tmp7, tmp3, tmp1, LSL #16
    PKHBT   tmp8, tmp4, tmp2, LSL #16

    SMLAD   tmp7, tmp7, valX, c32       ;// multiply
    SMLAD   tmp8, tmp8, valX, c32       ;// multiply

    MOV     tmp5, tmp5, LSR #6          ;// scale down
    STRB    tmp5, [mb,#8]               ;// store row 2 col 1

    MOV     tmp6, tmp6, LSR #6          ;// scale down
    STRB    tmp6, [mb],#1               ;// store row 1 col 1

    MOV     tmp7, tmp7, LSR #6          ;// scale down
    STRB    tmp7, [mb,#8]               ;// store row 2 col 2

    MOV     tmp8, tmp8, LSR #6          ;// scale down
    STRB    tmp8, [mb],#1               ;// store row 1 col 2

    SUBS    count, count, #2<<28
    BCS     loop2_x

    AND     tmp2, count, #0x00F00000

    ADDS    mb, mb, #16
    SBC     mb, mb, tmp2, LSR #20
    ADD     ptrA, ptrA, width, LSL #1
    SBC     ptrA, ptrA, tmp2, LSR #20
    SUB     ptrA, ptrA, #1

    ADDS    count, count, #0xE << 24
    BGE     loop2_y

    ADD     sp,sp,#0xd4
    LDMFD   sp!, {r4-r11,pc}

    END
