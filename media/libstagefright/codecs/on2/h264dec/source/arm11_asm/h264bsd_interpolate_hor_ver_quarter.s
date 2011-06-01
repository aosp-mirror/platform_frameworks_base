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
;-- Abstract : ARMv6 optimized version of h264bsdInterpolateHorVerQuarter 
;--            function
;--
;-------------------------------------------------------------------------------


    IF :DEF: H264DEC_WINASM
        ;// We dont use REQUIRE8 and PRESERVE8 for winasm
    ELSE
        REQUIRE8
        PRESERVE8
    ENDIF

    AREA    |.text|, CODE

;// h264bsdInterpolateHorVerQuarter register allocation

ref     RN 0

mb      RN 1
buff    RN 1

count   RN 2
x0      RN 2

y0      RN 3
x_2_0   RN 3
res     RN 3

x_3_1   RN 4
tmp1    RN 4

height  RN 5
x_6_4   RN 5
tmp2    RN 5

partW   RN 6
x_7_5   RN 6
tmp3    RN 6

partH   RN 7
tmp4    RN 7

tmp5    RN 8

tmp6    RN 9

tmpa    RN 10

mult_20_01  RN 11
tmpb        RN 11

mult_20_m5  RN 12
width       RN 12

plus16  RN 14


;// function exports and imports

    IMPORT  h264bsdFillBlock

    EXPORT  h264bsdInterpolateHorVerQuarter

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


h264bsdInterpolateHorVerQuarter
    STMFD   sp!, {r0-r11, lr}
    SUB     sp, sp, #0x1e4

    CMP     x0, #0
    BLT     do_fill                 ;// (x0 < 0)
    LDR     partW, [sp,#0x220]      ;// partWidth
    LDR     width, [sp,#0x218]      ;// width
    ADD     tmpa, x0, partW         ;// (x0+partWidth)
    ADD     tmpa, tmpa, #5          ;// (x0+partW+5)
    CMP     tmpa, width
    BHI     do_fill                 ;// (x0+partW)>width

    CMP     y0, #0
    BLT     do_fill                 ;// (y0 < 0)
    LDR     partH, [sp,#0x224]      ;// partHeight
    LDR     height, [sp,#0x21c]     ;// height
    ADD     tmp5, y0, partH         ;// (y0+partHeight)
    ADD     tmp5, tmp5, #5          ;// (y0+partH+5)
    CMP     tmp5, height
    BLS     skip_fill               ;// no overfill needed


do_fill
    LDR     partH, [sp,#0x224]      ;// partHeight
    LDR     partW, [sp,#0x220]      ;// partWidth
    LDR     height, [sp,#0x21c]     ;// height
    ADD     tmp5, partH, #5         ;// tmp5 = partH + 5
    ADD     tmpa, partW, #5         ;// tmpa = partW + 5
    STMIB   sp, {height, tmpa}      ;// sp+4 = height, sp+8 = partWidth+5
    LDR     width, [sp,#0x218]      ;// width
    STR     tmp5, [sp,#0xc]         ;// sp+c = partHeight+5
    STR     tmpa, [sp,#0x10]        ;// sp+10 = partWidth+5
    STR     width, [sp,#0]          ;// sp+0 = width
    ADD     buff, sp, #0x28         ;// buff = p1[21*21/4+1]
    BL      h264bsdFillBlock

    MOV     x0, #0
    STR     x0,[sp,#0x1ec]          ;// x0 = 0
    STR     x0,[sp,#0x1f0]          ;// y0 = 0
    ADD     ref,sp,#0x28            ;// ref = p1
    STR     tmpa, [sp,#0x218]       ;// width = partWidth+5


skip_fill
    LDR     x0 ,[sp,#0x1ec]         ;// x0
    LDR     y0 ,[sp,#0x1f0]         ;// y0
    LDR     width, [sp,#0x218]      ;// width
    LDR     tmp6, [sp,#0x228]       ;// horVerOffset
    LDR     mb, [sp, #0x1e8]        ;// mb
    MLA     tmp5, width, y0, x0     ;// y0*width+x0
    ADD     ref, ref, tmp5          ;// ref += y0*width+x0
    STR     ref, [sp, #0x1e4]       ;// store "ref" for vertical filtering
    AND     tmp6, tmp6, #2          ;// calculate ref for horizontal filter
    MOV     tmpa, #2
    ADD     tmp6, tmpa, tmp6, LSR #1
    MLA     ref, tmp6, width, ref
    ADD     ref, ref, #8            ;// ref = ref+8

    ;// pack values to count register
    ;// [31:28] loop_x (partWidth-1)
    ;// [27:24] loop_y (partHeight-1)
    ;// [23:20] partWidth-1
    ;// [19:16] partHeight-1
    ;// [15:00] width
    MOV     count, width
    SUB     partW, partW, #1;
    SUB     partH, partH, #1;
    ADD     tmp5, partH, partW, LSL #4
    ADD     count, count, tmp5, LSL #16


    LDR     mult_20_01, = 0x00140001    ;// constant multipliers
    LDR     mult_20_m5, = 0x0014FFFB    ;// constant multipliers
    MOV     plus16, #16                 ;// constant for add
    AND     tmp4, count, #0x000F0000    ;// partHeight-1
    AND     tmp6, count, #0x00F00000    ;// partWidth-1
    ADD     count, count, tmp4, LSL #8  ;// partH-1 to lower part of top byte

;// HORIZONTAL PART

loop_y_hor
    LDR     x_3_1, [ref, #-8]
    ADD     count, count, tmp6, LSL #8   ;// partW-1 to upper part of top byte
    LDR     x_7_5, [ref, #-4]
    UXTB16  x_2_0, x_3_1
    UXTB16  x_3_1, x_3_1, ROR #8
    UXTB16  x_6_4, x_7_5

loop_x_hor
    UXTB16  x_7_5, x_7_5, ROR #8

    SMLAD   tmp4, x_2_0, mult_20_01, plus16
    SMLATB  tmp6, x_2_0, mult_20_01, plus16
    SMLATB  tmp5, x_2_0, mult_20_m5, plus16
    SMLATB  tmpa, x_3_1, mult_20_01, plus16

    SMLAD   tmp4, x_3_1, mult_20_m5, tmp4
    SMLATB  tmp6, x_3_1, mult_20_m5, tmp6
    SMLAD   tmp5, x_3_1, mult_20_01, tmp5
    LDR     x_3_1, [ref], #4
    SMLAD   tmpa, x_6_4, mult_20_m5, tmpa

    SMLABB  tmp4, x_6_4, mult_20_m5, tmp4
    SMLADX  tmp6, x_6_4, mult_20_m5, tmp6
    SMLADX  tmp5, x_6_4, mult_20_01, tmp5
    SMLADX  tmpa, x_7_5, mult_20_m5, tmpa

    SMLABB  tmp4, x_7_5, mult_20_01, tmp4
    UXTB16  x_2_0, x_3_1
    SMLABB  tmp5, x_7_5, mult_20_m5, tmp5
    SMLADX  tmp6, x_7_5, mult_20_01, tmp6
    SMLABB  tmpa, x_2_0, mult_20_01, tmpa

    MOV     tmp5, tmp5, ASR #5
    MOV     tmp4, tmp4, ASR #5
    PKHBT   tmp5, tmp5, tmpa, LSL #(16-5)
    PKHBT   tmp4, tmp4, tmp6, LSL #(16-5)
    USAT16  tmp5, #8, tmp5
    USAT16  tmp4, #8, tmp4

    SUBS    count, count, #4<<28
    ORR     tmp4, tmp4, tmp5, LSL #8
    STR     tmp4, [mb], #4
    BCC     next_y_hor

    UXTB16  x_3_1, x_3_1, ROR #8

    SMLAD   tmp4, x_6_4, mult_20_01, plus16
    SMLATB  tmp6, x_6_4, mult_20_01, plus16
    SMLATB  tmp5, x_6_4, mult_20_m5, plus16
    SMLATB  tmpa, x_7_5, mult_20_01, plus16

    SMLAD   tmp4, x_7_5, mult_20_m5, tmp4
    SMLATB  tmp6, x_7_5, mult_20_m5, tmp6
    SMLAD   tmp5, x_7_5, mult_20_01, tmp5
    LDR     x_7_5, [ref], #4
    SMLAD   tmpa, x_2_0, mult_20_m5, tmpa

    SMLABB  tmp4, x_2_0, mult_20_m5, tmp4
    SMLADX  tmp6, x_2_0, mult_20_m5, tmp6
    SMLADX  tmp5, x_2_0, mult_20_01, tmp5
    SMLADX  tmpa, x_3_1, mult_20_m5, tmpa

    SMLABB  tmp4, x_3_1, mult_20_01, tmp4
    UXTB16  x_6_4, x_7_5
    SMLABB  tmp5, x_3_1, mult_20_m5, tmp5
    SMLADX  tmp6, x_3_1, mult_20_01, tmp6
    SMLABB  tmpa, x_6_4, mult_20_01, tmpa

    MOV     tmp5, tmp5, ASR #5
    MOV     tmp4, tmp4, ASR #5
    PKHBT   tmp5, tmp5, tmpa, LSL #(16-5)
    PKHBT   tmp4, tmp4, tmp6, LSL #(16-5)
    USAT16  tmp5, #8, tmp5
    USAT16  tmp4, #8, tmp4

    SUBS    count, count, #4<<28
    ORR     tmp4, tmp4, tmp5, LSL #8
    STR     tmp4, [mb], #4
    BCS     loop_x_hor

next_y_hor
    AND     tmp6, count, #0x00F00000        ;// partWidth-1
    SMLABB  ref, count, mult_20_01, ref     ;// +width
    ADDS    mb, mb, #16                     ;// +16, Carry=0
    SBC     mb, mb, tmp6, LSR #20           ;// -(partWidth-1)-1
    SBC     ref, ref, tmp6, LSR #20         ;// -(partWidth-1)-1
    ADDS    count, count, #(1<<28)-(1<<24)  ;// decrement counter (partW)
    BGE     loop_y_hor



;// VERTICAL PART
;//
;// Approach to vertical interpolation
;//
;// Interpolation is done by using 32-bit loads and stores
;// and by using 16 bit arithmetic. 4x4 block is processed
;// in each round.
;//
;// |a_11|a_11|a_11|a_11|...|a_1n|a_1n|a_1n|a_1n|
;// |b_11|b_11|b_11|b_11|...|b_1n|b_1n|b_1n|b_1n|
;// |c_11|c_11|c_11|c_11|...|c_1n|c_1n|c_1n|c_1n|
;// |d_11|d_11|d_11|d_11|...|d_1n|d_1n|d_1n|d_1n|
;//           ..
;//           ..
;// |a_m1|a_m1|a_m1|a_m1|...
;// |b_m1|b_m1|b_m1|b_m1|...
;// |c_m1|c_m1|c_m1|c_m1|...
;// |d_m1|d_m1|d_m1|d_m1|...

;// Approach to bilinear interpolation to quarter pel position.
;// 4 bytes are processed parallel
;//
;// algorithm (a+b+1)/2. Rouding upwards +1 can be achieved by 
;// negating second operand to get one's complement (instead of 2's)
;// and using subtraction, EOR is used to correct sign.
;//
;// MVN     b, b
;// UHSUB8  a, a, b
;// EOR     a, a, 0x80808080


    LDR     ref, [sp, #0x1e4]           ;// ref
    LDR     tmpa, [sp, #0x228]          ;// horVerOffset
    LDR     mb, [sp, #0x1e8]            ;// mb
    LDR     width, [sp, #0x218]         ;// width
    ADD     ref, ref, #2                ;// calculate correct position
    AND     tmpa, tmpa, #1
    ADD     ref, ref, tmpa
    LDR     plus16, = 0x00100010        ;// +16 to lower and upperf halfwords
    AND     count, count, #0x00FFFFFF   ;// partWidth-1

    AND     tmpa, count, #0x000F0000    ;// partHeight-1
    ADD     count, count, tmpa, LSL #8

loop_y
    ADD     count, count, tmp6, LSL #8  ;// partWidth-1

loop_x
    LDR     tmp1, [ref], width     ;// |a4|a3|a2|a1|
    LDR     tmp2, [ref], width     ;// |c4|c3|c2|c1|
    LDR     tmp3, [ref], width     ;// |g4|g3|g2|g1|
    LDR     tmp4, [ref], width     ;// |m4|m3|m2|m1|
    LDR     tmp5, [ref], width     ;// |r4|r3|r2|r1|
    LDR     tmp6, [ref], width     ;// |t4|t3|t2|t1|

    ;// first four pixels 
    UXTB16  tmpa, tmp3                  ;// |g3|g1|
    UXTAB16 tmpa, tmpa, tmp4            ;// |g3+m3|g1+m1|
    UXTB16  tmpb, tmp2                  ;// |c3|c1|
    ADD     tmpa, tmpa, tmpa, LSL #2    ;// 5(G+M)

    UXTAB16 tmpb, tmpb, tmp5            ;// |c3+r3|c1+r1|
    ADD     tmpa, plus16, tmpa, LSL #2  ;// 16+20(G+M)
    UXTAB16 tmpa, tmpa, tmp1            ;// 16+20(G+M)+A
    UXTAB16 tmpa, tmpa, tmp6            ;// 16+20(G+M)+A+T

    ADD     tmpb, tmpb, tmpb, LSL #2    ;// 5(C+R)
    SSUB16  tmpa, tmpa, tmpb            ;// 16+20(G+M)+(A+T)-5(C+R)

    USAT16  tmpb, #13, tmpa             ;// saturate
    LDR     res, = 0x00FF00FF
    UXTB16  tmpa, tmp3, ROR #8          ;// |g4|g2|
    UXTAB16 tmpa, tmpa, tmp4, ROR #8    ;// |g4+m4|g2+m2|
    AND     res, res, tmpb, LSR #5      ;// mask and divide by 32

    ADD     tmpa, tmpa, tmpa, LSL #2    ;// 5(G+M)
    UXTB16  tmpb, tmp2, ROR #8          ;// |c4|c2|
    ADD     tmpa, plus16, tmpa, LSL #2  ;// 16+20(G+M)
    UXTAB16 tmpb, tmpb, tmp5, ROR #8    ;// |c4+r4|c2+r2|
    UXTAB16 tmpa, tmpa, tmp1, ROR #8    ;// 16+20(G+M)+A
    UXTAB16 tmpa, tmpa, tmp6, ROR #8    ;// 16+20(G+M)+A+T

    ADD     tmpb, tmpb, tmpb, LSL #2    ;// 5(C+R)
    SSUB16  tmpa, tmpa, tmpb            ;// 16+20(G+M)+(A+T)-5(C+R)

    USAT16  tmpb, #13, tmpa             ;// saturate
    LDR     tmp1, [mb]
    LDR     tmpa, = 0xFF00FF00
    MVN     tmp1, tmp1
    AND     tmpa, tmpa, tmpb, LSL #3    ;// mask and divede by 32
    ORR     res, res, tmpa

    LDR     tmpa, = 0x80808080
    UHSUB8  res, res, tmp1              ;// bilinear interpolation
    LDR     tmp1, [ref], width          ;// load next row
    EOR     res, res, tmpa              ;// correct sign

    STR     res, [mb], #16              ;// next row (mb)


    ;// tmp2 = |a4|a3|a2|a1|
    ;// tmp3 = |c4|c3|c2|c1|
    ;// tmp4 = |g4|g3|g2|g1|
    ;// tmp5 = |m4|m3|m2|m1|
    ;// tmp6 = |r4|r3|r2|r1|
    ;// tmp1 = |t4|t3|t2|t1|

    ;// second four pixels
    UXTB16  tmpa, tmp4                  ;// |g3|g1|
    UXTAB16 tmpa, tmpa, tmp5            ;// |g3+m3|g1+m1|
    UXTB16  tmpb, tmp3                  ;// |c3|c1|
    ADD     tmpa, tmpa, tmpa, LSL #2    ;// 5(G+M)
    UXTAB16 tmpb, tmpb, tmp6            ;// |c3+r3|c1+r1|
    ADD     tmpa, plus16, tmpa, LSL #2  ;// 16+20(G+M)
    UXTAB16 tmpa, tmpa, tmp2            ;// 16+20(G+M)+A
    UXTAB16 tmpa, tmpa, tmp1            ;// 16+20(G+M)+A+T

    ADD     tmpb, tmpb, tmpb, LSL #2    ;// 5(C+R)
    SSUB16  tmpa, tmpa, tmpb            ;// 16+20(G+M)+(A+T)-5(C+R)

    USAT16  tmpb, #13, tmpa             ;// saturate
    LDR     res, = 0x00FF00FF
    UXTB16  tmpa, tmp4, ROR #8          ;// |g4|g2|
    UXTAB16 tmpa, tmpa, tmp5, ROR #8    ;// |g4+m4|g2+m2|
    AND     res, res, tmpb, LSR #5      ;// mask and divide by 32

    ADD     tmpa, tmpa, tmpa, LSL #2    ;// 5(G+M)
    UXTB16  tmpb, tmp3, ROR #8          ;// |c4|c2|
    ADD     tmpa, plus16, tmpa, LSL #2  ;// 16+20(G+M)
    UXTAB16 tmpb, tmpb, tmp6, ROR #8    ;// |c4+r4|c2+r2|
    UXTAB16 tmpa, tmpa, tmp2, ROR #8    ;// 16+20(G+M)+A
    UXTAB16 tmpa, tmpa, tmp1, ROR #8    ;// 16+20(G+M)+A+T

    ADD     tmpb, tmpb, tmpb, LSL #2    ;// 5(C+R)
    SSUB16  tmpa, tmpa, tmpb            ;// 16+20(G+M)+(A+T)-5(C+R)

    USAT16  tmpb, #13, tmpa             ;// saturate
    LDR     tmp2, [mb]
    LDR     tmpa, = 0xFF00FF00
    MVN     tmp2, tmp2

    AND     tmpa, tmpa, tmpb, LSL #3    ;// mask and divide by 32
    ORR     res, res, tmpa
    LDR     tmpa, = 0x80808080
    UHSUB8  res, res, tmp2              ;// bilinear interpolation
    LDR     tmp2, [ref], width          ;// load next row
    EOR     res, res, tmpa              ;// correct sign
    STR     res, [mb], #16              ;// next row

    ;// tmp3 = |a4|a3|a2|a1|
    ;// tmp4 = |c4|c3|c2|c1|
    ;// tmp5 = |g4|g3|g2|g1|
    ;// tmp6 = |m4|m3|m2|m1|
    ;// tmp1 = |r4|r3|r2|r1|
    ;// tmp2 = |t4|t3|t2|t1|

    ;// third four pixels
    UXTB16  tmpa, tmp5                  ;// |g3|g1|
    UXTAB16 tmpa, tmpa, tmp6            ;// |g3+m3|g1+m1|
    UXTB16  tmpb, tmp4                  ;// |c3|c1|
    ADD     tmpa, tmpa, tmpa, LSL #2    ;// 5(G+M)
    UXTAB16 tmpb, tmpb, tmp1            ;// |c3+r3|c1+r1|
    ADD     tmpa, plus16, tmpa, LSL #2  ;// 16+20(G+M)
    UXTAB16 tmpa, tmpa, tmp3            ;// 16+20(G+M)+A
    UXTAB16 tmpa, tmpa, tmp2            ;// 16+20(G+M)+A+T

    ADD     tmpb, tmpb, tmpb, LSL #2    ;// 5(C+R)
    SSUB16  tmpa, tmpa, tmpb            ;// 16+20(G+M)+(A+T)-5(C+R)

    USAT16  tmpb, #13, tmpa             ;// saturate
    LDR     res, = 0x00FF00FF
    UXTB16  tmpa, tmp5, ROR #8          ;// |g4|g2|
    UXTAB16 tmpa, tmpa, tmp6, ROR #8    ;// |g4+m4|g2+m2|
    AND     res, res, tmpb, LSR #5      ;// mask and divide by 32

    ADD     tmpa, tmpa, tmpa, LSL #2    ;// 5(G+M)
    UXTB16  tmpb, tmp4, ROR #8          ;// |c4|c2|
    ADD     tmpa, plus16, tmpa, LSL #2  ;// 16+20(G+M)
    UXTAB16 tmpb, tmpb, tmp1, ROR #8    ;// |c4+r4|c2+r2|
    UXTAB16 tmpa, tmpa, tmp3, ROR #8    ;// 16+20(G+M)+A
    UXTAB16 tmpa, tmpa, tmp2, ROR #8    ;// 16+20(G+M)+A+T


    ADD     tmpb, tmpb, tmpb, LSL #2    ;// 5(C+R)
    SSUB16  tmpa, tmpa, tmpb            ;// 16+20(G+M)+(A+T)-5(C+R)

    USAT16  tmpb, #13, tmpa             ;// saturate
    LDR     tmp3, [mb]
    LDR     tmpa, = 0xFF00FF00
    MVN     tmp3, tmp3

    AND     tmpa, tmpa, tmpb, LSL #3    ;// mask and divide by 32
    ORR     res, res, tmpa
    LDR     tmpa, = 0x80808080
    UHSUB8  res, res, tmp3              ;// bilinear interpolation
    LDR     tmp3, [ref]                 ;// load next row
    EOR     res, res, tmpa              ;// correct sign
    STR     res, [mb], #16              ;// next row

    ;// tmp4 = |a4|a3|a2|a1|
    ;// tmp5 = |c4|c3|c2|c1|
    ;// tmp6 = |g4|g3|g2|g1|
    ;// tmp1 = |m4|m3|m2|m1|
    ;// tmp2 = |r4|r3|r2|r1|
    ;// tmp3 = |t4|t3|t2|t1|

    ;// fourth four pixels
    UXTB16  tmpa, tmp6                  ;// |g3|g1|
    UXTAB16 tmpa, tmpa, tmp1            ;// |g3+m3|g1+m1|
    UXTB16  tmpb, tmp5                  ;// |c3|c1|
    ADD     tmpa, tmpa, tmpa, LSL #2    ;// 5(G+M)
    UXTAB16 tmpb, tmpb, tmp2            ;// |c3+r3|c1+r1|
    ADD     tmpa, plus16, tmpa, LSL #2  ;// 16+20(G+M)
    UXTAB16 tmpa, tmpa, tmp4            ;// 16+20(G+M)+A
    UXTAB16 tmpa, tmpa, tmp3            ;// 16+20(G+M)+A+T

    ADD     tmpb, tmpb, tmpb, LSL #2    ;// 5(C+R)
    SSUB16  tmpa, tmpa, tmpb            ;// 16+20(G+M)+(A+T)-5(C+R)

    USAT16  tmpb, #13, tmpa             ;// saturate
    LDR     res, = 0x00FF00FF
    UXTB16  tmpa, tmp6, ROR #8          ;// |g4|g2|
    UXTAB16 tmpa, tmpa, tmp1, ROR #8    ;// |g4+m4|g2+m2|
    AND     res, res, tmpb, LSR #5      ;// mask and divide by 32

    ADD     tmpa, tmpa, tmpa, LSL #2    ;// 5(G+M)
    UXTB16  tmpb, tmp5, ROR #8          ;// |c4|c2|
    ADD     tmpa, plus16, tmpa, LSL #2  ;// 16+20(G+M)
    UXTAB16 tmpb, tmpb, tmp2, ROR #8    ;// |c4+r4|c2+r2|
    UXTAB16 tmpa, tmpa, tmp4, ROR #8    ;// 16+20(G+M)+A
    UXTAB16 tmpa, tmpa, tmp3, ROR #8    ;// 16+20(G+M)+A+T

    ADD     tmpb, tmpb, tmpb, LSL #2    ;// 5(C+R)
    SSUB16  tmpa, tmpa, tmpb            ;// 16+20(G+M)+(A+T)-5(C+R)

    USAT16  tmpb, #13, tmpa             ;// saturate
    LDR     tmp5, [mb]
    LDR     tmp4, = 0xFF00FF00
    MVN     tmp5, tmp5

    AND     tmpa, tmp4, tmpb, LSL #3    ;// mask and divide by 32
    ORR     res, res, tmpa
    LDR     tmpa, = 0x80808080
    UHSUB8  res, res, tmp5              ;// bilinear interpolation

    ;// decrement loop_x counter
    SUBS    count, count, #4<<28        ;// decrement x loop counter

    ;// calculate "ref" address for next round
    SUB     ref, ref, width, LSL #3     ;// ref -= 8*width;
    ADD     ref, ref, #4                ;// next column (4 pixels)

    EOR     res, res, tmpa              ;// correct sign
    STR     res, [mb], #-44

    BCS     loop_x

    ADDS    mb, mb, #64                 ;// set Carry=0
    ADD     ref, ref, width, LSL #2     ;// ref += 4*width
    AND     tmp6, count, #0x00F00000    ;// partWidth-1
    SBC     ref, ref, tmp6, LSR #20     ;// -(partWidth-1)-1
    SBC     mb, mb, tmp6, LSR #20       ;// -(partWidth-1)-1

    ADDS    count, count, #0xC << 24    ;// decrement y loop counter
    BGE     loop_y

    ADD     sp, sp, #0x1f4
    LDMFD   sp!, {r4-r11, pc}

    END
