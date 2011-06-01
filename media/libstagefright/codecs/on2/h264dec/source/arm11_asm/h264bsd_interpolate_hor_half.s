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
;-- Abstract : ARMv6 optimized version of h264bsdInterpolateHorHalf function
;--
;-------------------------------------------------------------------------------


    IF :DEF: H264DEC_WINASM
        ;// We dont use REQUIRE8 and PRESERVE8 for winasm
    ELSE
        REQUIRE8
        PRESERVE8
    ENDIF

    AREA    |.text|, CODE

;// h264bsdInterpolateHorHalf register allocation

ref     RN 0

mb      RN 1
buff    RN 1

count   RN 2
x0      RN 2

y0      RN 3
x_2_0   RN 3

width   RN 4
x_3_1   RN 4

height  RN 5
x_6_4   RN 5

partW   RN 6
x_7_5   RN 6

partH   RN 7
tmp1    RN 7

tmp2    RN 8

tmp3    RN 9

tmp4    RN 10

mult_20_01  RN 11
mult_20_m5  RN 12

plus16  RN 14


;// function exports and imports

    IMPORT  h264bsdFillBlock

    EXPORT  h264bsdInterpolateHorHalf

;// Horizontal filter approach
;//
;// Basic idea in horizontal filtering is to adjust coefficients
;// like below. Calculation is done with 16-bit maths.
;//
;// Reg     x_2_0     x_3_1     x_6_4     x_7_5     x_2_0
;//       [  2  0 ] [  3  1 ] [  6  4 ] [  7  5 ] [ 10  8 ] ...
;// y_0 =   20  1     20 -5        -5         1
;// y_1 =   -5        20  1      1 20        -5
;// y_2 =    1        -5        -5 20      1 20
;// y_3 =              1        20 -5     -5 20         1


h264bsdInterpolateHorHalf
    STMFD   sp!, {r0-r11, lr}
    SUB     sp, sp, #0x1e4

    CMP     x0, #0
    BLT     do_fill                 ;// (x0 < 0)
    LDR     partW, [sp,#0x220]      ;// partWidth
    ADD     tmp4, x0, partW         ;// (x0+partWidth)
    ADD     tmp4, tmp4, #5          ;// (y0+partW+5)
    LDR     width, [sp,#0x218]      ;// width
    CMP     tmp4, width
    BHI     do_fill                 ;// (x0+partW)>width

    CMP     y0, #0
    BLT     do_fill                 ;// (y0 < 0)
    LDR     partH, [sp,#0x224]      ;// partHeight
    ADD     tmp2, y0, partH         ;// (y0+partHeight)
    LDR     height, [sp,#0x21c]     ;// height
    CMP     tmp2, height
    BLS     skip_fill               ;// no overfill needed


do_fill
    LDR     partH, [sp,#0x224]      ;// partHeight
    LDR     height, [sp,#0x21c]     ;// height
    LDR     partW, [sp,#0x220]      ;// partWidth
    ADD     tmp4, partW, #5         ;// tmp4 = partW + 5;
    STMIB   sp, {height, tmp4}      ;// sp+4 = height, sp+8 = partWidth+5
    STR     partH, [sp,#0xc]        ;// sp+c = partHeight
    STR     tmp4, [sp,#0x10]        ;// sp+10 = partWidth+5
    LDR     width, [sp,#0x218]      ;// width
    STR     width, [sp,#0]          ;// sp+0 = width
    ADD     buff, sp, #0x28         ;// buff = p1[21*21/4+1]
    BL      h264bsdFillBlock

    MOV     x0, #0
    STR     x0,[sp,#0x1ec]          ;// x0 = 0
    STR     x0,[sp,#0x1f0]          ;// y0 = 0
    ADD     ref,sp,#0x28            ;// ref = p1
    STR     tmp4, [sp,#0x218]       ;// width = partWidth+5


skip_fill
    LDR     x0 ,[sp,#0x1ec]         ;// x0
    LDR     y0 ,[sp,#0x1f0]         ;// y0
    LDR     width, [sp,#0x218]      ;// width
    MLA     tmp2, width, y0, x0     ;// y0*width+x0
    ADD     ref, ref, tmp2          ;// ref += y0*width+x0
    ADD     ref, ref, #8            ;// ref = ref+8
    LDR     mb, [sp, #0x1e8]        ;// mb

    ;// pack values to count register
    ;// [31:28] loop_x (partWidth-1)
    ;// [27:24] loop_y (partHeight-1)
    ;// [23:20] partWidth-1
    ;// [19:16] partHeight-1
    ;// [15:00] width
    MOV     count, width
    SUB     partW, partW, #1;
    SUB     partH, partH, #1;
    ADD     tmp2, partH, partW, LSL #4
    ADD     count, count, tmp2, LSL #16


    LDR     mult_20_01, = 0x00140001
    LDR     mult_20_m5, = 0x0014FFFB
    MOV     plus16, #16
    AND     tmp1, count, #0x000F0000    ;// partHeight-1
    AND     tmp3, count, #0x00F00000    ;// partWidth-1
    ADD     count, count, tmp1, LSL #8
loop_y
    LDR     x_3_1, [ref, #-8]
    ADD     count, count, tmp3, LSL #8
    LDR     x_7_5, [ref, #-4]
    UXTB16  x_2_0, x_3_1
    UXTB16  x_3_1, x_3_1, ROR #8
    UXTB16  x_6_4, x_7_5

loop_x
    UXTB16  x_7_5, x_7_5, ROR #8

    SMLAD   tmp1, x_2_0, mult_20_01, plus16
    SMLATB  tmp3, x_2_0, mult_20_01, plus16
    SMLATB  tmp2, x_2_0, mult_20_m5, plus16
    SMLATB  tmp4, x_3_1, mult_20_01, plus16

    SMLAD   tmp1, x_3_1, mult_20_m5, tmp1
    SMLATB  tmp3, x_3_1, mult_20_m5, tmp3
    SMLAD   tmp2, x_3_1, mult_20_01, tmp2
    LDR     x_3_1, [ref], #4
    SMLAD   tmp4, x_6_4, mult_20_m5, tmp4

    SMLABB  tmp1, x_6_4, mult_20_m5, tmp1
    SMLADX  tmp3, x_6_4, mult_20_m5, tmp3
    SMLADX  tmp2, x_6_4, mult_20_01, tmp2
    SMLADX  tmp4, x_7_5, mult_20_m5, tmp4

    SMLABB  tmp1, x_7_5, mult_20_01, tmp1
    UXTB16  x_2_0, x_3_1
    SMLABB  tmp2, x_7_5, mult_20_m5, tmp2
    SMLADX  tmp3, x_7_5, mult_20_01, tmp3
    SMLABB  tmp4, x_2_0, mult_20_01, tmp4

    MOV     tmp2, tmp2, ASR #5
    MOV     tmp1, tmp1, ASR #5
    PKHBT   tmp2, tmp2, tmp4, LSL #(16-5)
    PKHBT   tmp1, tmp1, tmp3, LSL #(16-5)
    USAT16  tmp2, #8, tmp2
    USAT16  tmp1, #8, tmp1

    SUBS    count, count, #4<<28
    ORR     tmp1, tmp1, tmp2, LSL #8
    STR     tmp1, [mb], #4
    BCC     next_y

    UXTB16  x_3_1, x_3_1, ROR #8

    SMLAD   tmp1, x_6_4, mult_20_01, plus16
    SMLATB  tmp3, x_6_4, mult_20_01, plus16
    SMLATB  tmp2, x_6_4, mult_20_m5, plus16
    SMLATB  tmp4, x_7_5, mult_20_01, plus16

    SMLAD   tmp1, x_7_5, mult_20_m5, tmp1
    SMLATB  tmp3, x_7_5, mult_20_m5, tmp3
    SMLAD   tmp2, x_7_5, mult_20_01, tmp2
    LDR     x_7_5, [ref], #4
    SMLAD   tmp4, x_2_0, mult_20_m5, tmp4

    SMLABB  tmp1, x_2_0, mult_20_m5, tmp1
    SMLADX  tmp3, x_2_0, mult_20_m5, tmp3
    SMLADX  tmp2, x_2_0, mult_20_01, tmp2
    SMLADX  tmp4, x_3_1, mult_20_m5, tmp4

    SMLABB  tmp1, x_3_1, mult_20_01, tmp1
    UXTB16  x_6_4, x_7_5
    SMLABB  tmp2, x_3_1, mult_20_m5, tmp2
    SMLADX  tmp3, x_3_1, mult_20_01, tmp3
    SMLABB  tmp4, x_6_4, mult_20_01, tmp4

    MOV     tmp2, tmp2, ASR #5
    MOV     tmp1, tmp1, ASR #5
    PKHBT   tmp2, tmp2, tmp4, LSL #(16-5)
    PKHBT   tmp1, tmp1, tmp3, LSL #(16-5)
    USAT16  tmp2, #8, tmp2
    USAT16  tmp1, #8, tmp1

    SUBS    count, count, #4<<28
    ORR     tmp1, tmp1, tmp2, LSL #8
    STR     tmp1, [mb], #4
    BCS     loop_x

next_y
    AND     tmp3, count, #0x00F00000    ;// partWidth-1
    SMLABB  ref, count, mult_20_01, ref ;// +width
    ADDS    mb, mb, #16                 ;// +16, Carry=0
    SBC     mb, mb, tmp3, LSR #20       ;// -(partWidth-1)-1
    SBC     ref, ref, tmp3, LSR #20     ;// -(partWidth-1)-1
    ADDS    count, count, #(1<<28)-(1<<24)
    BGE     loop_y

    ADD     sp,sp,#0x1f4
    LDMFD   sp!, {r4-r11, pc}

    END

