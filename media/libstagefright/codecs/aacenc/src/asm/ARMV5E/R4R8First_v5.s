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
@	File:		R4R8First_v5.s
@
@	Content:	Radix8First and Radix4First function armv5 assemble
@
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@

	.section .text
	.global	Radix4First

Radix4First:
	stmdb       sp!, {r4 - r11, lr}

	movs				r10, r1
	mov					r11, r0
	beq					Radix4First_END

Radix4First_LOOP:
	ldrd				r0, [r11]
	ldrd				r2, [r11, #8]
	ldrd				r4, [r11, #16]
	ldrd				r6, [r11, #24]

	add					r8, r0, r2
	add					r9, r1, r3

	sub					r0, r0, r2
	sub					r1, r1, r3

	add					r2, r4, r6
	add					r3, r5, r7

	sub					r4, r4, r6
	sub					r5, r5, r7

	add					r6, r8, r2
	add					r7, r9, r3

	sub					r8, r8, r2
	sub					r9, r9, r3

	add					r2, r0, r5
	sub					r3, r1, r4

	sub					r0, r0, r5
	add					r1, r1, r4

	strd				r6, [r11]
	strd				r2, [r11, #8]
	strd				r8, [r11, #16]
	strd				r0, [r11, #24]

	subs				r10, r10, #1
	add					r11, r11, #32
	bne					Radix4First_LOOP

Radix4First_END:
	ldmia       sp!, {r4 - r11, pc}
	@ENDP  @ |Radix4First|

	.section .text
	.global	Radix8First

Radix8First:
	stmdb       sp!, {r4 - r11, lr}
	sub         sp, sp, #0x24

	mov				  r12, r1
	mov					r14, r0
	cmp					r12, #0
	beq					Radix8First_END

Radix8First_LOOP:
	ldrd				r0, [r14]
	ldrd				r2, [r14, #8]
	ldrd				r4, [r14, #16]
	ldrd				r6, [r14, #24]

	add					r8, r0, r2					@ r0 = buf[0] + buf[2]@
	add					r9, r1, r3					@ i0 = buf[1] + buf[3]@

	sub					r0, r0, r2					@ r1 = buf[0] - buf[2]@
	sub					r1, r1, r3					@ i1 = buf[1] - buf[3]@

	add					r2, r4, r6					@	r2 = buf[4] + buf[6]@
	add					r3, r5, r7					@ i2 = buf[5] + buf[7]@

	sub					r4, r4, r6					@	r3 = buf[4] - buf[6]@
	sub					r5, r5, r7					@ i3 = buf[5] - buf[7]@

	add					r6, r8, r2					@ r4 = (r0 + r2) >> 1@
	add					r7, r9, r3					@ i4 = (i0 + i2) >> 1@

	sub					r8, r8, r2					@	r5 = (r0 - r2) >> 1@
	sub					r9, r9, r3					@ i5 = (i0 - i2) >> 1@

	sub					r2, r0, r5					@ r6 = (r1 - i3) >> 1@
	add					r3, r1, r4					@ i6 = (i1 + r3) >> 1@

	add					r0, r0, r5					@ r7 = (r1 + i3) >> 1@
	sub					r1, r1, r4					@ i7 = (i1 - r3) >> 1@

	mov					r6, r6, asr #1			@
	mov					r7, r7, asr #1			@

	mov					r8, r8, asr #1
	mov					r9, r9, asr #1

	mov					r2, r2, asr #1
	mov					r3, r3, asr #1

	mov					r0, r0, asr #1
	mov					r1, r1, asr #1

	str					r6, [sp]
	str					r7, [sp, #4]

	str					r8, [sp, #8]
	str					r9, [sp, #12]

	str					r2, [sp, #16]
	str					r3, [sp, #20]

	str					r0, [sp, #24]
	str					r1, [sp, #28]

	ldrd				r2, [r14, #32]
	ldrd				r4, [r14, #40]
	ldrd				r6, [r14, #48]
	ldrd				r8, [r14, #56]

	add					r0, r2, r4					@ r0 = buf[ 8] + buf[10]@
	add					r1, r3, r5					@ i0 = buf[ 9] + buf[11]@

	sub					r2, r2, r4					@ r1 = buf[ 8] - buf[10]@
	sub					r3, r3, r5					@ i1 = buf[ 9] - buf[11]@

	add					r4, r6, r8					@ r2 = buf[12] + buf[14]@
	add					r5, r7, r9					@ i2 = buf[13] + buf[15]@

	sub					r6, r6, r8					@ r3 = buf[12] - buf[14]@
	sub					r7, r7, r9					@	i3 = buf[13] - buf[15]@

	add					r8, r0, r4					@ t0 = (r0 + r2)
	add					r9, r1, r5					@ t1 = (i0 + i2)

	sub					r0, r0, r4					@ t2 = (r0 - r2)
	sub					r1, r1, r5					@ t3 = (i0 - i2)

	mov					r8, r8, asr #1
	ldr					r4, [sp]

	mov					r9, r9, asr #1
	ldr					r5, [sp, #4]

	mov					r0, r0, asr #1
	mov					r1, r1, asr #1

	add					r10, r4, r8					@ buf[ 0] = r4 + t0@
	add					r11, r5, r9					@ buf[ 1] = i4 + t1@

	sub					r4,  r4, r8					@ buf[ 8] = r4 - t0@
	sub					r5,  r5, r9					@	buf[ 9] = i4 - t1@

 	strd				r10, [r14]
 	strd				r4,  [r14, #32]

 	ldr					r10, [sp, #8]
 	ldr					r11, [sp, #12]

 	add					r4, r10, r1					@ buf[ 4] = r5 + t3@
 	sub					r5, r11, r0					@	buf[ 5] = i5 - t2@

 	sub					r10, r10, r1				@ buf[12] = r5 - t3@
 	add					r11, r11, r0				@ buf[13] = i5 + t2@

 	strd				r4,  [r14, #16]
 	strd				r10, [r14, #48]

 	sub					r0, r2, r7					@ r0 = r1 - i3@
 	add					r1, r3, r6					@ i0 = i1 + r3@

  ldr					r11, DATATab

 	add					r2, r2, r7					@ r2 = r1 + i3@
 	sub					r3, r3, r6					@ i2 = i1 - r3@

	sub					r4, r0, r1					@ r0 - i0
	add					r5, r0, r1					@ r0 + i0

	sub					r0, r2, r3					@ r2 - i2
	add					r1, r2, r3					@ r2 + i2

	smull				r8, r6, r4, r11
	smull				r9, r7, r5, r11

	ldr					r2, [sp, #16]
	ldr					r3, [sp, #20]

	smull				r8, r4, r0, r11
	smull				r9, r5, r1, r11

	ldr					r10, [sp, #24]
	ldr					r11, [sp, #28]

	sub					r8, r2, r6
	sub					r9, r3, r7

	add					r2, r2, r6
	add					r3, r3, r7

	add					r6, r10, r5
	sub					r7, r11, r4

	sub					r0, r10, r5
	add					r1, r11, r4

	strd				r6, [r14, #8]
	strd				r8, [r14, #24]
	strd				r0, [r14, #40]
	strd				r2, [r14, #56]

	subs				r12, r12, #1
	add					r14, r14, #64

	bne					Radix8First_LOOP

Radix8First_END:
	add         sp, sp, #0x24
	ldmia       sp!, {r4 - r11, pc}

DATATab:
	.word       0x5a82799a

	@ENDP  @ |Radix8First|
	.end
