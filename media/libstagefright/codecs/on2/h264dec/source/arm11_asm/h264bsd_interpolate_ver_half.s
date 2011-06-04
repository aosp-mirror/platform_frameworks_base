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
;-- Abstract : ARMv6 optimized version of h264bsdInterpolateVerHalf function
;--
;-------------------------------------------------------------------------------


    IF :DEF: H264DEC_WINASM
        ;// We dont use REQUIRE8 and PRESERVE8 for winasm
    ELSE
        REQUIRE8
        PRESERVE8
    ENDIF

    AREA    |.text|, CODE

;// h264bsdInterpolateVerHalf register allocation

ref     RN 0

mb      RN 1
buff    RN 1

count   RN 2
x0      RN 2

res     RN 3
y0      RN 3

tmp1    RN 4

tmp2    RN 5
height  RN 5

tmp3    RN 6
partW   RN 6

tmp4    RN 7
partH   RN 7

tmp5    RN 8
tmp6    RN 9

tmpa    RN 10
tmpb    RN 11
width   RN 12

plus16  RN 14


;// function exports and imports

    IMPORT  h264bsdFillBlock

    EXPORT  h264bsdInterpolateVerHalf

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

h264bsdInterpolateVerHalf
    STMFD   sp!, {r0-r11, lr}
    SUB     sp, sp, #0x1e4

    CMP     x0, #0
    BLT     do_fill                 ;// (x0 < 0)
    LDR     partW, [sp,#0x220]      ;// partWidth
    ADD     tmp5, x0, partW         ;// (x0+partWidth)
    LDR     width, [sp,#0x218]      ;// width
    CMP     tmp5, width
    BHI     do_fill                 ;// (x0+partW)>width

    CMP     y0, #0
    BLT     do_fill                 ;// (y0 < 0)
    LDR     partH, [sp,#0x224]      ;// partHeight
    ADD     tmp6, y0, partH         ;// (y0+partHeight)
    ADD     tmp6, tmp6, #5          ;// (y0+partH+5)
    LDR     height, [sp,#0x21c]     ;// height
    CMP     tmp6, height
    BLS     skip_fill               ;// no overfill needed


do_fill
    LDR     partH, [sp,#0x224]      ;// partHeight
    ADD     tmp5, partH, #5         ;// r2 = partH + 5;
    LDR     height, [sp,#0x21c]     ;// height
    LDR     partW, [sp,#0x220]      ;// partWidth
    STMIB   sp, {height, partW}     ;// sp+4 = height, sp+8 = partWidth
    STR     tmp5, [sp,#0xc]         ;// sp+c partHeight+5
    STR     partW, [sp,#0x10]       ;// sp+10 = partWidth
    LDR     width, [sp,#0x218]      ;// width
    STR     width, [sp,#0]          ;// sp+0 = width
    ADD     buff, sp, #0x28         ;// buff = p1[21*21/4+1]
    BL      h264bsdFillBlock

    MOV     x0, #0
    STR     x0,[sp,#0x1ec]          ;// x0 = 0
    STR     x0,[sp,#0x1f0]          ;// y0 = 0
    ADD     ref,sp,#0x28            ;// ref = p1
    STR     partW, [sp,#0x218]


skip_fill
    LDR     x0 ,[sp,#0x1ec]         ;// x0
    LDR     y0 ,[sp,#0x1f0]         ;// y0
    LDR     width, [sp,#0x218]      ;// width
    MLA     tmp6, width, y0, x0     ;// y0*width+x0
    ADD     ref, ref, tmp6          ;// ref += y0*width+x0
    LDR     mb, [sp, #0x1e8]        ;// mb

    ADD     count, partW, partH, LSL #16    ;// |partH|partW|
    LDR     tmp5, = 0x00010001
    SSUB16  count, count, tmp5;     ;// |partH-1|partW-1|
    LDR     plus16, = 0x00100010

    AND     tmp1, count, #0x000000FF ;// partWidth


loop_y
    ADD     count, count, tmp1, LSL #24  ;// partWidth-1 to top byte

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
    LDR     tmp1, [ref], width
    LDR     tmpa, = 0xFF00FF00

    AND     tmpa, tmpa, tmpb, LSL #3    ;// mask and divede by 32
    ORR     res, res, tmpa
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
    LDR     tmp2, [ref], width
    LDR     tmpa, = 0xFF00FF00

    AND     tmpa, tmpa, tmpb, LSL #3    ;// mask and divide by 32
    ORR     res, res, tmpa
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
    LDR     tmp3, [ref]
    LDR     tmpa, = 0xFF00FF00

    ;// decrement loop_x counter
    SUBS    count, count, #4<<24        ;// (partWidth-1) -= 4;

    AND     tmpa, tmpa, tmpb, LSL #3    ;// mask and divide by 32
    ORR     res, res, tmpa
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
    LDR     tmp4, = 0xFF00FF00

    ;// calculate "ref" address for next round
    SUB     ref, ref, width, LSL #3     ;// ref -= 8*width;
    ADD     ref, ref, #4;               ;// next column (4 pixels)
    AND     tmpa, tmp4, tmpb, LSL #3    ;// mask and divide by 32
    ORR     res, res, tmpa
    STR     res, [mb], #-44

    BCS     loop_x

    ADDS    count, count, #252<<16      ;// (partHeight-1) -= 4;
    ADD     ref, ref, width, LSL #2     ;// ref += 4*width
    AND     tmp1, count, #0x000000FF    ;// partWidth-1
    ADD     tmp2, tmp1, #1              ;// partWidth
    SUB     ref, ref, tmp2              ;// ref -= partWidth
    ADD     mb, mb, #64;
    SUB     mb, mb, tmp2;               ;// mb -= partWidth
    BGE     loop_y

    ADD     sp,sp,#0x1f4
    LDMFD   sp!, {r4-r11, pc}

    END
