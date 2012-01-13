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

@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
@	File:		Radix4FFT_v7.s
@
@	Content:	Radix4FFT armv7 assemble
@
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@

	.section .text
	.global	Radix4FFT

Radix4FFT:
	stmdb    sp!, {r4 - r11, lr}

	mov			r1, r1, asr #2
	cmp     	r1, #0
	beq     	Radix4FFT_END

Radix4FFT_LOOP1:
	mov     	r5, r2, lsl #1
	mov     	r8, r0
	mov     	r7, r1
	mov     	r5, r5, lsl #2
	cmp     	r1, #0
	rsbeq   	r12, r5, r5, lsl #2
	beq     	Radix4FFT_LOOP1_END

	rsb     	r12, r5, r5, lsl #2

Radix4FFT_LOOP2:
	mov     	r6, r3
	mov     	r4, r2
	cmp     	r2, #0
	beq     	Radix4FFT_LOOP2_END

Radix4FFT_LOOP3:
	@r0 = xptr[0]@
	@r1 = xptr[1]@
	VLD2.I32			{D0, D1, D2, D3}, [r8]
	VLD2.I32			{D28, D29, D30, D31}, [r6]!		@ cosx = csptr[0]@ sinx = csptr[1]@

	add					r8, r8, r5										@ xptr += step@
	VLD2.I32			{D4, D5, D6,D7}, [r8]					@ r2 = xptr[0]@ r3 = xptr[1]@

	VQDMULH.S32		Q10, Q2, Q14									@ MULHIGH(cosx, t0)
	VQDMULH.S32		Q11, Q3, Q15									@ MULHIGH(sinx, t1)
	VQDMULH.S32		Q12, Q3, Q14									@ MULHIGH(cosx, t1)
	VQDMULH.S32		Q13, Q2, Q15									@ MULHIGH(sinx, t0)

	VADD.S32			Q2, Q10, Q11									@ MULHIGH(cosx, t0) + MULHIGH(sinx, t1)
	VSUB.S32			Q3, Q12, Q13									@ MULHIGH(cosx, t1) - MULHIGH(sinx, t0)

	add					r8, r8, r5										@ xptr += step@
	VSHR.S32			Q10, Q0, #2										@ t0 = r0 >> 2@
	VSHR.S32			Q11, Q1, #2										@ t1 = r1 >> 2@

	VSUB.S32			Q0,	Q10, Q2										@ r0 = t0 - r2@
	VSUB.S32			Q1,	Q11, Q3										@ r1 = t1 - r3@
	VADD.S32			Q2, Q10, Q2										@ r2 = t0 + r2@
	VADD.S32			Q3, Q11, Q3										@ r3 = t1 + r3@

	VLD2.I32			{D8, D9, D10, D11}, [r8]
	VLD2.I32			{D28, D29, D30, D31}, [r6]!
	add						r8, r8, r5

	VQDMULH.S32		Q10, Q4, Q14									@ MULHIGH(cosx, t0)
	VQDMULH.S32		Q11, Q5, Q15									@ MULHIGH(sinx, t1)
	VQDMULH.S32		Q12, Q5, Q14									@ MULHIGH(cosx, t1)
	VQDMULH.S32		Q13, Q4, Q15									@ MULHIGH(sinx, t0)

	VADD.S32			Q8, Q10, Q11									@ MULHIGH(cosx, t0) + MULHIGH(sinx, t1)
	VSUB.S32			Q9, Q12, Q13									@ MULHIGH(cosx, t1) - MULHIGH(sinx, t0)

	VLD2.I32		{D12, D13, D14, D15}, [r8]
	VLD2.I32		{D28, D29, D30, D31}, [r6]!

	VQDMULH.S32		Q10, Q6, Q14									@ MULHIGH(cosx, t0)
	VQDMULH.S32		Q11, Q7, Q15									@ MULHIGH(sinx, t1)
	VQDMULH.S32		Q12, Q7, Q14									@ MULHIGH(cosx, t1)
	VQDMULH.S32		Q13, Q6, Q15									@ MULHIGH(sinx, t0)

	VADD.S32			Q6, Q10, Q11									@ MULHIGH(cosx, t0) + MULHIGH(sinx, t1)
	VSUB.S32			Q7, Q12, Q13									@ MULHIGH(cosx, t1) - MULHIGH(sinx, t0)

	VADD.S32			Q4, Q8, Q6										@ r4 = t0 + r6@
	VSUB.S32			Q5, Q7, Q9										@ r5 = r7 - t1@
	VSUB.S32			Q6, Q8, Q6										@ r6 = t0 - r6@
	VADD.S32			Q7, Q7, Q9										@ r7 = r7 + t1@

	VADD.S32			Q8, Q0, Q5										@ xptr[0] = r0 + r5@
	VADD.S32			Q9, Q1, Q6										@ xptr[1] = r1 + r6@
	VST2.I32			{D16, D17, D18, D19}, [r8]

	VSUB.S32			Q10, Q2, Q4										@ xptr[0] = r2 - r4@
	sub					r8, r8, r5										@ xptr -= step@
	VSUB.S32			Q11, Q3, Q7										@ xptr[1] = r3 - r7@
	VST2.I32			{D20, D21, D22, D23}, [r8]

	VSUB.S32			Q8, Q0, Q5										@ xptr[0] = r0 - r5@
	sub					r8, r8, r5										@ xptr -= step@
	VSUB.S32			Q9, Q1, Q6										@ xptr[1] = r1 - r6@
	VST2.I32			{D16, D17, D18, D19}, [r8]

	VADD.S32			Q10, Q2, Q4										@ xptr[0] = r2 + r4@
	sub					r8, r8, r5										@ xptr -= step@
	VADD.S32			Q11, Q3, Q7										@ xptr[1] = r3 + r7@
	VST2.I32			{D20, D21, D22, D23}, [r8]!

	subs    			r4, r4, #4
	bne     			Radix4FFT_LOOP3

Radix4FFT_LOOP2_END:
	add     			r8, r8, r12
	sub    				r7, r7, #1
	cmp					r7, #0
	bhi     			Radix4FFT_LOOP2

Radix4FFT_LOOP1_END:
	add     			r3, r12, r3
	mov     			r2, r2, lsl #2
	movs    			r1, r1, asr #2
	bne     			Radix4FFT_LOOP1

Radix4FFT_END:
	ldmia   			sp!, {r4 - r11, pc}

	@ENDP  @ |Radix4FFT|
	.end