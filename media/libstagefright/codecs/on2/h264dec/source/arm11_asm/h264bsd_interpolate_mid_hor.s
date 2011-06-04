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
;-- Abstract : ARMv6 optimized version horizontal part of 
;--            h264bsdInterpolateMid functions
;--
;-------------------------------------------------------------------------------


    IF :DEF: H264DEC_WINASM
        ;// We dont use REQUIRE8 and PRESERVE8 for winasm
    ELSE
        REQUIRE8
        PRESERVE8
    ENDIF

    AREA    |.text|, CODE


;// Register allocation

ref     RN 0    ;// pointer to current position in reference image
mb      RN 1    ;// pointer to current position in interpolated mb
count   RN 2    ;// bit-packed width and count values

x_2_0   RN 4
x_3_1   RN 5
x_6_4   RN 6
x_7_5   RN 7

tmp1    RN 8
tmp2    RN 9
tmp3    RN 10
tmp4    RN 11

mult_20_01  RN 12   ;// [20,  1]
mult_20_m5  RN 14   ;// [20, -5]


        EXPORT  h264bsdInterpolateMidHorPart

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


h264bsdInterpolateMidHorPart
    STMFD   sp!, {r4-r11, lr}

    ;// pack values to count register
    ;// [31:28] loop_x (partWidth-1)
    ;// [27:24] loop_y (partHeight-1)
    ;// [23:20] partWidth-1
    ;// [19:16] partHeight-1
    ;// [15:00] width


    LDR     mult_20_01, = 0x00140001
    LDR     mult_20_m5, = 0x0014FFFB
    AND     tmp3, count, #0x000F0000    ;// partWidth-1
loop_y
    LDR     x_3_1, [ref, #-8]
    ADD     count, count, tmp3, LSL #12
    LDR     x_7_5, [ref, #-4]
    UXTB16  x_2_0, x_3_1
    UXTB16  x_3_1, x_3_1, ROR #8
    UXTB16  x_6_4, x_7_5

loop_x
    UXTB16  x_7_5, x_7_5, ROR #8

    SMUAD   tmp1, x_2_0, mult_20_01
    SMULTB  tmp2, x_2_0, mult_20_m5
    SMULTB  tmp3, x_2_0, mult_20_01
    SMULTB  tmp4, x_3_1, mult_20_01

    SMLAD   tmp1, x_3_1, mult_20_m5, tmp1
    SMLAD   tmp2, x_3_1, mult_20_01, tmp2
    SMLATB  tmp3, x_3_1, mult_20_m5, tmp3
    LDR     x_3_1, [ref], #4
    SMLAD   tmp4, x_6_4, mult_20_m5, tmp4

    SMLABB  tmp1, x_6_4, mult_20_m5, tmp1
    SMLADX  tmp2, x_6_4, mult_20_01, tmp2
    SMLADX  tmp3, x_6_4, mult_20_m5, tmp3
    SMLADX  tmp4, x_7_5, mult_20_m5, tmp4

    SMLABB  tmp1, x_7_5, mult_20_01, tmp1
    SMLABB  tmp2, x_7_5, mult_20_m5, tmp2
    UXTB16  x_2_0, x_3_1
    SMLADX  tmp3, x_7_5, mult_20_01, tmp3
    SMLABB  tmp4, x_2_0, mult_20_01, tmp4

    SUBS    count, count, #4<<28
    STR     tmp1, [mb], #4
    STR     tmp2, [mb], #4
    STR     tmp3, [mb], #4
    STR     tmp4, [mb], #4
    BCC     next_y

    UXTB16  x_3_1, x_3_1, ROR #8

    SMUAD   tmp1, x_6_4, mult_20_01
    SMULTB  tmp2, x_6_4, mult_20_m5
    SMULTB  tmp3, x_6_4, mult_20_01
    SMULTB  tmp4, x_7_5, mult_20_01

    SMLAD   tmp1, x_7_5, mult_20_m5, tmp1
    SMLAD   tmp2, x_7_5, mult_20_01, tmp2
    SMLATB  tmp3, x_7_5, mult_20_m5, tmp3
    LDR     x_7_5, [ref], #4
    SMLAD   tmp4, x_2_0, mult_20_m5, tmp4

    SMLABB  tmp1, x_2_0, mult_20_m5, tmp1
    SMLADX  tmp2, x_2_0, mult_20_01, tmp2
    SMLADX  tmp3, x_2_0, mult_20_m5, tmp3
    SMLADX  tmp4, x_3_1, mult_20_m5, tmp4

    SMLABB  tmp1, x_3_1, mult_20_01, tmp1
    SMLABB  tmp2, x_3_1, mult_20_m5, tmp2
    UXTB16  x_6_4, x_7_5
    SMLADX  tmp3, x_3_1, mult_20_01, tmp3
    SMLABB  tmp4, x_6_4, mult_20_01, tmp4

    SUBS    count, count, #4<<28
    STR     tmp1, [mb], #4
    STR     tmp2, [mb], #4
    STR     tmp3, [mb], #4
    STR     tmp4, [mb], #4
    BCS     loop_x

next_y
    AND     tmp3, count, #0x000F0000    ;// partWidth-1
    SMLABB  ref, count, mult_20_01, ref   ;// +width
    SBC     ref, ref, tmp3, LSR #16   ;// -(partWidth-1)-1
    ADDS    count, count, #(1<<28)-(1<<20)
    BGE     loop_y

    LDMFD   sp!, {r4-r11, pc}

    END

