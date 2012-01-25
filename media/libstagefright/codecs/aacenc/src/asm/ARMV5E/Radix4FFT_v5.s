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
@	File:		Radix4FFT_v5.s
@
@	Content:	Radix4FFT armv5 assemble
@
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
	.section .text
	.global	Radix4FFT

Radix4FFT:
	stmdb     sp!, {r4 - r11, lr}
	sub       sp, sp, #32

	mov			r1, r1, asr #2
	cmp     r1, #0
	beq     Radix4FFT_END

Radix4FFT_LOOP1:
	mov     r14, r0          							@ xptr = buf@
	mov		r10, r1 												@ i = num@
	mov     r9, r2, lsl #3  							@ step = 2*bgn@
	cmp     r10, #0
	str		r0, [sp]
	str		r1, [sp, #4]
	str		r2, [sp, #8]
	str		r3, [sp, #12]
	beq     Radix4FFT_LOOP1_END

Radix4FFT_LOOP2:
	mov     r12, r3				        				@ csptr = twidTab@
	mov		r11, r2												@ j = bgn
	cmp     r11, #0
	str		r10, [sp, #16]
	beq     Radix4FFT_LOOP2_END

Radix4FFT_LOOP3:
	str			r11, [sp, #20]

	ldrd		r0, [r14, #0]									@ r0 = xptr[0]@ r1 = xptr[1]@
	add			r14, r14, r9 	 								@ xptr += step@

	ldrd		r10,	[r14, #0]  					 			@ r2 = xptr[0]@ r3 = xptr[1]@
	ldr			r8, [r12], #4									@ cosxsinx = csptr[0]@

	smulwt	r4, r10, r8										@ L_mpy_wx(cosx, t0)
	smulwt	r3, r11, r8										@ L_mpy_wx(cosx, t1)

	smlawb	r2, r11, r8, r4								@ r2 = L_mpy_wx(cosx, t0) + L_mpy_wx(sinx, t1)@
	smulwb	r5, r10, r8										@ L_mpy_wx(sinx, t0)

	mov			r10, r0, asr #2								@ t0 = r0 >> 2@
	mov			r11, r1, asr #2								@	t1 = r1 >> 2@

	sub			r3, r3, r5										@ r3 = L_mpy_wx(cosx, t1) - L_mpy_wx(sinx, t0)@
	add     r14, r14, r9 	 								@ xptr += step@

	sub			r0, r10, r2										@ r0 = t0 - r2@
	sub			r1, r11, r3									  @ r1 = t1 - r3@

	add			r2, r10, r2										@ r2 = t0 + r2@
	add			r3, r11, r3										@ r3 = t1 + r3@

	str			r2, [sp, #24]
	str			r3, [sp, #28]

	ldrd		r10, [r14, #0]								@ r4 = xptr[0]@ r5 = xptr[1]@
	ldr			r8, [r12], #4									@ cosxsinx = csptr[1]@

	smulwt	r6, r10, r8										@ L_mpy_wx(cosx, t0)
	smulwt	r5, r11, r8										@ L_mpy_wx(cosx, t1)

	smlawb	r4, r11, r8, r6								@ r4 = L_mpy_wx(cosx, t0) + L_mpy_wx(sinx, t1)@
	smulwb	r7, r10, r8										@ L_mpy_wx(sinx, t0)

	add			r14, r14, r9									@ xptr += step@
	sub			r5, r5, r7										@ r5 = L_mpy_wx(cosx, t1) - L_mpy_wx(sinx, t0)@

	ldrd		r10, [r14]										@ r6 = xptr[0]@ r7 = xptr[1]@
	ldr			r8, [r12], #4									@ cosxsinx = csptr[1]@

	smulwt	r2, r10, r8										@ L_mpy_wx(cosx, t0)
	smulwt	r7, r11, r8										@ L_mpy_wx(cosx, t1)

	smlawb	r6, r11, r8, r2								@ r4 = L_mpy_wx(cosx, t0) + L_mpy_wx(sinx, t1)@
	smulwb	r3, r10, r8										@ L_mpy_wx(sinx, t0)

	mov			r10, r4												@ t0 = r4@
	mov			r11, r5												@ t1 = r5@

	sub			r7, r7, r3										@ r5 = L_mpy_wx(cosx, t1) - L_mpy_wx(sinx, t0)@


	add			r4,  r10, r6									@	r4 = t0 + r6@
	sub			r5, r7, r11										@ r5 = r7 - t1@

	sub			r6, r10, r6										@ r6 = t0 - r6@
	add			r7, r7, r11										@ r7 = r7 + t1@

	ldr			r2, [sp, #24]
	ldr			r3, [sp, #28]

	add			r10, r0, r5										@ xptr[0] = r0 + r5@
	add			r11, r1, r6										@ xptr[0] = r1 + r6

	strd		r10, [r14]
	sub			r14, r14, r9									@ xptr -= step@

	sub			r10, r2, r4										@	xptr[0] = r2 - r4@
	sub			r11, r3, r7										@ xptr[1] = r3 - r7@

	strd		r10, [r14]
	sub			r14, r14, r9									@ xptr -= step@

	sub			r10, r0, r5										@ xptr[0] = r0 - r5@
	sub			r11, r1, r6										@ xptr[0] = r1 - r6

	strd		r10, [r14]
	sub			r14, r14, r9									@ xptr -= step@

	add			r10, r2, r4										@	xptr[0] = r2 - r4@
	add			r11, r3, r7										@ xptr[1] = r3 - r7@

	strd		r10, [r14]
	add			r14, r14, #8									@ xptr += 2@

	ldr			r11, [sp, #20]
	subs		r11, r11, #1
	bne			Radix4FFT_LOOP3

Radix4FFT_LOOP2_END:
	ldr			r10, [sp, #16]
	ldr			r3, [sp, #12]
	ldr			r2, [sp, #8]
	rsb			r8, r9, r9, lsl #2
	sub			r10, r10, #1
	add			r14, r14, r8
	cmp			r10, #0
	bhi     Radix4FFT_LOOP2

Radix4FFT_LOOP1_END:
	ldr     r0, [sp]
	ldr		r1, [sp, #4]
	add     r3, r3, r8, asr #1
	mov     r2, r2, lsl #2
	movs    r1, r1, asr #2
	bne     Radix4FFT_LOOP1

Radix4FFT_END:
	add     sp, sp, #32
	ldmia   sp!, {r4 - r11, pc}

	@ENDP  @ |Radix4FFT|
	.end
