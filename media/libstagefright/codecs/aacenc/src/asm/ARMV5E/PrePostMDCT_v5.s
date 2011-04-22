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
@	File:		PrePostMDCT_v5.s
@
@	Content:	premdct and postmdct function armv5 assemble
@
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@

	.section .text
	.global	PreMDCT

PreMDCT:
	stmdb       sp!, {r4 - r11, lr}
	
	add         r9, r0, r1, lsl #2
	sub         r3, r9, #8

	movs        r1, r1, asr #2
	beq         PreMDCT_END
	
PreMDCT_LOOP:
	ldr					r8, [r2], #4
	ldr					r9, [r2], #4
	
	ldrd				r4, [r0]
	ldrd				r6, [r3]
	
	smull				r14, r11, r4, r8					@ MULHIGH(tr1, cosa)
	smull    			r10, r12, r7, r8					@ MULHIGH(ti1, cosa)
		
	smull				r14, r8, r7, r9						@ MULHIGH(ti1, sina)
	smull				r7, r10, r4, r9						@ MULHIGH(tr1, sina)	
		
	add					r11, r11, r8						@ MULHIGH(cosa, tr1) + MULHIGH(sina, ti1)@	
	sub					r7, r12, r10						@ MULHIGH(ti1, cosa) - MULHIGH(tr1, sina)
	
	ldr					r8, [r2], #4
	ldr					r9, [r2], #4
	
	smull				r14, r4, r6, r8						@ MULHIGH(tr2, cosa)
	smull    			r10, r12, r5, r8					@ MULHIGH(ti2, cosa)
		
	smull				r14, r8, r5, r9						@ MULHIGH(ti2, sina)
	smull				r5, r10, r6, r9						@ MULHIGH(tr2, sina)
	
	add					r8, r8, r4
	sub					r9, r12, r10
	
	mov					r6, r11		

	strd				r6, [r0]	
	strd				r8, [r3]
	
	subs				r1, r1, #1
	sub					r3, r3, #8
	add 				r0, r0, #8
	bne					PreMDCT_LOOP

PreMDCT_END:
	ldmia       sp!, {r4 - r11, pc}
	@ENDP  @ |PreMDCT|
	
	.section .text
	.global	PostMDCT

PostMDCT:
	stmdb       sp!, {r4 - r11, lr}
	
	add         r9, r0, r1, lsl #2
	sub         r3, r9, #8

	movs        r1, r1, asr #2
	beq         PostMDCT_END
	
PostMDCT_LOOP:
	ldr					r8, [r2], #4					
	ldr					r9, [r2], #4
	
	ldrd				r4, [r0]
	ldrd				r6, [r3]
	
	smull				r14, r11, r4, r8					@ MULHIGH(tr1, cosa)
	smull    			r10, r12, r5, r8					@ MULHIGH(ti1, cosa)
		
	smull				r14, r8, r5, r9						@ MULHIGH(ti1, sina)
	smull				r5, r10, r4, r9						@ MULHIGH(tr1, sina)	
		
	add					r4, r11, r8							@ MULHIGH(cosa, tr1) + MULHIGH(sina, ti1)@	
	sub					r11, r10, r12						@ MULHIGH(ti1, cosa) - MULHIGH(tr1, sina)@
	
	ldr					r8, [r2], #4						@
	ldr					r9, [r2], #4
	
	smull				r14, r5, r6, r8						@ MULHIGH(tr2, cosa)
	smull    			r10, r12, r7, r8					@ MULHIGH(ti2, cosa)
		
	smull				r14, r8, r7, r9						@ MULHIGH(ti2, sina)
	smull				r7, r10, r6, r9						@ MULHIGH(tr2, sina)
	
	add					r6, r8, r5							@ MULHIGH(cosb, tr2) + MULHIGH(sinb, ti2)@
	sub					r5, r10, r12						@ MULHIGH(sinb, tr2) - MULHIGH(cosb, ti2)@
	
	mov					r7, r11				

	strd				r4, [r0]
	strd				r6, [r3]
	
	subs				r1, r1, #1
	sub					r3, r3, #8
	add 				r0, r0, #8
	bne					PostMDCT_LOOP

PostMDCT_END:
	ldmia       sp!, {r4 - r11, pc}
	@ENDP  @ |PostMDCT|
	.end