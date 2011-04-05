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
@	File:		AutoCorrelation_v5.s
@
@	Content:	AutoCorrelation function armv5 assemble
@
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@


	.section .text
	.global	AutoCorrelation

AutoCorrelation:
	stmdb     sp!, {r4 - r11, lr}

  sub     r13, r13, #20

  mov     r5, r0
  mov     r7, r1
  mov     r9, r3
  mov     r2, r2, lsl #16
  mov     r0, #0
  mov     r4, r2, asr #16
  mov     r8, #0
  cmp     r4, #0
  ble     L136

	cmp     r4, #8
	mov		  r2, #0
  blt     L133

	sub     r12, r4, #8
L132:
  ldr     r6, [r5, r2]
	add		  r2, r2, #4
	smulbb  r3, r6, r6
	ldr     r1, [r5, r2]
	smultt	r10, r6, r6
	mov		  r3, r3, asr #9
	smulbb	r6, r1, r1
	mov		  r10, r10, asr #9
	qadd	  r0, r0, r3
	smultt	r11, r1, r1
	add     r2, r2, #4
	qadd	  r0, r0, r10
	mov		  r6, r6, asr #9
	mov		  r11, r11, asr #9
	ldr		  r1, [r5, r2]
	qadd	  r0, r0, r6
	smulbb	r10, r1, r1
	smultt	r6, r1, r1
	qadd	  r0, r0, r11
	mov		  r10, r10, asr #9
	mov		  r6, r6, asr #9
	qadd	  r0, r0, r10
	add     r2, r2, #4
	add     r8, r8, #6

	qadd	  r0, r0, r6
	cmp     r8, r12
  blt     L132
L133:
  ldrsh   r6, [r5, r2]
  mul     r10, r6, r6
	add     r2, r2, #2
  mov     r1, r10, asr #9
  qadd    r0, r0, r1
L134:
  add     r8, r8, #1
  cmp     r8, r4
  blt     L133
L135:
L136:
  str     r0, [r7, #0]
  cmp     r0, #0
  beq     L1320
L137:
  mov     r2, r9, lsl #16
	mov     r8, #1
  mov     r2, r2, asr #16
  cmp     r2, #1
  ble     L1319
L138:
L139:
  sub     r4, r4, #1
  mov     r14, #0
  mov     r3, #0
  cmp     r4, #0
  ble     L1317
L1310:
  cmp     r4, #6
  addlt   r6, r5, r8, lsl #1
  blt     L1314
L1311:
  add     r6, r5, r8, lsl #1
  sub     r12, r4, #6
  str     r8, [r13, #8]
  str     r7, [r13, #4]
L1312:
  mov     r1, r3, lsl #1
  ldrsh   r7, [r6, r1]
  ldrsh   r10, [r5, r1]
  add     r8, r1, r6
	add     r9, r5, r1
	mul     r7, r10, r7
  ldrsh   r1, [r8, #2]
	ldrsh   r10, [r8, #4]
  add     r7, r14, r7, asr #9
  ldrsh   r0, [r9, #2]
  ldrsh   r11, [r9, #4]
  mul     r1, r0, r1
  ldrsh   r14, [r8, #6]
  mul     r10, r11, r10
	add     r7, r7, r1, asr #9
  ldrsh   r8, [r8, #8]
	add     r3, r3, #5
	ldrsh   r11, [r9, #6]
  ldrsh   r1, [r9, #8]
  mul     r14, r11, r14
  add     r7, r7, r10, asr #9
  mul     r1, r1, r8
  add     r14, r7, r14, asr #9
	cmp     r3, r12
  add     r14, r14, r1, asr #9
  ble     L1312
L1313:
  ldr     r8, [r13, #8]
  ldr     r7, [r13, #4]
L1314:
L1315:
  mov     r12, r3, lsl #1
  ldrsh   r9, [r6, r12]
  ldrsh   r12, [r5, r12]
  add     r3, r3, #1
  cmp     r3, r4
  mul     r12, r12, r9
  add     r14, r14, r12, asr #9
  blt     L1315
L1316:
L1317:
  str     r14, [r7, +r8, lsl #2]
  add     r8, r8, #1
  cmp     r8, r2
  blt     L139

L1319:
L1320:
	add     r13, r13, #20
	ldmia   sp!, {r4 - r11, pc}

	@ENDP  @ |AutoCorrelation|
	.end
