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
@	File:		R4R8First_v7.s
@
@	Content:	Radix8First and Radix4First function armv7 assemble
@
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@

	.section .text
	.global	Radix8First

Radix8First:
	stmdb     		sp!, {r4 - r11, lr}

	ldr       		r3, SQRT1_2
	cmp       		r1, #0
	
	VDUP.I32  		Q15, r3	
	beq       		Radix8First_END
	
Radix8First_LOOP:
	VLD1.I32			{d0, d1, d2, d3},	[r0]!
	VLD1.I32			{d8, d9, d10, d11},	[r0]!
		
	VADD.S32			d4, d0, d1		@ r0 = buf[0] + buf[2]@i0 = buf[1] + buf[3]@
	VSUB.S32			d5, d0, d1		@ r1 = buf[0] - buf[2]@i1 = buf[1] - buf[3]@	
	VSUB.S32			d7, d2, d3		@ r2 = buf[4] - buf[6]@i2 = buf[5] - buf[7]@	
	VADD.S32			d6, d2, d3		@ r3 = buf[4] + buf[6]@i3 = buf[5] + buf[7]@
	VREV64.I32			d7, d7	
	
	VADD.S32			Q0, Q2, Q3		@ r4 = (r0 + r2)@i4 = (i0 + i2)@i6 = (i1 + r3)@r7 = (r1 + i3)
	VSUB.S32			Q1, Q2, Q3		@ r5 = (r0 - r2)@i5 = (i0 - i2)@r6 = (r1 - i3)@i7 = (i1 - r3)@

	VREV64.I32			d3, d3	

	VADD.S32			d4, d8, d9		@ r0 = buf[ 8] + buf[10]@i0 = buf[ 9] + buf[11]@
	VSUB.S32			d7, d10, d11	@ r1 = buf[12] - buf[14]@i1 = buf[13] - buf[15]@	
	VADD.S32			d6, d10, d11	@ r2 = buf[12] + buf[14]@i2 = buf[13] + buf[15]@
	VREV64.I32			d7, d7	
	VSUB.S32			d5, d8, d9		@ r3 = buf[ 8] - buf[10]@i3 = buf[ 9] - buf[11]@
	
	VTRN.32				d1, d3	
	
	VADD.S32			Q4, Q2, Q3		@ t0 = (r0 + r2) >> 1@t1 = (i0 + i2) >> 1@i0 = i1 + r3@r2 = r1 + i3@
	VSUB.S32			Q5, Q2, Q3		@ t2 = (r0 - r2) >> 1@t3 = (i0 - i2) >> 1@r0 = r1 - i3@i2 = i1 - r3@
	
	VREV64.I32			d3, d3
	
	VSHR.S32			d8, d8, #1		 
	VSHR.S32			Q0, Q0, #1
	VREV64.I32			d10, d10
	VTRN.32				d11, d9
	VSHR.S32			Q1, Q1, #1
	VSHR.S32			d10, d10, #1
	VREV64.I32			d9, d9
	
	sub       			r0, r0, #0x40
	
	VADD.S32			d12, d0, d8
	VSUB.S32			d16, d0, d8	
	VADD.S32			d14, d2, d10
	VSUB.S32			d18, d2, d10
	
	VSUB.S32			d4, d11, d9
	VADD.S32			d5, d11, d9
	
	VREV64.I32			d18, d18
	
	VQDMULH.S32			Q3, Q2, Q15
	VTRN.32				d14, d18
	VTRN.32				d6, d7
	VREV64.I32			d18, d18	
	
	VSUB.S32			d15, d3, d6
	VREV64.I32			d7, d7
	VADD.S32			d19, d3, d6
	VADD.S32			d13, d1, d7
	VSUB.S32			d17, d1, d7
	
	VREV64.I32			d17, d17
	VTRN.32				d13, d17
	VREV64.I32			d17, d17
	
	subs       			r1, r1, #1	
	
	VST1.I32			{d12, d13, d14, d15}, [r0]!
	VST1.I32			{d16, d17, d18, d19}, [r0]!	
	bne       			Radix8First_LOOP
	
Radix8First_END:
	ldmia     sp!, {r4 - r11, pc}	
SQRT1_2:
	.word      0x2d413ccd
	
	@ENDP  @ |Radix8First|
	
	.section .text
	.global	Radix4First

Radix4First:
	stmdb     	sp!, {r4 - r11, lr}

	cmp       	r1, #0
	beq       	Radix4First_END
	
Radix4First_LOOP:
	VLD1.I32			{d0, d1, d2, d3}, [r0]					
	
	VADD.S32			d4, d0, d1							@ r0 = buf[0] + buf[2]@ r1 = buf[1] + buf[3]@		
	VSUB.S32			d5, d0, d1							@ r2 = buf[0] - buf[2]@ r3 = buf[1] - buf[3]@
	VSUB.S32			d7, d2, d3							@ r4 = buf[4] + buf[6]@ r5 = buf[5] + buf[7]@
	VADD.S32			d6, d2, d3							@ r6 = buf[4] - buf[6]@ r7 = buf[5] - buf[7]@
	
	VREV64.I32		d7, d7									@ 
	
	VADD.S32			Q4, Q2, Q3
	VSUB.S32			Q5, Q2, Q3
	
	VREV64.I32		d11, d11
	VTRN.32				d9, d11
	subs       		r1, r1, #1	
	VREV64.I32		d11, d11
	VST1.I32			{d8, d9, d10, d11}, [r0]!

	bne       		Radix4First_LOOP
	
Radix4First_END:
	ldmia    		sp!, {r4 - r11, pc}

	@ENDP  @ |Radix4First|
	.end