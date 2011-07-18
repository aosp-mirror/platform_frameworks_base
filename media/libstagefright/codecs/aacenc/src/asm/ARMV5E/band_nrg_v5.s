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
@	File:		band_nrg_v5.s
@
@	Content:	CalcBandEnergy and CalcBandEnergyMS function armv5 assemble
@
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@

	.section .text

	.global	CalcBandEnergy

CalcBandEnergy:
	stmdb   sp!, {r4 - r11, lr}

  mov     r2, r2, lsl #16
	ldr     r12, [r13, #36]
	mov			r9, #0
  mov     r5, r2, asr #16
	mov			r4, #0
  cmp     r5, #0
	ble     L212

L22:
  mov     r2, r4, lsl #1
  ldrsh   r10, [r1, r2]
  add     r11, r1, r2
  ldrsh   r2, [r11, #2]
	mov     r14, #0
  cmp     r10, r2
  bge     L28

L23:
	ldr     r11, [r0, +r10, lsl #2]
  add     r10, r10, #1
	ldr     r6, [r0, +r10, lsl #2]
	smull   r11, r7, r11, r11
	add     r10, r10, #1
	smull	  r6, r8, r6, r6
	ldr     r11, [r0, +r10, lsl #2]
	qadd	  r14, r14, r7
	add     r10, r10, #1
	smull	  r11, r7, r11, r11
	ldr     r6, [r0, +r10, lsl #2]
	qadd	  r14, r14, r8
	smull	  r6, r8, r6, r6
  add     r10, r10, #1
	qadd	  r14, r14, r7
	cmp     r10, r2
	qadd	  r14, r14, r8
	blt     L23

L28:
	qadd	  r14, r14, r14
	str     r14, [r3, +r4, lsl #2]
	add     r4, r4, #1
	qadd	  r9, r9, r14
	cmp     r4, r5

  blt     L22

L212:
	str     r9, [r12, #0]
	ldmia   sp!, {r4 - r11, pc}

	@ENDP  ; |CalcBandEnergy|

	.global	CalcBandEnergyMS

CalcBandEnergyMS:
	stmdb   sp!, {r4 - r11, lr}
	sub     r13, r13, #24

	mov     r12, #0
  mov     r3, r3, lsl #16
  mov     r14, #0
	mov     r3, r3, asr #16
	cmp     r3, #0
	mov		  r4, #0
  ble     L315

L32:
	mov		  r5, r4, lsl #1
	mov		  r6, #0
	ldrsh   r10, [r2, r5]
	add     r5, r2, r5
	mov		  r7, #0
	ldrsh	  r11, [r5, #2]
	cmp     r10, r11
  bge     L39

	str		  r3, [r13, #4]
	str		  r4, [r13, #8]
	str		  r12, [r13, #12]
	str		  r14, [r13, #16]

L33:
	ldr     r8, [r0, +r10, lsl #2]
	ldr     r9, [r1, +r10, lsl #2]
	mov		  r8, r8, asr #1
	add		  r10, r10, #1
	mov		  r9, r9, asr #1

	ldr     r12, [r0, +r10, lsl #2]
	add		  r5, r8, r9
	ldr     r14, [r1, +r10, lsl #2]
	sub		  r8, r8, r9

	smull   r5, r3, r5, r5
	mov		  r12, r12, asr #1
	smull   r8, r4, r8, r8
	mov		  r14, r14, asr #1

	qadd	  r6, r6, r3
	add		  r5, r12, r14
	qadd	  r7, r7, r4
	sub		  r8, r12, r14

	smull   r5, r3, r5, r5
	add		  r10, r10, #1
	smull   r8, r4, r8, r8

	qadd	  r6, r6, r3
	qadd	  r7, r7, r4

	ldr     r8, [r0, +r10, lsl #2]
	ldr     r9, [r1, +r10, lsl #2]
	mov		  r8, r8, asr #1
	add		  r10, r10, #1
	mov		  r9, r9, asr #1

	ldr     r12, [r0, +r10, lsl #2]
	add		  r5, r8, r9
	ldr     r14, [r1, +r10, lsl #2]
	sub		  r8, r8, r9

	smull   r5, r3, r5, r5
	mov		  r12, r12, asr #1
	smull   r8, r4, r8, r8
	mov		  r14, r14, asr #1

	qadd	  r6, r6, r3
	add		  r5, r12, r14
	qadd	  r7, r7, r4
	sub		  r8, r12, r14

	smull   r5, r3, r5, r5
	add		  r10, r10, #1
	smull   r8, r4, r8, r8

	qadd	  r6, r6, r3
	qadd	  r7, r7, r4

	cmp     r10, r11

	blt		  L33

	ldr		  r3, [r13, #4]
	ldr		  r4, [r13, #8]
	ldr		  r12, [r13, #12]
	ldr		  r14, [r13, #16]
L39:
	qadd	  r6, r6, r6
	qadd	  r7, r7, r7

	ldr		  r8, [r13, #60]
	ldr		  r9, [r13, #68]

	qadd	  r12, r12, r6
	qadd	  r14, r14, r7

	str		  r6, [r8, +r4, lsl #2]
	str     r7, [r9, +r4, lsl #2]

	add		  r4, r4, #1
	cmp		  r4, r3
	blt     L32

L315:
	ldr		  r8, [r13, #64]
	ldr		  r9, [r13, #72]
	str		  r12, [r8, #0]
	str		  r14, [r9, #0]

	add     r13, r13, #24
	ldmia   sp!, {r4 - r11, pc}
	@ENDP  ; |CalcBandEnergyMS|

	.end
