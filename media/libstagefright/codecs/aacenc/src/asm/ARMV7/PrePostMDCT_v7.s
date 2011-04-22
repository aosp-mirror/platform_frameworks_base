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
@	File:		PrePostMDCT_v7.s
@
@	Content:	premdct and postmdct function armv7 assemble
@
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@

	.section .text
	.global	PreMDCT

PreMDCT:
	stmdb     sp!, {r4 - r11, lr}
	
	add         r9, r0, r1, lsl #2
	sub         r3, r9, #32

	movs        r1, r1, asr #2
	beq         PreMDCT_END	
	
PreMDCT_LOOP:
	VLD4.I32			{d0, d2, d4, d6}, [r2]!				@ cosa = *csptr++@ sina = *csptr++@
	VLD4.I32			{d1, d3, d5, d7}, [r2]!				@ cosb = *csptr++@ sinb = *csptr++@
	VLD2.I32			{d8, d9, d10, d11}, [r0]			@ tr1 = *(buf0 + 0)@ ti2 = *(buf0 + 1)@
	VLD2.I32			{d13, d15}, [r3]!					@ tr2 = *(buf1 - 1)@ ti1 = *(buf1 + 0)@
	VLD2.I32			{d12, d14}, [r3]!					@ tr2 = *(buf1 - 1)@ ti1 = *(buf1 + 0)@
		
	VREV64.32			Q8, Q7	
	VREV64.32			Q9, Q6

	
	VQDMULH.S32		Q10, Q0, Q4								@ MULHIGH(cosa, tr1)
	VQDMULH.S32		Q11, Q1, Q8								@ MULHIGH(sina, ti1)
	VQDMULH.S32		Q12, Q0, Q8								@ MULHIGH(cosa, ti1)
	VQDMULH.S32		Q13, Q1, Q4								@ MULHIGH(sina, tr1)
		
	VADD.S32			Q0, Q10, Q11						@ *buf0++ = MULHIGH(cosa, tr1) + MULHIGH(sina, ti1)@
	VSUB.S32			Q1, Q12, Q13						@ *buf0++ = MULHIGH(cosa, ti1) - MULHIGH(sina, tr1)@
	
	VST2.I32			{d0, d1, d2, d3}, [r0]!
	sub						r3, r3, #32
	
	VQDMULH.S32		Q10, Q2, Q9										@ MULHIGH(cosb, tr2)
	VQDMULH.S32		Q11, Q3, Q5										@ MULHIGH(sinb, ti2)
	VQDMULH.S32		Q12, Q2, Q5										@ MULHIGH(cosb, ti2)
	VQDMULH.S32		Q13, Q3, Q9										@ MULHIGH(sinb, tr2)
		
	VADD.S32			Q0, Q10, Q11									@ MULHIGH(cosa, tr2) + MULHIGH(sina, ti2)@
	VSUB.S32			Q1, Q12, Q13									@ MULHIGH(cosa, ti2) - MULHIGH(sina, tr2)@
	
	VREV64.32			Q3, Q1
	VREV64.32			Q2, Q0
		
	VST2.I32		{d5, d7}, [r3]!	
	VST2.I32		{d4, d6}, [r3]! 
	
	subs     		r1, r1, #4
	sub		  		r3, r3, #64	
	bne       	PreMDCT_LOOP
	
PreMDCT_END:
	ldmia     sp!, {r4 - r11, pc}
	@ENDP  @ |PreMDCT|

	.section .text
	.global	PostMDCT

PostMDCT:
	stmdb     sp!, {r4 - r11, lr}
	
	add         r9, r0, r1, lsl #2
	sub         r3, r9, #32

	movs        r1, r1, asr #2
	beq         PostMDCT_END
	
PostMDCT_LOOP:
	VLD4.I32			{d0, d2, d4, d6}, [r2]!				@ cosa = *csptr++@ sina = *csptr++@
	VLD4.I32			{d1, d3, d5, d7}, [r2]!				@ cosb = *csptr++@ sinb = *csptr++@
	VLD2.I32			{d8, d9, d10, d11}, [r0]			@ tr1 = *(zbuf1 + 0)@ ti1 = *(zbuf1 + 1)@
	VLD2.I32			{d13, d15}, [r3]!							@ tr2 = *(zbuf2 - 1)@ ti2 = *(zbuf2 + 0)@
	VLD2.I32			{d12, d14}, [r3]!							@ tr2 = *(zbuf2 - 1)@ ti2 = *(zbuf2 + 0)@	

	VREV64.32			Q8, Q6	
	VREV64.32			Q9, Q7			
	
	VQDMULH.S32		Q10, Q0, Q4										@ MULHIGH(cosa, tr1)
	VQDMULH.S32		Q11, Q1, Q5										@ MULHIGH(sina, ti1)
	VQDMULH.S32		Q12, Q0, Q5										@ MULHIGH(cosa, ti1)
	VQDMULH.S32		Q13, Q1, Q4										@ MULHIGH(sina, tr1)
		
	VADD.S32			Q0, Q10, Q11									@ *buf0++ = MULHIGH(cosa, tr1) + MULHIGH(sina, ti1)@
	VSUB.S32			Q5, Q13, Q12									@ *buf1-- = MULHIGH(sina, tr1) - MULHIGH(cosa, ti1)@
	
	VQDMULH.S32		Q10, Q2, Q8										@ MULHIGH(cosb, tr2)
	VQDMULH.S32		Q11, Q3, Q9										@ MULHIGH(sinb, ti2)
	VQDMULH.S32		Q12, Q2, Q9										@ MULHIGH(cosb, ti2)
	VQDMULH.S32		Q13, Q3, Q8										@ MULHIGH(sinb, tr2)
		
	VADD.S32			Q4, Q10, Q11									@ *buf1-- = MULHIGH(cosa, tr2) + MULHIGH(sina, ti2)@
	VSUB.S32			Q1, Q13, Q12									@ *buf0++ = MULHIGH(sina, tr2) - MULHIGH(cosa, ti2)@	
	
	VREV64.32			Q2, Q4
	VREV64.32			Q3, Q5	
	
	sub						r3, r3, #32	
	VST2.I32			{d0, d1, d2, d3}, [r0]!
		
	VST2.I32			{d5, d7}, [r3]!	
	VST2.I32			{d4, d6}, [r3]! 
	
	subs     			r1, r1, #4
	sub		  			r3, r3, #64		
	bne       	PostMDCT_LOOP

PostMDCT_END:
	ldmia     sp!, {r4 - r11, pc}

	@ENDP  		@ |PostMDCT|
	.end