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
@	File:		CalcWindowEnergy_v5.s
@
@	Content:	CalcWindowEnergy function armv5 assemble
@
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@

	.section .text

	.global	CalcWindowEnergy

CalcWindowEnergy:
	stmdb   sp!, {r4 - r11, lr}
	sub     r13, r13, #20

  mov     r3, r3, lsl #16
	ldr     r10, [r0, #168]                    @ states0 = blockSwitchingControl->iirStates[0];
  mov     r3, r3, asr #16
	ldr     r11, [r0, #172]                    @ states1 = blockSwitchingControl->iirStates[1];

	mov     r2, r2, lsl #16
	ldr     r12, hiPassCoeff                   @ Coeff0 = hiPassCoeff[0];
  mov     r2, r2, asr #16
	ldr     r14, hiPassCoeff + 4			         @ Coeff1 = hiPassCoeff[1];

	mov			r8, #0							               @ w=0
	mov			r5, #0							               @ wOffset = 0;

BLOCK_BEGIN:
	mov			r6, #0                             @ accuUE = 0;
	mov			r7, #0								             @ accuFE = 0;
	mov			r4, #0							               @ i=0

	str			r8, [r13, #4]
	str			r0, [r13, #8]
	str			r3, [r13, #12]

ENERGY_BEG:
	mov     r9, r5, lsl #1
	ldrsh   r9, [r1, r9]											@ tempUnfiltered = timeSignal[tidx];

	add			r5, r5, r2												@ tidx = tidx + chIncrement;

	smulwb	r3, r14, r9												@ accu1 = L_mpy_ls(Coeff1, tempUnfiltered);
	smull		r0, r8, r12, r11									@ accu2 = fixmul( Coeff0, states1 );

	mov			r3, r3, lsl #1
	mov			r8, r8, lsl #1

	sub			r0, r3, r10												@ accu3 = accu1 - states0;
	sub			r8,	r0, r8												@ out = accu3 - accu2;

	mov		  r10, r3														@ states0 = accu1;
	mov		  r11, r8														@ states1 = out;

	mul		  r3, r9, r9
	mov     r8, r8, asr #16

	add		  r4, r4, #1
	add     r6, r6, r3, asr #7

	mul		  r9, r8, r8
	ldr		  r3, [r13, #12]

	add		  r7, r7, r9, asr #7

	cmp     r4, r3
  blt     ENERGY_BEG

	ldr		  r0, [r13, #8]
	ldr		  r8, [r13, #4]

ENERGY_END:
	add		  r4, r0, r8, lsl #2

	str     r6, [r4, #72]
	add		  r8, r8, #1
  str     r7, [r4, #136]

	cmp		  r8, #8
	blt		  BLOCK_BEGIN

BLOCK_END:
	str     r10, [r0, #168]
  str     r11, [r0, #172]
  mov     r0, #1

  add     r13, r13, #20
	ldmia   sp!, {r4 - r11, pc}

hiPassCoeff:
	.word 0xbec8b439
	.word	0x609d4952

	@ENDP
	.end
