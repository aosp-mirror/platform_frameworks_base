@/*
@ ** Copyright 2003-2010, VisualOn, Inc.
@ **
@ ** Licensed under the Apache License, Version 2.0 (the "License");
@ ** you may not use this file except in compliance with the License.
@ ** You may obtain a copy of the License at
@ **
@ **     http://www.apache.org/licenses/LICENSE-2.0
@ **
@ ** Unless required by applicable law or agreed to in writing, software
@ ** distributed under the License is distributed on an "AS IS" BASIS,
@ ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@ ** See the License for the specific language governing permissions and
@ ** limitations under the License.
@ */
@
@static void Norm_Corr (Word16 exc[],                    /* (i)     : excitation buffer          */
@                       Word16 xn[],                     /* (i)     : target vector              */
@                       Word16 h[],                      /* (i) Q15 : impulse response of synth/wgt filters */
@                       Word16 L_subfr,                  /* (i)     : sub-frame length */
@                       Word16 t_min,                    /* (i)     : minimum value of pitch lag.   */
@                       Word16 t_max,                    /* (i)     : maximum value of pitch lag.   */
@                       Word16 corr_norm[])              /* (o) Q15 : normalized correlation    */
@

@ r0 --- exc[]
@ r1 --- xn[]
@ r2 --- h[]
@ r3 --- L_subfr
@ r4 --- t_min
@ r5 --- t_max
@ r6 --- corr_norm[]


	.section  .text
        .global    Norm_corr_asm
        .extern    Convolve_asm
        .extern    Isqrt_n
@******************************
@ constant
@******************************
.equ    EXC               , 0
.equ    XN                , 4
.equ    H                 , 8
.equ    L_SUBFR           , 12
.equ    voSTACK           , 172
.equ    T_MIN             , 212
.equ    T_MAX             , 216
.equ    CORR_NORM         , 220

Norm_corr_asm:

        STMFD          r13!, {r4 - r12, r14}
        SUB            r13, r13, #voSTACK

        ADD            r8, r13, #20                 @get the excf[L_SUBFR]
        LDR            r4, [r13, #T_MIN]            @get t_min
        RSB            r11, r4, #0                  @k = -t_min
        ADD            r5, r0, r11, LSL #1          @get the &exc[k]

        @transfer Convolve function
        STMFD          sp!, {r0 - r3}
        MOV            r0, r5
        MOV            r1, r2
        MOV            r2, r8                       @r2 --- excf[]
        BL             Convolve_asm
        LDMFD          sp!, {r0 - r3}

        @ r8 --- excf[]

	MOV            r14, r1                       @copy xn[] address
        MOV            r7, #1
	VLD1.S16       {Q0, Q1}, [r14]!
	VLD1.S16       {Q2, Q3}, [r14]!
	VLD1.S16       {Q4, Q5}, [r14]!
	VLD1.S16       {Q6, Q7}, [r14]!

        VMULL.S16      Q10, D0, D0
        VMLAL.S16      Q10, D1, D1
        VMLAL.S16      Q10, D2, D2
        VMLAL.S16      Q10, D3, D3
        VMLAL.S16      Q10, D4, D4
        VMLAL.S16      Q10, D5, D5
        VMLAL.S16      Q10, D6, D6
        VMLAL.S16      Q10, D7, D7
        VMLAL.S16      Q10, D8, D8
        VMLAL.S16      Q10, D9, D9
	VMLAL.S16      Q10, D10, D10
	VMLAL.S16      Q10, D11, D11
	VMLAL.S16      Q10, D12, D12
	VMLAL.S16      Q10, D13, D13
	VMLAL.S16      Q10, D14, D14
	VMLAL.S16      Q10, D15, D15

        VQADD.S32      D20, D20, D21
        VMOV.S32       r9,  D20[0]
        VMOV.S32       r10, D20[1]
        QADD           r6, r9, r10
	QADD           r6, r6, r6
        QADD           r9, r6, r7                   @L_tmp = (L_tmp << 1) + 1;
	CLZ            r7, r9
	SUB            r6, r7, #1                   @exp = norm_l(L_tmp)
        RSB            r7, r6, #32                  @exp = 32 - exp
	MOV            r6, r7, ASR #1
	RSB            r7, r6, #0                   @scale = -(exp >> 1)

        @loop for every possible period
	@for(t = t_min@ t <= t_max@ t++)
	@r7 --- scale r4 --- t_min r8 --- excf[]

LOOPFOR:
	ADD            r14, r13, #20                @copy of excf[]
	MOV            r12, r1                      @copy of xn[]
	MOV            r8, #0x8000

        VLD1.S16       {Q0, Q1}, [r14]!                 @ load 16 excf[]
        VLD1.S16       {Q2, Q3}, [r14]!                 @ load 16 excf[]
        VLD1.S16       {Q4, Q5}, [r12]!                 @ load 16 x[]
	VLD1.S16       {Q6, Q7}, [r12]!                 @ load 16 x[]
        VMULL.S16    Q10, D0, D0                      @L_tmp1 += excf[] * excf[]
        VMULL.S16    Q11, D0, D8                      @L_tmp  += x[] * excf[]
        VMLAL.S16    Q10, D1, D1
        VMLAL.S16    Q11, D1, D9
        VMLAL.S16    Q10, D2, D2
        VMLAL.S16    Q11, D2, D10
        VMLAL.S16    Q10, D3, D3
        VMLAL.S16    Q11, D3, D11
        VMLAL.S16    Q10, D4, D4
        VMLAL.S16    Q11, D4, D12
        VMLAL.S16    Q10, D5, D5
        VMLAL.S16    Q11, D5, D13
        VMLAL.S16    Q10, D6, D6
        VMLAL.S16    Q11, D6, D14
        VMLAL.S16    Q10, D7, D7
        VMLAL.S16    Q11, D7, D15

	VLD1.S16       {Q0, Q1}, [r14]!                 @ load 16 excf[]
        VLD1.S16       {Q2, Q3}, [r14]!                 @ load 16 excf[]
        VLD1.S16       {Q4, Q5}, [r12]!                 @ load 16 x[]
        VLD1.S16       {Q6, Q7}, [r12]!                 @ load 16 x[]
        VMLAL.S16    Q10, D0, D0
        VMLAL.S16    Q11, D0, D8
        VMLAL.S16    Q10, D1, D1
        VMLAL.S16    Q11, D1, D9
        VMLAL.S16    Q10, D2, D2
        VMLAL.S16    Q11, D2, D10
        VMLAL.S16    Q10, D3, D3
        VMLAL.S16    Q11, D3, D11
        VMLAL.S16    Q10, D4, D4
        VMLAL.S16    Q11, D4, D12
        VMLAL.S16    Q10, D5, D5
        VMLAL.S16    Q11, D5, D13
        VMLAL.S16    Q10, D6, D6
        VMLAL.S16    Q11, D6, D14
        VMLAL.S16    Q10, D7, D7
        VMLAL.S16    Q11, D7, D15

        VQADD.S32      D20, D20, D21
        VQADD.S32      D22, D22, D23

	VPADD.S32      D20, D20, D20                   @D20[0] --- L_tmp1 << 1
	VPADD.S32      D22, D22, D22                   @D22[0] --- L_tmp << 1

	VMOV.S32       r6, D20[0]
        VMOV.S32       r5, D22[0]

	@r5 --- L_tmp, r6 --- L_tmp1
	MOV            r10, #1
	ADD            r5, r10, r5, LSL #1                     @L_tmp = (L_tmp << 1) + 1
	ADD            r6, r10, r6, LSL #1                     @L_tmp1 = (L_tmp1 << 1) + 1

	CLZ            r10, r5
	CMP            r5, #0
	RSBLT          r11, r5, #0
	CLZLT          r10, r11
	SUB            r10, r10, #1                 @exp = norm_l(L_tmp)

	MOV            r5, r5, LSL r10              @L_tmp = (L_tmp << exp)
	RSB            r10, r10, #30                @exp_corr = 30 - exp
	MOV            r11, r5, ASR #16             @corr = extract_h(L_tmp)

	CLZ            r5, r6
	SUB            r5, r5, #1
	MOV            r6, r6, LSL r5               @L_tmp = (L_tmp1 << exp)
	RSB            r5, r5, #30                  @exp_norm = 30 - exp

	@r10 --- exp_corr, r11 --- corr
	@r6  --- L_tmp, r5 --- exp_norm

	@Isqrt_n(&L_tmp, &exp_norm)

	MOV            r14, r0
	MOV            r12, r1

        STMFD          sp!, {r0 - r4, r7 - r12, r14}
	ADD            r1, sp, #4
	ADD            r0, sp, #0
	STR            r6, [sp]
	STRH           r5, [sp, #4]
	BL             Isqrt_n
	LDR            r6, [sp]
	LDRSH          r5, [sp, #4]
        LDMFD          sp!, {r0 - r4, r7 - r12, r14}
	MOV            r0, r14
	MOV            r1, r12


	MOV            r6, r6, ASR #16              @norm = extract_h(L_tmp)
	MUL            r12, r6, r11
	ADD            r12, r12, r12                @L_tmp = vo_L_mult(corr, norm)

	ADD            r6, r10, r5
	ADD            r6, r6, r7                   @exp_corr + exp_norm + scale

        CMP            r6, #0
        RSBLT          r6, r6, #0
	MOVLT          r12, r12, ASR r6
        MOVGT          r12, r12, LSL r6             @L_tmp = L_shl(L_tmp, exp_corr + exp_norm + scale)

        ADD            r12, r12, r8
        MOV            r12, r12, ASR #16            @vo_round(L_tmp)

        LDR            r5, [r13, #CORR_NORM]        @ get corr_norm address
	LDR            r6, [r13, #T_MAX]            @ get t_max
	ADD            r10, r5, r4, LSL #1          @ get corr_norm[t] address
	STRH           r12, [r10]                   @ corr_norm[t] = vo_round(L_tmp)

	CMP            r4, r6
	BEQ            Norm_corr_asm_end

	ADD            r4, r4, #1                   @ t_min ++
	RSB            r5, r4, #0                   @ k

	MOV            r6, #63                      @ i = 63
	MOV            r8, r0                       @ exc[]
	MOV            r9, r2                       @ h[]
	ADD            r10, r13, #20                @ excf[]

	ADD            r8, r8, r5, LSL #1           @ exc[k] address
	ADD            r9, r9, r6, LSL #1           @ h[i] address
	ADD            r10, r10, r6, LSL #1         @ excf[i] address
	LDRSH          r11, [r8]                    @ tmp = exc[k]

LOOPK:
        LDRSH          r8, [r9], #-2                @ load h[i]
	LDRSH          r12, [r10, #-2]              @ load excf[i - 1]
	MUL            r14, r11, r8
	MOV            r8, r14, ASR #15
	ADD            r14, r8, r12
	STRH           r14, [r10], #-2
	SUBS           r6, r6, #1
	BGT            LOOPK

	LDRSH          r8, [r9]                     @ load h[0]
	MUL            r14, r11, r8
        LDR            r6, [r13, #T_MAX]            @ get t_max
	MOV            r8, r14, ASR #15
	STRH           r8, [r10]

	CMP            r4, r6
	BLE            LOOPFOR

Norm_corr_asm_end:

        ADD            r13, r13, #voSTACK
        LDMFD          r13!, {r4 - r12, r15}

        .END


